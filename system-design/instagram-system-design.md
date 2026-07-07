# Instagram — System Design (Photo/Video Sharing)

> **Core challenge:** upload and serve **photos/videos** fast and globally, build a **follow-based ranked feed** and **Stories**, and support likes/comments/Explore — a blend of **media pipeline + CDN** (like YouTube) and **feed fan-out** (like Twitter). Distinctive parts: **media handling** and the **unidirectional follow graph** with **private accounts**.

---

## Contents

- [1. Mental Model & vs Facebook/Twitter](#1-mental-model--vs-facebooktwitter)
- [2. Requirements](#2-requirements)
- [3. Capacity Estimation](#3-capacity-estimation)
- [4. Architecture](#4-architecture)
- [5. Media Upload & Processing Pipeline](#5-media-upload--processing-pipeline)
- [6. Media Delivery (CDN)](#6-media-delivery-cdn)
- [7. Follow Graph & Feed (fan-out + ranking)](#7-follow-graph--feed-fan-out--ranking)
- [8. Stories (ephemeral)](#8-stories-ephemeral)
- [9. Explore / Discovery](#9-explore--discovery)
- [10. Data Model (all tables)](#10-data-model-all-tables)
- [11. API Design](#11-api-design)
- [12. Sequences](#12-sequences)
- [13. Consistency & Edge Cases](#13-consistency--edge-cases)
- [14. Design Patterns (that can be used)](#14-design-patterns-that-can-be-used)
- [15. Scaling & Failure](#15-scaling--failure)
- [16. Interview Cheat Sheet](#16-interview-cheat-sheet)
- [17. Final Takeaways](#17-final-takeaways)

---

## 1. Mental Model & vs Facebook/Twitter

```
Upload photo → process (resize/thumbnails/transcode) → store in blob + CDN
Follow users → home feed = ranked recent posts from people you follow
Stories = ephemeral (24h) media; Explore = discovery of content you don't follow
```

| | **Instagram** | **Facebook** | **Twitter** |
| --- | --- | --- | --- |
| Graph | **Unidirectional follow** (+ private-account approval) | Bidirectional friends | Unidirectional follow |
| Content | **Photo/video-first** | Mixed | Short text |
| Signature | **Media pipeline + Stories + Explore + Reels** | Groups/Pages | Retweets |

It's **media-heavy** (YouTube-like pipeline + CDN) **plus** a **follow feed** (Twitter-like fan-out). Read-heavy.

---

## 2. Requirements

**Functional**
- Upload **photo/video** posts (caption, filters, location, tags, carousel).
- **Follow/unfollow** (unidirectional; **private accounts** need approval); home **feed**.
- **Likes, comments**; **Stories** (24h ephemeral, view-state); **Explore/Reels** (discovery); DMs (separate chat system).

**Non-functional**
- Fast media upload + **global low-latency delivery** (CDN); read-heavy feed; huge scale; **eventual consistency OK**.

---

## 3. Capacity Estimation

```
Users ~ 2B, DAU ~ 1B · uploads ~ 100M+ posts/day + Stories · views ~ billions/day
Media: avg photo ~ few MB original → store ~4 sizes; video → HLS renditions → 5–10× source
Bandwidth: images/video reads dominate → CDN carries it (origin barely touched)
Feed reads ~ millions/sec → precompute + cache; fan-out: 1 post × avg followers writes
Storage: exabytes of media (blob) + small metadata (DB); Stories auto-expire (24h) → bounded
```

> **Bandwidth (media reads)** is the cost center → CDN. Fan-out write volume + feed read QPS → precompute + cache. DB holds only small metadata.

---

## 4. Architecture

```
Client → API Gateway
  ├── Media Service (upload URL, processing pipeline) → blob store + CDN
  │        └─ media workers (resize/transcode) via Kafka
  ├── Post Service (metadata)                         → sharded DB + cache
  ├── Graph Service (follows, private-approval)       → sharded store + cache
  ├── Fan-out + Feed Service (build/rank/serve)       → Redis feed cache + ML rank
  ├── Story Service (TTL, tray, views)                → fast store + TTL
  ├── Explore/Search (recommendations)               → Elasticsearch + ML
  └── Notification + Counter services
             │
          Kafka (POST_CREATED, MEDIA_READY, LIKE, FOLLOW → fan-out, index, notifications, analytics)
```

---

## 5. Media Upload & Processing Pipeline

The signature part (shares DNA with the Video Streaming note).

```
1. Client → Media Service: request a pre-signed upload URL
2. Client uploads the ORIGINAL directly to blob store (resumable/chunked)
3. Upload-complete event → processing queue (Kafka)
4. Media workers (parallel pipeline):
     photos → resize into sizes (thumb/grid, feed, full), strip EXIF (privacy), generate blurhash placeholder
     video  → transcode to HLS renditions (multiple bitrates), thumbnail/preview
     apply filters if server-side; content-moderation scan
5. Store variants in blob → push to CDN → mark post READY (state machine)
6. Post appears in feeds once READY
```

| Size | Use |
| --- | --- |
| **thumb** | profile grid (tiny) |
| **feed** | in-feed display (medium) |
| **full** | on-tap full view |
| **HLS renditions** | video adaptive bitrate |

- **Parallel + retryable** (Producer-Consumer); a failed variant re-processes.
- **Blurhash/placeholder** → instant blurred preview while the image loads.
- **EXIF stripped** (removes GPS/camera metadata → privacy).

---

## 6. Media Delivery (CDN)

- Variants served from **CDN edge caches** near the viewer → low latency; origin (blob) hit only on miss.
- Serve the **right size per context** (thumb in grid, feed in feed, full on tap) → save bandwidth.
- Immutable media URLs → cache forever (long TTL); **signed URLs** for private content.
- Video uses **adaptive bitrate (HLS)** like the Video Streaming note.

---

## 7. Follow Graph & Feed (fan-out + ranking)

Unidirectional **follow** graph + **fan-out** feed (same trade-offs as Twitter; see [Fan-Out / Fan-In](../concepts/fan-out-fan-in.md)).

| Model | Trade-off |
| --- | --- |
| **Push (fan-out on write)** | Push post id into followers' feeds → fast reads; **celebrity write amplification** |
| **Pull (fan-out on read)** | Gather followees' recent posts at read → cheap write, heavier read |
| **Hybrid** ✅ | Push for normal accounts; **pull for celebrities** (100M+ followers); merge → rank |

```
Build feed:
  candidates = recent posts from followees (pushed) + pulled celebrity posts (+ some recs/ads)
  rank by ML (recency, engagement, affinity, media type) → cache post ids → hydrate media URLs
```

- Store **post ids** in the feed cache (Redis sorted set); hydrate **media variant URLs** + counts on read.
- **Private-account posts** only enter followers' feeds if the follow is `ACCEPTED`.

---

## 8. Stories (ephemeral)

Ephemeral media that expires after 24h.

```
Story = media with expires_at = created_at + 24h
  "stories tray" = unexpired stories from people you follow (assembled at read, cached briefly)
  view-state = who viewed which story → drives the seen/unseen ring + "viewers" list
```

| Concern | Approach |
| --- | --- |
| **Auto-expiry** | `expires_at` + TTL on cache; a sweeper purges expired media |
| **Tray assembly** | Fetch followees' active stories (index `WHERE expires_at > now()`), group by author, order by unseen-first |
| **View-state at scale** | High write volume, short-lived → fast store (Cassandra/Redis) keyed by (story, viewer); expire with the story |

- View-state writes are huge (every view of every story) but **ephemeral** → cheap store + TTL.

---

## 9. Explore / Discovery

- Content from accounts you **don't** follow, ranked by interest (ML recommendations) — the growth engine.
- **Candidate generation** (trending, similar-to-liked, topics/hashtags, accounts like ones you follow) → **ML ranking** → cache.
- Backed by a search/recommendation pipeline (Elasticsearch + embedding similarity + engagement signals), rebuilt continuously.

---

## 10. Data Model (all tables)

```sql
CREATE TABLE users ( user_id BIGINT PRIMARY KEY, username VARCHAR(50) UNIQUE, name TEXT,
                     bio TEXT, is_private BOOLEAN DEFAULT FALSE, follower_count BIGINT DEFAULT 0 );

CREATE TABLE follows (
    follower_id BIGINT, followee_id BIGINT, created_at TIMESTAMP,
    status VARCHAR(10) DEFAULT 'ACCEPTED',   -- PENDING for private accounts (needs approval)
    PRIMARY KEY (follower_id, followee_id)
);
CREATE INDEX idx_follows_followee ON follows(followee_id) WHERE status='ACCEPTED';  -- fan-out

CREATE TABLE posts (
    post_id BIGINT PRIMARY KEY,              -- Snowflake
    author_id BIGINT NOT NULL, caption TEXT, location TEXT,
    type VARCHAR(10),                        -- PHOTO, VIDEO, CAROUSEL, REEL
    status VARCHAR(10) DEFAULT 'PROCESSING', -- PROCESSING, READY, FAILED
    like_count INT DEFAULT 0, comment_count INT DEFAULT 0,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_posts_author ON posts(author_id, created_at DESC);

CREATE TABLE post_media (                     -- one post → many items (carousel) → many sizes
    media_id BIGINT PRIMARY KEY, post_id BIGINT, seq INT,
    type VARCHAR(10), variants JSONB, blurhash TEXT   -- { thumb, feed, full, hls } → blob/CDN URLs
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

-- Feeds precomputed → Redis: feed:{userId} = sorted set of post ids; stories:tray:{userId} cached
-- Media bytes → blob store + CDN
```

> **Tables to consider:** users, follows, posts, post_media, likes, comments, stories, story_views, hashtags, post_hashtags, notifications, precomputed feeds (Redis), explore/search index (ES). DMs = separate chat system.

---

## 11. API Design

```
POST /v1/media/upload-url                    → pre-signed upload URL
POST /v1/posts { mediaIds, caption, tags }   → creates post (PROCESSING → READY)
GET  /v1/feed?cursor=                          # home feed
POST /v1/users/{id}/follow                     · DELETE /v1/users/{id}/follow
POST /v1/follow-requests/{id}/approve          # private accounts
POST /v1/posts/{id}/like                       · POST /v1/posts/{id}/comments
GET  /v1/users/{id}                            # profile + grid
POST /v1/stories { mediaRef }                  · GET /v1/stories/tray · POST /v1/stories/{id}/view
GET  /v1/explore
```

---

## 12. Sequences

### Upload → post ready

```
Client  MediaSvc  Blob  Kafka  Workers  PostSvc  Fan-out
  │ upload-url │     │     │       │        │        │
  ├───────────►│ pre-signed URL   │        │        │
  ├─ PUT original ──►│     │       │        │        │
  │            ├─ MEDIA_UPLOADED ─►│        │        │
  │            │     │     │ ├─ resize/transcode variants → blob → CDN
  │            │     │     │ ├─ MEDIA_READY ─────────►│ post=READY
  │            │     │     │        │        ├─ POST_CREATED → fan-out to followers' feeds
```

### Feed read

```
User → FeedSvc: read feed:{me} (pushed ids) + pull celebrity posts → rank →
       hydrate post_media variant URLs + counts → paginate
```

---

## 13. Consistency & Edge Cases

| Case | Handling |
| --- | --- |
| Post not ready | `status=PROCESSING` until media done; excluded from feeds until READY |
| Private account | Follow is `PENDING` until approved; posts only fan out to `ACCEPTED` followers |
| Celebrity | Skip push; pull at read (hybrid) |
| Deleted post | Tombstone; skip on hydrate; remove media async |
| Story expiry | `expires_at` + TTL; sweeper purges; view-state expires with it |
| Counter accuracy | Async aggregation → approximate cached counts |
| Media processing failure | Retry; `status=FAILED` after max attempts + notify |
| Feed freshness | Eventual (a post may appear a few seconds late) |

---

## 14. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Pipeline** | Media processing (resize→transcode→variants→publish) | Composable stages |
| **Producer-Consumer** | Media workers + fan-out + counter aggregation (Kafka) | Parallel scale |
| **Strategy** | Feed fan-out (push/pull/hybrid), ranking, explore recs | Swap algorithms |
| **State** | Post status (PROCESSING→READY→FAILED) | Guarded lifecycle |
| **Observer / Pub-Sub** | Post/like/media events → feed, notifications, index | Decouple |
| **CQRS + Materialized View** | Precomputed feed vs write model | Fast reads |
| **CDN / Cache-Aside** | Media + feed caching | Bandwidth + latency |
| **TTL / Expiry** | Stories (24h) + view-state | Auto-cleanup ephemeral content |
| **Facade** | Feed service over graph + posts + media + rank | Simple API |
| **Repository** | Data access | Testable |

---

## 15. Scaling & Failure

- **Media** → blob + **CDN** (carries read bandwidth); multiple sizes save bandwidth; parallel + retryable processing.
- **Feed** = hybrid fan-out + rank; celebrities pulled; store ids, hydrate media URLs.
- **Follow graph** sharded by user; `follows(followee)` (ACCEPTED) drives fan-out.
- **Stories** auto-expire via TTL; view-state high-volume + short-lived (ephemeral store).
- **Counters** async-aggregated; approximate cached.
- **Private accounts** → follow requests (PENDING) + ACL on read.
- Post shows `PROCESSING` until media ready (state machine); failed media retried.

---

## 16. Interview Cheat Sheet

> **"How do you handle photo/video upload and delivery?"**
> "Client uploads the original to blob via a pre-signed URL; an event triggers async workers that generate multiple sizes/thumbnails (strip EXIF, blurhash) and HLS for video, store variants in blob, push to **CDN**, and mark the post READY. Viewers fetch the right size from the nearest edge; the DB holds only small metadata."

> **"How is the feed built?"**
> "Hybrid fan-out over the **follow** graph — push post ids into followers' Redis feeds for normal accounts, pull for celebrities, then rank (recency/engagement/affinity) and hydrate media URLs. Store ids, not media. Private accounts only fan out to approved followers."

> **"How do Stories work at scale?"**
> "Ephemeral media with a 24h `expires_at` + TTL; the tray shows unexpired followees' stories (unseen-first); view-state (who viewed) is huge but short-lived, kept in a fast store that expires with the story."

> **"Instagram vs Facebook/Twitter?"**
> "Unidirectional **follow** graph (like Twitter) with private-account approval, but **media-first** — a media pipeline + CDN (like YouTube) combined with fan-out feed, plus ephemeral Stories and ML-driven Explore."

---

## 17. Final Takeaways

- Instagram = **media pipeline + CDN** (YouTube-like) **+ follow-feed fan-out** (Twitter-like).
- **Upload → async process (sizes/HLS, EXIF-strip, blurhash) → blob + CDN**; DB holds only metadata + references.
- **Feed** = hybrid fan-out over follow graph + ML ranking; store ids, hydrate media URLs; private accounts gated by approval.
- **Stories** = 24h TTL/ephemeral + huge short-lived view-state; **Explore** = ML recs; **counters async**.
- **Post state machine** (PROCESSING→READY) hides posts until media is ready.
- Patterns: Pipeline, Producer-Consumer, Strategy (fan-out/rank), State, CDN/Cache-Aside, TTL, CQRS.

### Related notes

- [Fan-Out / Fan-In & Celebrity Problem](../concepts/fan-out-fan-in.md) — feed fan-out in depth
- [Video Streaming](video-streaming-system-design.md) — media pipeline/CDN/HLS overlap
- [Twitter / News Feed](twitter-news-feed-system-design.md) · [Facebook](facebook-system-design.md) — feed fan-out siblings
- [Caching Strategies](../concepts/caching-strategies.md) · [Apache Kafka](../concepts/kafka.md)
