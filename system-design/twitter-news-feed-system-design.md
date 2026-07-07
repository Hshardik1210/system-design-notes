# Twitter / News Feed — System Design

> **Core challenge:** let users **post** short messages and see a **home timeline** of posts from everyone they follow, ranked and fresh — at massive scale where **reads ≫ writes** and some accounts have **tens of millions of followers**. The signature problem is **feed fan-out** (push vs pull) and the **celebrity/hot-key** problem.

---

## Contents

- [1. Mental Model](#1-mental-model)
- [2. Requirements](#2-requirements)
- [3. Capacity Estimation](#3-capacity-estimation)
- [4. Architecture](#4-architecture)
- [5. Feed Fan-out — Push vs Pull vs Hybrid](#5-feed-fan-out--push-vs-pull-vs-hybrid)
- [6. The Fan-out Pipeline](#6-the-fan-out-pipeline)
- [7. Timeline Store & Read Path](#7-timeline-store--read-path)
- [8. Ranking](#8-ranking)
- [9. Search & Trending](#9-search--trending)
- [10. Data Model (all tables)](#10-data-model-all-tables)
- [11. API Design](#11-api-design)
- [12. Sequences](#12-sequences)
- [13. Edge Cases](#13-edge-cases)
- [14. Design Patterns (that can be used)](#14-design-patterns-that-can-be-used)
- [15. Scaling & Failure](#15-scaling--failure)
- [16. Interview Cheat Sheet](#16-interview-cheat-sheet)
- [17. Final Takeaways](#17-final-takeaways)

---

## 1. Mental Model

```
User posts a tweet → delivered into the home timelines of all followers
Home timeline = merge of tweets from everyone you follow, ranked by time/relevance
```

Read-heavy. The whole design hinges on **how the home timeline is assembled**: precompute per-user timelines (**push**), assemble at read time (**pull**), or a **hybrid**. (Deep dive on the pattern: [Fan-Out / Fan-In](../concepts/fan-out-fan-in.md).)

---

## 2. Requirements

**Functional**
- Post a tweet (text/media); follow/unfollow; retweet, reply, like.
- **Home timeline** (people you follow) + **user timeline** (a person's own tweets).
- Search; trending topics; notifications.

**Non-functional**
- **Read-heavy** (reads ≫ writes, ~100:1); low-latency timeline (feels instant); huge scale (100s M users).
- **Eventual consistency** is fine (a tweet appearing a second late is OK); **highly available** (read the feed even if some parts are degraded).

---

## 3. Capacity Estimation

```
DAU ~ 200M · tweets ~ 500M/day (~6k/sec avg, 2–3× peaks)
Timeline reads ~ hundreds of thousands/sec  → must be precomputed/cached (never rebuilt per request)
Fan-out math:
   avg 200 followers → 1 tweet = ~200 timeline writes
   500M tweets/day × 200 = 100B timeline writes/day  → a huge async fan-out fleet
   celebrity 50M followers → 1 tweet = 50M writes  ← the hot-key / write-amplification problem
Storage:
   tweets 500M/day × ~300 B ≈ 150 GB/day of tweet content → sharded store + archive
   timelines: store IDS only (8 B each) × capped length → Redis-friendly
```

> Two pressures: **enormous fan-out write volume** (async workers) and **very high timeline read QPS** (precompute + cache). Celebrities break naive push.

---

## 4. Architecture

```
Client → API Gateway
   ├── Tweet Service (write)     → tweet store (sharded by tweet_id) + tweet cache
   ├── Fan-out Service (workers) → consumes tweet events (Kafka) → writes follower timelines
   ├── Timeline Service (read)   → Redis timeline cache + pull celebrities + hydrate + rank
   ├── Graph Service             → follows (who-follows-whom), sharded by user
   ├── Search Service            → Elasticsearch (tweets, hashtags)
   └── Notification Service
              │
           Kafka (TWEET_CREATED → fan-out, search index, notifications, analytics)
```

- **Write path and read path are separate services** (CQRS) — the read path is optimized for the 100:1 read load.

---

## 5. Feed Fan-out — Push vs Pull vs Hybrid

| Model | On post | On read | Trade-off |
| --- | --- | --- | --- |
| **Push (fan-out on write)** | Write tweet id into **every follower's** timeline | Read your precomputed timeline (fast) | Great reads; **write amplification** explodes for celebrities |
| **Pull (fan-out on read)** | Just store the tweet | Fetch recent tweets from all followees + merge | Cheap writes; **expensive reads** |
| **Hybrid** ✅ | Push for normal users; **skip celebrities** | Merge precomputed timeline **+ pull** followed celebrities' recent tweets | Best of both |

```
Hybrid:
  normal author posts → fan-out to followers' timeline caches (push, async)
  celebrity posts     → NOT fanned out; kept in their user timeline
  read home timeline  → merge(precomputed pushed timeline, pulled recent tweets of followed celebrities) → rank
```

> **The crux:** pure push dies on celebrities (50M writes/tweet); pure pull is slow on every read. **Hybrid** (push for the masses, pull for the handful of celebrities you follow) is the standard answer. Threshold on follower count (e.g. >100k = "celebrity", skip push).

---

## 6. The Fan-out Pipeline

```
1. Tweet Service persists the tweet → emits TWEET_CREATED to Kafka
2. Fan-out workers consume it:
     - look up the author's followers (Graph Service, `follows(followee)` index)
     - for each follower's timeline: ZADD timeline:{followerId} score=tweetTime tweetId
     - cap each timeline to recent N (e.g. 800) → ZREMRANGEBYRANK trims old
3. (celebrity authors are skipped here → pulled at read time)
```

- **Async + parallel** via Kafka + a worker fleet — the poster doesn't wait for 200 writes.
- **Batch** timeline writes; partition fan-out work by follower shard.
- **Backpressure:** a huge (non-celebrity) account's fan-out is queued/throttled so it doesn't starve others.
- Store **tweet IDs** in timelines (8 bytes), not content → hydrate on read.

---

## 7. Timeline Store & Read Path

```
Timeline cache (Redis):  timeline:{userId} = SORTED SET of tweet ids (score = tweet timestamp)
                         capped to recent ~800 ids; older tweets → pull from user timelines

GET /home:
  1. ZREVRANGE timeline:{userId} 0 N        → recent tweet ids (precomputed / pushed)
  2. pull recent tweets of followed CELEBRITIES (cached per celebrity) → merge
  3. hydrate ids → tweet content from a shared tweet cache (mget)
  4. rank + paginate (cursor by score/id)
```

- **IDs in the timeline, content in a shared cache** → dedup storage (a viral tweet is cached once, referenced by millions of timelines).
- **Cursor pagination** by (score, tweet_id) — stable under new inserts.
- Cap timeline length; deeper history falls back to pull.

---

## 8. Ranking

- **Chronological (reverse-time)** is the baseline and simplest.
- **Ranked feed** = ML relevance: candidate generation (follow graph + pulled celebrities + some recommendations) → **scoring** (engagement likelihood, recency, author affinity, media type) → sort → cache.
- Treat the model as a **black box**; emphasize the pipeline: **candidates → rank → cache**, recomputed periodically or at read with cached features.

---

## 9. Search & Trending

- **Search** via **Elasticsearch** (tweet text, hashtags, users), fed from `TWEET_CREATED` via CDC/consumer; inverted index → fast full-text (see the ES section in Databases Deep Dive).
- **Trending** = count hashtag/term frequency over a **sliding time window** per region (stream aggregation, like ad-click counting) → top-k with time decay; cache and refresh every few minutes.

---

## 10. Data Model (all tables)

```sql
CREATE TABLE users ( user_id BIGINT PRIMARY KEY, handle VARCHAR(50) UNIQUE, name TEXT,
                     follower_count BIGINT DEFAULT 0, is_celebrity BOOLEAN DEFAULT FALSE );

CREATE TABLE tweets (
    tweet_id BIGINT PRIMARY KEY,            -- Snowflake (time-sortable)
    author_id BIGINT NOT NULL, text TEXT, media_ref TEXT,
    reply_to BIGINT, retweet_of BIGINT, quote_of BIGINT,
    like_count INT DEFAULT 0, retweet_count INT DEFAULT 0, reply_count INT DEFAULT 0,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_tweets_author ON tweets(author_id, created_at DESC);   -- user timeline

CREATE TABLE follows (
    follower_id BIGINT, followee_id BIGINT, created_at TIMESTAMP,
    PRIMARY KEY (follower_id, followee_id)
);
CREATE INDEX idx_follows_followee ON follows(followee_id);   -- who follows X (drives fan-out)

CREATE TABLE likes ( user_id BIGINT, tweet_id BIGINT, PRIMARY KEY(user_id, tweet_id) );
CREATE TABLE retweets ( user_id BIGINT, tweet_id BIGINT, PRIMARY KEY(user_id, tweet_id) );
CREATE TABLE hashtags ( tag VARCHAR(100), tweet_id BIGINT, created_at TIMESTAMP, PRIMARY KEY(tag, tweet_id) );

-- Precomputed timelines → Redis:
--   timeline:{userId} = sorted set of tweet ids (score = tweet time), capped
--   tweet:{id}        = cached tweet content (shared)
--   celeb:{id}:recent = recent tweet ids of a celebrity (pulled at read)
```

> **Tables to consider:** users, tweets, follows, likes, retweets, hashtags, notifications, media_refs, precomputed timelines (Redis), search index (ES), trending (stream/cache). Media → blob/CDN.

---

## 11. API Design

```
POST /v1/tweets            { text, mediaRef? }              → { tweetId }
GET  /v1/home?cursor=       # home timeline (hybrid assembled + ranked)
GET  /v1/users/{id}/tweets  # user timeline
POST /v1/users/{id}/follow  · DELETE /v1/users/{id}/follow
POST /v1/tweets/{id}/like   · POST /v1/tweets/{id}/retweet · POST /v1/tweets/{id}/reply
GET  /v1/search?q=          · GET /v1/trending?region=
```

---

## 12. Sequences

### Post (fan-out on write)

```
Author  TweetSvc  Kafka   Fan-outWorkers  GraphSvc  Redis(timelines)
  │ post  │         │           │             │           │
  ├──────►│ persist │           │             │           │
  │◄─ id ─┤─ TWEET_CREATED ────►│             │           │
  │       │         │           ├─ get followers ─────────►│
  │       │         │           ├─ ZADD each follower timeline (cap) ──►│
  │       │         │  (celebrity author → skipped; pulled at read)
```

### Read home timeline (hybrid)

```
User → TimelineSvc:
  ZREVRANGE timeline:{me}  → pushed tweet ids
  + pull recent tweets of followed celebrities (cached)
  → merge → hydrate content (mget tweet:{id}) → rank → paginate → return
```

---

## 13. Edge Cases

| Case | Handling |
| --- | --- |
| **New follow** | Backfill: pull the newly-followed user's recent tweets into the timeline (or just rely on pull for a while) |
| **Unfollow** | Lazy — filter their tweets out on read, or rebuild timeline async |
| **Deleted tweet** | Tombstone; filtered on hydrate (timeline holds a dangling id → skip) |
| **Celebrity crosses threshold** | Switch that account from push to pull (stop fanning out) |
| **Retweet of a celebrity** | Store as a retweet edge; hydrate original; avoid duplicating content |
| **Backfill for inactive users** | Don't fan out to long-inactive users (lazy pull on return) → saves huge write volume |

---

## 14. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Strategy** | Fan-out (push/pull/hybrid), ranking (chrono/ML) | Swap per user type/experiment |
| **Producer-Consumer** | Fan-out workers consume tweet events (Kafka) | Absorb + parallelize fan-out |
| **Observer / Pub-Sub** | Tweet event → fan-out, search index, trending, notifications | Decouple |
| **CQRS + Materialized View** | Precomputed timelines vs write model | Fast reads |
| **Cache-Aside** | Tweet + timeline caches | Read performance |
| **Repository** | Data access | Testable |
| **Facade** | Timeline service over cache + pull + hydrate + rank | Simple API |
| **Decorator** | Tweet rendering (badges, media, quoted) | Compose display |

---

## 15. Scaling & Failure

- **Fan-out** via Kafka + worker pool; celebrities skip it; inactive users skip it; batch + backpressure.
- **Timeline cache** in Redis (ids only, capped); hydrate from a shared tweet cache; CDN for media.
- **Follow graph** sharded by user; `follows(followee)` powers fan-out; mutuals via set intersection.
- **Tweet store** sharded by tweet_id/time; archive old tweets.
- **Hot key (celebrity/viral tweet)** → pull + heavy caching + CDN; the viral tweet's content is cached once.
- **Failure:** timeline cache miss → rebuild from user timelines (pull) → eventual consistency (a brief lag is fine); fan-out worker lag → some followers see a tweet a bit late.

---

## 16. Interview Cheat Sheet

> **"How do you build the home timeline?"**
> "Hybrid fan-out: async workers push tweet **ids** into followers' Redis timelines for normal authors; for celebrities, skip fan-out and **pull** their recent tweets at read time, merging with the precomputed timeline, then hydrate content from a shared cache and rank."

> **"Why hybrid, not pure push or pull?"**
> "Pure push = 50M writes for one celebrity tweet (write amplification). Pure pull = merging hundreds of followees on every read (slow). Hybrid pushes for the masses (fast reads) and pulls the few celebrities you follow (no write explosion)."

> **"How do you store timelines?"**
> "Redis sorted set of tweet ids per user (score = time), capped to ~800; store ids not content (a viral tweet is cached once and referenced by millions of timelines); hydrate on read."

> **"How does fan-out scale?"**
> "TWEET_CREATED → Kafka → parallel fan-out workers write follower timelines in batches; skip celebrities and inactive users; backpressure large accounts."

> **"Search / trending?"**
> "Search via Elasticsearch fed from tweet events. Trending = sliding-window term-frequency aggregation per region with time decay, cached."

---

## 17. Final Takeaways

- **Read-heavy** → precompute timelines (CQRS); the core decision is **fan-out push vs pull → hybrid**.
- **Hybrid** (push for normal, pull for celebrities/inactive) solves write amplification / hot-key.
- **Timelines store ids** (Redis sorted set, capped); **content cached once** and hydrated on read.
- **Fan-out = Kafka + worker fleet**, batched, backpressured; `follows(followee)` drives it.
- **Search = Elasticsearch**; **trending = windowed term counts** with decay.
- Patterns: Strategy (fan-out/rank), Producer-Consumer, CQRS/Materialized View, Cache-Aside, Observer.

### Related notes

- [Fan-Out / Fan-In & Celebrity Problem](../concepts/fan-out-fan-in.md) — push/pull/hybrid + celebrity problem in depth
- [Facebook](facebook-system-design.md) · [Instagram](instagram-system-design.md) · [Reddit](reddit-system-design.md) · [Quora](quora-system-design.md) — related feed/ranking systems
- [Caching Strategies](../concepts/caching-strategies.md) · [Apache Kafka](../concepts/kafka.md) · [Consistent Hashing](../concepts/consistent-hashing.md)
