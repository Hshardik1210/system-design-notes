# Idempotency

> **Definition:** Idempotency means doing the same operation multiple times has the same effect as doing it once.

---

## Contents

- [1. Why Idempotency Matters](#1-why-idempotency-matters)
- [2. Where It's Used](#2-where-its-used)
- [2.5. HTTP Method Idempotency (REST semantics)](#25-http-method-idempotency-rest-semantics)
- [2.6. Designing Naturally Idempotent Operations](#26-designing-naturally-idempotent-operations)
- [3. Core Idea: The Idempotency Key](#3-core-idea-the-idempotency-key)
- [4. High-Level Request Flow](#4-high-level-request-flow)
- [5. Storage Design](#5-storage-design)
- [6. Request Handling Logic](#6-request-handling-logic)
- [7. How Order Status is Maintained (Idempotency ≠ Order Lifecycle)](#7-how-order-status-is-maintained-idempotency-≠-order-lifecycle)
- [8. Payload Consistency (Request Hash)](#8-payload-consistency-request-hash)
- [9. Concurrency & Race Conditions](#9-concurrency--race-conditions)
- [10. Crash & Failure Scenarios](#10-crash--failure-scenarios)
- [11. TTL / Cleanup](#11-ttl--cleanup)
- [12. Client (Frontend) Responsibilities](#12-client-frontend-responsibilities)
- [13. Security Note](#13-security-note)
- [14. Redis vs DB: Tradeoffs](#14-redis-vs-db-tradeoffs)
- [15. Tech Choices](#15-tech-choices)
- [16. Common Pitfalls](#16-common-pitfalls)
- [17. Production-Grade Pattern](#17-production-grade-pattern)
- [18. Interview Cheat Sheet](#18-interview-cheat-sheet)

---

## 1. Why Idempotency Matters

### The real-world problem: **retries**

In distributed systems, failures are common:

- Network timeout
- Server crash
- Client didn't receive the response
- Load balancer dropped the connection

After a failure, the client doesn't know:

> "Did my order get created or not?"

So it **retries** — and that's where things break.

### Without idempotency

```
Client → Create Order (timeout)
Client retries → Create Order again
```

**Result:** Order #1 created + Order #2 created → **duplicate / double charge**

### With idempotency

```
Client → Create Order (key=abc123) → timeout
Client retries → Create Order (key=abc123)
Server → "I've already processed this request"
```

**Result:** Only **1 order created**, same response returned.

### What it prevents

| Problem | Impact |
| --- | --- |
| Double payments | 💸 Real money loss |
| Duplicate shipments | 📦 Operational cost |
| Data inconsistencies | 📊 Bad reports |
| Bad UX | 😡 Lost trust |
| Reconciliation logic | 🔁 Engineering overhead |

---

## 2. Where It's Used

- **Payments** — Stripe, PayPal, Google Pay
- **Order creation** — e-commerce
- **Booking systems** — tickets, hotels
- **Any API** with retries / unreliable networks

---

## 2.5. HTTP Method Idempotency (REST semantics)

> 🎯 **A top interview question:** "Which HTTP methods are idempotent and why?" Idempotency is a built-in part of the HTTP spec — not just an app-level concern.

| Method | Idempotent? | Safe? | Why |
| --- | --- | --- | --- |
| `GET` | ✅ Yes | ✅ Yes | Read-only; same result, no side effects |
| `HEAD` / `OPTIONS` | ✅ Yes | ✅ Yes | Metadata only |
| `PUT` | ✅ Yes | ❌ No | **Replaces** the resource — sending it N times = same final state |
| `DELETE` | ✅ Yes | ❌ No | Deleting N times leaves the resource deleted (same end state) |
| `POST` | ❌ No | ❌ No | **Creates** a new resource each call → duplicates |
| `PATCH` | ⚠️ Usually no | ❌ No | Depends — a relative patch (`+1`) isn't; an absolute one can be |

- **Safe** = no server state change at all (a subset of idempotent).
- **Idempotent** = repeating it has the **same effect** as doing it once.

> 💡 **This is exactly why we add an `Idempotency-Key` to `POST`** — `POST` isn't naturally idempotent, so we *make* it idempotent with a dedup key. `PUT` rarely needs one.

> ⚠️ Idempotent is about **server state**, not the **response**. A second `DELETE` may return `404` instead of `204` — the *state* is identical, which is what matters.

---

## 2.6. Designing Naturally Idempotent Operations

> Sometimes you don't need a key at all — you can **design the operation** so retries are harmless. "How would you *make* this idempotent?" is a common follow-up.

### Absolute vs relative updates

```
balance = balance + 100     ❌ NOT idempotent (each retry adds 100)
balance = 100               ✅ idempotent (same final value every time)

seat.status = 'BOOKED'      ✅ idempotent (setting to same value)
counter++                   ❌ NOT idempotent
```

### Techniques

| Technique | Example |
| --- | --- |
| **Set absolute value** | `SET status = 'CONFIRMED'` instead of incrementing |
| **Upsert** (`INSERT ... ON CONFLICT DO NOTHING/UPDATE`) | create-or-update by a stable key |
| **Conditional update** | `UPDATE ... WHERE status = 'PENDING'` (second call affects 0 rows) |
| **Natural/business key** | dedup on `order_id` the client supplies |
| **PUT instead of POST** | client supplies the resource ID → replace, not create |

> **Rule of thumb:** prefer **declarative** operations ("make it look like X") over **imperative** deltas ("change it by X"). Declarative is naturally idempotent.

---

## 3. Core Idea: The Idempotency Key

The client generates a **unique idempotency key** per logical request.

- Sent via header (common): `Idempotency-Key: <uuid>`
- Or inside the request body

```
Same key      = same operation
Different key  = new operation
```

---

## 4. High-Level Request Flow

1. Client sends `POST /orders` with an idempotency key
2. Server checks a **persistent idempotency store**
3. Based on the result:
   - **Key not found** → process the request
   - **Key exists** → return the stored response

---

## 5. Storage Design

You need a **durable, consistent store** (DB, or Redis with persistence).

### Schema / fields

| Field | Purpose |
| --- | --- |
| `idempotency_key` | Unique key from client |
| `request_hash` | Hash of request payload |
| `status` | `IN_PROGRESS` / `SUCCESS` / `FAILED` |
| `response_body` | Stored API response |
| `http_status` | Status code to replay |
| `order_id` | Useful for crash recovery |
| `created_at` | TTL management |

### Redis key format

```
idempotency:{user_id}:{idempotency_key}
```

Example:

```
idempotency:123:550e8400-e29b-41d4-a716-446655440000
```

> Including `user_id` prevents cross-user key collisions.

### Redis value (JSON)

You can store the value as a **JSON string** (most common) or a **Redis Hash** (alternative).

**When request starts:**

```json
{
  "status": "IN_PROGRESS",
  "request_hash": "a3f5c9d8e21b...",
  "response": null,
  "http_status": null,
  "order_id": null,
  "created_at": 1710000000
}
```

**After success:**

```json
{
  "status": "SUCCESS",
  "request_hash": "a3f5c9d8e21b...",
  "response": { "order_id": 789, "status": "CONFIRMED" },
  "http_status": 201,
  "order_id": 789,
  "created_at": 1710000000
}
```

**If it failed (optional handling):**

```json
{
  "status": "FAILED",
  "request_hash": "a3f5c9d8e21b...",
  "response": { "error": "Payment failed" },
  "http_status": 402,
  "created_at": 1710000000
}
```

### How to write/update it in Redis

```
# Initial insert — only set if key doesn't exist (prevents race condition)
SET idempotency:123:abc123 "<json_value>" NX EX 86400

# Update after success — only update if key exists
SET idempotency:123:abc123 "<updated_json>" XX
```

- `NX` → only set if key does **not** exist
- `XX` → only update if key **already** exists
- `EX 86400` → expires in 24 hours

---

## 6. Request Handling Logic

### Case A — Key does NOT exist

1. Insert record with `status = IN_PROGRESS` (atomically, see [Concurrency](#9-concurrency--race-conditions))
2. Process the order (DB insert → generate `order_id`)
3. Update record → `status = SUCCESS`, store response

### Case B — Key exists

First **compare the hash** (`incoming_hash != stored_hash` → `400 Bad Request`), then act on status:

| Stored status | Action |
| --- | --- |
| `SUCCESS` | Return stored response (no reprocessing, no DB call) |
| `IN_PROGRESS` | Another request is processing → return `409 Conflict`, or `202 Accepted`, or block/wait (less common) |
| `FAILED` | Allow retry (overwrite) **or** return failure consistently |

### Which fields update, and when

- **When the request arrives** → store `key`, `status = IN_PROGRESS`, `request_hash`.
  *(Meaning: "someone started this request.")*
- **After the DB insertion succeeds** → update `status = SUCCESS`, `response`, `order_id`, `http_status` — **all together**, not just the response.
  *(Meaning: "here is the result of that request.")*

---

## 7. How Order Status is Maintained (Idempotency ≠ Order Lifecycle)

⚠️ **Important:** idempotency status and order status are **two separate things**.

### 1. Idempotency status (Redis / store)

- `IN_PROGRESS`
- `SUCCESS`
- `FAILED`

👉 Only for **request deduplication**.

### 2. Order status (Database)

Stored in your **Orders table** — this is the order's real business lifecycle:

```
orders
------
order_id
user_id
status (CREATED, CONFIRMED, FAILED, SHIPPED...)
created_at
```

### Flow relationship

```
Request comes →
Idempotency layer →
Order service →
Database
```

### Example

- **Redis:** `status = SUCCESS`, `order_id = 789`
- **DB:** `order_id = 789`, `status = CONFIRMED`

### 🔁 Retry case

If the client retries:

- Redis says `SUCCESS`
- You return the **stored response**
- You **DO NOT touch the DB again**

> **Mental model:** Redis = *"Have I seen this request before?"* · DB = *"What is the actual order?"*

---

## 8. Payload Consistency (Request Hash)

Goal: **same key + different payload = reject.**

### Steps

1. **Take the request body**

```json
{
  "user_id": 123,
  "items": [
    { "product_id": 1, "qty": 2 },
    { "product_id": 5, "qty": 1 }
  ],
  "address_id": 456
}
```

2. **Normalize it (canonical JSON)** — sort keys, strip whitespace. JSON can differ but mean the same:

```
{"a":1,"b":2}   vs   {"b":2,"a":1}   → same meaning, different string
```

Normalized:

```json
{"address_id":456,"items":[{"product_id":1,"qty":2},{"product_id":5,"qty":1}],"user_id":123}
```

3. **Hash it** — `SHA-256(normalized_json)` (MD5 also works; this isn't security-critical).

4. **Store the hash** alongside the key.

5. **On retry:** if `incoming_hash != stored_hash` → return `400 Bad Request`.

---

## 9. Concurrency & Race Conditions

Two identical requests can hit the backend at the same time (flaky network → double send).

### ❌ The wrong way: `GET` then `SET`

```
Request A → GET key → not found
Request B → GET key → not found
Both process → duplicate order
```

### ✅ The right way: atomic `SET NX`

```
SET idempotency:123:abc123 <value> NX EX 86400
```

- `NX` → only set if key does **not** exist
- Redis is single-threaded → **only one request wins**

**Timeline:**

```
Request A → SET NX → SUCCESS ✅ → proceeds to DB
Request B → SET NX → FAIL ❌ → GET key → IN_PROGRESS → return 409
```

When B retries later, status is `SUCCESS` → it gets the stored response. No duplicate.

> **`SET NX` acts like a lock — only one request is allowed to proceed.**

**DB equivalent:** a `UNIQUE(idempotency_key)` constraint. First insert wins; others fail and fetch the existing record.

---

## 10. Crash & Failure Scenarios

"Crash" = process killed (OOM, deploy, autoscale), container restart, DB timeout, network failure, power loss.

### The dangerous scenario

```
1. Write IN_PROGRESS to Redis ✅
2. Create order in DB         ✅
3. Server crashes BEFORE updating Redis ❌
```

State afterward:

- **Redis:** `status = IN_PROGRESS`
- **DB:** `order_id = 789` (already created)

On retry, the backend sees `IN_PROGRESS` and either returns `409` forever, or reprocesses → **duplicate order**.

> **Key insight:** Redis and DB are two separate systems. They are NOT automatically consistent.

### Fixes

**Option 1 — Store `order_id` early.** Right after the DB insert, update Redis with `order_id` (still `IN_PROGRESS`). On retry you can recover from DB.

**Option 2 — Recovery on retry.** If `status = IN_PROGRESS`, check the DB for an order with this key:
- Found → update Redis → `SUCCESS`, return order
- Not found → continue processing

**Option 3 — Idempotency key in DB (strongest).** Add `idempotency_key UNIQUE` to the `orders` table. The DB itself guarantees no duplicates.

**Other techniques:**
- Wrap the order creation + store update in a **single DB transaction** so they commit atomically.
- Use the **outbox / event-log pattern** — write the result to an outbox table in the same transaction, then publish/process it reliably.
- **Store the response before** external side-effects complete, so a replay never re-triggers them.

### Revised safe flow

```
1. Check store
2. If not found → proceed
3. Insert order in DB (idempotency_key UNIQUE)
4. Update store → SUCCESS
5. Return response

If crash → retry hits DB constraint → fetch existing order
```

---

## 11. TTL / Cleanup

Idempotency keys shouldn't live forever.

- Typical TTL: **24–72 hours** (depends on the retry window)
- Redis: `EX 86400` (24h)
- After expiry, the key is deleted → a new request becomes a new operation

---

## 12. Client (Frontend) Responsibilities

In a real system, the **client (e.g. Android app)** generates the key when the user taps **"Place Order"**.

```
POST /orders
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
```

### Why the frontend generates it

The frontend knows *"this is the same user action being retried."*

### Rules (this is where people mess up)

1. **Generate the key once per user action** — reuse it for retries. A new key per retry → duplicates.
2. **Key must survive retries** — keep it in memory / ViewModel / local DB.
3. **Still prevent double taps** — disable the button + show a loading state. Idempotency is a *safety net*, not UX control.
4. **Backend is the source of truth** — store the key, enforce uniqueness, return the same response.

### App restart edge case

If the app crashes after sending the request, the key may be lost. Advanced apps:
- Persist the key locally until success/failure, **or**
- Fetch order status from the backend on restart.

### Alternative 1: backend-generated key (less common for mobile)

```
Client → /orders/init → backend returns key → client uses it for /orders/create
```

### Alternative 2: natural idempotency (business identifier)

Instead of a dedicated key, use a **business identifier** — e.g. an `order_id` the client generates.

- **Pros:** simpler (no extra key to manage)
- **Cons:** harder when the **server** generates the IDs

---

## 13. Security Note

> **Idempotency is NOT a security feature. It's a correctness / reliability feature.**

A modded APK could generate fake keys, spam requests, or bypass retry logic — and that's fine, because:

- The key is **not trusted for business logic**; it's just a **deduplication token**.
- Frontend-generated keys are **industry standard** (Stripe, PayPal).

Protect against abuse separately (auth, rate limiting, server-side validation).

---

## 14. Redis vs DB: Tradeoffs

### DB-only approach (how it works)

Some systems skip Redis entirely and rely on the DB — a **strong interview discussion point**.

```
orders table
-------------
order_id
user_id
idempotency_key (UNIQUE)
```

Flow:

1. **Insert order** → `INSERT INTO orders (user_id, idempotency_key, ...) VALUES (123, 'abc123', ...)`
2. **DB enforces uniqueness** → Request A inserts ✅, Request B fails ❌ (duplicate key)
3. **Handle failure** → Request B does `SELECT * FROM orders WHERE idempotency_key = 'abc123'` → returns the existing order

Why it's powerful: DB gives **strong consistency**, no separate system needed, no Redis↔DB sync issues.

### Redis approach

| Pros | Cons |
| --- | --- |
| Fast (in-memory) | Two systems → consistency issues |
| Can cache full response | Crash scenarios (Redis/DB mismatch) |
| Great for high throughput | More complexity |

### DB-only approach

| Pros | Cons |
| --- | --- |
| Simpler architecture | Slightly slower |
| Strong consistency | Harder to store full response |
| No `IN_PROGRESS` mismatch | More DB load |

### When to use what

- **DB-only:** you already have a strong DB, moderate throughput, want simplicity.
- **Redis:** very high scale, fast dedup, want to cache full responses.

> **Interview line:** *"I'd start with DB-based idempotency because it's simpler and strongly consistent. At scale, I'd introduce Redis for performance, but carefully handle consistency between Redis and DB."*

---

## 15. Tech Choices

| Layer | Choice | Why |
| --- | --- | --- |
| **DB** | Postgres / MySQL | Strong consistency, unique constraints |
| **Cache** | Redis (with persistence) | Fast dedup, can store full response |
| **Queue** | Kafka (idempotent consumers) | Dedup at the consumer level for event-driven systems |

> Response consistency is non-negotiable: always return the **exact same body + HTTP status** on a retry. This is critical for client trust.

---

## 16. Common Pitfalls

- ❌ Storing the key only in memory → breaks in distributed systems
- ❌ Not handling `IN_PROGRESS` → race conditions
- ❌ Not validating payload consistency (request hash)
- ❌ No persistence → duplicates on restart
- ❌ Returning different responses on retry

---

## 17. Production-Grade Pattern

- Idempotency key **+ DB unique constraint**
- Store the **full response** (body + HTTP status)
- **Request hash** validation
- **TTL** cleanup job
- Handle **`IN_PROGRESS`** safely (atomic `SET NX` / unique constraint)
- Store `order_id` early for crash recovery

### Final mental model

```
Redis / store = "Have I seen this request before?"
DB            = "What is the actual order?"
```

> ⚠️ **Idempotency status ≠ Order lifecycle.**
> `IN_PROGRESS / SUCCESS / FAILED` is about request dedup.
> `CREATED / CONFIRMED / SHIPPED` is the order's business state (lives in DB).

---

## 18. Interview Cheat Sheet

1. **Client-generated identifier is standard** — Stripe `Idempotency-Key`, Google Pay `transactionId`.
2. **Payload validation is mandatory** — same key + different payload = error.
3. **Backend stores the full response** — retries return same data + same status.
4. **TTL is always present** — defines the retry window, prevents infinite storage.
5. **Critical for payments** — duplicate execution = real money loss.
6. **HTTP methods:** `GET`/`PUT`/`DELETE` are idempotent by spec; `POST` is not → that's why `POST` gets an `Idempotency-Key`.
7. **Prefer naturally idempotent design** — absolute updates / upserts / conditional writes over relative deltas.
