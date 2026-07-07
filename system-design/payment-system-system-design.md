# Payment System — System Design

> **Core challenge:** move money **correctly** — never double-charge, never lose a payment, always know the exact state — integrating external **payment gateways/banks** that are slow, async, and occasionally fail. The heart is **idempotency**, a **double-entry ledger**, **async webhooks + reconciliation**, and **exactly-once financial effect**.

---

## Contents

- [1. Mental Model](#1-mental-model)
- [2. Requirements](#2-requirements)
- [3. Payment Flow](#3-payment-flow)
- [4. Idempotency (never double-charge)](#4-idempotency-never-double-charge)
- [5. Double-Entry Ledger](#5-double-entry-ledger)
- [6. Async Gateways, Webhooks & Reconciliation](#6-async-gateways-webhooks--reconciliation)
- [7. Payment State Machine](#7-payment-state-machine)
- [8. Refunds, Payouts & Wallets](#8-refunds-payouts--wallets)
- [9. Data Model (all tables)](#9-data-model-all-tables)
- [10. Design Patterns (that can be used)](#10-design-patterns-that-can-be-used)
- [11. Consistency, Security & Failure](#11-consistency-security--failure)
- [12. Interview Cheat Sheet](#12-interview-cheat-sheet)
- [13. Final Takeaways](#13-final-takeaways)

---

## 1. Mental Model

```
Client → Payment Service → Payment Gateway (Stripe/Razorpay) → Bank/Card network
                     │  record every state change in a durable LEDGER
                     ◄── async webhook confirms final status
```

Correctness > latency. The two commandments: **never double-charge** (idempotency) and **money is never created or destroyed** (double-entry ledger, everything balances).

---

## 2. Requirements

**Functional**
- Initiate a payment (card/UPI/wallet/netbanking); capture result; **refunds**.
- Support **wallets/balances**, **payouts** (to merchants/sellers).
- Full **audit trail**; reconciliation with the gateway/bank.

**Non-functional**
- **Correctness & consistency** (strong); **idempotent** (safe retries); **durable** (no lost txn); **auditable**; secure (PCI); highly available.

---

## 3. Payment Flow

```
1. Client → Payment Service: POST /payments (Idempotency-Key, amount, method)
2. Service creates payment record (status = INITIATED) + ledger entries (pending)
3. Service calls the Payment Gateway (charge)  [circuit breaker, timeout]
4. Gateway responds sync (PENDING/SUCCESS/FAIL) — but truth often comes LATER via webhook
5. On webhook (SUCCESS/FAIL): update payment status + post final ledger entries
6. Notify the ordering service (event) → order proceeds
```

- Treat the sync response as provisional; the **webhook** (or a status poll) is the source of truth.

---

## 4. Idempotency (never double-charge)

Retries are inevitable (timeouts, client re-taps). Every payment carries a client-supplied **idempotency key**.

```
POST /payments  Idempotency-Key: <uuid>
  if key seen → return the SAME result (do NOT charge again)
  else → process once, store result under the key
```

- Enforced by a **unique constraint** on the idempotency key.
- The gateway call itself must be idempotent too (pass an idempotency key to Stripe/Razorpay).
- Combined with at-least-once webhooks → **exactly-once financial effect**.

---

## 5. Double-Entry Ledger

The foundation of financial correctness: **every transaction is recorded as balanced debits and credits** across accounts. Sum of all entries = 0, always.

```
User pays ₹500 for an order:
  DEBIT  user_wallet      -500
  CREDIT merchant_payable +500
(entries always balance → money is conserved and auditable)
```

| Property | Why |
| --- | --- |
| **Append-only** | Never mutate a posted entry; corrections are new entries (reversal) |
| **Balanced** | Debits = credits per transaction → detect corruption |
| **Auditable** | Full history; derive any balance by summing entries |
| **Immutable** | Source of truth for disputes/reconciliation |

> Account **balances** are derived from (or cached from) ledger entries; the **ledger is the truth**.

---

## 6. Async Gateways, Webhooks & Reconciliation

Gateways are async and unreliable — design for it.

| Mechanism | Purpose |
| --- | --- |
| **Webhooks** | Gateway calls back with final status; **verify signature**, **dedup** (at-least-once) |
| **Status polling** | Fallback if webhook missed — poll pending payments |
| **Reconciliation job** | Periodically compare your records vs the gateway's settlement report; fix mismatches |
| **Outbox** | Emit "payment succeeded" events reliably (no dual-write loss) |
| **Timeouts + retries** | Circuit breaker around gateway calls; retry with backoff |

> **Reconciliation** is the safety net: a daily settlement file from the gateway is compared to your ledger; discrepancies (missed webhook, stuck PENDING) are auto-resolved or flagged.

---

## 7. Payment State Machine

```
INITIATED ─► PENDING ─(webhook)─► SUCCESS ─► (settled)
     │           │                   
     │           └──────────► FAILED
     └─ timeout ─► PENDING (poll/reconcile) 
SUCCESS ─(refund)─► REFUNDED / PARTIALLY_REFUNDED
```

Every transition is durably recorded; stuck `PENDING` payments are resolved by polling/reconciliation (never left dangling).

---

## 8. Refunds, Payouts & Wallets

- **Refund** = a new ledger transaction reversing the original (full/partial); call gateway refund; idempotent.
- **Payout** = settle merchant/seller balances (escrow → payout minus fees); batched; ledger-tracked.
- **Wallet** = an account in the ledger; top-up/spend are balanced entries; strong consistency on balance changes (atomic).

---

## 9. Data Model (all tables)

```sql
CREATE TABLE payments (
    payment_id      BIGINT PRIMARY KEY,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,   -- never double-charge
    user_id         BIGINT, order_id BIGINT,
    amount          BIGINT NOT NULL,               -- minor units (paise/cents)
    currency        CHAR(3) NOT NULL,
    method          VARCHAR(20),                    -- CARD, UPI, WALLET, NETBANKING
    status          VARCHAR(20) NOT NULL,           -- INITIATED, PENDING, SUCCESS, FAILED, REFUNDED
    gateway         VARCHAR(30), gateway_ref VARCHAR(255),
    created_at      TIMESTAMP DEFAULT now(), updated_at TIMESTAMP
);

-- Double-entry ledger (the source of truth)
CREATE TABLE accounts ( account_id BIGINT PRIMARY KEY, owner_id BIGINT, type VARCHAR(20), currency CHAR(3), balance BIGINT DEFAULT 0 );
CREATE TABLE ledger_transactions ( txn_id BIGINT PRIMARY KEY, payment_id BIGINT, type VARCHAR(20), created_at TIMESTAMP );
CREATE TABLE ledger_entries (
    entry_id   BIGINT PRIMARY KEY,
    txn_id     BIGINT NOT NULL REFERENCES ledger_transactions(txn_id),
    account_id BIGINT NOT NULL,
    direction  CHAR(1) NOT NULL,        -- 'D' debit / 'C' credit
    amount     BIGINT NOT NULL,         -- >0; sum(debits)=sum(credits) per txn
    created_at TIMESTAMP DEFAULT now()
);
CREATE INDEX idx_entries_account ON ledger_entries(account_id, created_at);

CREATE TABLE refunds ( refund_id BIGINT PRIMARY KEY, payment_id BIGINT, amount BIGINT, status VARCHAR(20),
                       idempotency_key VARCHAR(255) UNIQUE );
CREATE TABLE payouts ( payout_id BIGINT PRIMARY KEY, merchant_id BIGINT, amount BIGINT, status VARCHAR(20), batch_id BIGINT );
CREATE TABLE webhook_events (              -- dedup + audit of gateway callbacks
    event_id VARCHAR(255) PRIMARY KEY,     -- gateway event id (idempotency)
    payment_id BIGINT, type VARCHAR(30), payload JSONB, processed BOOLEAN DEFAULT FALSE, received_at TIMESTAMP
);
CREATE TABLE outbox ( id BIGINT PRIMARY KEY, event_type VARCHAR(50), payload JSONB, published BOOLEAN DEFAULT FALSE );
CREATE TABLE reconciliation_log ( id BIGINT PRIMARY KEY, run_date DATE, mismatches INT, details JSONB );
```

> **Tables to consider:** payments, accounts, ledger_transactions, ledger_entries (double-entry core), refunds, payouts, webhook_events (dedup), outbox, reconciliation_log, payment_methods (tokenized), disputes/chargebacks.

---

## 10. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Idempotency Key** | Payment/refund creation + gateway calls | Never double-charge |
| **Double-Entry Ledger** | Ledger entries | Financial correctness/audit |
| **State** | Payment lifecycle | Guard transitions |
| **Saga / Orchestration** | Order↔payment↔fulfillment with compensation (refund) | Distributed txn |
| **Outbox** | Reliable payment events | No dual-write loss |
| **Circuit Breaker + Retry** | Gateway calls | Handle slow/failing providers |
| **Observer / Pub-Sub** | Payment events → order, notification, ledger | Decouple |
| **Ports & Adapters** | Gateway abstraction (Stripe/Razorpay/PayPal) | Swap providers |
| **Reconciliation (batch)** | Settlement vs ledger | Catch/fix mismatches |
| **Strategy** | Method-specific handling, refund policy | Swap logic |

---

## 11. Consistency, Security & Failure

- **Strong consistency** on payment/ledger writes (ACID); balance updates atomic with ledger entries.
- **Exactly-once effect** = idempotency key + at-least-once webhooks (deduped by event id) + reconciliation.
- **Security/PCI**: never store raw card data — **tokenize** via the gateway; TLS; audit; least privilege.
- **Failure handling:** timeout on charge → status stays PENDING → poll/reconcile (never assume failed). Missed webhook → reconciliation catches it. Crash between charge and record → outbox/reconciliation resolves.
- **Disputes/chargebacks** → ledger reversal entries + case tracking.

---

## 12. Interview Cheat Sheet

> **"How do you avoid double-charging?"**
> "Client-supplied **idempotency key** with a unique constraint — a retry returns the original result without charging again. Pass the key to the gateway too, and dedup webhooks by event id. Idempotency + at-least-once webhooks = exactly-once financial effect."

> **"How do you guarantee financial correctness?"**
> "A **double-entry ledger** — every transaction is balanced debits/credits, append-only and immutable; balances are derived from entries. Money can't be created or lost, and everything is auditable."

> **"Gateways are async and flaky — how do you handle that?"**
> "Treat the sync response as provisional; the **webhook** (signature-verified, deduped) is the truth, with **status polling** as a fallback and a daily **reconciliation** job against the settlement report. Circuit breaker + retries around gateway calls; outbox for reliable events."

> **"A charge times out — is the money gone?"**
> "Unknown → leave the payment PENDING and resolve via polling/reconciliation; never assume success or failure. Idempotency ensures a retry doesn't double-charge."

---

## 13. Final Takeaways

- Two commandments: **never double-charge** (idempotency key) and **money is conserved** (double-entry ledger, append-only, balanced).
- Treat gateway sync responses as provisional; **webhooks (deduped) = truth**, **polling + reconciliation** = safety nets.
- **Exactly-once effect** = idempotency + at-least-once webhooks + reconciliation.
- **Strong consistency** on money writes; **outbox** for reliable events; **saga** for order↔payment↔refund.
- **PCI**: tokenize, never store raw cards.
- Patterns: Idempotency, Double-Entry Ledger, State, Saga, Outbox, Circuit Breaker, Reconciliation, Ports&Adapters.

### Related notes

- [Idempotency](../concepts/idempotency.md) · [Outbox & Saga](../concepts/outbox-and-saga.md) — the core reliability patterns here
- [BookMyShow — System Design](bookmyshow-system-design.md) · [Food Ordering — HLD & LLD](food-ordering-hld-lld.md) — payment integration in context
