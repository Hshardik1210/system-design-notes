# Instagram — System Design (Photo/Video Sharing)

> **Core challenge:** upload and serve **photos/videos** fast and globally, build a **follow-based feed** and **Stories**, and support likes/comments/explore — a blend of **media pipeline + CDN** (like YouTube) and **feed fan-out** (like Twitter). The distinctive parts are the **media handling** and the **unidirectional follow graph**.

---

## Contents

- [1. Mental Model & vs Facebook/Twitter](#1-mental-model--vs-facebooktwitter)
- [2. Requirements](#2-requirements)
- [3. Media Upload & Delivery](#3-media-upload--delivery)
- [4. Follow Graph & Feed](#4-follow-graph--feed)
- [5. Stories](#5-stories)
- [6. Explore / Discovery](#6-explore--discovery)
- [7. Data Model (all tables)](#7-data-model-all-tables)
- [8. API Design](#8-api-design)
- [9. Architecture](#9-architecture)
- [10. Design Patterns (that can be used)](#10-design-patterns-that-can-be-used)
- [11. Scaling & Failure](#11-scaling--failure)
- [12. Interview Cheat Sheet](#12-interview-cheat-sheet)
- [13. Final Takeaways](#13-final-takeaways)

---

## 1. Mental Model & vs Facebook/Twitter

```
Upload photo → process (resize/thumbnails/transcode) → store in blob + CDN
Follow users → home feed = recent posts from people you follow, ranked
Stories = ephemeral (24h) media; Explore = discovery of content you don't follow
```

| | **Instagram** | **Facebook** | **Twitter** |
| --- | --- | --- | --- |
| Graph | **Unidirectional follow** | Bidirectional friends | Unidirectional follow |
| Content | **Photo/video-first** | Mixed | Short text |
| Signature | **Media pipeline + Stories + Explore** | Groups/Pages | Retweets |

It's **media-heavy** (YouTube-like pipeline + CDN) **plus** a **follow feed** (Twitter-like fan-out). Read-heavy.

---

## 2. Requirements

**Functional**
- Upload **photo/video** posts (with caption, filters, location, tags).
- **Follow/unfollow** (unidirectional); home **feed** of followees' posts.
- **Likes, comments**; **Stories** (24h ephemeral); **Explore** (discover non-followed content); DMs (separate chat system).

**Non-functional**
- Fast media upload + **global low-latency delivery** (CDN); read-heavy feed; huge scale; eventual consistency OK.

---

## 3. Media Upload & Delivery

The media pipeline is central (like the Video Streaming note).

```
1. Client requests upload URL → uploads original to blob store (resumable, pre-signed URL)
2. Upload event → processing queue (Kafka)
3. Media workers: resize into multiple sizes (thumb/feed/full), transcode video (HLS),
   strip EXIF, generate blurhash/placeholder → store variants in blob
4. Distribute to CDN; mark post READY
5. Viewers fetch the right size from the nearest CDN edge
```

| Concern | Approach |
| --- | --- |
| Storage | **Blob/object store (S3)** for originals + variants |
| Delivery | **CDN** edge caches — serves the vast majority of reads |
| Processing | Async worker fleet (Producer-Consumer); parallel per image/video |
| Multiple sizes | Serve thumbnail in grid, medium in feed, full on tap → save bandwidth |

> Metadata (post, caption, counts) is small and lives in the DB; the **bytes live in blob + CDN** and never touch the DB.

---

## 4. Follow Graph & Feed

Unidirectional **follow** graph + **fan-out** feed (same trade-offs as Twitter).

```
follow(follower_id, followee_id)   -- one direction

Home feed = recent posts from people you follow, ranked (recency + engagement + affinity)
```

| Model | Trade-off |
| --- | --- |
| **Fan-out on write (push)** | Push post id into followers' feeds → fast reads; **celebrity write amplification** |
| **Fan-out on read (pull)** | Gather followees' recent posts at read → cheap write, heavier read |
| **Hybrid** ✅ | Push for normal accounts; **pull for celebrities** (millions of followers); merge + rank |

- Store **post ids** in the feed cache (Redis), hydrate media URLs + metadata.
- Ranking = ML (recency, engagement, affinity); treat as a black box.

---

## 5. Stories

Ephemeral media that expires after 24h.

```
Story = media with a TTL (24h)
  stored like posts but auto-expire (TTL on the record / cache)
  "stories tray" = recent unexpired stories from people you follow
  view state tracked (who viewed whom) → seen/unseen ring
```

- Use **TTL** (DB expiry job + cache TTL) so stories disappear automatically.
- Ephemeral view-state (who saw it) in a fast store; high write volume, short-lived.

---

## 6. Explore / Discovery

- Content from accounts you **don't** follow, ranked by interest (ML recommendations).
- Candidate generation (popular/trending, similar-to-liked, topics) → ranking → cache.
- Backed by a search/recommendation pipeline (Elasticsearch + ML), rebuilt from engagement signals.

---

## 7. Data Model (all tables)

```sql
CREATE TABLE users ( user_id BIGINT PRIMARY KEY, username VARCHAR(50) UNIQUE, name TEXT,
                     bio TEXT, is_private BOOLEAN DEFAULT FALSE, follower_count BIGINT DEFAULT 0 );

CREATE TABLE follows (
    follower_id BIGINT, followee_id BIGINT, created_at TIMESTAMP,
    status VARCHAR(10) DEFAULT 'ACCEPTED',   -- PENDING for private accounts
    PRIMARY KEY (follower_id, followee_id)
);
CREATE INDEX idx_follows_followee ON follows(followee_id);   -- who follows X (fan-out)

CREATE TABLE posts (
    post_id BIGINT PRIMARY KEY,              -- Snowflake
    author_id BIGINT NOT NULL, caption TEXT, location TEXT,
    type VARCHAR(10),                        -- PHOTO, VIDEO, CAROUSEL
    status VARCHAR(10) DEFAULT 'PROCESSING', -- PROCESSING, READY
    like_count INT DEFAULT 0, comment_count INT DEFAULT 0,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_posts_author ON posts(author_id, created_at DESC);

CREATE TABLE post_media (                     -- one post → many media items (carousel) → many sizes
    media_id BIGINT PRIMARY KEY, post_id BIGINT, seq INT,
    type VARCHAR(10), variants JSONB          -- { thumb, feed, full, hls } → blob/CDN URLs
);

CREATE TABLE likes ( user_id BIGINT, post_id BIGINT, created_at TIMESTAMP, PRIMARY KEY(user_id, post_id) );
CREATE TABLE comments ( comment_id BIGINT PRIMARY KEY, post_id BIGINT, author_id BIGINT, body TEXT, created_at TIMESTAMP );

CREATE TABLE stories (
    story_id BIGINT PRIMARY KEY, author_id BIGINT, media_ref TEXT,
    created_at TIMESTAMP, expires_at TIMESTAMP     -- created_at + 24h
);
CREATE INDEX idx_stories_active ON stories(author_id, expires_at) WHERE expires_at > now();
CREATE TABLE story_views ( story_id BIGINT, viewer_id BIGINT, viewed_at TIMESTAMP, PRIMARY KEY(story_id, viewer_id) );

CREATE TABLE hashtags ( tag VARCHAR(100) PRIMARY KEY, post_count BIGINT );
CREATE TABLE post_hashtags ( post_id BIGINT, tag VARCHAR(100), PRIMARY KEY(post_id, tag) );
CREATE TABLE notifications ( notif_id BIGINT PRIMARY KEY, user_id BIGINT, type VARCHAR(30), data JSONB, is_read BOOLEAN, created_at TIMESTAMP );

-- Feeds precomputed → Redis: feed:{userId} = sorted set of post ids; stories tray cached
-- Media bytes → blob store + CDN
```

> **Tables to consider:** users, follows, posts, post_media, likes, comments, stories, story_views, hashtags, post_hashtags, notifications, precomputed feeds (Redis), explore/search index (ES). DMs = separate chat system.

---

## 8. API Design

```
POST /v1/media/upload-url                    → pre-signed upload URL
POST /v1/posts { mediaIds, caption, tags }
GET  /v1/feed?cursor=                          # home feed
POST /v1/users/{id}/follow                     · DELETE /v1/users/{id}/follow
POST /v1/posts/{id}/like                       · POST /v1/posts/{id}/comments
GET  /v1/users/{id}                            # profile + grid
POST /v1/stories { mediaRef }                  · GET /v1/stories/tray
GET  /v1/explore                               # discovery
```

---

## 9. Architecture

```
Client → API Gateway
  ├── Media Service (upload URL, processing pipeline)  → blob store + CDN
  │        └─ media workers (resize/transcode) via Kafka
  ├── Post Service (metadata)                          → sharded DB + cache
  ├── Graph Service (follows, suggestions)             → sharded store + cache
  ├── Feed Service (fan-out + rank + serve)            → Redis feed cache + ML rank
  ├── Story Service (TTL, tray, views)                 → fast store + TTL
  ├── Explore/Search (recommendations)                 → Elasticsearch + ML
  └── Notification Service
             │
          Kafka (post events → fan-out, media done, notifications, analytics)
```

---

## 10. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Pipeline** | Media processing (resize→transcode→variants→publish) | Composable stages |
| **Producer-Consumer** | Media workers + fan-out + counter aggregation (Kafka) | Parallel scale |
| **Strategy** | Feed fan-out (push/pull/hybrid), ranking, explore recs | Swap algorithms |
| **State** | Post status (PROCESSING→READY) | Guarded lifecycle |
| **Observer / Pub-Sub** | Post/like events → feed, notifications, index | Decouple |
| **CQRS + Materialized View** | Precomputed feed vs write model | Fast reads |
| **CDN / Cache-Aside** | Media + feed caching | Bandwidth + latency |
| **TTL / Expiry** | Stories (24h) | Auto-cleanup ephemeral content |
| **Facade** | Feed service over graph + posts + media + rank | Simple API |
| **Repository** | Data access | Testable |

---

## 11. Scaling & Failure

- **Media** → blob + **CDN** (carries the read bandwidth); multiple sizes save bandwidth; processing is parallel + retryable.
- **Feed** = hybrid fan-out + rank; celebrities pulled; store ids + hydrate.
- **Follow graph** sharded by user; `follows(followee)` index drives fan-out.
- **Stories** auto-expire via TTL; view-state high-volume + short-lived.
- **Counters** async-aggregated; approximate cached.
- **Private accounts** → follow requests (PENDING) + ACL checks on read.
- Post shows `PROCESSING` until media ready (state machine).

---

## 12. Interview Cheat Sheet

> **"How do you handle photo/video upload and delivery?"**
> "Client uploads the original to blob storage via a pre-signed URL; an event triggers async workers that generate multiple sizes/thumbnails (and HLS for video), stored in blob and pushed to a **CDN**. Viewers fetch the right size from the nearest edge; the DB only holds small metadata."

> **"How is the feed built?"**
> "Hybrid fan-out over the **follow** graph — push post ids into followers' Redis feeds for normal accounts, pull for celebrities, then rank (recency/engagement/affinity) and hydrate media URLs. Store ids, not media."

> **"How do Stories work?"**
> "Ephemeral media with a 24h **TTL** (expiry job + cache TTL); the tray shows unexpired stories from people you follow; view-state (seen/unseen) tracked in a fast store."

> **"Instagram vs Facebook/Twitter?"**
> "Unidirectional **follow** graph (like Twitter), but **media-first** — so it's a media pipeline + CDN (like YouTube) combined with fan-out feed. Plus Stories (ephemeral) and Explore (recommendations)."

---

## 13. Final Takeaways

- Instagram = **media pipeline + CDN** (YouTube-like) **+ follow-feed fan-out** (Twitter-like).
- **Upload → async process (multiple sizes/HLS) → blob + CDN**; DB holds only metadata + references.
- **Feed** = hybrid fan-out over the follow graph + ML ranking; store ids, hydrate media.
- **Stories** = TTL/ephemeral; **Explore** = recommendations; **counters async**; **private accounts** = follow requests + ACL.
- Patterns: Pipeline, Producer-Consumer, Strategy (fan-out/rank), State (post processing), CDN/Cache-Aside, TTL, CQRS.

### Related notes

- [Fan-Out / Fan-In & Celebrity Problem](../concepts/fan-out-fan-in.md) — feed fan-out push/pull/hybrid in depth
- [Video Streaming — System Design](video-streaming-system-design.md) — media pipeline/CDN overlap
- [Twitter / News Feed](twitter-news-feed-system-design.md) · [Facebook — System Design](facebook-system-design.md) — feed fan-out siblings
- [Caching Strategies](../concepts/caching-strategies.md) · [Apache Kafka](../concepts/kafka.md)
