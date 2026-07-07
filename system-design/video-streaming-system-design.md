# Video Streaming — System Design (YouTube / Netflix)

> **Core challenge:** ingest huge video files, **transcode** them into many resolutions/formats, and **stream** them to millions of viewers **smoothly** (adaptive bitrate) with **low startup latency** — dominated by **storage + CDN + transcoding**, not database logic.

---

## Contents

- [1. Mental Model](#1-mental-model)
- [2. Requirements](#2-requirements)
- [3. Upload & Transcoding Pipeline](#3-upload--transcoding-pipeline)
- [4. Storage](#4-storage)
- [5. Streaming & Adaptive Bitrate](#5-streaming--adaptive-bitrate)
- [6. CDN — The Delivery Backbone](#6-cdn--the-delivery-backbone)
- [7. Metadata, Search & Recommendations](#7-metadata-search--recommendations)
- [8. Data Model (all tables)](#8-data-model-all-tables)
- [9. Design Patterns (that can be used)](#9-design-patterns-that-can-be-used)
- [10. Scaling & Failure](#10-scaling--failure)
- [11. Interview Cheat Sheet](#11-interview-cheat-sheet)
- [12. Final Takeaways](#12-final-takeaways)

---

## 1. Mental Model

```
Creator uploads video → transcode into multiple resolutions + chunk into segments
   → store in blob + push to CDN → viewer's player fetches segments (adaptive bitrate) from nearest edge
```

The "database" is small (metadata); the real system is a **media processing pipeline + global CDN**.

> **YouTube vs Netflix:** YouTube = **UGC** (millions of uploads, transcode at scale, long tail). Netflix = **curated catalog** (fewer titles, pre-encoded in many profiles, aggressive CDN pre-positioning). Same core: transcode + chunk + CDN + adaptive streaming.

---

## 2. Requirements

**Functional**
- Upload video; transcode to multiple resolutions (240p→4K) & codecs.
- Stream with **adaptive bitrate** (quality adjusts to bandwidth); seek, resume.
- Browse/search catalog; recommendations; view counts, likes, comments.

**Non-functional**
- **Low startup latency + no buffering**; massive **read/bandwidth** scale; durable storage; global reach.

---

## 3. Upload & Transcoding Pipeline

```
1. Client requests upload URL → uploads raw file to blob store (resumable, chunked)
2. Upload event → transcoding queue (Kafka)
3. Transcoding workers:
     - split video into short SEGMENTS (e.g. 2–10s)
     - encode each into MULTIPLE resolutions/bitrates + codecs (H.264/H.265/VP9/AV1)
     - generate a MANIFEST (HLS .m3u8 / DASH .mpd) listing segments per quality
4. Store outputs in blob → distribute to CDN
5. Mark video READY; notify creator
```

- **Transcoding is parallel & embarrassingly scalable** — segment-level jobs across a worker fleet (fan-out on write).
- **Pipeline stages**: validate → segment → encode (per profile) → package → thumbnail → publish.

---

## 4. Storage

| Data | Store |
| --- | --- |
| Raw uploads + encoded segments | **Blob/object store (S3/GCS)** — cheap, durable, huge |
| Manifests, thumbnails | Blob + CDN |
| Metadata (title, owner, stats) | RDBMS / NoSQL |
| View counts, likes | Counters (async aggregation) |

- Store **many encoded variants per video** (resolution × codec) → storage is large; tier cold/rarely-watched to cheaper storage.

---

## 5. Streaming & Adaptive Bitrate

**Adaptive Bitrate Streaming (ABR)** via **HLS** or **MPEG-DASH**:

```
Player fetches manifest → sees available qualities (240p..4K) as segment lists
Player measures bandwidth → requests next segment at the best sustainable quality
Bandwidth drops → switch down; improves → switch up   (seamless, per-segment)
```

- Video is delivered as **small segments over HTTP** (cacheable by CDN), not one giant file.
- Low startup: fetch a low-quality first segment fast, ramp up.
- Enables **seeking** (jump to a segment) and **resume**.

---

## 6. CDN — The Delivery Backbone

- Segments served from **CDN edge caches** near the viewer → low latency, offloads origin.
- **Popular content pre-positioned** at edges (Netflix Open Connect appliances inside ISPs).
- Origin (blob store) hit only on cache miss (cold/long-tail content).
- CDN absorbs the enormous read bandwidth — the origin/DB barely sees traffic.

---

## 7. Metadata, Search & Recommendations

- **Metadata service** (title, description, tags, duration, owner) → RDBMS/NoSQL + cache.
- **Search** via Elasticsearch.
- **Recommendations** = ML (watch history, similarity) — treat as a black box; candidate generation → ranking → cache.
- **View counts/likes** async-aggregated (Kafka → counters); approximate is fine.

---

## 8. Data Model (all tables)

```sql
CREATE TABLE videos (
    video_id BIGINT PRIMARY KEY, owner_id BIGINT, title TEXT, description TEXT,
    duration_s INT, status VARCHAR(20) DEFAULT 'UPLOADING', -- UPLOADING, TRANSCODING, READY, FAILED
    thumbnail_url TEXT, view_count BIGINT DEFAULT 0, like_count BIGINT DEFAULT 0,
    created_at TIMESTAMP
);
CREATE TABLE video_renditions (          -- one row per resolution/codec variant
    video_id BIGINT, quality VARCHAR(10), codec VARCHAR(10),
    manifest_url TEXT, storage_path TEXT,
    PRIMARY KEY (video_id, quality, codec)
);
CREATE TABLE transcoding_jobs (
    job_id BIGINT PRIMARY KEY, video_id BIGINT, segment_no INT, profile VARCHAR(20),
    status VARCHAR(20), worker_id VARCHAR(100), updated_at TIMESTAMP
);
CREATE TABLE users ( user_id BIGINT PRIMARY KEY, name TEXT );
CREATE TABLE watch_history ( user_id BIGINT, video_id BIGINT, position_s INT, watched_at TIMESTAMP,
                             PRIMARY KEY(user_id, video_id) );
CREATE TABLE comments ( comment_id BIGINT PRIMARY KEY, video_id BIGINT, user_id BIGINT, body TEXT, created_at TIMESTAMP );
CREATE TABLE subscriptions ( subscriber_id BIGINT, channel_id BIGINT, PRIMARY KEY(subscriber_id, channel_id) );
-- Segments/manifests/thumbnails → blob store + CDN (not RDBMS)
```

> **Tables to consider:** videos, video_renditions, transcoding_jobs, users/channels, watch_history, comments, subscriptions, likes, playlists, search_index. Media bytes live in blob/CDN.

---

## 9. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Pipeline** | Transcoding stages (validate→segment→encode→package→publish) | Composable processing |
| **Producer-Consumer** | Upload events → transcoding worker fleet (Kafka) | Parallel scale |
| **Strategy** | Codec/profile selection, ABR ladder, recommendation | Swap algorithms |
| **State** | Video status (UPLOADING→TRANSCODING→READY) | Guarded lifecycle |
| **Observer/Pub-Sub** | Transcode-done → publish, notify, index | Decouple |
| **CDN / Cache-Aside** | Edge delivery of segments | Bandwidth offload |
| **Factory** | Encoder per codec, packager per protocol (HLS/DASH) | Extensible |
| **Facade** | Playback service (manifest + auth + CDN URL signing) | Simple API |
| **Repository** | Metadata access | Testable |

---

## 10. Scaling & Failure

- **Transcoding** = massively parallel segment jobs (Producer-Consumer); autoscale workers; retry failed segments.
- **CDN** carries read bandwidth; origin blob store hit only on miss; pre-position popular content.
- **Resumable chunked upload** survives flaky networks.
- **View counts** async-aggregated; eventual consistency.
- **Failure:** a failed segment re-transcodes; a CDN edge miss falls back to origin; metadata replicated.

---

## 11. Interview Cheat Sheet

> **"How do you stream smoothly on variable networks?"**
> "Adaptive bitrate (HLS/DASH): transcode into multiple resolutions, chunk into short segments, and let the player pick the best sustainable quality per segment based on measured bandwidth — switching seamlessly."

> **"How do you handle huge upload/transcode load?"**
> "Resumable chunked upload to blob store; an upload event fans out segment-level transcoding jobs to a worker fleet via Kafka (parallel), producing multiple renditions + a manifest, then publishing to the CDN."

> **"How do you serve millions of viewers?"**
> "CDN edge caches serve cacheable HTTP segments near users; popular content is pre-positioned at edges; origin is hit only on cache miss. The DB only holds small metadata."

> **"YouTube vs Netflix?"**
> "YouTube = UGC, transcode-at-scale, long tail; Netflix = curated catalog, pre-encoded, aggressive CDN pre-positioning. Same core pipeline."

---

## 12. Final Takeaways

- The system is a **media pipeline + CDN**, not a DB problem.
- **Transcode → segment → multiple renditions → manifest**; jobs are parallel (Producer-Consumer).
- **Adaptive bitrate (HLS/DASH)** over cacheable HTTP segments = smooth playback + seek/resume.
- **CDN** carries the bandwidth; pre-position hot content; origin only on miss.
- Metadata in DB/cache; view counts async; recommendations/search separate.
- Patterns: Pipeline, Producer-Consumer, Strategy (ABR/codec), State, CDN/Cache-Aside, Factory.

### Related notes

- [File Storage & Sync — System Design](file-storage-sync-system-design.md) — chunked upload / blob storage overlap
- [Caching Strategies](../concepts/caching-strategies.md) · [Apache Kafka](../concepts/kafka.md)
