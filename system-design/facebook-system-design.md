# Facebook — System Design (Social Network)

> **Core challenge:** model a huge **bidirectional social graph** (friends), generate a ranked **News Feed** from friends' activity, and support posts, likes, comments, groups, and notifications — read-heavy, at billions of users. The distinctive parts vs Twitter are the **friend graph (mutual)** and **rich feed ranking**.

---

## Contents

- [1. Mental Model & vs Twitter/Instagram](#1-mental-model--vs-twitterinstagram)
- [2. Requirements](#2-requirements)
- [3. The Social Graph](#3-the-social-graph)
- [4. News Feed Generation](#4-news-feed-generation)
- [5. Posts, Likes & Comments](#5-posts-likes--comments)
- [6. Data Model (all tables)](#6-data-model-all-tables)
- [7. API Design](#7-api-design)
- [8. Architecture](#8-architecture)
- [9. Design Patterns (that can be used)](#9-design-patterns-that-can-be-used)
- [10. Scaling & Failure](#10-scaling--failure)
- [11. Interview Cheat Sheet](#11-interview-cheat-sheet)
- [12. Final Takeaways](#12-final-takeaways)

---

## 1. Mental Model & vs Twitter/Instagram

```
Friends (mutual) → post activity → your News Feed = ranked merge of friends' + groups' + pages' posts
```

| | **Facebook** | **Twitter** | **Instagram** |
| --- | --- | --- | --- |
| Graph | **Bidirectional** (friend request → accept) | Unidirectional (follow) | Unidirectional (follow) |
| Content | Mixed (text/photo/video/links/life events) | Short text | Photo/video-first |
| Feed | Heavily **ranked** (relevance) | Chrono or ranked | Ranked + Stories |
| Extra | Groups, Pages, Messenger, Events | Retweets | Stories, Reels, Explore |

Read-heavy; the two hard parts are the **friend graph at scale** and **feed generation + ranking**.

---

## 2. Requirements

**Functional**
- Send/accept **friend requests** (bidirectional); unfriend/block.
- Create **posts** (text/photo/video/link); **like**, **comment**, **share**.
- **News Feed** ranked from friends/groups/pages you follow.
- Groups, Pages, notifications, search. (Messenger = separate chat system.)

**Non-functional**
- Read-heavy (reads ≫ writes); low-latency feed; huge scale (billions); eventual consistency OK for feed.

---

## 3. The Social Graph

The friend graph is the foundation — billions of nodes (users) and edges (friendships).

| Concern | Approach |
| --- | --- |
| **Storage** | A **graph** structure: adjacency lists in a sharded store (or a dedicated graph DB / TAO-style layer). Edge = `(user_a, user_b)` — friendship is symmetric (store both directions or query both) |
| **Sharding** | By `user_id`; a user's friend list on their shard |
| **Hot queries** | "friends of X", "mutual friends", "friend suggestions" (friends-of-friends) |
| **Caching** | Friend lists cached (Redis) — read on every feed build |

```
friendship(user_a, user_b, status)  -- PENDING (request) → ACCEPTED
-- symmetric: querying "friends of X" hits both a=X and b=X (or store both rows)
```

> Facebook's real system uses **TAO** (a graph-aware cache over sharded MySQL) — for an interview, say "graph stored as sharded adjacency lists with a caching layer optimized for edge/association queries."

---

## 4. News Feed Generation

Same **fan-out** trade-off as Twitter, but merging **friends + groups + pages** and heavily **ranked**.

| Model | On post | On read | Trade-off |
| --- | --- | --- | --- |
| **Fan-out on write (push)** | Push post into friends' feed caches | Read precomputed feed (fast) | Write amplification for users with many friends |
| **Fan-out on read (pull)** | Store the post | Gather friends' recent posts + rank at read | Cheap write; heavy read |
| **Hybrid** ✅ | Push for most; pull for very-high-degree nodes/pages | Merge precomputed + pulled, then **rank** | Balances both |

```
Build feed:
  candidates = recent posts from friends + joined groups + followed pages
  rank by relevance model (affinity, recency, engagement, content type)   # EdgeRank-style
  cache the ranked feed (list of post ids) per user; hydrate post content
```

- Store **post ids** in the feed cache; hydrate content from a shared post cache.
- Ranking is ML (affinity × weight × time-decay) — treat as a black box; emphasize candidate generation → ranking → cache.

---

## 5. Posts, Likes & Comments

- **Post** belongs to an author, has type + content + privacy scope (public/friends/custom).
- **Likes/reactions** and **comments** (nested) → high write volume → **async counter aggregation**, cached counts.
- **Comments** = tree (materialized path / adjacency list), lazy-loaded.
- Privacy checks on read (can this viewer see this post?).

---

## 6. Data Model (all tables)

```sql
CREATE TABLE users ( user_id BIGINT PRIMARY KEY, name TEXT, email VARCHAR(255) UNIQUE,
                     created_at TIMESTAMP );

CREATE TABLE friendships (
    user_a BIGINT, user_b BIGINT,
    status VARCHAR(12) NOT NULL,          -- PENDING, ACCEPTED, BLOCKED
    requested_by BIGINT, created_at TIMESTAMP,
    PRIMARY KEY (user_a, user_b)          -- store both directions or normalize a<b + query both
);
CREATE INDEX idx_friend_user ON friendships(user_a) WHERE status='ACCEPTED';

CREATE TABLE posts (
    post_id BIGINT PRIMARY KEY,           -- Snowflake (time-sortable)
    author_id BIGINT NOT NULL, type VARCHAR(15),  -- TEXT, PHOTO, VIDEO, LINK
    content TEXT, media_ref TEXT,
    privacy VARCHAR(15) DEFAULT 'FRIENDS', -- PUBLIC, FRIENDS, CUSTOM
    like_count INT DEFAULT 0, comment_count INT DEFAULT 0,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_posts_author ON posts(author_id, created_at DESC);

CREATE TABLE reactions (                   -- like/love/haha...
    user_id BIGINT, post_id BIGINT, type VARCHAR(10), created_at TIMESTAMP,
    PRIMARY KEY (user_id, post_id)
);
CREATE TABLE comments (
    comment_id BIGINT PRIMARY KEY, post_id BIGINT, parent_id BIGINT, path TEXT,
    author_id BIGINT, body TEXT, created_at TIMESTAMP
);
CREATE INDEX idx_comments_post ON comments(post_id, path);

CREATE TABLE groups ( group_id BIGINT PRIMARY KEY, name TEXT, privacy VARCHAR(10) );
CREATE TABLE group_members ( group_id BIGINT, user_id BIGINT, role VARCHAR(10), PRIMARY KEY(group_id, user_id) );
CREATE TABLE pages ( page_id BIGINT PRIMARY KEY, name TEXT, owner_id BIGINT );
CREATE TABLE page_follows ( page_id BIGINT, user_id BIGINT, PRIMARY KEY(page_id, user_id) );
CREATE TABLE notifications ( notif_id BIGINT PRIMARY KEY, user_id BIGINT, type VARCHAR(30), data JSONB, is_read BOOLEAN DEFAULT FALSE, created_at TIMESTAMP );

-- Feeds precomputed → Redis:  feed:{userId} = sorted set of post ids
-- Media → blob store + CDN
```

> **Tables to consider:** users, friendships, posts, reactions, comments, groups, group_members, pages, page_follows, notifications, precomputed feeds (Redis), media_refs, privacy/ACL. Chat lives in a separate messaging system.

---

## 7. API Design

```
POST /v1/friend-requests { toUserId }      · POST /v1/friend-requests/{id}/accept
GET  /v1/feed?cursor=                        # ranked news feed
POST /v1/posts { type, content, privacy }
GET  /v1/posts/{id}/comments?cursor=         · POST /v1/posts/{id}/comments { parentId?, body }
POST /v1/posts/{id}/react { type }
GET  /v1/users/{id}/friends                  · GET /v1/users/{id}/mutual/{otherId}
POST /v1/groups/{id}/join                    · GET /v1/notifications
```

---

## 8. Architecture

```
Client → API Gateway
  ├── Graph Service (friendships, suggestions)   → sharded graph store + cache (TAO-style)
  ├── Post Service (create/read posts)           → sharded SQL/NoSQL + cache
  ├── Feed Service (build/rank/serve feed)        → Redis feed cache + ranking (ML)
  ├── Comment/Reaction Service                    → async counters
  ├── Notification Service                        → (see Notification note)
  └── Media Service                               → blob store + CDN
             │
          Kafka (post events → fan-out, indexing, notifications, analytics)
```

---

## 9. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Strategy** | Feed fan-out (push/pull/hybrid), ranking model | Swap per user/experiment |
| **Producer-Consumer** | Fan-out + counter aggregation via Kafka | Absorb + parallelize |
| **Observer / Pub-Sub** | Post/like/comment events → feed, notifications, search | Decouple |
| **CQRS + Materialized View** | Precomputed ranked feed vs write model | Fast reads |
| **Graph adjacency + Cache-Aside** | Friend graph reads | Edge queries at scale |
| **Composite** | Nested comment trees | Uniform tree ops |
| **Facade** | Feed service over graph + posts + rank + cache | Simple API |
| **Decorator** | Post rendering (privacy, tags, attachments) | Compose display |
| **Repository** | Data access | Testable |
| **State** | Friend-request lifecycle (PENDING→ACCEPTED/BLOCKED) | Guard transitions |

---

## 10. Scaling & Failure

- **Graph** sharded by user + heavy caching (friend lists read on every feed build).
- **Feed** = hybrid fan-out + ranking; store ids, hydrate content; cache per user.
- **Counters** (likes/comments) async-aggregated; approximate cached values.
- **Media** → blob + CDN; posts store references.
- **Privacy** enforced on read (ACL check) — never leak restricted posts.
- Eventual consistency for feed/counts; strong only where needed.

---

## 11. Interview Cheat Sheet

> **"How is the friend graph stored?"**
> "Sharded adjacency lists (edges `user_a↔user_b`), symmetric, with a graph-aware cache (TAO-style) since friend lists are read on every feed build. Sharded by user_id; mutual-friends = set intersection."

> **"How is the News Feed built?"**
> "Hybrid fan-out: push posts into friends' feed caches for most users, pull for very-high-degree nodes/pages, then **rank** candidates by an ML relevance model (affinity, recency, engagement) and cache the ranked id list; hydrate content from a shared cache."

> **"Facebook vs Twitter feed?"**
> "Facebook's graph is **bidirectional** (friends) and the feed merges friends + groups + pages with heavy relevance ranking; Twitter is unidirectional follows, often more chronological. Same fan-out trade-offs and celebrity/high-degree problem."

> **"Likes/comments at scale?"**
> "Async counter aggregation (Kafka), cached approximate counts; comments as a tree (materialized path), lazy-loaded."

---

## 12. Final Takeaways

- Two hard parts: **bidirectional friend graph at scale** + **ranked News Feed**.
- **Graph** = sharded adjacency lists + graph-aware cache (TAO-style).
- **Feed** = hybrid fan-out + ML ranking; store post ids, hydrate content, cache per user.
- **Counters async**, **media on CDN**, **privacy enforced on read**.
- Patterns: Strategy (fan-out/rank), Producer-Consumer, CQRS/Materialized View, Composite (comments), Cache-Aside, Observer.

### Related notes

- [Fan-Out / Fan-In & Celebrity Problem](../concepts/fan-out-fan-in.md) — push/pull/hybrid feed fan-out in depth
- [Twitter / News Feed — System Design](twitter-news-feed-system-design.md) · [Instagram — System Design](instagram-system-design.md) — sibling feed systems
- [Caching Strategies](../concepts/caching-strategies.md) · [Apache Kafka](../concepts/kafka.md) · [Consistent Hashing](../concepts/consistent-hashing.md)
