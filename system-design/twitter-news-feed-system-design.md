# Twitter / News Feed — System Design

> **Core challenge:** let users **post** short messages and see a **home timeline** of posts from everyone they follow, ranked and fresh — at massive scale where **reads ≫ writes** and some accounts have **tens of millions of followers**. The signature problem is **feed fan-out** (push vs pull) and the **celebrity/hot-key** problem.

> **How to read this doc:** each section has the dense interview summary first, then a **deep dive** (annotated code and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand.

---

## Contents

- [1. Mental Model](#1-mental-model)
- [2. Requirements](#2-requirements)
- [3. Capacity Estimation](#3-capacity-estimation)
- [4. Architecture](#4-architecture)
- [5. Feed Fan-out — Push vs Pull vs Hybrid](#5-feed-fan-out--push-vs-pull-vs-hybrid)
- [6. The Fan-out Pipeline](#6-the-fan-out-pipeline)
- [7. Timeline Store & Read Path](#7-timeline-store--read-path)
- [8. Ranking](#8-ranking)
- [9. Search & Trending](#9-search--trending)
- [10. Data Model (all tables)](#10-data-model-all-tables)
- [11. API Design](#11-api-design)
- [12. Sequences](#12-sequences)
- [13. Edge Cases](#13-edge-cases)
- [14. Scaling & Failure](#14-scaling--failure)
- [15. Interview Cheat Sheet](#15-interview-cheat-sheet)
- [16. Replies & Conversation Threading](#16-replies--conversation-threading)
- [17. Consistency & CAP Tradeoffs](#17-consistency--cap-tradeoffs)
- [18. How to Drive the Interview (framework)](#18-how-to-drive-the-interview-framework)
- [19. Design Patterns (that can be used)](#19-design-patterns-that-can-be-used)
- [20. Final Takeaways](#20-final-takeaways)

---

## 1. Mental Model

```
User posts a tweet → delivered into the home timelines of all followers
Home timeline = merge of tweets from everyone you follow, ranked by time/relevance
```

Read-heavy. The whole design hinges on **how the home timeline is assembled**: precompute per-user timelines (**push**), assemble at read time (**pull**), or a **hybrid**. (Deep dive on the pattern: [Fan-Out / Fan-In](../concepts/fan-out-fan-in.md).)

### What are we even building?

Picture Twitter/X. Two everyday actions:

- **You tweet** — you type "just landed in Tokyo" and hit post. That's a **write**.
- **You open the app** — you see a **home timeline**: a stream of the latest tweets from everyone you **follow**, newest-ish at the top. That's a **read**.

The entire system is basically answering one question, fast, for hundreds of millions of people:

> "Show me the recent tweets from all the people I follow."

**Why is that hard?** Because you follow maybe 300 people, each of them tweets, and you refresh the app constantly. So the app is asked "build my timeline" **way more often** than anyone actually tweets — reads dwarf writes (~100:1).

### Why not just query the database every time someone opens the app

"Get the latest tweets from the 300 people I follow, sorted by time" is a heavy query, and hundreds of thousands of people fire it **every second**. Running that live, per refresh, would melt any database.

The trick that drives the whole design:

> **Don't build the timeline when it's read. Build it ahead of time (precompute it) so a read is just "grab the ready-made list."**

That precompute-vs-compute-on-read decision is the single biggest fork in the road — it's called **fan-out** (push vs pull), and everything below is about getting it right.

#### Push vs pull

- **Push (precompute):** when a tweet is posted, its id is written into each follower's stored timeline immediately. A read just returns that ready-made timeline — instant. (But every post costs one write per follower.)
- **Pull (compute on read):** nothing is precomputed on write. On read, the system fetches the latest tweets from everyone you follow and merges them. (No per-follower write cost, but each read does a lot of work.)

The rest of this doc is about how to fill each timeline without incurring huge write costs when an account has 50 million followers.

---

## 2. Requirements

**Functional**
- Post a tweet (text/media); follow/unfollow; retweet, reply, like.
- **Home timeline** (people you follow) + **user timeline** (a person's own tweets).
- Search; trending topics; notifications.

**Non-functional**
- **Read-heavy** (reads ≫ writes, ~100:1); low-latency timeline (feels instant); huge scale (100s M users).
- **Eventual consistency** is fine (a tweet appearing a second late is OK); **highly available** (read the feed even if some parts are degraded).

### Reading the requirements

- **"Read-heavy, ~100:1"** — for every 1 tweet posted, the system serves ~100 timeline reads. So we happily do **extra work at write time** if it makes reads cheap. (This is why "precompute the timeline" wins.)
- **"Eventual consistency is fine"** — if your friend tweets and it shows up in your feed **2 seconds later**, nobody cares. Twitter is not a bank. This looseness is a *gift*: it lets us do fan-out **asynchronously** (in the background) instead of making the poster wait.
- **"Highly available"** — opening the app and seeing *something* is more important than seeing the *absolutely freshest* thing. A slightly stale feed beats an error page.

#### Q: What's the difference between "strong" and "eventual" consistency here, concretely?

- **Strong:** the instant you post, *every* follower's timeline is guaranteed updated before your post returns "success." Expensive and slow.
- **Eventual:** post returns "success" immediately; a background fleet then trickles your tweet into followers' timelines over the next moment. They'll all see it *soon*, just not *the same instant*. For a social feed, eventual is the right call.

---

## 3. Capacity Estimation

```
DAU ~ 200M · tweets ~ 500M/day (~6k/sec avg, 2–3× peaks)
Timeline reads ~ hundreds of thousands/sec  → must be precomputed/cached (never rebuilt per request)
Fan-out math:
   avg 200 followers → 1 tweet = ~200 timeline writes
   500M tweets/day × 200 = 100B timeline writes/day  → a huge async fan-out fleet
   celebrity 50M followers → 1 tweet = 50M writes  ← the hot-key / write-amplification problem
Storage:
   tweets 500M/day × ~300 B ≈ 150 GB/day of tweet content → sharded store + archive
   timelines: store IDS only (8 B each) × capped length → Redis-friendly
```

> Two pressures: **enormous fan-out write volume** (async workers) and **very high timeline read QPS** (precompute + cache). Celebrities break naive push.

### Where the numbers come from

The one line to internalize:

> **1 tweet is NOT 1 write. If you have 200 followers, 1 tweet becomes ~200 writes** (one into each follower's timeline). This is called **write amplification**.

Walk the math like a story:

```
You tweet once.                          → 1 tweet stored
You have 200 followers.                  → we copy its id into 200 timelines = 200 writes
The whole site posts 500M tweets/day.
500M tweets × 200 followers each         ≈ 100,000,000,000 timeline writes/day (!!)
```

That's why fan-out needs a whole **fleet of background workers** — no single machine copies 100 billion ids/day.

### Why store tweet ids in timelines, not the tweets themselves

A timeline is just a **list of pointers**, not a pile of full tweets.

```
Store the tweet ONCE:      tweet:987 = { "just landed in Tokyo", author=you, ... }  (~300 bytes)
Put only its ID everywhere: timeline:{follower} → [987, 986, 971, ...]              (8 bytes each)
```

A viral tweet followed by millions is stored **one time**; millions of timelines just hold the 8-byte number `987`. If we copied the full text into every timeline, storage would explode by ~40×. "Hydrate" (look up the full tweet by id) happens later, at read time.

### What the "celebrity" line in the estimate is about

```
celebrity with 50,000,000 followers tweets once → 50,000,000 timeline writes for ONE tweet
```

Copying one tweet id into 50 million timelines is a disaster (slow, bursty, overloads the machines holding those timelines). This is the **celebrity / hot-key problem** and it's the reason pure "push" doesn't work — solved by the **hybrid** approach in §5.

---

## 4. Architecture

```
Client → API Gateway
   ├── Tweet Service (write)     → tweet store (sharded by tweet_id) + tweet cache
   ├── Fan-out Service (workers) → consumes tweet events (Kafka) → writes follower timelines
   ├── Timeline Service (read)   → Redis timeline cache + pull celebrities + hydrate + rank
   ├── Graph Service             → follows (who-follows-whom), sharded by user
   ├── Search Service            → Elasticsearch (tweets, hashtags)
   └── Notification Service
              │
           Kafka (TWEET_CREATED → fan-out, search index, notifications, analytics)
```

- **Write path and read path are separate services** (CQRS) — the read path is optimized for the 100:1 read load.

### Who does what (the services)

Each box in the diagram is one service with one job:

| Service | Its one job |
| --- | --- |
| **Tweet Service** | Save the tweet, hand back an id |
| **Fan-out Service** | Copy the new tweet id into followers' timelines |
| **Timeline Service** | Assemble + return your home feed on read |
| **Graph Service** | Answer "who follows whom" |
| **Search Service** | Full-text search of tweets |
| **Kafka** | Carries "a tweet happened" events so every service can react |

### Why split "write path" and "read path" into different services (CQRS)

Posting a tweet and reading a timeline are **totally different shapes of work**:

- **Writing** (posting) is rare, and it's fine if it does heavy background work (fan-out).
- **Reading** (opening the app) is *constant* (100:1) and must feel instant.

If one service did both, you couldn't tune them independently. So we **C**ommand-**Q**uery-**S**eparate them: the write side optimizes for "accept the tweet fast and fan out in the background," the read side optimizes purely for "return a ready-made timeline in milliseconds."

```
WRITE side:  you post → Tweet Service → Kafka → Fan-out workers fill timelines   (do work now)
READ side:   you open app → Timeline Service → read your ready timeline           (just fetch)
```

### Why Kafka sits in the middle instead of Tweet Service calling Fan-out directly

One tweet triggers **many** independent reactions: fan-out, search indexing, notifications, analytics. Instead of Tweet Service knowing and calling all of them (tight coupling, and it'd have to wait for the slow fan-out), it just drops one `TWEET_CREATED` event on Kafka and moves on. Every interested team picks it up on its own schedule. This is the **pub/sub** pattern — the poster is decoupled from all the downstream work, and gets a fast response.

---

## 5. Feed Fan-out — Push vs Pull vs Hybrid

| Model | On post | On read | Trade-off |
| --- | --- | --- | --- |
| **Push (fan-out on write)** | Write tweet id into **every follower's** timeline | Read your precomputed timeline (fast) | Great reads; **write amplification** explodes for celebrities |
| **Pull (fan-out on read)** | Just store the tweet | Fetch recent tweets from all followees + merge | Cheap writes; **expensive reads** |
| **Hybrid** ✅ | Push for normal users; **skip celebrities** | Merge precomputed timeline **+ pull** followed celebrities' recent tweets | Best of both |

```
Hybrid:
  normal author posts → fan-out to followers' timeline caches (push, async)
  celebrity posts     → NOT fanned out; kept in their user timeline
  read home timeline  → merge(precomputed pushed timeline, pulled recent tweets of followed celebrities) → rank
```

> **The crux:** pure push dies on celebrities (50M writes/tweet); pure pull is slow on every read. **Hybrid** (push for the masses, pull for the handful of celebrities you follow) is the standard answer. Threshold on follower count (e.g. >100k = "celebrity", skip push).

### The single most important idea in the whole design

"Fan-out" just means: **when a tweet is created, how do we get it into the feeds of everyone who should see it?** There are two moments where we *could* do the work — when the tweet is written, or when the feed is read — and that choice is everything.

#### Push vs pull vs hybrid

- **Push = write the tweet id into every follower's timeline the moment it's posted.** Reads are instant (the timeline is prebuilt). But if an account has 50M followers, one tweet triggers 50M writes.
- **Pull = write nothing extra; on read, fetch each followed account's latest tweets and merge.** No per-follower write cost, but every read fetches from ~300 accounts and merges them — slow, and it happens on every refresh.
- **Hybrid = push for normal accounts, but for the few celebrities you follow, pull their tweets at read time.** You avoid the 50M-write explosion *and* keep reads fast, because you only pull for a tiny handful of celebrities.

#### Push (fan-out on write) — annotated

```java
// Runs in the BACKGROUND right after a normal user posts.
// Cost lands on the WRITER, once, and only for their (modest) follower count.
void fanOutOnWrite(Tweet tweet) {
    List<Long> followers = graph.getFollowers(tweet.authorId);   // e.g. 200 people

    for (long followerId : followers) {
        // push the tweet's ID into each follower's precomputed timeline (a sorted set)
        redis.zadd("timeline:" + followerId, tweet.createdAt, tweet.id);
        redis.zremrangeByRank("timeline:" + followerId, 0, -801); // keep only newest ~800
    }
}

// A READ is now trivial — the timeline is already built:
List<Long> readHome(long userId) {
    return redis.zrevrange("timeline:" + userId, 0, 50);   // just grab the ready list. Fast!
}
```

Great reads, but notice the `for` loop: for a celebrity, `followers` is 50 million → 50M `zadd`s for one tweet. That's the write-amplification wall.

> 💡 **Redis sorted set = the timeline's data structure.** `ZADD key score member` inserts a tweet id scored by its timestamp; `ZREVRANGE key 0 50` returns the newest 50 already ordered (no sort at read time); `ZREMRANGEBYRANK` trims to the newest ~800. One structure gives you "time-ordered, capped, top-N" for free — that's why it's the timeline store (full rationale in §7).

#### Pull (fan-out on read) — annotated

```java
// Posting is cheap — do almost nothing:
void fanOutOnRead_post(Tweet tweet) {
    tweetStore.save(tweet);   // that's it. No copying into anyone's timeline.
}

// But every READ pays the price — gather + merge on the spot:
List<Tweet> readHome(long userId) {
    List<Long> followees = graph.getFollowees(userId);        // the 300 people you follow

    List<Tweet> all = new ArrayList<>();
    for (long f : followees) {
        all.addAll(tweetStore.recentTweetsOf(f, 50));         // fetch each one's latest
    }
    all.sort(byCreatedAtDesc());                              // merge by time
    return all.subList(0, Math.min(50, all.size()));
}
```

Cheap writes, but every refresh hits 300 users' tweets and merges them — expensive, and you do it constantly.

#### Hybrid (the standard answer) — annotated

```java
// On POST: push only if the author is NOT a celebrity.
void onPost(Tweet tweet) {
    tweetStore.save(tweet);
    User author = users.get(tweet.authorId);
    if (author.followerCount < CELEBRITY_THRESHOLD) {   // e.g. < 100,000
        fanOutOnWrite(tweet);          // push to followers (they're few enough)
    }
    // else: DO NOTHING extra. Celebrity tweets are pulled at read time.
}

// On READ: merge the pushed timeline with a live pull of followed celebrities.
List<Tweet> readHome(long userId) {
    List<Long> pushedIds = redis.zrevrange("timeline:" + userId, 0, 800);  // the masses (precomputed)

    List<Long> celebIds = new ArrayList<>();
    for (long celeb : graph.followedCelebrities(userId)) {   // usually a tiny handful
        celebIds.addAll(cache.recentTweetsOf(celeb, 50));    // pull their recent (cached per celeb)
    }

    List<Long> merged = mergeByTime(pushedIds, celebIds);
    return hydrate(merged);   // turn ids into full tweets (see §7)
}
```

You skip the 50M-write explosion (celebrities aren't pushed) **and** keep reads fast (you only pull a *few* celebrities, not all 300 followees).

### Push vs pull vs hybrid — when does each win

| | Best when | Falls apart when |
| --- | --- | --- |
| **Push** | Author has few followers; reads must be instant | Author is a celebrity (millions of writes per tweet) |
| **Pull** | Author has huge/rare readership; writes must be cheap | Reads are frequent (merging on every refresh is slow) |
| **Hybrid** | Real life: most users small, a few are huge | (essentially the production answer) |

### What exactly makes someone a "celebrity" here

It's a **threshold on follower count** (say 100k). Above it, we stop pushing their tweets and pull them at read time instead. It's not fame — it's purely "does fanning this person out cost too much?" An account can cross the line both ways, and we flip its mode when it does (see §13 Edge Cases).

> ⚠️ **The threshold is a tuning knob, not a law.** Too low → you pull for too many accounts and reads get slow; too high → fan-out for a near-celebrity blasts millions of writes and starves the pipeline. Tune it against your **actual fan-out cost vs read cost** (and it can be dynamic — e.g. lower the bar during peak hours, or treat sudden viral follower spikes specially). ~100k is an illustrative starting point, not a magic number.

#### Q: Isn't pulling celebrities also slow?

No, because you follow only a **handful** of celebrities, and each celebrity's recent tweets are cached **once** and reused across everyone who follows them. Pulling 3 cached celebrity lists is cheap; the thing pull is bad at (fetching *all 300* followees every read) is exactly what push already handled for the non-celebrities.

---

## 6. The Fan-out Pipeline

```
1. Tweet Service persists the tweet → emits TWEET_CREATED to Kafka
2. Fan-out workers consume it:
     - look up the author's followers (Graph Service, `follows(followee)` index)
     - for each follower's timeline: ZADD timeline:{followerId} score=tweetTime tweetId
     - cap each timeline to recent N (e.g. 800) → ZREMRANGEBYRANK trims old
3. (celebrity authors are skipped here → pulled at read time)
```

- **Async + parallel** via Kafka + a worker fleet — the poster doesn't wait for 200 writes.
- **Batch** timeline writes; partition fan-out work by follower shard.
- **Backpressure:** a huge (non-celebrity) account's fan-out is queued/throttled so it doesn't starve others.
- Store **tweet IDs** in timelines (8 bytes), not content → hydrate on read.

### The fan-out pipeline, step by step

When you post, you get a fast response immediately. The actual copying into followers' timelines happens **afterward, in the background**:

```java
// 1) The poster's request finishes here — instantly. No waiting for fan-out.
String postTweet(long authorId, String text) {
    Tweet t = tweetStore.save(new Tweet(authorId, text));  // save once
    kafka.publish("TWEET_CREATED", t);                     // publish the event; fan-out happens later
    return t.id;                                            // return NOW; fan-out happens later
}

// 2) A fleet of background workers consumes those notes and does the copying.
@KafkaListener(topics = "TWEET_CREATED", groupId = "fanout")
void fanOutWorker(Tweet t) {
    if (users.get(t.authorId).followerCount >= CELEBRITY_THRESHOLD) {
        return;   // celebrity → skip; their tweets are pulled at read time
    }

    List<Long> followers = graph.getFollowers(t.authorId);

    // batch the writes instead of 200 separate round-trips to Redis
    for (List<Long> chunk : partition(followers, 500)) {
        Pipeline p = redis.pipelined();
        for (long followerId : chunk) {
            p.zadd("timeline:" + followerId, t.createdAt, t.id);   // insert id, scored by time
            p.zremrangeByRank("timeline:" + followerId, 0, -801);  // trim to newest ~800
        }
        p.sync();   // one network round-trip for the whole chunk
    }
}
```

- **`zadd` into a sorted set** = "put this tweet id into their timeline, positioned by timestamp so newest sorts first."
- **`zremrangeByRank(..., 0, -801)`** = "keep only the newest ~800; drop the rest." Timelines are **capped** — nobody scrolls back 10,000 tweets from cache; deeper history falls back to pull.

### Why cap each timeline at ~800 instead of keeping everything

A cache full of every tweet ever, per user, is enormous and pointless — people read the top of the feed. Capping keeps Redis small and writes cheap (trimming is O(log n)). If someone scrolls way back, we **pull** older tweets from the authors' user-timelines on demand.

### What "backpressure" is, and why a big non-celebrity account needs it

Say someone has 90,000 followers — under the celebrity line, so we *do* push, but that's still 90,000 writes for one tweet. If a worker blasts all 90,000 at once, it can hog Redis and starve everyone else's fan-out. **Backpressure** = we queue/throttle that job (spread it over time, limited concurrency) so it drains steadily without freezing the pipeline. It's a fairness valve.

### What "partition fan-out work by follower shard" means

Followers' timelines live on different Redis shards (machines). Instead of one worker touching 50 shards randomly, we **group the writes by which shard they land on** and let workers hit shards in parallel/batched. Fewer round-trips, better parallelism.

### Notification fan-out — a *separate* fan-out from the timeline

Timeline fan-out copies a tweet id into your **followers'** feeds. **Notification** fan-out is a different fan-out with a different audience and trigger: it pings the **specific people involved in an interaction** — the author you replied to, the person you mentioned, whoever's tweet you liked/retweeted. Same `TWEET_CREATED` (and `LIKE`/`FOLLOW`) events on Kafka, but a **separate consumer group** and a different target list.

| | Timeline fan-out | Notification fan-out |
| --- | --- | --- |
| **Trigger** | Any tweet posted | Reply, mention, like, retweet, new follow |
| **Audience** | All of the author's *followers* | The few users *directly involved* (parent author, @mentions) |
| **Volume** | Huge (write amplification) | Tiny per event (usually 1–handful) |
| **Store** | `timeline:{userId}` sorted set | `notifs:{userId}` sorted set + push token |
| **Celebrity twist** | Skip push, pull at read (§5) | A *mega-liked* tweet is the hot key — **aggregate** ("Alice and 2M others liked…") instead of 2M rows |

```java
@KafkaListener(topics = {"REPLY_CREATED", "MENTION", "LIKE", "FOLLOW"}, groupId = "notifications")
void notifyWorker(InteractionEvent e) {
    List<Long> targets = e.directlyInvolvedUsers();   // parent author + @mentions — NOT all followers
    for (long uid : targets) {
        redis.zadd("notifs:" + uid, e.time, e.id);    // notification inbox (its own sorted set)
        push.deliverIfOnline(uid, e);                 // APNs/FCM push if device registered
    }
}
```

> ⚠️ **Don't reuse the timeline fan-out for notifications.** A celebrity's tweet getting **2M likes** would generate 2M notification writes to the *celebrity's* inbox — the hot key flips to the *recipient*. **Aggregate** high-volume interactions ("Alice and 1.2M others liked your tweet") instead of one row per like.

---

## 7. Timeline Store & Read Path

```
Timeline cache (Redis):  timeline:{userId} = SORTED SET of tweet ids (score = tweet timestamp)
                         capped to recent ~800 ids; older tweets → pull from user timelines

GET /home:
  1. ZREVRANGE timeline:{userId} 0 N        → recent tweet ids (precomputed / pushed)
  2. pull recent tweets of followed CELEBRITIES (cached per celebrity) → merge
  3. hydrate ids → tweet content from a shared tweet cache (mget)
  4. rank + paginate (cursor by score/id)
```

- **IDs in the timeline, content in a shared cache** → dedup storage (a viral tweet is cached once, referenced by millions of timelines).
- **Cursor pagination** by (score, tweet_id) — stable under new inserts.
- Cap timeline length; deeper history falls back to pull.

### Ids in the timeline, content in a shared store

Two separate stores, and it matters that they're separate:

```
timeline:{userId}  = [ 987, 986, 971, 970, ... ]     ← just IDS (the timeline: a list of pointers)
tweet:987          = { text, author, likes, media }  ← the ACTUAL tweet (stored once, shared)
```

Reading your home feed is a two-step dance: get the **list of ids**, then **hydrate** them (look up the real tweets).

```java
List<Tweet> getHome(long userId, String cursor) {
    // 1) recent tweet ids from your precomputed timeline (already sorted newest-first)
    List<Long> ids = redis.zrevrange("timeline:" + userId, 0, 50);

    // 2) also pull followed celebrities' recent ids and merge (hybrid, see §5)
    ids = mergeByTime(ids, pullCelebrityIds(userId));

    // 3) HYDRATE: turn ids into full tweets in ONE batched lookup (mget), not 50 lookups
    List<Tweet> tweets = tweetCache.mget(ids);   // e.g. [tweet:987, tweet:986, ...]

    // 4) drop any that came back null (deleted tweets → dangling ids) and paginate
    return tweets.stream().filter(Objects::nonNull).toList();
}
```

### What "hydrate" means, and why not just store full tweets in the timeline

**Hydrate** = swap each lightweight id for its full tweet content, right before showing it. We don't store full tweets in every timeline because a viral tweet followed by 10M people would then be copied 10M times. Instead it's stored **once** in `tweet:987`, and 10M timelines just hold the number `987`. On read, everyone hydrates from the same shared cache. Massive storage savings + you always get the *latest* like/retweet counts (they live on the one shared copy).

### Why a Redis "sorted set" specifically

A timeline needs two things: **ordered by time** and **easy to trim to the newest N**. A sorted set stores each id with a **score** (we use the tweet's timestamp), so:
- `ZREVRANGE 0 50` → the 50 newest, already in order (no sorting at read time).
- `ZREMRANGEBYRANK` → drop the oldest to keep it capped.

It's purpose-built for "a capped, time-ordered list of ids."

#### Q: What's cursor pagination and why not just "page 1, page 2"?

Feeds get **new tweets inserted at the top constantly**. With numbered pages ("give me items 20–40"), a new tweet arriving shifts everything down by one, so you'd **see a tweet twice or skip one** when you scroll. A **cursor** says "give me tweets *older than this exact (timestamp, id)*" — a stable bookmark that doesn't care what got inserted above it.

```
Page 1:  ZREVRANGE ... → newest 20, remember the last one's (score,id) = the cursor
Page 2:  "give me 20 with score < cursor"  → continues cleanly, no dupes/gaps
```

### What happens on a cache miss (timeline not in Redis)

Rebuild it by **pulling** — read the recent tweets of everyone you follow from their user-timelines, merge, and repopulate the cache. Slower for that one request, but correct, and it self-heals. (This is the read-path's safety net; see §14.)

---

## 8. Ranking

- **Chronological (reverse-time)** is the baseline and simplest.
- **Ranked feed** = ML relevance: candidate generation (follow graph + pulled celebrities + some recommendations) → **scoring** (engagement likelihood, recency, author affinity, media type) → sort → cache.
- Treat the model as a **black box**; emphasize the pipeline: **candidates → rank → cache**, recomputed periodically or at read with cached features.

### "Newest first" vs "best first"

Two ways to order your feed:

- **Chronological** — literally sort by time, newest at top. Simple, predictable. (Our sorted-set score = timestamp already gives this for free.)
- **Ranked** — show what you're *most likely to care about* first, even if it's not the newest. This is what modern Twitter/X, Instagram, etc. do.

#### Chronological vs ranked

Chronological orders posts purely by time, newest first. Ranked orders by predicted relevance: a high-engagement post from an account you interact with a lot beats a low-value post from an account you barely engage with, even if the latter is newer.

#### The pipeline (don't overthink the ML)

```java
List<Tweet> rankedHome(long userId) {
    // 1) CANDIDATES: gather everything that COULD be in the feed
    List<Tweet> candidates = new ArrayList<>();
    candidates.addAll(pushedTimeline(userId));      // people you follow (precomputed)
    candidates.addAll(pulledCelebrities(userId));   // celebrities you follow
    candidates.addAll(recommendations(userId));     // "you might like" extras

    // 2) SCORE each candidate — the model is a BLACK BOX. You just feed it features.
    for (Tweet t : candidates) {
        t.score = model.predict(features(
            userId, t,
            /* recency      */ ageOf(t),
            /* affinity     */ howMuchYouInteractWith(userId, t.authorId),
            /* engagement   */ t.likeCount, t.retweetCount,
            /* mediaType    */ t.hasVideo
        ));
    }

    // 3) SORT by score, cache the result, return the top slice
    candidates.sort(byScoreDesc());
    cache.put("ranked:" + userId, candidates);
    return candidates.subList(0, Math.min(50, candidates.size()));
}
```

### Do you need to know the ML model internals for an interview

No — say "treat the model as a **black box**" and focus on the **pipeline**: gather **candidates → score them → sort → cache**. The systems-design interest is *where* ranking runs and how you keep it fast (precompute/cache features, recompute periodically), not the neural net internals.

### When ranking runs — on write or on read

Usually a mix: candidate lists are precomputed (via fan-out), and **scoring** happens either periodically in the background or at read time using **cached features** (so you're not recomputing expensive signals per refresh). Chronological needs no scoring step at all — that's why it's the simple baseline.

---

## 9. Search & Trending

- **Search** via **Elasticsearch** (tweet text, hashtags, users), fed from `TWEET_CREATED` via CDC/consumer; inverted index → fast full-text (see the ES section in Databases Deep Dive).
- **Trending** = count hashtag/term frequency over a **sliding time window** per region (stream aggregation, like ad-click counting) → top-k with time decay; cache and refresh every few minutes.

### Search and "what's trending"

Two different jobs that both feed off the same `TWEET_CREATED` event stream.

#### Search — the inverted index

An **inverted index** maps each word to the list of documents that contain it, so instead of scanning every tweet you look the word up directly. Elasticsearch builds one for tweets:

```
word "tokyo"  → [ tweet 987, tweet 1200, tweet 5501, ... ]
word "landed" → [ tweet 987, tweet 340, ... ]
```

So "search tokyo" is an instant index lookup, not a scan of 500M tweets. It's kept up to date by a consumer of `TWEET_CREATED` — every new tweet gets added to the index in the background.

```java
@KafkaListener(topics = "TWEET_CREATED", groupId = "search-indexer")
void indexTweet(Tweet t) {
    elasticsearch.index("tweets", t.id, Map.of(
        "text", t.text, "author", t.authorId, "createdAt", t.createdAt
    ));   // ES tokenizes the text and updates its inverted index
}
```

#### Trending — a rolling tally of hashtags

"What's trending" = which hashtags are being used **a lot, right now**. It's the same idea as ad-click counting: keep a **count per hashtag over a sliding time window** (e.g. the last hour), take the **top-k**, and apply **time decay** so old spikes fade.

```java
// Each new tweet bumps its hashtags' counters (in Redis, per region, with a TTL window)
void onTweet(Tweet t) {
    for (String tag : t.hashtags) {
        redis.zincrby("trending:" + t.region, 1, tag);   // +1 to this tag's rolling count
    }
}

// Every few minutes: read the top hashtags and cache them for the UI
List<String> topTrends(String region) {
    return redis.zrevrange("trending:" + region, 0, 10);  // top 10 right now
}
```

### Why not just `SELECT hashtag, COUNT(*) ... GROUP BY hashtag` on demand

That counts *all history* and reruns on every request — far too slow at this scale, and it wouldn't capture "right now." Trending cares about a **moving window** ("last hour"), so we **pre-aggregate as a stream** into rolling counters and just read the precomputed top-k. (Same philosophy as timelines: precompute, don't compute-on-read.)

### What "time decay" is

Without it, a hashtag that trended hard yesterday could stay on top forever. Time decay gradually **shrinks old counts** (or uses a sliding window that drops old events) so trending reflects *current* momentum, not yesterday's.

---

## 10. Data Model (all tables)

### Database & storage choices (which DB, and why at scale)

No single database is best for every job here, so we use **polyglot persistence** — pick the store that matches each data type's access pattern. The deciding question for tweets is *"is this the durable record, or a derived, disposable ranking of it?"* Tweets themselves need durability; timelines need raw speed.

| Data | Store | Why this one | Why not the alternative |
| --- | --- | --- | --- |
| Tweets, users, follows, likes, retweets (**source of truth**) | **RDBMS** (or Manhattan-style wide-column at Twitter's real scale) — sharded by **Snowflake tweet id** / by `author_id` | Tweets are written once, sharded by id keep an author's own tweets on one shard (`idx_tweets_author`) for the pull path, and moderate per-shard write volume keeps a relational engine viable; a distributed KV store like Manhattan is the real-world upgrade once write throughput outgrows single-shard RDBMS capacity, trading some query flexibility for horizontal write scale. | Storing tweets only in Redis loses durability the moment a node evicts/restarts — Redis is a cache, not a system of record; tweets must survive a restart even if every timeline cache is lost. |
| Precomputed home/user timelines | **Redis** (sorted sets, `timeline:{userId}`) | Timelines are read hundreds of thousands of times/sec and are pure derived data (rebuildable from `tweets` + `follows`) — `ZADD`/`ZREVRANGE` gives "top 20, newest first" with zero query cost, which is exactly what fan-out-on-write precomputes (§12). | Re-querying `tweets` joined against `follows` on every home-timeline open can't sustain Twitter's read QPS — that's precisely the join a materialized, precomputed Redis structure exists to avoid. |
| Search, trending, hashtags | **Elasticsearch** | Full-text tweet search and trending needs an inverted index + real-time aggregation, not row scans. | The tweet store has no relevance ranking or fast term aggregation; ES is the dedicated search/trending read model. |
| Media (images/video) | **Blob store + CDN** | `media_ref` is a pointer; bytes belong in object storage served from the edge. | Bloats the tweet store and kills replication/backup speed if bytes lived inline. |

**Why timelines live in Redis and not the tweet DB:** the whole design (§4–§7) hinges on **fan-out on write** — pushing a new tweet's id into every follower's timeline at write time so reads are just a list fetch. That precomputed list *is* the derived read model, and it has to live somewhere sub-ms and cheap to overwrite/rebuild — that's Redis's job, not the durable store's. We shard tweets by **Snowflake id** (time-sortable, minted independently by many machines with no central counter — §10) and shard/partition Redis timelines by follower/user so fan-out work can be **batched per shard** (§7) instead of scattering writes randomly. Celebrities are the hot-key case: instead of fanning out a push to millions of followers' sorted sets, their tweets are skipped at write time and **pulled** at read time (hybrid), which keeps hot authors from melting the fan-out workers. (For the full engine trade-off matrix, see [Databases — Deep Dive](../concepts/databases-deep-dive.md).)

```sql
CREATE TABLE users ( user_id BIGINT PRIMARY KEY, handle VARCHAR(50) UNIQUE, name TEXT,
                     follower_count BIGINT DEFAULT 0, is_celebrity BOOLEAN DEFAULT FALSE );

CREATE TABLE tweets (
    tweet_id BIGINT PRIMARY KEY,            -- Snowflake (time-sortable)
    author_id BIGINT NOT NULL, text TEXT, media_ref TEXT,
    reply_to BIGINT, retweet_of BIGINT, quote_of BIGINT,
    like_count INT DEFAULT 0, retweet_count INT DEFAULT 0, reply_count INT DEFAULT 0,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_tweets_author ON tweets(author_id, created_at DESC);   -- user timeline

CREATE TABLE follows (
    follower_id BIGINT, followee_id BIGINT, created_at TIMESTAMP,
    PRIMARY KEY (follower_id, followee_id)
);
CREATE INDEX idx_follows_followee ON follows(followee_id);   -- who follows X (drives fan-out)

CREATE TABLE likes ( user_id BIGINT, tweet_id BIGINT, PRIMARY KEY(user_id, tweet_id) );
CREATE TABLE retweets ( user_id BIGINT, tweet_id BIGINT, PRIMARY KEY(user_id, tweet_id) );
CREATE TABLE hashtags ( tag VARCHAR(100), tweet_id BIGINT, created_at TIMESTAMP, PRIMARY KEY(tag, tweet_id) );

-- Precomputed timelines → Redis:
--   timeline:{userId} = sorted set of tweet ids (score = tweet time), capped
--   tweet:{id}        = cached tweet content (shared)
--   celeb:{id}:recent = recent tweet ids of a celebrity (pulled at read)
```

> 💡 **`tweet_id` is a Snowflake id** — a 64-bit `timestamp + machineId + sequence`. Two payoffs: many machines mint ids with **no central counter** (no bottleneck at thousands of tweets/sec), and ids are **time-sortable**, so "bigger id = newer tweet" is what makes sorted-set ordering and cursor pagination work. (Deep dive in the Q below.)

> **Tables to consider:** users, tweets, follows, likes, retweets, hashtags, notifications, media_refs, precomputed timelines (Redis), search index (ES), trending (stream/cache). Media → blob/CDN.

### Reading the schema

Each table maps to one real-world thing:

| Table | Holds | Plain meaning |
| --- | --- | --- |
| `users` | one row per account | the people; `is_celebrity` flips push→pull |
| `tweets` | one row per tweet | the posts themselves (stored **once**) |
| `follows` | one row per follow edge | the social graph: "A follows B" |
| `likes` / `retweets` | one row per action | who liked/retweeted what |
| `hashtags` | tag → tweet | powers search/trending |
| `timeline:{id}` (Redis) | list of ids | the precomputed timeline |

#### Q: Why is `tweet_id` a "Snowflake" id and not just `AUTO_INCREMENT`?

An auto-increment id needs **one central counter** — a bottleneck when thousands of tweets/sec are created across many machines. A **Snowflake id** packs a **timestamp + machine id + sequence** into a 64-bit number, so:
- Many machines mint ids **independently** (no central counter).
- Ids are **time-sortable** — a bigger id means a newer tweet. That's why timelines can sort by id and why cursor pagination works.

### Why two indexes — `idx_tweets_author` and `idx_follows_followee`

They power the two hot lookups:

```sql
-- "give me a user's own recent tweets" (user timeline, and the PULL path)
CREATE INDEX idx_tweets_author  ON tweets(author_id, created_at DESC);

-- "who follows X?" — this is the exact question fan-out asks for every tweet
CREATE INDEX idx_follows_followee ON follows(followee_id);
```

Note `follows` is indexed **both ways** conceptually: by `follower_id` (the primary key → "who do I follow?", used on read/pull) and by `followee_id` (→ "who follows me?", used by fan-out on write). Feeds ask both directions constantly.

### Why the timeline isn't a SQL table

It's a **hot, capped, time-ordered list read hundreds of thousands of times per second**. Redis sorted sets do exactly that in memory with `ZADD`/`ZREVRANGE`. Timelines are **derived data** (rebuildable from `tweets` + `follows`), so it's safe to keep them in a fast cache rather than the durable DB.

---

## 11. API Design

```
POST /v1/tweets            { text, mediaRef? }              → { tweetId }
GET  /v1/home?cursor=       # home timeline (hybrid assembled + ranked)
GET  /v1/users/{id}/tweets  # user timeline
POST /v1/users/{id}/follow  · DELETE /v1/users/{id}/follow
POST /v1/tweets/{id}/like   · POST /v1/tweets/{id}/retweet · POST /v1/tweets/{id}/reply
POST /v1/users/{id}/mute    · POST /v1/users/{id}/block
GET  /v1/tweets/{id}/thread # conversation (parent + replies)
GET  /v1/search?q=          · GET /v1/trending?region=
```

### Button → call (what the UI actually fires)

| User action (button) | API call | Read/Write | Notes |
| --- | --- | --- | --- |
| Open app / pull-to-refresh | `GET /v1/home?cursor=` | Read | Hybrid-assembled + ranked; the 100:1 hot path |
| Tap a profile | `GET /v1/users/{id}/tweets` | Read | User timeline (`idx_tweets_author`) |
| Compose → Post | `POST /v1/tweets` | Write | Returns `tweetId` fast; fan-out is async (§6) |
| Tap a tweet to expand | `GET /v1/tweets/{id}/thread` | Read | Parent chain + replies (§16) |
| Like / Retweet | `POST /v1/tweets/{id}/like` · `/retweet` | Write | Counter update; retweet is an edge, not a copy |
| Reply | `POST /v1/tweets/{id}/reply` | Write | New tweet with `reply_to` set (§16) |
| Follow / Unfollow | `POST`/`DELETE /v1/users/{id}/follow` | Write | Edge in `follows`; drives fan-out |
| Mute / Block | `POST /v1/users/{id}/mute` · `/block` | Write | Filtered on read, not on write (see below) |
| Search / See trending | `GET /v1/search?q=` · `GET /v1/trending?region=` | Read | Elasticsearch + windowed counts (§9) |

### Mute / block filtering on the timeline

Muting or blocking someone does **not** scrub their tweets out of your (or millions of others') cached timelines — that would be a fan-out-sized rewrite (same "don't touch millions of timelines" rule as §13). Instead we keep a small **per-user mute/block set** (cached in Redis, e.g. `muted:{userId}`) and **filter on read**, right after hydrate:

```java
List<Tweet> applyMuteBlock(long userId, List<Tweet> tweets) {
    Set<Long> hidden = relationships.mutedAndBlocked(userId);   // small, cached per viewer
    return tweets.stream()
                 .filter(t -> !hidden.contains(t.authorId))     // drop muted/blocked authors
                 .toList();
}
```

> 💡 Block is bidirectional (neither sees the other) and also gates the write path — a blocked user can't reply/follow — but the *timeline hiding* is still a cheap read-time filter, not a timeline rewrite.

---

## 12. Sequences

### Post (fan-out on write)

```
Author  TweetSvc  Kafka   Fan-outWorkers  GraphSvc  Redis(timelines)
  │ post  │         │           │             │           │
  ├──────►│ persist │           │             │           │
  │◄─ id ─┤─ TWEET_CREATED ────►│             │           │
  │       │         │           ├─ get followers ─────────►│
  │       │         │           ├─ ZADD each follower timeline (cap) ──►│
  │       │         │  (celebrity author → skipped; pulled at read)
```

### Read home timeline (hybrid)

```
User → TimelineSvc:
  ZREVRANGE timeline:{me}  → pushed tweet ids
  + pull recent tweets of followed celebrities (cached)
  → merge → hydrate content (mget tweet:{id}) → rank → paginate → return
```

### Following the two paths

- **Post (write) sequence:** you post → Tweet Service saves it and hands you an id **right away** → it drops a `TWEET_CREATED` note on Kafka → background workers later look up your followers and drop the id into each follower's timeline. The key point: **you got your "posted!" back before any fan-out happened.** Celebrities are simply skipped in that last step.
- **Read sequence:** you open the app → Timeline Service grabs your ready-made list of ids, mixes in a quick pull of the celebrities you follow, turns ids into full tweets (hydrate), ranks, and returns a page. Almost all the heavy lifting already happened at write time, so this is fast.

One-line mnemonic:

```
WRITE = save + announce, fan out later      (work moved off the user's critical path)
READ  = grab list + pull celebs + hydrate   (mostly just fetching precomputed stuff)
```

---

## 13. Edge Cases

| Case | Handling |
| --- | --- |
| **New follow** | Backfill: pull the newly-followed user's recent tweets into the timeline (or just rely on pull for a while) |
| **Unfollow** | Lazy — filter their tweets out on read, or rebuild timeline async |
| **Deleted tweet** | Tombstone; filtered on hydrate (timeline holds a dangling id → skip) |
| **Celebrity crosses threshold** | Switch that account from push to pull (stop fanning out) |
| **Retweet of a celebrity** | Store as a retweet edge; hydrate original; avoid duplicating content |
| **Backfill for inactive users** | Don't fan out to long-inactive users (lazy pull on return) → saves huge write volume |

### The tricky "what ifs"

- **You follow someone new:** your precomputed timeline has none of their past tweets. Fix: **backfill** — pull their recent tweets in and merge, or just let the pull path cover them until fan-out catches up. Their *future* tweets will fan out normally.
- **You unfollow someone:** their tweets may still sit in your cached timeline. We don't scramble to scrub them — we **lazily filter** them out on read, or rebuild the timeline in the background. Cheaper than reacting instantly.
- **A tweet gets deleted:** timelines still hold its **id** (a dangling pointer). We don't hunt through millions of timelines to remove it. Instead, on hydrate the lookup returns nothing (**tombstone**) and we just **skip it**. Self-cleaning.
- **An account crosses the celebrity threshold:** flip it from **push to pull** — stop fanning out its tweets; readers pull them instead. (And vice-versa if it drops below.)
- **Inactive users:** someone who hasn't opened the app in months doesn't need tweets pushed into a timeline they never check. **Skip fan-out to them**; rebuild via pull if they return. Across millions of dormant accounts this saves an enormous amount of write volume.

### Why "don't touch millions of timelines" is a recurring theme

Any fix that requires editing every follower's timeline is itself a fan-out-sized operation. So the design consistently prefers **lazy** fixes (filter/skip on read) and **tombstones** over eagerly rewriting millions of timelines. The read path is cheap to make a little smarter; rewriting all timelines is not.

---

## 14. Scaling & Failure

- **Fan-out** via Kafka + worker pool; celebrities skip it; inactive users skip it; batch + backpressure.
- **Timeline cache** in Redis (ids only, capped); hydrate from a shared tweet cache; CDN for media.
- **Follow graph** sharded by user; `follows(followee)` powers fan-out; mutuals via set intersection.
- **Tweet store** sharded by tweet_id/time; archive old tweets.
- **Hot key (celebrity/viral tweet)** → pull + heavy caching + CDN; the viral tweet's content is cached once.
- **Failure:** timeline cache miss → rebuild from user timelines (pull) → eventual consistency (a brief lag is fine); fan-out worker lag → some followers see a tweet a bit late.

### What breaks, and why it's OK

The comforting theme: because a timeline is **derived data** (rebuildable from `tweets` + `follows`), most failures are **degradations, not disasters**.

| If this fails... | What the user sees | Why it's survivable |
| --- | --- | --- |
| Timeline **cache miss** | A slightly slower load | Rebuild by pulling from user-timelines; then re-cache |
| **Fan-out workers lag** | A friend's tweet shows up a few seconds late | Eventual consistency is acceptable for a feed |
| A **celebrity/viral tweet** is a hot key | Nothing bad — it's cached once | Pull + heavy caching + CDN absorb the reads |
| A Redis shard dies | That slice of timelines rebuilds from source | Timelines aren't the source of truth |

#### Q: What's the "hot key" at read time (vs the write-time celebrity problem)?

Two flavors of the same person:
- **Write-time:** a celebrity *posting* → millions of writes → solved by **not pushing** (hybrid/pull).
- **Read-time:** a single viral tweet *being read* by millions → solved by **caching that tweet once** and serving it from cache/CDN, so all those reads hit one cached copy instead of hammering the store.

### Why we can get away with "eventual consistency" everywhere

A social feed has no correctness contract like a bank. If your tweet reaches followers over a few seconds instead of instantly, nothing is *wrong* — it's just slightly delayed. That tolerance is what lets fan-out be async, timelines be a rebuildable cache, and the system stay **available** even when parts are degraded.

---

## 15. Interview Cheat Sheet

> **"How do you build the home timeline?"**
> "Hybrid fan-out: async workers push tweet **ids** into followers' Redis timelines for normal authors; for celebrities, skip fan-out and **pull** their recent tweets at read time, merging with the precomputed timeline, then hydrate content from a shared cache and rank."

> **"Why hybrid, not pure push or pull?"**
> "Pure push = 50M writes for one celebrity tweet (write amplification). Pure pull = merging hundreds of followees on every read (slow). Hybrid pushes for the masses (fast reads) and pulls the few celebrities you follow (no write explosion)."

> **"How do you store timelines?"**
> "Redis sorted set of tweet ids per user (score = time), capped to ~800; store ids not content (a viral tweet is cached once and referenced by millions of timelines); hydrate on read."

> **"How does fan-out scale?"**
> "TWEET_CREATED → Kafka → parallel fan-out workers write follower timelines in batches; skip celebrities and inactive users; backpressure large accounts."

> **"Search / trending?"**
> "Search via Elasticsearch fed from tweet events. Trending = sliding-window term-frequency aggregation per region with time decay, cached."

### Tricky scenarios (rapid-fire)

| Scenario | What happens / what to do |
| --- | --- |
| **Celebrity with 50M followers tweets** | **Don't push.** Above the celebrity threshold → skip fan-out; their tweet is **pulled** at read time from a per-celebrity cache and merged into each reader's feed (§5). |
| **Timeline cache miss** (Redis shard evicted/restarted) | Rebuild by **pulling** recent tweets of everyone the user follows, merge, re-cache. Slower for that one request, self-heals; timelines are derived data (§7, §14). |
| **Mass-follow backpressure** (90k-follower account, or a bot following millions) | It's under the celebrity line so we *do* push — but **queue/throttle** the fan-out job (bounded concurrency, spread over time) so it drains steadily without starving others (§6). |
| **Deleted tweet still in cached timelines** | Leave the dangling **id**; don't scrub millions of timelines. On hydrate the lookup returns null (**tombstone**) → **skip it** (§13). Self-cleaning. |
| **Account crosses the celebrity threshold** | Flip push→pull (or pull→push if it drops). Old pushed copies age out of capped timelines naturally (§5, §13). |
| **Muted/blocked author's tweets in your feed** | **Filter on read** against a small per-viewer mute/block set — never rewrite timelines (§11). |

---

## 16. Replies & Conversation Threading

> A reply is just a tweet with a **parent pointer**. A "thread" is the tree you get by following those pointers.

Threading reuses the tweet table — no new store. Two fields do the work:

- **`reply_to`** — the tweet this one directly answers (its **parent**). `NULL` for a top-level tweet.
- **`conversation_id`** — the **root** tweet id of the whole thread (copied down from the parent on create). Lets you fetch a whole conversation with one indexed lookup instead of walking pointers one hop at a time.

```
tweet 100  "Deploying v2 tonight"          reply_to=NULL   conversation_id=100   ← root
 └─ tweet 101  "nice, any downtime?"        reply_to=100    conversation_id=100
     └─ tweet 102  "~5 min"                  reply_to=101    conversation_id=100
 └─ tweet 103  "good luck!"                  reply_to=100    conversation_id=100
```

- **Replies fan out like any tweet** — a reply lands in the timelines of the *replier's* followers (normal fan-out), and separately pings the parent author via **notification fan-out** (§6).
- **Reply count** on the parent (`reply_count`) is a counter bumped on reply — don't `COUNT(*)` the children on every render.

### Hydrating a conversation (the read sequence)

When a user taps a tweet to expand the thread, `GET /v1/tweets/{id}/thread`:

```java
Thread getThread(long tweetId) {
    Tweet focus = tweetCache.get(tweetId);

    // 1) ANCESTORS: walk parent pointers up to the root (short chain, usually a few hops)
    List<Long> ancestorIds = new ArrayList<>();
    for (Long p = focus.replyTo; p != null; p = tweetCache.get(p).replyTo) {
        ancestorIds.add(p);
    }

    // 2) REPLIES: direct children, newest/most-relevant first (one indexed lookup)
    List<Long> replyIds = replyIndex.childrenOf(tweetId, /*limit*/ 50);   // idx on (reply_to, created_at)

    // 3) HYDRATE everything in ONE batched lookup, then filter deleted (tombstones) + muted authors
    List<Long> all = concat(reverse(ancestorIds), tweetId, replyIds);
    List<Tweet> hydrated = tweetCache.mget(all).stream()
                                     .filter(Objects::nonNull)     // deleted parent/reply → skip
                                     .toList();
    return Thread.of(hydrated);
}
```

> 💡 Store `conversation_id` on every reply so "load the whole conversation" is `WHERE conversation_id = ?` (one index scan) instead of recursively chasing `reply_to`. The parent-walk above is only for the *ancestor spine* when you deep-link into the middle of a thread.

> ⚠️ **Deleted parent, surviving replies:** if the root/parent is deleted, its replies still exist. Hydrate returns null for the parent → render a "this tweet is unavailable" placeholder so the child replies still make sense, rather than dropping the whole subtree.

---

## 17. Consistency & CAP Tradeoffs

> Interviewers love: "Where do you pick availability over consistency — and where can't you?"

The feed side of Twitter is deliberately **AP**: seeing *something* instantly beats seeing the *absolute freshest* thing. A few interactions (money-adjacent or privacy-sensitive) want stronger guarantees.

| Path | Choice | Why |
| --- | --- | --- |
| **Home timeline / feed reads** | **AP** (available + eventual) | A friend's tweet arriving a second late is fine; an error page is not. Fan-out is async by design. |
| **Post a tweet** | **AP** (accept fast, fan out later) | Return "posted!" immediately; followers converge over the next moment. |
| **Likes / retweet counts** | **AP** (eventually consistent counters) | A count that's off by a few for a second is invisible to users; use approximate/rolled-up counters at scale. |
| **Follow / unfollow** | **Read-your-writes** for the actor | *You* must see your own follow take effect immediately; others can see it propagate eventually. |
| **DMs / direct messages** (if in scope) | **Stronger / ordered** | Messages must not drop or reorder within a conversation — closer to a durable, ordered log than a best-effort feed. |
| **Blocking** (if in scope) | **Stronger (safety-critical)** | A block must take effect reliably and promptly — a stale "not blocked" is a real harm, not a cosmetic lag. |

> One-liner: **"AP for the feed and social counters; read-your-writes for your own actions; stronger, ordered consistency only for DMs and safety-critical blocks."**

---

## 18. How to Drive the Interview (framework)

> Use this order so you never freeze. Spend ~5 min on 1–4, then go deep on 5.

1. **Clarify requirements** (functional + NFRs; call out read-heavy ~100:1 and "eventual is fine") — §2
2. **Estimate scale** (write amplification: 1 tweet → N follower writes; celebrity 50M) — §3
3. **Define APIs** (post, home, user timeline, follow, thread) — §11
4. **High-level architecture + data model** (CQRS write/read split, Kafka in the middle) — §4, §10
5. **Deep dive: the hard part → feed fan-out** — §5–§7
6. **Ranking, search, trending** — §8, §9
7. **Edge cases + scale/failure** (celebrity, cache miss, deletes, backpressure) — §13, §14
8. **Summarize tradeoffs** — §17, §15

> 🎤 **Spend ~60% of the interview on fan-out.** The interviewer is testing whether you reach for **push vs pull vs hybrid** and handle the **celebrity / hot-key** problem. State the crux early ("pure push dies on celebrities, pure pull is slow on every read → hybrid"), then justify the celebrity threshold, backpressure, and capped timelines. Everything else (search, trending, ranking) is supporting detail.

---

## 19. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Strategy** | Fan-out (push/pull/hybrid), ranking (chrono/ML) | Swap per user type/experiment |
| **Producer-Consumer** | Fan-out workers consume tweet events (Kafka) | Absorb + parallelize fan-out |
| **Observer / Pub-Sub** | Tweet event → fan-out, search index, trending, notifications | Decouple |
| **CQRS + Materialized View** | Precomputed timelines vs write model | Fast reads |
| **Cache-Aside** | Tweet + timeline caches | Read performance |
| **Repository** | Data access | Testable |
| **Facade** | Timeline service over cache + pull + hydrate + rank | Simple API |
| **Decorator** | Tweet rendering (badges, media, quoted) | Compose display |

---

## 20. Final Takeaways

- **Read-heavy** → precompute timelines (CQRS); the core decision is **fan-out push vs pull → hybrid**.
- **Hybrid** (push for normal, pull for celebrities/inactive) solves write amplification / hot-key.
- **Timelines store ids** (Redis sorted set, capped); **content cached once** and hydrated on read.
- **Fan-out = Kafka + worker fleet**, batched, backpressured; `follows(followee)` drives it.
- **Search = Elasticsearch**; **trending = windowed term counts** with decay.
- Patterns: Strategy (fan-out/rank), Producer-Consumer, CQRS/Materialized View, Cache-Aside, Observer.

### Related notes

- [Fan-Out / Fan-In & Celebrity Problem](../concepts/fan-out-fan-in.md) — push/pull/hybrid + celebrity problem in depth
- [Facebook](facebook-system-design.md) · [Instagram](instagram-system-design.md) · [Reddit](reddit-system-design.md) · [Quora](quora-system-design.md) — related feed/ranking systems
- [Caching Strategies](../concepts/caching-strategies.md) · [Apache Kafka](../concepts/kafka.md) · [Consistent Hashing](../concepts/consistent-hashing.md)
