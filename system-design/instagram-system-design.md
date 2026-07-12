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

### What are we actually building?

Imagine you're rebuilding Instagram from scratch. Strip away the branding and there are really just **two hard jobs glued together**:

1. **The photo/video part (like YouTube).** Someone posts a photo. You have to store that big file safely, make several sizes of it (tiny for the profile grid, medium for the feed, huge for full-screen), and then serve it to millions of people around the world *fast*. Big files + global audience = a **media pipeline + CDN** problem.
2. **The "who sees whose posts" part (like Twitter).** You **follow** people. Your home feed = recent posts from the people you follow, ranked so the good stuff is on top. Figuring out how to build that feed cheaply for a billion users = the **fan-out** problem.

Everything else (Stories, Explore, likes, comments) hangs off those two.

The two halves map to the two subsystems:

- The **media pipeline** takes your original photo, produces several sizes of it, and distributes copies to CDN edges worldwide so delivery is fast.
- The **feed** decides *which* posts land in your feed based on who you follow. That's fan-out + ranking.

#### Q: Why compare it to both Twitter *and* YouTube — isn't that two systems?

Yes, and that's the whole point. Most feed apps (Twitter) are text-first, so they're "just" a fan-out problem. Most video apps (YouTube) are media-first, so they're "just" a pipeline + CDN problem. Instagram is unusual because it's **genuinely both at once** — heavy media *and* a personalized follow feed. When interviewing, saying "this is YouTube's media half plus Twitter's feed half" instantly shows you see the shape of the problem.

#### Q: What's a "unidirectional follow graph" and why does it matter?

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

#### Q: Why does "read-heavy" change the design so much?

Because for every *one* time you post a photo, that photo might be *viewed* millions of times. So:

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

#### Q: What does "the origin is barely touched" mean?

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

#### Q: What is Kafka doing sitting in the middle of all this?

Kafka is a **message queue / event log**. When something happens, a service **publishes an event** to Kafka instead of directly calling ten other services. Everyone interested **subscribes**.

```
You post a photo →  Post Service drops a "POST_CREATED" event on Kafka
                         │
       ┌─────────────────┼──────────────────┬───────────────────┐
       ▼                 ▼                  ▼                   ▼
   Fan-out svc      Search indexer     Notification svc     Analytics
 (put in feeds)    (make searchable)   (tell followers)    (count it)
```

Why this is huge: the Post Service doesn't need to *know* about search, notifications, or analytics. It just shouts "new post!" and moves on. You can add a new listener (say, a fraud detector) **later without changing the Post Service at all**. This decoupling is the **Observer / Pub-Sub** pattern (§14).

#### Q: Isn't calling other services directly simpler?

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

#### Q: What is a "pre-signed upload URL" and why not just upload to the Media Service?

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

#### Q: What actually happens after the file lands in S3?

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

#### Q: What's blurhash, and what's EXIF stripping?

- **Blurhash** = a ~30-character string that encodes a *blurry* version of the image. The app shows that instant colored blur while the real photo downloads, so you never stare at a blank gray box. (Think of it as a "loading preview" baked into the metadata.)
- **EXIF stripping** = photos secretly embed the **GPS location and camera model** in their metadata. Instagram deletes that before publishing so you don't accidentally leak your home address in a selfie. Pure privacy.

#### Q: Why "parallel + retryable"? What if transcoding fails?

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

#### Q: How does a file get to the Tokyo edge in the first place?

Lazily, on the **first** request (a "cache miss"):

```
1. First Tokyo user requests photo 42  → edge doesn't have it yet (MISS)
                                        → edge fetches once from origin (S3), keeps a copy
2. Next 1,000,000 Tokyo users          → edge already has it (HIT) → served locally, origin untouched
```

So the origin is hit **once per region per file**, and the CDN absorbs the millions of repeat reads. That's why "origin barely touched" (§3).

#### Q: Why "serve the right size per context"?

Because bandwidth is the #1 cost. The client requests a *different variant URL* depending on where the image appears:

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

#### Q: What does "immutable URLs → cache forever" mean? And signed URLs?

- **Immutable URL:** each processed variant gets a **unique URL that never changes its content** (e.g. `.../42_feed_v1.jpg`). Because the bytes at that URL will *never* change, the CDN can cache it essentially forever (long TTL) — no need to re-check the origin. If you edit the media, you publish a *new* URL, you never overwrite the old one.
- **Signed URL:** for **private** content, the URL carries a short-lived cryptographic signature (`?expires=...&sig=...`). Without a valid, unexpired signature the CDN refuses to serve the file — so a leaked link stops working after a few minutes. Public posts skip this; private posts and Stories use it.

#### Q: What's "adaptive bitrate (HLS)" for video?

Instead of one video file, you store the same video at several qualities (360p, 720p, 1080p) chopped into small chunks. The player **switches quality on the fly** based on your network: on strong Wi-Fi it grabs 1080p chunks; when you walk into an elevator and Wi-Fi drops, it seamlessly downgrades to 360p so playback never stalls. (Same idea as the Video Streaming note.)

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

#### Q: What is "ranking" and why not just show newest-first?

Newest-first (pure chronological) buries good posts under noise. **Ranking** scores each candidate post and sorts best-first using signals like:

```
score = f(recency, engagement (likes/comments), affinity (how much you interact with this author), media type, ...)
```

That's the "ML rank" box. It's why your feed shows your best friend's photo above a stranger's, even if the stranger posted more recently.

#### Q: How do private accounts fit in?

A follow row has a `status`: `PENDING` or `ACCEPTED`. For a **private** account, a new follow starts as `PENDING` and the owner must **approve** it. Fan-out only pushes a post to followers whose follow is `ACCEPTED`:

```java
List<Long> followers = graph.followersOf(authorId).stream()
    .filter(f -> f.status() == ACCEPTED)   // private posts skip PENDING requesters
    .toList();
```

So requesting to follow a private account does **not** let you see their posts until they tap "Approve."

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

### Stories = posts with a 24h expiry

A Story is basically a post that **auto-deletes after 24 hours**. That one twist (temporary) changes how you store it.

Because a Story is temporary, you don't need the permanent, carefully-archived storage a normal post gets — you just need it *fast* while it's live, then let it expire.

#### Q: How does "expires after 24h" actually work?

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

#### Q: What's the "stories tray" and how is it built?

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

#### Q: "View-state writes are huge but ephemeral" — what does that mean?

Instagram tracks **who viewed each story** (to show you the "seen by" list and the unseen ring). Every single view of every single story = a write. That's an enormous write volume. But here's the relief: that data only matters for 24 hours, then it's garbage.

```
story_views keyed by (story_id, viewer_id)  →  written on every view (huge volume)
                                            →  stored in a fast store (Cassandra/Redis)
                                            →  expires WITH the story (24h TTL) → self-cleaning
```

Because it's short-lived, you can use a cheap, fast, write-optimized store and never worry about it piling up forever — the TTL is your free garbage collector.

---

## 9. Explore / Discovery

- Content from accounts you **don't** follow, ranked by interest (ML recommendations) — the growth engine.
- **Candidate generation** (trending, similar-to-liked, topics/hashtags, accounts like ones you follow) → **ML ranking** → cache.
- Backed by a search/recommendation pipeline (Elasticsearch + embedding similarity + engagement signals), rebuilt continuously.

### Explore = the feed for content you don't follow

Your **home feed** shows people you *chose* to follow. **Explore** shows content from people you *don't* follow but might like. It's how Instagram grows — it keeps you scrolling past your own follow graph.

Explore surfaces content from accounts you don't follow, chosen by a recommender based on what you've engaged with — the mechanism that drives discovery.

#### Q: How does it decide what to show a stranger's content to me?

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

#### Q: What's Elasticsearch / "embedding similarity" doing here?

- **Elasticsearch** = a search engine that finds posts by text/hashtag/caption quickly.
- **Embeddings** = each post (and each user's taste) is turned into a list of numbers (a vector) capturing "what it's about." Two posts with **nearby vectors** are similar in vibe, even without shared words. "Similar to what you liked" = "find posts whose vector is close to yours." This is rebuilt continuously as new posts and new engagement flow in.

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

#### Q: Why is `follows` keyed `(follower_id, followee_id)` and indexed on `followee_id`?

Because you ask the graph **two opposite questions**, and each needs its own fast lookup:

```sql
-- "Who do I follow?" (build MY feed by pulling) → uses the primary key's follower_id
SELECT followee_id FROM follows WHERE follower_id = :me;

-- "Who follows this author?" (fan-out a new post) → needs the extra index on followee_id
SELECT follower_id FROM follows WHERE followee_id = :author AND status = 'ACCEPTED';
```

Without the second index, fanning out a post would require scanning the entire follows table. The `WHERE status='ACCEPTED'` in the index definition is a **partial index** — it only indexes accepted follows, so fan-out never even looks at pending requests.

#### Q: Why does `posts` have NO image data, just a `status` and counts?

Because (from §3) bytes live in blob+CDN, not the DB. The `posts` row is the tiny "fact card":

```sql
-- This is the WHOLE post row — notice: no image bytes, just a pointer lives in post_media
post_id | author_id | caption   | type  | status | like_count | created_at
42      | 7         | "sunset"  | PHOTO | READY  | 1290       | 2026-07-08 ...
```

`status` matters because uploads are async (§5): the row is created as `PROCESSING`, and feeds **exclude** it until media finishes and it flips to `READY`. That's the **State** pattern guarding the post's lifecycle.

#### Q: What does the `variants JSONB` in `post_media` hold?

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

#### Q: Why is posting a photo TWO calls (upload-url then posts)?

Because of the decoupled upload-then-process flow (§5). You **can't** cram a 3 MB (or 300 MB video) upload into a normal JSON API call — so it's split:

```
1. POST /v1/media/upload-url      → server returns a pre-signed S3 link + a mediaId
2. (client PUTs the bytes straight to S3 using that link — not to our API)
3. POST /v1/posts { mediaIds, caption }  → creates the post row (status=PROCESSING)
                                          → returns immediately; workers finish media async
```

So the API "create post" call is tiny and instant; the heavy upload goes S3-direct, and processing happens in the background. The client polls or gets notified when `status` becomes `READY`.

#### Q: What's `?cursor=` in `GET /v1/feed?cursor=`?

**Cursor-based pagination.** Instead of "page 1, page 2" (which breaks when new posts shift everything), each response returns a **cursor** — a bookmark pointing at "where you stopped." The next request passes it back to continue from exactly there:

```
GET /v1/feed                 → { posts: [...], nextCursor: "eyJvZmZzZXQiOjIwfQ" }
GET /v1/feed?cursor=eyJ...    → next 20 posts after the bookmark, no dupes/skips
```

This is the right choice for infinite-scroll feeds where new content constantly arrives at the top — page numbers would show duplicates or gaps; a cursor stays stable.

#### Q: Why separate `follow` and `follow-requests/{id}/approve`?

Because of private accounts (§7). Following a **public** account is instant (`POST /follow` → ACCEPTED). Following a **private** account creates a `PENDING` request that the owner must act on — hence a distinct `approve` endpoint they call. Two different real actions → two different endpoints.

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

#### Q: Why is the read path so much simpler/faster than the write path?

That's the whole strategy for a read-heavy app (§2): **push the hard work to write time so reads stay cheap.** Fan-out-on-write did the expensive "who should see this?" step already, so opening the app is mostly "read a ready-made list + freshen the details." One writer's effort saves a million readers' effort.

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

#### Q: Someone posts, but the media isn't done processing. What do people see?

Nothing — and that's on purpose. The post sits at `status=PROCESSING` and is **excluded from every feed** until media finishes and it flips to `READY`. This prevents a broken/blank post from appearing. It's the **State pattern** acting as a gate.

```java
// Fan-out and feed hydration both skip non-ready posts
if (post.status() != READY) return;   // don't show a half-baked post
```

#### Q: What happens to a celebrity's post so it doesn't blow up fan-out?

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

#### Q: Why are like/comment counts "approximate"?

Because counting exactly, in real time, for a viral post getting thousands of likes per second would hammer the DB with contention (the classic hot-row problem). So counts are **aggregated asynchronously** and cached — you might see "10,402" when it's really "10,418" for a few seconds. For a social app that's totally fine (eventual consistency again). Money would need exact counts; likes don't.

#### Q: "Feed freshness is eventual" — is that a bug?

No, it's a deliberate trade. A brand-new post might take a few seconds to fan out into your feed. Accepting that small delay is what lets the system stay fast and cheap at billions-of-users scale. The alternative (guaranteeing instant, globally-consistent feeds) would be enormously expensive for zero real benefit.

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

### The patterns, in one line each with a "where"

Patterns are just named solutions to recurring problems. Here's each one grounded in *this* app:

| Pattern | The everyday idea | Where it shows up here |
| --- | --- | --- |
| **Pipeline** | An assembly line: output of one stage feeds the next | Media: resize → transcode → variants → publish |
| **Producer-Consumer** | A to-do list many workers pull from | Media workers, fan-out, counters (via Kafka) |
| **Strategy** | Swap the algorithm without changing callers | Feed fan-out (push/pull/hybrid), ranking |
| **State** | An object with guarded life stages | Post PROCESSING → READY → FAILED |
| **Observer / Pub-Sub** | Shout an event; interested parties react | POST_CREATED → feed, notifications, index |
| **CQRS + Materialized View** | Separate the write model from a fast read model | Precomputed feed vs source-of-truth posts |
| **CDN / Cache-Aside** | Keep copies close to the reader | Media at edges, feed in Redis |
| **TTL / Expiry** | Data that deletes itself | Stories + view-state (24h) |
| **Facade** | One simple door over a messy back room | Feed service hides graph+posts+media+rank |
| **Repository** | A clean layer for data access | Testable DB access |

#### Q: How do I actually use this table in an interview?

Don't recite it. When you make a decision, **name the pattern** as justification: "I'll process media as a **pipeline** of retryable stages," or "feed fan-out is a **Strategy** so I can push for normal users and pull for celebrities." Naming the pattern signals you recognize the *shape* of the problem, which is exactly what interviewers score.

---

## 15. Scaling & Failure

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

#### Q: What actually breaks first under a traffic spike, and why is that OK?

Usually the **feed build** and **media edges** feel it first. But the design is built to *degrade gracefully* rather than fall over:

- If **media workers** back up → posts just take a bit longer to go `READY` (users see "posting..."), nothing is lost — Kafka holds the backlog.
- If a **CDN edge** is cold → the first viewer waits a hair longer for a cache miss; everyone after is fast.
- If the **notification service** dies → uploads and feeds keep working; notifications catch up from Kafka later.

This is the payoff of decoupling everything through Kafka and caches: a failure in one service becomes a *slowdown in one feature*, not a full outage. That's called **graceful degradation**, and it's the goal of the whole failure story.

#### Q: What does "shard by user" mean for the follow graph?

The follow data is too big for one machine, so you split it across many by user id (e.g. `shard = userId % N`). All of one user's follows live on one shard, so "who do I follow?" hits a single machine. The tricky part is celebrities (billions of *followers*), which is exactly why fan-out goes hybrid instead of pushing to a giant single list.

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
