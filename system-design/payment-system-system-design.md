# Payment System — System Design

> **Core challenge:** move money **correctly** — never double-charge, never lose a payment, always know the exact state — while integrating external **payment gateways/banks** that are slow, async, and occasionally fail. The heart is **idempotency**, a **double-entry ledger**, **auth/capture**, **async webhooks + reconciliation**, and an **exactly-once financial effect**.

> **How to read this doc:** each section has the dense interview summary first, then a **deep dive** (annotated Java and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand.

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
- [15. Interview Cheat Sheet](#15-interview-cheat-sheet)
- [16. API Design](#16-api-design)
- [17. Consistency & CAP Tradeoffs](#17-consistency--cap-tradeoffs)
- [18. Scaling & Archival](#18-scaling--archival)
- [19. Worked Example — Auth, Capture, Refund, Chargeback](#19-worked-example--auth-capture-refund-chargeback)
- [20. How to Drive the Interview (framework)](#20-how-to-drive-the-interview-framework)
- [21. Design Patterns (that can be used)](#21-design-patterns-that-can-be-used)
- [22. Final Takeaways](#22-final-takeaways)

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

### Who are all these players?

When you tap your card at a shop to pay ₹500, a chain of companies exchange messages behind that one tap. Here's each player:

- **You (the shopper)** = the **client/app**. You tapped "Pay".
- **The shop's payment software** = the **Payment Service** (the system *we* are designing). It remembers "this order needs ₹500", writes everything down, and talks to everyone else.
- **The card machine company** (e.g. Stripe, Razorpay) = the **PSP / Gateway**. The shop doesn't wire up to Visa directly — that's terrifying and heavily regulated. Instead it plugs into a PSP that knows how to talk to the banks and card networks. The PSP also **tokenizes** your card (turns your card number into a safe stand-in code) so the shop never touches raw card digits.
- **Visa / Mastercard / UPI** = the **card network**. It routes the "can this person pay ₹500?" authorization request to the right bank.
- **Your bank** (the one that gave you the card) = the **issuing bank**. It's the one that actually says "yes, she has ₹500, approved" or "declined".
- **The shop's bank** = the **acquirer**. It's where the ₹500 eventually lands.

```
You tap card ─► Shop's Payment Service ─► PSP (Stripe) ─► Visa ─► Your bank
                        │  (writes down every step in a ledger)
                        ◄── later, a WEBHOOK arrives: "that ₹500? confirmed."
```

The tricky bit: the shop's software gets an *instant-ish* reply ("looks okay, pending"), but the **real, final answer arrives later** as an async **webhook** (a callback message from the PSP). So "did it truly succeed?" is a question we answer a moment later, not immediately. That single fact — *truth arrives late* — is why the rest of this document exists.

### Why we don't talk to Visa/the bank directly

Connecting directly to card networks requires massive certification, security audits (PCI), and bank relationships. The PSP is a specialist middleman that already did all that. We integrate with the PSP; the PSP integrates with the world. (This is the **Ports & Adapters** pattern later in §21 — we hide "Stripe vs Razorpay" behind one clean interface.)

### What "never double-charge" and "money is conserved" actually mean here

- **Never double-charge** — if you tap once but the network hiccups and the request is sent twice, you must still be charged **exactly ₹500 once**, not ₹1000. The tool for this is the **idempotency key** (§6).
- **Money is conserved** — every rupee that leaves your account must land *somewhere* and be accounted for (merchant's share, platform fee, taxes), and it must all add up. The tool is the **double-entry ledger** (§7). If the numbers don't balance, something is broken and an alarm should fire.

---

## 2. Requirements

> 💡 **Start here in the interview.** Say the two commandments out loud first — *never double-charge* and *money is conserved* — then scope functional vs non-functional. Framing correctness as the top NFR (not latency/throughput) signals you understand what makes payments different.

**Functional**
- Initiate a payment (card/UPI/wallet/netbanking); **authorize then capture**; **refunds** (full *and* partial).
- **Wallets/balances**, **payouts** to merchants/sellers, multi-currency.
- Full **audit trail**; **reconciliation** with gateway/bank; disputes/chargebacks.

**Non-functional**
- **Correctness & strong consistency** on money writes; **idempotent** (safe retries); **durable** (no lost txn); **auditable**; **secure (PCI)**; highly available.

### Non-Functional (NFRs)

| NFR | Target / Note |
| --- | --- |
| **Consistency** | **Strong (ACID)** on every money write — a payment's debit + credit commit together or not at all. Status *reads* can be eventual. |
| **Correctness** | Exactly-once financial effect: never double-charge, never lose a payment, money always balances. This is *the* requirement. |
| **Durability** | A committed payment/ledger entry must never be lost; retained for **years** (compliance), never hard-deleted. |
| **Availability** | High for reads (status/history); money writes may favor consistency over availability (CP — see §17). |
| **Latency** | Modest — a payment initiate returning `202 PENDING` in a few hundred ms is fine; correctness > speed. |
| **Auditability** | Every state change and money movement is provable after the fact (immutable ledger + audit log). |
| **Security** | PCI compliance; never store raw card data; signed webhooks; encryption in transit + at rest. |

> ⚠️ **The MOST IMPORTANT requirement:** an **exactly-once financial effect**. Every other decision below exists to guarantee the customer is charged exactly once and every rupee is accounted for.

### Out of scope (state assumptions)

- Fraud/risk ML scoring, KYC/onboarding, tax computation, lending/BNPL, and the internals of the card networks (mention, then defer — we integrate a PSP, §1).

---

## 3. Capacity Estimation

```
Payments ~ 10M/day (large merchant) → ~120/sec avg, 5–10× peaks (sales)
Reads (status polls, history) ≫ writes
Ledger entries: ≥ 2 per transaction (often more with fees/taxes/payouts) → 2–5× payment rows
Storage: financial data retained for YEARS (compliance) → partition + archive, never delete
```

> Volume is modest vs a feed system, but **every write must be ACID + auditable + retained**. The hard part is correctness under retries, async gateways, and partial failures — not raw throughput.

### Why "small numbers, hard problem"

A social feed might handle *millions* of writes per second; a payment system handles maybe ~120/sec. So why is payments considered *harder*? Because **the difficulty isn't volume — it's that every single write must be perfect.**

Payments trade throughput for correctness. Unlike a high-volume system where an occasional error is tolerable, every payment write must be exactly right, provable, and retained forever — a single lost or duplicated rupee is a serious incident.

Concretely, the estimates tell us three things:

- **Reads ≫ writes** — people check "did my payment go through?" and browse history far more than they pay. So we can cache/scale reads, but writes stay strict.
- **Each payment fans out to several ledger rows** (the charge, the fee, the tax, later a payout) — so ledger storage is a few times bigger than the payment count.
- **Nothing is ever deleted** — financial records are retained for **years** for compliance. We partition and archive old data instead of deleting it.

### Low throughput doesn't mean we can relax

You use a normal RDBMS, yes — but you can't relax on *correctness*. The engineering effort goes into ACID transactions, idempotency, the ledger, and reconciliation, not into sharding for scale. It's a different kind of hard: **getting every write provably right under retries and partial failures**, not surviving a firehose.

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

### What each box does

The Payment Service is made of specialized components; a payment request moves through them in turn:

- **API Gateway** — the entry point. Every request enters here; it authenticates the caller before any real work happens.
- **Payment Service** — owns the request end-to-end: creates the payment record, decides what happens next, writes to the strongly-consistent database. This is the orchestrator.
- **Gateway Adapter** — talks to Stripe/Razorpay. It knows each PSP's quirks and wraps calls in a **circuit breaker** (if the PSP is down, stop calling and fail fast instead of hanging). "Port + adapter" = one clean interface, many possible providers behind it.
- **Webhook Handler** — receives the PSP's later callback ("payment SUCCESS"), verifies the signature is genuine, and discards duplicates.
- **Ledger Service** — records every money movement as balanced debits/credits. This is the source of truth (§7).
- **Reconciliation Job** — once a day, compares the bank's official settlement file against our ledger and flags anything that doesn't match.
- **Payout Service** — later pays merchants their accumulated balance, in batches.
- **Kafka / Outbox** — once a payment succeeds, reliably notifies the order service, notifications, and analytics without risking the message getting lost.

```java
// The Payment Service as an orchestrator, calling each "desk" in turn.
// Notice: our OWN database write and the PSP call are SEPARATE steps —
// that separation is exactly why webhooks + reconciliation are needed later.
@Service
class PaymentService {

    Payment pay(PaymentRequest req) {
        // 1. Reception already authenticated the caller (API Gateway).
        // 2. Main clerk records intent in the strongly-consistent DB first.
        Payment p = payments.create(req, Status.INITIATED);   // durable record BEFORE calling anyone
        ledger.postPending(p);                                // accountant notes pending entries

        // 3. Translator phones the PSP (wrapped in a circuit breaker).
        AuthResult auth = gatewayAdapter.authorize(p);        // may be slow / may fail

        // 4. We DON'T declare final success here — the webhook does that later.
        payments.update(p, Status.PENDING);
        return p;   // caller gets a provisional "pending" answer
    }
}
```

### Why an RDBMS (SQL) and not a fast NoSQL store

Money writes need **ACID transactions**: "deduct the balance AND write the ledger entries" must happen **all-or-nothing**. A relational DB (Postgres/MySQL) gives that guarantee cleanly, plus **unique constraints** (which power idempotency in §6) and multi-row transactions. Throughput here is modest (~120/sec), so we don't need to trade away correctness for scale.

#### Q: What's the "outbox" and why not just publish to Kafka directly?

If you write the payment to the DB and then separately publish "PAYMENT_SUCCESS" to Kafka, a crash *between* the two leaves them inconsistent (DB says success, but nobody was told → the order never ships). The **outbox** trick: in the **same DB transaction** that marks the payment successful, also insert a row into an `outbox` table. A separate poller reads unpublished outbox rows and sends them to Kafka. Because both writes are in one transaction, you can never "succeed but forget to announce it." (More in §8 and §21.)

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

> 💡 **Partial capture** is common: you authorize ₹5000 but only ship ₹4200 worth of goods, so you **capture ₹4200** and let the remaining ₹800 hold expire (or void it). One auth can also be captured in multiple pieces (partial shipments) up to the authorized amount. Each capture posts its own balanced ledger entries (§7).

### Authorize vs capture

A **hotel check-in** is the standard example. When you arrive, the hotel doesn't charge you yet — it puts a **hold** on your card (reserving, say, ₹5000). Your available balance drops, but no money has actually moved. That hold is **authorize**. When you check out, the hotel **captures** the real amount (say ₹4200 for the nights you stayed). If you never showed up, they **void** the hold and nothing is charged.

- **Authorize** = *reserve/hold* the money. Confirms the card is real and has funds. No money moved yet.
- **Capture** = *actually take* the held money. This is when the charge becomes real.
- **Void** = *cancel* the hold before capture. Nothing is charged (cleaner than refunding, because no money ever moved).

For e-commerce: authorize when the order is placed, **capture when the item actually ships**. If the item is out of stock, void — the customer never sees a charge.

```java
// Two-phase card payment: authorize now, capture later (e.g. on shipment)
class OrderPaymentFlow {

    // Step 1 — when the order is placed: HOLD the funds
    void onOrderPlaced(Order order) {
        // idempotencyKey makes this safe to retry (see §6)
        AuthResult a = psp.authorize(order.card(), order.amount(), order.idempotencyKey());
        payments.setStatus(order.paymentId(), Status.AUTHORIZED);  // funds held, not taken
        payments.saveAuthRef(order.paymentId(), a.authRef());       // remember the hold's id
    }

    // Step 2 — when the item ships: TAKE the held funds
    void onOrderShipped(Order order) {
        psp.capture(payments.authRef(order.paymentId()));           // money actually moves now
        payments.setStatus(order.paymentId(), Status.CAPTURED);
    }

    // Alternative — order cancelled before shipping: RELEASE the hold, nobody is charged
    void onOrderCancelledBeforeShip(Order order) {
        psp.voidAuth(payments.authRef(order.paymentId()));          // no refund needed — no charge happened
        payments.setStatus(order.paymentId(), Status.VOIDED);
    }
}
```

### Why not just charge immediately in one step

Many payments (UPI, small purchases) *do* — authorize and capture happen together ("sale"). Two-phase is valuable when there's a **gap between promising and delivering**: e-commerce (charge on ship, not on click), ride-hailing (hold an estimate, capture the final fare), hotels (hold a deposit). Holding first means you've *verified the money exists* without taking it before you deliver — and voiding is cleaner than refunding.

### Why the immediate response is only "provisional," even with a 200 OK

The PSP's quick reply just means "I received your request and it looks plausible." The **real** outcome — did the bank finally approve, did the capture settle — comes back **later** as a webhook (or you poll for it). Treating the sync reply as final is the classic beginner mistake: a payment can look "okay" now and turn out FAILED, or vice versa. Always wait for the confirmed webhook / reconciliation before doing anything irreversible (like shipping goods). This is why the flow returns `202 PENDING`, not `200 SUCCESS`.

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

> ⚠️ **Idempotency-key TTL:** keys aren't kept forever. Give each stored key a **TTL** (e.g. 24–72h) that comfortably outlives all client retries, then expire it. Too short → a late retry gets treated as a *new* charge (double-charge risk); too long → unbounded growth and a stale key colliding with a genuinely new payment. A key also binds to its **original request payload** — if the same key arrives with a *different* amount, reject it (`422`) rather than silently returning the old result.

### Idempotency (tapping "Pay" twice)

You tap "Pay ₹500". The spinner hangs. Nervous, you tap again. Or your phone's network silently retried the request. Now the server might receive **two identical "charge ₹500" requests**. Without protection, you get charged ₹1000. **Idempotency** is the rule: *"doing the same thing twice has the same effect as doing it once."*

The trick is a single **idempotency key** — a unique id (like a UUID) that the client generates **once** for this payment attempt and attaches to *every* retry of it. Both taps carry the **same** key. The server uses that key to recognize "this is the same payment I already saw" and refuses to charge again.

```java
@RestController
class PaymentController {

    @PostMapping("/payments")
    Payment create(@RequestHeader("Idempotency-Key") String key,   // SAME key on every retry
                   @RequestBody PaymentRequest req) {

        // Try to claim the key by inserting a brand-new IN_PROGRESS row.
        // The DB has a UNIQUE constraint on idempotency_key, so only the
        // FIRST request can insert; a duplicate insert fails/does nothing.
        boolean weWonTheRace = payments.insertIfAbsent(key, Status.IN_PROGRESS);

        if (!weWonTheRace) {
            // Someone with the same key got here first. Look at what happened.
            Payment existing = payments.findByKey(key);

            if (existing.status() == Status.IN_PROGRESS) {
                // A first request is STILL running (concurrent double-tap).
                // Don't start a second charge — tell the caller to retry shortly.
                throw new Conflict409("payment in progress, retry shortly");
            }
            // The first request already finished — return the SAME result.
            // The caller sees success once, not a second charge.
            return existing;   // <-- idempotent: repeat returns stored result, no new charge
        }

        // We are the FIRST and only one to claim this key → do the real work once.
        Payment p = charge(req, key);          // calls the PSP exactly once
        payments.update(key, p);               // store the final result under the key
        return p;
    }
}
```

The two safeguards working together:

| Safeguard | Stops | How |
| --- | --- | --- |
| **UNIQUE constraint on `idempotency_key`** | Two *sequential* requests both charging | DB physically rejects the second insert of the same key |
| **`IN_PROGRESS` marker** | Two *concurrent* requests both charging | Second one sees "in progress" and backs off instead of also calling the PSP |

#### Q: Idempotency vs "exactly-once" — same thing?

Related but not identical. **Idempotency** is a property of an *operation*: calling it twice = calling it once. **Exactly-once financial effect** is the *goal* — the customer is charged once, no matter how many retries or duplicate webhooks happen. You achieve the goal by combining several idempotent pieces:

> **Idempotency key (our API) + idempotency key passed to the PSP + webhook dedup by `event_id` + daily reconciliation = exactly-once financial effect.**

No single mechanism is enough. The client might retry (our key catches it), *we* might retry the PSP (the PSP's key catches it), the PSP might send the same webhook twice (event_id dedup catches it), and if *all* of that somehow slips, reconciliation catches it the next day.

### Why we pass an idempotency key to the PSP too, not just our own

Ours protects against the **client** retrying us. But *we* also retry the PSP (their API timed out, so we call `authorize` again). If we don't send the PSP a key, our retry could make the PSP charge twice. So we also generate/forward a key on the outbound call so **Stripe/Razorpay** dedups *our* retries on *their* side. Every hop in the chain needs its own retry protection.

### Why an `IN_PROGRESS` marker on top of the unique constraint

The unique constraint stops a duplicate that arrives *after* the first finished. But two taps can arrive at *the same instant* (both before either has finished charging). The `IN_PROGRESS` marker is written the moment the first request starts, so the racing second request sees "already in flight" and waits/retries instead of firing a parallel charge. Constraint = protects sequential duplicates; marker = protects concurrent duplicates.

---

## 7. Double-Entry Ledger

> 💡 **"Double-entry"** = a 500-year-old accounting rule where every money movement is recorded **twice** — once as where money left (a *debit*) and once as where it arrived (a *credit*) — and the two sides must be equal. If they don't balance, you *know instantly* something is wrong. It is the single most important idea in this doc.

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

### Double-entry ledger (money never appears from nowhere)

A **ledger** is just a notebook of money movements. "Double-entry" is a 500-year-old accounting rule: **every movement is written twice** — once as where money **came from** (a *debit*) and once as where it **went to** (a *credit*) — and the two sides must be **equal**. If they don't match, you know instantly that something is wrong.

Concretely: whatever amount leaves one account must arrive in one or more others, and the totals on each side must be equal. If ₹500 leaves account A, exactly ₹500 must be recorded arriving elsewhere — e.g. ₹450 into B and ₹50 into C — and the ledger checks that money out = money in.

```
You pay ₹500 for an order; the platform keeps a ₹50 fee. One transaction, three entries:

  DEBIT   user_payment_source   500   ← ₹500 LEFT the customer (money out)
  CREDIT  merchant_payable      450   ← ₹450 owed to the shop  (money in)
  CREDIT  platform_revenue       50   ← ₹50 is our fee         (money in)
  ─────────────────────────────────
  debits 500  ==  credits 450 + 50    ← balanced ✓  (nothing created or lost)
```

Notice: we don't store "the customer's balance = X". We store the *movements*, and any balance is just the **sum of all movements** for that account. The history is the truth; balances are derived from it.

```java
// A ledger transaction = a bundle of entries that MUST sum to zero (balanced).
// Written append-only: we never edit a posted entry, only add new ones.
class LedgerTransaction {
    long txnId;
    List<Entry> entries;   // e.g. 1 debit + 2 credits
}

record Entry(long accountId, char direction /* 'D' or 'C' */, long amount) {}

class LedgerService {

    // Post a balanced transaction inside ONE ACID DB transaction.
    // If anything fails, the WHOLE thing rolls back — never half-written.
    @Transactional
    void post(LedgerTransaction txn) {
        long debits  = txn.entries.stream().filter(e -> e.direction()=='D')
                                  .mapToLong(Entry::amount).sum();
        long credits = txn.entries.stream().filter(e -> e.direction()=='C')
                                  .mapToLong(Entry::amount).sum();

        // THE core invariant: money in == money out. Anything else is corruption.
        if (debits != credits) {
            throw new IllegalStateException("unbalanced txn " + txn.txnId + " — REJECT");
        }

        for (Entry e : txn.entries) {
            ledgerEntries.insert(e);                       // append-only: add, never mutate
            accounts.applyDelta(e.accountId(),             // update the cached balance too...
                    e.direction()=='D' ? -e.amount() : e.amount());
        }
        // ...all in the same @Transactional block → balances and entries can't disagree.
    }
}

// A refund does NOT edit the original. It's a brand-new, opposite transaction:
void refund(long originalPaymentId, long amount) {
    ledger.post(new LedgerTransaction(/* entries reversed:
        DEBIT merchant_payable amount, CREDIT user_payment_source amount */));
    // history stays intact; the refund is just more append-only entries.
}
```

### Why two entries instead of just `balance = balance - 500`

A single subtraction loses the *why* and the *where*. With double-entry, every rupee out is matched to a rupee in, so:

- **You can always prove money was conserved** — sum every entry in a transaction; it must be 0. If it isn't, you have a bug/corruption, and it's caught immediately.
- **You get a full audit trail for free** — every movement, forever. Disputes, taxes, and reconciliation all read this history.
- **Balances are always reconstructable** — even if a cached balance is wrong, replaying the entries recomputes the truth.

### Debit vs credit, made concrete

For a given account, think of it simply as *direction of money*. In this system a **debit** on `user_payment_source` means money **flowed out of** the customer's funding source; a **credit** on `merchant_payable` means money **flowed into** what we owe the merchant. The only rule you must never break: **within one transaction, total debits = total credits.** (Accountants have deeper sign conventions per account type, but for interview-level reasoning, "every txn balances to zero" is the core idea.)

### Multi-currency & FX (each currency is its own account)

> ⚠️ **Never mix currencies in one account.** A ledger account holds exactly **one currency** — you can't add ₹ and $ in the same balance any more than you can add apples and oranges. A user with rupee and dollar balances has **two accounts**.

When money crosses currencies (customer pays in USD, merchant settles in INR), you don't fudge one entry — you post a **balanced transaction per currency** plus a dedicated **FX account** that absorbs the conversion, so each currency still balances to zero on its own:

```
Customer pays $10, merchant is paid ₹830 (rate 83), platform FX spread aside:
  USD txn:  DEBIT  user_usd_source   $10
            CREDIT fx_clearing_usd   $10        (USD side balances)
  INR txn:  DEBIT  fx_clearing_inr   ₹830
            CREDIT merchant_payable  ₹830       (INR side balances)
```

The FX clearing account is where the exchange-rate gain/loss lives; each currency's entries still sum to zero independently. Store the **rate used** on the transaction for audit.

#### Q: How do refunds/chargebacks fit without editing history?

You **never** change a posted entry. A refund is a *new* transaction that moves the money the opposite way (credit back the customer, debit the merchant). A chargeback (bank-forced reversal) is the same idea — new reversal entries plus a dispute record. The original entries stay exactly as they were, so the notebook always reads like a truthful diary: "charged ₹500 on Tuesday, refunded ₹500 on Friday" — two facts, not one edited fact.

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

> 💡 **"Settlement file"** = the bank/PSP's official daily export listing every transaction that *actually settled* and for how much. It is the highest authority we have — where our records disagree with it, **it wins** and we correct ours.

> **Reconciliation is the ultimate safety net:** the settlement file is the bank's truth. Any discrepancy — a missed webhook, a stuck `PENDING`, a mismatched amount — is caught daily and resolved. Never trust a single signal.

### Webhooks (the callback you wait for)

A **webhook** is a callback: instead of us repeatedly polling the PSP "done yet?", the PSP **calls us** at a URL we gave it — "payment `pay_123` is now SUCCESS" — the moment it knows.

Two catches make webhooks tricky, and both have standard fixes:

1. **Anyone could POST to our webhook URL pretending to be Stripe.** So every webhook is **signed** — the PSP includes a signature computed with a shared secret; we verify it. No valid signature → ignore.
2. **The PSP may send the same webhook more than once** (networks retry; that's "at-least-once" delivery). So we **dedup by `event_id`** — remember which event ids we've processed and skip repeats.

```java
@PostMapping("/webhooks/psp")
ResponseEntity<Void> onWebhook(@RequestBody String rawBody,
                               @RequestHeader("PSP-Signature") String signature) {

    // 1. AUTHENTICITY — is this really from the PSP? Reject forgeries.
    if (!psp.verifySignature(rawBody, signature, sharedSecret)) {
        return ResponseEntity.status(401).build();     // not genuine → drop it
    }

    WebhookEvent evt = parse(rawBody);

    // 2. DEDUP — have we already handled this exact event? (at-least-once delivery)
    //    INSERT fails if event_id already exists (PRIMARY KEY), so a duplicate
    //    simply does nothing and we return 200 (so the PSP stops retrying).
    if (!webhookEvents.insertIfAbsent(evt.eventId())) {
        return ResponseEntity.ok().build();            // duplicate → already processed, ignore
    }

    // 3. First time we've seen it → apply it exactly once.
    payments.setStatus(evt.paymentId(), evt.status()); // e.g. PENDING → SUCCESS
    ledger.postFinalEntries(evt);                      // now post the real ledger entries
    outbox.add("PAYMENT_SUCCESS", evt.paymentId());    // reliably tell the rest of the system

    return ResponseEntity.ok().build();                // 200 = "got it, don't resend"
}
```

### Reconciliation (the nightly audit)

Even with webhooks, things slip: a webhook never arrives (our server was down), a payment is stuck in `PENDING` forever, or an amount is subtly wrong. **Reconciliation** is the daily sanity check: the PSP/bank sends a **settlement file** — the official, authoritative list of "here is every transaction that actually settled and how much" — and we compare it, line by line, against our own ledger.

The settlement file is authoritative: where our records disagree with it, we correct ours to match and investigate anything that can't be reconciled.

```java
@Scheduled(cron = "0 0 3 * * *")   // 3 AM daily
void reconcile() {
    List<SettlementRow> bankTruth = psp.downloadSettlementFile(yesterday());

    for (SettlementRow bank : bankTruth) {
        Payment ours = payments.findByGatewayRef(bank.gatewayRef());

        if (ours == null) {
            // Bank charged it but WE have no record → missed webhook. Recover it.
            recoverMissingPayment(bank);
        } else if (ours.status() != Status.SUCCESS) {
            // Bank says settled, we still show PENDING/unknown → fix our state.
            payments.setStatus(ours.id(), Status.SUCCESS);
            ledger.postFinalEntries(ours);
        } else if (ours.amount() != bank.amount()) {
            // Amounts disagree → do NOT auto-guess; flag for a human.
            reconciliationLog.flagMismatch(ours, bank);
        }
    }
    // Also handle the reverse: payments WE think succeeded but the bank never settled.
}
```

#### Q: Webhook vs polling vs reconciliation — why all three?

They're **layered safety nets**, from fastest to most authoritative:

| Signal | Speed | Role |
| --- | --- | --- |
| **Webhook** | Seconds | Primary: PSP proactively tells us the result |
| **Status polling** | Minutes | Fallback: if a payment is still PENDING and no webhook came, we ask the PSP directly |
| **Reconciliation** | Daily | Final truth: compare against the bank's settlement file; catch anything the first two missed |

The rule is **"never trust a single signal."** A missed webhook shouldn't cost a customer their order or leave money unaccounted for. Something *always* catches it.

### Why the settlement file outranks our own records

It comes from the entity that actually moved the money (the bank/PSP). Our records are our *belief* about what happened; the settlement file is what *did* happen at the bank. When they disagree, the bank wins (and we investigate why we drifted).

### PSP failover (don't bet the business on one provider)

A single PSP is a single point of failure — outages, regional declines, or commercial disputes shouldn't stop *all* payments. Because every provider sits behind the same **Ports & Adapters** interface (§21), we can route around a sick one:

- **Health-based routing** — a circuit breaker trips on a PSP's error/latency spike; new payments route to a backup PSP (Razorpay → Stripe, or a second acquirer).
- **Smart retry** — a *decline* isn't retried on another PSP (it'll decline again and looks like abuse), but a *provider/timeout* error can be retried on the backup — carrying a **fresh idempotency key scoped to that PSP** so neither charges twice.
- **Reconcile per provider** — each PSP has its own settlement file; the reconciliation job runs per provider and rolls up.

> ⚠️ Failover multiplies the double-charge risk: the *same* payment may touch two providers. Our own `idempotency_key` (one per payment attempt, §6) is what keeps the customer charged once even when the outbound provider changes.

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

### The state machine (a payment's lifecycle)

A **state machine** is just "a thing can only be in one state at a time, and can only move between states along allowed transitions." A payment isn't a free-for-all — it can't jump from `INITIATED` straight to `REFUNDED` (you can't refund money you never captured). Modeling it explicitly prevents illegal, money-losing transitions: only certain next-states are legal from wherever the payment is now.

```java
enum Status { INITIATED, AUTHORIZED, PENDING, CAPTURED, SUCCESS,
              FAILED, VOIDED, REFUNDED, PARTIALLY_REFUNDED, DISPUTED }

class PaymentStateMachine {

    // For each state, the ONLY states it is allowed to move to.
    static final Map<Status, Set<Status>> ALLOWED = Map.of(
        Status.INITIATED,  Set.of(Status.AUTHORIZED, Status.PENDING, Status.FAILED),
        Status.AUTHORIZED, Set.of(Status.CAPTURED, Status.SUCCESS, Status.VOIDED, Status.FAILED),
        Status.PENDING,    Set.of(Status.SUCCESS, Status.FAILED),        // resolved by webhook/reconcile
        Status.SUCCESS,    Set.of(Status.REFUNDED, Status.PARTIALLY_REFUNDED, Status.DISPUTED),
        Status.CAPTURED,   Set.of(Status.SUCCESS, Status.REFUNDED, Status.DISPUTED)
    );

    void transition(Payment p, Status next) {
        // Guard: refuse any move that isn't on the allowed list.
        if (!ALLOWED.getOrDefault(p.status(), Set.of()).contains(next)) {
            throw new IllegalStateException(
                "illegal transition " + p.status() + " → " + next);   // e.g. INITIATED → REFUNDED = blocked
        }
        Status from = p.status();
        p.setStatus(next);
        payments.save(p);                      // durably record EVERY transition
        auditLog.record(p.id(), from, next);   // full history for disputes/compliance
    }
}
```

### Why bother with a formal state machine instead of just a status field

Money bugs hide in illegal transitions. Without guards, a retry or a race could mark a `FAILED` payment as `SUCCESS`, or refund something twice. The allowed-transitions map turns "that should never happen" into "that literally cannot happen — the code throws." It also gives you a clean audit trail of exactly how each payment moved.

#### Q: What happens to a payment stuck in PENDING forever?

That's the dangerous state — "we don't know yet." The rule: **PENDING is never abandoned.** A background job polls the PSP for its real status, and the daily reconciliation resolves any that are still stuck, moving them to `SUCCESS` or `FAILED`. A payment left dangling in PENDING is a bug, not a resting state.

### The chargeback / dispute state machine

A **chargeback** is a bank-forced reversal: the customer tells *their* bank "I didn't authorize this," and the bank claws the money back — often months later. It's a second, separate lifecycle hanging off a `SUCCESS` payment, and it has its own states and deadlines:

```
(payment SUCCESS)
      │ bank raises dispute
      ▼
  DISPUTE_OPENED ─ merchant submits evidence ─► UNDER_REVIEW
      │ (no response / deadline passes)              │
      ▼                                     ┌────────┴────────┐
  LOST (funds clawed back)                  ▼                 ▼
                                          WON              LOST
                                    (funds returned)  (reversal stands)
```

- Each transition posts ledger entries: opening a dispute may move funds to a **`disputed_hold`** account; **WON** returns them, **LOST** posts the reversal (money leaves `merchant_payable`, plus a dispute fee).
- Deadlines matter — miss the evidence window and you auto-**LOSE**. Track `due_by` on the dispute row.
- Like refunds, a chargeback **never edits** the original charge; it's new reversal entries + a `disputes` case (§10, §12).

> ⚠️ Don't confuse a **refund** (you *choose* to give money back) with a **chargeback** (the bank *forces* it, against your will, with fees and a win/lose fight). Different triggers, different accounting, different state machine.

---

## 10. Refunds, Payouts & Wallets

- **Refund** = a new ledger transaction **reversing** the original (full/partial); call gateway refund; **idempotent** (own key).

> 💡 **Partial refunds** must never exceed what was captured. Track `refunded_total` on the payment and guard each refund with `refunded_total + amount <= captured_amount`; the payment moves to `PARTIALLY_REFUNDED` until the sum reaches the full amount, then `REFUNDED`. Multiple partial refunds are fine — they just keep appending reversal entries.

- **Payout** = settle merchant/seller balances (escrow → payout minus fees); **batched**; ledger-tracked; scheduled.
- **Wallet** = an account in the ledger; top-up/spend are balanced entries; **atomic** balance changes (strong consistency; a wallet can't go negative → conditional debit).
- **Chargeback/dispute** = network-initiated reversal → ledger reversal + case tracking + evidence.

### Refunds, payouts & wallets (three money moves)

These are three everyday money movements, and each maps to ledger entries:

- **Refund** = giving money **back** to the customer. Not an "undo" of the original — a **new, opposite transaction** (the original sale stays recorded; a separate return is added).
- **Payout** = the platform **paying merchants** their accumulated earnings, usually **batched** (e.g. all of a seller's sales settled once a day, minus fees). Money you were *holding* on their behalf now actually leaves to their bank.
- **Wallet** = a stored balance the user can top up and spend. In our system a wallet is just **another account in the ledger**; top-ups and spends are balanced entries like everything else.

```java
// REFUND — a new reversing transaction, idempotent via its own key
void refund(long paymentId, long amount, String refundIdempotencyKey) {
    if (refunds.exists(refundIdempotencyKey)) return;      // safe to retry: already done
    psp.refund(paymentId, amount, refundIdempotencyKey);   // tell the PSP too
    ledger.post(reversalOf(paymentId, amount));            // credit customer, debit merchant
    payments.setStatus(paymentId, amount == fullAmount(paymentId)
            ? Status.REFUNDED : Status.PARTIALLY_REFUNDED);
}

// WALLET SPEND — must NEVER let the balance go negative.
// The magic is the conditional update: deduct ONLY IF enough funds exist.
boolean spendFromWallet(long walletAccountId, long amount) {
    // "WHERE balance >= amount" makes the check-and-deduct ATOMIC in the DB —
    // two concurrent spends can't both pass the check and overdraw.
    int rowsUpdated = db.execute(
        "UPDATE accounts SET balance = balance - ? " +
        "WHERE account_id = ? AND balance >= ?",           // conditional debit
        amount, walletAccountId, amount);

    if (rowsUpdated == 0) return false;                    // insufficient funds → rejected
    ledger.post(walletSpendEntries(walletAccountId, amount));
    return true;
}
```

### Why a refund is a new transaction, not a deletion of the original

The ledger is **append-only** and the original charge really *did* happen — pretending it didn't would destroy the audit trail and break "money is conserved." A refund records a *second* truth ("we gave ₹500 back"), so the history reads honestly: charged, then refunded. Same for chargebacks.

### Stopping a wallet from going negative when two spends race

Use a **conditional debit**: `UPDATE ... SET balance = balance - amount WHERE balance >= amount`. The database evaluates the condition and the deduction as one atomic step, so if two ₹80 spends hit a ₹100 wallet simultaneously, only one succeeds (the other updates 0 rows and is rejected). No overdraft, no race condition — the DB enforces it, not application-level checking.

### Why payouts are batched instead of instant

Merchants accumulate many sales; sending each one to their bank immediately would be expensive and noisy. Instead we hold their balance (in escrow, tracked in the ledger) and pay it out on a schedule (daily/weekly), minus fees, in one batch transfer. It's cheaper, simpler to reconcile, and matches how settlement actually works with banks.

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

### Security & PCI

> 💡 **"PCI DSS"** (Payment Card Industry Data Security Standard) = the mandatory, audited rulebook that kicks in the moment you *store or handle raw card numbers*. Compliance is expensive, so the winning move is to **stay out of scope** entirely — never let a raw card number touch your servers; tokenize via the PSP.

**PCI DSS** is a set of strict security rules you must follow if you *store or handle raw card numbers* (the 16-digit PAN). The rules are expensive and audited. The single best move is therefore: **never touch raw card data at all.** Let the PSP handle it and give you a harmless **token** instead.

You send the real card number to the PSP's vault; it returns a token like `tok_abc123`, and you store only the token. If your database is stolen, the attacker gets useless tokens, not card numbers — and a token only works when *you* present it to *that* PSP.

```java
// SAVING a card: the raw number goes to the PSP, we store only a token + last4.
class SaveCardFlow {
    PaymentMethod saveCard(RawCardInput card) {
        // The raw PAN goes STRAIGHT to the PSP's vault — it never lands in our DB/logs.
        String token = psp.tokenize(card);        // e.g. "tok_abc123"

        // We persist only the safe token and a display hint. No PAN, no CVV, ever.
        return paymentMethods.save(new PaymentMethod(
                userId, "CARD", token, card.last4()));   // last4 for "•••• 4242" display
    }
}

// CHARGING later: we present the TOKEN, not a card number.
void charge(long userId, long amount) {
    String token = paymentMethods.tokenFor(userId);
    psp.charge(token, amount);                    // PSP swaps token → real card internally
}
```

> **"tokenize"** = replace sensitive data with a meaningless stand-in that only the vault can reverse. Because we store `tok_abc123` (not `4242 4242 4242 4242`), a breach of our database leaks nothing chargeable — which keeps us **out of most PCI scope**.

### How we charge a returning customer without ever storing their card

The PSP stores the real card in *its* vault and gives us a durable token tied to that customer. Next time, we send the **token** and the amount; the PSP looks up the real card behind the token and charges it. We get repeat payments without ever holding the dangerous data.

### What 3-D Secure (3DS) — that OTP step — actually does

**3DS** is the extra "enter the OTP your bank texted you" step. It proves the *real cardholder* is present, and — importantly — it **shifts fraud liability** to the bank: if a 3DS-approved payment turns out fraudulent, the bank (not the merchant) generally eats the loss. It adds friction, so it's often used for higher-risk or higher-value payments.

### Why webhooks are signed and verified (again, under security)

A webhook endpoint is a public URL — an attacker could POST a fake "payment SUCCESS" to trick us into shipping goods for free. The PSP signs each webhook with a shared secret; we verify the signature before trusting it (see §8). Same idea as checking ID before believing a message.

---

## 12. Data Model (all tables)

### Database & storage choices (which DB, and why at scale)

Unlike a social feed or a booking system, there's no polyglot debate for the *core* data here — the deciding question, *"does this need strong consistency and transactions?"*, is an unconditional **yes** for every rupee that moves, so the ledger and payment records only ever live in an RDBMS. The polyglot choices show up only around the edges (events, caching, reconciliation).

| Data | Store | Why this one | Why not the alternative |
| --- | --- | --- | --- |
| Payments, ledger accounts/entries (**source of truth**) | **RDBMS** (Postgres/MySQL), strong ACID | "Deduct the balance AND write the ledger entries" must be **all-or-nothing** — a multi-row transaction. The `idempotency_key` **UNIQUE constraint** is what makes "never double-charge" a DB-enforced guarantee, not an application promise. | An eventually-consistent NoSQL store (Cassandra/Dynamo) gives you none of this for free — you'd have to hand-roll cross-row atomicity and uniqueness checks for the one kind of bug (a duplicated or lost rupee) that's least acceptable here. Throughput is only ~120/sec (§3), so there's no scale pressure pushing you toward NoSQL in the first place. |
| Ledger entries specifically (**append-only**) | Same RDBMS, `ledger_entries` **insert-only, never updated** | Corrections are new reversal transactions, never edits — the table's write pattern is "append," which a relational engine handles as easily as any store, with the bonus of transactional balance in the same commit. | A log/stream store (Kafka) is great for *events about* the ledger, but the ledger itself needs to be **queryable with WHERE/JOIN for reconciliation and disputes** — not just replayable. |
| Idempotency markers + rate-limit counters | **Redis** | Sub-ms `IN_PROGRESS` marker checks and rate-limit counters keep hot paths fast; these are short-lived coordination state, not permanent financial records. | Storing the `IN_PROGRESS` marker only in the RDBMS still works (it's how the unique-constraint trick is described in §6) — Redis is an optimization to keep concurrent-request detection off the DB's write path at higher volume, not a correctness requirement. |
| Payment/refund/webhook events | **Kafka** (via outbox) | Durably decouples "payment succeeded" from every downstream reaction (order service, notifications, analytics) — and the **outbox** ensures the DB write and the event can't drift apart (§4). | Calling downstream services synchronously couples them to payment latency and loses events on a crash between the DB write and the call. |
| Reconciliation records | Same RDBMS (`reconciliation_log`), fed from the gateway's flat settlement file | It's small, structured, and needs to be joined against `payments`/`ledger_entries` daily — another relational access pattern. | A separate analytics store adds an integration point for a job that's fundamentally "compare two tables" — not worth it at this volume. |

**Why RDBMS wins unconditionally at this scale:** payment volume tops out around ~120/sec (§3) — trivial for a single ACID primary — so there is zero throughput incentive to trade away correctness. The hard problem is **never getting a single write wrong**, and that's exactly what row-level ACID transactions plus unique constraints buy you for free. **Scaling** is done by **sharding by account/merchant** once volume genuinely outgrows one primary — each shard still runs full ACID transactions *within* itself, so a single payment's debit+credit pair never needs to cross a shard boundary. Read replicas absorb the (much higher) read volume — balance checks, status polls, history — without touching the write primary. (See [Databases — Deep Dive](../concepts/databases-deep-dive.md).)

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

### Reading the data model (which table does what)

The tables mirror the story above. Here's each one in plain terms:

| Table | Purpose | Key detail |
| --- | --- | --- |
| `payments` | One row per payment attempt | `idempotency_key` is **UNIQUE** → the "never double-charge" guarantee lives here |
| `accounts` | One row per money bucket (user, merchant, platform, wallet) | `balance` is a cached snapshot; the real truth is the entries |
| `ledger_transactions` | One row per money *event* (a charge, a refund) | Groups the entries that must balance to zero together |
| `ledger_entries` | The debits & credits themselves | The **source of truth**; append-only; ≥2 rows per transaction |
| `refunds` | Money given back | Has its **own** `idempotency_key` so refunds are safe to retry |
| `payouts` | Batched settlements to merchants | `batch_id` + `release_at` for scheduled payout runs |
| `webhook_events` | Every PSP callback we received | `event_id` is the **PRIMARY KEY** → dedup duplicate webhooks |
| `payment_methods` | Saved cards | Stores a **token + last4**, never the real card number |
| `disputes` | Chargebacks/complaints | Tracks the case + evidence |
| `outbox` | Events waiting to be published to Kafka | Written in the same txn as the payment → no lost events |
| `reconciliation_log` | Nightly audit results | Records mismatches found vs the settlement file |

The three columns doing the heavy correctness-lifting:

```sql
-- 1. Never double-charge: the DB itself rejects a duplicate key.
idempotency_key VARCHAR(255) NOT NULL UNIQUE

-- 2. Never process a webhook twice: event_id can only exist once.
event_id VARCHAR(255) PRIMARY KEY

-- 3. Money is conserved: enforced in code per transaction —
--    sum of 'D' amounts must equal sum of 'C' amounts.
direction CHAR(1) NOT NULL,   -- 'D' debit / 'C' credit
amount    BIGINT NOT NULL     -- >0; sum(debits) == sum(credits) per txn
```

### Indexes that matter

The indexes here exist for **correctness** as much as speed — the first one is what physically enforces "never double-charge."

- `payments (idempotency_key)` **UNIQUE** — the DB-level guarantee behind idempotency (§6); a duplicate insert is *rejected*, not just slow.
- `payments (user_id, created_at)` — a user's payment history / status polls, newest-first.
- `payments (status, updated_at)` where `status = 'PENDING'` — cheap sweep for the poller/reconciler to find stuck payments (§9).
- `webhook_events (event_id)` **PRIMARY KEY** — dedup at-least-once webhook deliveries (§8); the second copy of an event does nothing.
- `ledger_entries (account_id, created_at)` — derive/verify an account balance by summing its entries, and pull an account statement.
- `ledger_entries (txn_id)` — fetch all legs of one transaction to check it balances.
- `refunds (idempotency_key)` **UNIQUE** — refunds are independently retry-safe.
- `payouts (merchant_id, status)` and `disputes (payment_id)` — batch payout runs and dispute lookups.

> 💡 The two **UNIQUE** constraints (`idempotency_key`, `event_id`) aren't performance tuning — they're the enforcement mechanism for exactly-once. Treat them as part of the design, not an afterthought.

### Why `amount` is a BIGINT in "minor units," not a decimal like 500.00

Storing money as **integer paise/cents** (₹500 → `50000`) avoids floating-point rounding bugs (`0.1 + 0.2 != 0.3`). You never want ₹0.01 to vanish or appear in a financial system, so we count the smallest unit as a whole number and format for display only.

### If `accounts.balance` is stored and `ledger_entries` is also the truth, which wins

The **entries win.** The balance column is a convenience/cache so you don't re-sum millions of rows on every read. It's kept in step by updating it *inside the same transaction* as the entries (see §7). If they ever disagree, you recompute the balance by summing the entries — the ledger is authoritative, the balance is derived.

### Why `payments` is kept separate from the ledger at all

`payments` tracks the **workflow/state** of one attempt (INITIATED → AUTHORIZED → SUCCESS, which gateway, which ref). The **ledger** tracks the **money movements**. One payment can produce several ledger transactions over time (capture, fee, refund), and the ledger also holds movements that aren't a single payment (payouts, wallet top-ups). Separating "what's the status of this attempt" from "how did money move" keeps both clean.

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

### Walking the happy path end-to-end

Let's replay one ₹500 checkout and watch every mechanism from this doc fire in order:

1. **You tap Pay.** The app generates an **idempotency key** (§6) and POSTs it with the amount.
2. **Payment Service claims the key** by inserting an `IN_PROGRESS` payment row. If you double-tapped, the second request sees `IN_PROGRESS` and backs off — no double charge.
3. **Pending ledger entries** are written (§7): the accountant notes the intended movement, not yet final.
4. **Call the PSP** to authorize/capture, passing an idempotency key to *them* too so *our* retries don't double-charge on their side.
5. **You get `202 PENDING`** — deliberately not "SUCCESS", because the truth isn't in yet (§5).
6. **Later, the webhook arrives** — signature verified, deduped by `event_id` (§8). *Now* we set status `SUCCESS` and post the **final balanced ledger entries**.
7. **Outbox → Kafka** publishes `PAYMENT_SUCCESS` in the same transaction (§4), so the order service reliably learns to ship the goods.
8. **Overnight, reconciliation** compares our ledger to the bank's settlement file (§8) — catching anything the webhook missed.

```java
// The whole story as one annotated flow (simplified).
Payment checkout(PaymentRequest req, String idemKey) {
    // (2) claim the key — concurrent double-taps can't both pass here
    if (!payments.insertIfAbsent(idemKey, Status.IN_PROGRESS))
        return payments.findByKey(idemKey);          // idempotent: return existing

    Payment p = payments.byKey(idemKey);
    ledger.postPending(p);                            // (3) pending entries

    psp.authorizeAndCapture(p, idemKey);             // (4) PSP call, its own idempotency
    payments.setStatus(p, Status.PENDING);           // (5) provisional
    return p;                                         // caller sees 202 PENDING
}

// (6-7) arrives moments later, separately:
void onWebhook(WebhookEvent e) {
    if (!verifyAndDedup(e)) return;                  // signature + event_id dedup
    payments.setStatus(e.paymentId(), Status.SUCCESS);
    ledger.postFinal(e);                             // final balanced entries
    outbox.add("PAYMENT_SUCCESS", e.paymentId());    // reliable event → order ships
}
```

### What happens if the charge times out and we never hear back

We **leave it PENDING and never guess** (§9). A timeout means "unknown", not "failed". A poller asks the PSP for the real status, and reconciliation settles it against the bank file. Because everything is idempotent, any retry along the way can't double-charge. This "assume nothing, resolve later" discipline is what prevents both lost payments and double charges.

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

### The failure playbook (what breaks, what saves you)

Every row in that table is a real thing that *will* go wrong in production, paired with the mechanism that saves you. Grouped by the two commandments:

**Commandment 1 — never double-charge (duplicates):**

- *You tapped twice / the network retried* → **idempotency key** (§6): the same key can only charge once.
- *The PSP sent the same webhook twice* → **dedup by `event_id`** (§8): second copy does nothing.
- *We retried the PSP after a timeout* → **PSP-side idempotency key** (§6): the PSP dedups our retries.

**Commandment 2 — money is conserved / never lost:**

- *Money seems created or lost* → **double-entry ledger** (§7): every txn must balance, or an alarm fires.
- *A charge timed out — did it go through?* → **leave PENDING, never guess** (§9): poll + reconcile decide.
- *A webhook never arrived* → **reconciliation vs settlement file** (§8): the nightly audit catches it.
- *We crashed between charging and recording it* → **outbox + reconciliation** (§4, §8): nothing lost, and idempotency stops a duplicate on retry.
- *The PSP is down/slow* → **circuit breaker + retry with backoff** (§8): fail fast instead of hanging, retry later.
- *Two spends race on a wallet* → **conditional debit** (`WHERE balance >= amount`, §10): can't go negative.
- *The bank reverses a charge (chargeback)* → **ledger reversal + dispute case** (§10): recorded, never edited away.

The pattern is layered, independent safety nets: idempotency catches duplicates at the entry point, the ledger catches imbalances in the accounting, and reconciliation catches anything else in its nightly pass. No single failure slips through because a *later, more authoritative* check always exists.

#### Q: What's the one-sentence version of the whole failure strategy?

**Make every operation safe to retry (idempotent), never assume an unknown outcome (leave PENDING), and always have a slower, more authoritative check behind the fast one (webhook → poll → reconciliation).** Do that, and you get an exactly-once financial effect even though every individual piece is unreliable.

---

## 15. Interview Cheat Sheet

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

### Tricky scenarios (rapid-fire)

| Scenario | What happens / what to do |
| --- | --- |
| **Client double-taps "Pay"** | Same **idempotency key** → the `IN_PROGRESS` marker makes the second request back off; only one charge (§6). |
| **PSP times out after possibly charging** | Treat as **UNKNOWN**, leave `PENDING`, never assume; poll + reconcile settle it. Idempotency stops a retry double-charge (§9). |
| **Same webhook delivered twice** | **Dedup by `event_id`** (PRIMARY KEY) → the duplicate does nothing, still return `200` so the PSP stops resending (§8). |
| **Webhook never arrives** | Status **polling** catches most; nightly **reconciliation** vs the settlement file catches the rest (§8). |
| **Ledger doesn't balance** | Reject the transaction and alarm — debits must equal credits; an imbalance is corruption, never "round it off" (§7). |
| **Refund requested twice** | Refund has its **own** idempotency key → second call returns the same result, no double refund (§10). |
| **Chargeback months later** | New **reversal entries** + a `disputes` case with an evidence deadline; original charge is never edited (§9, §10). |
| **Two wallet spends race** | **Conditional debit** (`WHERE balance >= amount`) → only one succeeds, no overdraft (§10). |
| **Primary PSP is down** | Circuit-breaker routes to a **backup PSP**; provider errors retry with a fresh PSP-scoped key; our own key keeps it single-charge (§8). |
| **Same key, different amount** | Reject (`422`) — an idempotency key is bound to its original payload, don't silently return the old charge (§6). |

> **Ultimate layer model:** idempotency key = no duplicate charges · double-entry ledger = money conserved · webhook → poll → reconciliation = truth always arrives · outbox = events never lost.

---

## 16. API Design

> Keep it RESTful; the whole write path hinges on the **`Idempotency-Key`** header and returning **`202 PENDING`**, not `200 SUCCESS`.

```
POST /v1/payments                                  → initiate a payment (auth, or auth+capture)
     header: Idempotency-Key: <uuid>               (REQUIRED — dedup retries)
     body:   { amount, currency, method|token, orderId, captureMode: AUTO|MANUAL }
     → 202 { paymentId, status: PENDING }           (truth arrives later via webhook)
     → 409 { paymentId, status: IN_PROGRESS }       (same key already in flight)
     → 422 { error: "idempotency key reused with different payload" }

POST /v1/payments/{id}/capture                      → capture a prior authorization (full or partial)
     header: Idempotency-Key: <uuid>
     body:   { amount }                             (≤ authorized amount)
     → 200 { paymentId, status: CAPTURED | PARTIALLY_CAPTURED }

POST /v1/payments/{id}/void                         → cancel an auth before capture (no charge)
     → 200 { paymentId, status: VOIDED }

POST /v1/refunds                                     → refund a captured payment (full or partial)
     header: Idempotency-Key: <uuid>
     body:   { paymentId, amount, reason }
     → 202 { refundId, status: PENDING }             → REFUNDED | PARTIALLY_REFUNDED

GET  /v1/payments/{id}                               → poll payment status (+ ledger summary)
     → 200 { paymentId, status, amount, capturedAmount, refundedTotal }

GET  /v1/users/{userId}/payments                     → payment history (cursor paginated)

POST /v1/webhooks/psp                                → PSP callback with final status
     header: PSP-Signature: <hmac>                  (verify signature; dedup by event_id)
     → 200 (always ack a verified event so the PSP stops retrying)
     → 401 (bad/absent signature → drop)
```

> 💡 **Why `202`, not `200`:** `POST /payments` returns **`202 Accepted` + `PENDING`** because the real outcome isn't known yet — the bank confirms *later* via webhook (§5). The client then **polls `GET /payments/{id}`** or waits for its own push. Returning `200 SUCCESS` synchronously is the classic beginner bug.

| Status code | Meaning here |
| --- | --- |
| **202 Accepted** | Payment/refund accepted and `PENDING` — outcome confirmed asynchronously |
| **200 OK** | Idempotent replay, a completed capture/void, or a webhook ack |
| **409 Conflict** | Same idempotency key is still `IN_PROGRESS` (concurrent retry) — retry-after |
| **422 Unprocessable** | Idempotency key reused with a *different* payload |
| **401 Unauthorized** | Webhook signature invalid — reject |

> The **`POST /payments`**, **`/capture`**, **`/refunds`** and **`/webhooks/psp`** endpoints are the ones that **must be idempotent** — retries must never create a second charge, capture, or refund.

---

## 17. Consistency & CAP Tradeoffs

> Interviewers love: "Where do you choose consistency vs availability?" For payments the answer is sharp — **money writes are CP; status reads can be AP.**

| Path | Choice | Why |
| --- | --- | --- |
| **Money writes** (payment + ledger entries) | **CP + ACID** | A debit and its credit must commit **together or not at all**; a duplicated or lost rupee is unacceptable. Correctness > availability. |
| **Status reads** (poll, history) | **AP / eventual** | "Is my payment done yet?" being a second stale is fine — serve from replicas/cache. |
| **Downstream events** (order, notifications, analytics) | **Eventual** | Delivered via outbox → Kafka, at-least-once, consumer-idempotent. |
| **Reconciliation** | **Authoritative backstop** | The nightly settlement-file compare is the *ground truth* that repairs any drift the fast paths missed. |

- Money writes go to a **single source-of-truth** row set in an RDBMS; the debit+credit pair lives in **one transaction** on one shard, so it never needs a distributed commit.
- The system is **eventually consistent across services** (outbox/saga), but **strongly consistent at the ledger**.
- When the fast, available path (webhook) and the correct, authoritative path (reconciliation) disagree, **reconciliation wins** — it's the CP backstop behind the AP conveniences.

> One-liner: **"Strong ACID consistency wherever money moves, eventual consistency everywhere else — with reconciliation as the authoritative backstop that makes the eventual parts safe."**

---

## 18. Scaling & Archival

> Volume is modest (~120/sec, §3) so we don't shard for throughput — we scale for **read load, ledger growth, and years of retention**.

### Reads

- **Read replicas** absorb status polls, history, and balance reads — far more numerous than writes; the write primary stays for money only.
- **Redis** for hot coordination state: `IN_PROGRESS` markers, rate-limit counters (not financial truth).

### Ledger growth (the real scaling pressure)

- The ledger only ever **grows** (append-only, ≥2 rows per payment, never deleted). **Partition `ledger_entries` by month** (`created_at`) so hot recent data stays small and old partitions can be detached cheaply.
- **Cold storage / archival:** partitions older than the hot window (e.g. > 3–6 months) move to cheaper storage (or a data lake / Parquet in object storage) — still queryable for audits and disputes, just not on the fast path. Nothing is ever hard-deleted (compliance retention in years).

### Sharding (only when one primary is genuinely outgrown)

- **Shard by `account_id` (or `merchant_id`)** so all of one account's entries co-locate and a single transaction's debit+credit stay on **one shard** — no cross-shard money moves.
- Sharding by `merchant_id` also isolates a whale merchant's load and eases per-merchant payouts/reconciliation.

> ⚠️ Never pick a shard key that splits the two legs of a ledger transaction across shards — that would force a distributed transaction for every payment, exactly what ACID-on-one-shard avoids.

---

## 19. Worked Example — Auth, Capture, Refund, Chargeback

> One ₹1000 order followed end-to-end. Watch the **payment state** and the **double-entry ledger rows** at each step. (Fee ₹20 for illustration; amounts in whole rupees for readability.)

**Step 1 — Authorize ₹1000** (order placed, funds held, nothing captured yet). Status `INITIATED → AUTHORIZED`. Only *pending* entries; no money has truly moved:

```
txn AUTH (pending hold, informational):
  no final ledger movement yet — the hold lives at the PSP, we record intent
  payment.status = AUTHORIZED, authorized_amount = 1000
```

**Step 2 — Capture ₹1000** (order ships). Webhook confirms `SUCCESS`. *Now* the real balanced entries post:

```
txn CAPTURE:
  DEBIT   user_payment_source   1000     ← money leaves the customer
  CREDIT  merchant_payable       980     ← owed to the merchant
  CREDIT  platform_revenue        20     ← our fee
  ───────────────────────────────────
  debits 1000 == credits 1000  ✓ balanced
  payment.status = CAPTURED, captured_amount = 1000
```

**Step 3 — Partial refund ₹300** (customer returns one item). A **new** reversing transaction — the original stays untouched:

```
txn REFUND (partial):
  DEBIT   merchant_payable       294     ← claw back merchant's share (300 − 6 fee)
  DEBIT   platform_revenue         6     ← refund the proportional fee
  CREDIT  user_payment_source    300     ← money returned to the customer
  ───────────────────────────────────
  debits 300 == credits 300  ✓ balanced
  payment.status = PARTIALLY_REFUNDED, refunded_total = 300
```

**Step 4 — Chargeback on the remaining ₹700** (customer disputes with their bank; bank forces reversal + ₹15 dispute fee). Again new entries, plus a `disputes` case:

```
txn CHARGEBACK (bank-forced reversal of the un-refunded ₹700):
  DEBIT   merchant_payable       686     ← claw back merchant's remaining share
  DEBIT   platform_revenue        14     ← reverse remaining fee
  CREDIT  user_payment_source    700     ← funds returned to customer via the bank
  ───────────────────────────────────
  debits 700 == credits 700  ✓ balanced

txn DISPUTE_FEE (penalty charged to the merchant):
  DEBIT   merchant_payable        15     ← merchant eats the dispute fee
  CREDIT  platform_revenue        15
  ───────────────────────────────────
  debits 15 == credits 15  ✓ balanced
  payment.status = DISPUTED
```

**The invariant across all four steps:** *every* transaction balances to zero on its own, the original charge is **never edited**, and summing all entries for `user_payment_source` gives `1000 − 300 − 700 = 0` — the customer ended up paying nothing, and the ledger proves it, line by line.

---

## 20. How to Drive the Interview (framework)

> Use this order so you never freeze. Spend ~5 min framing, then go deep on the two hard parts.

1. **Lead with the crux** — say up front: *"the core is an exactly-once financial effect — **never double-charge** and **money is conserved** — everything else serves that."* — §1, §2
2. **Requirements + NFRs** — correctness/consistency as the top NFR, not throughput — §2
3. **Capacity** — "small numbers, hard problem": modest QPS, every write must be perfect — §3
4. **APIs + architecture + data model** — `Idempotency-Key`, `202 PENDING`, RDBMS + ledger — §16, §4, §12
5. **Deep dive #1 (~10 min): idempotency + the double-entry ledger** — the two commandments, in code — §6, §7
6. **Deep dive #2 (~5 min): async gateways — webhooks, reconciliation, PSP failover** — truth arrives late — §8
7. **Edge cases** — auth/capture, refunds/chargebacks, wallets, scaling/archival — §5, §9, §10, §18
8. **Summarize tradeoffs** — CP for money, eventual for reads, reconciliation as backstop — §17, §15

> 🎤 **Land it in one sentence:** *"I never double-charge (idempotency key + IN_PROGRESS + PSP key), money is always conserved (balanced double-entry ledger), and since the truth arrives late I treat the sync reply as provisional and lean on webhook → poll → reconciliation."*

---

## 21. Design Patterns (that can be used)

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

## 22. Final Takeaways

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
