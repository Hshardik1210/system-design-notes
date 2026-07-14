# Rate Limiting

> **Goal:** cap how many requests a client can make in a time window — to **prevent abuse, protect backends, ensure fair use, and control cost**. The interview question is usually "compare the algorithms."

> **How to read this doc:** each section has the dense summary first, then a **deep dive** (annotated Java, and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand.

---

## Contents

- [1. Why Rate Limit?](#1-why-rate-limit)
- [2. The Algorithms](#2-the-algorithms)
- [3. Comparison](#3-comparison)
- [4. Distributed Rate Limiting](#4-distributed-rate-limiting)
- [Fail-open vs Fail-closed](#fail-open-vs-fail-closed)
- [Limits Hierarchy and Tiers](#limits-hierarchy-and-tiers)
- [Adaptive and Dynamic Rate Limiting](#adaptive-and-dynamic-rate-limiting)
- [5. Responses & Headers](#5-responses--headers)
- [Common Mistakes](#common-mistakes)
- [6. Interview Cheat Sheet](#6-interview-cheat-sheet)
- [7. Final Takeaways](#7-final-takeaways)

---

## 1. Why Rate Limit?

- **Prevent abuse / DoS** — stop a client hammering the API.
- **Protect backends** — shed load before it melts the DB.
- **Fair usage** — one tenant can't starve others.
- **Cost control** — cap expensive operations.

> Usually enforced at the **API Gateway / edge**, keyed by `user_id`, `API key`, or `IP`.

### Why rate limit, in detail

A rate limiter sits at the entry point to your API and enforces a rule like *"at most 100 requests per client per minute."* Requests beyond that are rejected until the window resets. The four motivations expand as:

- **Prevent abuse / DoS** — a single client can't flood the API with traffic and starve everyone else.
- **Protect backends** — downstream systems (the database) have finite capacity; the limiter caps load before it exceeds that and everything melts.
- **Fair usage** — no single client can consume all the capacity and lock others out.
- **Cost control** — every request costs money (compute, downstream calls); capping the count caps the bill.

#### Q: Where is rate limiting enforced?

At the **API Gateway / edge**, before requests reach your actual servers. You want to reject excess requests *before* they consume backend resources. Checking at the entry point is cheap; checking after the request has already done work downstream is not.

#### Q: What does "keyed by user_id / API key / IP" mean?

It's *who the limit counts against*. The limit is "100/min **per client**," not "100/min globally" — otherwise one busy client would use up everyone's allowance. So you need a field to identify each client:

- **`user_id`** — logged-in users (most accurate: it identifies the actual account).
- **`API key`** — machine/developer clients calling your API.
- **`IP address`** — anonymous traffic where the client isn't yet identified.

The chosen field becomes the **key** for that client's private counter. This is why every algorithm below starts with a `key` like `user:123` — see the per-user keys discussion in §4.

#### Q: Rate limiting vs throttling vs backpressure — same thing?

Related, but distinct:

- **Rate limiting** — a hard cap you set: *"at most N per window."* Over the cap → **reject** (429). It's an explicit policy, usually at the edge.
- **Throttling** — *slowing* a client rather than cutting it off: delaying or queuing requests, or shrinking the allowed rate so they proceed more slowly. Often used loosely as a synonym for rate limiting; the nuance is "slow down" vs "reject."
- **Backpressure** — a signal that flows **upstream from an overloaded component**: a full bounded queue, TCP flow control, or a lagging consumer telling its callers *"I'm saturated, send less."* Rate limiting is a limit *you impose*; backpressure is the system *reacting* to real load and pushing that pressure back toward the source.

---

## 2. The Algorithms

### 1️⃣ Fixed Window Counter

Count requests per fixed window (e.g. per minute); reset at the boundary.

| Pros | Cons |
| --- | --- |
| Dead simple, low memory | **Boundary burst**: 2× limit possible across the edge of two windows |

> **Boundary problem:** limit=100/min. 100 requests at 00:59 + 100 at 01:00 = 200 in ~1s.

> ⚠️ **This is the motivator for sliding windows.** Fixed window honors "100/min" for each minute *individually*, but a rolling one-second view straddling the reset can see **2× the limit**. If bursty abuse matters, that's the reason to reach for a sliding window (or token bucket).

### A counter that resets every minute

Keep one counter per client with a rule: *"count requests, and at the top of every minute reset the counter to 0."* Simple. Request #101 within a minute is rejected; once the clock ticks over to the next minute, the count is wiped and 100 more requests are allowed.

- Each **fixed window** is a whole minute (`10:10:00`–`10:10:59`), and the counter belongs to that minute only.
- The `key` bakes the minute into it (`user:123:minute:1010`) so each new minute automatically gets a fresh counter, and `EXPIRE key 60` throws away old ones so memory stays tiny.

```java
// One counter per (user, minute). Resets simply by using a new key each minute.
boolean allow(String userId, int limit) {
    long minute = System.currentTimeMillis() / 60_000;   // which minute we're in
    String key = "rl:" + userId + ":" + minute;          // new key every minute = auto-reset

    long count = redis.incr(key);   // atomic +1, returns the new value
    if (count == 1) {
        redis.expire(key, 60);      // first hit this minute → set it to vanish in 60s
    }
    return count <= limit;          // 101st request in this minute → false (reject)
}
```

#### Q: What exactly is the "boundary burst" bug?

The reset is **abrupt and instantaneous**. Take limit = 100/min:

```
10:00:59  →  100 requests arrive   (counter hits 100, minute nearly over)
10:01:00  →  counter RESETS to 0
10:01:00  →  100 more requests arrive immediately (counter hits 100 again)
```

In about **one second** you allowed **200 requests** — double the limit — because the two bursts sit on either side of the reset line. The counter obeyed "100 per minute" for each minute individually, but the *rolling* one-second view got slammed. The next few algorithms exist mostly to fix this.

---

### 2️⃣ Sliding Window Log

Store a **timestamp per request**; count those within the last window.

| Pros | Cons |
| --- | --- |
| **Exact**, no boundary burst | **Memory-heavy** (stores every timestamp); costly at scale |

### Logging each request timestamp

Instead of a counter that resets, store an exact **timestamp for every request** from a client. When a new request arrives, the limiter:

1. **Drops every timestamp older than one minute ago** (those requests have slid out of the window).
2. **Counts what's left.**
3. If that's already at the limit, reject; otherwise record the new request's timestamp.

Because the "last 60 seconds" is measured **fresh from right now** (not from a fixed clock), there's no reset line to game — so **no boundary burst**. It's the exact answer.

```java
// Redis sorted set: members = request timestamps, score = same timestamp.
boolean allow(String userId, int limit, long windowMs) {
    String key = "rl:" + userId;
    long now = System.currentTimeMillis();

    redis.zremrangeByScore(key, 0, now - windowMs);  // 1. drop entries older than the window
    long count = redis.zcard(key);                   // 2. how many remain in the last 60s

    if (count >= limit) return false;                // 3. already full → reject

    redis.zadd(key, now, now + ":" + Math.random()); // record this request's timestamp
    redis.expire(key, windowMs / 1000);              // let idle keys expire
    return true;
}
```

#### Q: Why is this "memory-heavy" when the counter version isn't?

Because it stores **one entry per request**, not one number. If a user makes 10,000 requests a minute, the store holds 10,000 timestamps for that one user. Multiply by millions of users and it becomes enormous. Fixed/sliding **counters** store just a couple of integers per user regardless of traffic — that's the trade-off: this one is *exact* but *expensive*.

---

### 3️⃣ Sliding Window Counter (the practical favorite)

Approximate the sliding window by **weighting the previous window's count**.

| Pros | Cons |
| --- | --- |
| Smooths the boundary burst, low memory | slight approximation |

> Best **balance** of accuracy and cost → common in production (e.g. Cloudflare-style).

### Blending the last two windows

This is the clever compromise. Keep the cheap fixed-window counters (just two numbers: this minute's count and last minute's count), but **approximate a sliding window** by mixing them based on how far into the current minute you are.

Reasoning at 10:01:15 (15 seconds = 25% into the current minute), limit = 100:

> "The current minute has counted some requests already. And we're only 25% into this minute, so **75% of the previous minute still overlaps** the rolling 60-second view. Estimate = current count + 75% × previous count."

```java
boolean allow(String userId, int limit, long windowMs) {
    long now = System.currentTimeMillis();
    long currWindow = now / windowMs;
    double positionInWindow = (now % windowMs) / (double) windowMs; // 0.0 → 1.0

    long curr = redis.get("rl:" + userId + ":" + currWindow);       // this window's count
    long prev = redis.get("rl:" + userId + ":" + (currWindow - 1));  // previous window's count

    // weight the previous window by how much of it still overlaps the rolling window
    double estimated = curr + prev * (1.0 - positionInWindow);

    if (estimated >= limit) return false;
    redis.incr("rl:" + userId + ":" + currWindow);
    return true;
}
```

#### Q: Why is it "approximate," and is that OK?

It **assumes requests were spread evenly** across the previous minute, so it estimates rather than counting exact timestamps. If last minute's traffic was actually all bunched at the very start, the weighting slightly over- or under-shoots. But the error is small, it uses **two integers instead of a huge timestamp list**, and it **kills the boundary burst** — so for almost everyone it's the sweet spot (this is roughly what Cloudflare uses).

---

### 4️⃣ Token Bucket (most popular)

A bucket holds up to `capacity` tokens; tokens **refill at a fixed rate**. Each request consumes one token; empty bucket → reject.

| Pros | Cons |
| --- | --- |
| **Allows bursts** up to capacity, then steady rate; memory-light (2 fields) | needs careful refill math |

> **Mental model:** you save up tokens when idle and can spend a burst later. Great for APIs that should tolerate short spikes.

### The token bucket, concretely

Each client has a **bucket that holds up to `capacity` tokens** (say 10). Tokens refill at a fixed rate — **1 token every 6 seconds** (`refill rate` = 10/min). Every request **costs 1 token**. No token available? The request is rejected.

- **Idle for a while?** The bucket refills back up to 10. Now the client can make **10 requests back-to-back** — an allowed **burst**.
- **Bucket empty?** The client is throttled to "1 request per 6 seconds" — the steady refill rate.

So the token bucket says *"bursts are fine, up to a saved-up cap, but the long-run average is capped."* That flexibility (tolerate short spikes, cap the average) is exactly what most APIs want, which is why it's the default.

```java
class TokenBucket {
    final double capacity;      // max tokens the bucket holds (max burst size)
    final double refillPerMs;   // tokens added per millisecond
    double tokens;              // tokens currently in the bucket
    long   lastRefillMs;        // when we last topped up

    boolean allow() {
        long now = System.currentTimeMillis();

        // 1. REFILL lazily: add tokens for the time elapsed since we last checked,
        //    but never overflow past capacity.
        double elapsed = now - lastRefillMs;
        tokens = Math.min(capacity, tokens + elapsed * refillPerMs);
        lastRefillMs = now;

        // 2. SPEND: if there's a token, take one and allow; else reject.
        if (tokens >= 1) {
            tokens -= 1;
            return true;
        }
        return false;
    }
}
```

Notice we don't run a background timer adding tokens — we **compute the refill on demand** ("time elapsed × refill rate") the moment a request arrives. That's why it only needs **two fields** (`tokens`, `lastRefillMs`) and is memory-light.

#### Q: How is this different from a fixed-window counter?

A counter asks *"how many so far this minute?"* and resets hard on the boundary. A token bucket has **no boundary** — tokens refill continuously and **carry over**. That's what lets it absorb a burst smoothly right after an idle period, instead of the jarring reset-and-slam behavior that causes the boundary-burst bug.

---

### 5️⃣ Leaky Bucket (queue / shaper)

Requests enter a queue (bucket); processed (leak) at a **constant rate**; overflow → reject.

| Pros | Cons |
| --- | --- |
| **Smooths output** to a constant rate (traffic shaping) | no bursts; adds queueing latency |

> **Token vs Leaky:** Token bucket allows **bursty** output (up to capacity); leaky bucket forces a **smooth, constant** output.

### The leaky bucket, concretely

Requests enter a queue (the bucket) and are processed — "leak out" — at a **fixed, constant rate** (say 10/sec, no matter how fast they arrive). If requests arrive faster than that, the queue fills; once it's full, the excess is rejected (overflow).

The key difference from token bucket: the **output** rate is always constant. Even if 1,000 requests arrive at once, they leave at exactly 10/sec. This is **traffic shaping** — smoothing a spiky input into a steady, predictable output — useful when the downstream (a legacy service, a payment processor) can only handle a constant pace.

```java
class LeakyBucket {
    final int    capacity;      // max requests that can wait in the queue
    final double leakPerMs;     // requests processed per ms (the constant drip)
    double water;               // how full the bucket is right now
    long   lastLeakMs;

    boolean allow() {
        long now = System.currentTimeMillis();

        // LEAK: drain water for the time elapsed (constant outflow)
        water = Math.max(0, water - (now - lastLeakMs) * leakPerMs);
        lastLeakMs = now;

        // Room left? add this request (it'll be processed at the steady rate). Else overflow.
        if (water < capacity) {
            water += 1;
            return true;
        }
        return false;   // bucket full → drop
    }
}
```

#### Q: Token and leaky bucket look almost identical in code — what's the real difference?

They are close cousins (both track a level that changes over time), but the **intent is opposite**:

- **Token bucket** meters the **input**: "do you have a token to spend?" It happily lets a **burst** through the instant it arrives, as long as you saved up tokens. Good for *"be responsive, tolerate spikes."*
- **Leaky bucket** meters the **output**: requests leave at a **fixed rate** regardless of how they arrived. It deliberately **refuses to burst** — it queues and smooths. Good for *"protect a fragile downstream that needs an even pace."*

One-liner: **token bucket allows bursts; leaky bucket erases them.**

---

## 3. Comparison

| Algorithm | Bursts? | Accuracy | Memory | Notes |
| --- | --- | --- | --- | --- |
| Fixed window | ❌ (boundary burst bug) | low | tiny | simplest |
| Sliding log | controlled | exact | high | precise but costly |
| Sliding counter | controlled | good (approx) | low | **best balance** |
| **Token bucket** | ✅ allowed | good | tiny | **most common for APIs** |
| Leaky bucket | ❌ (smoothed) | good | low/med | traffic shaping |

### Picking one without overthinking it

The whole table collapses into a few instincts. Two axes matter: **do you want to allow bursts?** and **how much memory / accuracy can you afford?**

- **Just need something simple and don't care about the boundary edge case?** → Fixed window.
- **Need it perfectly exact and money is no object on memory?** → Sliding log.
- **Want it accurate *and* cheap?** → Sliding window counter.
- **Want to tolerate short spikes (typical API)?** → Token bucket. ← default choice.
- **Need to force a calm, even output to protect something downstream?** → Leaky bucket.

> 💡 **Default choice:** **token bucket at the API gateway, keyed by API key / user_id.** It's memory-light (two fields per client), tolerates the short bursts real clients naturally produce (a page firing 8 API calls on load), and still caps the long-run average. Reach for something else only with a specific reason — sliding-window counter for tighter accuracy, leaky bucket when a fragile downstream needs a smoothed constant rate.

#### Q: Fixed window vs sliding window — what's the actual difference?

They both cap "N per window," but they define *"the window"* differently:

- **Fixed window** uses a **wall clock that resets** — `10:00:00–10:00:59` is one bucket, then it wipes. Cheap, but two bursts on either side of the reset line let you sneak through **2× the limit** (the boundary burst).
- **Sliding window** measures **"the last 60 seconds from right now,"** which is always moving. There's no reset line to exploit, so it's smooth and honest — but you pay for it, either in memory (log) or in a small approximation (counter).

Think: fixed = "reset the clicker every minute" vs sliding = "always look back exactly 60 seconds."

#### Q: Token bucket vs leaky bucket — when do I reach for each?

- **Token bucket** if you want to be **user-friendly**: a client that's been quiet can fire off a quick burst (page load making 8 API calls at once) without being punished, as long as the long-run average is capped.
- **Leaky bucket** if you want to be **protective**: the downstream can only stomach a steady pace, so you deliberately flatten every spike into a constant drip, even if it adds queueing delay.

Rule of thumb: **token bucket = protect *yourself* while staying responsive; leaky bucket = protect a *fragile downstream* by smoothing.**

---

## 4. Distributed Rate Limiting

> Multiple gateway instances must share one counter → use a **centralized store (Redis)**.

- Store counters/tokens in **Redis**; use **atomic** ops (`INCR`, or a **Lua script** for token bucket) to avoid races.
- **Race condition:** read-modify-write across instances → use atomic Redis commands / Lua so the check+decrement is one step.
- **Latency vs accuracy:** a local in-memory limiter is fast but approximate; a central Redis limiter is accurate but adds a hop. Hybrid: local pre-check + periodic sync.

### Sharing counters across instances

Real systems run **many gateway instances** behind a load balancer, not one. The problem: if each instance keeps its **own** private counter, a user who spreads 10 requests across 10 instances looks like "1 request" to each. All get allowed → the real limit is blown 10×.

Fix: all instances read and write **one shared counter** in a central store — **Redis**. Now "how many has this user made?" has a single true answer no matter which instance handled the request.

```
[gateway 1] ┐
[gateway 2] ┼──►  Redis  (the one shared counter per user)
[gateway 3] ┘
```

#### Q: What's the race condition, and why does Redis fix it?

If two instances do "**read** the count, **decide**, **write** the new count" separately, they can both read `99`, both think "room for one more," and both write `100` — allowing **two** requests when only one slot was left. That read-modify-write gap is the race.

Redis fixes it by making the check **one atomic step**:

- `INCR` for a counter is already atomic — the +1 and the read-back happen as a single indivisible operation, so two instances can't both see `99`.
- For **token bucket** (which needs read tokens → refill → maybe decrement → write, several steps), wrap it all in a **Lua script**. Redis runs a Lua script **atomically start-to-finish** — no other instance can interleave in the middle.

```lua
-- Atomic token bucket in Redis. KEYS[1]=bucket, ARGV=capacity, refillRate, now, requested
local tokens   = tonumber(redis.call("HGET", KEYS[1], "tokens") or ARGV[1])
local lastMs   = tonumber(redis.call("HGET", KEYS[1], "ts")     or ARGV[3])
local capacity = tonumber(ARGV[1])
local rate     = tonumber(ARGV[2])   -- tokens per ms
local now      = tonumber(ARGV[3])

-- refill for elapsed time, capped at capacity
tokens = math.min(capacity, tokens + (now - lastMs) * rate)

local allowed = 0
if tokens >= 1 then
  tokens = tokens - 1     -- spend one token
  allowed = 1
end

redis.call("HSET", KEYS[1], "tokens", tokens, "ts", now)
redis.call("PEXPIRE", KEYS[1], 60000)   -- let idle buckets expire
return allowed            -- 1 = allow, 0 = reject
```

#### Q: What are "per-user keys" exactly?

Each user gets their **own entry in the shared store**, addressed by a key built from their identity — e.g. `rl:user:123`, `rl:apikey:abc`, or `rl:ip:49.36.x.x`. Two users' tallies are never mixed. That's why every snippet in §2 keys by the user: the limit is *per client*, so the counter must be *per client* too. (If you also want per-endpoint limits, put that in the key: `rl:user:123:/checkout`.)

#### Q: Isn't calling Redis on every request slow?

It adds a **network hop** (~sub-millisecond, but not free). The trade-off:

- **Central Redis** = accurate (one true count) but every request pays the hop.
- **Local in-memory** = blazing fast but each instance only sees its own slice → approximate, and lets bursts leak through.
- **Hybrid (common at big scale):** each instance keeps a **local budget** and pre-checks against it, then **periodically syncs** with Redis to reconcile. You accept a little inaccuracy for a lot of speed.

---

## Fail-open vs Fail-closed

The limiter depends on Redis. **What happens when Redis is unreachable** — down, timing out, a network blip? Decide this *before* it happens, because the default (whatever your code does on an exception) is a real policy choice.

- **Fail-open** — if the limiter can't reach Redis, **allow the request**. Availability wins: a limiter outage doesn't take down your API. Risk: for the duration there's *no* rate limiting, so you're exposed to abuse and overload.
- **Fail-closed** — if the limiter can't reach Redis, **reject the request** (429/503). Protection wins: unmetered traffic never gets through. Risk: a limiter outage becomes an *API* outage — a dependency failure cascades into user-facing downtime.

#### Q: Which do I pick?

It depends on what the limit is protecting:

- **Fail-open** for general public APIs where availability matters most and the backend can absorb a brief unmetered spike. A rate-limiter blip shouldn't 429 all your paying customers.
- **Fail-closed** when the limit guards something that *must not* be overwhelmed — a fragile downstream, an expensive operation, or an abuse-sensitive endpoint (login, payments). Here letting traffic through unmetered is worse than a short outage.

> 💡 **Common middle ground:** fail-open, but back it with a **local in-memory fallback limiter** so you still enforce a coarse (approximate, per-instance) cap during the Redis outage instead of dropping all limits. Pair it with **tight Redis timeouts** and a **circuit breaker** — a limiter that blocks 2s waiting on Redis is its own outage; fail fast to the fallback.

---

## Limits Hierarchy and Tiers

Real systems don't have one limit — they have **layers**, and a request must clear **all** of them. Check the broadest/cheapest first and stop as soon as one is exceeded.

| Scope | Example limit | Protects against |
| --- | --- | --- |
| **Global** | 1M req/s total | total infra capacity / catastrophic overload |
| **Per-tenant** (org / customer) | 10k req/min per tenant | one customer starving all others (fair use) |
| **Per-endpoint** | 100/min on `/search`, 5/min on `/export` | one expensive route melting shared resources |
| **Per-user** (or API key / IP) | 100/min per user | one user abusing their tenant's share |

The scope is encoded in the key: `rl:global`, `rl:tenant:42`, `rl:tenant:42:/export`, `rl:user:123`. Each is incremented, and the request is rejected the moment **any** one is over.

### Tiered limits (free vs paid)

Limits usually vary by **plan**: free = 60/min, pro = 1,000/min, enterprise = negotiated. Store the limit as an attribute of the plan / API key and look it up per request — don't hard-code it.

Two **independent dimensions** are worth separating:

- **Burst** — how many requests can fire *at once* (the token bucket's `capacity`).
- **Sustained** — the long-run average allowed (the token bucket's `refill rate`).

A plan is naturally expressed as both — e.g. *"burst 100, sustained 20/s."* Token bucket maps straight onto this (`capacity` = burst, `refillPerMs` = sustained), another reason it's the default.

---

## Adaptive and Dynamic Rate Limiting

Static limits are set once and never move. **Adaptive** (dynamic) limiting adjusts the allowed rate at runtime based on the system's actual health — tightening when the backend is stressed, loosening when it has headroom.

Signals you might feed in: backend CPU / latency, error rate, DB connection-pool saturation, or a downstream circuit breaker tripping. When p99 latency climbs past a threshold, the limiter lowers each client's effective rate (or the global cap) to **shed load before things fall over**; when the system recovers, it relaxes again. This is closely related to **load shedding** and AIMD-style congestion control. It's more complex and can be surprising to debug (limits move on their own), so most teams start static and add adaptation only where load is genuinely spiky and the backend fragile.

---

## 5. Responses & Headers

- Reject with **HTTP 429 Too Many Requests**.
- Include headers: `Retry-After`, `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`.
- Prefer **failing fast** over queuing (unless you're shaping traffic with a leaky bucket).

### What the response says when a request is rejected

A good rejection doesn't just fail — it tells the client *why* and *when to retry*. That's what these headers do. Instead of a vague error, the client gets a machine-readable "not now, try again in 30 seconds," so a well-behaved client can back off gracefully instead of retrying harder.

```
HTTP/1.1 429 Too Many Requests
Retry-After: 30                 ← come back in 30 seconds
X-RateLimit-Limit: 100          ← your cap is 100 per window
X-RateLimit-Remaining: 0        ← you have 0 left right now
X-RateLimit-Reset: 1710000060   ← the counter resets at this time
```

- **`429`** is the standard "you're being rate limited" status code (distinct from `503`, which means the server itself is down).
- **`Retry-After`** is the most important one for clients — it tells them exactly how long to wait, so retries are spaced out instead of piling on.

#### Q: Why "fail fast" instead of just making requests wait in a line?

Queuing every excess request means holding open thousands of connections and memory while they wait — which is itself a way to get overwhelmed (the queue becomes the bottleneck). **Failing fast** (instant 429) frees your server immediately and pushes the "waiting" back onto the client. The one exception is when queuing *is* the goal — a **leaky bucket** deliberately queues to smooth traffic — but that's a conscious traffic-shaping choice, not the default.

---

## Common Mistakes

- **Forgetting the TTL on the Redis key.** A fixed-window / counter key without `EXPIRE` lives forever — you leak one key per user per window and Redis memory grows without bound. Always set the expiry (see the `expire` / `PEXPIRE` calls in §2 and §4).
- **Keying by IP behind NAT / a shared proxy.** Thousands of users behind one corporate NAT, mobile carrier, or cloud egress share a single IP — you'll throttle them all together, or let one abuser exhaust the whole IP's budget. Prefer `user_id` / API key when the client is identified; treat per-IP as a coarse fallback for anonymous traffic only.
- **Rate limiting *after* the expensive work.** Checking the limit deep in the request — after auth lookups, DB queries, downstream calls — means rejected requests still burned the resources you were trying to protect. Enforce at the **edge / gateway**, *before* the costly work.

---

## 6. Interview Cheat Sheet

> **"Which algorithm would you use?"**
>
> "**Token bucket** for most APIs — it's memory-light and allows controlled bursts. If I need a smooth constant output, **leaky bucket**. If I want accuracy without storing every request, **sliding window counter**."

> **"What's wrong with fixed window?"**
>
> "The boundary burst — a client can do 2× the limit across the edge of two windows. Sliding window counter fixes it cheaply."

> **"Token bucket vs leaky bucket?"**
>
> "Token bucket allows bursts up to capacity then steady rate; leaky bucket forces a constant output rate (shaping) and adds queueing."

> **"How do you rate limit across many servers?"**
>
> "Centralize counters in Redis with atomic ops / a Lua script to avoid races; key by user/API-key/IP at the gateway, and respond 429 with `Retry-After`."

---

## 7. Final Takeaways

- **Token bucket** = default (bursts + cheap). **Leaky bucket** = smoothing. **Sliding counter** = accurate + cheap. **Fixed window** = simple but bursty. **Sliding log** = exact but heavy.
- Enforce at the **gateway**, keyed by user/API-key/IP.
- **Distributed** → Redis + atomic ops (Lua) to avoid races.
- Respond **429** with `Retry-After` + rate-limit headers.
