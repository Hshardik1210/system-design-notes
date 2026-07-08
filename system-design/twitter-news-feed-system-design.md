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
- [14. Design Patterns (that can be used)](#14-design-patterns-that-can-be-used)
- [15. Scaling & Failure](#15-scaling--failure)
- [16. Interview Cheat Sheet](#16-interview-cheat-sheet)
- [17. Final Takeaways](#17-final-takeaways)

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

#### Q: Why not just query the database every time someone opens the app?

Because "get the latest tweets from the 300 people I follow, sorted by time" is a heavy query, and hundreds of thousands of people fire it **every second**. Running that live, per refresh, would melt any database.

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

#### Q: Why store tweet *ids* in timelines, not the tweets themselves?

Because a timeline is just a **list of pointers**, not a pile of full tweets.

```
Store the tweet ONCE:      tweet:987 = { "just landed in Tokyo", author=you, ... }  (~300 bytes)
Put only its ID everywhere: timeline:{follower} → [987, 986, 971, ...]              (8 bytes each)
```

A viral tweet followed by millions is stored **one time**; millions of timelines just hold the 8-byte number `987`. If we copied the full text into every timeline, storage would explode by ~40×. "Hydrate" (look up the full tweet by id) happens later, at read time.

#### Q: What's the "celebrity" line in the estimate about?

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

#### Q: Why split "write path" and "read path" into different services (CQRS)?

Because posting a tweet and reading a timeline are **totally different shapes of work**:

- **Writing** (posting) is rare, and it's fine if it does heavy background work (fan-out).
- **Reading** (opening the app) is *constant* (100:1) and must feel instant.

If one service did both, you couldn't tune them independently. So we **C**ommand-**Q**uery-**S**eparate them: the write side optimizes for "accept the tweet fast and fan out in the background," the read side optimizes purely for "return a ready-made timeline in milliseconds."

```
WRITE side:  you post → Tweet Service → Kafka → Fan-out workers fill timelines   (do work now)
READ side:   you open app → Timeline Service → read your ready timeline           (just fetch)
```

#### Q: Why is Kafka in the middle instead of Tweet Service calling Fan-out directly?

Because one tweet triggers **many** independent reactions: fan-out, search indexing, notifications, analytics. Instead of Tweet Service knowing and calling all of them (tight coupling, and it'd have to wait for the slow fan-out), it just drops one `TWEET_CREATED` event on Kafka and moves on. Every interested team picks it up on its own schedule. This is the **pub/sub** pattern — the poster is decoupled from all the downstream work, and gets a fast response.

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

#### Q: Push vs pull vs hybrid — when does each win?

| | Best when | Falls apart when |
| --- | --- | --- |
| **Push** | Author has few followers; reads must be instant | Author is a celebrity (millions of writes per tweet) |
| **Pull** | Author has huge/rare readership; writes must be cheap | Reads are frequent (merging on every refresh is slow) |
| **Hybrid** | Real life: most users small, a few are huge | (essentially the production answer) |

#### Q: What exactly makes someone a "celebrity" here?

A **threshold on follower count** (say 100k). Above it, we stop pushing their tweets and pull them at read time instead. It's not fame — it's purely "does fanning this person out cost too much?" An account can cross the line both ways, and we flip its mode when it does (see §13 Edge Cases).

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

#### Q: Why cap each timeline at ~800 instead of keeping everything?

Because a cache full of every tweet ever, per user, is enormous and pointless — people read the top of the feed. Capping keeps Redis small and writes cheap (trimming is O(log n)). If someone scrolls way back, we **pull** older tweets from the authors' user-timelines on demand.

#### Q: What is "backpressure" and why does a big non-celebrity account need it?

Say someone has 90,000 followers — under the celebrity line, so we *do* push, but that's still 90,000 writes for one tweet. If a worker blasts all 90,000 at once, it can hog Redis and starve everyone else's fan-out. **Backpressure** = we queue/throttle that job (spread it over time, limited concurrency) so it drains steadily without freezing the pipeline. It's a fairness valve.

#### Q: What does "partition fan-out work by follower shard" mean?

Followers' timelines live on different Redis shards (machines). Instead of one worker touching 50 shards randomly, we **group the writes by which shard they land on** and let workers hit shards in parallel/batched. Fewer round-trips, better parallelism.

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

#### Q: What does "hydrate" mean, and why not just store full tweets in the timeline?

**Hydrate** = swap each lightweight id for its full tweet content, right before showing it. We don't store full tweets in every timeline because a viral tweet followed by 10M people would then be copied 10M times. Instead it's stored **once** in `tweet:987`, and 10M timelines just hold the number `987`. On read, everyone hydrates from the same shared cache. Massive storage savings + you always get the *latest* like/retweet counts (they live on the one shared copy).

#### Q: Why a Redis "sorted set" specifically?

Because a timeline needs two things: **ordered by time** and **easy to trim to the newest N**. A sorted set stores each id with a **score** (we use the tweet's timestamp), so:
- `ZREVRANGE 0 50` → the 50 newest, already in order (no sorting at read time).
- `ZREMRANGEBYRANK` → drop the oldest to keep it capped.

It's purpose-built for "a capped, time-ordered list of ids."

#### Q: What's cursor pagination and why not just "page 1, page 2"?

Feeds get **new tweets inserted at the top constantly**. With numbered pages ("give me items 20–40"), a new tweet arriving shifts everything down by one, so you'd **see a tweet twice or skip one** when you scroll. A **cursor** says "give me tweets *older than this exact (timestamp, id)*" — a stable bookmark that doesn't care what got inserted above it.

```
Page 1:  ZREVRANGE ... → newest 20, remember the last one's (score,id) = the cursor
Page 2:  "give me 20 with score < cursor"  → continues cleanly, no dupes/gaps
```

#### Q: What happens on a cache miss (timeline not in Redis)?

Rebuild it by **pulling** — read the recent tweets of everyone you follow from their user-timelines, merge, and repopulate the cache. Slower for that one request, but correct, and it self-heals. (This is the read-path's safety net; see §15.)

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

#### Q: In an interview, do I need to know the ML model?

No. Say "treat the model as a **black box**" and focus on the **pipeline**: gather **candidates → score them → sort → cache**. The systems-design interest is *where* ranking runs and how you keep it fast (precompute/cache features, recompute periodically), not the neural net internals.

#### Q: When does ranking run — on write or on read?

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

#### Q: Why not just `SELECT hashtag, COUNT(*) ... GROUP BY hashtag` on demand?

Because that counts *all history* and reruns on every request — far too slow at this scale, and it wouldn't capture "right now." Trending cares about a **moving window** ("last hour"), so we **pre-aggregate as a stream** into rolling counters and just read the precomputed top-k. (Same philosophy as timelines: precompute, don't compute-on-read.)

#### Q: What's "time decay"?

Without it, a hashtag that trended hard yesterday could stay on top forever. Time decay gradually **shrinks old counts** (or uses a sliding window that drops old events) so trending reflects *current* momentum, not yesterday's.

---

## 10. Data Model (all tables)

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

#### Q: Why two indexes — `idx_tweets_author` and `idx_follows_followee`?

They power the two hot lookups:

```sql
-- "give me a user's own recent tweets" (user timeline, and the PULL path)
CREATE INDEX idx_tweets_author  ON tweets(author_id, created_at DESC);

-- "who follows X?" — this is the exact question fan-out asks for every tweet
CREATE INDEX idx_follows_followee ON follows(followee_id);
```

Note `follows` is indexed **both ways** conceptually: by `follower_id` (the primary key → "who do I follow?", used on read/pull) and by `followee_id` (→ "who follows me?", used by fan-out on write). Feeds ask both directions constantly.

#### Q: Why isn't the timeline a SQL table?

Because it's a **hot, capped, time-ordered list read hundreds of thousands of times per second**. Redis sorted sets do exactly that in memory with `ZADD`/`ZREVRANGE`. Timelines are **derived data** (rebuildable from `tweets` + `follows`), so it's safe to keep them in a fast cache rather than the durable DB.

---

## 11. API Design

```
POST /v1/tweets            { text, mediaRef? }              → { tweetId }
GET  /v1/home?cursor=       # home timeline (hybrid assembled + ranked)
GET  /v1/users/{id}/tweets  # user timeline
POST /v1/users/{id}/follow  · DELETE /v1/users/{id}/follow
POST /v1/tweets/{id}/like   · POST /v1/tweets/{id}/retweet · POST /v1/tweets/{id}/reply
GET  /v1/search?q=          · GET /v1/trending?region=
```

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

#### Q: Why is "don't touch millions of timelines" a recurring theme?

Because any fix that requires editing every follower's timeline is itself a fan-out-sized operation. So the design consistently prefers **lazy** fixes (filter/skip on read) and **tombstones** over eagerly rewriting millions of timelines. The read path is cheap to make a little smarter; rewriting all timelines is not.

---

## 14. Design Patterns (that can be used)

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

## 15. Scaling & Failure

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

#### Q: Why can we get away with "eventual consistency" everywhere?

Because a social feed has no correctness contract like a bank. If your tweet reaches followers over a few seconds instead of instantly, nothing is *wrong* — it's just slightly delayed. That tolerance is what lets fan-out be async, timelines be a rebuildable cache, and the system stay **available** even when parts are degraded.

---

## 16. Interview Cheat Sheet

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

---

## 17. Final Takeaways

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
