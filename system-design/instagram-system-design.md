# Instagram — System Design (Photo/Video Sharing)

> **Core challenge:** upload and serve **photos/videos** fast and globally, build a **follow-based ranked feed** and **Stories**, and support likes/comments/Explore — a blend of **media pipeline + CDN** (like YouTube) and **feed fan-out** (like Twitter). Distinctive parts: **media handling** and the **unidirectional follow graph** with **private accounts**.

> **How to read this doc:** each section has the dense interview summary first, then a **deep dive** (annotated Java and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand.

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
- [14. Scaling & Failure](#14-scaling--failure)
- [15. Interview Cheat Sheet](#15-interview-cheat-sheet)
- [16. Reels](#16-reels)
- [17. Consistency & CAP Tradeoffs](#17-consistency--cap-tradeoffs)
- [18. How to Drive the Interview (framework)](#18-how-to-drive-the-interview-framework)
- [19. Design Patterns (that can be used)](#19-design-patterns-that-can-be-used)
- [20. Final Takeaways](#20-final-takeaways)

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

### What are we actually building?

Imagine you're rebuilding Instagram from scratch. Strip away the branding and there are really just **two hard jobs glued together**:

1. **The photo/video part (like YouTube).** Someone posts a photo. You have to store that big file safely, make several sizes of it (tiny for the profile grid, medium for the feed, huge for full-screen), and then serve it to millions of people around the world *fast*. Big files + global audience = a **media pipeline + CDN** problem.
2. **The "who sees whose posts" part (like Twitter).** You **follow** people. Your home feed = recent posts from the people you follow, ranked so the good stuff is on top. Figuring out how to build that feed cheaply for a billion users = the **fan-out** problem.

Everything else (Stories, Explore, likes, comments) hangs off those two.

The two halves map to the two subsystems:

- The **media pipeline** takes your original photo, produces several sizes of it, and distributes copies to CDN edges worldwide so delivery is fast.
- The **feed** decides *which* posts land in your feed based on who you follow. That's fan-out + ranking.

### Why compare it to both Twitter and YouTube

It looks like two systems bolted together, and that's the whole point. Most feed apps (Twitter) are text-first, so they're "just" a fan-out problem. Most video apps (YouTube) are media-first, so they're "just" a pipeline + CDN problem. Instagram is unusual because it's **genuinely both at once** — heavy media *and* a personalized follow feed. When interviewing, saying "this is YouTube's media half plus Twitter's feed half" instantly shows you see the shape of the problem.

### The unidirectional follow graph

- **Bidirectional (Facebook friends):** if we're friends, I see your posts and you see mine — it's mutual, always symmetric.
- **Unidirectional (Instagram/Twitter follow):** I can follow a celebrity who has *no idea I exist*. The arrow goes one way.

Why it matters: because follows are one-way and unlimited, one account (a celebrity) can have **100M followers**. That single fact creates the hardest problem in the whole design — the **celebrity / hot-key problem** (§7). Private accounts add a twist: a follow doesn't count until the owner **approves** it (`PENDING` → `ACCEPTED`).

---

## 2. Requirements

**Functional**
- Upload **photo/video** posts (caption, filters, location, tags, carousel).
- **Follow/unfollow** (unidirectional; **private accounts** need approval); home **feed**.
- **Likes, comments**; **Stories** (24h ephemeral, view-state); **Explore/Reels** (discovery); DMs (separate chat system).

**Non-functional**
- Fast media upload + **global low-latency delivery** (CDN); read-heavy feed; huge scale; **eventual consistency OK**.

### Functional vs non-functional, and "eventual consistency OK"

- **Functional requirements** = *what the app does* — the features a user can name: post a photo, follow someone, see a feed, like, comment, watch a Story.
- **Non-functional requirements** = *how well it must do them* — speed, scale, reliability. Users don't say "I want low latency," but they *feel* it when the app is slow.

**The single most important non-functional line here: "eventual consistency OK."**

For example, when you post a photo, it's fine if your friend in Australia sees it **3 seconds** after your friend next door does. Nobody is harmed. Compare that to a **bank transfer**, where everyone must agree on your balance *instantly* (strong consistency), or you get double-spending.

```
Strong consistency  → "everyone sees the exact same thing at the exact same instant" (banks) — expensive, slow
Eventual consistency → "everyone converges to the same thing within a few seconds"  (social feeds) — cheap, fast
```

### Why "read-heavy" changes the design so much

For every *one* time you post a photo, that photo might be *viewed* millions of times. So:

```
writes (uploads)  : rare-ish
reads  (views/feed): astronomically more common   →   optimize HARD for reads
```

That imbalance is the justification for the two biggest design choices: **precompute feeds** (do the work once at write time so reads are cheap) and **CDN caching** (copy media close to viewers so reads never hit your servers). If it were write-heavy, we'd design completely differently.

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

### Reading the back-of-envelope math

These numbers aren't trivia — each one *points at a specific design decision*. Let's translate them:

| The number | What it really tells us | So we decide... |
| --- | --- | --- |
| 2B users, 1B DAU | Massive, always-on audience | Everything must shard/scale horizontally |
| 100M+ posts/day | Writes are big but not the bottleneck | Post metadata fits in a (sharded) DB |
| billions of views/day | **Reads dominate everything** | CDN + precomputed feeds |
| photo → ~4 sizes, video → 5–10× source | Media *balloons* after processing | Cheap blob storage, not a DB |
| exabytes of media vs small metadata | Two totally different storage needs | Blob store for bytes, DB for the tiny facts |

**The key mental split:** there are two kinds of data with wildly different sizes.

```
BIG stuff  (the actual photo/video bytes)  → blob store (S3) + CDN     ← exabytes
TINY stuff (who posted what, when, caption) → database (metadata only) ← gigabytes
```

#### Q: Why not just store the photos *in* the database?

A photo is a few megabytes; a database row should be a few *hundred bytes*. If you shove multi-MB blobs into your DB:

- The DB bloats to exabytes → backups, replication, and queries all crawl.
- You can't put a database in 200 cities worldwide, but you *can* put CDN edges there.

So the rule everyone follows: **the database stores a tiny pointer (a URL) to the media; the bytes live in a blob store fronted by a CDN.**

```
posts table row:  { post_id: 42, author: 7, caption: "sunset", media_url: "https://cdn/.../42_feed.jpg" }
                                                                └── just a string; the 3 MB file lives in S3+CDN
```

### What "the origin is barely touched" means

The **origin** = your own blob store (the master copy). The **CDN** = hundreds of cache servers near users. When 10M people view a photo, the CDN serves ~9.99M of them from a nearby edge cache; only the *first* request in each region ("cache miss") reaches your origin. So your own servers see a tiny trickle of the actual view traffic. That's how a startup-sized origin can survive Instagram-sized read volume.

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

### Why so many separate services?

At first this box-diagram looks like overkill — why not one big program? Because each job has **wildly different needs**, and you want to scale/fix/deploy them independently. If uploads spike, you add media workers without touching the feed service.

| Service | Its one job | Why it's separate |
| --- | --- | --- |
| **API Gateway** | Front door: auth, routing, rate-limit | One place to guard everything |
| **Media Service** | Hand out upload URLs, run the processing pipeline | Media is CPU-heavy (transcoding) — scale on its own |
| **Post Service** | Store post metadata (caption, author, status) | Tiny, transactional — a normal DB |
| **Graph Service** | Follows / private-approval | The follow graph has its own scaling shape |
| **Fan-out + Feed** | Build, rank, and serve feeds | The read-heavy hot path — needs Redis + ML |
| **Story Service** | Stories with 24h TTL | Ephemeral data, different lifecycle |
| **Explore/Search** | Recommend content you don't follow | ML + search index (Elasticsearch) |

### What Kafka is doing sitting in the middle of all this

Kafka is a **message queue / event log**. When something happens, a service **publishes an event** to Kafka instead of directly calling ten other services. Everyone interested **subscribes**.

```
You post a photo →  Post Service drops a "POST_CREATED" event on Kafka
                         │
       ┌─────────────────┼──────────────────┬───────────────────┐
       ▼                 ▼                  ▼                   ▼
   Fan-out svc      Search indexer     Notification svc     Analytics
 (put in feeds)    (make searchable)   (tell followers)    (count it)
```

Why this is huge: the Post Service doesn't need to *know* about search, notifications, or analytics. It just shouts "new post!" and moves on. You can add a new listener (say, a fraud detector) **later without changing the Post Service at all**. This decoupling is the **Observer / Pub-Sub** pattern (§19).

#### Q: Isn't calling other services directly simpler than routing everything through Kafka?

For 2 services, yes. For 10 services during a traffic spike, no — direct calls create a fragile web where one slow service stalls everyone. Kafka acts as a **buffer**: if the notification service is down, events pile up safely in Kafka and get processed when it recovers, instead of failing the user's upload.

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

### The upload flow, step by step

The upload is decoupled from processing: the client uploads the original quickly and gets a response immediately, while the heavy work (making sizes, transcoding) happens afterward in the background. **Fast drop-off now, processing later.**

### Pre-signed upload URLs — why not just upload to the Media Service?

A pre-signed URL is a **temporary, permission-stamped link straight into the blob store** (S3). The Media Service says "here's a link that lets you PUT one file into this exact spot for the next 10 minutes," and the client uploads the big file *directly to S3* — the file bytes never pass through your app servers.

```java
// 1. Client asks for a place to put the file
UploadUrlResponse res = mediaService.getUploadUrl(userId, "photo.jpg");
//    res.url  = "https://s3.../uploads/abc123?signature=...&expires=..."
//    res.key  = "uploads/abc123"

// 2. Client uploads the ORIGINAL straight to S3 (not through our servers!)
httpClient.put(res.url, fileBytes);   // 3 MB goes S3-direct — app servers untouched
```

Why: routing every 3 MB (or 300 MB video) upload *through* your servers would waste their bandwidth and CPU. Pre-signed URLs let S3 absorb the heavy lifting. Your server only handles the tiny "give me a URL" request.

### What happens after the file lands in S3

An event fires and **background workers** do the slow work. Here's the pipeline in code shape:

```java
// A worker picks up "this file finished uploading" from Kafka and processes it
@KafkaListener(topics = "media-uploaded", groupId = "media-workers")
public void process(MediaUploadedEvent e) {
    byte[] original = blob.get(e.key());          // the raw file the user uploaded

    if (e.type() == PHOTO) {
        // make several sizes from the ONE original
        blob.put(variant(e, "thumb"), resize(original, 150));   // profile grid
        blob.put(variant(e, "feed"),  resize(original, 1080));  // in-feed
        blob.put(variant(e, "full"),  resize(original, 2048));  // full-screen tap
        String hash = blurhash(original);                       // tiny blurred preview
        stripExif(original);                                    // remove GPS/camera data (privacy)
    } else { // VIDEO
        for (int bitrate : List.of(360, 720, 1080))
            blob.put(variant(e, "hls_" + bitrate), transcodeHls(original, bitrate));
    }

    cdn.push(e.postId());                          // copy variants out to edge caches
    postService.markReady(e.postId());             // PROCESSING → READY (state machine)
}
```

**Why make multiple sizes up front?** So each screen downloads only what it needs. Sending a 3 MB full-res photo to fill a 150px profile-grid thumbnail wastes ~95% of the bytes. Pre-making a `thumb` means the grid loads instantly.

### Blurhash and EXIF stripping

- **Blurhash** = a ~30-character string that encodes a *blurry* version of the image. The app shows that instant colored blur while the real photo downloads, so you never stare at a blank gray box. (Think of it as a "loading preview" baked into the metadata.)
- **EXIF stripping** = photos secretly embed the **GPS location and camera model** in their metadata. Instagram deletes that before publishing so you don't accidentally leak your home address in a selfie. Pure privacy.

### Why "parallel + retryable" — what happens if transcoding fails

Each variant is an independent task, so many workers crunch them **in parallel** (fast). And media work is flaky (a transcode can OOM or time out), so each task is **retryable** — if the `full` size fails, that *one* task re-runs without redoing the others. After N failures the post is marked `FAILED` and the user is told. This is the **Producer-Consumer** pattern: Kafka holds the "to-do" jobs, a pool of workers consumes them.

---

## 6. Media Delivery (CDN)

- Variants served from **CDN edge caches** near the viewer → low latency; origin (blob) hit only on miss.
- Serve the **right size per context** (thumb in grid, feed in feed, full on tap) → save bandwidth.
- Immutable media URLs → cache forever (long TTL); **signed URLs** for private content.
- Video uses **adaptive bitrate (HLS)** like the Video Streaming note.

### What a CDN actually is

A **CDN (Content Delivery Network)** keeps **copies of your media in hundreds of cities** instead of serving the whole planet from one origin server. Each viewer downloads from a nearby edge server a few milliseconds away rather than a distant origin.

```
Without CDN:  user in Tokyo ──────── 10,000 km ────────► your server in Virginia   (slow, 300ms)
With CDN:     user in Tokyo ─► CDN edge in Tokyo (already has the photo)            (fast, 10ms)
```

### How a file gets to the Tokyo edge in the first place

Lazily, on the **first** request (a "cache miss"):

```
1. First Tokyo user requests photo 42  → edge doesn't have it yet (MISS)
                                        → edge fetches once from origin (S3), keeps a copy
2. Next 1,000,000 Tokyo users          → edge already has it (HIT) → served locally, origin untouched
```

So the origin is hit **once per region per file**, and the CDN absorbs the millions of repeat reads. That's why "origin barely touched" (§3).

### Why "serve the right size per context"

Bandwidth is the #1 cost. The client requests a *different variant URL* depending on where the image appears:

```java
String urlFor(Post p, DisplayContext ctx) {
    return switch (ctx) {
        case PROFILE_GRID -> p.media().thumb();  // ~150px, ~10 KB   — tiny
        case HOME_FEED    -> p.media().feed();   // ~1080px, ~150 KB — medium
        case FULL_SCREEN  -> p.media().full();   // ~2048px, ~600 KB — big, only when tapped
    };
}
```

Showing the `thumb` in a grid of 30 photos downloads ~300 KB total instead of ~90 MB of full-res. Massive savings, multiplied by billions of views.

### Immutable URLs → cache forever, and signed URLs

- **Immutable URL:** each processed variant gets a **unique URL that never changes its content** (e.g. `.../42_feed_v1.jpg`). Because the bytes at that URL will *never* change, the CDN can cache it essentially forever (long TTL) — no need to re-check the origin. If you edit the media, you publish a *new* URL, you never overwrite the old one.
- **Signed URL:** for **private** content, the URL carries a short-lived cryptographic signature (`?expires=...&sig=...`). Without a valid, unexpired signature the CDN refuses to serve the file — so a leaked link stops working after a few minutes. Public posts skip this; private posts and Stories use it.

### Adaptive bitrate (HLS) for video

Instead of one video file, you store the same video at several qualities (360p, 720p, 1080p) chopped into small chunks. The player **switches quality on the fly** based on your network: on strong Wi-Fi it grabs 1080p chunks; when you walk into an elevator and Wi-Fi drops, it seamlessly downgrades to 360p so playback never stalls. (Same idea as the Video Streaming note.)

### CDN cache invalidation when a post is deleted

Because media URLs are immutable and cached with a long TTL, deleting a post can't rely on "the cache will expire soon" — the edge might hold that photo for days.

> ⚠️ **pitfall:** a **CDN purge** (telling every edge to drop a URL) is slow and rate-limited — you cannot purge millions of URLs synchronously on every delete, and a global purge can take seconds to minutes to propagate. Never block the delete request on it.

The practical approach separates *access control* from *cache cleanup*:

```
1. Delete → flip post to tombstoned in DB (instant; feed hydration now filters it out, §13)
2. For PRIVATE/sensitive media → issue an async CDN purge for those specific URLs
3. For public media → let it age out via TTL; the tombstone already hides it from every feed/profile
```

So the *authoritative* "it's gone" happens at the DB (hydration skips it immediately); the CDN purge is a **best-effort async cleanup**, not the enforcement point. Signed-URL content is doubly safe — even a still-cached object stops serving once its signature expires.

---

## 7. Follow Graph & Feed (fan-out + ranking)

Unidirectional **follow** graph + **fan-out** feed (same trade-offs as Twitter; see [Fan-Out / Fan-In](../concepts/fan-out-fan-in.md)).

> 💡 **tip:** "**fan-out on write**" = do the work *when someone posts* (copy the post into followers' feeds). "**fan-out on read**" = do the work *when someone opens the app* (gather followees' posts live). Whenever you hear "fan-out," ask *"at write time or read time?"* — that single question frames the entire feed design.

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

### The feed is the heart of the whole design

Your home feed = "recent posts from people I follow, best stuff on top." Sounds trivial. It is *the* hardest scaling problem in the system. The core question:

> **When should we do the work of building your feed — when someone POSTS, or when you OPEN the app?**

That single choice is called **fan-out**, and there are two extremes.

#### Fan-out on WRITE (push) — do the work when someone posts

The moment I post, immediately **copy the post's id into the feed of every one of my followers**. When you open the app, your feed is already sitting there, pre-built.

```java
// Runs when a post is created (triggered by POST_CREATED on Kafka)
void fanOutOnWrite(Post post) {
    List<Long> followers = graph.followersOf(post.authorId());   // e.g. 500 people
    for (long followerId : followers) {
        // push this post id into each follower's precomputed feed (a Redis sorted set,
        // scored by time/rank so it stays ordered)
        redis.zadd("feed:" + followerId, post.rankScore(), post.id());
    }
}
```

- ✅ **Reads are instant** — the feed is already built. Perfect for a read-heavy app.
- ❌ **Writes explode for popular accounts.** If a celebrity with 100M followers posts, this loop does **100 million writes** for one post. That's **write amplification** — the killer problem.

> ⚠️ **pitfall:** never fan-out-on-write **synchronously** inside the post request. Even for a normal account with a few thousand followers, doing thousands of Redis writes before returning would make posting feel slow. Fan-out runs **async off `POST_CREATED`** (Kafka) — the user's post returns instantly and feeds fill in a beat later.

#### Fan-out on READ (pull) — do the work when you open the app

Store nothing ahead of time. When you open the app, **go gather** the recent posts from everyone you follow, right then.

```java
// Runs when a user opens the app
List<Post> fanOutOnRead(long userId) {
    List<Long> followees = graph.followeesOf(userId);   // people I follow
    List<Post> candidates = new ArrayList<>();
    for (long followeeId : followees)
        candidates.addAll(posts.recentBy(followeeId));  // fetch their recent posts NOW
    return rank(candidates);                            // sort best-first, then return
}
```

- ✅ **Writes are cheap** — posting does zero fan-out work.
- ❌ **Reads are slow & expensive** — if you follow 2,000 people, every feed open queries 2,000 accounts. Multiply by a billion daily users = disaster.

#### The winner: HYBRID (push for normal, pull for celebrities)

Notice the two approaches fail in *opposite* directions. Push dies on celebrities (too many followers to write to). Pull dies on normal reads (too many followees to gather). So do **both, each where it wins**:

```java
List<Post> buildFeed(long userId) {
    // 1. Normal accounts already pushed their posts into my feed → read it instantly (cheap)
    List<Long> pushedPostIds = redis.zrange("feed:" + userId, 0, 500);

    // 2. Celebrities I follow did NOT push (too expensive) → pull their recent posts live
    List<Long> celebs = graph.celebrityFolloweesOf(userId);   // usually a small handful
    List<Post> pulledCelebPosts = new ArrayList<>();
    for (long c : celebs)
        pulledCelebPosts.addAll(posts.recentBy(c));

    // 3. Merge both sources, rank by ML, then hydrate media URLs
    List<Post> merged = merge(hydrate(pushedPostIds), pulledCelebPosts);
    return rank(merged);
}
```

**Why this works:** a celebrity has 100M followers but you follow only a *few* celebrities — so pulling their posts is cheap *for you*, and it saves the celebrity from doing 100M writes. Normal accounts have few followers, so pushing is cheap. Each strategy is used exactly where it's efficient. This is the **celebrity / hot-key problem** and its standard solution.

#### Q: Why store post *ids* in the feed, not the whole posts?

Because the same post appears in millions of feeds. If you copied the full post (caption, media URLs, like count) into every feed, you'd store it a million times **and** the like count would be frozen at whatever it was when you fanned it out. Instead, store just the tiny **id**, and at read time **hydrate** — look up the current media URLs and live counts.

```java
// Feed cache holds ids only:  feed:me = [ 991, 887, 743, ... ]   ← tiny
// At read time, hydrate each id into a full object with FRESH data:
FeedItem hydrate(long postId) {
    Post p = posts.get(postId);                 // caption, author, status
    p.attachMedia(postMedia.variantsFor(postId)); // current CDN URLs (thumb/feed/full)
    p.attachCounts(counters.get(postId));         // LIVE like/comment counts
    return p;
}
```

Bonus: this is the **CQRS + Materialized View** pattern — the precomputed feed is a fast read-model; the source-of-truth posts live separately.

### Ranking — why not just show newest-first

Newest-first (pure chronological) buries good posts under noise. **Ranking** scores each candidate post and sorts best-first using signals like:

```
score = f(recency, engagement (likes/comments), affinity (how much you interact with this author), media type, ...)
```

That's the "ML rank" box. It's why your feed shows your best friend's photo above a stranger's, even if the stranger posted more recently.

### How private accounts fit in

A follow row has a `status`: `PENDING` or `ACCEPTED`. For a **private** account, a new follow starts as `PENDING` and the owner must **approve** it. Fan-out only pushes a post to followers whose follow is `ACCEPTED`:

```java
List<Long> followers = graph.followersOf(authorId).stream()
    .filter(f -> f.status() == ACCEPTED)   // private posts skip PENDING requesters
    .toList();
```

So requesting to follow a private account does **not** let you see their posts until they tap "Approve."

### Push notifications are just another fan-out

Likes, comments, new-follower alerts, and "someone you follow just posted" pushes are **the same fan-out problem wearing a different hat**: an event happens, and it must reach N interested devices. They ride the exact same event backbone as the feed.

```
LIKE / COMMENT / FOLLOW / POST_CREATED on Kafka
        │
        ▼
 Notification service → build message → device-token store → APNs (iOS) / FCM (Android)
```

- **Targeted events** (like/comment/follow) fan out to **one** recipient — trivial.
- **"New post from someone you follow"** has the *same celebrity skew* as the feed: a celebrity posting could notify 100M people. So the same **hybrid** instinct applies — throttle/batch/coalesce (e.g. "3 people you follow posted") and treat these as **best-effort**, not guaranteed.

> 💡 **tip:** notifications are **fire-and-forget over Kafka** — if the push service is briefly down, events buffer and drain later; a lost like-notification is annoying, not a correctness bug. Contrast with the feed, which is rebuildable from source-of-truth data.

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

> 💡 **tip:** **TTL** (time-to-live) = "auto-delete after N seconds." Stamp `expires_at` once and the data cleans *itself* up — you never write a per-item deletion timer. It's the recurring trick for anything short-lived (Stories, story-views, feed cache entries).

### Stories = posts with a 24h expiry

A Story is basically a post that **auto-deletes after 24 hours**. That one twist (temporary) changes how you store it.

Because a Story is temporary, you don't need the permanent, carefully-archived storage a normal post gets — you just need it *fast* while it's live, then let it expire.

### How "expires after 24h" actually works

You don't run a timer per story. You just **stamp an `expires_at` timestamp** and let two mechanisms handle it:

```java
Story s = new Story();
s.setCreatedAt(now());
s.setExpiresAt(now().plusHours(24));   // the self-destruct time is just a column

// (1) When assembling the tray, only ask for still-alive stories:
//     SELECT ... FROM stories WHERE author_id IN (:followees) AND expires_at > now()
// (2) A background sweeper periodically deletes rows/media where expires_at < now()
// (3) Cache entries carry a TTL so they vanish on their own
```

So "expiry" = *filter out expired ones on read* + *a janitor cleans up later*. Simple and cheap.

### The "stories tray" and how it's built

The tray = that row of circles at the top of the app (one per person you follow who has an active story). It's assembled at read time from your followees' unexpired stories, grouped by author, with **unseen ones shown first** (that's why the colorful ring means "unwatched"):

```java
List<StoryBubble> tray(long userId) {
    List<Long> followees = graph.followeesOf(userId);
    List<Story> active = stories.activeFor(followees);          // expires_at > now()
    Map<Long, List<Story>> byAuthor = groupByAuthor(active);
    return byAuthor.entrySet().stream()
        .sorted(unseenFirst(userId))     // people with unwatched stories float to the left
        .map(StoryBubble::from)
        .toList();
}
```

### "View-state writes are huge but ephemeral"

Instagram tracks **who viewed each story** (to show you the "seen by" list and the unseen ring). Every single view of every single story = a write. That's an enormous write volume. But here's the relief: that data only matters for 24 hours, then it's garbage.

```
story_views keyed by (story_id, viewer_id)  →  written on every view (huge volume)
                                            →  stored in a fast store (Cassandra/Redis)
                                            →  expires WITH the story (24h TTL) → self-cleaning
```

Because it's short-lived, you can use a cheap, fast, write-optimized store and never worry about it piling up forever — the TTL is your free garbage collector.

### The TTL sweeper, mechanically

"Expired" content is hidden **the instant it's stale** (the `expires_at > now()` filter on read), but the *bytes and rows* still need reclaiming. Two layers do that, and it's worth knowing which does what:

| Layer | Mechanism | Reclaims |
| --- | --- | --- |
| **Cache (Redis)** | native per-key **TTL** — Redis evicts on its own | tray/view-state cache entries |
| **DB rows** | a **sweeper** job scans `WHERE expires_at < now()` in batches and deletes | `stories` / `story_views` rows |
| **Blob media** | sweeper enqueues the media keys → async blob delete + CDN age-out | the actual image/video bytes |

```java
@Scheduled(fixedDelay = 60_000)   // every ~1 min, like a cron
void sweepExpiredStories() {
    List<Story> dead = stories.findExpired(now(), /*batch*/ 1000);  // WHERE expires_at < now()
    for (Story s : dead) blob.enqueueDelete(s.mediaRef());          // reclaim bytes async
    stories.deleteAll(dead);                                        // reclaim rows
    // story_views rows expire with their story (same TTL) → self-cleaning
}
```

Key point: **correctness never depends on the sweeper running on time.** Reads already filter by `expires_at`, so a story is invisible the moment it expires even if the janitor is late — the sweeper only reclaims space, it isn't the enforcement point (same philosophy as the CDN purge in §6).

---

## 9. Explore / Discovery

- Content from accounts you **don't** follow, ranked by interest (ML recommendations) — the growth engine.
- **Candidate generation** (trending, similar-to-liked, topics/hashtags, accounts like ones you follow) → **ML ranking** → cache.
- Backed by a search/recommendation pipeline (Elasticsearch + embedding similarity + engagement signals), rebuilt continuously.

### Explore = the feed for content you don't follow

Your **home feed** shows people you *chose* to follow. **Explore** shows content from people you *don't* follow but might like. It's how Instagram grows — it keeps you scrolling past your own follow graph.

Explore surfaces content from accounts you don't follow, chosen by a recommender based on what you've engaged with — the mechanism that drives discovery.

### How it decides which strangers' content to show you

Two stages — **candidate generation** then **ranking** (a very common recommender-system shape):

```java
List<Post> explore(long userId) {
    // STAGE 1 — cast a wide net: gather thousands of plausible candidates cheaply
    Set<Post> candidates = new HashSet<>();
    candidates.addAll(trendingPosts());                    // popular right now
    candidates.addAll(similarToLiked(userId));             // like posts you've liked (embeddings)
    candidates.addAll(fromTopics(interestsOf(userId)));    // hashtags/topics you engage with
    candidates.addAll(popularAmongSimilarUsers(userId));   // "people like you also liked"

    // STAGE 2 — score them precisely with ML, keep the best, cache the result
    return rankByPredictedEngagement(candidates, userId);
}
```

Stage 1 is fast-and-rough (get ~1000 maybes); Stage 2 is slow-and-precise (rank them well). You do the expensive ranking on only the survivors, not the whole catalog.

### What Elasticsearch and "embedding similarity" are doing here

- **Elasticsearch** = a search engine that finds posts by text/hashtag/caption quickly.
- **Embeddings** = each post (and each user's taste) is turned into a list of numbers (a vector) capturing "what it's about." Two posts with **nearby vectors** are similar in vibe, even without shared words. "Similar to what you liked" = "find posts whose vector is close to yours." This is rebuilt continuously as new posts and new engagement flow in.

> 💡 **tip:** an **embedding** is just "meaning as coordinates." A model reads a photo/caption and outputs, say, 256 numbers so that *similar content lands near each other in space*. "Recommend more like this" then becomes a geometry problem: **find the nearest vectors** — no keywords required.

#### Q: How does the two-stage recommender actually work, and is this Elasticsearch or a vector DB?

Explore (and modern feed ranking) is a **two-stage funnel**, because you can't run an expensive ML model over billions of posts per request:

```
Stage 1 — CANDIDATE GENERATION (cheap, wide):  billions of posts → ~1,000 plausible ones
   sources: trending, hashtags/topics, "users like you liked", and
            ANN vector search ("posts near your taste embedding")
Stage 2 — RANKING (expensive, narrow):         ~1,000 → ranked top ~50
   a heavier ML model scores predicted engagement (p(like), p(save), watch-time)
   using rich features (affinity, freshness, media type, author quality)
```

The point of the split: **be rough-and-cheap on the many, precise-and-costly on the few.** You only pay for the good ranking model on the ~1,000 survivors, never on the whole catalog.

Which store powers Stage 1 depends on the *kind* of match:

| Need | Store | Why |
| --- | --- | --- |
| Text / hashtag / caption lookup, faceted filters | **Elasticsearch** | inverted index — great at "posts tagged #sunset in the last day" |
| "Semantically similar to this" (embedding nearest-neighbor) | **Vector DB / ANN index** (FAISS, Milvus, pgvector, ES kNN) | indexes vectors for **approximate nearest-neighbor (ANN)** search — finds close vectors in ms |

In practice they're **complementary, not either/or**: ES handles keyword/lexical recall, the vector index handles semantic recall, and their candidate sets are merged before ranking. (Newer Elasticsearch even offers native kNN, blurring the line — but "text search → ES, similarity search → vector index" is the clean interview answer.)

---

## 10. Data Model (all tables)

### Database & storage choices (which DB, and why at scale)

No single database is best for every job here, so we use **polyglot persistence** — pick the store that matches each data type's access pattern. The deciding question for the core data is always *"does this need strong consistency and transactions?"* User/post/follow metadata does (a follow must not silently double-insert, a post's counters must reconcile) — an RDBMS is the source of truth there. Everything derived or oversized (media bytes, feeds, search) goes elsewhere.

| Data | Store | Why this one | Why not the alternative |
| --- | --- | --- | --- |
| Users, posts (metadata), follows, likes, comments (**source of truth**) | **RDBMS** (sharded by `user_id`/`author_id`) | Structured rows with real relationships (`follows`, `likes` composite keys) and moderate write volume (100M posts/day is trivial per-shard) — ACID gives "one like per user" and "no duplicate follow" for free via primary keys. | Cassandra would need the app to hand-roll the uniqueness/dedup logic (`PRIMARY KEY(user_id, post_id)`) that a relational PK gives natively; not worth it until write volume outgrows a sharded RDBMS (§14 discusses this ceiling). |
| Photo/video bytes, HLS renditions | **Blob store + CDN** (S3/CloudFront) | Multi-GB video and multi-size image variants are exactly what object storage + edge caching is built for — cheap, durable, served from the edge close to the viewer. | Storing bytes in the DB (even as `BLOB`) bloats every backup/replica and kills cache locality — the DB should hold a **pointer** (`variants JSONB`), never the pixels. |
| Precomputed home feed, stories tray, hot counters | **Redis** (sorted sets) | Feed reads happen on every app open — millions/sec — and must return in single-digit ms. A **sorted set** (`feed:{userId}`) keeps post ids pre-ordered by time/rank so "give me my feed" is one `ZREVRANGE`, no query. | Re-querying `posts` + `follows` and joining on every feed load doesn't survive Instagram's read fan-out; the feed is derived/rebuildable, so it's safe to keep it in a fast, lossy cache instead of the durable store. |
| Explore/search (users, hashtags, captions) | **Elasticsearch** | Full-text + faceted browse (hashtags, captions, trending) needs an inverted index, not `LIKE '%...%' ` scans. Rebuilt from the RDBMS via CDC, so it's a disposable read model. | The RDBMS has no relevance ranking or tokenized text search at this scale; ES is the CQRS read side dedicated to that job. |
| Feed at extreme scale (optional) | **Cassandra** | If a single sharded RDBMS ever can't keep up with post-metadata write throughput (not likely at 100M posts/day, but relevant past that), Cassandra's LSM engine absorbs huge write volume with tunable consistency. | Reaching for Cassandra by default sacrifices the transactional guarantees (dedup, referential sanity) the RDBMS gives essentially free — only adopt it once write throughput, not correctness, is the bottleneck. |

**Why RDBMS wins for the core graph/metadata, and how it scales:** the follow graph and post metadata need exact-once semantics (no duplicate follow, no double count) that eventually-consistent stores don't give without extra engineering — and the actual write volume (posts, follows, likes) is well within what a **sharded RDBMS** handles. We shard by `user_id` (§14: "shard = userId % N") so a user's own follows/posts/likes co-locate on one shard, keeping single-user reads/writes to a single machine; celebrities' massive *follower* counts are the skew case, handled by keeping fan-out hybrid (push+pull) rather than by resharding. Reads scale further with **read replicas** behind Redis — the DB is rarely hit for a feed render at all. (For the full engine trade-off matrix, see [Databases — Deep Dive](../concepts/databases-deep-dive.md).)

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

### Indexes that matter

A handful of indexes carry the hot paths; call these out explicitly in an interview:

- **`follows(followee_id)` where `status='ACCEPTED'`** — the **fan-out** index: "who follows this author?" Without it, every post would scan the whole `follows` table. The `WHERE status='ACCEPTED'` makes it a **partial index** so pending requests are never even scanned.
- **`follows(follower_id, followee_id)`** (the PK) — the *other* direction: "who do I follow?" for the pull path and profile checks.
- **`posts(author_id, created_at DESC)`** — powers a profile grid and the celebrity **pull** ("recent posts by author"), and keeps them time-ordered without a sort.
- **`stories(author_id, expires_at) where expires_at > now()`** — a **partial index** on *only live stories*, so tray assembly touches a tiny set instead of every story ever posted.
- **`likes(user_id, post_id)`** / **`story_views(story_id, viewer_id)`** (PKs) — the composite keys double as dedup ("one like per user", "one view row per viewer").

> 💡 **tip:** a **partial index** only indexes rows matching a predicate (`WHERE status='ACCEPTED'`, `WHERE expires_at > now()`). It stays small and fast *and* it stops the query from ever considering the irrelevant rows — the perfect fit for "only accepted follows" and "only live stories."

### Reading the schema

Each table maps to one real-world thing. Walk through them as "who / what / relationships":

| Table | Plain meaning | The clever bit |
| --- | --- | --- |
| `users` | one row per account | `is_private` flag drives approval; `follower_count` cached here |
| `follows` | "A follows B" — one row per follow | `status` = ACCEPTED/PENDING; index on `followee_id` powers fan-out |
| `posts` | one row per post (metadata only!) | `status` PROCESSING→READY hides half-baked posts; no bytes here |
| `post_media` | the actual media variants for a post | `variants JSONB` holds the CDN URLs; a carousel = many rows |
| `likes` / `comments` | engagement | keyed so a user can't double-like |
| `stories` / `story_views` | ephemeral content + who saw it | `expires_at` = the 24h self-destruct |
| `hashtags` / `post_hashtags` | tags and the many-to-many link | powers search/Explore |

### Why `follows` is keyed `(follower_id, followee_id)` and also indexed on `followee_id`

You ask the graph **two opposite questions**, and each needs its own fast lookup:

```sql
-- "Who do I follow?" (build MY feed by pulling) → uses the primary key's follower_id
SELECT followee_id FROM follows WHERE follower_id = :me;

-- "Who follows this author?" (fan-out a new post) → needs the extra index on followee_id
SELECT follower_id FROM follows WHERE followee_id = :author AND status = 'ACCEPTED';
```

Without the second index, fanning out a post would require scanning the entire follows table. The `WHERE status='ACCEPTED'` in the index definition is a **partial index** — it only indexes accepted follows, so fan-out never even looks at pending requests.

### Why `posts` has no image data — just a `status` and counts

As established in §3, bytes live in blob+CDN, not the DB. The `posts` row is the tiny "fact card":

```sql
-- This is the WHOLE post row — notice: no image bytes, just a pointer lives in post_media
post_id | author_id | caption   | type  | status | like_count | created_at
42      | 7         | "sunset"  | PHOTO | READY  | 1290       | 2026-07-08 ...
```

`status` matters because uploads are async (§5): the row is created as `PROCESSING`, and feeds **exclude** it until media finishes and it flips to `READY`. That's the **State** pattern guarding the post's lifecycle.

### What the `variants JSONB` in `post_media` holds

The map of size → CDN URL, so hydration (§7) can pick the right one per context:

```json
{
  "thumb": "https://cdn/.../42_thumb.jpg",
  "feed":  "https://cdn/.../42_feed.jpg",
  "full":  "https://cdn/.../42_full.jpg",
  "hls":   "https://cdn/.../42_master.m3u8"
}
```

One post can have many `post_media` rows (a **carousel** = swipeable multiple images), each with its own variants — that's why `post_media` is separate from `posts` (a one-to-many relationship).

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

### Why the API is shaped this way

The APIs mirror the design decisions above. Two patterns stand out.

### Why posting a photo is TWO calls (upload-url then posts)

This follows the decoupled upload-then-process flow (§5). You **can't** cram a 3 MB (or 300 MB video) upload into a normal JSON API call — so it's split:

```
1. POST /v1/media/upload-url      → server returns a pre-signed S3 link + a mediaId
2. (client PUTs the bytes straight to S3 using that link — not to our API)
3. POST /v1/posts { mediaIds, caption }  → creates the post row (status=PROCESSING)
                                          → returns immediately; workers finish media async
```

So the API "create post" call is tiny and instant; the heavy upload goes S3-direct, and processing happens in the background. The client polls or gets notified when `status` becomes `READY`.

### Cursor-based pagination — `?cursor=` in `GET /v1/feed?cursor=`

**Cursor-based pagination.** Instead of "page 1, page 2" (which breaks when new posts shift everything), each response returns a **cursor** — a bookmark pointing at "where you stopped." The next request passes it back to continue from exactly there:

```
GET /v1/feed                 → { posts: [...], nextCursor: "eyJvZmZzZXQiOjIwfQ" }
GET /v1/feed?cursor=eyJ...    → next 20 posts after the bookmark, no dupes/skips
```

This is the right choice for infinite-scroll feeds where new content constantly arrives at the top — page numbers would show duplicates or gaps; a cursor stays stable.

### Why `follow` and `follow-requests/{id}/approve` are separate endpoints

This follows from private accounts (§7). Following a **public** account is instant (`POST /follow` → ACCEPTED). Following a **private** account creates a `PENDING` request that the owner must act on — hence a distinct `approve` endpoint they call. Two different real actions → two different endpoints.

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

### Following one post from tap to feed

Let's trace a single photo through the whole system, tying the sections together. The write path has three stages: upload the original, process it into variants, then fan out the post id into followers' feeds. Each follower reads it when they open the app (feed read).

#### Upload → ready (the write path)

```
1. You tap "share"       → app asks for an upload URL, PUTs the photo straight to S3
2. S3 upload finishes    → a MEDIA_UPLOADED event lands on Kafka
3. Workers process       → make thumb/feed/full sizes, strip EXIF, push to CDN   (async, ~seconds)
4. Post flips PROCESSING → READY   (state machine)
5. POST_CREATED event    → fan-out service pushes the post id into followers' feeds
```

The reason your photo sometimes shows "posting..." for a moment is steps 3–4 running in the background.

#### Feed read (the read path)

```
1. You open the app      → FeedSvc reads your prebuilt feed:{you} from Redis (pushed ids)
2. Plus pulls posts from the few celebrities you follow (hybrid)
3. Ranks the merged list by ML (recency, engagement, affinity)
4. Hydrates each id → current CDN media URLs + live like/comment counts
5. Returns one page; the cursor bookmarks where to continue on scroll
```

### Why the read path is so much simpler/faster than the write path

This is the whole strategy for a read-heavy app (§2): **push the hard work to write time so reads stay cheap.** Fan-out-on-write did the expensive "who should see this?" step already, so opening the app is mostly "read a ready-made list + freshen the details." One writer's effort saves a million readers' effort.

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

### The tricky corner cases, decoded

These are the "what happens if..." questions an interviewer loves. Each row is a real situation and a pragmatic answer.

### What people see when a post's media isn't done processing yet

Nothing — and that's on purpose. The post sits at `status=PROCESSING` and is **excluded from every feed** until media finishes and it flips to `READY`. This prevents a broken/blank post from appearing. It's the **State pattern** acting as a gate.

```java
// Fan-out and feed hydration both skip non-ready posts
if (post.status() != READY) return;   // don't show a half-baked post
```

### How a celebrity's post avoids blowing up fan-out

The system **skips the push** for celebrities and **pulls at read** instead (the hybrid from §7). Detecting "celebrity" is usually just a follower-count threshold:

```java
boolean isCelebrity(long userId) {
    return graph.followerCount(userId) > 1_000_000;   // skip push above this line
}
```

#### Q: What about a deleted post that's already sitting in millions of feeds?

You **don't** hunt through millions of feed caches to yank it out — too expensive. Instead you **tombstone** it (mark deleted in the posts table) and **skip it during hydration**. The dead id may linger in feed caches, but it's silently filtered out when read, and the media is cleaned up asynchronously.

```java
FeedItem hydrate(long postId) {
    Post p = posts.get(postId);
    if (p == null || p.isDeleted()) return null;   // tombstone → skip, feed just shows one fewer
    ...
}
```

### Why like/comment counts are "approximate"

Counting exactly, in real time, for a viral post getting thousands of likes per second would hammer the DB with contention (the classic hot-row problem). So counts are **aggregated asynchronously** and cached — you might see "10,402" when it's really "10,418" for a few seconds. For a social app that's totally fine (eventual consistency again). Money would need exact counts; likes don't.

#### Q: "Feed freshness is eventual" — is that a bug?

No, it's a deliberate trade. A brand-new post might take a few seconds to fan out into your feed. Accepting that small delay is what lets the system stay fast and cheap at billions-of-users scale. The alternative (guaranteeing instant, globally-consistent feeds) would be enormously expensive for zero real benefit.

---

## 14. Scaling & Failure

- **Media** → blob + **CDN** (carries read bandwidth); multiple sizes save bandwidth; parallel + retryable processing.
- **Feed** = hybrid fan-out + rank; celebrities pulled; store ids, hydrate media URLs.
- **Follow graph** sharded by user; `follows(followee)` (ACCEPTED) drives fan-out.
- **Stories** auto-expire via TTL; view-state high-volume + short-lived (ephemeral store).
- **Counters** async-aggregated; approximate cached.
- **Private accounts** → follow requests (PENDING) + ACL on read.
- Post shows `PROCESSING` until media ready (state machine); failed media retried.

### How each piece scales, and what happens when it breaks

The trick to scaling is that each subsystem has a *different* pressure and a *different* release valve:

| Subsystem | The pressure | How it scales / survives failure |
| --- | --- | --- |
| **Media** | Huge read bandwidth | CDN carries reads; blob store is bottomless; workers parallel + retryable |
| **Feed** | Billions of reads/sec | Precompute (fan-out) + Redis cache; celebrities pulled to avoid write storms |
| **Follow graph** | Enormous + skewed (celebrities) | Shard by user; partial index on ACCEPTED follows |
| **Stories** | Massive short-lived writes | Ephemeral store + TTL auto-cleans |
| **Counters** | Hot-row contention on viral posts | Async aggregation, approximate cached counts |

### What actually breaks first under a traffic spike, and why it's OK

Usually the **feed build** and **media edges** feel it first. But the design is built to *degrade gracefully* rather than fall over:

- If **media workers** back up → posts just take a bit longer to go `READY` (users see "posting..."), nothing is lost — Kafka holds the backlog.
- If a **CDN edge** is cold → the first viewer waits a hair longer for a cache miss; everyone after is fast.
- If the **notification service** dies → uploads and feeds keep working; notifications catch up from Kafka later.

This is the payoff of decoupling everything through Kafka and caches: a failure in one service becomes a *slowdown in one feature*, not a full outage. That's called **graceful degradation**, and it's the goal of the whole failure story.

### What "shard by user" means for the follow graph

The follow data is too big for one machine, so you split it across many by user id (e.g. `shard = userId % N`). All of one user's follows live on one shard, so "who do I follow?" hits a single machine. The tricky part is celebrities (billions of *followers*), which is exactly why fan-out goes hybrid instead of pushing to a giant single list.

---

## 15. Interview Cheat Sheet

> **"How do you handle photo/video upload and delivery?"**
> "Client uploads the original to blob via a pre-signed URL; an event triggers async workers that generate multiple sizes/thumbnails (strip EXIF, blurhash) and HLS for video, store variants in blob, push to **CDN**, and mark the post READY. Viewers fetch the right size from the nearest edge; the DB holds only small metadata."

> **"How is the feed built?"**
> "Hybrid fan-out over the **follow** graph — push post ids into followers' Redis feeds for normal accounts, pull for celebrities, then rank (recency/engagement/affinity) and hydrate media URLs. Store ids, not media. Private accounts only fan out to approved followers."

> **"How do Stories work at scale?"**
> "Ephemeral media with a 24h `expires_at` + TTL; the tray shows unexpired followees' stories (unseen-first); view-state (who viewed) is huge but short-lived, kept in a fast store that expires with the story."

> **"Instagram vs Facebook/Twitter?"**
> "Unidirectional **follow** graph (like Twitter) with private-account approval, but **media-first** — a media pipeline + CDN (like YouTube) combined with fan-out feed, plus ephemeral Stories and ML-driven Explore."

> **"How does Explore/Reels pick content you don't follow?"**
> "A **two-stage recommender**: cheap **candidate generation** (trending + topics + embedding nearest-neighbor over your taste vector) narrows billions → ~1,000, then a heavier **ranking** model scores predicted engagement on just those. Text/hashtag recall from **Elasticsearch**, semantic recall from a **vector/ANN index**, merged then ranked."

### Tricky scenarios (rapid-fire)

| Scenario | What happens / what to do |
| --- | --- |
| **Deleted celebrity post already in millions of feeds** | Don't scrub every feed cache. **Tombstone** in the posts table; **hydration skips it** on read (feed just shows one fewer). Media reclaimed async; private media gets a **CDN purge** (§6). |
| **Fan-out backlog** (celebrity storm / worker lag) | Fan-out is async off Kafka → posts just land in feeds a few seconds late. Nothing lost; **feed freshness is eventual** by design. Celebrities are **pulled**, not pushed, so they don't create the storm in the first place. |
| **Private-account fan-out** | Fan-out filters to `status='ACCEPTED'` followers only; a `PENDING` requester sees nothing until approval. Enforced again as an **ACL check on read**. |
| **Hot counter** (viral post, 1000s of likes/sec) | Don't `UPDATE ... SET like_count = like_count+1` on one row (hot-row contention). **Async-aggregate** and cache an **approximate** count; exactness isn't needed for likes. |
| **Media still processing** | Post sits at `PROCESSING`, **excluded from feeds** until it flips `READY` (State pattern) — no half-baked post appears. |
| **Story expired but bytes still around** | Read filter (`expires_at > now()`) hides it instantly; a **TTL sweeper** reclaims rows/bytes later (§8). Correctness never waits on the sweeper. |

> **Ultimate layer model:** CDN = absorb read bandwidth · precomputed feed (Redis) = absorb feed reads · hybrid fan-out = tame the celebrity · async counters = tame hot rows · TTL/tombstone = clean up without blocking.

---

## 16. Reels

Reels are Instagram's short-form vertical video product. Architecturally they **reuse the media pipeline wholesale** but plug into a **different discovery surface** than the home feed.

```
Same as posts:  upload → transcode to HLS renditions → blob + CDN
Different:       the Reels tab is a recommendation feed (mostly content you DON'T follow),
                 not a follow-graph fan-out feed
```

### Reels tab vs Home feed vs Stories

| Surface | Candidate pool | Ordering | Lifetime |
| --- | --- | --- | --- |
| **Home feed** | posts from people you **follow** (hybrid fan-out) | ML rank (recency/engagement/affinity) | permanent |
| **Reels tab** | mostly accounts you **don't** follow (recommender) | ML rank for **watch-time / completion**, endless scroll | permanent |
| **Stories** | followees' **unexpired** (24h) stories | unseen-first, chronological | ephemeral (§8) |

The key insight: **same bytes, different brain.** A Reel's video runs through the identical transcode-to-HLS pipeline (§5) and is served from the same CDN (§6). What differs is the **candidate generation + ranking** — the Reels tab is essentially the Explore recommender (§9) specialized for video and optimized for *watch-time* rather than *likes*.

### Why watch-time changes the ranking

Home feed ranks for engagement (likes/comments/affinity). Reels ranks for **completion and watch-time** — did you watch it to the end, did you loop it, did you not swipe away. That signal drives an endless "just one more" scroll, so the ranking model's target label is different even though the two-stage funnel shape is the same.

### Prefetch — why Reels feel instant

> 💡 **tip:** the app **prefetches** the next few Reels' video chunks *while you're still watching the current one*, so the next swipe plays with zero buffering. Combined with CDN edge caching and adaptive bitrate (§6), the swipe feels instant. This is the same "do work ahead of time so the read is cheap" instinct as precomputing the feed.

- Prefetch the **next N** candidates' first chunks (low bitrate first → upgrade on the fly).
- Cache aggressively at the CDN edge — a viral Reel is served millions of times from cache, origin barely touched (§3).

---

## 17. Consistency & CAP Tradeoffs

> Interviewers love: "Where do you choose consistency vs availability?" Instagram's answer is **mostly AP/eventual** — the opposite of a booking system — with a few narrow spots that need strong guarantees.

| Path | Choice | Why |
| --- | --- | --- |
| **Follow dedup, likes/story-view uniqueness** | **CP** (strong, via PK) | the composite primary keys (`follows(follower_id, followee_id)`, `likes(user_id, post_id)`) make "one follow", "one like per user" an atomic DB guarantee — no double-follow, no double-like |
| **Feed freshness** | **AP** (available + eventual) | a new post appearing a few seconds late is fine; the feed stays fast and always serves *something* |
| **Like / comment / follower counters** | **Eventual** (approximate) | async-aggregated + cached; "10,402 vs 10,418" for a moment is harmless |
| **Media readiness** | **CP-ish** (state gate) | a post is hidden until `PROCESSING → READY`, so nobody sees a half-processed post |
| **Explore / notifications** | **Eventual** | downstream, async, retryable, best-effort |

- The **write correctness** we care about is *uniqueness/dedup* (enforced by primary keys on a single source-of-truth row), **not** cross-entity transactions — Instagram has no "never double-book" invariant like BookMyShow.
- Everything user-visible on the read path (feed, counts, Explore) is **eventually consistent across services** via Kafka fan-out, and that's a deliberate trade for scale.

> One-liner: **"Strong consistency only where a primary key must dedup (follows, likes); eventual consistency everywhere the eye can't tell — feeds, counters, Explore."**

---

## 18. How to Drive the Interview (framework)

> Use this order so you never freeze. Spend ~5 min on 1–4, then go deep on 5–7.

1. **Clarify requirements** (functional + NFRs; note "eventual consistency OK") — §2
2. **Estimate scale** (read-heavy; media bytes vs metadata split) — §3
3. **High-level architecture** (services + Kafka backbone) — §4
4. **Data model** (metadata in DB, bytes in blob+CDN; indexes that matter) — §10
5. **Deep dive: the two hard halves** → **media pipeline + CDN** and **feed fan-out** — §5–§7
6. **Deep dive: Stories, Explore/Reels, counters** — §8, §9, §16
7. **Address consistency, scale + edge cases** — §13, §14, §17

> 🎤 **Lead with the shape:** open with *"this is YouTube's media pipeline + CDN glued to Twitter's follow-feed fan-out."* Then name the crux of each half — **write amplification on celebrity fan-out** and **serving exabytes of media cheaply** — and say you'll spend most time there. Naming the shape up front signals seniority.

> 💡 **tip:** if asked to pick ONE thing to go deep on, choose **hybrid fan-out (push for normal, pull for celebrities)** — it's the single most-tested idea and it's where the interesting tradeoff lives.

---

## 19. Design Patterns (that can be used)

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

### How to actually use this table in an interview

Don't recite it. When you make a decision, **name the pattern** as justification: "I'll process media as a **pipeline** of retryable stages," or "feed fan-out is a **Strategy** so I can push for normal users and pull for celebrities." Naming the pattern signals you recognize the *shape* of the problem, which is exactly what interviewers score.

---

## 20. Final Takeaways

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
