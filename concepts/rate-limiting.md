# Rate Limiting

> **Goal:** cap how many requests a client can make in a time window — to **prevent abuse, protect backends, ensure fair use, and control cost**. The interview question is usually "compare the algorithms."

> **How to read this doc:** each section has the dense summary first, then a **Plain-English** deep dive (a bouncer-at-a-club analogy, annotated Java, and the exact confusions that come up while learning). Skim the summaries for revision; read the plain-English parts to actually understand.

---

## Contents

- [1. Why Rate Limit?](#1-why-rate-limit)
- [2. The Algorithms](#2-the-algorithms)
- [3. Comparison](#3-comparison)
- [4. Distributed Rate Limiting](#4-distributed-rate-limiting)
- [5. Responses & Headers](#5-responses--headers)
- [6. Interview Cheat Sheet](#6-interview-cheat-sheet)
- [7. Final Takeaways](#7-final-takeaways)

---

## 1. Why Rate Limit?

- **Prevent abuse / DoS** — stop a client hammering the API.
- **Protect backends** — shed load before it melts the DB.
- **Fair usage** — one tenant can't starve others.
- **Cost control** — cap expensive operations.

> Usually enforced at the **API Gateway / edge**, keyed by `user_id`, `API key`, or `IP`.

### Plain-English: the bouncer at the club door

**Analogy used throughout this doc: a bouncer standing at a club door.**

Your API is the club. Requests are people trying to get in. Without a bouncer, a mob rushes the door all at once, the club gets dangerously overcrowded, and it becomes miserable (or unsafe) for everyone inside. The **bouncer = the rate limiter**: they stand at the entrance and enforce a rule like *"I'm letting at most 100 people in per minute."* Everyone beyond that gets told "sorry, come back later."

Why a club needs a bouncer maps one-to-one onto why an API needs a rate limiter:

- **Prevent abuse / DoS** — one rowdy person can't shove 10,000 friends through the door and trample everyone.
- **Protect backends** — the club (your database) has a fire-code capacity; the bouncer stops you before you exceed it and everything melts.
- **Fair usage** — no single big group can pack the whole club and lock everyone else out.
- **Cost control** — every guest costs the club money (drinks, staff); capping the count caps the bill.

#### Q: Where does the bouncer stand — at each room, or the front door?

At the **front door** — the **API Gateway / edge**, before requests reach your actual servers. You want to turn people away *before* they wander into the building and consume resources. Checking at the entrance is cheap; checking after they've already ordered food is not.

#### Q: What does "keyed by user_id / API key / IP" mean?

It's *who the bouncer counts you as*. The limit is "100/min **per person**," not "100/min for the whole planet" — otherwise one busy user would use up everyone's allowance. So the bouncer needs a way to recognize each guest:

- **`user_id`** — logged-in users (most accurate: it's really *you*).
- **`API key`** — machine/developer clients calling your API.
- **`IP address`** — anonymous traffic where you don't know who they are yet.

The chosen field becomes the **key** for that person's private counter. This is why every algorithm below starts with a `key` like `user:123` — see the per-user keys discussion in §4.

---

## 2. The Algorithms

### 1️⃣ Fixed Window Counter

Count requests per fixed window (e.g. per minute); reset at the boundary.

```
key = user:123:minute:1010
INCR key                       # atomic
if count > limit: reject
EXPIRE key 60
```

| Pros | Cons |
| --- | --- |
| Dead simple, low memory | **Boundary burst**: 2× limit possible across the edge of two windows |

> **Boundary problem:** limit=100/min. 100 requests at 00:59 + 100 at 01:00 = 200 in ~1s.

### Plain-English: the bouncer with a clock that resets every minute

The bouncer has one clicker-counter and a rule: *"I count guests, and at the top of every minute I reset the counter to 0."* Simple. If you're guest #101 before the minute is up, you're rejected; once the clock ticks over to the next minute, the count is wiped and 100 new people can come in.

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

#### Q: What exactly is the "boundary burst" bug, in bouncer terms?

The reset is **brutal and instantaneous**. Picture limit = 100/min:

```
10:00:59  →  100 guests rush in    (counter hits 100, minute nearly over)
10:01:00  →  counter RESETS to 0
10:01:00  →  100 more guests rush in immediately (counter hits 100 again)
```

In about **one second** you let in **200 people** — double the limit — because the two bursts sit on either side of the reset line. The bouncer obeyed "100 per minute" for each minute individually, but the *rolling* one-second view got slammed. The next few algorithms exist mostly to fix this.

---

### 2️⃣ Sliding Window Log

Store a **timestamp per request**; count those within the last window.

```
now = time()
ZREMRANGEBYSCORE key 0 (now - window)   # drop old
ZADD key now now
count = ZCARD key
if count > limit: reject
```

| Pros | Cons |
| --- | --- |
| **Exact**, no boundary burst | **Memory-heavy** (stores every timestamp); costly at scale |

### Plain-English: the bouncer who writes down every entry time

Instead of a clicker that resets, this bouncer keeps a **guest list with exact timestamps**: "Alice 10:00:03, Alice 10:00:41, Alice 10:01:12...". When Alice shows up again, the bouncer:

1. **Crosses out everyone older than one minute ago** (they no longer count — their window has slid past).
2. **Counts what's left on the list.**
3. If that's already at the limit, reject; otherwise add Alice's new timestamp.

Because the "last 60 seconds" is measured **fresh from right now** (not from a fixed clock), there's no reset line to game — so **no boundary burst**. It's the exact, honest answer.

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

Because it stores **one entry per request**, not one number. If a user makes 10,000 requests a minute, the bouncer's list has 10,000 lines for that one user. Multiply by millions of users and the guest list becomes enormous. Fixed/sliding **counters** store just a couple of integers per user regardless of traffic — that's the trade-off: this one is *exact* but *expensive*.

---

### 3️⃣ Sliding Window Counter (the practical favorite)

Approximate the sliding window by **weighting the previous window's count**.

```
rate = curr_count
     + prev_count * (overlap fraction of previous window)
if rate > limit: reject
```

| Pros | Cons |
| --- | --- |
| Smooths the boundary burst, low memory | slight approximation |

> Best **balance** of accuracy and cost → common in production (e.g. Cloudflare-style).

### Plain-English: the bouncer who "blends" the last two minutes

This is the clever compromise. Keep the cheap fixed-window counters (just two numbers: this minute's count and last minute's count), but **fake a sliding window** by mixing them based on how far into the current minute you are.

Bouncer's reasoning at 10:01:15 (15 seconds = 25% into the current minute), limit = 100:

> "The current minute has counted some requests already. And I'm only 25% into this minute, so **75% of the previous minute still overlaps** my rolling 60-second view. Estimate = current count + 75% × previous count."

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

```
refill: tokens = min(capacity, tokens + rate * elapsed)
on request:
    if tokens >= 1: tokens -= 1; allow
    else: reject
```

| Pros | Cons |
| --- | --- |
| **Allows bursts** up to capacity, then steady rate; memory-light (2 fields) | needs careful refill math |

> **Mental model:** you save up tokens when idle and can spend a burst later. Great for APIs that should tolerate short spikes.

### Plain-English: the arcade with a refilling stack of tokens

Forget counting for a second. Picture an arcade. Each guest has a **cup that holds up to 10 tokens** (`capacity`). A machine drips **1 fresh token into the cup every 6 seconds** (`refill rate` = 10/min). Every ride (request) **costs 1 token**. No token in the cup? You wait.

- **Idle for a while?** Your cup fills back up to 10. Now you can take **10 rides back-to-back** — that's an allowed **burst**.
- **Cup empty?** You're throttled down to "1 ride per 6 seconds" — the steady refill rate.

So the token bucket says *"bursts are fine, up to a saved-up cap, but your long-run average is capped."* That flexibility (tolerate short spikes, cap the average) is exactly what most APIs want, which is why it's the default.

```java
class TokenBucket {
    final double capacity;      // max tokens the cup holds (max burst size)
    final double refillPerMs;   // tokens added per millisecond
    double tokens;              // tokens currently in the cup
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

Notice we don't run a background timer dripping tokens — we **compute the refill on demand** ("how much time passed × drip rate") the moment a request arrives. That's why it only needs **two fields** (`tokens`, `lastRefillMs`) and is memory-light.

#### Q: How is this different from a fixed-window counter?

A counter asks *"how many so far this minute?"* and resets hard on the boundary. A token bucket has **no boundary** — tokens refill continuously and **carry over**. That's what lets it absorb a burst smoothly right after an idle period, instead of the jarring reset-and-slam behavior that causes the boundary-burst bug.

---

### 5️⃣ Leaky Bucket (queue / shaper)

Requests enter a queue (bucket); processed (leak) at a **constant rate**; overflow → reject.

```
queue requests; process at fixed rate R
if queue full: reject
```

| Pros | Cons |
| --- | --- |
| **Smooths output** to a constant rate (traffic shaping) | no bursts; adds queueing latency |

> **Token vs Leaky:** Token bucket allows **bursty** output (up to capacity); leaky bucket forces a **smooth, constant** output.

### Plain-English: the bucket with a hole in the bottom

Picture an actual bucket with a small **hole in the bottom**. Requests are water poured in from the top; the hole lets water out at a **fixed, constant drip** (say 10/sec, no matter how fast you pour). If you pour too fast, the bucket fills up; once it **overflows** (queue full), the excess spills on the floor (rejected).

The key difference from token bucket: what comes **out** is always a steady trickle. Even if 1,000 requests slam in at once, they leave the bucket at exactly 10/sec. This is **traffic shaping** — smoothing a spiky input into a calm, predictable output — useful when the thing downstream (a legacy service, a payment processor) can only handle a steady pace.

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

### Plain-English: picking one without overthinking it

The whole table collapses into a few instincts. Two axes matter: **do you want to allow bursts?** and **how much memory / accuracy can you afford?**

- **Just need something simple and don't care about the boundary edge case?** → Fixed window.
- **Need it perfectly exact and money is no object on memory?** → Sliding log.
- **Want it accurate *and* cheap?** → Sliding window counter.
- **Want to tolerate short spikes (typical API)?** → Token bucket. ← default choice.
- **Need to force a calm, even output to protect something downstream?** → Leaky bucket.

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

```lua
-- token bucket in one atomic Redis Lua call (sketch)
-- refill based on elapsed time, then try to consume 1 token
```

### Plain-English: many bouncers, one shared guest book

Real systems don't have one bouncer — they have **10 bouncers at 10 doors** (many gateway instances behind a load balancer). The problem: if each bouncer keeps their **own** private counter in their head, a user who spreads 10 requests across 10 bouncers looks like "1 request" to each. Everyone waves them through → the real limit is blown 10×.

Fix: all bouncers write into **one shared guest book** that lives in a central place — **Redis**. Now "how many has this user made?" has a single true answer no matter which door you walked up to.

```
[gateway 1] ┐
[gateway 2] ┼──►  Redis  (the one shared counter per user)
[gateway 3] ┘
```

#### Q: What's the race condition, and why does Redis fix it?

If two bouncers do "**read** the count, **decide**, **write** the new count" separately, they can both read `99`, both think "room for one more," and both write `100` — letting **two** people in when only one slot was left. That read-modify-write gap is the race.

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

Each user gets their **own row in the shared guest book**, addressed by a key built from their identity — e.g. `rl:user:123`, `rl:apikey:abc`, or `rl:ip:49.36.x.x`. The bouncer never mixes two users' tallies. That's why every snippet in §2 keys by the user: the limit is *per person*, so the counter must be *per person* too. (If you also want per-endpoint limits, put that in the key: `rl:user:123:/checkout`.)

#### Q: Isn't calling Redis on every request slow?

It adds a **network hop** (~sub-millisecond, but not free). The trade-off:

- **Central Redis** = accurate (one true count) but every request pays the hop.
- **Local in-memory** = blazing fast but each instance only sees its own slice → approximate, and lets bursts leak through.
- **Hybrid (common at big scale):** each instance keeps a **local budget** and pre-checks against it, then **periodically syncs** with Redis to reconcile. You accept a little inaccuracy for a lot of speed.

---

## 5. Responses & Headers

- Reject with **HTTP 429 Too Many Requests**.
- Include headers: `Retry-After`, `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`.
- Prefer **failing fast** over queuing (unless you're shaping traffic with a leaky bucket).

### Plain-English: what the bouncer *says* when they turn you away

A good bouncer doesn't just slam the door — they tell you *why* and *when to come back*. That's what these headers do. Instead of a vague error, the client gets a polite, machine-readable "not now, try again in 30 seconds," so a well-behaved client can back off gracefully instead of hammering harder.

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
