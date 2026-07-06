# URL Shortener — System Design

> A classic "second design" that shows breadth. Core challenges: **generate short unique IDs at scale**, **redirect with very low latency**, and survive a **read-heavy** load (reads ≫ writes).

---

## Contents

- [1. Requirements](#1-requirements)
- [2. Capacity Estimation](#2-capacity-estimation)
- [3. The Short Code — Encoding](#3-the-short-code--encoding)
- [4. Distributed ID Generation](#4-distributed-id-generation)
- [5. Data Model](#5-data-model)
- [6. Architecture](#6-architecture)
- [7. Core Flows](#7-core-flows)
- [8. Scaling & Reliability](#8-scaling--reliability)
- [9. Edge Cases](#9-edge-cases)
- [10. Interview Cheat Sheet](#10-interview-cheat-sheet)
- [11. Final Takeaways](#11-final-takeaways)

---

## 1. Requirements

### Functional
- Create a short URL from a long URL (`POST /shorten`).
- Redirect short URL → long URL (`GET /{code}` → `301/302`).
- Optional: custom alias, expiry, click analytics.

### Non-functional
- **Read-heavy:** redirects ≫ creations (~100:1).
- **Low latency** redirects (< 50ms).
- **High availability** (a dead link breaks everyone's links).
- **Unique, short, non-guessable-ish** codes.

---

## 2. Capacity Estimation

```
Assume: 100M new URLs / month
Writes:  100M / (30*86400)        ≈ 40 writes/sec
Reads:   100:1 ratio              ≈ 4,000 reads/sec
Storage: 100M/mo * 12 * 5yr ≈ 6B URLs
         ~500 bytes/row → ~3 TB
Keyspace: base62, length 7 → 62^7 ≈ 3.5 trillion → plenty
```

> **Key insight:** it's a **read-heavy, low-write** system → cache + read replicas dominate the design.

---

## 3. The Short Code — Encoding

Use **base62** (`[a-zA-Z0-9]`): short, URL-safe.

```
62^6 ≈ 56 billion
62^7 ≈ 3.5 trillion     ← length 7 is plenty
```

### Approaches to generate the code

| Approach | How | Pros | Cons |
| --- | --- | --- | --- |
| **Auto-increment ID → base62** | DB sequence, encode the integer | simple, guaranteed unique, short | sequential = **guessable / enumerable**; single counter is a bottleneck |
| **Random + check** | random base62, check collision | non-sequential | collision checks (DB hit) as it fills |
| **Hash (MD5/SHA) + truncate** | hash long URL, take first 7 | dedups identical URLs | truncation **collisions** to handle |
| **Key Generation Service (KGS)** | pre-generate unique keys, hand them out | no collision at write time, fast | needs a key store + concurrency control |

> **Common pick:** distributed **counter → base62** (via a range allocator), or a **KGS**. Add randomness/salting if you don't want enumerable URLs.

---

## 4. Distributed ID Generation

A single auto-increment counter is a SPOF/bottleneck. Options:

- **DB ticket server / range allocation** — each app node grabs a **block** of IDs (e.g. 1000) and serves locally; refills when exhausted.
- **Snowflake-style IDs** — `timestamp | machineId | sequence` → unique, roughly time-ordered, no coordination.
- **Multiple counters** — each node starts at an offset and steps by N.

> **Range allocation** is the simplest scalable answer: minimal coordination, no per-write contention.

---

## 5. Data Model

```sql
CREATE TABLE url (
    code        VARCHAR(10) PRIMARY KEY,   -- base62 short code
    long_url    TEXT NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    expires_at  TIMESTAMP,                 -- optional
    user_id     BIGINT                     -- optional
);
```

- Store is essentially a **huge key-value map** (`code → long_url`) → a **KV store (DynamoDB/Cassandra)** fits naturally, or sharded SQL.
- **Shard by `code`** (hash / consistent hashing) — reads and writes are single-key lookups.

---

## 6. Architecture

```
                 ┌─────────┐
Client ────────► │   CDN   │ (optional, cache redirects at edge)
                 └────┬────┘
                      ▼
                ┌───────────┐
                │API Gateway│ (rate limiting, auth)
                └─────┬─────┘
            ┌─────────┴──────────┐
            ▼                    ▼
     ┌────────────┐       ┌────────────┐
     │ Write Svc  │       │ Read Svc    │  (redirects)
     │ (/shorten) │       │ (/{code})   │
     └─────┬──────┘       └─────┬──────┘
           ▼                    ▼
     ┌──────────┐         ┌──────────┐
     │ ID/KGS   │         │  Redis   │ ◄── cache (hot codes)
     └────┬─────┘         └────┬─────┘
          ▼                    ▼  (miss)
       ┌──────────────────────────┐
       │   DB / KV store (sharded) │  + read replicas
       └──────────────────────────┘
                  │ click events
                  ▼
                Kafka → Analytics
```

---

## 7. Core Flows

### Create (write)

```
POST /shorten { longUrl }
1. (optional) dedup: seen this longUrl before? return existing code
2. get a unique id (range allocator / KGS)
3. code = base62(id)
4. store code → longUrl in DB
5. return short URL
```

### Redirect (read — the hot path)

```
GET /{code}
1. cache.get(code)              # Redis, cache-aside
2. on miss → DB.get(code) → cache.set(code, longUrl, ttl)
3. return 301/302 → longUrl
4. async: emit click event → Kafka (don't block redirect)
```

> **301 vs 302:** `301` (permanent) is cached by browsers → fewer hits but **no analytics** on repeat visits. `302` (temporary) keeps traffic flowing through you → better for **click tracking**. Pick based on whether you need analytics.

---

## 8. Scaling & Reliability

- **Cache-aside + CDN** — the redirect path is read-heavy; most reads should hit cache, never the DB.
- **Read replicas** + **sharding by code** for the DB.
- **Async analytics** via Kafka — never let click logging slow the redirect.
- **High availability** — replicate; a redirect outage breaks every link.
- **Rate limiting** at the gateway to stop abuse / spam link creation.

---

## 9. Edge Cases

| Case | Handling |
| --- | --- |
| Duplicate long URL | optional dedup (hash index on long_url) → return existing code |
| Custom alias collision | check uniqueness; reject if taken |
| Expired link | check `expires_at` → return 404/410 |
| Malicious URLs | scan/blocklist before storing |
| Code exhaustion | length 7 base62 ≈ 3.5T — effectively never; extend length if needed |
| Hot link (viral) | cache + CDN absorb it |

---

## 10. Interview Cheat Sheet

> **"How do you generate the short code?"**
>
> "Base62 encode a unique ID. Get the ID from a **range allocator / KGS** to avoid a single-counter bottleneck. Add salting if URLs shouldn't be enumerable. Alternatively hash+truncate with collision handling."

> **"How do you make redirects fast?"**
>
> "It's read-heavy, so **cache-aside with Redis (+ CDN)**, read replicas, and shard by code. Redirects rarely touch the DB."

> **"301 or 302?"**
>
> "302 if I want click analytics (traffic keeps flowing through me); 301 if I want fewer hits and don't need per-visit tracking."

> **"How do you avoid collisions at scale?"**
>
> "Range-allocated counters or a KGS give collision-free IDs without per-write coordination; for hash-based codes, check-and-retry on collision."

---

## 11. Final Takeaways

- **Read-heavy** → cache + CDN + replicas dominate; writes are tiny.
- **Short code** = base62 of a **uniquely generated ID** (range allocator / KGS / Snowflake).
- **KV store, sharded by code**; analytics async via Kafka.
- **301 vs 302** is an analytics trade-off.
