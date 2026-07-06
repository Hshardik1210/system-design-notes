# Outbox & Saga Patterns

> **In one line:** **Outbox** makes event publishing reliable (DB ↔ messaging); **Saga** keeps data consistent across multiple services. Combined with **Idempotency**, they make distributed systems actually work.

---

## Contents

- [1. Outbox Pattern](#1-outbox-pattern)
- [2. How the Outbox Works](#2-how-the-outbox-works)
- [3. The Background Worker — Is it a scheduler?](#3-the-background-worker--is-it-a-scheduler)
- [4. Tick-Based Processing](#4-tick-based-processing)
- [5. Concurrency — Only One Worker per Row](#5-concurrency--only-one-worker-per-row)
- [6. Delivery Guarantees & Consumer Idempotency](#6-delivery-guarantees--consumer-idempotency)
- [7. Event Ordering](#7-event-ordering)
- [8. Outbox Table Cleanup](#8-outbox-table-cleanup)
- [9. Saga Pattern](#9-saga-pattern)
- [10. Saga Walkthrough — Create Order](#10-saga-walkthrough--create-order)
- [11. Two Ways to Implement Saga](#11-two-ways-to-implement-saga)
- [12. Saga Isolation Gotcha](#12-saga-isolation-gotcha)
- [13. Retriable vs Compensatable Steps (Pivot Transaction)](#13-retriable-vs-compensatable-steps-pivot-transaction)
- [14. When a Compensation Fails](#14-when-a-compensation-fails)
- [15. How Outbox + Saga + Idempotency Fit Together](#15-how-outbox--saga--idempotency-fit-together)
- [16. Interview Cheat Sheet](#16-interview-cheat-sheet)
- [17. Final Takeaways](#17-final-takeaways)

---

## 1. Outbox Pattern

### Problem it solves

> "What if I update the DB successfully, but fail to send the event/message?"

#### Real scenario

Your service does two things: (1) save the order in DB, (2) publish `OrderCreated` to Kafka. Either one can fail independently.

**Case 1 — DB ✅, Kafka ❌**

- DB says: order exists
- Other services (payment, inventory) don't know ❌

👉 Order exists but nobody is notified → **inconsistent**.

**Case 2 — Kafka ✅, DB ❌**

- Event was published, order was never saved

👉 Other services think the order exists, but it doesn't → **inconsistent**.

#### Root problem

> You're trying to update **two systems (DB + Kafka) without a single shared transaction.** There's no atomic "do both or neither."

### The solution

> Write everything to the **DB first**, then publish reliably **from there**.

---

## 2. How the Outbox Works

### Step 1 — Inside the same DB transaction

```
BEGIN TRANSACTION
  1. Insert into orders table
  2. Insert into outbox table (event)
COMMIT
```

**Example state after commit:**

`orders` table:


| order_id | status  |
| -------- | ------- |
| 101      | CREATED |


`outbox` table:


| event_id | event_type    | payload             | status  |
| -------- | ------------- | ------------------- | ------- |
| 1        | ORDER_CREATED | `{ order_id: 101 }` | PENDING |


### Step 2 — Background worker

1. Reads the `outbox` table
2. Publishes the event to Kafka
3. Marks the row as `SENT`

### Why this works

The DB transaction guarantees atomicity:

- Either **both** order + event are saved ✅
- Or **neither** is ❌

👉 No inconsistency possible.

> **Simple analogy:** Outbox = "write the message in a notebook before sending the email." Even if the email fails, the message is still saved.

### Downsides & alternatives

> Be ready for "what are the **downsides** of the outbox?" and "what **else** could you do?"

**Downsides:**

- **Added latency** — events are published *after* the DB commit + worker pickup (not instant).
- **Extra moving part** — the relay/worker is one more thing to run and monitor.
- **At-least-once** — duplicates are possible → consumers must be idempotent (see §6).
- **DB load** — the outbox table is written on every business write and polled continuously.

**Alternatives:**

| Approach | Idea | Trade-off |
| --- | --- | --- |
| **Transactional outbox** (this note) | event row in same txn, relay publishes | simple, reliable, slight latency |
| **CDC / log tailing** (Debezium) | stream the DB commit log → Kafka | no app polling, but infra-heavy |
| **Event sourcing** | the event log *is* the source of truth | powerful but a big paradigm shift |
| **"Listen to yourself"** | service publishes, then consumes its own event to update its DB | avoids dual write, adds eventual consistency on own state |

> For most systems the **transactional outbox** is the pragmatic default; reach for CDC only at high scale.

---

## 3. The Background Worker — Is it a scheduler?

> **Short answer:** It *can* be a scheduler, but it doesn't have to be. In production it's usually a **continuous polling worker**, not a traditional scheduler.

The worker is simply a process that:

1. Reads records from the `outbox` table
2. Sends them to Kafka / message queue
3. Marks them as `SENT`

### Option 1 — Scheduler (cron-job style)

Runs on a fixed schedule (e.g. every 5 seconds):

```
Every 5 seconds:
  → SELECT * FROM outbox WHERE status = 'PENDING'
  → Publish events
  → Mark as SENT
```


| Pros                 | Cons                                          |
| -------------------- | --------------------------------------------- |
| Simple to implement  | Adds latency (event waits up to the interval) |
| Easy to reason about | Not real-time                                 |


### Option 2 — Continuous polling worker (most common)

```
while (true):
    fetch batch of PENDING events
    process them
    sleep for a small time (e.g. 100ms)
```


| Pros                    | Cons                  |
| ----------------------- | --------------------- |
| Near real-time          | Slightly more complex |
| More efficient at scale |                       |


### Option 3 — Event-driven / CDC (advanced)

Uses DB triggers or **Change Data Capture** tools like **Debezium**:

```
DB change → CDC → Kafka directly
```

👉 No polling needed.

### What big companies use


| System scale       | Approach             |
| ------------------ | -------------------- |
| Simple systems     | Scheduler or polling |
| High-scale systems | CDC (Debezium)       |


---

## 4. Tick-Based Processing

> A **tick** is a periodic trigger (every X ms/s) that wakes the worker to do work. **Tick-based processing is just polling, framed differently.**

### Tick-based worker flow

```
Loop:
  wait for tick (e.g. 100ms)
  fetch N records from outbox where status = PENDING
  process them
```

**Pseudo-code:**

```
while (true):
    sleep(100ms)   ← tick

    events = SELECT * FROM outbox
             WHERE status = 'PENDING'
             LIMIT 10

    for event in events:
        process(event)
```

### Tick vs Polling — are they different?

Not really. **Tick = controlled polling frequency.**


| Concept        | Reality                              |
| -------------- | ------------------------------------ |
| Tick-based     | Polling with a fixed interval        |
| Polling worker | Same thing, sometimes a tighter loop |


### Choosing the tick interval


| Tick interval | Effect                    |
| ------------- | ------------------------- |
| 10ms          | Low latency, high DB load |
| 100ms         | Good balance (common)     |
| 1s            | Low load, higher latency  |


### Production-grade improvement — adaptive polling

```
if events found:
    process immediately (no sleep)
else:
    sleep longer

# Even better — dynamic sleep:
#   backlog high → reduce sleep
#   empty        → increase sleep
```

### Where tick-based fits


| System type               | Recommended?               |
| ------------------------- | -------------------------- |
| Small system              | ✅ Yes                      |
| Medium scale              | ✅ Yes (with optimizations) |
| High scale (millions/sec) | ❌ Use CDC (Debezium)       |


---

## 5. Concurrency — Only One Worker per Row

> ⚠️ If multiple workers run, they may pick the **same rows**. You must prevent duplicate processing.

### Option A — Status update

```sql
UPDATE outbox
SET status = 'PROCESSING'
WHERE id = ?
```

### Option B — Row locking (better)

```sql
SELECT * FROM outbox
WHERE status = 'PENDING'
FOR UPDATE SKIP LOCKED
LIMIT 10
```

👉 `FOR UPDATE SKIP LOCKED` ensures each worker gets **different** rows → no duplicate processing.

### Full flow with worker

```
1. API writes order + outbox event (one transaction)
2. Worker picks event
3. Publishes to Kafka
4. Marks event as SENT
```

> **Interview-ready answer:** "It's typically a background worker that continuously polls the outbox table. A scheduler works for simpler setups, but polling or CDC-based approaches are preferred for lower latency and scalability."

### Mental model

```
Scheduler     = "check occasionally"
Polling/Tick  = "keep checking continuously"
CDC           = "react instantly to change"
```

---

## 6. Delivery Guarantees & Consumer Idempotency

> 🔑 **The key link to your Idempotency notes.** The outbox guarantees an event is **never lost**, but it can publish the **same event more than once** → this is **at-least-once** delivery.

### How duplicates happen

```
1. Worker reads PENDING event
2. Publishes to Kafka ✅
3. Worker CRASHES before marking SENT ❌
4. On restart → row is still PENDING → publishes AGAIN
```

So the consumer sees `ORDER_CREATED` **twice**.

### Why the outbox is at-least-once (not exactly-once)

You cannot atomically "publish to Kafka" **and** "mark the row SENT" in one transaction — they are two different systems. So one must happen first, and a crash in between causes a duplicate.


| Guarantee         | Reality with outbox                         |
| ----------------- | ------------------------------------------- |
| At-most-once      | ❌ risks losing events                       |
| **At-least-once** | ✅ what outbox gives you                     |
| Exactly-once      | ❌ practically impossible across DB + broker |


### The fix: idempotent consumers

> Every event carries a unique `event_id`. Consumers track processed IDs and **skip duplicates**.

```
on event:
  if event_id already processed → skip
  else → process + record event_id
```

👉 This is exactly the **idempotency** pattern from your other notes, applied at the **consumer** side.

---

## 7. Event Ordering

> ⚠️ Outbox events can be **published or consumed out of order** — especially with multiple workers or partitions.

Example problem:

```
OrderUpdated (v2) processed before OrderCreated (v1)  ❌
```

### Common fixes

- **Per-aggregate ordering key** — partition Kafka by `order_id` so all events for one order go to the same partition (preserves order *within* that order).
- **Sequence numbers / versions** — attach a monotonic `version` to each event; consumer ignores stale/older versions.
- **Single-threaded per key** — process events for the same aggregate sequentially.

> Global ordering is expensive and rarely needed. **Per-aggregate ordering is usually enough.**

---

## 8. Outbox Table Cleanup

> 📈 `SENT` rows accumulate forever and slow down the table. You need an archival / TTL job (same idea as the TTL section in your Idempotency notes).

### Options


| Strategy            | Description                                            |
| ------------------- | ------------------------------------------------------ |
| Delete after send   | Remove the row once `SENT` (simplest, no history)      |
| TTL / retention job | Periodically delete `SENT` rows older than N days      |
| Archive table       | Move `SENT` rows to a cold/archive table for audit     |
| Partitioning        | Time-partition the outbox; drop old partitions cheaply |


```sql
DELETE FROM outbox
WHERE status = 'SENT'
  AND created_at < NOW() - INTERVAL '7 days'
```

> Keep a short retention window for debugging/replay, then clean up. Don't let the hot path query a giant table.

---

## 9. Saga Pattern

### Problem it solves

> "How do I manage multi-step workflows across services?"

In a **monolith + single DB**, you could do:

```
BEGIN TRANSACTION
  - create order
  - reserve inventory
  - charge payment
COMMIT
```

👉 Everything succeeds or everything rolls back ✅

But in **distributed systems**, each step lives in a different service + DB:

```
Order Service     → DB1
Inventory Service → DB2
Payment Service   → DB3
```

👉 You **cannot** have one global transaction.

### The solution

> Break the big transaction into smaller steps, and define a **compensating action (undo)** for each step.

### Why not 2PC (two-phase commit)?

> 🎯 **Classic interview question:** "Why not just use a distributed transaction?" 2PC *is* the textbook way to make multiple systems commit atomically — but it's avoided at scale.

**How 2PC works:** a **coordinator** asks every participant to *prepare* (phase 1), then tells all to *commit* (phase 2). All commit or all abort.

| 2PC | Saga |
| --- | --- |
| **Synchronous, blocking** — participants hold locks until everyone is ready | **Async, non-blocking** — each step commits independently |
| **Coordinator is a SPOF** — if it dies mid-protocol, participants are stuck | No global coordinator required (or a recoverable one) |
| **Poor scalability / availability** — locks + network round trips | Scales well, high availability |
| **Strong/atomic** consistency | **Eventual** consistency (via compensations) |
| Needs all participants to support it (XA) | Works across heterogeneous services |

> **One-liner:** "2PC gives atomicity but blocks and doesn't scale; Saga trades atomicity for availability and uses compensations to reach eventual consistency."

---

## 10. Saga Walkthrough — Create Order

### Successful flow

```
Order Created ✅
→ Inventory Reserved ✅
→ Payment Success ✅
→ Order Confirmed ✅
```

### Failure case (where Saga matters)

```
Order Created ✅
→ Inventory Reserved ✅
→ Payment FAILED ❌
```

Now: inventory is stuck, order is half-done.

### Compensation mapping


| Step              | Action        | Compensation  |
| ----------------- | ------------- | ------------- |
| Create Order      | create order  | cancel order  |
| Reserve Inventory | reserve stock | release stock |
| Charge Payment    | charge        | refund        |


### Saga rollback flow

```
1. Create Order ✅
2. Reserve Inventory ✅
3. Payment FAILED ❌
   → 4. Release Inventory 🔁
   → 5. Cancel Order 🔁
```

👉 System becomes consistent again.

> ⚠️ **Saga does NOT roll back automatically like a DB.** You must explicitly write the compensation logic.

---

## 11. Two Ways to Implement Saga

### 1️⃣ Choreography (event-driven)

No central controller — services react to events.

```
Order Service     → emits OrderCreated
Inventory Service → listens → reserves stock → emits InventoryReserved
Payment Service   → listens → charges → emits PaymentSuccess / PaymentFailed
```

**Failure case:**

```
PaymentFailed ❌
→ Inventory Service releases stock
→ Order Service marks order FAILED
```

**Key idea:** each service reacts to events and decides what to do next.

### 2️⃣ Orchestration (central controller)

One service controls the whole flow.

```
Orchestrator:
  1. Call Order Service
  2. Call Inventory Service
  3. Call Payment Service
```

**Failure case:**

```
Payment fails ❌
Orchestrator:
  → Call Inventory Service (release stock)
  → Call Order Service (cancel order)
```

**Key idea:** one brain controls the whole flow.

### Choreography vs Orchestration


| Feature    | Choreography | Orchestration    |
| ---------- | ------------ | ---------------- |
| Control    | Distributed  | Centralized      |
| Complexity | Hidden       | Explicit         |
| Debugging  | Hard         | Easier           |
| Coupling   | Loose        | Slightly tighter |


> **Real-life analogy:** Booking a trip — book flight → book hotel → pay. If payment fails: cancel hotel → cancel flight. That's Saga.

### Orchestrator must persist saga state (crash recovery)

> ❓ "What happens if the **orchestrator crashes** halfway through?" It must be able to resume — so saga state has to be **durable**, not in memory.

- Persist a **saga log / saga instance** row: which step we're on, step statuses, payloads.

```
saga_id | type          | current_step      | status      | data
--------|---------------|-------------------|-------------|------
abc123  | CREATE_ORDER  | CHARGE_PAYMENT    | IN_PROGRESS | {...}
```

- On restart, the orchestrator **reads the log and continues** (re-issue the pending step, or run compensations if it was rolling back).
- Each step + compensation must be **idempotent** so re-running after a crash is safe.
- This is what frameworks like **Temporal / Camunda / AWS Step Functions** give you out of the box (durable workflow state + retries).

> **Choreography equivalent:** there's no central log — each service persists its own state and relies on events being redelivered (Kafka offsets / retries) to recover.

---

## 12. Saga Isolation Gotcha

> ⚠️ **Saga has no isolation (the "I" in ACID is missing).** Other transactions can see **intermediate state** while a saga is mid-flight.

Example problem:

```
Saga reserves inventory → (not yet paid)
Another request reads stock → sees it as available/unavailable inconsistently
→ overselling or dirty reads
```

### Countermeasures (semantic locks)


| Technique               | Idea                                                                             |
| ----------------------- | -------------------------------------------------------------------------------- |
| **Semantic lock**       | Mark the record with a `PENDING` / `reserved` flag so others know it's in-flight |
| **Commutative updates** | Design ops so order doesn't matter (e.g. `+1 / -1` instead of `set`)             |
| **Reread value**        | Re-check state before acting, abort if changed                                   |
| **By-status filtering** | Hide/ignore records still in an intermediate state                               |


> The reserved-but-not-paid inventory is the classic case — a `RESERVED` status flag is the simplest semantic lock.

---

## 13. Retriable vs Compensatable Steps (Pivot Transaction)

> Not every step is undoable. Saga steps fall into three categories around the **pivot** — the point of no return.


| Type              | Meaning                                                      | Example                       |
| ----------------- | ------------------------------------------------------------ | ----------------------------- |
| **Compensatable** | Can be undone later                                          | Reserve inventory (→ release) |
| **Pivot**         | The commit point; success makes the saga go forward          | Charge payment                |
| **Retriable**     | Must eventually succeed (only after pivot), cannot be undone | Confirm order, send receipt   |


### How it changes failure handling

```
Before pivot fails → COMPENSATE backwards (undo previous steps)
After pivot fails  → RETRY forward (keep retrying until success)
```

👉 Design sagas so all **compensatable** steps come **before** the pivot, and all **retriable** steps come **after**.

---

## 14. When a Compensation Fails

> A compensation is itself a distributed call — it can fail too. You **cannot** just give up, or you'll leave inconsistent state.

### Handling strategies

- **Make compensations idempotent + retry** — keep retrying `release stock` / `refund` safely.
- **Dead-letter queue (DLQ)** — park failed compensations for later reprocessing.
- **Backoff + alerting** — escalate after N retries.
- **Manual intervention / ops dashboard** — last resort for stuck sagas (e.g. a failed refund).

> Rule of thumb: **compensations must always eventually succeed.** That's why they're designed to be retriable and idempotent.

---

## 15. How Outbox + Saga + Idempotency Fit Together

### Full system flow

```
Client → Order API (Idempotency)
       → DB + Outbox (one transaction)
       → Kafka
       → Saga starts
```

**Step 1 — API layer (Idempotency)**

- Prevent duplicate orders
- Ensure safe retries

**Step 2 — DB + Outbox (one transaction)**

- Create order
- Write `OrderCreated` event to outbox

**Step 3 — Event processing (Saga starts)**

```
OrderCreated → Inventory → Payment → Final status
```

**Step 4 — Saga handles failures**

```
PaymentFailed → rollback inventory → order marked FAILED
```

### Why these patterns matter together


| Problem                          | Solution    |
| -------------------------------- | ----------- |
| Duplicate requests               | Idempotency |
| Lost events                      | Outbox      |
| Partial failures across services | Saga        |


### Real-world mental model

Placing an order:

1. You place the order → **idempotency** ensures no duplicates
2. System records everything → **outbox** ensures nothing is lost
3. Multiple steps happen → **saga** ensures consistency

---

## 16. Interview Cheat Sheet

> **"What are Outbox and Saga?"**
>
> "The **Outbox pattern** ensures reliable event publishing by storing events in the **same database transaction** as the business data, then asynchronously sending them later.
>
> The **Saga pattern** manages distributed transactions by breaking them into smaller steps and defining **compensating actions** for failures."

> **"Can we use a tick to process the outbox?"**
>
> "Yes — a tick-based approach is essentially polling the outbox at fixed intervals. It's simple and common but adds latency based on the interval. At scale, systems move to adaptive polling or CDC (e.g. Debezium) for near real-time processing."

> **"Explain the Saga pattern."**
>
> "Saga manages distributed transactions by breaking them into smaller steps with compensating actions per step. If any step fails, completed steps are undone via compensations to maintain consistency."

> **"Does the outbox give exactly-once delivery?"**
>
> "No — it's **at-least-once**. You can't atomically publish to the broker and mark the row sent, so a crash in between causes duplicates. That's why **consumers must be idempotent** (dedup on `event_id`)."

> **"Saga is ACID, right?"**
>
> "It has no **isolation** — other transactions can see intermediate state. You handle this with **semantic locks** (status flags like `RESERVED`). It provides **eventual** consistency, not atomic isolation."

> **"Why not use 2PC / a distributed transaction?"**
>
> "2PC gives atomicity but is **synchronous, blocking**, has a **coordinator SPOF**, and **doesn't scale**. Saga trades atomicity for availability — independent local commits + **compensations** for eventual consistency."

> **"What if the orchestrator crashes mid-saga?"**
>
> "Saga state is **persisted to a saga log**, so on restart it resumes the pending step or runs compensations. Steps are idempotent so re-running is safe — this is what Temporal / Step Functions provide."

### Final mental model

```
DB transaction → automatic rollback
Saga           → manual rollback using compensations
```

---

## 17. Final Takeaways

- **Outbox** = reliability between DB and messaging
- **Saga** = consistency across multiple services
- **Idempotency** = safety for retries
- Outbox worker can be a **scheduler**, but production prefers a **polling/tick worker** or **CDC**
- Always use `FOR UPDATE SKIP LOCKED` (or equivalent) to avoid duplicate processing
- Outbox is **at-least-once** → consumers **must be idempotent** (dedup on `event_id`)
- Preserve **per-aggregate ordering** (partition by `order_id`) and **clean up** `SENT` rows
- Saga has **no isolation** → use **semantic locks**; order steps around the **pivot**
- Saga compensation is **manual**, must be **idempotent**, and must **eventually succeed**
- Prefer **Saga over 2PC** at scale (2PC blocks + coordinator SPOF); persist a **saga log** for crash recovery

👉 Together, these three patterns make distributed systems actually work.