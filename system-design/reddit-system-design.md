# Reddit — System Design (Communities, Feeds & Voting)

> **Core challenge:** communities (subreddits) of **posts** and **nested comments**, ranked **feeds** ("hot"/"top"/"new"), **voting at massive scale**, and personalized home feeds — a **read-heavy** system where **ranking** and **feed generation** dominate.

---

## Contents

- [1. Mental Model](#1-mental-model)
- [2. Requirements](#2-requirements)
- [3. Capacity Estimation](#3-capacity-estimation)
- [4. Feed Generation (fan-out)](#4-feed-generation-fan-out)
- [5. Ranking (hot / top / best)](#5-ranking-hot--top--best)
- [6. Voting at Scale](#6-voting-at-scale)
- [7. Nested Comments](#7-nested-comments)
- [8. Data Model (all tables)](#8-data-model-all-tables)
- [9. API Design](#9-api-design)
- [10. Design Patterns (that can be used)](#10-design-patterns-that-can-be-used)
- [11. Scaling & Failure](#11-scaling--failure)
- [12. Interview Cheat Sheet](#12-interview-cheat-sheet)
- [13. Final Takeaways](#13-final-takeaways)

---

## 1. Mental Model

```
User joins subreddits → posts + votes + comments → home feed = ranked merge of subscribed subreddits
```

Read-heavy content platform. The interesting parts: **how the feed is built** (fan-out), **how content is ranked** (hot/top), and **voting throughput**.

---

## 2. Requirements

**Functional**
- Create/join **subreddits**; create **posts** (text/link/image); **nested comments**.
- **Upvote/downvote** posts and comments; score-based ranking.
- Feeds: subreddit feed + personalized **home feed** (subscribed subs), sorts: hot/new/top/best.
- Search; moderation.

**Non-functional**
- **Read-heavy** (reads ≫ writes) → cache + precomputed feeds.
- Eventual consistency fine (vote counts, feed freshness).
- Scale to billions of votes/posts.

---

## 3. Capacity Estimation

```
DAU ~ 50M · reads:writes ~ 100:1
Feed reads (peak) ~ 100k+/sec → must be cached/precomputed
Votes ~ very high write volume → aggregate async, don't recount on read
Storage: posts/comments grow forever → partition + archive cold
```

---

## 4. Feed Generation (fan-out)

Two feeds: a **subreddit feed** and a **personalized home feed** (merge of subscribed subreddits).

| Approach | How | Trade-off |
| --- | --- | --- |
| **Fan-out on read (pull)** | At read time, fetch top posts from each subscribed sub, merge + rank | Cheap writes; heavier reads; good when subscriptions vary a lot |
| **Fan-out on write (push)** | On new post, push post id into followers' feed caches | Fast reads; expensive for huge subs (millions of subscribers) |
| **Hybrid** | Push for small subs, pull for huge subs; cache merged home feed | Best of both (Reddit/Twitter style) |

> Reddit-style: precompute/cache **per-subreddit ranked lists**; the home feed **merges** the user's subscribed subs' cached lists (pull + cache). Huge subs use cached hot lists everyone shares.

---

## 5. Ranking (hot / top / best)

- **Hot** — score decays with time so fresh popular posts rise:

```
hot = log10(max(|ups - downs|, 1)) * sign(ups - downs) + (epoch_seconds / 45000)
```

- **Top** — pure score over a time window (day/week/all).
- **Best (comments)** — Wilson score confidence interval (fair for few votes).
- Rankings are **precomputed periodically** per subreddit and cached; not computed per request.

| Sort | Idea |
| --- | --- |
| Hot | popularity + time decay |
| New | recency |
| Top | score in window |
| Best | Wilson confidence (comments) |

---

## 6. Voting at Scale

```
vote(user, post, +1/-1):
  1. record the vote (dedupe: one vote per user per post) → votes table / Kafka event
  2. do NOT recompute score synchronously on the hot read path
  3. async aggregator updates post.score (counter) + recomputes ranking periodically
  4. cache the score; feeds read cached score
```

- **One vote per (user, post)** — unique constraint / idempotent (changing vote updates it).
- Counters updated **async** (Kafka consumer / batch) — approximate counts are fine; exact recount is too expensive at scale.
- Cache scores in Redis; periodic ranking jobs refresh hot lists.

---

## 7. Nested Comments

Comments form a **tree** (replies to replies). Storage options:

| Model | Note |
| --- | --- |
| **Adjacency list** (`parent_id`) | Simple; recursive fetch or CTE |
| **Materialized path** (`path = "1/5/9"`) | Fetch a whole subtree by prefix; easy ordering |
| **Closure table** | Flexible ancestor queries; more storage |

- Load top-level comments + a few levels; lazy-load deeper ("load more replies").
- Rank sibling comments by **best/top/new**.

---

## 8. Data Model (all tables)

```sql
CREATE TABLE users ( user_id BIGINT PRIMARY KEY, username VARCHAR(50) UNIQUE, karma BIGINT DEFAULT 0, created_at TIMESTAMP );
CREATE TABLE subreddits ( subreddit_id BIGINT PRIMARY KEY, name VARCHAR(50) UNIQUE, description TEXT, member_count BIGINT DEFAULT 0 );
CREATE TABLE subscriptions ( user_id BIGINT, subreddit_id BIGINT, PRIMARY KEY(user_id, subreddit_id) );

CREATE TABLE posts (
    post_id BIGINT PRIMARY KEY,          -- Snowflake (time-sortable)
    subreddit_id BIGINT NOT NULL, author_id BIGINT NOT NULL,
    title TEXT, type VARCHAR(10), body TEXT, url TEXT,
    score INT DEFAULT 0, up_count INT DEFAULT 0, down_count INT DEFAULT 0,
    comment_count INT DEFAULT 0, hot_rank DOUBLE PRECISION,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_posts_sub_hot ON posts(subreddit_id, hot_rank DESC);
CREATE INDEX idx_posts_sub_new ON posts(subreddit_id, created_at DESC);

CREATE TABLE comments (
    comment_id BIGINT PRIMARY KEY, post_id BIGINT NOT NULL,
    parent_id BIGINT, path TEXT,          -- materialized path e.g. '1/5/9'
    author_id BIGINT, body TEXT, score INT DEFAULT 0, created_at TIMESTAMP
);
CREATE INDEX idx_comments_post_path ON comments(post_id, path);

CREATE TABLE votes (
    user_id BIGINT, target_type VARCHAR(10), target_id BIGINT,  -- POST or COMMENT
    value SMALLINT,                        -- +1 / -1
    created_at TIMESTAMP,
    PRIMARY KEY (user_id, target_type, target_id)   -- one vote per user per target
);

-- Precomputed feeds (cache/materialized): per-subreddit hot list, per-user home feed
--   Redis:  feed:sub:{id}:hot → [postIds],   feed:home:{userId} → [postIds]
CREATE TABLE moderation ( id BIGINT PRIMARY KEY, target_type VARCHAR(10), target_id BIGINT, action VARCHAR(20), mod_id BIGINT );
```

> **Tables to consider:** users, subreddits, subscriptions, posts, comments, votes, precomputed feeds (Redis/materialized), moderation, awards, media_refs.

---

## 9. API Design

```
GET  /v1/r/{sub}/posts?sort=hot&cursor=          # subreddit feed
GET  /v1/home?sort=hot&cursor=                    # personalized feed
POST /v1/r/{sub}/posts        { title, body|url }
GET  /v1/posts/{id}/comments?sort=best
POST /v1/posts/{id}/comments  { parentId?, body }
POST /v1/vote                 { targetType, targetId, value }
POST /v1/subreddits/{id}/subscribe
```

---

## 10. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Strategy** | Ranking (hot/top/best/new), feed generation (push/pull/hybrid) | Swap algorithms |
| **Composite** | Nested comment tree | Uniform tree ops |
| **Observer / Pub-Sub** | Vote/post events → counters, feed updates, notifications | Decouple write from aggregation |
| **CQRS** | Precomputed feed read model vs write model | Fast reads |
| **Materialized View** | Cached ranked feeds, vote counters | Avoid recompute on read |
| **Producer-Consumer** | Async vote aggregation via Kafka | Absorb vote firehose |
| **Repository** | Data access | Testable |
| **Decorator** | Post rendering (awards, flair, NSFW tags) | Compose display |
| **Facade** | Feed service over sub + rank + cache | Simple API |

---

## 11. Scaling & Failure

- **Read path** = cache/precomputed feeds (Redis); DB rarely hit for feed reads.
- **Vote firehose** → Kafka → async counter aggregation; approximate counts cached.
- **Ranking jobs** run periodically per subreddit; hot lists shared across users.
- **Home feed** = merge subscribed subs' cached lists (+ cache the merged result).
- **Comment trees** via materialized path; lazy-load deep threads.
- Partition posts/comments by subreddit or time; archive cold content.
- Eventual consistency: a vote count lagging a bit is fine.

---

## 12. Interview Cheat Sheet

> **"How do you build the home feed?"**
> "Hybrid fan-out: precompute per-subreddit ranked hot lists (shared), then merge the user's subscribed subs' cached lists into a home feed and cache it. Push for small subs, pull for huge ones — pure push to millions of subscribers is too expensive."

> **"How is 'hot' ranking done?"**
> "A score combining vote count (log-scaled) and time (decay), computed periodically and cached — never per request. Top = score in a window; Best comments = Wilson confidence interval."

> **"How do you handle massive vote volume?"**
> "One vote per user per target (unique constraint), events to Kafka, async aggregation of counters, cached scores. Approximate counts are acceptable; exact recount on read doesn't scale."

> **"How are nested comments stored?"**
> "Materialized path (or adjacency list) — fetch a subtree by path prefix, lazy-load deeper replies, rank siblings by best/top/new."

---

## 13. Final Takeaways

- **Read-heavy** → precomputed, cached feeds; DB rarely touched on reads (**CQRS + materialized views**).
- **Feed = hybrid fan-out**: per-sub cached hot lists + merged home feed.
- **Ranking** (hot/top/best) computed periodically, cached; time-decay for hot.
- **Voting** = one per user/target, async aggregation, approximate cached counts.
- **Comments** = tree via materialized path, lazy-loaded.
- Patterns: Strategy (rank/feed), Composite (comments), Observer/Producer-Consumer (votes), CQRS/Materialized View.

### Related notes

- [Quora — System Design](quora-system-design.md) — sibling Q&A/content-ranking platform
- [Caching Strategies](../concepts/caching-strategies.md) · [Apache Kafka](../concepts/kafka.md) · [Database Indexing](../concepts/database-indexing.md)
