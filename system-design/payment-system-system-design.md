# Payment System — System Design

> **Core challenge:** move money **correctly** — never double-charge, never lose a payment, always know the exact state — while integrating external **payment gateways/banks** that are slow, async, and occasionally fail. The heart is **idempotency**, a **double-entry ledger**, **auth/capture**, **async webhooks + reconciliation**, and an **exactly-once financial effect**.

---

## Contents

- [1. Mental Model & Players](#1-mental-model--players)
- [2. Requirements](#2-requirements)
- [3. Capacity Estimation](#3-capacity-estimation)
- [4. Architecture](#4-architecture)
- [5. Payment Flow (auth vs capture)](#5-payment-flow-auth-vs-capture)
- [6. Idempotency (never double-charge)](#6-idempotency-never-double-charge)
- [7. Double-Entry Ledger](#7-double-entry-ledger)
- [8. Async Gateways, Webhooks & Reconciliation](#8-async-gateways-webhooks--reconciliation)
- [9. Payment State Machine](#9-payment-state-machine)
- [10. Refunds, Payouts & Wallets](#10-refunds-payouts--wallets)
- [11. Security & PCI](#11-security--pci)
- [12. Data Model (all tables)](#12-data-model-all-tables)
- [13. Sequences](#13-sequences)
- [14. Consistency & Failure](#14-consistency--failure)
- [15. Design Patterns (that can be used)](#15-design-patterns-that-can-be-used)
- [16. Interview Cheat Sheet](#16-interview-cheat-sheet)
- [17. Final Takeaways](#17-final-takeaways)

---

## 1. Mental Model & Players

```
Client → Payment Service → Payment Gateway/PSP (Stripe/Razorpay) → Card Network (Visa) → Issuing Bank
                     │  record every state change in a durable double-entry LEDGER
                     ◄── async WEBHOOK confirms the final status (truth arrives later)
```

| Player | Role |
| --- | --- |
| **Payment Service** (you) | Orchestrate, persist state, ledger, idempotency |
| **PSP / Gateway** (Stripe, Razorpay) | Talks to networks/banks; tokenizes cards; sends webhooks |
| **Acquirer** | The merchant's bank that receives funds |
| **Card network** (Visa/Mastercard/UPI) | Routes the authorization |
| **Issuing bank** | The customer's bank that approves/declines |

**Two commandments:** **never double-charge** (idempotency) and **money is never created or destroyed** (double-entry ledger — everything balances). Correctness > latency.

---

## 2. Requirements

**Functional**
- Initiate a payment (card/UPI/wallet/netbanking); **authorize then capture**; **refunds**.
- **Wallets/balances**, **payouts** to merchants/sellers, multi-currency.
- Full **audit trail**; **reconciliation** with gateway/bank; disputes/chargebacks.

**Non-functional**
- **Correctness & strong consistency** on money writes; **idempotent** (safe retries); **durable** (no lost txn); **auditable**; **secure (PCI)**; highly available.

---

## 3. Capacity Estimation

```
Payments ~ 10M/day (large merchant) → ~120/sec avg, 5–10× peaks (sales)
Reads (status polls, history) ≫ writes
Ledger entries: ≥ 2 per transaction (often more with fees/taxes/payouts) → 2–5× payment rows
Storage: financial data retained for YEARS (compliance) → partition + archive, never delete
```

> Volume is modest vs a feed system, but **every write must be ACID + auditable + retained**. The hard part is correctness under retries, async gateways, and partial failures — not raw throughput.

---

## 4. Architecture

```
Client → API Gateway
  ├── Payment Service      → RDBMS (payments, ledger) — STRONG consistency
  ├── Gateway Adapter(s)   → PSP APIs (Stripe/Razorpay) behind a Port + circuit breaker
  ├── Webhook Handler      → verifies + dedups gateway callbacks
  ├── Ledger Service       → double-entry accounts/entries (source of truth)
  ├── Reconciliation Job   → settlement file vs ledger (batch)
  └── Payout Service       → merchant settlements (batched)
             │
          Kafka / Outbox (PAYMENT_SUCCESS/FAILED, REFUND_INITIATED → order svc, notifications, analytics)
```

- **RDBMS with ACID** for payments + ledger; **outbox** so events aren't lost; **vault/PSP** holds card data (you don't).

---

## 5. Payment Flow (auth vs capture)

Card payments are often **two-phase**: **authorize** (hold funds) then **capture** (take them).

```
1. Client → Payment Service: POST /payments (Idempotency-Key, amount, method/token)
2. Service: create payment (INITIATED) + pending ledger entries; call PSP.authorize()  [timeout, circuit breaker]
3. AUTHORIZED → funds held on the card (not yet taken)
4. CAPTURE (immediately, or later e.g. when an order ships) → money actually moves
5. Truth confirmed via async WEBHOOK (SUCCESS/FAIL) → update status + post final ledger entries
6. Emit PAYMENT_SUCCESS (outbox → Kafka) → order proceeds
```

| Phase | Meaning | Use |
| --- | --- | --- |
| **Authorize** | Reserve/hold funds on the card | Verify funds without charging yet |
| **Capture** | Actually transfer the held funds | On fulfillment/ship (e-commerce), or immediately |
| **Void** | Cancel an auth before capture | Order cancelled pre-capture (no refund needed) |

> Treat the **sync response as provisional**; the **webhook** (or a status poll) is the source of truth. Auth+capture lets you verify funds upfront and only take money when you deliver.

---

## 6. Idempotency (never double-charge)

Retries are inevitable (timeouts, client re-taps, at-least-once webhooks). Every payment carries a client-supplied **idempotency key**.

```
POST /payments  Idempotency-Key: <uuid>
  BEGIN
    row = INSERT payments(idempotency_key, status=IN_PROGRESS) ON CONFLICT DO NOTHING
    if conflict (key seen):
        existing = SELECT by key
        if existing.status == IN_PROGRESS → return 409 / retry-after   (a request is in flight)
        else → return the SAME stored result                          (already processed)
    else:
        process once (call gateway) → store result under the key
```

- **Unique constraint** on `idempotency_key` = the DB-level guarantee.
- An **`IN_PROGRESS` marker** prevents two concurrent requests with the same key from both charging (the second sees in-progress and waits/retries).
- **Pass an idempotency key to the PSP too** (Stripe/Razorpay support it) so *its* retries don't double-charge.
- **Dedup webhooks** by gateway `event_id`.
- **Idempotency + at-least-once webhooks + reconciliation = exactly-once financial effect.**

---

## 7. Double-Entry Ledger

The foundation of financial correctness: **every transaction is recorded as balanced debits and credits** across accounts. The sum of all entries in a transaction = 0, always.

```
User pays ₹500 for an order (with a ₹50 platform fee):
  txn:
    DEBIT   user_payment_source   500
    CREDIT  merchant_payable      450
    CREDIT  platform_revenue       50
  (debits 500 == credits 500 → balanced → money conserved)
```

| Property | Why |
| --- | --- |
| **Append-only** | Never mutate a posted entry; corrections are **new reversal entries** |
| **Balanced** | Debits = credits per txn → any imbalance = corruption alarm |
| **Auditable** | Full history; derive any balance by summing entries |
| **Immutable** | The source of truth for disputes/reconciliation |

- **Account balances** are derived from (or cached snapshots of) ledger entries; the **ledger is the truth**.
- A **refund/chargeback** is a *new* transaction that reverses the original — you never edit history.
- Balance updates and their ledger entries commit in **one ACID transaction**.

---

## 8. Async Gateways, Webhooks & Reconciliation

Gateways are async and unreliable — design for it.

| Mechanism | Purpose |
| --- | --- |
| **Webhooks** | Gateway calls back with final status; **verify signature**, **dedup by event_id** (at-least-once) |
| **Status polling** | Fallback when a webhook is missed — poll pending payments' status |
| **Reconciliation job** | Daily: compare the gateway's **settlement report** vs your ledger; auto-fix/flag mismatches |
| **Outbox** | Emit "payment succeeded" events reliably (no dual-write loss) |
| **Timeouts + circuit breaker + retry** | Around gateway calls; back off on provider degradation |

> **Reconciliation is the ultimate safety net:** the settlement file is the bank's truth. Any discrepancy — a missed webhook, a stuck `PENDING`, a mismatched amount — is caught daily and resolved. Never trust a single signal.

---

## 9. Payment State Machine

```
INITIATED ─► AUTHORIZED ─capture→ CAPTURED/SUCCESS ─► (settled)
    │            │ void                 │ refund
    │            ▼                       ▼
    │        VOIDED               REFUNDED / PARTIALLY_REFUNDED
    ├─► PENDING ─(webhook)─► SUCCESS | FAILED
    └─ timeout ─► PENDING (poll/reconcile → never left dangling)
                                        └─ chargeback → DISPUTED (ledger reversal)
```

Every transition is durably recorded; stuck `PENDING` payments are **always** resolved by polling/reconciliation — never abandoned.

---

## 10. Refunds, Payouts & Wallets

- **Refund** = a new ledger transaction **reversing** the original (full/partial); call gateway refund; **idempotent** (own key).
- **Payout** = settle merchant/seller balances (escrow → payout minus fees); **batched**; ledger-tracked; scheduled.
- **Wallet** = an account in the ledger; top-up/spend are balanced entries; **atomic** balance changes (strong consistency; a wallet can't go negative → conditional debit).
- **Chargeback/dispute** = network-initiated reversal → ledger reversal + case tracking + evidence.

---

## 11. Security & PCI

| Concern | Approach |
| --- | --- |
| **Never store raw card data** | **Tokenize** via the PSP — you store a token, not the PAN; keeps you out of PCI scope |
| **Vault** | Secrets/keys in a vault; rotate; least privilege |
| **In transit** | TLS everywhere; **mTLS** for internal service calls |
| **Auth** | Strong auth on payment APIs; signed webhooks (verify signature) |
| **3-D Secure (3DS)** | Extra bank-side auth (OTP) to shift fraud liability |
| **Fraud** | Rate limits, velocity checks, ML scoring, block/allow lists |
| **Audit** | Immutable ledger + access logs (compliance/retention) |

---

## 12. Data Model (all tables)

```sql
CREATE TABLE payments (
    payment_id      BIGINT PRIMARY KEY,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,   -- never double-charge
    user_id BIGINT, order_id BIGINT,
    amount BIGINT NOT NULL,                          -- minor units (paise/cents)
    currency CHAR(3) NOT NULL,
    method VARCHAR(20),                              -- CARD, UPI, WALLET, NETBANKING
    status VARCHAR(20) NOT NULL,                     -- INITIATED, AUTHORIZED, PENDING, SUCCESS, FAILED, VOIDED, REFUNDED, DISPUTED
    gateway VARCHAR(30), gateway_ref VARCHAR(255), auth_ref VARCHAR(255),
    created_at TIMESTAMP DEFAULT now(), updated_at TIMESTAMP
);

-- Double-entry ledger (the source of truth)
CREATE TABLE accounts ( account_id BIGINT PRIMARY KEY, owner_id BIGINT, type VARCHAR(30), currency CHAR(3), balance BIGINT DEFAULT 0 );
CREATE TABLE ledger_transactions ( txn_id BIGINT PRIMARY KEY, payment_id BIGINT, type VARCHAR(20), created_at TIMESTAMP );
CREATE TABLE ledger_entries (
    entry_id   BIGINT PRIMARY KEY,
    txn_id     BIGINT NOT NULL REFERENCES ledger_transactions(txn_id),
    account_id BIGINT NOT NULL,
    direction  CHAR(1) NOT NULL,        -- 'D' debit / 'C' credit
    amount     BIGINT NOT NULL,         -- >0; sum(debits) == sum(credits) per txn
    created_at TIMESTAMP DEFAULT now()
);
CREATE INDEX idx_entries_account ON ledger_entries(account_id, created_at);

CREATE TABLE refunds ( refund_id BIGINT PRIMARY KEY, payment_id BIGINT, amount BIGINT, status VARCHAR(20), idempotency_key VARCHAR(255) UNIQUE );
CREATE TABLE payouts ( payout_id BIGINT PRIMARY KEY, merchant_id BIGINT, amount BIGINT, status VARCHAR(20), batch_id BIGINT, release_at TIMESTAMP );
CREATE TABLE webhook_events (              -- dedup + audit of gateway callbacks
    event_id VARCHAR(255) PRIMARY KEY,     -- gateway event id (idempotency)
    payment_id BIGINT, type VARCHAR(30), payload JSONB, processed BOOLEAN DEFAULT FALSE, received_at TIMESTAMP
);
CREATE TABLE payment_methods ( method_id BIGINT PRIMARY KEY, user_id BIGINT, type VARCHAR(20), token VARCHAR(255), last4 CHAR(4) ); -- tokenized
CREATE TABLE disputes ( dispute_id BIGINT PRIMARY KEY, payment_id BIGINT, reason TEXT, status VARCHAR(20), amount BIGINT );
CREATE TABLE outbox ( id BIGINT PRIMARY KEY, event_type VARCHAR(50), payload JSONB, published BOOLEAN DEFAULT FALSE );
CREATE TABLE reconciliation_log ( id BIGINT PRIMARY KEY, run_date DATE, mismatches INT, details JSONB );
```

> **Tables to consider:** payments, accounts, ledger_transactions, **ledger_entries** (double-entry core), refunds, payouts, webhook_events (dedup), payment_methods (tokenized), disputes/chargebacks, outbox, reconciliation_log.

---

## 13. Sequences

### Happy path (auth + capture)

```
Client  PaymentSvc  DB(ledger)  PSP   Webhook  Kafka
  │ pay  │            │          │       │       │
  ├─────►│ INSERT payment(IN_PROGRESS, idem key) │
  │      ├─ pending ledger entries ─►│           │
  │      ├─ authorize ─────────────►│ AUTHORIZED │
  │      ├─ capture ───────────────►│ (async)    │
  │◄─ 202 PENDING ──────────────────┤            │
  │      │            │   ...PSP processes...     │
  │      │◄──────────── webhook SUCCESS (verify sig, dedup event_id) ─┤
  │      ├─ status=SUCCESS; post final balanced ledger entries        │
  │      ├─ outbox → PAYMENT_SUCCESS ────────────────────────────────►│ → order proceeds
```

### Timeout → reconcile

```
charge times out → status stays PENDING (never assume success/failed)
poll PSP status  OR  daily reconciliation vs settlement file → resolve to SUCCESS/FAILED
idempotency ensures any retry doesn't double-charge
```

---

## 14. Consistency & Failure

| Concern | Handling |
| --- | --- |
| Double-charge on retry | Idempotency key (unique + IN_PROGRESS marker); PSP idempotency key too |
| Duplicate webhook | Dedup by `event_id` |
| Money created/lost | Double-entry ledger (balanced, append-only) — imbalance = alarm |
| Charge timeout (unknown) | Leave PENDING → poll/reconcile; never assume |
| Missed webhook | Reconciliation vs settlement file catches it |
| Crash between charge & record | Outbox/reconciliation resolves; idempotency prevents dup |
| Provider down | Circuit breaker → fail fast/queue; retry with backoff |
| Wallet overspend | Conditional debit (`WHERE balance >= amount`) — can't go negative |
| Chargeback | Ledger reversal + dispute case |

- **Strong consistency (ACID)** on payment + ledger writes; balance and its entries commit together.

---

## 15. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Idempotency Key** | Payment/refund creation + gateway calls | Never double-charge |
| **Double-Entry Ledger** | Ledger entries | Financial correctness/audit |
| **State** | Payment lifecycle (auth/capture/refund) | Guard transitions |
| **Saga / Orchestration** | Order↔payment↔fulfillment with compensation (refund) | Distributed txn |
| **Outbox** | Reliable payment events | No dual-write loss |
| **Circuit Breaker + Retry** | Gateway calls | Handle slow/failing providers |
| **Observer / Pub-Sub** | Payment events → order, notification, analytics | Decouple |
| **Ports & Adapters** | Gateway abstraction (Stripe/Razorpay/PayPal) | Swap providers |
| **Reconciliation (batch)** | Settlement vs ledger | Catch/fix mismatches |
| **Strategy** | Method-specific handling, refund policy | Swap logic |

---

## 16. Interview Cheat Sheet

> **"How do you avoid double-charging?"**
> "Client **idempotency key** with a unique constraint + an `IN_PROGRESS` marker so concurrent retries don't both charge; pass the key to the PSP too; dedup webhooks by event_id. Idempotency + at-least-once webhooks + reconciliation = exactly-once financial effect."

> **"How do you guarantee financial correctness?"**
> "A **double-entry ledger** — every transaction is balanced debits/credits, append-only and immutable; balances derived from entries. Money can't be created or lost; refunds/chargebacks are reversal entries, never edits."

> **"Auth vs capture?"**
> "Authorize holds funds (verifies the card) without taking them; capture actually moves the money (e.g. when the order ships); void cancels an auth before capture. Lets you confirm funds upfront and charge only on delivery."

> **"Gateways are async and flaky — how?"**
> "Sync response is provisional; the **signature-verified, deduped webhook** is truth, with **status polling** as fallback and a **daily reconciliation** against the settlement file. Circuit breaker + retries; outbox for reliable events."

> **"A charge times out — is the money gone?"**
> "Unknown → leave it PENDING and resolve via poll/reconcile; never assume. Idempotency ensures a retry doesn't double-charge."

> **"PCI?"**
> "Never store raw card data — tokenize via the PSP (store a token + last4), TLS/mTLS, vault secrets, 3DS for liability shift, immutable audit."

---

## 17. Final Takeaways

- Two commandments: **never double-charge** (idempotency key + IN_PROGRESS + PSP key) and **money is conserved** (double-entry ledger, append-only, balanced).
- **Auth then capture** (hold → take); void before capture; refund/chargeback = reversal entries.
- Gateway sync response is provisional; **webhooks (deduped) = truth**, **polling + reconciliation** = safety nets → **exactly-once financial effect**.
- **Strong ACID consistency** on money writes; **outbox** for events; **saga** for order↔payment↔refund.
- **PCI:** tokenize (never store raw cards), TLS/mTLS, vault, 3DS, immutable audit; retain for years.
- Patterns: Idempotency, Double-Entry Ledger, State, Saga, Outbox, Circuit Breaker, Reconciliation, Ports&Adapters.

### Related notes

- [Idempotency](../concepts/idempotency.md) · [Outbox & Saga](../concepts/outbox-and-saga.md) — the core reliability patterns
- [Splitwise](splitwise-system-design.md) — double-entry ledger sibling
- [BookMyShow](bookmyshow-system-design.md) · [Food Ordering — HLD & LLD](food-ordering-hld-lld.md) — payment integration in context
