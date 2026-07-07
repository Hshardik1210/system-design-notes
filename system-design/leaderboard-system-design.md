# Leaderboard / Ranking — System Design

> **Core challenge:** maintain a **real-time ranking** of millions of players by score — support "top N", "my rank", and "players around me" — with **fast updates and reads**. The heart is a **Redis Sorted Set** (skip list) and how to scale it (sharding + approximate rank) at very large scale.

---

## Contents

- [1. Mental Model](#1-mental-model)
- [2. Requirements](#2-requirements)
- [3. Capacity Estimation](#3-capacity-estimation)
- [4. Architecture](#4-architecture)
- [5. The Naive DB Approach (why it fails)](#5-the-naive-db-approach-why-it-fails)
- [6. Redis Sorted Set (the core)](#6-redis-sorted-set-the-core)
- [7. Write Path & Durability](#7-write-path--durability)
- [8. Scaling to Millions (sharding + approximate rank)](#8-scaling-to-millions-sharding--approximate-rank)
- [9. Time-Windowed Leaderboards](#9-time-windowed-leaderboards)
- [10. Data Model](#10-data-model)
- [11. API Design](#11-api-design)
- [12. Sequences](#12-sequences)
- [13. Consistency & Edge Cases](#13-consistency--edge-cases)
- [14. Design Patterns (that can be used)](#14-design-patterns-that-can-be-used)
- [15. Interview Cheat Sheet](#15-interview-cheat-sheet)
- [16. Final Takeaways](#16-final-takeaways)

---

## 1. Mental Model

```
Player scores points → update their score → ranking recomputed implicitly
Queries: top 10, "my rank", "10 players around me", "my score"
```

Two operations dominate: **update score** and **get rank/range** — both must be fast. **Redis sorted sets** do exactly this in `O(log n)`.

---

## 2. Requirements

**Functional**
- Update a player's score (set/increment).
- Get **top N**; a player's **rank**; **players around a player** (neighbors); a player's score.
- Optionally: **time-windowed** (daily/weekly/all-time), **per-region/game**.

**Non-functional**
- **Real-time** updates + reads (sub-ms); scale to **millions/billions** of players; highly available. **Eventual consistency** usually fine (rank lagging a moment is OK).

---

## 3. Capacity Estimation

```
Players ~ 100M's · score updates ~ high (every game event) · reads ~ very high (everyone checks rank)
One Redis ZSET: ~100 bytes/member → 10M members ≈ ~1 GB (fits); 100M+ → shard or bigger nodes
Ops: ZADD/ZINCRBY O(log n) + ZREVRANK O(log n) → 100k+ ops/sec/node easily (in-memory)
Durable store: scores table + score_events (audit) → modest
```

> The serving structure is **in-memory (Redis)**; the DB is the durable backing. Sizing = members × ~100 bytes; shard when a single ZSET gets too big or too hot.

---

## 4. Architecture

```
Game/App → Score Service → ZINCRBY Redis ZSET (serving)   ─┐
                        └→ emit score event (Kafka)         │ async write-behind
                                                            ▼
                                                      Scores DB (durable truth) + score_events (audit)
Reads (top-N / rank / around) → Redis ZSET (+ cached top-N) ; DB only for cold-start rebuild
```

- **CQRS-ish:** Redis is the fast read/serve model; the DB is the source of truth (write-behind).

---

## 5. The Naive DB Approach (why it fails)

```sql
SELECT COUNT(*)+1 FROM scores WHERE score > (SELECT score FROM scores WHERE user_id=?)   -- my rank
SELECT * FROM scores ORDER BY score DESC LIMIT 10                                          -- top 10
```

- "My rank" = a **COUNT over all higher scores** → O(n) (or heavy index work) **per query**.
- With frequent updates + millions of rows, this crushes the DB. **Ranking is fundamentally an ordered-set problem, not a relational one.**

---

## 6. Redis Sorted Set (the core)

A **sorted set (ZSET)** — backed by a **skip list + hash map** — keeps members ordered by score with `O(log n)` ops.

```
ZADD    leaderboard <score> <userId>        # add/update score          O(log n)
ZINCRBY leaderboard <delta> <userId>        # increment score           O(log n)
ZREVRANGE leaderboard 0 9 WITHSCORES        # TOP 10                     O(log n + k)
ZREVRANK  leaderboard <userId>              # MY RANK (0-based)          O(log n)
ZREVRANGE leaderboard <rank-5> <rank+5>     # players AROUND me          O(log n + k)
ZSCORE   leaderboard <userId>               # my score                   O(1)
```

- The skip list gives ordered range/rank; the hash map gives O(1) member→score lookup.
- All the "hard" queries become single `O(log n)` (or `+k`) commands — that's the whole trick.

---

## 7. Write Path & Durability

```
onScore(user, delta):
  ZINCRBY leaderboard delta user        # update serving ZSET (atomic)
  append score_event (Kafka) → async writer updates the DB (write-behind)
```

- **`ZINCRBY` is atomic** (Redis single-threaded) → no lost updates on concurrent increments.
- **Write-behind:** the DB is updated asynchronously (batched) → fast writes, durable truth.
- **Cold start / Redis loss:** rebuild the ZSET from the DB (`scores` table); Redis AOF/RDB persistence speeds recovery.

---

## 8. Scaling to Millions (sharding + approximate rank)

| Concern | Approach |
| --- | --- |
| **Memory / size** | One ZSET holds millions; beyond that, **shard** (by region/game/score-band) |
| **Sharding** | Partition into sub-leaderboards; **merge top-N** across shards for a global view |
| **Exact global rank across shards** | Expensive → use **approximate rank** |
| **Durability** | DB is truth; rebuild ZSET on cold start; AOF/RDB |
| **Hot updates** | `ZINCRBY` atomic; batch write-behind |
| **Read scale** | Redis replicas for read-heavy top-N; **cache top-N** (changes slowly) |

**Approximate global rank (the key large-scale trick):**
```
Maintain a HISTOGRAM of score buckets (e.g. counts per score range), globally aggregated.
my_rank ≈ (Σ counts in all buckets with score > my bucket) + my position within my bucket
→ O(#buckets), avoids exact ordering across all shards; exact only within a bucket.
```
- Top-N is still exact (merge each shard's top-N); **exact global rank** is what's expensive, so approximate it.

---

## 9. Time-Windowed Leaderboards

- **Daily/weekly** boards = separate ZSETs keyed by window (`lb:daily:2026-07-07`) with **TTL** → auto-expire.
- **All-time** = a persistent ZSET.
- A score update **increments all active windows** (daily + weekly + all-time).

---

## 10. Data Model

```sql
-- Durable backing store (source of truth)
CREATE TABLE scores ( user_id BIGINT PRIMARY KEY, score BIGINT NOT NULL, updated_at TIMESTAMP );
CREATE TABLE score_events ( event_id BIGINT PRIMARY KEY, user_id BIGINT, delta BIGINT, at TIMESTAMP );  -- audit / rebuild

-- Serving layer (Redis):
--   ZSET leaderboard:all              member=userId score=score
--   ZSET leaderboard:daily:{date}     (TTL)  · leaderboard:region:{r}
--   score_histogram (buckets)         for approximate global rank
--   cache: top-N list (short TTL)
```

> **Stores to consider:** scores (durable), score_events (audit/rebuild), Redis ZSETs per window/region, score histogram (approx rank), cached top-N. Redis ZSET = serving; DB = truth.

---

## 11. API Design

```
POST /v1/scores            { userId, delta }          # ZINCRBY (+ event)
GET  /v1/leaderboard/top?n=10&window=daily
GET  /v1/leaderboard/rank?userId=            → { rank, score }
GET  /v1/leaderboard/around?userId=&range=5
```

---

## 12. Sequences

### Score update + read rank

```
Game → ScoreSvc: ZINCRBY leaderboard delta user  (atomic)  → emit score_event → Kafka
       async writer → update scores DB (batched, write-behind)
User → ScoreSvc: ZREVRANK leaderboard user → rank ; ZSCORE → score  (O(log n))
```

### Cold start (Redis lost)

```
On Redis restart with empty ZSET → stream scores table → ZADD each → serving restored
(AOF/RDB persistence makes this fast; DB is the ultimate truth)
```

---

## 13. Consistency & Edge Cases

| Case | Handling |
| --- | --- |
| Concurrent increments | `ZINCRBY` atomic (Redis single-threaded) — no lost updates |
| Ties (equal scores) | ZSET orders by score then lexicographically by member; or pack a timestamp into the score for deterministic ties |
| Redis loss | Rebuild ZSET from DB (+ AOF/RDB) |
| Exact global rank at scale | Approximate via bucket histogram; exact within bucket |
| Score rollback/cheating | `score_events` audit; recompute; anomaly detection |
| Read during update | Eventual — a momentarily stale rank is fine |
| Time-window rollover | New ZSET per window with TTL; writes hit all active windows |

---

## 14. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Sorted Set (skip list)** | Core ranking structure | O(log n) rank/range |
| **Write-Behind (write-back) cache** | Redis serves; async persist to DB | Fast writes + durability |
| **Strategy** | Ranking scope (global/region/time), exact vs approximate rank | Swap approach |
| **Sharding** | Split ZSET by region/score-band | Scale beyond one node |
| **CQRS / Materialized View** | Redis serving view vs DB truth | Fast reads |
| **Observer / Pub-Sub** | Score events → boards, notify, DB | Decouple |
| **Cache-Aside** | Cached top-N | Read performance |
| **Producer-Consumer** | Async score-event processing | Absorb bursts |

---

## 15. Interview Cheat Sheet

> **"How do you get 'my rank' fast?"**
> "A **Redis sorted set** (skip list) — `ZREVRANK` gives rank in `O(log n)`, `ZREVRANGE` gives top-N and 'players around me', `ZSCORE` is O(1). A relational `COUNT(*) WHERE score > mine` is O(n) per query and doesn't scale."

> **"How is it durable?"**
> "Redis is the serving layer with **write-behind** to the DB (source of truth); `ZINCRBY` is atomic. On cold start, rebuild the ZSET from the `scores` table; Redis AOF/RDB speeds recovery."

> **"How do you scale to hundreds of millions of players?"**
> "**Shard** leaderboards by region/game/score-band and merge top-N. Exact global rank across shards is expensive → use **approximate rank via a score-bucket histogram** (count higher buckets + position within your bucket). Cache top-N and use read replicas."

> **"Ties / windows?"**
> "Ties: order by score then member, or pack a timestamp into the score. Windows: separate ZSETs per day/week with TTL; a score update increments all active windows."

---

## 16. Final Takeaways

- Ranking is an **ordered-set** problem → **Redis sorted set** (`ZADD`/`ZINCRBY`/`ZREVRANK`/`ZREVRANGE`), all `O(log n)`.
- The relational `COUNT` approach is O(n) per query — doesn't scale.
- **Redis serves, DB is truth** (write-behind, atomic ZINCRBY); rebuild ZSET on cold start.
- **Shard** by region/score-band + **approximate rank** (bucket histogram) at massive scale; cache top-N.
- **Time-windowed** boards = per-window ZSETs with TTL.
- Patterns: Sorted Set, Write-Behind, Strategy, Sharding, CQRS/Materialized View, Cache-Aside.

### Related notes

- [Distributed Cache](distributed-cache-system-design.md) · [Databases — Deep Dive](../concepts/databases-deep-dive.md) (Redis sorted sets) · [Caching Strategies](../concepts/caching-strategies.md) · [Consistent Hashing](../concepts/consistent-hashing.md)
