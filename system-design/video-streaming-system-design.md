# Video Streaming — System Design (YouTube / Netflix)

> **Core challenge:** ingest huge video files, **transcode** them into many resolutions/formats, and **stream** them to millions of viewers **smoothly** (adaptive bitrate), **globally**, with **low startup latency** and **content protection**. It's dominated by **transcoding + storage + a global CDN** — the database is tiny. Reads (views) ≫ writes (uploads) by orders of magnitude.

---

## Contents

- [1. Mental Model](#1-mental-model)
- [2. Requirements](#2-requirements)
- [3. Capacity Estimation](#3-capacity-estimation)
- [4. Upload & Transcoding Pipeline](#4-upload--transcoding-pipeline)
- [5. Encoding Ladder & Codecs](#5-encoding-ladder--codecs)
- [6. Storage](#6-storage)
- [7. Adaptive Bitrate Streaming (HLS / DASH)](#7-adaptive-bitrate-streaming-hls--dash)
- [8. End-to-End Playback Flow](#8-end-to-end-playback-flow)
- [9. CDN — The Delivery Backbone (internals)](#9-cdn--the-delivery-backbone-internals)
- [10. DRM & Content Protection](#10-drm--content-protection)
- [11. Live Streaming vs VOD](#11-live-streaming-vs-vod)
- [12. Metadata, Search & Recommendations](#12-metadata-search--recommendations)
- [13. View Counting at Scale](#13-view-counting-at-scale)
- [14. Data Model (all tables)](#14-data-model-all-tables)
- [15. Design Patterns (that can be used)](#15-design-patterns-that-can-be-used)
- [16. Scaling & Failure](#16-scaling--failure)
- [17. YouTube vs Netflix — Deeper](#17-youtube-vs-netflix--deeper)
- [18. Interview Cheat Sheet](#18-interview-cheat-sheet)
- [19. Final Takeaways](#19-final-takeaways)

---

## 1. Mental Model

```
Upload → transcode into multiple resolutions + chunk into short segments
       → store in blob → push to CDN edges
Watch  → player fetches a manifest, then segments (adaptive bitrate) from the nearest CDN edge
       → decrypts (DRM) → plays; keeps adapting quality to bandwidth
```

The "database" holds small **metadata**; the real system is a **media processing pipeline + global CDN**. Two very different workloads: **write path** (upload/transcode — heavy compute, rare) and **read path** (streaming — massive bandwidth, constant).

---

## 2. Requirements

**Functional**
- Upload video; transcode to multiple resolutions (144p→4K/8K) & codecs.
- Stream with **adaptive bitrate** (quality tracks bandwidth); **seek**, **resume/continue watching**.
- Browse/search catalog; recommendations; view counts, likes, comments, subscriptions/watchlist.
- (Netflix) **DRM**-protected licensed content; (YouTube) **live** streams.

**Non-functional**
- **Low startup latency + no rebuffering**; **massive read/bandwidth** scale; **globally** low-latency; **durable** storage; secure (DRM); highly available.

---

## 3. Capacity Estimation

```
YouTube-scale assumptions:
  Uploads:   ~500 hours of video/minute → transcoding is a massive compute fleet
  Views:     ~5B/day → tens of millions of CONCURRENT streams at peak
  Bitrate:   1080p ≈ 5 Mbps, 4K ≈ 15–25 Mbps
  Bandwidth: 10M concurrent 1080p streams × 5 Mbps = 50 Tbps  ← CDN territory, not origin
Storage:
  1 hour 1080p ≈ ~2–4 GB; store MANY renditions (144p..4K × codecs) → 5–10× the source
  → exabytes total; tier cold/long-tail content to cheaper storage
Transcode compute:
  each upload → (resolutions × codecs) encodes, segment-parallel → huge but embarrassingly parallel
```

**Takeaways:** **bandwidth dominates** → the design is a **CDN problem**; the origin/DB barely sees traffic. Transcoding is heavy but parallelizable. Storage is enormous → tiering + efficient codecs matter.

---

## 4. Upload & Transcoding Pipeline

```
1. Client asks for an upload URL → uploads the raw master to blob store (RESUMABLE, chunked)
2. Upload-complete event → transcoding queue (Kafka)
3. Transcoding workers (fan-out on write):
     - split into short SEGMENTS (2–10s), keyframe-aligned across renditions
     - encode each segment into MULTIPLE (resolution × bitrate × codec) = the encoding ladder (§5)
     - package into HLS (.m3u8) + DASH (.mpd) MANIFESTS listing segments per quality
     - generate thumbnails / preview sprites; extract captions
4. Store outputs in blob → replicate/push to CDN
5. Mark video READY (state machine); notify creator
```

- **Massively parallel:** split the video → encode segments independently across a worker fleet → reassemble. A 1-hour video transcodes fast by parallelizing segments.
- **Resumable upload:** chunked upload with offsets → survives flaky networks (retry only missing chunks).
- **Retries + DLQ:** a failed segment re-transcodes; poison uploads → DLQ + alert.
- **Pipeline stages** (validate → segment → encode → package → thumbnail → publish) = classic **Pipeline** pattern.

---

## 5. Encoding Ladder & Codecs

### The encoding ladder

A **ladder** = the set of (resolution, bitrate) "rungs" the player can choose from:

```
144p  @ ~100 kbps        480p  @ ~1 Mbps        1080p @ ~5 Mbps
240p  @ ~300 kbps        720p  @ ~2.5 Mbps      4K    @ ~15–25 Mbps
```

- The player picks the highest rung its bandwidth sustains → **adaptive bitrate**.
- **Per-title encoding (Netflix):** don't use a fixed ladder — a cartoon needs far less bitrate than an action film at the same quality. Analyze each title's complexity and **optimize the ladder per title** (even per-scene) → same quality at lower bitrate → less bandwidth + storage.

### Codecs

| Codec | Note |
| --- | --- |
| **H.264 / AVC** | Universal device support; baseline everyone can play |
| **H.265 / HEVC** | ~50% smaller than H.264; licensing complexity |
| **VP9** | Google, royalty-free; good for web/YouTube |
| **AV1** | Best compression, royalty-free; newer, heavier to encode, growing support |

- Encode into **multiple codecs**; serve the **best one the device supports** (negotiated via manifest).
- **Keyframe (IDR) alignment across renditions**: every rendition starts a segment at the same keyframe so the player can **switch quality at segment boundaries** seamlessly.
- **Segment duration trade-off:** shorter (2s) = faster quality switching + lower live latency, but more requests/overhead; longer (6–10s) = more efficient, higher latency.

---

## 6. Storage

| Data | Store |
| --- | --- |
| Raw master uploads + encoded segments | **Blob/object store (S3/GCS)** — cheap, durable, effectively infinite |
| Manifests, thumbnails, captions | Blob + CDN |
| Metadata (title, owner, catalog, stats) | RDBMS / NoSQL + cache |
| View counts, likes | Async counters (§13) |

- Segments are **immutable** → cache forever (long TTL) at the CDN.
- **Storage tiering:** hot/popular in fast storage; cold long-tail → cheaper/archival tiers. Content-addressed dedup for identical uploads.

---

## 7. Adaptive Bitrate Streaming (HLS / DASH)

Deliver video as **many small HTTP segments**, not one giant file — so it's **cacheable by CDNs** and quality can adapt per segment.

```
Player downloads the MANIFEST → sees available qualities as lists of segment URLs
Loop: measure throughput + buffer level → request the NEXT segment at the best sustainable rung
      bandwidth drops → step down; improves → step up   (seamless at segment boundaries)
Startup: fetch a low rung first (fast start) → ramp up as buffer fills
```

| Protocol | Note |
| --- | --- |
| **HLS** (Apple) | `.m3u8` playlists; widest device support; low-latency variant **LL-HLS** |
| **MPEG-DASH** | `.mpd`; codec-agnostic, flexible; not native on Apple |
| **CMAF** | Common segment format so one set of segments serves both HLS & DASH (less storage) |

- Enables **seek** (jump to a segment), **resume** (start at saved position), and **rebuffer-free** playback.
- ABR logic lives in the **player** (client-side) using throughput + buffer heuristics (sometimes ML).

---

## 8. End-to-End Playback Flow

```
1. Player → Playback API:  auth + ENTITLEMENT check (is this user allowed? subscription/rental)
2. Playback API returns:   manifest URL + DRM license URL + a SIGNED/TOKENIZED CDN URL (short-lived)
3. Player → CDN:           GET manifest → GET segments (signed URLs)   ← nearest edge
4. Player → DRM license server: request decryption key (for protected content)
5. Player: decrypt segments → decode → render; ABR loop continues; report progress/heartbeats
```

- **Playback service** is a **Facade**: auth + entitlement + manifest + URL signing in one call, hiding the CDN/DRM details.
- **Progress heartbeats** feed "continue watching" + analytics + view counting.
- **Signed URLs** (expiring tokens) stop people from sharing/hotlinking segment links.

---

## 9. CDN — The Delivery Backbone (internals)

The CDN is where the whole system lives or dies — it absorbs the terabits of read traffic.

### How a request reaches the right edge

```
Player → DNS (GeoDNS) / Anycast IP → nearest CDN Point of Presence (PoP / edge)
Edge cache hit  → serve segment immediately (the common case, >95%+)
Edge miss       → fetch from a regional "shield" cache → else origin (blob) → cache on the way back
```

- **Cache key = segment URL**; segments are immutable → **very long TTL**, near-100% hit rates for popular content.
- **Cache hierarchy:** edge → regional/shield → origin. The shield tier shields the origin from many edges missing at once (reduces origin fan-in).

### Pre-positioning & Open Connect (Netflix)

- **Netflix Open Connect:** Netflix ships **its own CDN appliances into ISP data centers**. During off-peak **fill windows**, they **pre-load predicted-popular titles** onto those boxes → at prime time, streams are served from *inside your ISP* (minimal backbone traffic, lowest latency).
- **YouTube** uses Google's global edge network similarly, but popularity is far less predictable (viral UGC), so it relies more on **pull-through caching** (cache on first miss) than pre-positioning.
- **Multi-CDN:** big players use several CDNs and steer traffic by real-time performance/cost/availability.

> **Key point:** popularity is extremely skewed — a tiny fraction of videos drive most views. Cache/pre-position those at the edge and the origin barely gets touched.

---

## 10. DRM & Content Protection

Critical for licensed content (Netflix); relevant for paid/private YouTube content.

| Mechanism | Purpose |
| --- | --- |
| **Encryption at rest** | Segments stored/served **encrypted** (AES) |
| **DRM systems** | **Widevine** (Android/Chrome), **FairPlay** (Apple), **PlayReady** (Microsoft) — device-specific |
| **License server** | After entitlement check, issues the **decryption key** to the player's secure module |
| **Signed / tokenized URLs** | Short-lived signed segment URLs → prevent hotlinking/sharing |
| **Forensic watermarking** | Embed an invisible per-user marker to trace leaks/piracy |
| **Secure playback path** | Hardware-backed decryption (HDCP) so keys/frames aren't extractable |

```
Player must present a valid device DRM → license server checks entitlement → returns key
No valid license → segments are useless (encrypted). Keys never live in the manifest.
```

---

## 11. Live Streaming vs VOD

**VOD** (on-demand) can pre-transcode everything and cache it. **Live** cannot — it's a different pipeline with a latency constraint.

| | **VOD** | **Live** |
| --- | --- | --- |
| Encoding | Pre-transcode the whole file (offline) | **Real-time** transcode as it arrives |
| Ingest | Upload a file | **RTMP / SRT** stream from the broadcaster |
| Latency | Not critical | **Seconds matter** (sports, gaming, auctions) |
| Caching | Fully cacheable (immutable) | Segments generated on the fly; short TTL |
| Tech | HLS/DASH | **LL-HLS / Low-Latency CMAF-DASH**, chunked transfer |
| Extras | — | DVR window (rewind), simulcast to platforms |

```
Live: broadcaster → RTMP ingest → real-time transcode → package (short segments) → CDN → viewers
      LL-HLS/chunked CMAF push partial segments early → sub-few-second latency
```

- Live trades some quality/efficiency for **latency**; segments are tiny + pushed early.

---

## 12. Metadata, Search & Recommendations

- **Metadata service** (title, description, tags, cast, duration, availability) → RDBMS/NoSQL + heavy cache/CDN.
- **Search** via **Elasticsearch** (title/description/tags + filters), rebuilt from the catalog via CDC.
- **Recommendations** = ML (watch history, similarity, trending, per-user models) → candidate generation → ranking → cache. Netflix optimizes for **retention**; YouTube for **engagement/watch-time**. Treat the model as a black box; emphasize the pipeline + caching.
- **Continue watching / watchlist** from progress heartbeats.

---

## 13. View Counting at Scale

Counting views correctly at billions/day is its own problem (same family as **ad-click aggregation**).

```
Playback heartbeats → Kafka → stream aggregator → per-video counters (OLAP / counters store)
```

- **Define a "view"** (e.g. watched ≥ N seconds) → filter accidental/partial plays.
- **Dedup + bot filtering** — count once per user/session in a window; drop bots (this is why YouTube counts lag/"freeze" while it verifies).
- **Approximate for display** (fast, cached), **exact for monetization** (batch reconciliation from raw events) — Lambda-style.
- Likes/subscriptions similarly **async-aggregated**; approximate cached counts are fine.

---

## 14. Data Model (all tables)

```sql
CREATE TABLE videos (
    video_id BIGINT PRIMARY KEY, owner_id BIGINT, title TEXT, description TEXT,
    duration_s INT, status VARCHAR(20) DEFAULT 'UPLOADING',  -- UPLOADING, TRANSCODING, READY, FAILED
    visibility VARCHAR(10) DEFAULT 'PUBLIC',                  -- PUBLIC, UNLISTED, PRIVATE
    thumbnail_url TEXT, view_count BIGINT DEFAULT 0, like_count BIGINT DEFAULT 0,
    created_at TIMESTAMP
);
CREATE TABLE video_renditions (            -- one row per resolution × codec variant
    video_id BIGINT, quality VARCHAR(10), codec VARCHAR(10),
    manifest_url TEXT, storage_path TEXT, bitrate_kbps INT,
    PRIMARY KEY (video_id, quality, codec)
);
CREATE TABLE transcoding_jobs (
    job_id BIGINT PRIMARY KEY, video_id BIGINT, segment_no INT, profile VARCHAR(20),
    status VARCHAR(20), worker_id VARCHAR(100), updated_at TIMESTAMP
);
CREATE TABLE users ( user_id BIGINT PRIMARY KEY, name TEXT );          -- viewers/channels
CREATE TABLE channels ( channel_id BIGINT PRIMARY KEY, owner_id BIGINT, name TEXT, sub_count BIGINT DEFAULT 0 );
CREATE TABLE subscriptions ( subscriber_id BIGINT, channel_id BIGINT, PRIMARY KEY(subscriber_id, channel_id) );
CREATE TABLE watch_history ( user_id BIGINT, video_id BIGINT, position_s INT, watched_at TIMESTAMP,
                             PRIMARY KEY(user_id, video_id) );          -- continue watching
CREATE TABLE comments ( comment_id BIGINT PRIMARY KEY, video_id BIGINT, user_id BIGINT, parent_id BIGINT, body TEXT, created_at TIMESTAMP );
CREATE TABLE likes ( user_id BIGINT, video_id BIGINT, PRIMARY KEY(user_id, video_id) );
CREATE TABLE playlists ( playlist_id BIGINT PRIMARY KEY, user_id BIGINT, title TEXT );
CREATE TABLE playlist_items ( playlist_id BIGINT, video_id BIGINT, position INT, PRIMARY KEY(playlist_id, video_id) );
-- Segments/manifests/thumbnails → blob store + CDN (never in RDBMS)
-- View events → Kafka + OLAP; search → Elasticsearch
```

> **Tables to consider:** videos, video_renditions, transcoding_jobs, users, channels, subscriptions, watch_history, comments, likes, playlists, playlist_items, captions, entitlements/licenses (paid), search_index (ES), view aggregates (OLAP). Media bytes live in blob/CDN.

---

## 15. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Pipeline** | Transcoding stages (validate→segment→encode→package→publish) | Composable processing |
| **Producer-Consumer** | Upload events → transcoding worker fleet (Kafka) | Parallel, elastic scale |
| **Strategy** | Codec/profile selection, per-title encoding, ABR ladder, recommendation model | Swap algorithms |
| **State** | Video status (UPLOADING→TRANSCODING→READY→FAILED) | Guarded lifecycle |
| **Observer / Pub-Sub** | Transcode-done → publish, notify, index; heartbeats → analytics | Decouple |
| **CDN / Cache-Aside** | Edge delivery of immutable segments | Bandwidth offload |
| **Facade** | Playback service (auth + entitlement + manifest + URL signing) | Simple client API |
| **Factory** | Encoder per codec, packager per protocol (HLS/DASH) | Extensible |
| **Repository** | Metadata access | Testable |
| **Content-Addressed Storage** | Dedup identical uploads/segments | Save storage |

---

## 16. Scaling & Failure

- **Transcoding** = massively parallel segment jobs; autoscale the worker fleet; retry failed segments; DLQ poison uploads.
- **CDN** carries the read bandwidth (terabits); origin blob store hit only on cache miss; shield tier + pre-positioning protect the origin.
- **Resumable chunked upload** survives flaky networks.
- **Global**: multi-region blob + multi-CDN + edge caches near users.
- **View counts/likes** async-aggregated (eventual consistency; approximate display).
- **Failure modes:** failed segment → re-transcode; CDN edge miss → shield → origin; DRM license server down → playback of protected content blocked (make it HA); metadata replicated.

---

## 17. YouTube vs Netflix — Deeper

| Dimension | **YouTube** | **Netflix** |
| --- | --- | --- |
| Content | **UGC**, unbounded, long tail | **Curated/licensed**, thousands of titles |
| Ingest | Millions of uploads/day → transcode at scale | Studio masters → encode once (per-title/per-scene) |
| Popularity | **Unpredictable virality** → pull-through caching | More predictable → **pre-position** (Open Connect) |
| CDN | Google global edge | **Own CDN inside ISPs** (Open Connect) |
| Latency of new content | Must be watchable minutes after upload | Encoded well ahead of release |
| DRM | Mostly open; DRM for paid/rentals | **Heavy DRM** + forensic watermarking |
| Live | **Yes** (live streams) | Mostly VOD (some live events) |
| Monetization | **Ads** → view counting is business-critical | **Subscription** → retention-driven recs |
| Recommendations | Engagement / watch-time | Retention / satisfaction |

> **Same core** (transcode → segment → multi-rendition → manifest → CDN → ABR player). **Differences** are content model (UGC vs curated), caching strategy (pull vs pre-position), DRM intensity, and live support.

---

## 18. Interview Cheat Sheet

> **"How do you stream smoothly on variable networks?"**
> "Adaptive bitrate (HLS/DASH): transcode into an encoding ladder of resolutions/bitrates, chunk into short keyframe-aligned segments, and let the player pick the best sustainable rung per segment from measured throughput + buffer — switching seamlessly at segment boundaries."

> **"How do you handle massive upload/transcode load?"**
> "Resumable chunked upload to blob; an upload event fans out **segment-level** transcoding jobs to an autoscaling worker fleet via Kafka (embarrassingly parallel), producing multiple codec/resolution renditions + HLS/DASH manifests, then publishing to the CDN. State machine tracks UPLOADING→READY."

> **"How do you serve tens of millions of concurrent streams?"**
> "It's a **CDN problem**: immutable segments cached at edge PoPs (GeoDNS/anycast routing), a shield tier + pre-positioning protect the origin, and popularity is skewed so hot content is nearly always a cache hit. The origin/DB barely sees traffic — bandwidth is 50+ Tbps, all absorbed by the CDN."

> **"How is content protected?"**
> "Segments encrypted at rest; DRM (Widevine/FairPlay/PlayReady) license server issues decryption keys after an entitlement check; short-lived **signed segment URLs** prevent hotlinking; forensic watermarking traces leaks."

> **"Live vs VOD?"**
> "VOD pre-transcodes and fully caches (latency doesn't matter). Live ingests via RTMP/SRT, transcodes in real time, and uses LL-HLS/chunked CMAF with tiny segments pushed early for low latency — can't pre-encode or cache long."

> **"YouTube vs Netflix?"**
> "Same pipeline. YouTube = UGC, transcode-at-scale, unpredictable virality → pull-through caching, ads (view counting matters), live. Netflix = curated, per-title encoding, predictable popularity → pre-positioned via Open Connect inside ISPs, heavy DRM, subscription/retention recs."

> **"How do you count views at billions/day?"**
> "Playback heartbeats → Kafka → stream aggregation into counters; define a 'view' (≥N seconds), dedup + filter bots, approximate for display and reconcile exactly in batch for monetization (Lambda-style)."

---

## 19. Final Takeaways

- It's a **media pipeline + global CDN**, not a DB problem — **bandwidth dominates**, so caching/CDN is the whole game.
- **Transcode → segment (keyframe-aligned) → encoding ladder of renditions/codecs → HLS/DASH manifest**; jobs are massively parallel (Producer-Consumer).
- **Adaptive bitrate** over cacheable HTTP segments = smooth playback + seek/resume; ABR logic is client-side.
- **CDN internals:** GeoDNS/anycast → edge → shield → origin; immutable segments cache forever; **pre-position** (Netflix Open Connect) vs **pull-through** (YouTube); multi-CDN.
- **DRM** (Widevine/FairPlay/PlayReady) + signed URLs + watermarking protect licensed content.
- **Live ≠ VOD:** real-time transcode + LL-HLS for low latency; can't pre-cache.
- **View counts** async-aggregated with dedup/bot-filtering; approximate display + exact batch for money.
- Patterns: Pipeline, Producer-Consumer, Strategy (ABR/codec), State, CDN/Cache-Aside, Facade (playback), Factory.

### Related notes

- [File Storage & Sync — System Design](file-storage-sync-system-design.md) — chunked upload / blob storage overlap
- [Instagram — System Design](instagram-system-design.md) — media pipeline sibling
- [Ad Click Aggregation](ad-click-aggregation-system-design.md) — same view-counting/stream-aggregation pattern
- [Caching Strategies](../concepts/caching-strategies.md) · [Apache Kafka](../concepts/kafka.md) · [Networking Essentials](../concepts/networking-essentials.md) (CDN/anycast)
