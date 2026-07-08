# Outbox & Saga Patterns

> **In one line:** **Outbox** makes event publishing reliable (DB ↔ messaging); **Saga** keeps data consistent across multiple services. Combined with **Idempotency**, they make distributed systems actually work.

> **How to read this doc:** each section gives the dense summary/interview version first, then a **deep dive** (annotated example code/SQL, and the exact confusions that trip people up while learning). Skim the summaries for revision; read the deep dives to actually understand.

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

### The dual-write problem

Your service needs to do TWO things whenever an order is placed:

1. Save the order in its own database.
2. Notify the rest of the system ("payment", "inventory") by publishing an `OrderCreated` message to Kafka.

These are **two separate systems** (a database and a message broker). There is no single operation that does "both, or neither." So whenever you do them one after another, a crash in the gap leaves you half-done. **Writing to two systems with no shared transaction = the dual-write problem.**

Concretely: if the process is interrupted after the DB write but before publishing, the order exists but no event goes out. If you publish first but the DB write fails, consumers act on an order that was never saved. **Two separate actions, no safety net.**

The outbox trick: write **both** the business row AND an event row into the **same database** in one transaction (insert into `orders` + insert into `outbox`). Because it's a single transaction, you can never end up with the order but no event row, or vice versa. **Later**, a background relay reads the outbox and publishes the events to Kafka. Publishing can be slow or retried, but nothing is ever lost, because the intent to send was saved *atomically* with the business data.

#### Q: Why can't I just publish to Kafka inside my DB transaction?

Because a DB transaction only covers **that one database**. `COMMIT` guarantees rows in *your* DB are all-or-nothing — it has zero power over Kafka, which is a completely separate system. There is no cross-system commit here, so "DB + Kafka atomically" simply isn't a thing you can ask for.

#### Q: Then why does the outbox actually fix it?

Because the outbox turns "DB + Kafka" (two systems, impossible to do atomically) into "DB + DB" (**the `orders` row and the `outbox` row live in the same database**), which one ordinary transaction *can* do atomically. The risky "talk to Kafka" part is moved **out** of the critical write and handed to a background worker that retries until it succeeds.

#### Q: Isn't the event now delayed?

Yes — slightly. The event is published *after* the DB commits and *after* the worker picks it up, so it's not instant (see the **Downsides** table below). You trade a little latency for a strong guarantee: the event is **never silently lost**.

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

> **In short:** Outbox = persist the event in the DB before publishing it. Even if publishing fails, the event is still saved and can be retried.

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

### The outbox table + relay, in code

There are only two moving pieces: (1) the **write** that saves business data and the event together in one transaction, and (2) the **relay** (background worker) that later reads the outbox and publishes.

**The outbox table — just a to-do list of events to send:**

```sql
CREATE TABLE outbox (
    event_id     BIGSERIAL PRIMARY KEY,   -- unique id per event (used later for dedup)
    aggregate_id VARCHAR(64),             -- e.g. the order_id this event is about
    event_type   VARCHAR(64),             -- e.g. 'ORDER_CREATED'
    payload      JSONB,                    -- the event body: { "order_id": 101, ... }
    status       VARCHAR(16) DEFAULT 'PENDING',  -- PENDING → SENT
    created_at   TIMESTAMPTZ DEFAULT now()
);
```

**The write — business row + event row in ONE transaction (the whole point):**

```java
@Transactional   // Spring: everything in here commits together, or nothing does
public void createOrder(OrderRequest req) {
    // 1. the actual business change
    Order order = orderRepo.save(new Order(req));      // INSERT into orders

    // 2. the event, written to the SAME database, in the SAME transaction
    OutboxEvent event = new OutboxEvent(
        order.getId(),                                  // aggregate_id
        "ORDER_CREATED",
        toJson(Map.of("order_id", order.getId())),      // payload
        "PENDING"
    );
    outboxRepo.save(event);                            // INSERT into outbox

    // when this method returns, COMMIT makes BOTH rows durable together.
    // NOTHING has been sent to Kafka yet — that's the relay's job.
}
```

**The relay — a loop that drains the outbox and publishes:**

```java
@Scheduled(fixedDelay = 100)   // wake up frequently (see §3/§4 on polling vs tick)
public void relay() {
    // grab a batch of unsent events (SKIP LOCKED so multiple workers don't clash — see §5)
    List<OutboxEvent> batch = outboxRepo.findPendingForUpdateSkipLocked(100);

    for (OutboxEvent e : batch) {
        kafka.send(e.getEventType(), e.getAggregateId(), e.getPayload());  // publish
        e.setStatus("SENT");                                               // mark done
        outboxRepo.save(e);
    }
    // if we crash AFTER publish but BEFORE marking SENT, the row stays PENDING
    // and gets published AGAIN on restart → at-least-once delivery (see §6).
}
```

That's the entire pattern: **one transaction writes intent, one loop fulfils it.** Everything else in this doc (workers, locking, ordering, cleanup) is just making that relay robust.

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

### Why two workers fight, and how SKIP LOCKED fixes it

If multiple relay workers run at once, they all `SELECT ... WHERE status = 'PENDING'`, see the same rows, and all publish them → duplicates and wasted work.

`FOR UPDATE SKIP LOCKED` is the rule "**each worker takes only rows nobody else is currently holding**":

```sql
SELECT * FROM outbox
WHERE status = 'PENDING'
ORDER BY created_at
FOR UPDATE          -- lock the rows I'm reading so no one else can grab them
SKIP LOCKED         -- ...and if a row is already locked by another worker, skip past it
LIMIT 10;
```

- `FOR UPDATE` = "I'm claiming these rows; lock them until my transaction ends."
- `SKIP LOCKED` = "don't wait for rows someone else already claimed — just move on to the next free ones."

So worker A grabs rows 1–10, worker B *skips* those (they're locked) and grabs 11–20, worker C grabs 21–30. **No worker ever picks a row another worker is already holding → no double-publish from the workers themselves.**

#### Q: Without SKIP LOCKED, wouldn't `FOR UPDATE` alone be safe?

It would be *correct* but *slow*: plain `FOR UPDATE` makes worker B **block and wait** for worker A to finish those rows, so your workers effectively serialize instead of running in parallel. `SKIP LOCKED` lets them work on disjoint batches simultaneously — safety *and* throughput.

#### Q: If workers can't double-pick, why are duplicates still possible?

Because the duplicate risk that remains isn't between workers — it's the **crash-after-publish-before-SENT** gap covered in §6. Locking stops two workers from grabbing the same row *at the same time*; it can't undo an event already sent when the worker dies before recording `SENT`. That's why delivery is still **at-least-once** and consumers must be idempotent.

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

### "At-least-once" and the idempotent consumer

**What the three guarantees really mean, plainly:**

- **At-most-once** = the event is delivered zero or one times. Never duplicates, but can *lose* events. Unacceptable when the event matters (an order!).
- **At-least-once** = the event is delivered one or more times. Never loses, but can *repeat*. **This is what the outbox gives you.**
- **Exactly-once** = delivered once, guaranteed. Ideal — but impossible to truly achieve across a DB and a broker, because you can't atomically publish *and* mark `SENT`.

The duplicate happens as described above: the worker publishes, then crashes before marking the row `SENT`; on restart the row is still `PENDING`, so it publishes again. Nothing is lost (good), but a duplicate arrives (the price we pay).

**The fix lives at the reader, not the sender.** Since we can't stop duplicates from being *sent*, we make them *harmless to receive*. Every event carries a unique `event_id`; the consumer remembers which ids it has already handled and ignores repeats:

```java
@KafkaListener(topics = "orders")
public void onOrderCreated(OrderEvent e) {
    // THE idempotency check: have we already processed this exact event?
    if (processedEvents.contains(e.getEventId())) {
        return;   // duplicate → do nothing (safe to receive twice)
    }

    // do the real work exactly once...
    inventoryService.reserve(e.getOrderId());

    // ...and remember we handled it, so a redelivery is a no-op
    processedEvents.add(e.getEventId());   // often an INSERT into a processed_events table
}
```

#### Q: Why not just make the outbox exactly-once and skip all this?

Because "publish to Kafka" and "mark the row SENT" touch two different systems, so there's no way to do both atomically — a crash in the gap *always* leaves a window for a duplicate. Rather than chase an impossible exactly-once *delivery*, we accept at-least-once delivery and get exactly-once *effect* by making the consumer idempotent. Cheaper, simpler, and it actually works.

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

### What a saga is and why we need it

**The problem, plainly.** In a single app with one database, "create order + reserve inventory + charge payment" can all sit inside one `BEGIN ... COMMIT`. If payment fails, the database *automatically* undoes the order and the inventory — free rollback. But once those three steps live in **three different services with three different databases**, there is no shared transaction and no automatic undo. If step 3 fails, steps 1 and 2 have already **committed for real** in their own databases. You're stuck with a half-finished order.

Take the order saga concretely:

1. Create order. ✅ (committed in the Order service DB)
2. Reserve inventory. ✅ (committed in the Inventory service DB)
3. Charge payment. ❌ — the card is declined.

There is no global rollback. The order and inventory reservation are *already committed*. To get back to a clean state you must **explicitly undo each completed step, in reverse**: release the inventory, then cancel the order. **That deliberate sequence of "do a step, and know how to undo it" is a saga.** Each step has a paired **compensating action** (its undo):

```
Step                Do               Compensation (undo)
------------------  ---------------  -------------------
Create order        create order     cancel order
Reserve inventory   reserve stock    release stock
Charge payment      charge card      refund
```

#### Q: Why not just use a distributed transaction (2PC)?

2PC *can* make several systems commit atomically, but it does so by holding **locks** on every participant while a central **coordinator** runs a prepare→commit handshake. That blocks, scales poorly, and if the coordinator dies mid-protocol everyone is stuck (a single point of failure). At scale we prefer the saga: each step commits **independently and immediately**, and we clean up failures with compensations. We trade *strong atomic* consistency for *eventual* consistency plus high availability.

#### Q: If steps already committed, "rollback" isn't a real rollback — right?

Correct, and this is the mental leap. A saga does **not** rewind time like a database `ROLLBACK`. The order really was created; the inventory really was reserved. Compensation issues **new forward actions** that *semantically* cancel the old ones (a refund is a new transaction, not an erasure of the charge). The end state looks "as if nothing happened," but the history shows both the action and its compensation.

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

### Compensating transactions, in code

A **compensating transaction** is just "the undo function you write yourself for a step." The trick is that undo runs in **reverse order** — you unwind the most recent successful step first.

```java
public void createOrderSaga(OrderRequest req) {
    // remember what we've done so we know what to undo if a later step fails
    Deque<Runnable> compensations = new ArrayDeque<>();

    try {
        Long orderId = orderService.create(req);
        compensations.push(() -> orderService.cancel(orderId));      // undo for step 1

        inventoryService.reserve(orderId);
        compensations.push(() -> inventoryService.release(orderId)); // undo for step 2

        paymentService.charge(orderId);   // ❌ if this throws, we compensate everything above
        compensations.push(() -> paymentService.refund(orderId));    // undo for step 3

        orderService.confirm(orderId);    // all good → saga complete
    } catch (Exception failure) {
        // run the undos in REVERSE order (release inventory, THEN cancel order)
        while (!compensations.isEmpty()) {
            compensations.pop().run();   // each undo must be idempotent + retriable (see §14)
        }
    }
}
```

**Key beginner points hiding in here:**

- The `push` order builds a stack, so `pop` naturally gives you **reverse (LIFO) order** — the most recent step is undone first (release inventory before cancelling the order).
- We only compensate steps that **actually succeeded**. If `reserve` never ran, there's nothing to release.
- Each compensation is a **real distributed call that can itself fail**, so it must be safe to retry — see §14.

#### Q: Why reverse order — does it matter?

Usually yes. Later steps often depend on earlier ones (you can't refund a payment that was never charged; releasing inventory before cancelling the order keeps the order's view sane). Undoing newest-first mirrors how you got into the state and avoids acting on things that no longer exist.

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


> **Recap:** a saga is a sequence of local transactions, each with a compensating action; if a step fails, previously completed steps are undone via their compensations.

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

### Orchestration vs choreography

Both get the same job done; they differ in **who holds the plan**.

- **Orchestration = a central controller.** One service (the *orchestrator*) holds the whole flow and calls each service in turn: reserve inventory, then charge payment, then confirm. If a step fails, the orchestrator decides what to compensate. A single component always knows the current state.
- **Choreography = event-driven, no controller.** Each service reacts to events ("`InventoryReserved` arrived → I'll charge payment") and emits its own events. Coordination emerges from services reacting to each other's *events*, but no single component holds the full picture.

**Orchestration — one service issues commands and handles failure centrally:**

```java
// The ORCHESTRATOR is the single brain. It calls each service in turn.
public void run(OrderRequest req) {
    Long orderId = orderService.create(req);
    try {
        inventoryService.reserve(orderId);
        paymentService.charge(orderId);      // pivot — see §13
        orderService.confirm(orderId);
    } catch (Exception e) {
        // the orchestrator KNOWS the whole flow, so it drives compensations itself
        inventoryService.release(orderId);
        orderService.cancel(orderId);
    }
}
```

**Choreography — no central brain; services react to each other's events:**

```java
// Each service only knows "when I see event X, I do Y and emit Z". No one owns the flow.

// Inventory service:
@KafkaListener(topics = "OrderCreated")
void onOrderCreated(OrderEvent e) {
    inventory.reserve(e.orderId());
    kafka.send("InventoryReserved", e.orderId());   // hand the baton onward
}

// Payment service:
@KafkaListener(topics = "InventoryReserved")
void onInventoryReserved(OrderEvent e) {
    if (payment.charge(e.orderId())) kafka.send("PaymentSucceeded", e.orderId());
    else                            kafka.send("PaymentFailed",   e.orderId());
}

// Inventory again — reacts to failure by compensating itself:
@KafkaListener(topics = "PaymentFailed")
void onPaymentFailed(OrderEvent e) {
    inventory.release(e.orderId());   // its own undo, triggered by an event
}
```

#### Q: When should I pick which?

- **Orchestration** when the flow is **complex, has many steps, or needs clear visibility/debugging** — one place holds the state machine, so it's easy to see "we're stuck on CHARGE_PAYMENT." Slightly tighter coupling (services must expose commands), and the orchestrator must persist its state (see the subsection above). Tools: Temporal, Camunda, AWS Step Functions.
- **Choreography** when you want **loose coupling and few steps** — services just publish/subscribe to events and don't know about each other. Great for autonomy, but the workflow is **implicit and spread across services**, which makes it hard to debug ("who's supposed to react next?") and easy to accidentally create event spaghetti.

#### Q: "Distributed vs centralized control" — what does that actually mean here?

In **choreography** the decision logic is *distributed*: each service independently decides its next move from the events it sees. In **orchestration** it's *centralized*: one orchestrator decides every next move. Same steps, same compensations — the only question is whether the "what happens next" logic lives in one brain or is scattered across many.

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

### The pivot = the "point of no return"

Everything before the payment is undoable: you can release reserved inventory and cancel the order. The **moment the payment succeeds is the pivot** — the point of no return. After that, backing out isn't "undo"; the saga is committed to completing — confirm the order, send the receipt. Those after-steps *must* eventually succeed, so you keep **retrying** them rather than cancelling.

So each step is one of three kinds:

- **Compensatable** (before the pivot): if a later step fails, **undo** this one. *Reserve inventory → release it.*
- **Pivot**: the single commit point. Once it succeeds, the saga is going to complete no matter what. *Charge payment.*
- **Retriable** (after the pivot): can't be undone, so on failure you just **keep retrying until it works.** *Confirm order, send receipt.*

```
Timeline:   [compensatable] [compensatable] → PIVOT → [retriable] [retriable]
Fails BEFORE pivot?  → compensate backwards (undo)
Fails AFTER pivot?   → retry forwards (never undo)
```

#### Q: Why deliberately order steps around the pivot?

Because it removes impossible situations. If a non-undoable step sat *before* the pivot and a later step failed, you'd be asked to undo something that can't be undone — a dead end. By putting all **compensatable** steps first and all **retriable** steps last, every failure has a clean answer: *before* the pivot you can always roll back, and *after* it you can always roll forward. No stuck states.

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