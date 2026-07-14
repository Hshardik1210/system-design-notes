# Idempotency

> **The core question:** *A client tapped "Pay", got a timeout, and retried. Did we just charge the customer twice?* Idempotency is how you answer **"no — charged exactly once"** with confidence, no matter how many times the request arrives.

> **Definition:** Idempotency means doing the same operation multiple times has the same effect as doing it once.

> 💡 **The one-line bridge (remember this):** you can't guarantee a message is *delivered* exactly once over a flaky network, so you accept **at-least-once** delivery — retries, duplicate events — and make your *processing* idempotent. The observable result is **effectively-once**: the world changes exactly once even though the request arrived twice. This same idea carries straight from HTTP retries → message queues → webhooks. (Full treatment in §10.)

> **How to read this doc:** each section has the dense summary first, then a **deep dive** (annotated example code, and the exact confusions that trip up beginners). Skim the summaries for revision; read the deep dives to actually understand.

---

## Contents

- [1. Why Idempotency Matters](#1-why-idempotency-matters)
- [2. Where It's Used](#2-where-its-used)
- [2.5. HTTP Method Idempotency (REST semantics)](#25-http-method-idempotency-rest-semantics)
- [2.6. Designing Naturally Idempotent Operations](#26-designing-naturally-idempotent-operations)
- [2.7. When NOT to use an Idempotency-Key](#27-when-not-to-use-an-idempotency-key)
- [3. Core Idea: The Idempotency Key](#3-core-idea-the-idempotency-key)
- [4. High-Level Request Flow](#4-high-level-request-flow)
- [5. Storage Design](#5-storage-design)
- [6. Request Handling Logic](#6-request-handling-logic)
- [7. How Order Status is Maintained (Idempotency ≠ Order Lifecycle)](#7-how-order-status-is-maintained-idempotency-≠-order-lifecycle)
- [8. Payload Consistency (Request Hash)](#8-payload-consistency-request-hash)
- [9. Concurrency & Race Conditions](#9-concurrency--race-conditions)
- [10. Crash & Failure Scenarios](#10-crash--failure-scenarios)
- [10.5. Webhook / Async-Callback Idempotency](#105-webhook--async-callback-idempotency)
- [11. TTL / Cleanup](#11-ttl--cleanup)
- [12. Client (Frontend) Responsibilities](#12-client-frontend-responsibilities)
- [13. Security Note](#13-security-note)
- [14. Redis vs DB: Tradeoffs](#14-redis-vs-db-tradeoffs)
- [15. Tech Choices](#15-tech-choices)
- [16. Common Pitfalls](#16-common-pitfalls)
- [17. Production-Grade Pattern](#17-production-grade-pattern)
- [18. Interview Cheat Sheet](#18-interview-cheat-sheet)
- [Final Takeaways](#final-takeaways)

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

### The "press pay twice, charged once" idea

Consider tapping **"Pay ₹5,000"** at checkout. The screen spins and nothing happens — did it go through? You tap **"Pay"** again. Behind the scenes *two* pay requests may have reached the server. What you want is obvious: **charged once, one order** — no matter how many times you (or a flaky network) hit the button.

That is exactly what idempotency guarantees: the operation can *arrive* many times, but its *effect* happens only once.

```
tap Pay  ─┐
tap Pay  ─┼──►  server  ──►  ₹5,000 charged ONCE, one order
tap Pay  ─┘        (recognises "same request" and ignores the extras)
```

#### Q: Why do requests get sent more than once in the first place?

Because of **retries**. In a distributed system the client often can't tell the difference between "the server never got my request" and "the server did the work but the *reply* got lost on the way back." Both look identical to the client: **no response**. The only safe move for the client is to *try again* — which is why the server must be ready to receive the same thing twice.

```
Client → request → [server did the work] → reply LOST on the way back
Client sees: nothing → assumes failure → retries the SAME request
```

#### Q: So is a retry a bug I should stop?

No — retries are **normal and desirable** (they're how systems survive flaky networks). You don't prevent retries; you make them *safe*. Idempotency is the safety net that lets clients retry freely without causing double charges.

---

## 2. Where It's Used

- **Payments** — Stripe, PayPal, Google Pay
- **Order creation** — e-commerce
- **Booking systems** — tickets, hotels
- **Any API** with retries / unreliable networks

### Spot the pattern

The common thread: **an action that costs something real and must happen exactly once**, running over an **unreliable network** where retries are inevitable. If re-running the action would move money, ship a box, or reserve a seat *again*, it needs idempotency.

- Money moves → **payments** (charge once, not twice).
- A thing gets created → **orders** (one order, not five).
- A limited resource is claimed → **bookings** (one seat, not double-booked).
- Reading data (a `GET`) needs nothing — repeating a read changes nothing (more on this in §2.5).

> Rule of thumb: **"Would doing this twice hurt?"** If yes, make it idempotent.

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

### HTTP methods and idempotency

What each method does when repeated:

- **`GET`** = read-only. Call it 10 times and nothing on the server changes. (Safe *and* idempotent.)
- **`PUT`** = set the resource to a given state. Send it once or five times, the end state is identical. (Idempotent.)
- **`DELETE`** = remove the resource. Delete it, then delete it again — it's already gone, same end state. (Idempotent.)
- **`POST`** = create a new resource. Call it twice and you get *two* resources. Each call creates a new thing. (**Not** idempotent → duplicates.)

```java
// PUT — you send the WHOLE desired state, so repeats converge to the same thing
PUT /users/42   { "name": "Asha", "email": "asha@x.com" }   // run 5 times → identical user

// POST — server invents a NEW id each time, so repeats pile up
POST /orders    { "items": [...] }   // run 5 times → orders #101, #102, #103, #104, #105  ❌
```

#### Q: If `PUT`/`DELETE` are already idempotent, why does `POST` get all the attention?

Because `POST` is the odd one out — it *creates* — and creation is exactly where duplicates cause double charges. So we bolt idempotency onto `POST` artificially with an **`Idempotency-Key`** (see §3). The other methods rarely need one because they're idempotent *by design*.

#### Q: The second `DELETE` returned `404` — didn't the "same effect" rule break?

No. Idempotency is a promise about **server state**, not about the exact bytes you get back. After the first `DELETE` the resource is gone; after the second it's *still* gone — state is identical. The `404` just describes "there was nothing to delete," which is fine.

---

## 2.6. Designing Naturally Idempotent Operations

> Sometimes you don't need a key at all — you can **design the operation** so retries are harmless. "How would you *make* this idempotent?" is a common follow-up.

### Techniques

| Technique | Example |
| --- | --- |
| **Set absolute value** | `SET status = 'CONFIRMED'` instead of incrementing |
| **Upsert** (`INSERT ... ON CONFLICT DO NOTHING/UPDATE`) | create-or-update by a stable key |
| **Conditional update** | `UPDATE ... WHERE status = 'PENDING'` (second call affects 0 rows) |
| **Natural/business key** | dedup on `order_id` the client supplies |
| **PUT instead of POST** | client supplies the resource ID → replace, not create |

> **Rule of thumb:** prefer **declarative** operations ("make it look like X") over **imperative** deltas ("change it by X"). Declarative is naturally idempotent.

### "Set it", don't "nudge it"

Setting an **absolute** value (*"set the balance to 100"*) converges to the same result no matter how many times it runs. A **relative** delta (*"add 100"*) stacks up on every retry — run it 10 times and you've added 1000. **Absolute = safe to repeat. Relative = dangerous to repeat.**

```java
// ❌ Relative / imperative — every retry stacks up
account.balance = account.balance + 100;   // 3 retries → +300, wrong!
counter = counter + 1;                      // 3 retries → +3, wrong!

// ✅ Absolute / declarative — retries converge to one answer
account.balance = 100;                      // 3 retries → still 100 ✅
seat.status = "BOOKED";                      // 3 retries → still BOOKED ✅
```

Conditional writes get you there too — the *first* call does the work, later calls quietly do nothing:

```sql
-- Second identical call updates 0 rows, because status is no longer 'PENDING'
UPDATE orders SET status = 'CONFIRMED'
WHERE order_id = 789 AND status = 'PENDING';
```

#### Q: Natural key vs synthetic (idempotency) key — what's the difference?

- **Natural / business key** = an identifier that already *means something* in your domain and the client already has: an `order_id`, an `email`, a `seat_number`. You dedup on it because it's naturally unique. *"There can only be one order #789."*
- **Synthetic / idempotency key** = a meaningless token (usually a random `UUID`) generated *only* to detect retries. It carries no business meaning; it exists purely to say *"this is the same attempt as before."*

```
Natural key:    order_id = 789          (means something; unique by nature)
Synthetic key:  Idempotency-Key = a UUID (means nothing; just a "same request?" tag)
```

#### Q: When do I use which?

If the client can supply a stable business id (e.g. it generates the `order_id`), lean on the **natural key** — it's simpler, no extra machinery. If the id is only known *after* the server creates the record (server-generated ids), you can't dedup on it up front, so you fall back to a **synthetic idempotency key** the client makes before sending. Payments APIs (Stripe, etc.) use synthetic keys for exactly this reason.

---

## 2.7. When NOT to use an Idempotency-Key

An idempotency key is **machinery** — a durable stored record per request, a hash check, a TTL cleanup job. It earns its keep when a duplicate would *hurt*. When a duplicate is harmless (or impossible), skip the key.

| Situation | Why you can skip the key |
| --- | --- |
| **Reads (`GET` / `HEAD` / `OPTIONS`)** | No side effects — repeating a read changes nothing. A key buys zero safety. |
| **Naturally idempotent writes (`PUT`, absolute `SET`, upserts)** | The operation already converges to the same state on repeat (§2.6). The key would be redundant. |
| **Ops where a duplicate is genuinely harmless** | E.g. writing the *same* value to a cache, appending to a content-keyed log. If re-running costs nothing, don't pay for dedup. |
| **Analytics / fire-and-forget events at extreme scale** | Billions of low-value events (page views, metrics). A per-event idempotency record can cost more in storage than an occasional double-count is worth — dedup downstream, or just tolerate it. |

> 💡 **Decision rule:** reach for an idempotency key only for **non-idempotent side effects that must happen once** (charge, create, ship). For everything else, prefer *designing* the op to be naturally idempotent (§2.6) — it's cheaper and simpler.

> ⚠️ **"Harmless" is a business call, not a technical one.** A duplicate audit-log line looks harmless until compliance counts it; a duplicate notification looks harmless until the user gets two SMS charges. Confirm "a duplicate is fine here" with the domain — don't assume it.

#### Q: Isn't it safer to just add a key everywhere?

No — it adds real cost: a durable write on the hot path, a store to operate and monitor, a TTL cleanup job, and hashing on every request. At extreme scale that overhead is not free, and for operations that are already idempotent it protects against nothing. Idempotency keys are a **targeted tool for dangerous repeats**, not a blanket policy.

---

## 3. Core Idea: The Idempotency Key

The client generates a **unique idempotency key** per logical request.

- Sent via header (common): `Idempotency-Key: <uuid>`
- Or inside the request body

```
Same key      = same operation
Different key  = new operation
```

### The idempotency key identifies a request

The idempotency key is a token attached to a request. When the server sees **the same key** again, it recognises *"this is the same request from before"* and hands back the same result instead of doing the work again. A **different key** means a different operation.

```java
// Client makes ONE key for ONE user action ("Place Order"), and reuses it on every retry
String idempotencyKey = UUID.randomUUID().toString();  // e.g. "550e8400-...-446655440000"

// attempt 1 (timed out) and attempt 2 (retry) BOTH send the SAME key
httpPost("/orders", body, header("Idempotency-Key", idempotencyKey));
// ... no response, retry ...
httpPost("/orders", body, header("Idempotency-Key", idempotencyKey));  // same key on purpose
```

#### Q: Where does the key come from, and when is a new one made?

The **client generates it once per real user action** (one tap of "Place Order" = one key) and reuses that exact key for every retry of that action. A brand-new key means "this is a genuinely new operation." So the golden rule is: **new user intent → new key; retry of the same intent → same key.** (More client-side rules in §12.)

#### Q: Header or request body?

Either works, but the **HTTP header** (`Idempotency-Key: <uuid>`) is the convention (Stripe, PayPal) because it's separate from your business payload and easy for middleware to read. Putting it in the body is fine too, just less standard.

---

## 4. High-Level Request Flow

1. Client sends `POST /orders` with an idempotency key
2. Server checks a **persistent idempotency store**
3. Based on the result:
   - **Key not found** → process the request
   - **Key exists** → return the stored response

### The request flow

Every request passes through the idempotency layer, which checks the idempotency store for the key: **key not present?** → process the request and record the key. **Key already present?** → don't reprocess; return the result stored from the first time.

```java
// The whole flow in ~10 lines of pseudo-Java
Record existing = store.get(key);

if (existing == null) {                 // key NOT found → first time seeing this
    store.put(key, "IN_PROGRESS");      //   write it down
    Response r = processOrder(request); //   do the real work
    store.put(key, "SUCCESS", r);       //   remember the result
    return r;
} else {                                // key EXISTS → we've seen this request
    return existing.storedResponse();   //   replay old result, do NOT redo the work
}
```

#### Q: The "check then process" — isn't there a gap where two copies sneak in at once?

Yes, and that's the single trickiest part. Two retries can both hit "key not found" at the *same instant*. The fix is to make step 1 an **atomic** "write-it-down-only-if-absent" (a `SET NX` in Redis or a `UNIQUE` constraint in the DB) so only one wins. This is important enough to have its own section — see §9 (Concurrency).

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

### What each stored field is *for*

For every request you keep a stored record. If the same request comes back, you read the record and reply with what it says instead of doing the work again. Each field earns its place:

| Field | Why it's stored |
| --- | --- |
| `idempotency_key` | The identifier used to look up this record. |
| `request_hash` | A fingerprint of *what was asked*, so we can catch "same key, different payload" (see §8). |
| `status` | Which stage we're at: `IN_PROGRESS`, `SUCCESS`, or `FAILED`. |
| `response_body` + `http_status` | The exact reply to replay on a retry, so the client sees identical bytes. |
| `order_id` | A pointer to the record we created, for crash recovery (see §10). |
| `created_at` | When to auto-delete it (see §11 TTL). |

The two-step write in code — reserve first, fill in the answer later:

```java
// Step 1: when the request STARTS — stake a claim (only if nobody else has)
// "SET ... NX" = write ONLY if the key doesn't already exist → wins the race
redis.set(key, json(status="IN_PROGRESS", request_hash=hash), "NX", "EX", 86400);

// ... do the actual work (create the order) ...

// Step 2: when it SUCCEEDS — overwrite with the real result (only if key exists)
redis.set(key, json(status="SUCCESS", response=body, http_status=201, order_id=789), "XX");
```

#### Q: Why include `user_id` in the Redis key (`idempotency:{user_id}:{key}`)?

To stop **cross-user collisions**. If two different users' clients ever generated the same UUID (rare, but not impossible with buggy clients), namespacing by `user_id` keeps them in separate slots so one user can never accidentally read another user's stored response.

#### Q: Why store the *whole* response, not just "done"?

Because the retry must get back **the exact same body and status code** as the original — that's the whole promise. If you only stored "done," you'd have to recompute the response (and risk it differing). Storing it verbatim makes replay trivial and trustworthy.

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

### The three answers the server can give

When a second request shows up with a familiar key, the server is basically answering *"what happened to the first one?"* There are only three possible answers, and each has a sensible reply:

```java
Record r = store.get(key);

if (r == null) {                       // never seen it → do the work (Case A)
    ...
} else {                               // seen it → don't redo (Case B)
    if (!r.request_hash.equals(hash))  // same ticket, DIFFERENT order?
        return http(400, "key reused with a different payload");

    switch (r.status) {
        case "SUCCESS":     return http(r.http_status, r.response); // replay, no DB call
        case "IN_PROGRESS": return http(409, "still processing, try again shortly");
        case "FAILED":      return retryOrReturnFailure(r);         // your policy
    }
}
```

The three stored statuses map to three responses:
- **`SUCCESS`** → return the *same* stored response; don't reprocess.
- **`IN_PROGRESS`** → another copy is still being processed → return `409`/`202`, don't start a second one.
- **`FAILED`** → either retry or return the failure consistently.

#### Q: Why check the hash *before* looking at the status?

Because a matching key is supposed to mean "the exact same request." If the key matches but the payload differs, something is wrong (a client bug, or key reuse), and silently returning the *old* order would be misleading. So you reject early with `400` before doing anything else. (Details in §8.)

#### Q: Why update all the fields together at the end, not the response alone?

So the record can never be left half-true (e.g. `status` still `IN_PROGRESS` but a response present). Writing `status`, `response`, `order_id`, and `http_status` in **one** update keeps the record internally consistent — a retry always sees a complete, trustworthy record.

### The `IN_PROGRESS` response policy: `409` vs `202` vs wait

When a retry lands while the first copy is *still running*, you can't return a result yet — there isn't one. You have three choices for what to tell the caller:

| Policy | Response | When to use it |
| --- | --- | --- |
| **Reject** | `409 Conflict` | Default and simplest. Says "a copy is in flight, retry shortly." Client backs off and retries/polls. |
| **Accept + point to status** | `202 Accepted` + a status URL / job id | Long-running or async work. The client polls the status endpoint instead of blocking. |
| **Synchronous wait** | block, then return the real result | Best UX (one call, one answer) but ties up a server thread/connection and needs a timeout. Use only for short work behind a bounded wait. |

> 💡 **Pick `409` unless you have a reason not to.** It's stateless and cheap. Reach for `202` when the work is genuinely async, and only block synchronously for short operations where a bounded wait beats making the client poll.

> ⚠️ Whatever you pick, **never start a second copy of the work** on an `IN_PROGRESS` hit — that's the exact duplicate you're trying to prevent. All three policies leave the original in-flight request untouched.

### The idempotency record's state machine

The stored record moves through a tiny lifecycle. `NEW` is the implicit *"key not found"* state; the first request atomically claims the key and drives it to a terminal state.

```
              first request claims the key (atomic SET NX / UNIQUE)
   NEW  ─────────────────────────────────────────────────►  IN_PROGRESS
 (no key)                                                        │
                                                                 │ work finishes
                                                   ┌─────────────┴─────────────┐
                                                   ▼                           ▼
                                               SUCCESS                      FAILED
                                        (store full response)         (per your policy)
                                                   │                           │
                        retry ─────────────────────┘                           ▼
                     (replay stored response,                       retry: allow re-attempt
                      no re-processing)                             (overwrite) OR return
                                                                    the failure consistently

   retry while IN_PROGRESS ──►  409 / 202 / wait   (do NOT start a second copy)
```

- **`NEW → IN_PROGRESS`** — only the first of racing retries wins the atomic claim (§9).
- **`IN_PROGRESS → SUCCESS`** — work done; store the full response so retries replay it.
- **`IN_PROGRESS → FAILED`** — work failed; your retry policy decides whether the key may be reused.
- **retry while `IN_PROGRESS`** — answered by the policy table above; never a second copy.

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

### Two different "statuses" people keep confusing

Idempotency status tracks *"did we already handle this request?"* (`IN_PROGRESS` / `SUCCESS` / `FAILED`). Order status tracks the order's own lifecycle (`CREATED` → `SHIPPED` → `DELIVERED`). They're **two separate trackers** for two separate things.

```java
// Idempotency store — ONLY about request dedup ("have I handled this call?")
idempotency.status ∈ { IN_PROGRESS, SUCCESS, FAILED }

// Orders table — the real business lifecycle of the order itself
order.status       ∈ { CREATED, CONFIRMED, SHIPPED, DELIVERED, CANCELLED }
```

They connect but don't mirror each other:

```
Redis:  key=abc123  → status=SUCCESS,  order_id=789   ("yes, I handled that request")
DB:     order_id=789 → status=CONFIRMED               ("and here's what the order is doing")
```

#### Q: If an order later gets `SHIPPED` or `CANCELLED`, does the idempotency status change?

No. Idempotency status freezes at `SUCCESS` once the request was handled — it's a record of *the API call*, not the order. The order can go on to be shipped, delivered, or refunded; that lives entirely in the Orders table. Mixing the two is a classic beginner mistake.

#### Q: On a retry, why must I *not* touch the DB again?

Because the request was already handled — the order already exists. The retry just wants the *same answer*. So you read the stored response from the idempotency layer and return it. Re-running the DB write risks a second order (the very thing we're preventing).

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

> ⚠️ **Hash only the *stable* business fields.** If the body carries volatile data — a client `timestamp`, a `nonce`, a retry counter — those change on every attempt and would make a legitimate retry look like a "different payload," triggering a spurious `400`. Exclude such fields from the canonical form *before* hashing.

### The fingerprint that catches key reuse

The **request hash** is a fingerprint of the original request payload. If a later call reuses the key but the fingerprint doesn't match, the payload differs from what the key was first used for — so you reject it instead of returning the wrong stored result.

```java
// Turn the body into ONE short fingerprint string
String fingerprint(String body) {
    String canonical = canonicalJson(body); // sort keys, strip whitespace → stable form
    return sha256(canonical);               // e.g. "a3f5c9d8e21b..."
}

// On a retry with a known key:
if (!incomingHash.equals(storedHash)) {
    return http(400, "Idempotency-Key reused with a different payload");
}
```

#### Q: Why "normalize" (canonical JSON) before hashing?

Because two JSON strings can *mean the same thing* but *look different* — `{"a":1,"b":2}` vs `{"b":2,"a":1}` — and would hash to different values. Sorting keys and stripping whitespace first gives a **stable, canonical form** so logically-identical requests produce identical fingerprints. Otherwise you'd get false `400`s on perfectly valid retries.

#### Q: Does the hash need to be a *secure* hash?

No. This isn't a security control — it's just change-detection. `SHA-256` is fine, but even `MD5` works here; you only need "different input → different fingerprint," not cryptographic strength. (Security is covered separately in §13.)

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

> ⚠️ **An `IN_PROGRESS` claim can get stuck.** If the request that won the `SET NX` crashes *before* writing `SUCCESS`, the key sits at `IN_PROGRESS` until its TTL expires — and until then, later retries keep getting `409`. That's exactly the crash case §10 solves: recover from the DB on retry, or let the TTL eventually free the key.

**DB equivalent:** a `UNIQUE(idempotency_key)` constraint. First insert wins; others fail and fetch the existing record.

### Why "check, then set" is a trap

If checking and setting are *separate* steps, two concurrent requests can both read "key absent" and both proceed → duplicate. The fix is a **single atomic step**: write the key *only if* it doesn't already exist. Whichever request runs that instruction first wins; the other fails the write.

```java
// ❌ Trap: two separate steps → both can pass the check before either writes
if (redis.get(key) == null) {   // A sees null, B sees null (both at once)
    redis.set(key, value);      // both write → both "win" → duplicate order
}

// ✅ Atomic: check-and-write are ONE operation the datastore does indivisibly
boolean iWon = redis.set(key, value, "NX", "EX", 86400);  // NX = only if absent
if (iWon) {
    processOrder();             // exactly ONE request reaches here
} else {
    return replayOrConflict(key); // the loser reads the winner's record
}
```

The database version is the exact same idea, enforced by the schema:

```sql
-- The UNIQUE constraint IS the atomic check. First INSERT wins; the rest throw.
CREATE TABLE idempotency ( idempotency_key VARCHAR PRIMARY KEY, ... );

INSERT INTO idempotency (idempotency_key, status) VALUES ('abc123', 'IN_PROGRESS');
-- Request B's identical INSERT → duplicate-key error → B SELECTs the existing row instead
```

#### Q: Why does `SET NX` behave like a lock without me writing locking code?

Because Redis runs commands **one at a time** (single-threaded), so `SET NX` can't be "interrupted" halfway. The instant one request creates the key, every other `NX` for that key fails. You get mutual exclusion for free — no explicit lock, no `GET` + `SET` window for a race to slip through.

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

### Two systems that can disagree

Redis records that the request started; the DB is where the order is actually created. If the process crashes *after* the DB creates the order but *before* Redis is updated to `SUCCESS`, the two disagree: Redis says `IN_PROGRESS`, the DB says the order exists. Neither is wrong — they're just **separate systems that don't update together atomically**.

```
1. Redis: IN_PROGRESS   ✅
2. DB:    order #789 created ✅
3. 💥 crash before updating Redis to SUCCESS
   → Redis: IN_PROGRESS   |   DB: order #789 exists   (they disagree)
```

The robust fix is to let the **DB enforce uniqueness** with a unique key, so even a crashed-and-retried request can't create a second order:

```java
try {
    // idempotency_key is UNIQUE in the orders table → DB itself blocks duplicates
    Order o = db.insert(order, idempotencyKey);   // first attempt wins
    store.set(key, "SUCCESS", o);
    return o;
} catch (DuplicateKeyException e) {
    // a previous attempt already created it (maybe before the crash)
    Order existing = db.findByIdempotencyKey(idempotencyKey);  // recover the real order
    store.set(key, "SUCCESS", existing);
    return existing;                                            // no second order created
}
```

### Worked example: the side effect succeeded but our write didn't

The nastiest partial failure isn't "we crashed before finishing" — it's when an **irreversible external side effect already happened** and *then* our own bookkeeping failed:

```
1. store: IN_PROGRESS                                   ✅
2. call payment provider → CHARGE ₹5,000 SUCCEEDS       ✅  (money moved — external, irreversible)
3. write order row to our DB → FAILS (timeout / crash)  ❌
   → provider thinks: PAID.   our DB thinks: no order.   💥 mismatch
```

Now a retry arrives. If we naively "process again," we call the provider a second time and **double-charge**. Two things save us:

```java
boolean firstTime = store.setIfAbsent(key, "IN_PROGRESS", hash, TTL);
if (!firstTime) { /* replay or 409, as usual */ }

// (1) Pass the SAME idempotency key to the external call. Stripe et al. dedup on it,
//     so a retried charge returns the ORIGINAL charge instead of creating a new one.
Charge c = provider.charge(amount, key);      // idempotent AT THE PROVIDER too

try {
    Order o = db.insertOrder(req, key);       // idempotency_key UNIQUE
    store.set(key, "SUCCESS", http(201, o), o.id);
    return http(201, o);
} catch (DuplicateKeyException e) {
    return http(201, db.findByKey(key));      // order already existed → replay
}
```

1. **Propagate the idempotency key to the external call**, so *its* retry is deduped too — the charge happens once even if we call twice.
2. **Reconciliation** for anything that still slips through: a background job compares *"provider says charged"* against *"we have an order"* and repairs the gap — create the missing order, or refund an orphaned charge.

> ⚠️ You **cannot** wrap an external charge and your DB write in one transaction — the provider isn't in your database, so there's no atomic commit across both. That's why the idempotency key must reach *both* systems, and why a **reconciliation job** is the ultimate backstop for money-moving flows.

> 💡 **Where possible, order the steps so the replayable record exists before the irreversible effect.** When a side effect is truly irreversible, make the downstream call itself idempotent (pass the key) so *"did this already happen?"* is answerable without guessing.

#### Q: "At-least-once delivery + idempotent processing = effectively-once" — what does that mean?

It's the whole payoff, in one line. Many systems (message queues like Kafka, HTTP clients with retries) promise only **at-least-once**: *"I'll deliver your message, but maybe more than once."* They refuse to promise "exactly once" because guaranteeing that across a network is extremely hard. So duplicates *will* happen. If your *processing* is **idempotent**, a duplicate delivery has no extra effect — so the observable outcome is as if it happened **exactly once**. That combined result is called **effectively-once** (or "exactly-once semantics").

```
delivery guarantee (hard to make exactly-once):  at-least-once  → duplicates happen
+ your handler is idempotent (dedup / absolute writes)
= effectively-once  → the WORLD changes exactly once, even though messages arrived twice
```

> Takeaway: you don't fight the network for "exactly-once delivery." You accept "at-least-once" and make your *code* idempotent — that's how real systems get exactly-once *outcomes*.

---

## 10.5. Webhook / Async-Callback Idempotency

So far the *client* retried *us*. Webhooks flip the direction: an external provider (Stripe, a payment gateway, a shipping API) calls **you** back to say *"payment succeeded."* Those callbacks are delivered **at-least-once** too — the provider retries until you return `200`, so you *will* receive the same event more than once. Same problem, opposite direction: process each event **once**.

The pattern has three parts:

1. **Verify the signature first.** A webhook is a public URL, so *anyone* can POST to it. Providers sign each payload (typically an HMAC over the raw body with your webhook secret). Reject anything whose signature doesn't match *before* you look at the contents.
2. **Dedup on the provider's `event_id`.** Every event carries a stable id (`evt_1abc...`). Treat it exactly like an idempotency key: record processed event ids in a dedup store with a `UNIQUE` constraint; a redelivery hits the constraint and is skipped.
3. **Process, then acknowledge.** Do the work idempotently, then return `200`. If you crash before `200`, the provider redelivers — which is fine, because step 2 makes reprocessing a no-op.

```java
void onWebhook(HttpRequest req) {
    if (!signatureValid(req.body, req.header("Stripe-Signature"), WEBHOOK_SECRET))
        return http(400, "bad signature");           // step 1: authenticate the caller

    String eventId = parse(req.body).id;              // e.g. "evt_1abc..."

    boolean fresh = dedup.setIfAbsent(eventId, "PROCESSED", TTL);
    if (!fresh) return http(200, "already handled");  // step 2: duplicate → ack, do nothing

    handleEvent(parse(req.body));                     // step 3: idempotent processing
    return http(200, "ok");
}
```

> ⚠️ **Signature verification is not optional here.** Unlike a client idempotency key (which grants no power, §13), a webhook endpoint *acts* on what it receives — an unverified "payment succeeded" event is a forged-money exploit. Verify the signature, *then* dedup.

> 💡 **Same mental model, both directions.** Outbound: *your* client sends an `Idempotency-Key` so the provider dedups. Inbound: the provider sends an `event_id` so *you* dedup. Both turn at-least-once delivery into effectively-once processing (§10).

#### Q: Why dedup on `event_id` instead of hashing the event's contents?

Because the `event_id` is the provider's stable, guaranteed-unique handle for that logical event — every redelivery of it shares the same id. Hashing the contents can also work, but ids are simpler, collision-free, and exactly what providers intend you to use. (Two *distinct* events could, in theory, carry identical bodies; their ids would still differ.)

---

## 11. TTL / Cleanup

Idempotency keys shouldn't live forever.

- Typical TTL: **24–72 hours** (depends on the retry window)
- Redis: `EX 86400` (24h)
- After expiry, the key is deleted → a new request becomes a new operation

### Why keys expire

Idempotency keys only need to live long enough to cover the realistic **retry window** — a client retries within seconds or minutes, not next week. After that, keeping them is pure waste.

```java
redis.set(key, value, "NX", "EX", 86400);  // EX 86400 = auto-delete after 24 hours
```

#### Q: What actually happens after a key expires?

It's gone, so the *same* key sent later is treated as a **brand-new operation** — the server no longer remembers it. That's fine and intended: legitimate retries happen fast (well inside the TTL), so by the time a key expires, any real retry attempt is long over.

#### Q: How long should the TTL be?

Match it to how long clients might reasonably retry — typically **24–72 hours**. Too short and a slow retry sneaks past the dedup window (risking a duplicate); too long and you hoard keys forever (wasting memory). Pick a value comfortably longer than your worst-case retry window.

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

### The key belongs to the *action*, not the network call

The client must generate **one key per real user intent** ("Place Order" tapped once) and reuse that same key across every retry. Generating a new key on each retry makes the server treat every attempt as a different operation, which defeats the whole scheme.

```java
// ✅ Correct: make the key when the user acts, reuse it on retries
void onPlaceOrderTapped() {
    String key = savedKey != null ? savedKey : (savedKey = UUID.randomUUID().toString());
    disablePlaceOrderButton();          // also prevent accidental double taps (UX layer)
    sendWithRetries("/orders", body, key);  // same key on attempt 1, 2, 3...
}

// ❌ Wrong: a fresh key each retry → server sees 3 different "operations" → 3 orders
void onRetry() { send("/orders", body, UUID.randomUUID().toString()); }
```

#### Q: Why the *frontend*? Isn't the backend the source of truth?

The backend *is* the source of truth for *enforcing* idempotency (it stores keys and dedups). But only the **frontend knows that two sends are the same user action** — that this retry is "the same Place Order I tried 3 seconds ago." The server can't tell retries from genuinely new orders on its own; the client signals it by reusing the key.

#### Q: Button-disable *and* idempotency — isn't that redundant?

They cover different failure modes. Disabling the button stops *accidental double taps* (a UX concern). Idempotency stops *duplicates from network retries* — timeouts, dropped responses, auto-retry libraries — which the client can't prevent by disabling a button. You want both: UX to avoid obvious mistakes, idempotency as the real safety net.

#### Q: What if the app crashes and loses the key?

Then a retry would generate a new key and risk a duplicate. Robust apps either **persist the key locally** (until the request finally succeeds/fails) or, on restart, **ask the backend for the order's status** instead of blindly re-sending. This is also where a **natural/business key** (§2.6) shines — if the order id itself is the dedup key, there's nothing extra to lose.

---

## 13. Security Note

> **Idempotency is NOT a security feature. It's a correctness / reliability feature.**

A modded APK could generate fake keys, spam requests, or bypass retry logic — and that's fine, because:

- The key is **not trusted for business logic**; it's just a **deduplication token**.
- Frontend-generated keys are **industry standard** (Stripe, PayPal).

Protect against abuse separately (auth, rate limiting, server-side validation).

### Dedup token, not a password

The idempotency key is a deduplication token the client attaches; it isn't a credential that proves *who* they are or *what* they're allowed to do. A malicious client can forge keys freely — and that's fine, because the key never *authorizes* anything; it only helps you avoid accidentally repeating work.

#### Q: If a hacker can fake keys, doesn't that break the system?

No, because the key grants no power. Worst case, a forged key just makes *their own* request dedup against *their own* earlier one — they can't read someone else's order (that's what namespacing by `user_id` and real authentication are for). The dangers that idempotency does **not** cover — impersonation, spam, abuse — are handled by separate, purpose-built controls: **authentication, authorization, and rate limiting**.

> One line: idempotency answers *"did I already do this?"*, never *"is this person allowed to do this?"* Keep the two concerns separate.

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

### DB-only vs Redis + DB

- **DB-only** = the `UNIQUE(idempotency_key)` constraint enforces "exactly one": the first request succeeds, duplicates fail. Simple and always consistent, but the DB handles *every* dedup check.
- **Redis + DB** = Redis filters most duplicates instantly in memory, with the DB still there as the final guard. Faster and can cache the full response — but now two systems can momentarily disagree (the crash mismatch from §10).

```java
// DB-only: the schema itself enforces "exactly one"
CREATE TABLE orders ( order_id ..., idempotency_key VARCHAR UNIQUE );
// Request A inserts ✅   Request B → duplicate-key error → SELECT existing row

// Redis + DB: Redis answers the fast common case, DB remains the ultimate guard
if (redis.set(key, "IN_PROGRESS", "NX")) { ... } else { return replay(key); }
```

#### Q: Which should I actually pick?

Start **DB-only** — it's simpler, strongly consistent, and needs no extra moving part. Reach for **Redis** only when throughput is high enough that hitting the DB for every dedup check hurts, or when you want to cache the full response for instant replays. And if you add Redis, treat the **DB unique constraint as the real backstop** so a Redis/DB mismatch can never create a duplicate.

---

## 15. Tech Choices

| Layer | Choice | Why |
| --- | --- | --- |
| **DB** | Postgres / MySQL | Strong consistency, unique constraints |
| **Cache** | Redis (with persistence) | Fast dedup, can store full response |
| **Queue** | Kafka (idempotent consumers) | Dedup at the consumer level for event-driven systems |

> Response consistency is non-negotiable: always return the **exact same body + HTTP status** on a retry. This is critical for client trust.

### What each tool is doing here

Each technology plays one job in the dedup story:
- **DB (Postgres/MySQL)** = the durable store. Its `UNIQUE` constraint is the hard guarantee that no two orders share a key.
- **Redis** = the fast in-memory check out front. It answers "seen this?" in microseconds and can hand back the cached response.
- **Kafka (idempotent consumers)** = for event-driven systems where the queue delivers a message twice (at-least-once); the consumer dedups so it processes the event once.

#### Q: Why does the "same body + same status on retry" rule matter so much?

Because the client uses the response to decide what to show the user. If the first reply said `201 Created {order_id: 789}` but the retry said `200 OK {}` or a different error, the client (and the user) gets confused about whether the order exists. Returning **byte-for-byte the same response** makes a retry indistinguishable from the original — which is the entire point of idempotency.

---

## 16. Common Pitfalls

- ❌ Storing the key only in memory → breaks in distributed systems
- ❌ Not handling `IN_PROGRESS` → race conditions
- ❌ Not validating payload consistency (request hash)
- ❌ No persistence → duplicates on restart
- ❌ Returning different responses on retry

### Each pitfall = a broken promise

Every pitfall above is just one of the earlier rules being violated — here's the "so what" for each:

| Pitfall | What actually goes wrong |
| --- | --- |
| Key only in memory | One server remembers the key; a *different* server (behind the load balancer) doesn't → it reprocesses → duplicate. The store must be **shared** (Redis/DB), not per-process. |
| Not handling `IN_PROGRESS` | Two simultaneous retries both start work → duplicate. You need the atomic `SET NX` / unique constraint from §9. |
| No request-hash check | "Same key, different payload" silently returns the wrong stored result (§8). |
| No persistence | A restart wipes in-memory keys → the next retry looks brand-new → duplicate (§10). |
| Different responses on retry | Client can't tell if it worked; erodes trust (§15). |

#### Q: These all sound like the same bug — are they?

Essentially yes: they're all *"a duplicate slipped through"* or *"the retry got an inconsistent answer."* The reason there are so many is that duplicates can sneak in at **every layer** — client, network, multiple servers, storage, crashes. Idempotency has to hold at *all* of them, so each pitfall is one layer where people forget to close the gap.

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

### The whole pattern in one annotated flow

Putting every piece together — this is the "press pay twice, charged once" guarantee end to end:

```java
Response handlePayment(Request req, String key) {
    String hash = fingerprint(req.body);                 // §8: detect key reuse

    // §9: atomic claim — only the FIRST of two racing retries wins
    boolean firstTime = store.setIfAbsent(key, "IN_PROGRESS", hash, TTL_24H);

    if (!firstTime) {                                     // §6: we've seen this key
        Record r = store.get(key);
        if (!r.hash.equals(hash)) return http(400, "same key, different payload");
        if (r.status.equals("SUCCESS")) return r.response;      // replay, no re-charge
        if (r.status.equals("IN_PROGRESS")) return http(409, "still processing");
    }

    // §10: DB unique constraint is the ultimate backstop against duplicates
    try {
        Order o = db.insertOrder(req, key);              // idempotency_key UNIQUE
        Response resp = http(201, o);
        store.set(key, "SUCCESS", resp, o.id);           // §5: remember full response
        return resp;
    } catch (DuplicateKeyException e) {                   // a prior attempt already did it
        Order existing = db.findByKey(key);
        store.set(key, "SUCCESS", http(201, existing), existing.id);
        return http(201, existing);                      // effectively-once outcome
    }
}
```

#### Q: If I remember only *one* thing, what should it be?

**Make retries harmless.** Everything here — client-side stable keys, atomic `SET NX`, request-hash checks, a DB unique constraint, storing the full response, TTL — serves one goal: a request can arrive any number of times, but its real-world effect (the charge, the order) happens **exactly once**. That's idempotency.

---

## 18. Interview Cheat Sheet

1. **Client-generated identifier is standard** — Stripe `Idempotency-Key`, Google Pay `transactionId`.
2. **Payload validation is mandatory** — same key + different payload = error.
3. **Backend stores the full response** — retries return same data + same status.
4. **TTL is always present** — defines the retry window, prevents infinite storage.
5. **Critical for payments** — duplicate execution = real money loss.
6. **HTTP methods:** `GET`/`PUT`/`DELETE` are idempotent by spec; `POST` is not → that's why `POST` gets an `Idempotency-Key`.
7. **Prefer naturally idempotent design** — absolute updates / upserts / conditional writes over relative deltas.

### How to *say* it in an interview

If you get "design an idempotent payment/order API," walk it in this order — it mirrors the sections above:

1. **Frame the problem** — "The network is unreliable, so clients retry. Without protection, a retry double-charges. Idempotency makes repeats safe."
2. **Client sends a key** — "The client generates an `Idempotency-Key` (a UUID) once per user action and reuses it on retries." (§3)
3. **Server dedups atomically** — "First request wins via `SET NX` (Redis) or a `UNIQUE` constraint (DB); duplicates read back the stored result." (§9)
4. **Store the full response** — "So every retry returns the identical body + status." (§5, §15)
5. **Validate the payload hash** — "Same key + different payload → `400`." (§8)
6. **Handle crashes** — "DB unique key is the backstop; recover the existing order on retry." (§10)
7. **Mention the big principle** — "At-least-once delivery + idempotent processing = **effectively-once**." (§10)

#### Q: What's the single sentence that impresses?

*"I don't try to guarantee exactly-once **delivery** — that's nearly impossible over a network — I accept at-least-once and make the **processing** idempotent, so the outcome is exactly-once."* It shows you understand *why* idempotency exists, not just *how* to code it.

#### Q: Simplest correct starting point if I'm unsure?

*"A DB-based idempotency key with a unique constraint, storing the full response, with a TTL."* It's simple, strongly consistent, and covers the core cases — then you layer on Redis for speed at scale.

---

## Final Takeaways

- **Idempotency makes retries harmless.** A request can arrive any number of times, but its real-world effect — the charge, the order, the booking — happens **exactly once**.
- **You don't buy exactly-once *delivery*; you build exactly-once *outcomes*.** Accept at-least-once, make processing idempotent → **effectively-once**. The same idea covers HTTP retries, message queues, and webhooks (both directions — §10.5).
- **Prefer naturally idempotent design first** (absolute `SET`, upserts, conditional writes, `PUT`). Reach for an explicit `Idempotency-Key` only when the side effect is non-idempotent *and* must happen once — and skip it entirely for reads and harmless repeats (§2.7).
- **The client owns the key; the server enforces it.** One key per user intent, reused on every retry; the server dedups atomically (`SET NX` / `UNIQUE` constraint) so only the first of racing copies wins.
- **Store the full response and replay it verbatim** — same body, same status — so a retry is indistinguishable from the original.
- **Two systems can disagree.** The store (Redis) and the DB don't commit together; a `UNIQUE(idempotency_key)` on the real table is the ultimate backstop, and **reconciliation** catches money-moving flows that touch an external side effect.
- **Idempotency is correctness, not security.** The key answers *"did I already do this?"*, never *"is this person allowed?"* — yet a webhook endpoint must still verify signatures before trusting an event.
- **Give keys a TTL** matched to the realistic retry window (24–72h) — long enough to catch slow retries, short enough not to hoard state.
