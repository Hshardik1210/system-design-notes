# Rate Limiting

> **Goal:** cap how many requests a client can make in a time window — to **prevent abuse, protect backends, ensure fair use, and control cost**. The interview question is usually "compare the algorithms."

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

---

## 3. Comparison

| Algorithm | Bursts? | Accuracy | Memory | Notes |
| --- | --- | --- | --- | --- |
| Fixed window | ❌ (boundary burst bug) | low | tiny | simplest |
| Sliding log | controlled | exact | high | precise but costly |
| Sliding counter | controlled | good (approx) | low | **best balance** |
| **Token bucket** | ✅ allowed | good | tiny | **most common for APIs** |
| Leaky bucket | ❌ (smoothed) | good | low/med | traffic shaping |

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

---

## 5. Responses & Headers

- Reject with **HTTP 429 Too Many Requests**.
- Include headers: `Retry-After`, `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`.
- Prefer **failing fast** over queuing (unless you're shaping traffic with a leaky bucket).

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
