# Video Streaming — System Design (YouTube / Netflix)

> **Core challenge:** ingest huge video files, **transcode** them into many resolutions/formats, and **stream** them to millions of viewers **smoothly** (adaptive bitrate), **globally**, with **low startup latency** and **content protection**. It's dominated by **transcoding + storage + a global CDN** — the database is tiny. Reads (views) ≫ writes (uploads) by orders of magnitude.

> **How to read this doc:** each section has the dense interview summary first, then a **deep dive** (annotated code, and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand.

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

### What problem are we even solving?

Imagine you run YouTube (or Netflix). Someone in Mumbai records a 10-minute 4K video on their phone and uploads it. Minutes later, a million people around the world — on a slow train Wi-Fi, a fast home fibre line, a 4K TV, an old phone — all want to press **play** and watch it **instantly, smoothly, no buffering**.

The whole system exists to bridge those two moments:

1. **Upload / process (the write path):** one giant raw file comes in. It's rare (compared to views), but it's *heavy* — you have to chop it up and re-encode it into dozens of versions. This is **transcoding**.
2. **Watch / deliver (the read path):** millions of people pull the video at once, each at whatever quality their internet can handle *right now*. This is **adaptive bitrate streaming over a CDN**, and it's where 99.9% of the traffic (terabits per second) lives.

So the system has two stages — processing and delivery:

> **Upload → transcode into many sizes + cut into small segments → store and replicate near viewers → player fetches the right-sized segments from the nearest CDN edge and plays them one after another.**

Almost everything in this doc is "how do we do the processing step efficiently" and "how do we deliver bytes to millions of people cheaply and smoothly."

### Why not just stream the raw uploaded file?

First instinct: store the file the creator uploaded, and when someone hits play, send them that file. Why that falls apart:

| Problem with sending the raw file | What breaks |
| --- | --- |
| **One fixed quality** | The raw upload is, say, 4K at 40 Mbps. Someone on a phone with 2 Mbps can't download it fast enough → it constantly **buffers** (spinner). Someone on a fast line gets 4K but had no cheaper option when their Wi-Fi dipped. |
| **Wrong device/format** | The camera's codec/container may not play in a browser or on an old TV. Devices support *different* codecs. |
| **One huge file = not seekable/cacheable** | To jump to minute 8, or to switch quality mid-video, you'd need the whole file. CDNs also cache **small files** well, not one 4 GB blob. |
| **No way to adapt** | Networks change second to second (train enters a tunnel). A single file can't get smaller/bigger as your bandwidth moves. |

**Key insight that drives the entire design:**

> **Don't serve one big file. Pre-process each video into many quality versions, each cut into small independent chunks. Let the player download chunks one at a time, choosing the quality that fits the network *at that moment*.**

That single idea explains transcoding (§4), the encoding ladder (§5), segments + manifests (§7), and why the CDN (§9) works so well (small immutable chunks cache beautifully).

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

### Why "bandwidth dominates" (and what a bit rate even is)

The number that stresses the system isn't how much video you store — it's how many streams are **open at once** and how many bytes per second flow out to all those viewers simultaneously. The hard part isn't storing the videos; it's sustaining the outbound bandwidth to every viewer at the same time.

- **Bitrate** = how many bits per second a video needs to play smoothly. 1080p ≈ **5 Mbps** means "to watch this in 1080p, 5 megabits must arrive every second, forever, or you buffer."
- Now multiply by viewers. **10 million** people watching 1080p at once = 10,000,000 × 5 Mbps = **50 Tbps** (terabits per second) leaving your system *continuously*.

```
one 1080p viewer      = 5 Mbps
10 million viewers     = 10,000,000 × 5 Mbps = 50,000,000 Mbps = 50 Tbps
```

No single data center or database can push 50 Tbps. That's why the answer is a **CDN** (§9): thousands of edge servers spread worldwide, each pushing a slice of that traffic from close to viewers. The database (metadata like titles, view counts) is *tiny* by comparison — it handles clicks and lookups, not the video bytes themselves.

#### Q: The videos take exabytes of storage — isn't storage the real problem?

Storage is big and costs money, but it's a *solved, boring* problem: object stores (S3/GCS) are effectively infinite and cheap, and you tier cold content to even cheaper archives (§6). The thing that's genuinely hard to engineer and easy to get wrong at scale is **sustained outbound bandwidth to millions of concurrent viewers** — hence "it's a CDN problem."

#### Q: Why store 5–10× the source? Isn't that wasteful?

Because you keep **many renditions** of the same video — 144p, 240p, 480p, 720p, 1080p, 4K, each possibly in multiple codecs (§5). Each rendition is a full copy of the video at that quality. It looks wasteful, but it's what makes smooth playback on every device/network possible, and cheap storage makes the trade-off worth it.

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

### What "transcoding" actually means

Take one master file and produce many re-encoded versions at different resolutions/qualities/formats — the same idea as generating a small, medium, and large copy of an image, but for video. Each output is a full re-encode tuned for a different device and network.

> **Transcode = decode the video back into raw frames, then re-encode it into a different resolution / bitrate / codec.** You do it many times per upload to build the "encoding ladder" (§5).

#### Q: What is "chunking into segments," and why do it?

The transcoder also **cuts the video into short pieces** (typically 2–10 seconds each), called **segments** (or chunks). So a 10-minute video at one quality becomes ~100 tiny files instead of one big file.

Why segments are the magic ingredient:

- **Parallel transcoding** — 100 segments can be encoded by 100 workers at once, so a 1-hour video finishes in the time it takes to encode a few seconds. ("Embarrassingly parallel.")
- **Adaptive quality** — the player can grab segment #5 in 1080p, then segment #6 in 480p if Wi-Fi drops (§7). Impossible with one file.
- **Cacheable** — small immutable files are perfect for CDN caching (§9).
- **Seek/resume** — jump to minute 8 = just fetch the segments covering minute 8.

#### Q: What does "resumable / chunked upload" mean — is it the same as segments?

**No — different thing, easy to confuse.** Chunked *upload* is about getting the raw file *in* reliably; segments are about breaking the *transcoded output* up for *playback*.

- **Resumable upload:** the client sends the raw file in pieces with byte offsets. If Wi-Fi dies at 70%, it retries only the missing pieces instead of restarting the whole 4 GB upload.
- **Segments:** produced *later*, by the transcoder, for streaming.

Annotated pipeline pseudocode (the whole write path):

```python
# ---------- 1. Upload (resumable) ----------
def start_upload(filename):
    upload_id = blob.create_resumable_session(filename)  # get a URL to push chunks to
    return upload_id                                     # client PUTs 8MB chunks by byte offset

def on_upload_complete(video_id, master_path):
    db.set_status(video_id, "TRANSCODING")               # State pattern: UPLOADING -> TRANSCODING
    kafka.send("transcode-jobs", {                       # hand off to the worker fleet
        "video_id": video_id,
        "master_path": master_path,
    })

# ---------- 2. Fan-out: one job -> many segment encodes ----------
def plan_transcode(job):
    segments = split_keyframe_aligned(job.master_path, seg_seconds=6)  # cut into 6s chunks
    ladder   = ["144p","240p","480p","720p","1080p","4K"]              # the encoding ladder (§5)
    codecs   = ["h264", "vp9", "av1"]

    for seg in segments:
        for quality in ladder:
            for codec in codecs:
                kafka.send("segment-encode", {           # each (segment × quality × codec) = 1 job
                    "video_id": job.video_id,
                    "segment_no": seg.index,
                    "quality": quality,
                    "codec": codec,
                })

# ---------- 3. A worker encodes ONE segment variant ----------
def encode_segment(task):                                # runs on any of thousands of workers
    try:
        out = ffmpeg_transcode(task.master_slice,        # the actual re-encode
                               resolution=task.quality,
                               codec=task.codec)
        blob.put(path_for(task), out)                    # store the encoded chunk
        db.mark_segment_done(task)
    except EncodeError:
        retry_or_dlq(task)                               # failed segment -> retry, poison -> DLQ

# ---------- 4. Package + publish when all segments done ----------
def on_all_segments_done(video_id):
    write_hls_manifest(video_id)                         # .m3u8 listing segments per quality (§7)
    write_dash_manifest(video_id)                        # .mpd
    cdn.warm(video_id)                                   # push/replicate to edges
    db.set_status(video_id, "READY")                     # State pattern: -> READY; notify creator
```

Key ideas the code shows: **fan-out on write** (one upload explodes into thousands of independent little jobs), a **state machine** (`UPLOADING → TRANSCODING → READY/FAILED`), and **retry + DLQ** so one bad segment doesn't sink the whole video.

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

### Ladder, codec, keyframe — three words demystified

- **Ladder = the list of (resolution, bitrate) options** you pre-made for a video. Each "rung" is one quality version. The player selects the highest rung its bandwidth can sustain, and steps down a rung if bandwidth drops mid-playback.
- **Codec = the *compression method* used to shrink the video** (H.264, VP9, AV1). Think of it like ZIP vs RAR vs 7z for video: same movie, different squeezing algorithm. Newer codecs (AV1) squeeze harder (smaller files, less bandwidth) but take more CPU to make and aren't supported on every old device — so you keep H.264 around as the "everyone can play it" fallback.
- **Bitrate = size of the size.** 1080p at 5 Mbps vs 1080p at 3 Mbps: same pixel dimensions, but the 3 Mbps one is more compressed (slightly worse looking, cheaper to deliver).

#### Q: What's a "keyframe," and why must they line up across qualities?

A **keyframe** (I-frame/IDR) is a *complete* picture; the frames after it only store "what changed" from it (that's how video compresses). A segment must **start on a keyframe** so it can be decoded on its own.

For the player to switch 1080p → 480p mid-video *without a glitch*, the 1080p and 480p versions must be cut at the **exact same time points** — i.e. keyframes aligned across all rungs. Then the player finishes segment #5 in 1080p and picks up segment #6 in 480p as if nothing happened.

```
time →        0s        6s        12s       18s
1080p:      [ seg0 ]  [ seg1 ]  [ seg2 ]  [ seg3 ]
 480p:      [ seg0 ]  [ seg1 ]  [ seg2 ]  [ seg3 ]
                         ↑ same boundary → safe to switch quality here
```

#### Q: What is "per-title encoding" (Netflix)?

Instead of blindly using the same ladder for every video, analyze each title's **visual complexity** and tailor the ladder to it:

- A **simple cartoon** (flat colors, little motion) looks perfect at a *low* bitrate → don't waste 5 Mbps on it; maybe 2 Mbps is plenty.
- A **fast action film** (explosions, grain, motion) needs a *high* bitrate to look good.

So Netflix picks the optimal (resolution, bitrate) rungs *per title* (even per scene). Result: same perceived quality at lower bitrate → less bandwidth *and* less storage across billions of streams. Netflix can do this because content is curated and encoded once, well ahead of release; YouTube's UGC firehose makes fully per-title tuning harder (§17).

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

### Two kinds of storage, and why they're separate

Keep the bulky video bytes in a blob/object store, and keep the small facts about each video (title, owner, status, view count) in a database. You never put the media bytes in the database — it would be enormous and impossible to query.

| Thing | Where it lives | Why |
| --- | --- | --- |
| The actual video bytes (segments, manifests, thumbnails) | **Blob / object store** (S3, GCS) + CDN | Huge, cheap, durable, "infinite"; served straight to viewers |
| Small facts about the video (title, owner, status, view count) | **Database** (RDBMS/NoSQL) + cache | Small, queryable, needs joins/filters/updates |

> **Rule of thumb: never put media bytes in your database.** The DB stores a *pointer* (a URL/path) to where the bytes live in blob storage.

#### Q: Why are segments "immutable," and why does that matter so much?

**Immutable** = once written, a segment file never changes. A 1080p segment #5 of video X is the same bytes forever. This is enormously useful:

- **Cache forever.** The CDN can keep it with a very long TTL (time-to-live) and never worry it went stale. That's why hit rates are near 100% for popular content (§9).
- **Dedup & safe retries.** Re-encoding or re-fetching yields identical bytes — no "which version is right?" confusion.

If videos could be edited in place, every edge cache worldwide would have to be invalidated and re-fetched. Immutability sidesteps that entirely.

#### Q: What is "storage tiering" / cold long-tail?

Most views go to a *tiny* fraction of videos; the vast majority are watched rarely (the **long tail**). So:

- **Hot** (popular, recent) → fast storage + heavily cached at CDN.
- **Cold** (old, rarely watched) → cheaper "archival" tiers (slower to fetch, but that's fine — almost nobody requests them).

**Content-addressed dedup:** name a file by a hash of its contents. If two uploads are byte-identical, they hash to the same name → you store the bytes **once**. Saves storage on duplicate uploads.

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

### The manifest and the player's ABR loop

Instead of one giant file, the video is delivered as **small segments requested one at a time**. The player first downloads the **manifest**, which lists every segment available in each quality. It then requests the next segment at the quality its network can currently sustain, measures how fast it arrives, and adjusts the next request up or down.

- **Manifest** = a text file listing the segment URLs for each quality. The player downloads it first. Two flavors: HLS `.m3u8` and DASH `.mpd`.
- **The player is in charge** (ABR = Adaptive BitRate). It watches two things every few seconds — how fast segments are arriving (**throughput**) and how much video it has buffered ahead (**buffer level**) — and picks the quality for the *next* segment.

What an HLS **master manifest** looks like (the menu of qualities):

```m3u8
#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=426x240       # 240p rung
240p/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720     # 720p rung
720p/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080    # 1080p rung
1080p/index.m3u8
```

Each rung points to a **media manifest** listing that quality's actual segments in order:

```m3u8
#EXTM3U
#EXT-X-TARGETDURATION:6
#EXTINF:6.0,
segment0.ts          # first 6 seconds
#EXTINF:6.0,
segment1.ts          # next 6 seconds
#EXTINF:6.0,
segment2.ts
#EXT-X-ENDLIST       # (VOD) the video ends here
```

The player's ABR decision loop, in simple annotated code:

```python
def pick_next_quality(rungs, measured_mbps, buffer_seconds):
    # rungs = [(240, 0.5), (720, 2.5), (1080, 5.0)]  # (label, required Mbps)

    # Safety margin: only pick a rung if we can download it comfortably faster
    # than it plays, so the buffer keeps filling instead of draining.
    affordable = [r for r in rungs if r.required_mbps <= measured_mbps * 0.8]

    if not affordable:
        return rungs[0]                 # network is bad → lowest quality, don't stall

    best = max(affordable, key=lambda r: r.required_mbps)

    # If the buffer is dangerously low, be conservative (step down a notch)
    if buffer_seconds < 5:
        best = step_down_one(best, rungs)

    return best

# The playback loop: fetch one segment at a time, re-decide each time.
def stream(manifest):
    buffer = []
    while not done:
        q = pick_next_quality(manifest.rungs, measured_mbps(), len(buffer)*SEG_SEC)
        seg = http_get(manifest.segment_url(next_index, quality=q))   # ← from nearest CDN edge
        buffer.append(seg)
        play(buffer.pop(0))
```

Startup trick: fetch a **low** rung first so playback starts almost instantly, then ramp up as the buffer fills.

#### Q: Why not just let the *server* decide the quality?

Only the **player** knows the *live* truth: its real download speed this second, its buffer level, its screen size, its device's codecs. The network changes constantly (a train enters a tunnel). Client-side ABR reacts in real time; a server would be guessing. The server's only job is to make all the rungs available; the client chooses.

#### Q: HLS vs DASH vs CMAF — do I need all three?

- **HLS** (Apple's format, `.m3u8`) — widest device support, required for Apple devices.
- **DASH** (`.mpd`) — open standard, flexible, but not native on Apple.
- **CMAF** — a *common segment format* so the **same segment files** can serve both HLS and DASH. You write two small manifests but store **one** set of segments → big storage savings. That's why it exists.

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

### What happens the instant you press play

One call to the Playback API checks that the user is allowed to watch (auth + entitlement), then returns the manifest URL plus a short-lived signed URL and the DRM license endpoint. The player then talks directly to the nearest CDN edge for bytes and to the DRM license server for the decryption key — never back through your backend for the actual video.

Step by step, with a tiny bit of code for the "one call" the player makes:

```python
# The Playback service = a FACADE: one call does auth + entitlement + manifest + signing.
def start_playback(user, video_id):
    if not user.is_authenticated():
        raise Unauthorized()

    if not entitled(user, video_id):            # subscription active? rented? region allowed?
        raise Forbidden("not entitled")

    manifest_url = manifests.url_for(video_id)  # points at the CDN

    # SIGNED URL: attach a short-lived token so links can't be shared/hotlinked
    signed = sign(manifest_url, user_id=user.id, expires_in="5m")

    return {
        "manifest_url": signed,
        "drm_license_url": drm.license_endpoint(video_id),  # where to fetch the key (§10)
    }
```

Then the player, on its own, talks to the **CDN** (for bytes) and the **DRM license server** (for the key) — never bothering your backend for the actual video:

```
1. Player → Playback API      : "can I watch video X?"  → gets manifest URL + DRM URL (signed)
2. Player → CDN (nearest edge): GET manifest → GET segment0, segment1, ...   ← the heavy traffic
3. Player → DRM license server: "give me the decryption key" (for protected content)
4. Player                     : decrypt → decode → show pixels; keep ABR loop + send heartbeats
```

#### Q: What's an "entitlement check"? Isn't that just login?

Login (**authentication**) = "who are you?" Entitlement (**authorization** for *this* content) = "are you allowed to watch *this specific* video *right now*?" — is your Netflix subscription active, did you rent this movie, is it available in your country/region, is it age-gated? Both must pass before you get a manifest.

#### Q: What is a "signed URL" and why not just a normal link?

A **signed URL** has a cryptographic token baked in that encodes "valid for user X until 9:05 PM." The CDN checks it before serving. If someone copies the link and shares it, it **expires** in minutes and is often tied to the user — so it's useless to a stranger. This stops **hotlinking** (others embedding your video/bandwidth) and casual link-sharing of paid content.

#### Q: What are "heartbeats"?

While you watch, the player periodically pings the backend: "user still watching video X, now at 03:42." These **heartbeats** power **continue watching** (resume where you left off), analytics, and **view counting** (§13).

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

### How a CDN delivers bytes (edge → shield → origin)

A **CDN (Content Delivery Network)** is a layer of caches between the viewer and the origin: thousands of edge servers placed physically close to users worldwide, each holding copies of popular segments, backed by a regional shield tier, backed by the origin. A request is almost always served by the nearby edge; the origin is touched rarely, only on cache misses.

```
you press play
      │
      ▼
nearest edge cache          ── HIT? ──► serve segment instantly  (this is >95% of the time)
      │ MISS
      ▼
regional "shield" cache     ── HIT? ──► serve + copy back to the edge
      │ MISS
      ▼
origin (blob store)         ─────────► serve + copy back up the chain
```

- **How you reach the *nearest* edge:** **GeoDNS / Anycast**. When your player looks up the video hostname, the network answers with the IP of the edge closest to you (by geography/latency). Same URL, different nearby server depending on where you are.
- **Cache key = the segment URL.** Since segments are immutable (§6), an edge that once fetched `segment5.ts` can keep it basically forever (**long TTL**) and serve it to everyone nearby → **near-100% hit rate** for popular content.
- **Why the shield tier?** If 500 edges all miss at the same instant (a video just went viral), you don't want 500 requests hammering the origin. They hit a **regional shield** first, which fetches from origin **once** and feeds all 500. This "**fan-in** protection" keeps the origin calm.

#### Q: Pre-positioning vs pull-through — what's the difference?

- **Pull-through (YouTube):** cache a segment the *first* time someone requests it (it "pulls" from origin on the first miss, then it's cached for everyone after). Best when you can't predict what'll be popular — UGC virality is random.
- **Pre-positioning (Netflix Open Connect):** *predict* tonight's popular titles and **copy them onto edge boxes ahead of time**, during quiet overnight "fill windows." Netflix even ships its own caching appliances **inside ISPs**, so at prime time your show streams from *within your internet provider's building* — minimal long-distance traffic, lowest latency. Works because Netflix's catalog is curated and predictable.

#### Q: Why does this make the origin/database "barely see traffic"?

Because popularity is wildly **skewed** — a small set of videos drives most views, and those are cached at the edge. The terabits of bandwidth are absorbed by thousands of edges close to users; the origin only handles rare cache misses (cold, long-tail content). That's the whole reason "it's a CDN problem" — the CDN, not your servers, carries the load.

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

### DRM: encrypted segments + a separate key

The segments are **encrypted with AES**. Even if someone downloads all of them, they're useless without the **decryption key**, which is issued separately — only after the license server verifies the user is entitled, and only into the device's secure hardware module. The key never appears in the manifest or plain network traffic.

- **Encryption at rest:** segments are stored and served scrambled. Downloading them yields garbage without the key.
- **License server:** after the entitlement check (§8), it issues the **decryption key** to the player's *secure* module. The key **never** appears in the manifest or plain network traffic.
- **DRM systems are device-specific:** **Widevine** (Android/Chrome), **FairPlay** (Apple), **PlayReady** (Microsoft). Each device negotiates the one it supports.

```
1. Player fetches encrypted segments from CDN            → useless bytes so far
2. Player → DRM license server: "I'm this trusted device, and this user is entitled"
3. License server verifies → returns the decryption KEY (to a secure hardware module)
4. Player decrypts inside the secure path → decodes → shows frames
   (no valid license → nothing plays; the key is never exposed to copy)
```

#### Q: If the browser can decrypt it, can't a user just grab the decrypted video?

That's the point of a **secure/hardware-backed playback path** (HDCP, secure enclaves): decryption and decoding happen inside protected hardware where normal software (and screen recorders) can't reach the raw frames or the key. It's not unbreakable, but it raises the bar a lot.

#### Q: What is "forensic watermarking"?

An **invisible, per-user marker** embedded into the video each user receives. If a copy leaks online, the studio can extract the marker and trace **which account** leaked it. It doesn't prevent copying; it enables *accountability* (mostly used by Netflix for high-value licensed content).

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

### Live vs VOD: the time-pressure difference

**VOD** (video-on-demand) transcodes the whole file offline, ahead of time, caches every rendition, and serves it instantly. **Live** has to transcode the stream *as it arrives*, second by second, and push it out fast — you can't pre-encode content that's still being recorded.

The core difference is **time pressure**:

- **VOD:** the file already exists. Transcode all renditions slowly and carefully, cache forever, latency doesn't matter. When you press play, everything's ready.
- **Live:** frames are arriving in real time from the broadcaster (via **RTMP/SRT** ingest). You must transcode *as it streams*, cut tiny segments, and push them out within **seconds** — or a sports goal spoils in the group chat before viewers see it.

```
Live:  broadcaster's camera → RTMP/SRT ingest → REAL-TIME transcode
       → cut into TINY segments → push to CDN immediately → viewers (a few seconds behind)
```

#### Q: Why can't live just reuse the normal VOD pipeline?

Two reasons: (1) **there's no file yet** — you can't pre-transcode something still being recorded; (2) **latency budget** — VOD can take minutes to encode all renditions; live has *seconds*. So live uses **LL-HLS / low-latency chunked CMAF**, which pushes *partial* segments out early (before the full segment even finishes) to shave latency, trading a bit of compression efficiency and quality for speed.

#### Q: What's a "DVR window" in live?

Even in a live stream, recent segments are kept around so viewers can **pause/rewind** (e.g. re-watch the goal) while the live edge keeps advancing. That rolling buffer of recent segments is the **DVR window**. Old segments age out (short TTL), unlike VOD where segments are cached forever.

---

## 12. Metadata, Search & Recommendations

- **Metadata service** (title, description, tags, cast, duration, availability) → RDBMS/NoSQL + heavy cache/CDN.
- **Search** via **Elasticsearch** (title/description/tags + filters), rebuilt from the catalog via CDC.
- **Recommendations** = ML (watch history, similarity, trending, per-user models) → candidate generation → ranking → cache. Netflix optimizes for **retention**; YouTube for **engagement/watch-time**. Treat the model as a black box; emphasize the pipeline + caching.
- **Continue watching / watchlist** from progress heartbeats.

### Metadata, search, and recommendations

Everything so far was about *pixels*. This section is the **text-and-facts** side: titles, descriptions, search, "you might also like." It's a normal web-app problem bolted onto the media system, and it's *tiny* in data compared to the video bytes.

- **Metadata service** — small facts (title, tags, cast, duration). Stored in a DB, then **heavily cached** because it's read constantly and rarely changes.
- **Search** — you can't ask a normal DB "find videos with 'cooking' in the title/tags, filtered by language, sorted by relevance" efficiently. A **search engine (Elasticsearch)** builds an inverted index for that. It's kept in sync with the catalog via **CDC** (Change Data Capture — when the DB changes, the change flows to the search index).
- **Recommendations** — an ML pipeline: generate candidate videos → rank them per user → cache the list. Treat the model as a black box in an interview; emphasize the *pipeline + caching*, not the math.

```
DB (catalog) --CDC--> Elasticsearch (search index)
DB + watch history --> ML pipeline --> ranked recs --> cache --> home feed
```

#### Q: Why a separate search engine instead of `WHERE title LIKE '%cooking%'`?

`LIKE '%...%'` can't use an index (it scans every row), can't rank by relevance, and can't do typo-tolerance, synonyms, or multi-field scoring. **Elasticsearch** pre-builds an **inverted index** (word → list of videos containing it), so it answers keyword+filter queries in milliseconds across billions of docs. The trade-off is you now maintain a second store kept in sync with the source DB.

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

### Counting views is the same pattern as ad clicks

Don't store one row per play and recount — keep a **running counter** that increments as heartbeats stream in, with a rule for what qualifies as a "view" (watched past some threshold, not an accidental play). This is the **exact same pattern as [ad-click aggregation](ad-click-aggregation-system-design.md)**: pre-aggregate the count instead of scanning raw events on every read.

```
playback heartbeats → Kafka (append-only log) → stream aggregator (counts per video)
                                              → OLAP / counters store → view counts on screen
```

Simplified counting logic:

```python
def on_heartbeat(event):
    # 1. DEFINE a view: only count once the user has watched enough
    if event.watched_seconds < MIN_VIEW_SECONDS:      # e.g. 30s → filters accidental plays
        return

    # 2. DEDUP: count once per (user/session, video) within a time window
    if seen.contains(event.session_id, event.video_id):   # TTL-backed set
        return
    seen.add(event.session_id, event.video_id)

    # 3. BOT FILTER: drop obvious non-humans before counting
    if is_bot(event):
        return

    # 4. Increment the running counter (not a row per play)
    counters.increment(event.video_id)               # aggregated, async
```

#### Q: Why does YouTube's view count sometimes "freeze" (e.g. stuck near 301)?

Because of steps 2–3 above. For virality-prone or monetized videos, YouTube **pauses the displayed count** while it verifies views are real (dedup + bot filtering + fraud checks) rather than show an inflated number it might have to take back. Real humans get counted; bots/duplicates get dropped. That verification lag is the "freeze."

#### Q: Why "approximate for display, exact for money"?

Showing "1.2M views" instantly is fine even if it's off by a few — freshness beats precision for a public counter. But **ad revenue** must be exact and defensible. So (Lambda-style, like ad-click billing): a fast streaming path drives the on-screen number, and a slower **batch job recomputes exact totals from the raw event log** for monetization. Likes/subscriptions are similarly async-aggregated — approximate cached counts are perfectly acceptable there.

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

### Notice what's NOT in these tables

Scan the schema and the biggest thing is what's **missing**: there is **no column holding video bytes**. The `videos` table stores a `thumbnail_url` and a status; `video_renditions` stores a `manifest_url` and a `storage_path` — all just **pointers** to where the real bytes live in blob/CDN. The database stores pointers, never the video bytes themselves (§6).

Walking the key tables in plain terms:

| Table | In one sentence |
| --- | --- |
| `videos` | The master record per video: title, owner, status (`UPLOADING→READY`), cached counts. |
| `video_renditions` | One row per quality×codec version — *where* each rendition's manifest/segments live. This is the encoding ladder (§5) made concrete. |
| `transcoding_jobs` | Progress tracking for the fan-out transcode (§4): which segment, which worker, done/failed. |
| `watch_history` | `(user, video) → position_s` — powers **continue watching** from heartbeats. |
| `subscriptions` / `likes` | Simple join tables (who follows/liked what); counts on them are async-aggregated (§13). |

```sql
-- The link: DB row points at bytes; bytes never sit in the DB.
SELECT manifest_url, storage_path            -- e.g. 'https://cdn.../video/42/1080p/index.m3u8'
FROM   video_renditions
WHERE  video_id = 42 AND quality = '1080p' AND codec = 'h264';
-- player then fetches that manifest + segments from the CDN, not from Postgres
```

#### Q: Why keep `view_count` on the `videos` row AND aggregate views in OLAP (§13)?

The `view_count` column is a **cached, approximate** number for fast display on the page (one quick read). The OLAP/stream aggregation is the **source of the numbers** (and, after batch reconciliation, the exact figure for monetization). The row's counter is periodically updated from the aggregation — display-fast vs money-accurate, the same Lambda split as everywhere else.

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
