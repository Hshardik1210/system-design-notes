# BookMyShow — System Design

> **Core challenge:** **Never double-book a seat.** If two users try to book the same seat, exactly **one** must succeed. Almost every design decision below exists to guarantee this.

---

## Contents

- [1. Problem Statement](#1-problem-statement)
- [2. Requirements](#2-requirements)
- [3. Capacity Estimation (back-of-envelope)](#3-capacity-estimation-back-of-envelope)
- [4. API Design](#4-api-design)
- [5. High-Level Architecture](#5-high-level-architecture)
- [6. Data Model & Indexes](#6-data-model--indexes)
- [7. Seat Booking Flow](#7-seat-booking-flow)
- [8. Lock Expiry (don't leak seats)](#8-lock-expiry-dont-leak-seats)
- [9. Concurrency — The Core Interview Point](#9-concurrency--the-core-interview-point)
- [10. Race Condition Deep Dive — 10 Users, 1 Seat](#10-race-condition-deep-dive--10-users-1-seat)
- [11. Pessimistic vs Optimistic Locking](#11-pessimistic-vs-optimistic-locking)
- [12. Redis + DB Hybrid Locking (high scale)](#12-redis--db-hybrid-locking-high-scale)
- [13. Multi-Seat Booking & Deadlocks](#13-multi-seat-booking--deadlocks)
- [14. Idempotency (safe "Pay" clicks)](#14-idempotency-safe-pay-clicks)
- [15. Payment Gateway Integration](#15-payment-gateway-integration)
- [16. Outbox + Kafka — When to Publish Events](#16-outbox--kafka--when-to-publish-events)
- [17. Saga — Payment Flow](#17-saga--payment-flow)
- [18. High-Demand Shows — Virtual Waiting Room](#18-high-demand-shows--virtual-waiting-room)
- [19. Search & Discovery](#19-search--discovery)
- [20. Background Jobs — How to Run Them](#20-background-jobs--how-to-run-them)
- [21. Scaling, Sharding & Caching](#21-scaling-sharding--caching)
- [22. Consistency & CAP Tradeoffs](#22-consistency--cap-tradeoffs)
- [23. Abuse Prevention & Rate Limiting](#23-abuse-prevention--rate-limiting)
- [24. Reliability & Observability](#24-reliability--observability)
- [25. How to Drive the Interview (framework)](#25-how-to-drive-the-interview-framework)
- [26. Interview Cheat Sheet](#26-interview-cheat-sheet)
- [27. Final Takeaways](#27-final-takeaways)

---

## 1. Problem Statement

Users should be able to:

- Browse movies / events across cities and theatres
- See available seats for a show
- Select and lock seats
- Pay and book tickets
- Receive confirmation (ticket + QR)

> ⚠️ **The MOST IMPORTANT requirement:** prevent **double booking** of seats. If two users try to book the same seat, only one should succeed.

---

## 2. Requirements

> 💡 **Always start the interview here.** Clarify scope out loud before designing — it shows seniority and frames every later decision.

### Functional

- Browse / search movies & events by city, language, genre
- View shows (theatre + timing) for a movie
- View the **seat map** with live availability
- **Select + lock** seats, then pay
- **Confirm booking** and issue a ticket
- View booking history; **cancel / refund** (often called out as optional)

### Non-Functional (NFRs)

| NFR | Target / Note |
| --- | --- |
| **Consistency** | **Strong** for seat booking (no double booking). Eventual is fine for browse/search. |
| **Availability** | High for browse; booking can favor consistency over availability (CP for the seat write). |
| **Latency** | Browse < 200ms; seat lock should feel instant. |
| **Scale** | Read-heavy (browsing) ≫ write-heavy (booking). Spiky traffic on popular releases. |
| **Durability** | A confirmed booking must never be lost. |

### Out of scope (state assumptions)

- Recommendations, reviews/ratings, dynamic pricing, food & beverage add-ons (mention, then defer).

---

## 3. Capacity Estimation (back-of-envelope)

> Numbers are illustrative — the point is to **show the method**, not be exact.

```
Assume:
  Daily active users        ~ 10M
  Bookings per day          ~ 1M
  Avg seats per booking     ~ 2  → ~2M seats/day

Write QPS (bookings):
  1M / 86,400s             ~ 12 bookings/sec average
  Peak (10–20x on big release) ~ 200–250 bookings/sec

Read QPS (browse/seat-map):
  ~100x writes             ~ 1,000–25,000 reads/sec at peak

Storage (rough):
  Booking row ~ 300 bytes
  1M/day * 365 * 300B      ~ 110 GB/year (bookings)
  Seats are bounded by shows*capacity, regenerated per show
```

**Takeaways that drive design:** read-heavy → **cache + read replicas**; write contention is on **hot seats**, not total volume → **per-seat atomic updates** + optional **Redis**.

---

## 4. API Design

> Keep it RESTful; highlight the **idempotency key** on the write path.

```
GET  /v1/movies?city=BLR&date=2026-06-21          → list movies
GET  /v1/movies/{movieId}/shows?city=BLR          → shows for a movie
GET  /v1/shows/{showId}/seats                      → seat map + status

POST /v1/shows/{showId}/lock                       → lock seats (hold)
     body: { seatIds: [A1, A2], userId }
     header: Idempotency-Key: <uuid>
     → 200 { lockId, expiresAt }  | 409 seat unavailable

POST /v1/bookings                                  → create booking + start payment
     body: { lockId, paymentMethod }
     header: Idempotency-Key: <uuid>
     → 201 { bookingId, status: PENDING }

POST /v1/payments/webhook                          → gateway callback (success/failure)
GET  /v1/bookings/{bookingId}                       → poll booking status
POST /v1/bookings/{bookingId}/cancel                → cancel + refund (optional)
```

> The **lock** and **booking** endpoints are the ones that must be **idempotent** — retries must not create duplicate holds or double charges.

---

## 5. High-Level Architecture

```
                 ┌──────────────┐
Client (App/Web) │   CDN        │  (posters, static seat-map layout)
       │         └──────────────┘
       ▼
  API Gateway  (auth, rate limiting, routing)
       │
   ┌───┼───────────────┬───────────────┐
   ▼   ▼               ▼               ▼
 Search   Booking Service     Payment Service
 Service     │  (seat lock /     │ (gateway integration,
 (ES)        │   confirm)        │  webhooks, reconciliation)
   │         │                   │
   │     ┌───┴────┐          ┌───┘
   ▼     ▼        ▼          ▼
 Elastic  SQL DB   Redis     Kafka ──► Notification / Analytics / Invoice
 search  (source   (cache +  (outbox events)
         of truth)  locks)
```

- **CDN** — posters, static seat-map layout
- **API Gateway** — auth, **rate limiting**, routing
- **Booking Service** — seat lock / confirm (the heart of the system)
- **Payment Service** — gateway integration, webhooks, reconciliation
- **Cache (Redis)** — fast seat-availability reads + short-lived locks
- **Search Service (Elasticsearch)** — movie/event discovery
- **Kafka** — event-driven downstream (notifications, analytics, invoices)

---

## 6. Data Model & Indexes

> Show more than 3 tables — model the real hierarchy (city → cinema → screen → show → seat).

### Core entities

| Entity | Key fields |
| --- | --- |
| **Movie** | `movie_id`, `title`, `language`, `genre`, `duration` |
| **City** | `city_id`, `name` |
| **Cinema / Theatre** | `cinema_id`, `city_id`, `name`, `location` |
| **Screen** | `screen_id`, `cinema_id`, `layout` (rows × cols) |
| **Show** | `show_id`, `movie_id`, `screen_id`, `start_time`, `price` |
| **Seat** | `seat_id`, `show_id`, `seat_number`, `status`, `locked_by`, `lock_expiry`, `version` |
| **Booking** | `booking_id`, `user_id`, `show_id`, `seat_ids`, `status`, `idempotency_key` |
| **Payment** | `payment_id`, `booking_id`, `amount`, `status`, `gateway_ref` |

### 💺 Seat — `status`: `AVAILABLE` / `LOCKED` / `BOOKED`

### 🎟 Booking — `status`: `PENDING` / `CONFIRMED` / `FAILED`

> 💡 **Seat status ≠ Booking status.** Seat lifecycle is about the **resource**; booking lifecycle is about the **user's request**. (Same separation idea as idempotency status vs order state.)

### Indexes that matter

- `seats (show_id, status)` — fast seat-map render & availability counts
- `seats (lock_expiry)` where `status = LOCKED` — efficient expiry sweep
- `bookings (user_id)` — booking history
- `bookings (idempotency_key)` **UNIQUE** — dedup retries
- `shows (movie_id, city_id, start_time)` — listing shows

> **Per-show seat partitioning:** seats are naturally scoped to a `show_id`, which makes **sharding by `show_id`** clean (see §22).

---

## 7. Seat Booking Flow

> 🔑 **Golden rule: lock first, confirm later.** We never book seats directly — we **lock** them, take payment, then **confirm**.

### 🟢 Step 1 — User selects seats

```
User picks: A1, A2
```

### 🟡 Step 2 — Lock seats (the critical step)

```sql
UPDATE seats
SET status = 'LOCKED',
    locked_by = 123,
    lock_expiry = now() + INTERVAL '5 minutes'
WHERE seat_id IN ('A1', 'A2')
  AND status = 'AVAILABLE'
```

**Why lock?**
- Prevents others from selecting the same seat
- Gives the user time to pay

> If `rows_affected < requested seats` → some seat was already taken → **fail the whole request** (and release any seats you did lock).

### 🔵 Step 3 — Payment

User pays via the Payment Service.

### 🟢 Step 4 — Confirm booking (on payment success)

```sql
UPDATE seats
SET status = 'BOOKED'
WHERE locked_by = 123;

UPDATE booking
SET status = 'CONFIRMED'
WHERE booking_id = ...;
```

### 🔴 Step 5 — Release (on payment failure)

```sql
UPDATE seats
SET status = 'AVAILABLE',
    locked_by = NULL
WHERE locked_by = 123;
```

---

## 8. Lock Expiry (don't leak seats)

> ❓ What if the user locks seats but never pays? Seats must **auto-release**, otherwise they're stuck forever.

**Background job:**

```sql
UPDATE seats
SET status = 'AVAILABLE',
    locked_by = NULL
WHERE status = 'LOCKED'
  AND lock_expiry < now();
```

Runs periodically (e.g. every 1 minute) to free up abandoned locks.

> **Race to watch:** payment succeeds *just as* the lock expires. Guard the confirm step with `WHERE locked_by = :user AND status = 'LOCKED'` and reconcile against payment status (see §15).

---

## 9. Concurrency — The Core Interview Point

> 📝 **Shorthand note:** in the examples below `seat_id = 'A1'` is shorthand for "this exact seat in this exact show." In reality, identify a seat by its **unique `seat_id` (PK)** or by **`show_id` + `seat_number`** — never by `seat_number` alone, since `A1` exists in every show (see §12).

### 💥 Scenario

User A and User B both try to book seat `A1` at the same time.

### ❌ Without proper control

If you `SELECT` then `UPDATE` as separate steps, **both can succeed** → double booking.

### ✅ Solution: atomic conditional update

```sql
UPDATE seats
SET status = 'LOCKED'
WHERE seat_id = 'A1'
  AND status = 'AVAILABLE';
```

👉 The DB processes this **atomically at the row level** — only **one** request matches `status = 'AVAILABLE'` and succeeds.

---

## 10. Race Condition Deep Dive — 10 Users, 1 Seat

> **Setup:** Seat `A1`, users `U1 → U10`, all click "Book" at almost the same instant.

### ❌ Case 1 — Bad design (check-then-act)

```sql
-- All 10 read first:
SELECT * FROM seats WHERE seat_id = 'A1';   -- everyone sees AVAILABLE

-- Then all 10 write:
UPDATE seats SET status = 'BOOKED' WHERE seat_id = 'A1';
```

**Result:** all 10 "succeed" → **10 bookings for 1 seat** 😬

**Why?** The `SELECT` (check) and `UPDATE` (act) are **separate operations** → classic **race condition**.

### ✅ Case 2 — Correct design (atomic conditional update)

> **Key idea: don't check first — update directly with a condition.**

```sql
UPDATE seats
SET status = 'LOCKED', locked_by = :user_id
WHERE seat_id = 'A1'
  AND status = 'AVAILABLE';
```

**Step-by-step:**

```
T0: all 10 requests arrive
T1: DB serializes them at the ROW level

U1  → WHERE status = 'AVAILABLE' matches  → rows_affected = 1 ✅
U2  → row now LOCKED, condition fails     → rows_affected = 0 ❌
U3..U10 → same → rows_affected = 0 ❌
```

**Application logic:**

```
if rows_affected == 1:
    proceed to payment
else:
    show "Seat already taken"
```

> The DB's **row-level locking + atomic execution** guarantees a single winner — even at the exact same microsecond. **No external locks needed.**

---

## 11. Pessimistic vs Optimistic Locking

> **Nature of the problem:** very high concurrency, very short operation (lock → pay → confirm), conflicts localized to **hot seats**. Seat booking is a **race problem, not a queue problem.**

### ⚔️ Pessimistic Locking

```sql
SELECT * FROM seats WHERE seat_id = 'A1' FOR UPDATE;
```

First user locks the row; **others wait**.

| Problem | Impact |
| --- | --- |
| Waiting = bad UX | spinner → timeout 😬 |
| Doesn't scale | 10,000 users all blocked on one row 💀 |
| Deadlocks | possible with multi-seat booking |

✅ Good for: banking, low-concurrency systems.

### ⚡ Optimistic Locking (recommended)

```sql
UPDATE seats
SET status = 'LOCKED'
WHERE seat_id = 'A1'
  AND status = 'AVAILABLE';
```

All users try; only **one** succeeds; the rest **fail instantly**.

| Benefit | Why |
| --- | --- |
| No waiting | immediate response |
| Highly scalable | DB handles contention efficiently |
| No deadlocks (single row) | nothing to wait on |
| Perfect for first-come-first-serve | it's a race |

❌ Downside: some users fail → need retry / UX handling (acceptable for booking).

> **Version-column variant:** add a `version` to the seat row; `UPDATE ... SET version = version + 1 WHERE seat_id = ? AND version = ?`. Same idea, generalizes to any field update.

### 💡 Key insight — our seat lock IS optimistic locking

Our `WHERE status = 'AVAILABLE'` update **is optimistic locking without a version column**: we don't lock beforehand, we check a condition **at update time**.

| Approach | Type |
| --- | --- |
| `WHERE version = ?` | Optimistic |
| `WHERE status = 'AVAILABLE'` | Optimistic (condition-based) |
| `SELECT ... FOR UPDATE` | Pessimistic |

- **Pessimistic** = "lock first, then act."
- **Optimistic** = "act first, verify (via condition/version) at write time."

### Philosophy in one line

| Type | Philosophy |
| --- | --- |
| Pessimistic | "Conflicts will happen → block others" |
| Optimistic | "Conflicts are rare → detect and retry" |

### Comparison

| Feature | Pessimistic | Optimistic |
| --- | --- | --- |
| Waits | Yes ❌ | No ✅ |
| Scalability | Poor ❌ | High ✅ |
| Deadlocks | Possible ❌ | Rare ✅ |
| UX | Slow ❌ | Fast ✅ |
| Best for seat booking | ❌ | ✅ |

> **Mental model:** Pessimistic = "wait your turn." Optimistic = "race, and only one wins."

---

## 12. Redis + DB Hybrid Locking (high scale)

> Production systems often pair **optimistic DB updates** with a **short-lived Redis lock** to cut contention. **Redis = quick gatekeeper; DB = final judge.**

### 🔑 The lock key must be scoped to a SHOW (not just the seat number)

> ⚠️ `seat:A1` is **wrong** — seat `A1` exists in every theatre, every screen, every showtime. Locking `seat:A1` would block `A1` across the *entire platform*.

A seat is only unique **within a show** (a `show_id` already pins movie + theatre + screen + start time). So the key must include the show:

```
seat_lock:{show_id}:{seat_number}      → e.g. seat_lock:show42:A1
```

Or use the globally-unique `seat_id` (PK) directly, which already encodes the show:

```
seat_lock:{seat_id}                    → e.g. seat_lock:99817
```

> **Same rule applies to the DB:** the conditional update must filter `WHERE show_id = ? AND seat_number = ?` (or `WHERE seat_id = ?`), never `seat_number` alone.

### ❗ The most common mistake: check-then-act

```
IF key exists → reject
ELSE → set key          ❌ UNSAFE (race condition)
```

### ✅ Correct: atomic acquire

```
SET seat_lock:show42:A1 user1 NX EX 300
```

`NX` = set only if not exists, `EX 300` = 5-min expiry. **Atomic** — only one user wins.

### Two-user simulation

```
U1: SET seat_lock:show42:A1 user1 NX EX 300  → success ✅
U2: SET seat_lock:show42:A1 user2 NX EX 300  → fails ❌ (key exists)
   → U2 returns: "Seat temporarily unavailable"
```

> ⚠️ Message nuance: it's **"temporarily unavailable"**, NOT "booked" — U1 may still fail payment.

### Can I `GET` before locking? (a common question)

A `GET` pre-check is **fine as a fast UX optimization**, but **never as the decision**:

```
# OK — optional fast rejection (optimization only)
owner = redis.get("seat_lock:show42:A1")
if owner and owner != current_user:
    return "Seat temporarily unavailable"

# MANDATORY — the actual decision must be atomic
success = redis.set("seat_lock:show42:A1", current_user, nx=True, ex=300)
if not success:
    return "Seat locked by another user"
```

**Why the `GET` alone is unsafe:** two users can both `GET → null`, both pass the check, both proceed → double booking. The **atomic `SET NX`** is what guarantees a single winner.

| Step | Purpose |
| --- | --- |
| `GET` check | "looks busy" — fast rejection (optional) |
| `SET NX` | "actually reserve it" — correctness (mandatory) |
| DB update | "final confirmation" — source of truth |

### Why DB is still required

Redis is **not** the source of truth. After acquiring the Redis lock, still run the DB conditional update.

### Full pseudo-flow

```
# Step 1: try Redis lock (key scoped to the show)
success = redis.set("seat_lock:show42:A1", user_id, nx=True, ex=300)
if not success:
    return "Seat temporarily unavailable"

# Step 2: try DB lock (scoped by show_id + seat_number, or by seat_id PK)
rows = UPDATE seats SET status='LOCKED'
       WHERE show_id=42 AND seat_number='A1' AND status='AVAILABLE'
if rows == 0:
    redis.delete("seat_lock:show42:A1")
    return "Seat already booked"

# Step 3: proceed to payment

# Step 4: on success
UPDATE seats SET status='BOOKED'
redis.delete("seat_lock:show42:A1")

# Step 5: on failure
UPDATE seats SET status='AVAILABLE'
redis.delete("seat_lock:show42:A1")
```

### Redis vs DB summary

| Your instinct | Correct version |
| --- | --- |
| Check Redis then act | ❌ Wrong |
| Direct `SET NX` | ✅ Correct |
| Redis alone is enough | ❌ No |
| DB must verify | ✅ Yes |

> **Edge case:** if the Redis key expires early while U1 is still paying, U2 may grab the Redis lock — but the **DB conditional update still prevents double booking**. The DB is the safety net.
>
> **Releasing safely:** delete the Redis key only if you still own it (check value, or use a Lua compare-and-delete) so you don't delete someone else's lock. This is the **Redlock** debate territory — for correctness, the DB is the real guard.

---

## 13. Multi-Seat Booking & Deadlocks

> Single-seat locking can't deadlock. **Multiple seats can.**

```
User A → locks A1, then waits for A2
User B → locks A2, then waits for A1
→ Deadlock 💀
```

### ✅ Fix: always lock seats in a consistent (sorted) order

```
Always acquire: A1 → A2 → A3 ...
```

If everyone locks in the same order, the circular wait is impossible.

> **Better:** lock all seats in **one atomic statement** (`WHERE seat_id IN (...) AND status='AVAILABLE'`) and check `rows_affected == requested count`. One statement = no ordering problem, all-or-nothing.

---

## 14. Idempotency (safe "Pay" clicks)

> When the user clicks **Pay**, use an **idempotency key**.

Prevents:

- Double payment
- Duplicate booking

Implementation: client generates a UUID `Idempotency-Key`; server stores it with the result (unique constraint). A retry with the same key returns the **same** response instead of re-executing.

(See the dedicated **Idempotency** note — same key + same payload returns the same result; retries are safe.)

---

## 15. Payment Gateway Integration

> Payment is an **external, unreliable, async** dependency — interviewers probe how you keep money + bookings consistent.

### Sync vs async

- **Sync API call** — simple, but the gateway can time out *after* charging the card → ambiguity.
- **Webhook callback** (preferred) — gateway calls you back with the final status; more reliable for the source of truth.

### Handling ambiguity (timeout after charge)

```
1. Booking = PENDING, payment initiated
2. Gateway times out → status UNKNOWN
3. Do NOT assume failure
4. Reconcile: query gateway by your reference, OR wait for webhook
5. Settle booking to CONFIRMED / FAILED based on truth
```

### Must-haves

- **Idempotency** toward the gateway (so a retry doesn't double-charge).
- **Reconciliation job** — periodically compare your `PENDING` payments against the gateway's records and settle stragglers.
- **Refund flow** — on cancellation, trigger refund + release seats (a saga compensation).

> **Rule:** never confirm a booking until payment is **confirmed truth**, and never leave a charged payment without a booking. Reconciliation closes the gap.

---

## 16. Outbox + Kafka — When to Publish Events

> 🔑 **Only publish events that represent a COMPLETED state change.** Publishing intermediate steps to general consumers causes them to act too early.

### Two types of events

| Type | Examples | Audience |
| --- | --- | --- |
| **State / business events** ✅ | `BOOKING_CONFIRMED`, `BOOKING_FAILED` | external consumers (via outbox → Kafka) |
| **Workflow events** ⚠️ | `SEAT_LOCKED`, `PAYMENT_STARTED` | internal saga only, NOT general consumers |

### ❌ Wrong flow

```
1. Seat locked
2. Write outbox → publish to Kafka
3. Consumer acts immediately   ❌ (payment not done yet!)
```

### ✅ Correct flow

```
1. Lock seat       → seat = LOCKED, booking = PENDING   (no external event)
2. Payment happens (sync call OR saga)
3. Final outcome:
   - success → seat = BOOKED,     booking = CONFIRMED → outbox: BOOKING_CONFIRMED
   - failure → seat = AVAILABLE,  booking = FAILED    → outbox: BOOKING_FAILED
4. Outbox → Kafka → Consumers (notifications, analytics, invoice)
```

> **Outbox = "final truth." Saga = "the process to reach that truth."** Only the final event reaches general consumers.

### Downstream consumers of `BOOKING_CONFIRMED`

- **Notification service** — send SMS / email / push (ticket + QR)
- **Analytics**
- **Invoice service**

(See the **Outbox & Saga** note for the at-least-once + consumer-idempotency details.)

---

## 17. Saga — Payment Flow

> For the multi-step booking workflow, use a **Saga** with compensating actions.

```
1. Lock seats
2. Process payment
3. Confirm booking

If payment fails → release seats (compensation)
```

### If payment is async (event-driven saga)

```
Booking Service → emits PaymentRequested (Kafka)
Payment Service → processes → emits PaymentSuccess / PaymentFailed
Booking Service → consumes result → updates DB → writes FINAL event to outbox
```

| Purpose | Mechanism |
| --- | --- |
| Internal workflow (saga) | Kafka (or direct calls) |
| External communication | Outbox → Kafka |

### Seat release on payment failure (the compensation)

> Releasing a locked seat correctly is where real bugs hide. Three layers are involved: **Redis lock**, **DB seat status**, **booking/payment state**.

**Use two mechanisms together:**

| Method | Purpose |
| --- | --- |
| **Immediate release** on failure | fast recovery |
| **Expiry cleanup** job | safety net if the server crashes mid-release |

**Immediate release — note the condition:**

```sql
UPDATE seats
SET status = 'AVAILABLE', locked_by = NULL
WHERE show_id = 42 AND seat_number = 'A1'
  AND locked_by = :user1;   -- ⚠️ critical
-- then: DEL seat_lock:show42:A1 (only if we still own it)
```

> **Why `WHERE locked_by = :user1` matters:** if U1's lock already expired and U2 re-locked the seat, U1's late failure must **not** overwrite U2's lock. The condition also makes a **double release** (two retries) safe — the second one simply affects 0 rows.

**Crash before release?** → the **lock-expiry background job** (§8) eventually frees the seat. Immediate release is the happy path; expiry is the guarantee.

```
Lock → Try → Either CONFIRM or ROLLBACK (release seat + DEL Redis key)
```

---

## 18. High-Demand Shows — Virtual Waiting Room

> ❓ "A blockbuster / concert goes on sale — 1M users hit one show at 10:00 AM. How do you survive?" This is a **top follow-up** for BookMyShow.

### Problems at the spike

- Thundering herd on a single show's seats
- DB / Redis hot-partition meltdown
- Bots / scalpers grabbing inventory

### Techniques

| Technique | What it does |
| --- | --- |
| **Virtual waiting room / queue** | Admit users in controlled batches (token grants entry); everyone else waits with a position number |
| **Rate limiting at gateway** | Shed excess load before it hits booking |
| **Seat-map served from cache/CDN** | Reads don't touch the DB |
| **Per-show sharding** | Isolate the hot show so it can't take down others |
| **Queue-based admission** | Push booking requests into Kafka/SQS; workers drain at a safe rate |
| **Backpressure + retries** | Fail fast with "try again", don't block |

> Mental model: convert a **stampede** into an **orderly line**. The waiting room protects the booking core; correctness still relies on the atomic seat update.

---

## 19. Search & Discovery

> Browsing is the **read-heavy** majority of traffic — keep it off the booking DB.

- **Elasticsearch** for movie/event search (by city, language, genre, free text).
- **CDN** for posters and static seat-map layouts.
- **Redis cache** for hot listings (now-showing in a city) and seat-availability snapshots.
- **Read replicas** for any relational reads on the browse path.

> Search/browse can be **eventually consistent** — a few seconds of staleness in "now showing" is fine; only the **seat write** needs strong consistency.

---

## 20. Background Jobs — How to Run Them

> Work that runs **outside** the API request flow: expire locks, send emails, retry tasks, process payments, reconcile. **No single "best" — pick by frequency, scale, and reliability needs.**

| Approach | Complexity | Scale | Real-time | Use case |
| --- | --- | --- | --- | --- |
| **Scheduler** (cron / `@Scheduled`) | Low | Low | ❌ | Cleanup jobs (seat expiry, reconciliation) |
| **Queue + Worker** (Kafka / RabbitMQ / SQS) | Medium | High | ✅ | Async processing, payments, emails |
| **Polling worker** (Outbox) | Medium | Medium | ⚠️ | Outbox, medium scale |
| **Distributed job system** (Celery / Sidekiq / BullMQ / Quartz) | Medium | Med-High | ✅ | Reliable jobs + retries + monitoring |
| **CDC** (Debezium) | High | Very High | ✅ | High-scale event-driven |

### Best combination for *this* system

- **Seat expiry cleanup** → **Scheduler** (every ~1 min)
- **Payment / booking flow** → **Queue + Worker** (Kafka / SQS)
- **Events (notifications, analytics)** → **Outbox + Worker** or Kafka
- **Payment reconciliation** → **Scheduler** (every few min)

> **Mental model:** Simple → Scheduler · Scalable → Queue · Advanced → CDC.

---

## 21. Scaling, Sharding & Caching

### Read-heavy path (seat availability / browsing)

- **Redis cache** + precomputed seat maps
- **Read replicas** for browsing
- **CDN** for static assets

### Write-heavy path (booking)

- **DB is the source of truth** — avoid caching writes
- **Shard / partition by `show_id`** — hot shows isolated, scales horizontally
- **Redis locks** to reduce DB contention on hot seats

### Sharding strategy

| Choice | Pro | Con |
| --- | --- | --- |
| Shard by `show_id` | natural isolation, all seats of a show co-located | a single mega-popular show is still one hot shard |
| Shard by `city`/region | locality, regional scaling | uneven load across cities |

> A single blockbuster show can be a hot shard even with `show_id` sharding → combine with the **virtual waiting room** (§18) and caching.

---

## 22. Consistency & CAP Tradeoffs

> Interviewers love: "Where do you choose consistency vs availability?"

| Path | Choice | Why |
| --- | --- | --- |
| **Seat booking (write)** | **CP** (strong consistency) | double booking is unacceptable — correctness > availability |
| **Browse / search** | **AP** (availability + eventual) | stale "now showing" for a few seconds is fine |
| **Notifications / analytics** | **Eventual** | downstream, async, retryable |

- Seat writes go to a **single source-of-truth** row (RDBMS) — the atomic conditional update gives you serializable behavior **per seat** without a global lock.
- The system is **eventually consistent across services** (outbox/saga), but **strongly consistent at the seat row**.

> One-liner: **"Strong consistency where money/inventory is involved, eventual consistency everywhere else."**

---

## 23. Abuse Prevention & Rate Limiting

> Real ticketing systems fight **bots and scalpers** — worth a mention to stand out.

- **Rate limiting** per user/IP at the API gateway (token bucket).
- **CAPTCHA** on the high-demand booking path.
- **Per-user booking caps** (e.g. max N seats per show).
- **Bot detection** (device fingerprint, behavioral signals).
- **Lock hold limits** — short expiry so squatting doesn't block real buyers.

---

## 24. Reliability & Observability

- **No single point of failure** — replicate DB (primary + replicas + failover), multi-AZ Redis, multiple service instances behind LB.
- **Idempotent retries** everywhere on the write path.
- **Dead-letter queues** for failed events / compensations.
- **Monitoring/alerts** — booking success rate, lock contention, payment failure rate, reconciliation backlog, p99 latency.
- **Graceful degradation** — if search is down, still allow direct booking; if Redis is down, fall back to DB-only locking.

---

## 25. How to Drive the Interview (framework)

> Use this order so you never freeze. Spend ~5 min on 1–4, then go deep on 5–6.

1. **Clarify requirements** (functional + NFRs) — §2
2. **Estimate scale** (read vs write, peak) — §3
3. **Define APIs** — §4
4. **High-level architecture + data model** — §5, §6
5. **Deep dive: the hard part** → **seat booking concurrency** — §7–§13
6. **Deep dive: payments, events, failures** — §14–§17
7. **Address scale + edge cases** — §18–§24
8. **Summarize tradeoffs** — §22, §26

> 🎤 **Lead with the core challenge:** state up front that "the crux is preventing double booking under high concurrency," then spend most of your time there. That's what they're testing.

---

## 26. Interview Cheat Sheet

> **"Design BookMyShow."**
>
> "Seat selection uses a **locking mechanism** to prevent double booking. Seats are first **locked with an expiry**, then **confirmed after successful payment**. I'd use **atomic conditional DB updates** for concurrency, **idempotency keys** to prevent duplicate bookings, and the **outbox pattern** to reliably publish booking events. For scale: **caching for reads**, **sharding by show for writes**, and a **virtual waiting room** for blockbuster spikes."

> **"How do you handle multiple users booking the same seat?"**
>
> "An **atomic update** with a condition on availability (`WHERE status = 'AVAILABLE'`). Only one request succeeds; the rest fail based on **rows affected**. No explicit/external locks needed — the DB guarantees it via row-level atomicity."

> **"Pessimistic or optimistic locking?"**
>
> "**Optimistic** (conditional updates) — it avoids blocking and scales under high contention. Pessimistic causes delays and doesn't scale. In practice, combine with **Redis** to reduce contention."

> **"How do you handle a payment timeout / not double-charge?"**
>
> "**Idempotency keys** toward the gateway, treat timeouts as **UNKNOWN** (not failure), and run a **reconciliation job** + rely on **webhooks** to settle the true status before confirming the booking."

> **"1M users hit one show at sale time?"**
>
> "**Virtual waiting room** to admit users in batches, **rate limiting** + CDN/cache for reads, **per-show sharding**, and queue-based admission so the booking core stays healthy."

> **"Consistency vs availability?"**
>
> "**Strong consistency** on the seat write (CP — no double booking), **eventual consistency** for browse/search and downstream events."

> **"When should events be published via outbox?"**
>
> "Only **after a final state change is committed**. Intermediate steps like seat locking aren't published to external consumers unless they're part of a saga workflow."

> **"Best way to run background jobs?"**
>
> "Scheduler for periodic cleanup/reconciliation, queue-based workers (Kafka/SQS) for scalable reliable processing, and CDC (Debezium) for high-scale event-driven systems."

### Tricky scenarios (rapid-fire)

| Scenario | What happens / what to do |
| --- | --- |
| **Redis lock OK, but DB update returns 0 rows** | Release the Redis key, tell user "seat unavailable", optionally suggest another seat. DB is the truth. |
| **Redis lock expires mid-payment (6-min pay, 5-min TTL)** | Another user may grab the Redis lock, but the **DB conditional update still prevents double booking**. Use longer TTL / lock refresh / lean on DB. |
| **Multi-seat deadlock (A locks A1→A2, B locks A2→A1)** | Lock in **sorted order** or in **one atomic `IN (...)` statement**. |
| **User clicks Pay → timeout → retries** | **Idempotency key** → return the same booking result, no duplicate. |
| **Booking confirmed in DB but event not sent (worker crash)** | **Outbox pattern** — event persisted in the same txn, retried until delivered. |
| **Payment success but response lost** | Idempotency key + reconciliation → don't re-charge, settle to CONFIRMED. |

> **Ultimate layer model:** Redis = reduce contention · DB = guarantee correctness · Idempotency = handle retries · Saga = handle failures · Outbox = ensure event delivery.

---

## 27. Final Takeaways

- **Seats must never be double-booked** — every decision serves this
- **Lock first → then confirm** (never book directly)
- **Atomic conditional UPDATE**, never `SELECT` → then `UPDATE`
- **Optimistic locking wins** — booking is a race, not a queue
- **Redis = quick gatekeeper, DB = final judge** (DB is the source of truth)
- **Expire locks** so abandoned seats don't leak
- **Lock multiple seats in one atomic statement** (or sorted order) to avoid deadlocks
- **Idempotency keys** make "Pay" retries safe; **reconciliation** closes payment gaps
- **Publish only final state events** via outbox (not intermediate workflow steps)
- **Saga** releases seats / refunds when payment fails
- **Virtual waiting room + sharding + caching** handle blockbuster spikes
- **Strong consistency on the seat write, eventual everywhere else**

> Concurrency is solved by **pushing logic into the database with atomic operations** — not by managing it in application code.
