# Leaderboard / Ranking — System Design

> **Core challenge:** maintain a **real-time ranking** of millions of players by score — support "top N", "my rank", and "players around me" — with **fast updates and reads**. The heart is a **Redis Sorted Set** and how to scale it (sharding, approximation) at very large scale.

---

## Contents

- [1. Mental Model](#1-mental-model)
- [2. Requirements](#2-requirements)
- [3. The Naive DB Approach (why it fails)](#3-the-naive-db-approach-why-it-fails)
- [4. Redis Sorted Set (the core)](#4-redis-sorted-set-the-core)
- [5. Scaling to Millions](#5-scaling-to-millions)
- [6. Time-Windowed Leaderboards](#6-time-windowed-leaderboards)
- [7. Data Model](#7-data-model)
- [8. API Design](#8-api-design)
- [9. Design Patterns (that can be used)](#9-design-patterns-that-can-be-used)
- [10. Interview Cheat Sheet](#10-interview-cheat-sheet)
- [11. Final Takeaways](#11-final-takeaways)

---

## 1. Mental Model

```
Player scores points → update their score → ranking recomputed implicitly
Queries: top 10, "my rank", "10 players around me", score
```

Two operations dominate: **update score** and **get rank/range** — both must be fast. Redis sorted sets do exactly this in `O(log n)`.

---

## 2. Requirements

**Functional**
- Update a player's score (set/increment).
- Get **top N**; get a player's **rank**; get **players around a player** (neighbors).
- Optionally: time-windowed (daily/weekly/all-time), per-region/game.

**Non-functional**
- **Real-time** updates + reads; scale to **millions/billions** of players; highly available. Eventual consistency usually fine.

---

## 3. The Naive DB Approach (why it fails)

```sql
SELECT COUNT(*)+1 FROM scores WHERE score > (SELECT score FROM scores WHERE user_id=?)   -- my rank
SELECT * FROM scores ORDER BY score DESC LIMIT 10                                          -- top 10
```

- "My rank" = a **COUNT over all higher scores** → O(n) or heavy index work **per query**.
- With frequent updates + millions of rows, this crushes the DB. Ranking is fundamentally an **ordered-set** problem, not a relational one.

---

## 4. Redis Sorted Set (the core)

A **sorted set (ZSET)** keeps members ordered by score with `O(log n)` ops — purpose-built for leaderboards.

```
ZADD   leaderboard <score> <userId>          # add/update score        O(log n)
ZINCRBY leaderboard <delta> <userId>          # increment score
ZREVRANGE leaderboard 0 9 WITHSCORES          # TOP 10                  O(log n + k)
ZREVRANK leaderboard <userId>                 # MY RANK (0-based)       O(log n)
ZREVRANGE leaderboard <rank-5> <rank+5>       # players AROUND me
ZSCORE leaderboard <userId>                   # my score
```

- All the hard queries become single `O(log n)` (or `O(log n + k)`) commands.
- Redis holds the leaderboard in memory; DB is the durable backing store (write-behind).

---

## 5. Scaling to Millions

| Concern | Approach |
| --- | --- |
| **Memory / size** | One ZSET can hold millions; beyond that, **shard** (by region/game/score-band) |
| **Sharding** | Partition into sub-leaderboards; merge top-N across shards for a global view |
| **Exact global rank at huge scale** | Expensive across shards → **approximate rank** (score buckets/histogram) or per-shard rank + estimation |
| **Durability** | DB is source of truth; Redis rebuilt from DB on cold start; AOF/RDB persistence |
| **Hot updates** | `ZINCRBY` is atomic; batch writes; write-behind to DB async |
| **Read scale** | Redis replicas for read-heavy top-N; cache top-N (changes slowly) |

> **Approximate ranking** trick at massive scale: maintain a **histogram of score buckets**; a player's rank ≈ (count in higher buckets) + position within their bucket — avoids exact global ordering across shards.

---

## 6. Time-Windowed Leaderboards

- **Daily/weekly** boards = separate ZSETs keyed by window (`lb:daily:2026-07-07`) with TTL.
- **All-time** = a persistent ZSET.
- Increment writes go to all relevant windows; expire old windows automatically.

---

## 7. Data Model

```sql
-- Durable backing store (source of truth)
CREATE TABLE scores ( user_id BIGINT PRIMARY KEY, score BIGINT NOT NULL, updated_at TIMESTAMP );
CREATE TABLE score_events ( event_id BIGINT PRIMARY KEY, user_id BIGINT, delta BIGINT, at TIMESTAMP );  -- audit

-- Serving layer (Redis):
--   ZSET leaderboard:all         member=userId score=score
--   ZSET leaderboard:daily:{date}  (TTL)
--   Cache: top-N list (short TTL)
```

> **Stores to consider:** scores (durable), score_events (audit), Redis ZSETs per window/region, cached top-N. Redis ZSET is the serving structure; DB is truth.

---

## 8. API Design

```
POST /v1/scores            { userId, delta }          # ZINCRBY
GET  /v1/leaderboard/top?n=10
GET  /v1/leaderboard/rank?userId=            → { rank, score }
GET  /v1/leaderboard/around?userId=&range=5
```

---

## 9. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Sorted Set (skip list)** | Core ranking structure | O(log n) rank/range |
| **Write-Behind (write-back) cache** | Redis serves; async persist to DB | Fast writes + durability |
| **Strategy** | Ranking scope (global/region/time), exact vs approximate rank | Swap approach |
| **Sharding** | Split ZSET by region/score-band | Scale beyond one node |
| **CQRS / Materialized View** | Redis serving view vs DB truth | Fast reads |
| **Observer / Pub-Sub** | Score events → update boards, notify | Decouple |
| **Cache-Aside** | Cached top-N | Read performance |
| **Producer-Consumer** | Async score-event processing | Absorb bursts |

---

## 10. Interview Cheat Sheet

> **"How do you get 'my rank' fast?"**
> "A **Redis sorted set** — `ZREVRANK` gives rank in `O(log n)`, `ZREVRANGE` gives top-N and 'players around me'. A relational `COUNT(*) WHERE score > mine` is O(n) per query and doesn't scale."

> **"How is it durable?"**
> "Redis is the serving layer with a **write-behind** to the DB (source of truth); `ZINCRBY` is atomic. On cold start, rebuild the ZSET from the DB; Redis persistence (AOF/RDB) helps too."

> **"How do you scale to hundreds of millions of players?"**
> "Shard leaderboards by region/game/score-band and merge top-N; for exact global rank, use **approximate ranking** via score-bucket histograms since exact cross-shard ranking is expensive. Cache top-N (it changes slowly) and use read replicas."

> **"Daily vs all-time boards?"**
> "Separate ZSETs per time window with TTL; writes increment all active windows."

---

## 11. Final Takeaways

- Ranking is an **ordered-set** problem → **Redis sorted set** (`ZADD`/`ZINCRBY`/`ZREVRANK`/`ZREVRANGE`), all `O(log n)`.
- The relational `COUNT` approach is O(n) per query — doesn't scale.
- **Redis serves, DB is truth** (write-behind); rebuild ZSET on cold start.
- **Shard** by region/score-band + **approximate rank** (bucket histogram) at massive scale; cache top-N.
- **Time-windowed** boards = per-window ZSETs with TTL.
- Patterns: Sorted Set, Write-Behind, Strategy, Sharding, CQRS/Materialized View, Cache-Aside.

### Related notes

- [Caching Strategies](../concepts/caching-strategies.md) · [Consistent Hashing](../concepts/consistent-hashing.md) · [Distributed Cache — System Design](distributed-cache-system-design.md)
