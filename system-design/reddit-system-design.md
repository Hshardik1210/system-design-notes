# Reddit — System Design (Communities, Feeds & Voting)

> **Core challenge:** communities (subreddits) of **posts** and deeply **nested comments**, ranked **feeds** ("hot"/"top"/"new"/"best"), **voting at massive scale**, and personalized home feeds — a **read-heavy** system where **ranking**, **feed generation**, and **vote aggregation** dominate.

---

## Contents

- [1. Mental Model](#1-mental-model)
- [2. Requirements](#2-requirements)
- [3. Capacity Estimation](#3-capacity-estimation)
- [4. Architecture](#4-architecture)
- [5. Feed Generation (fan-out)](#5-feed-generation-fan-out)
- [6. Ranking (the math)](#6-ranking-the-math)
- [7. Voting at Scale](#7-voting-at-scale)
- [8. Nested Comments (tree modeling)](#8-nested-comments-tree-modeling)
- [9. Data Model (all tables)](#9-data-model-all-tables)
- [10. API Design](#10-api-design)
- [11. Sequences](#11-sequences)
- [12. Consistency & Edge Cases](#12-consistency--edge-cases)
- [13. Design Patterns (that can be used)](#13-design-patterns-that-can-be-used)
- [14. Scaling & Failure](#14-scaling--failure)
- [15. Interview Cheat Sheet](#15-interview-cheat-sheet)
- [16. Final Takeaways](#16-final-takeaways)

---

## 1. Mental Model

```
User joins subreddits → posts + votes + comments → home feed = ranked merge of subscribed subreddits
```

Read-heavy content platform. The interesting parts: **how the feed is built** (fan-out), **how content is ranked** (hot/top/best), and **vote throughput** (aggregate async, never recount on read).

---

## 2. Requirements

**Functional**
- Create/join **subreddits**; create **posts** (text/link/image); **nested comments**.
- **Upvote/downvote** posts and comments; score-based ranking.
- Feeds: subreddit feed + personalized **home feed** (subscribed subs); sorts: hot/new/top/best.
- Search; moderation; awards.

**Non-functional**
- **Read-heavy** (reads ≫ writes, ~100:1) → cache + precomputed feeds.
- **Eventual consistency** fine (vote counts, feed freshness lag OK).
- Scale to billions of votes/posts/comments.

---

## 3. Capacity Estimation

```
DAU ~ 50M · reads:writes ~ 100:1
Feed reads (peak) ~ 100k+/sec → MUST be cached/precomputed (never rebuilt per request)
Votes ~ very high write volume → aggregate ASYNC; approximate cached counts
Posts/comments grow forever → partition by time/subreddit + archive cold
Storage: comments dominate (deep threads); shard by post_id
```

---

## 4. Architecture

```
Client → API Gateway
  ├── Post Service (create/read posts)     → posts store (sharded) + cache
  ├── Comment Service (tree)               → comments store (materialized path) + cache
  ├── Vote Service                         → votes table + Kafka events (async aggregation)
  ├── Feed Service (build/serve)           → Redis (per-sub hot lists + home feed) + ranking jobs
  ├── Search Service                       → Elasticsearch
  └── Moderation Service
             │
          Kafka (POST_CREATED, VOTE_CAST, COMMENT_CREATED → aggregation, ranking, feed, search)
```

- **CQRS:** read path = precomputed/cached feeds; write path = posts/comments/votes stores. Ranking is a **periodic job**, not per-request.

---

## 5. Feed Generation (fan-out)

Two feeds: a **subreddit feed** and a **personalized home feed** (merge of subscribed subs).

| Approach | How | Trade-off |
| --- | --- | --- |
| **Pull (fan-out on read)** | At read, fetch top posts from each subscribed sub, merge + rank | Cheap writes; heavier reads |
| **Push (fan-out on write)** | On new post, push id into subscribers' feed caches | Fast reads; expensive for huge subs |
| **Hybrid** ✅ | Precompute per-sub hot lists; home feed **merges** subscribed subs' cached lists | Best of both |

> **Reddit-style (the key insight):** unlike Twitter (per-user push), Reddit precomputes a **per-subreddit ranked hot list** that is **shared by all subscribers** — so a huge sub is computed **once**, not fanned out to millions. The home feed **merges** the user's subscribed subs' cached lists + caches the result. This sidesteps the celebrity/huge-sub write-amplification problem. (See [Fan-Out / Fan-In](../concepts/fan-out-fan-in.md).)

---

## 6. Ranking (the math)

Rankings are **precomputed periodically per subreddit** and cached — never computed per request.

### Hot (popularity + time decay)

```
hot = log10(max(|ups − downs|, 1)) × sign(ups − downs) + (epoch_seconds − 1134028003) / 45000
```

- **`log10(...)`** — the first 10 votes matter as much as the next 100; diminishing returns so a post doesn't dominate forever.
- **`sign(...)`** — downvoted posts sink.
- **time term** — newer posts get a higher base; the `45000` (~12.5h) means a post needs ~10× more votes to match one posted 12.5h earlier → **fresh content surfaces**. Because time is *additive*, hot scores are roughly monotonic in time → cache-friendly.

### Other sorts

| Sort | Idea |
| --- | --- |
| **Hot** | popularity + time decay (above) |
| **New** | pure recency (`created_at DESC`) |
| **Top** | pure score within a window (day/week/month/all) |
| **Best (comments)** | **Wilson score** lower bound — fair for items with few votes |

- **Wilson score** (for comments): the lower bound of a confidence interval on the upvote ratio → a comment with 5/5 upvotes doesn't outrank 900/1000 (accounts for sample size). Better than raw `ups − downs` or ratio.

---

## 7. Voting at Scale

```
vote(user, target, +1/-1):
  1. UPSERT vote (dedupe: one vote per (user, target); a re-vote UPDATES value)
  2. do NOT recompute score synchronously on the read path
  3. emit VOTE_CAST → Kafka → async aggregator updates counters (up/down) + score
  4. periodic ranking job recomputes hot_rank per subreddit; cache the results
```

- **One vote per (user, target)** — unique constraint; changing a vote (+1 → −1) updates the row and adjusts counters by the **delta**.
- **Async aggregation** (Kafka consumer / batched) — approximate cached counts are fine; **exact recount on read doesn't scale** (a post can have millions of votes).
- **Vote fuzzing** (Reddit does this) — displayed counts are slightly randomized to deter vote-manipulation bots.
- Cache scores in Redis; ranking jobs refresh per-sub hot lists on an interval.

---

## 8. Nested Comments (tree modeling)

Comments form a **tree** (replies to replies, arbitrarily deep). Storage options:

| Model | How | Trade-off |
| --- | --- | --- |
| **Adjacency list** (`parent_id`) | Each row points to its parent | Simple; fetching a subtree needs recursion/CTE (N queries or a recursive query) |
| **Materialized path** (`path='1/5/9'`) ✅ | Store the full path from root | Fetch a whole subtree by **prefix match** (`WHERE path LIKE '1/5/%'`); easy ordering; path can get long |
| **Closure table** | Row per ancestor-descendant pair | Flexible ancestor/descendant queries; more storage/writes |

- **Load pattern:** fetch top-level comments + a few levels ranked by **best**; **lazy-load** deeper threads ("load more replies") — never load a 10k-comment tree at once.
- **Rank siblings** by best/top/new (Wilson for best).
- Comment counts + scores aggregated async like votes.

---

## 9. Data Model (all tables)

```sql
CREATE TABLE users ( user_id BIGINT PRIMARY KEY, username VARCHAR(50) UNIQUE, karma BIGINT DEFAULT 0, created_at TIMESTAMP );
CREATE TABLE subreddits ( subreddit_id BIGINT PRIMARY KEY, name VARCHAR(50) UNIQUE, description TEXT, member_count BIGINT DEFAULT 0 );
CREATE TABLE subscriptions ( user_id BIGINT, subreddit_id BIGINT, PRIMARY KEY(user_id, subreddit_id) );

CREATE TABLE posts (
    post_id BIGINT PRIMARY KEY,           -- Snowflake (time-sortable)
    subreddit_id BIGINT NOT NULL, author_id BIGINT NOT NULL,
    title TEXT, type VARCHAR(10), body TEXT, url TEXT,
    score INT DEFAULT 0, up_count INT DEFAULT 0, down_count INT DEFAULT 0,
    comment_count INT DEFAULT 0, hot_rank DOUBLE PRECISION,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_posts_sub_hot ON posts(subreddit_id, hot_rank DESC);   -- subreddit hot feed
CREATE INDEX idx_posts_sub_new ON posts(subreddit_id, created_at DESC); -- new feed

CREATE TABLE comments (
    comment_id BIGINT PRIMARY KEY, post_id BIGINT NOT NULL,
    parent_id BIGINT, path TEXT,          -- materialized path e.g. '1/5/9'
    author_id BIGINT, body TEXT, score INT DEFAULT 0, created_at TIMESTAMP
);
CREATE INDEX idx_comments_post_path ON comments(post_id, path);         -- subtree by prefix

CREATE TABLE votes (
    user_id BIGINT, target_type VARCHAR(10), target_id BIGINT,          -- POST | COMMENT
    value SMALLINT, created_at TIMESTAMP,                               -- +1 / -1
    PRIMARY KEY (user_id, target_type, target_id)                       -- one vote per user per target
);

CREATE TABLE moderation ( id BIGINT PRIMARY KEY, target_type VARCHAR(10), target_id BIGINT, action VARCHAR(20), mod_id BIGINT, at TIMESTAMP );
CREATE TABLE awards ( award_id BIGINT PRIMARY KEY, target_id BIGINT, giver_id BIGINT, type VARCHAR(20) );

-- Precomputed feeds → Redis:
--   feed:sub:{id}:hot  = sorted set of post ids (score = hot_rank)
--   feed:home:{userId} = merged, cached home feed
--   score:{targetId}   = cached vote score
```

> **Tables to consider:** users, subreddits, subscriptions, posts, comments, votes, moderation, awards, media_refs, precomputed feeds (Redis), search index (ES).

---

## 10. API Design

```
GET  /v1/r/{sub}/posts?sort=hot&cursor=          # subreddit feed
GET  /v1/home?sort=hot&cursor=                    # personalized feed
POST /v1/r/{sub}/posts        { title, body|url }
GET  /v1/posts/{id}/comments?sort=best&cursor=    # top-level + a few levels
POST /v1/posts/{id}/comments  { parentId?, body }
GET  /v1/comments/{id}/replies?cursor=            # "load more replies" (lazy)
POST /v1/vote                 { targetType, targetId, value }
POST /v1/subreddits/{id}/subscribe
```

---

## 11. Sequences

### Vote (async aggregation)

```
User → VoteSvc: UPSERT vote (dedupe) → emit VOTE_CAST → Kafka
Aggregator: consume → update up/down counters + score (by delta) → cache score
Ranking job (periodic): recompute hot_rank per subreddit → refresh feed:sub:{id}:hot
(read path never recounts — reads cached score/hot lists)
```

### Home feed read

```
User → FeedSvc:
  for each subscribed sub → read cached feed:sub:{id}:hot (top slice)
  merge + rank across subs → cache feed:home:{userId} (short TTL) → hydrate post content → paginate
```

---

## 12. Consistency & Edge Cases

| Case | Handling |
| --- | --- |
| Double vote / re-vote | `UNIQUE(user, target)`; re-vote updates value, adjust counters by delta |
| Vote count exactness | Async aggregation → **approximate** cached counts (fine); vote fuzzing deters bots |
| Deleted post/comment | Tombstone; keep tree structure ("[deleted]") so replies survive |
| Huge comment thread | Materialized path + lazy "load more"; never load full tree |
| Ranking staleness | Periodic recompute; a slightly stale hot list is acceptable (eventual) |
| Hot post (viral) | Cached score + cached feed slice; DB not hit per view |
| Brigading/manipulation | Rate limit, vote fuzzing, moderation, anomaly detection |

---

## 13. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Strategy** | Ranking (hot/top/best/new), feed generation (push/pull/hybrid) | Swap algorithms |
| **Composite** | Nested comment tree | Uniform tree ops |
| **Observer / Pub-Sub** | Vote/post/comment events → counters, feeds, search | Decouple write from aggregation |
| **CQRS + Materialized View** | Precomputed feeds/counters vs write model | Fast reads, avoid recompute |
| **Producer-Consumer** | Async vote aggregation via Kafka | Absorb the vote firehose |
| **Repository** | Data access | Testable |
| **Decorator** | Post rendering (awards, flair, NSFW) | Compose display |
| **Facade** | Feed service over sub lists + rank + cache | Simple API |

---

## 14. Scaling & Failure

- **Read path** = cache/precomputed feeds (Redis); DB rarely hit for feed reads.
- **Vote firehose** → Kafka → async counter aggregation; approximate cached counts.
- **Per-sub hot lists** computed **once** and shared by all subscribers (not per-user fan-out) → huge subs are cheap.
- **Home feed** = merge subscribed subs' cached lists + cache the merged result (short TTL).
- **Comment trees** via materialized path; lazy-load deep threads.
- **Partition** posts/comments by subreddit or time; archive cold content; shard votes by target.
- **Eventual consistency:** vote counts / feed freshness lag briefly — acceptable.

---

## 15. Interview Cheat Sheet

> **"How do you build the home feed?"**
> "Precompute a **shared per-subreddit hot list** (computed once, not fanned out to each subscriber), then **merge** the user's subscribed subs' cached lists into a home feed and cache it (hybrid). This avoids the huge-sub write-amplification problem — a 30M-subscriber sub is ranked once."

> **"How is 'hot' ranking computed?"**
> "`log10(votes) × sign + time/45000` — log-scaled votes (diminishing returns) plus an additive time term so fresh posts surface (a post needs ~10× votes to match one 12.5h older). Computed periodically and cached, never per request. Top = score in a window; Best comments = Wilson lower bound."

> **"How do you handle massive vote volume?"**
> "One vote per (user, target) with a unique constraint; emit to Kafka; async aggregator updates counters by delta and a periodic job recomputes rankings. Approximate cached counts (with vote fuzzing) — exact recount on read doesn't scale."

> **"How are nested comments stored?"**
> "Materialized path (`'1/5/9'`) → fetch a subtree by prefix, lazy-load deeper replies, rank siblings by best (Wilson). Adjacency list is simpler but needs recursive queries."

---

## 16. Final Takeaways

- **Read-heavy** → precomputed, cached feeds; DB rarely touched on reads (**CQRS + materialized views**).
- **Shared per-subreddit hot lists** (computed once) + merged home feed = no huge-sub write amplification.
- **Hot ranking** = log-scaled votes + additive time decay; **Best** = Wilson score; computed periodically + cached.
- **Voting** = one per user/target, async delta aggregation, approximate cached counts (+ fuzzing).
- **Comments** = tree via materialized path, lazy-loaded, ranked by best.
- Patterns: Strategy, Composite, Observer/Producer-Consumer, CQRS/Materialized View.

### Related notes

- [Quora — System Design](quora-system-design.md) · [Twitter / News Feed](twitter-news-feed-system-design.md) — sibling feed/ranking platforms
- [Fan-Out / Fan-In](../concepts/fan-out-fan-in.md) · [Caching Strategies](../concepts/caching-strategies.md) · [Apache Kafka](../concepts/kafka.md) · [Database Indexing](../concepts/database-indexing.md)
