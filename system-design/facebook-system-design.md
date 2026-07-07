# Facebook — System Design (Social Network)

> **Core challenge:** model a huge **bidirectional social graph** (friends), generate a **ranked News Feed** from friends'/groups'/pages' activity, and support posts, reactions, comments, and notifications — read-heavy, at billions of users, with **privacy** enforced on every read. The distinctive parts vs Twitter are the **mutual friend graph** and **rich ML feed ranking**.

---

## Contents

- [1. Mental Model & vs Twitter/Instagram](#1-mental-model--vs-twitterinstagram)
- [2. Requirements](#2-requirements)
- [3. Capacity Estimation](#3-capacity-estimation)
- [4. Architecture](#4-architecture)
- [5. The Social Graph (TAO)](#5-the-social-graph-tao)
- [6. News Feed Generation](#6-news-feed-generation)
- [7. Feed Ranking Pipeline](#7-feed-ranking-pipeline)
- [8. Privacy / Visibility](#8-privacy--visibility)
- [9. Posts, Reactions & Comments](#9-posts-reactions--comments)
- [10. Data Model (all tables)](#10-data-model-all-tables)
- [11. API Design](#11-api-design)
- [12. Sequences](#12-sequences)
- [13. Consistency & Edge Cases](#13-consistency--edge-cases)
- [14. Design Patterns (that can be used)](#14-design-patterns-that-can-be-used)
- [15. Scaling & Failure](#15-scaling--failure)
- [16. Interview Cheat Sheet](#16-interview-cheat-sheet)
- [17. Final Takeaways](#17-final-takeaways)

---

## 1. Mental Model & vs Twitter/Instagram

```
Friends (mutual) → post activity → your News Feed = ranked merge of friends' + groups' + pages' posts
```

| | **Facebook** | **Twitter** | **Instagram** |
| --- | --- | --- | --- |
| Graph | **Bidirectional** (friend request → accept) | Unidirectional follow | Unidirectional follow |
| Content | Mixed (text/photo/video/links/events) | Short text | Photo/video-first |
| Feed | Heavily **ML-ranked** | Chrono or ranked | Ranked + Stories |
| Extra | Groups, Pages, Messenger, Events | Retweets | Stories, Reels, Explore |

Read-heavy; the two hard parts are the **friend graph at scale** and **feed generation + ranking**, plus **privacy on every read**.

---

## 2. Requirements

**Functional**
- Send/accept **friend requests** (bidirectional); unfriend/block.
- Create **posts** (text/photo/video/link) with **privacy scope**; **react**, **comment**, **share**.
- **News Feed** ranked from friends/groups/pages.
- Groups, Pages, notifications, search. (Messenger = separate chat system.)

**Non-functional**
- **Read-heavy** (reads ≫ writes); low-latency feed; huge scale (billions); **eventual consistency OK** for feed/counts; **privacy is strict** (never leak a restricted post).

---

## 3. Capacity Estimation

```
Users ~ 3B, DAU ~ 2B · avg ~300 friends
Posts ~ hundreds of millions/day; reactions/comments ~ billions/day
Feed reads ~ millions/sec at peak → precompute + cache (never rebuild per request)
Fan-out: 1 post × ~300 friends = ~300 feed writes; a page with 100M followers = the celebrity problem
Graph: 3B users × ~300 edges ≈ ~1 trillion edges → sharded graph store + heavy caching
```

> Two pressures: a **trillion-edge graph** (read on every feed build → TAO-style cache) and **millions of feed reads/sec** (precompute + rank + cache).

---

## 4. Architecture

```
Client → API Gateway
  ├── Graph Service (friendships, suggestions)   → sharded graph store + TAO-style cache
  ├── Post Service (create/read posts)           → sharded store + cache
  ├── Fan-out Service (workers)                  → write friends' feed caches (Kafka)
  ├── Feed Service (build/rank/serve)            → Redis feed cache + ML ranking
  ├── Privacy/ACL Service                        → visibility checks on read
  ├── Comment/Reaction Service                   → async counters
  ├── Notification + Media (blob/CDN)
  └── Search (Elasticsearch)
             │
          Kafka (POST_CREATED, REACTION, COMMENT → fan-out, ranking features, index, notifications)
```

---

## 5. The Social Graph (TAO)

The friend graph is the foundation — **~1 trillion edges**. Facebook's real system is **TAO** (a graph-aware cache over sharded MySQL).

| Concept | Detail |
| --- | --- |
| **Objects** | Nodes = users, posts, comments, pages (typed, with fields) |
| **Associations** | Edges = friendship, likes, authored, member-of (typed, bidirectional pairs) |
| **Storage** | Sharded MySQL (objects + assoc tables), sharded by id |
| **TAO cache** | A **read-through, write-through graph cache** in front of MySQL — optimized for `assoc_get`, `assoc_count`, `assoc_range` (e.g. "friends of X", "likes on post Y") |
| **Sharding** | By user/object id; a user's friend list co-located |

```
Common queries (served from TAO cache, not MySQL):
  friends(X)              → assoc_range(X, 'friend')
  mutual(X, Y)            → set intersection of friends(X), friends(Y)
  friend suggestions      → friends-of-friends (2-hop) ranked
  is X friends with Y?    → assoc_get(X, 'friend', Y)
```

- **Read-heavy graph** → the cache absorbs it; MySQL is the durable backing.
- **Friendship is symmetric** → store both directions (or normalize + query both) so "friends of X" is one shard read.

> **Interview line:** "sharded adjacency lists (objects + typed associations) behind a **read-through graph cache (TAO-style)** since friend lists are read on every feed build; sharded by id; mutuals = set intersection; suggestions = 2-hop friends-of-friends."

---

## 6. News Feed Generation

Same **fan-out** trade-off as Twitter (see [Fan-Out / Fan-In](../concepts/fan-out-fan-in.md)), but merging **friends + groups + pages** and heavily **ranked**.

| Model | On post | On read | Trade-off |
| --- | --- | --- | --- |
| **Push (fan-out on write)** | Push post id into friends' feed caches | Read precomputed feed (fast) | Write amplification for high-degree users/pages |
| **Pull (fan-out on read)** | Store the post | Gather + rank friends' recent posts at read | Cheap write; heavy read |
| **Hybrid** ✅ | Push for normal users; **pull for high-degree pages/celebrities** | Merge precomputed + pulled → **rank** | Balances both |

```
Build feed:
  candidates = recent posts from friends + joined groups + followed pages (+ some recs/ads)
  rank by ML relevance (see §7)
  cache the ranked feed (post IDs) per user; hydrate content on read
```

- Store **post ids** in the feed cache; hydrate content from a shared post cache (a viral post cached once).
- Because ranking is heavy, feeds are often **pull + rank at read with cached candidates**, or precomputed candidate sets re-ranked at read.

---

## 7. Feed Ranking Pipeline

Facebook's feed is **relevance-ranked** (historically "EdgeRank", now ML). Treat the model as a black box; know the **pipeline**:

```
1. Candidate generation → all eligible recent posts (friends + groups + pages), filtered by privacy
2. Feature extraction   → affinity (how close you are to the author), post type, recency,
                          engagement so far, your past behavior
3. Scoring (ML model)   → predict p(you engage) → a relevance score per candidate
4. Ranking + diversity  → sort by score, apply diversity/spacing rules, insert ads
5. Cache + paginate     → serve top-N; recompute periodically or at read with cached features
```

- Classic **EdgeRank** intuition: `score ≈ affinity × content_weight × time_decay`.
- Ranking runs on **candidate sets** (bounded), not the whole graph, so it's tractable.

---

## 8. Privacy / Visibility

Every post has a **privacy scope**; visibility is checked **on read** (never leak a restricted post).

| Scope | Who sees it |
| --- | --- |
| PUBLIC | Anyone |
| FRIENDS | Author's friends only |
| CUSTOM | Specific lists / except-people |
| ONLY_ME | Author |

```
canView(viewer, post):
  PUBLIC → yes
  FRIENDS → viewer ∈ friends(author)
  CUSTOM → check allow/deny lists
  + author hasn't blocked viewer
```

- Enforced at **feed build + post fetch**; a blocked user never sees your content.
- Privacy filtering happens **during candidate generation** so restricted posts never enter a feed.

---

## 9. Posts, Reactions & Comments

- **Post** = author + type + content + **privacy scope**; media → blob/CDN (post stores a reference).
- **Reactions** (like/love/haha…) + **comments** → **high write volume** → **async counter aggregation**, cached approximate counts.
- **Comments** = tree (materialized path), lazy-loaded, ranked (top/newest).
- Shares create a new post referencing the original.

---

## 10. Data Model (all tables)

```sql
CREATE TABLE users ( user_id BIGINT PRIMARY KEY, name TEXT, email VARCHAR(255) UNIQUE, created_at TIMESTAMP );

CREATE TABLE friendships (
    user_a BIGINT, user_b BIGINT,
    status VARCHAR(12) NOT NULL,          -- PENDING, ACCEPTED, BLOCKED
    requested_by BIGINT, created_at TIMESTAMP,
    PRIMARY KEY (user_a, user_b)          -- store both directions (or normalize a<b + query both)
);
CREATE INDEX idx_friend_user ON friendships(user_a) WHERE status='ACCEPTED';

CREATE TABLE posts (
    post_id BIGINT PRIMARY KEY,           -- Snowflake (time-sortable)
    author_id BIGINT NOT NULL, type VARCHAR(15), content TEXT, media_ref TEXT,
    privacy VARCHAR(15) DEFAULT 'FRIENDS', audience JSONB,   -- PUBLIC/FRIENDS/CUSTOM/ONLY_ME
    like_count INT DEFAULT 0, comment_count INT DEFAULT 0, share_count INT DEFAULT 0,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_posts_author ON posts(author_id, created_at DESC);

CREATE TABLE reactions ( user_id BIGINT, post_id BIGINT, type VARCHAR(10), created_at TIMESTAMP, PRIMARY KEY (user_id, post_id) );
CREATE TABLE comments (
    comment_id BIGINT PRIMARY KEY, post_id BIGINT, parent_id BIGINT, path TEXT,
    author_id BIGINT, body TEXT, created_at TIMESTAMP
);
CREATE INDEX idx_comments_post ON comments(post_id, path);

CREATE TABLE groups ( group_id BIGINT PRIMARY KEY, name TEXT, privacy VARCHAR(10) );
CREATE TABLE group_members ( group_id BIGINT, user_id BIGINT, role VARCHAR(10), PRIMARY KEY(group_id, user_id) );
CREATE TABLE pages ( page_id BIGINT PRIMARY KEY, name TEXT, owner_id BIGINT, follower_count BIGINT DEFAULT 0 );
CREATE TABLE page_follows ( page_id BIGINT, user_id BIGINT, PRIMARY KEY(page_id, user_id) );
CREATE TABLE blocks ( user_id BIGINT, blocked_id BIGINT, PRIMARY KEY(user_id, blocked_id) );
CREATE TABLE notifications ( notif_id BIGINT PRIMARY KEY, user_id BIGINT, type VARCHAR(30), data JSONB, is_read BOOLEAN DEFAULT FALSE, created_at TIMESTAMP );

-- Feeds precomputed → Redis: feed:{userId} = sorted set of post ids (+ cached features)
-- Media → blob store + CDN; search → Elasticsearch
```

> **Tables to consider:** users, friendships, posts, reactions, comments, groups, group_members, pages, page_follows, blocks, notifications, precomputed feeds (Redis), media_refs, privacy/ACL, search index. Chat = separate messaging system.

---

## 11. API Design

```
POST /v1/friend-requests { toUserId }   · POST /v1/friend-requests/{id}/accept · /decline
POST /v1/users/{id}/block
GET  /v1/feed?cursor=                     # ranked news feed (privacy-filtered)
POST /v1/posts { type, content, privacy, audience }
GET  /v1/posts/{id}/comments?cursor=      · POST /v1/posts/{id}/comments { parentId?, body }
POST /v1/posts/{id}/react { type }
GET  /v1/users/{id}/friends               · GET /v1/users/{id}/mutual/{otherId}
POST /v1/groups/{id}/join                 · GET /v1/notifications
```

---

## 12. Sequences

### Post → feed

```
Author  PostSvc  Kafka  Fan-outWorkers  GraphSvc  Redis(feeds)
  │ post  │        │          │             │          │
  ├──────►│ persist│          │             │          │
  │◄─ id ─┤─ POST_CREATED ───►│             │          │
  │       │        │          ├─ friends(author) (TAO) ►│
  │       │        │          ├─ filter by privacy, ZADD each friend's feed ►│
  │       │        │  (high-degree page → skip; pulled at read)
```

### Read feed (rank at read)

```
User → FeedSvc:
  candidates = pushed feed ids + pull recent posts of followed pages/high-degree
  privacy-filter → extract features → ML rank → hydrate content → paginate
```

---

## 13. Consistency & Edge Cases

| Case | Handling |
| --- | --- |
| Privacy leak | Filter by visibility during candidate generation + on fetch; blocked users excluded |
| High-degree page/celebrity | Skip push; pull at read (hybrid) |
| Friend accepted | Backfill recent posts into feed (or rely on pull) |
| Unfriend/block | Filter on read; rebuild feed async |
| Deleted post | Tombstone; skip on hydrate |
| Counter accuracy | Async aggregation → approximate cached counts |
| Feed freshness | Eventual (a post may appear a few seconds late) |

---

## 14. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Strategy** | Feed fan-out (push/pull/hybrid), ranking model | Swap per user/experiment |
| **Producer-Consumer** | Fan-out + counter aggregation (Kafka) | Absorb + parallelize |
| **Observer / Pub-Sub** | Post/reaction/comment events → feed, notifications, search | Decouple |
| **CQRS + Materialized View** | Precomputed ranked feed vs write model | Fast reads |
| **Graph adjacency + Cache-Aside (TAO)** | Friend graph reads | Edge queries at scale |
| **Composite** | Nested comment trees | Uniform tree ops |
| **Facade** | Feed service over graph + posts + privacy + rank + cache | Simple API |
| **Chain of Responsibility** | Privacy/ACL checks | Composable visibility rules |
| **Decorator** | Post rendering (privacy, tags, attachments) | Compose display |
| **State** | Friend-request lifecycle (PENDING→ACCEPTED/BLOCKED) | Guard transitions |

---

## 15. Scaling & Failure

- **Graph** sharded by id + **TAO-style read-through cache** (friend lists read on every feed build); MySQL durable backing.
- **Feed** = hybrid fan-out + ML ranking on candidate sets; store ids, hydrate content, cache per user.
- **Counters** (reactions/comments) async-aggregated; approximate cached values.
- **Privacy** enforced on read (ACL/Chain) — never leak restricted posts.
- **Media** → blob + CDN; posts store references.
- Eventual consistency for feed/counts; strong only where needed (friendship state).

---

## 16. Interview Cheat Sheet

> **"How is the friend graph stored?"**
> "Sharded objects + typed associations behind a **read-through graph cache (TAO-style)** over MySQL — friend lists are read on every feed build so caching is essential. Sharded by id; mutuals = set intersection; friend suggestions = 2-hop friends-of-friends."

> **"How is the News Feed built and ranked?"**
> "Hybrid fan-out (push for normal friends, pull for high-degree pages), then a ranking pipeline: candidate generation (privacy-filtered) → feature extraction (affinity, recency, engagement) → ML scoring → rank + diversity + ads → cache. Ranking runs on bounded candidate sets."

> **"How is privacy enforced?"**
> "Each post has a scope (public/friends/custom/only-me); visibility is checked during **candidate generation and on fetch**, and blocked users are excluded — restricted posts never enter a feed."

> **"Facebook vs Twitter?"**
> "Bidirectional friend graph + heavy ML ranking merging friends/groups/pages, vs Twitter's unidirectional follows and more chronological feed. Same fan-out trade-offs + high-degree/celebrity problem."

---

## 17. Final Takeaways

- Two hard parts: a **trillion-edge friend graph** (TAO-style read-through cache over sharded MySQL) + a **ranked News Feed**.
- **Feed** = hybrid fan-out + **ML ranking pipeline** (candidates → features → score → rank) on bounded candidate sets; store ids, hydrate, cache.
- **Privacy enforced on read** during candidate generation — never leak restricted posts.
- **Counters async**, **media on CDN**, **comments as trees**.
- Patterns: Strategy (fan-out/rank), Producer-Consumer, CQRS/Materialized View, Graph+Cache-Aside (TAO), Composite, Chain (privacy), Observer.

### Related notes

- [Fan-Out / Fan-In & Celebrity Problem](../concepts/fan-out-fan-in.md) — push/pull/hybrid in depth
- [Twitter / News Feed](twitter-news-feed-system-design.md) · [Instagram](instagram-system-design.md) — sibling feed systems
- [Caching Strategies](../concepts/caching-strategies.md) · [Apache Kafka](../concepts/kafka.md) · [Databases — Deep Dive](../concepts/databases-deep-dive.md)
