# Fan-Out / Fan-In & the Celebrity Problem

> **Fan-out** = deliver one thing to **many** places (1 → N). **Fan-in** = combine **many** things into one (N → 1). These two patterns underlie **news feeds, notifications, messaging, and sharded queries**. The famous gotcha is the **celebrity problem**: fanning out one post to 50M followers explodes into 50M writes.

> **How to read this doc:** each section has the dense summary first, then a **Plain-English** deep dive (analogies, annotated code, and the exact confusions that trip up beginners). Skim the summaries for revision; read the plain-English parts to actually understand *why*.

---

## Contents

- [1. Definitions](#1-definitions)
- [2. Fan-Out on Write vs Read (Push vs Pull)](#2-fan-out-on-write-vs-read-push-vs-pull)
- [3. The Celebrity Problem (in detail)](#3-the-celebrity-problem-in-detail)
- [4. The Hybrid Solution](#4-the-hybrid-solution)
- [5. Fan-In (Scatter-Gather / Aggregation)](#5-fan-in-scatter-gather--aggregation)
- [6. Where These Appear](#6-where-these-appear)
- [7. Trade-offs & Numbers](#7-trade-offs--numbers)
- [8. Interview Cheat Sheet](#8-interview-cheat-sheet)
- [9. Final Takeaways](#9-final-takeaways)

---

## 1. Definitions

```
FAN-OUT (1 → N):  one input distributed to many outputs
   one tweet        → written into N followers' feeds
   one order event  → N notification jobs (email + SMS + push)
   one search query → sent to N shards

FAN-IN (N → 1):   many inputs combined into one
   N shard results  → merged into one response (scatter-gather)
   N producers      → one queue / one aggregated counter
   N followees' posts → merged into one feed (pull model)
```

> **Mental hook:** MapReduce = **map is fan-out**, **reduce is fan-in**. Scatter-gather = **fan-out the request, fan-in the responses**.

### Plain-English: the party-invite analogy

Imagine you're throwing a party for **100 guests**.

- **Fan-out (1 → N)** = you write **one** invitation and send a **copy to each of the 100 guests**. One thing (the invite) becomes many deliveries. That's it. "Fan" like a hand-held fan spreading *out* from one point.
- **Fan-in (N → 1)** = later, **100 RSVPs** come trickling back, and you **collect them into one guest list**. Many things flow *in* to one place and get combined.

```
FAN-OUT:   1 invite  ──►  guest 1
                     ──►  guest 2
                     ──►  ... (100 copies go out)

FAN-IN:    RSVP 1  ──┐
           RSVP 2  ──┼──►  one final guest list (combine the 100 replies)
           RSVP 3  ──┘
```

Every fancy term in this doc is a version of that:

| Real system thing            | Party version                                            |
| ---------------------------- | -------------------------------------------------------- |
| Tweet → 100 followers' feeds | One invite copied to 100 guests (**fan-out**)            |
| Merge shard results → answer | Collecting 100 RSVPs into one list (**fan-in**)          |
| Search query → all shards    | Asking 100 helpers "do you have this?" (**fan-out**)     |
| MapReduce                    | Hand out tasks (map = fan-out), gather results (reduce = fan-in) |

#### Q: Is fan-out always about *copying*, and fan-in always about *summing*?

Not exactly — think **"spread"** and **"collect"**, not "copy" and "sum":

- **Fan-out = spread one thing to many targets.** Sometimes it's a literal copy (a tweet into each feed), sometimes it's splitting work (a query sent to each shard). The common thread: **1 source → N destinations**.
- **Fan-in = collect many things into one.** Sometimes you *add them up* (count clicks), sometimes you *merge lists* (combine shard results into one page). The common thread: **N sources → 1 result**.

#### Q: Why are these grouped together as one topic?

Because they're **mirror images** and constantly show up as a **pair**. Scatter-gather sends a query *out* to N shards (fan-out) and then merges the N answers back *in* (fan-in). MapReduce maps *out* then reduces *in*. Once you see one, you look for the other.

---

## 2. Fan-Out on Write vs Read (Push vs Pull)

The central design decision for feeds/timelines: **when** do you do the fan-out — at **write** time or at **read** time?

### Fan-out on write (push) — precompute feeds

```
User posts → immediately WRITE the post id into EVERY follower's feed (a per-user list in Redis)
Read feed  → just read your precomputed list (fast)
```

| Pros | Cons |
| --- | --- |
| **Reads are cheap + fast** (feed already built) | **Writes amplify**: 1 post → N feed writes |
| Great when avg followers is small | **Celebrity problem** (N huge) |
| Read-heavy systems love it | Wastes work for inactive followers |

### Fan-out on read (pull) — assemble at read time

```
User posts → just store the post once
Read feed  → fetch recent posts from EVERYONE you follow, then merge + rank (fan-in)
```

| Pros | Cons |
| --- | --- |
| **Writes are cheap** (store once) | **Reads are expensive** (gather from many sources every time) |
| No wasted work; no celebrity blow-up | Slower feed load; repeated work |
| Good for users who follow many / rarely read | Hard to rank across many sources live |

> **Read-heavy → push** (do work once on write, reads are cheap). **Write-heavy / celebrity → pull** (don't amplify writes). Most large systems use a **hybrid** (§4).

### Plain-English: "do the work now" vs "do the work later"

The one decision that drives feed design: **when do you build someone's feed?**

**Analogy — a newspaper.** You follow some people, and their posts are the "articles." Two ways to get you your morning paper:

- **Fan-out on write (push)** = the moment an author writes an article, a worker **immediately drops a copy into the mailbox of every subscriber**. When you wake up, your mailbox (feed) is already full — you just grab it. Fast for you, but the author's one article caused thousands of mailbox deliveries.
- **Fan-out on read (pull)** = nobody pre-delivers anything. When you wake up and ask for your paper, a clerk **runs around to every author you follow, grabs their latest, staples it together, and hands it to you**. The author did almost nothing when writing; *you* pay the cost every time you read.

```
PUSH (write-time work):  author posts → copy into feed_of(followerA), feed_of(followerB), ...
                         you read     → feed is already built → instant

PULL (read-time work):   author posts → store the post once, that's it
                         you read     → go fetch latest from everyone you follow → merge → show
```

#### Annotated code: the two approaches side by side

Fan-out **on write** — the expensive part happens when someone posts:

```java
// PUSH: runs once per POST. Cost scales with the author's follower count.
void onPost(Post post) {
    List<Long> followers = graph.followersOf(post.authorId);   // could be 200 ... or 50,000,000

    for (Long followerId : followers) {
        // drop this post id into each follower's precomputed feed list (e.g. a Redis list)
        feedStore.pushToFeed(followerId, post.id);             // 1 post → N writes (amplification!)
    }
}

// Reading is now trivial — the feed was built ahead of time.
List<Long> readFeed(long userId) {
    return feedStore.getFeed(userId);   // just read the ready-made list. Fast.
}
```

Fan-out **on read** — posting is trivial, reading does the work:

```java
// PULL: posting is cheap — store the post ONCE, no fan-out.
void onPost(Post post) {
    postStore.save(post);   // that's the whole write path
}

// Reading is expensive — gather + merge every time.
List<Post> readFeed(long userId) {
    List<Long> followees = graph.followeesOf(userId);          // everyone THIS user follows
    List<Post> merged = new ArrayList<>();

    for (Long authorId : followees) {
        merged.addAll(postStore.recentPostsOf(authorId));      // gather (this is the fan-IN)
    }
    return rankByTime(merged);                                 // merge + sort → the feed
}
```

Notice the cost just **moves**: push pays per-follower **at write time**; pull pays per-followee **at read time**.

#### Q: Which is "better"?

Neither — it depends on the **read/write ratio**:

- Most social feeds are **read-heavy** (you refresh 50× for every 1 post). So doing the work **once on write** and making the frequent reads cheap usually wins → **push**.
- But push has one fatal case (a celebrity with millions of followers, §3). That's why real systems combine both (§4).

#### Q: Where does "fan-in" hide in the pull model?

Right in `readFeed` above: the loop that **gathers posts from everyone you follow and merges them into one list** *is* the fan-in. Pull = fan-out the request to many authors, then fan-in their posts into one timeline.

---

## 3. The Celebrity Problem (in detail)

Pure **fan-out-on-write** breaks for accounts with millions of followers.

```
Normal user posts (200 followers)    → 200 feed writes      → fine
Celebrity posts (50,000,000 followers) → 50,000,000 feed writes for ONE tweet
```

**What goes wrong:**
| Symptom | Why |
| --- | --- |
| **Write amplification** | One action triggers tens of millions of writes |
| **Latency spike / backlog** | The fan-out job takes minutes; some followers see it late |
| **Hot partition / thundering herd** | A burst of writes hammers the feed store |
| **Wasted work** | Most followers won't open the app before the next post |
| **Multiple celebrities at once** | A big event (e.g. finale) = many celebrities posting → compounding load |

> It's really the **hot-key / write-amplification** problem wearing a costume. Same family as a hot DB shard or a viral URL — a single entity with disproportionate fan-out.

### Plain-English: why one tweet can melt the system

Back to the party analogy: sending **100** invites by hand is fine. Now imagine you have to send **50 million** invites by hand for **one** party — and you have to *finish before the party starts*. That's the celebrity problem. The act of posting is tiny; the **fan-out it triggers** is monstrous.

```
Normal user (200 followers):     1 tweet →        200 feed writes   → done in milliseconds
Celebrity (50,000,000 followers): 1 tweet → 50,000,000 feed writes  → minutes of work, huge load
```

What actually breaks, in plain terms:

- **Write amplification** — one human action (a tap on "Tweet") secretly becomes tens of millions of database writes. The *ratio* of work to action is insane.
- **Latency / backlog** — the fan-out worker is still stuffing feed #12,000,000 while early followers already saw the tweet and late ones are still waiting. Delivery becomes uneven and slow.
- **Hot partition (thundering herd)** — all those writes slam whatever storage holds feeds, overwhelming a few machines while others idle. (Exactly the **hot key** problem — same as a viral `ad_id` overloading one Kafka partition in ad-click aggregation.)
- **Wasted effort** — most of those 50M followers won't even open the app before the celebrity's *next* tweet, so you paid to build feeds nobody reads.

#### Q: Isn't this just the same "hot key" problem from other systems?

**Yes — it's the same monster in a new costume.** A "hot key" is any single entity that gets a disproportionate share of traffic: a viral ad_id, a trending URL, a celebrity account. The cure is always some flavor of "**don't let one entity funnel all the load through one path**" — here, that cure is the hybrid approach (§4); in ad-click aggregation, it's salting the key.

#### Q: Why not just make fan-out faster / add more workers?

You can (and systems do — background queues, parallel workers). But throwing hardware at 50M writes **per celebrity tweet** is wasteful when most feeds go unread, and it still causes bursty hot partitions. It's far cheaper to **not do the fan-out at all** for celebrities and instead pull their posts at read time (§4). Work avoided beats work optimized.

---

## 4. The Hybrid Solution

**Push for the masses, pull for celebrities.** This is the standard answer.

```
On post:
   if author is a NORMAL user  → fan-out on write (push post id into followers' feeds)
   if author is a CELEBRITY    → DON'T fan out; just store the post in their own timeline

On read (build home feed):
   feed = precomputed pushed posts               (from normal accounts you follow)
        ⊕ pull recent posts of the FEW celebrities you follow   (fan-in / merge)
        → rank → return
```

- **Threshold**: flag accounts above N followers (e.g. 100k) as "celebrity" → skip push for them.
- You follow only a handful of celebrities, so **pulling their latest posts at read time is cheap** — and you avoid the 50M-write explosion.
- **Best of both:** normal accounts get fast precomputed reads; celebrities avoid write amplification.
- Same idea for **notifications**: don't synchronously fan out to millions — use a **batch pipeline** (segment → chunk → queue → workers) and throttle producers.

```
Hybrid = cheap reads for the 99% (push) + cheap writes for the 1% celebrities (pull) + merge at read
```

### Plain-English: use push for most people, pull for the famous few

The trick is realizing the two approaches fail in **opposite** situations, so you use each where it's strong:

- **Normal accounts** have few followers → pushing is cheap → **push** (so reads stay fast).
- **Celebrities** have millions of followers → pushing is a disaster → **don't push**; store their post once and **pull** it at read time.

And here's why pulling celebrities is cheap even though pushing them is not: **you personally follow only a handful of celebrities.** So at read time you fetch a few celebrity timelines and merge them into your mostly-pre-built feed. Few pulls per reader ≪ 50M pushes per celebrity.

**Analogy:** your regular friends mail you their news directly (push — it's in your mailbox). But you don't ask the President to mail 300 million people; instead, when *you* want the news, you glance at the front page (pull the celebrity's public timeline). One shared page for everyone, read on demand.

#### Annotated code: the hybrid decision

On write — branch based on how many followers the author has:

```java
static final int CELEBRITY_THRESHOLD = 100_000;   // tune per system

void onPost(Post post) {
    postStore.save(post);   // ALWAYS store the post once (needed for pull path)

    int followerCount = graph.followerCount(post.authorId);

    if (followerCount < CELEBRITY_THRESHOLD) {
        // NORMAL user → push (fan-out on write) into each follower's feed
        for (Long followerId : graph.followersOf(post.authorId)) {
            feedStore.pushToFeed(followerId, post.id);
        }
    }
    // CELEBRITY → do NOTHING extra. No fan-out. The post is already stored,
    // and we'll fetch it at read time instead. This is what avoids 50M writes.
}
```

On read — combine the pushed feed with a fresh pull of the celebrities you follow:

```java
List<Post> readFeed(long userId) {
    // 1. The precomputed part: posts pushed by the NORMAL accounts you follow (fast)
    List<Post> feed = feedStore.getFeed(userId);

    // 2. The pull part: for the FEW celebrities you follow, grab their recent posts now
    for (Long celebId : graph.celebritiesFollowedBy(userId)) {   // usually a small handful
        feed.addAll(postStore.recentPostsOf(celebId));           // fan-IN: merge them in
    }

    // 3. Merge everything into one ranked timeline
    return rank(feed);
}
```

#### Q: Isn't there duplicate risk (a post both pushed and pulled)?

Only if an account crosses the threshold mid-stream; systems handle this by clearly marking who is a "celebrity" and pulling *only* those, so a given post takes exactly one path. The threshold (e.g. 100k followers) is the switch.

#### Q: Does the same idea apply to notifications and group chat?

Yes. "Blast one event to millions synchronously" is the same explosion. The fix is the same *spirit*: don't do a giant synchronous fan-out. Instead use a **batch pipeline** — segment the recipients, chunk them, drop chunks on a queue, and let a pool of workers deliver at a throttled rate. It spreads the fan-out over time and workers instead of one thundering blast.

---

## 5. Fan-In (Scatter-Gather / Aggregation)

Fan-in = combine many inputs into one result. Two common shapes:

### Scatter-gather (query many shards, merge)

```
Query → FAN-OUT to all N shards in parallel → each returns partial (e.g. top-k)
      → FAN-IN: coordinator MERGES + re-ranks → one response
```

- Used by **Elasticsearch** (search all shards → merge top-k), **sharded DBs** (query without a shard key), federated search.
- Cost: as slow as the **slowest shard** (tail latency); more shards = more tail risk. Mitigate with timeouts + partial results.

### Aggregation fan-in (many producers → one)

```
Many click events → one Kafka topic → stream aggregator → one counter per window
Many services → one log pipeline → one dashboard
```

- Used by **analytics** (ad-click aggregation), **counters** (likes/views), **log aggregation**, **MapReduce reduce**.

> **Pull-model feeds are fan-in too:** gather posts from all followees and merge into one timeline.

### Plain-English: asking many helpers one question, then combining answers

**Analogy — finding a book in a huge library.** The library is split across **10 floors** (shards), and you don't know which floor has your book. So you **shout the request to all 10 floors at once** (fan-out the query), each librarian searches *their* floor and hands back what they found, and you **staple the results into one list** (fan-in). That's **scatter-gather**.

```
             ┌─► shard 1 ─┐
your query ──┼─► shard 2 ─┼─► each returns its partial (e.g. its top-5) ─► MERGE + re-rank ─► answer
             └─► shard 3 ─┘
            (scatter = fan-out)                                        (gather = fan-in)
```

#### Annotated code: scatter-gather

```java
List<Result> search(String query) {
    List<Shard> shards = cluster.allShards();          // e.g. 10 shards

    // SCATTER (fan-out): fire the query at every shard IN PARALLEL
    List<Future<List<Result>>> futures = new ArrayList<>();
    for (Shard s : shards) {
        futures.add(threadPool.submit(() -> s.topK(query, 5)));   // each returns its best 5
    }

    // GATHER (fan-in): collect partial results and merge them into one answer
    List<Result> merged = new ArrayList<>();
    for (Future<List<Result>> f : futures) {
        merged.addAll(f.get(200, MILLISECONDS));       // NOTE the timeout — see tail-latency Q below
    }
    return topK(merged, 10);                            // re-rank the combined pile → final top 10
}
```

#### Q: Why is scatter-gather "as slow as the slowest shard"?

Because you can't build the final answer until **every** shard replies. If 9 shards answer in 10ms but 1 straggler takes 500ms, **you wait 500ms**. This is **tail latency**, and it gets *worse* with more shards (more shards = higher chance at least one is slow). Mitigations: set a **timeout** and return **partial results** (as `f.get(200, MILLISECONDS)` above does), or add shard-level retries/hedging.

#### Q: How is this different from the aggregation fan-in (counters, analytics)?

Both are "N → 1", but the *shape* differs:

| | **Scatter-gather fan-in** | **Aggregation fan-in** |
| --- | --- | --- |
| Trigger | One request fans out, then results come back | Continuous stream of independent events |
| Combine | **Merge / re-rank** a few partial lists | **Sum / count** many events into a rollup |
| Example | Elasticsearch search across shards | Ad-click counting, likes/views counters |
| Timing | Synchronous (you wait for the answer) | Usually asynchronous (events trickle in over time) |

The party analogy still holds: scatter-gather = "ask 10 helpers, staple their answers." Aggregation = "100 RSVPs trickle in all week; keep a running tally."

---

## 6. Where These Appear

| System | Fan-out | Fan-in |
| --- | --- | --- |
| **Twitter/Facebook/Instagram feed** | Push post → followers' feeds (write) | Pull + merge followees' posts (read) |
| **Notifications** | One event → many users → many channel jobs | — |
| **Group chat (WhatsApp)** | One message → all group members | — |
| **Search (Elasticsearch)** | Query → all shards | Merge top-k results |
| **Sharded DB query (no shard key)** | Query → all shards | Merge rows |
| **Analytics (ad clicks, counters)** | — | Many events → aggregated rollups |
| **MapReduce** | Map (split work) | Reduce (combine) |

### Plain-English: spotting fan-out/fan-in in the wild

Once you have the party lens, you'll notice these two words everywhere. A quick way to **spot which is which**: ask "**is one thing becoming many, or are many things becoming one?**"

- See **one input creating lots of work / copies / parallel calls**? → **fan-out** (posting to feeds, a query hitting all shards, one event triggering email+SMS+push).
- See **lots of things being merged / summed / stapled into one**? → **fan-in** (merging shard results, tallying clicks, combining followees' posts into a feed).

Many real systems do **both back-to-back**: a search *fans out* to shards then *fans in* the results; MapReduce *maps out* then *reduces in*; a pull feed *fans out* requests to followees then *fans in* their posts.

---

## 7. Trade-offs & Numbers

```
Assume: 200M users, avg 200 followers, celebrity = 50M followers

Pure push:  a normal post = 200 writes (fine)
            a celebrity post = 50,000,000 writes (disaster)
Pure pull:  every feed load = gather from ~hundreds of followees + merge (slow, repeated)
Hybrid:     normal posts pushed (fast reads) + pull ~handful of celebrities at read (cheap)
            → celebrity writes drop from 50M to ~0; reads stay fast
```

| Approach | Write cost | Read cost | Celebrity-safe? |
| --- | --- | --- | --- |
| Push (fan-out on write) | High (× followers) | Low | ❌ |
| Pull (fan-out on read) | Low | High | ✅ |
| **Hybrid** ✅ | Low–medium | Low–medium | ✅ |

### Plain-English: reading the numbers

The whole table is just "**where does the cost land, and is any single action catastrophic?**"

- **Push** — you pay `followers` writes **every post**. For a normal user (200) that's nothing; for a celebrity (50M) it's a disaster → the ❌.
- **Pull** — you pay `followees` fetches **every read**. Writes are trivial, but since people read far more than they post, you repeat this expensive gather constantly → slow.
- **Hybrid** — push the cheap majority (normal accounts) so reads stay fast, and pull the dangerous few (celebrities) so no single post explodes. Celebrity write cost collapses from **50M → ~0**, and reads only add a small pull for the handful of celebrities you follow. That's why it's the only row that's cheap-ish on *both* columns **and** celebrity-safe.

#### Q: If hybrid is best, why even learn push and pull?

Because hybrid is literally **"push here, pull there."** You can't choose the right side per case without understanding each pure approach's failure mode. Also, at small scale, plain push (or plain pull) is simpler and totally fine — you only reach for hybrid once celebrities/scale force your hand.

---

## 8. Interview Cheat Sheet

> **"What's fan-out vs fan-in?"**
> "Fan-out = deliver one input to many outputs (a post to N feeds, an event to N notifications, a query to N shards). Fan-in = combine many inputs into one (merge N shard results, aggregate N events). MapReduce is fan-out (map) then fan-in (reduce)."

> **"Fan-out on write vs read?"**
> "On write (push) precomputes each user's feed → fast reads but write amplification. On read (pull) stores once and gathers at read time → cheap writes but slow reads. Read-heavy → push; write-heavy → pull."

> **"What's the celebrity problem and how do you fix it?"**
> "Pure fan-out-on-write means one celebrity post (50M followers) triggers 50M feed writes — write amplification, latency spikes, hot partitions. Fix with a **hybrid**: push posts for normal accounts, but **don't fan out celebrity posts** — pull their recent posts at read time and merge into the feed. You follow few celebrities, so the pull is cheap, and you avoid the write explosion."

> **"What's scatter-gather?"**
> "Fan-out a query to all shards in parallel, then fan-in by merging their partial results (e.g. top-k) into one response — used by Elasticsearch and sharded DBs. Watch tail latency: you're as slow as the slowest shard."

---

## 9. Final Takeaways

- **Fan-out (1→N)** distribute; **fan-in (N→1)** combine. Map = fan-out, reduce = fan-in; scatter-gather = both.
- **Feeds:** push (fan-out on write) = fast reads / heavy writes; pull (fan-out on read) = cheap writes / heavy reads.
- **Celebrity problem** = fan-out-on-write write amplification (a hot-key problem). **Fix = hybrid**: push for normal accounts, pull celebrities at read, merge.
- **Notifications/group chat** fan out too — use a **batch pipeline + throttling**, not a synchronous blast.
- **Fan-in / scatter-gather** merges shard results (Elasticsearch, sharded queries) — mind **tail latency**; and aggregates event streams (analytics/counters).

### Related notes

- [Twitter / News Feed](../system-design/twitter-news-feed-system-design.md) · [Facebook](../system-design/facebook-system-design.md) · [Instagram](../system-design/instagram-system-design.md) — hybrid feed fan-out
- [Notification System](../system-design/notification-system-design.md) — event fan-out + batch pipeline
- [Ad Click Aggregation](../system-design/ad-click-aggregation-system-design.md) — fan-in aggregation · [Apache Kafka](kafka.md) · [Caching Strategies](caching-strategies.md)
