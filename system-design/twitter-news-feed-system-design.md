# Twitter / News Feed — System Design

> **Core challenge:** let users **post** short messages and see a **home timeline** of posts from everyone they follow, ranked and fresh — at massive scale where reads ≫ writes and some accounts have **tens of millions of followers**. The signature problem is **feed fan-out** (push vs pull) and the **celebrity/hot-key** problem.

---

## Contents

- [1. Mental Model](#1-mental-model)
- [2. Requirements](#2-requirements)
- [3. Capacity Estimation](#3-capacity-estimation)
- [4. Feed Fan-out — Push vs Pull vs Hybrid](#4-feed-fan-out--push-vs-pull-vs-hybrid)
- [5. Timeline Read Path](#5-timeline-read-path)
- [6. Ranking](#6-ranking)
- [7. Data Model (all tables)](#7-data-model-all-tables)
- [8. API Design](#8-api-design)
- [9. Design Patterns (that can be used)](#9-design-patterns-that-can-be-used)
- [10. Scaling & Failure](#10-scaling--failure)
- [11. Interview Cheat Sheet](#11-interview-cheat-sheet)
- [12. Final Takeaways](#12-final-takeaways)

---

## 1. Mental Model

```
User posts a tweet → delivered into the home timelines of all followers
Home timeline = merge of tweets from everyone you follow, ranked by time/relevance
```

Read-heavy. The whole design hinges on **how the home timeline is assembled**: precompute per-user timelines (push) or assemble on read (pull), or a hybrid.

---

## 2. Requirements

**Functional**
- Post a tweet (text/media); follow/unfollow users.
- **Home timeline** (people you follow) + **user timeline** (a person's own tweets).
- Likes, retweets, replies; search; notifications.

**Non-functional**
- **Read-heavy** (reads ≫ writes); low-latency timeline; huge scale (100s M users).
- Eventual consistency is fine (a tweet appearing a second late is OK).

---

## 3. Capacity Estimation

```
DAU ~ 200M · tweets ~ 500M/day (~6k/sec avg, peaks higher)
Timeline reads ~ hundreds of thousands/sec  → must be precomputed/cached
Fan-out: avg 200 followers → 1 tweet = 200 timeline writes
  celebrity 50M followers → 1 tweet = 50M writes (the hot-key problem)
```

---

## 4. Feed Fan-out — Push vs Pull vs Hybrid

The central decision.

| Model | On post | On read | Trade-off |
| --- | --- | --- | --- |
| **Fan-out on write (push)** | Write tweet id into **every follower's timeline** cache | Read your precomputed timeline (fast) | Great reads; **write amplification** explodes for celebrities |
| **Fan-out on read (pull)** | Just store the tweet | Fetch recent tweets from everyone you follow + merge | Cheap writes; **expensive reads** (merge many timelines) |
| **Hybrid** ✅ | Push for normal users; **don't** push celebrity tweets | Merge precomputed timeline + pull celebrities' recent tweets at read | Best of both — solves the celebrity problem |

```
Hybrid:
  normal author posts → fan-out to followers' timeline caches (push)
  celebrity posts     → NOT fanned out; stored in their user timeline
  read home timeline  → merge(precomputed pushed timeline, pull recent tweets of followed celebrities)
```

> **The interview crux:** pure push dies on celebrities (50M writes per tweet); pure pull is slow on read. **Hybrid** (push for the masses, pull for celebrities) is the standard answer.

---

## 5. Timeline Read Path

```
GET /home:
  1. read precomputed timeline (Redis list of tweet ids for this user)
  2. merge in recent tweets from followed celebrities (pulled + cached)
  3. hydrate tweet ids → tweet content (from cache / tweet store)
  4. rank + paginate (cursor)
```

- **Timeline cache** = Redis list/sorted set of tweet ids per user (store ids, not full tweets → hydrate from a shared tweet cache).
- Keep only recent N (e.g. 800) tweet ids per timeline; older via pull.

---

## 6. Ranking

- Chronological (reverse-time) is the baseline.
- Ranked feed = ML relevance score (engagement, recency, affinity, author quality) — treat as a black box; emphasize candidate generation (follow graph) → ranking → cache.

---

## 7. Data Model (all tables)

```sql
CREATE TABLE users ( user_id BIGINT PRIMARY KEY, handle VARCHAR(50) UNIQUE, name TEXT,
                     follower_count BIGINT DEFAULT 0, is_celebrity BOOLEAN DEFAULT FALSE );

CREATE TABLE tweets (
    tweet_id BIGINT PRIMARY KEY,           -- Snowflake (time-sortable)
    author_id BIGINT NOT NULL, text TEXT, media_ref TEXT,
    reply_to BIGINT, retweet_of BIGINT,
    like_count INT DEFAULT 0, retweet_count INT DEFAULT 0,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_tweets_author ON tweets(author_id, created_at DESC);   -- user timeline

CREATE TABLE follows (
    follower_id BIGINT, followee_id BIGINT, created_at TIMESTAMP,
    PRIMARY KEY (follower_id, followee_id)
);
CREATE INDEX idx_follows_followee ON follows(followee_id);   -- who follows X (for fan-out)

CREATE TABLE likes ( user_id BIGINT, tweet_id BIGINT, PRIMARY KEY(user_id, tweet_id) );
CREATE TABLE retweets ( user_id BIGINT, tweet_id BIGINT, PRIMARY KEY(user_id, tweet_id) );

-- Home timelines: precomputed → Redis
--   timeline:{userId} = sorted set of tweet ids (score = tweet time)
--   tweet:{id}        = cached tweet content
```

> **Tables to consider:** users, tweets, follows, likes, retweets, precomputed timelines (Redis), notifications, media_refs, hashtags/search_index.

---

## 8. API Design

```
POST /v1/tweets            { text, mediaRef? }
GET  /v1/home?cursor=       # home timeline (hybrid assembled)
GET  /v1/users/{id}/tweets  # user timeline
POST /v1/users/{id}/follow  · DELETE /v1/users/{id}/follow
POST /v1/tweets/{id}/like   · POST /v1/tweets/{id}/retweet
```

---

## 9. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Strategy** | Fan-out (push/pull/hybrid), ranking (chrono/ML) | Swap per user type/experiment |
| **Producer-Consumer** | Fan-out workers consume tweet events from Kafka | Absorb + parallelize fan-out |
| **Observer / Pub-Sub** | Tweet event → fan-out, search index, notifications | Decouple |
| **CQRS + Materialized View** | Precomputed timelines vs write model | Fast reads |
| **Fan-out (Publish-Subscribe)** | Deliver to followers | Core delivery |
| **Repository** | Data access | Testable |
| **Facade** | Timeline service over cache + pull + hydrate | Simple API |
| **Decorator** | Tweet rendering (badges, media, quoted) | Compose display |
| **Cache-Aside** | Tweet + timeline caches | Read performance |

---

## 10. Scaling & Failure

- **Fan-out via Kafka + worker pool**; celebrity tweets skip fan-out (hybrid).
- **Timeline cache** in Redis (ids only); hydrate from tweet cache; cap length.
- **Follow graph** sharded; `idx_follows_followee` powers fan-out.
- **Tweet store** partitioned by tweet_id/time; archive old.
- **Hot key (celebrity)** → pull + heavy caching + CDN for their tweets.
- Eventual consistency: timelines lag slightly, acceptable.

---

## 11. Interview Cheat Sheet

> **"How do you build the home timeline?"**
> "Hybrid fan-out: push tweet ids into followers' Redis timelines for normal authors; for celebrities, skip fan-out and pull their recent tweets at read time, merging with the precomputed timeline. Store ids, hydrate content from a shared cache."

> **"Why not pure push?"**
> "A celebrity with 50M followers = 50M timeline writes per tweet — write amplification kills it. Hybrid pushes for the masses and pulls for celebrities."

> **"Why not pure pull?"**
> "Reading merges tweets from everyone you follow on every request — too slow at scale. Precomputing (push) makes the common read fast."

> **"How do you store timelines?"**
> "Redis sorted set of tweet ids per user (score = time), capped to recent N; hydrate ids to content from a shared tweet cache."

---

## 12. Final Takeaways

- **Read-heavy** → precompute timelines; the core decision is **fan-out push vs pull**.
- **Hybrid fan-out** (push for normal, pull for celebrities) solves the write-amplification / hot-key problem.
- **Store ids in timelines**, hydrate content from a shared cache (Redis).
- **Fan-out via Kafka + workers**; `follows(followee)` index drives it.
- Patterns: Strategy (fan-out/rank), Producer-Consumer, CQRS/Materialized View, Cache-Aside, Observer.

### Related notes

- [Fan-Out / Fan-In & Celebrity Problem](../concepts/fan-out-fan-in.md) — the push/pull/hybrid + celebrity problem in depth
- [Reddit — System Design](reddit-system-design.md) · [Quora — System Design](quora-system-design.md) — related feed/ranking systems
- [Caching Strategies](../concepts/caching-strategies.md) · [Apache Kafka](../concepts/kafka.md) · [Consistent Hashing](../concepts/consistent-hashing.md)
