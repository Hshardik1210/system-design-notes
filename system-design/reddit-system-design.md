# Reddit — System Design (Communities, Feeds & Voting)

> **Core challenge:** communities (subreddits) of **posts** and deeply **nested comments**, ranked **feeds** ("hot"/"top"/"new"/"best"), **voting at massive scale**, and personalized home feeds — a **read-heavy** system where **ranking**, **feed generation**, and **vote aggregation** dominate.

> **How to read this doc:** each section has the dense interview summary first, then a **deep dive** (annotated Java/pseudocode and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand.

---

## Contents

- [1. Mental Model](#1-mental-model)
- [2. Requirements](#2-requirements)
- [3. Capacity Estimation](#3-capacity-estimation)
- [4. Architecture](#4-architecture)
- [5. Feed Generation (fan-out)](#5-feed-generation-fan-out)
- [6. Ranking (the math)](#6-ranking-the-math)
- [7. Voting at Scale](#7-voting-at-scale)
- [8. Nested Comments (tree modeling)](#8-nested-comments-tree-modeling)
- [9. Data Model (all tables)](#9-data-model-all-tables)
- [10. API Design](#10-api-design)
- [11. Sequences](#11-sequences)
- [12. Consistency & Edge Cases](#12-consistency--edge-cases)
- [13. Scaling & Failure](#13-scaling--failure)
- [14. Interview Cheat Sheet](#14-interview-cheat-sheet)
- [15. Consistency & CAP Tradeoffs](#15-consistency--cap-tradeoffs)
- [16. How to Drive the Interview (framework)](#16-how-to-drive-the-interview-framework)
- [17. Design Patterns (that can be used)](#17-design-patterns-that-can-be-used)
- [18. Final Takeaways](#18-final-takeaways)

---

## 1. Mental Model

```
User joins subreddits → posts + votes + comments → home feed = ranked merge of subscribed subreddits
```

Read-heavy content platform. The interesting parts: **how the feed is built** (fan-out), **how content is ranked** (hot/top/best), and **vote throughput** (aggregate async, never recount on read).

### What are we even building?

Reddit is a large set of communities called **subreddits** (`r/cooking`, `r/soccer`). Users create **posts** in a subreddit, others add **comments** underneath (and comments under comments — deeply nested), and everyone can **upvote** or **downvote** posts and comments.

The whole product is really three jobs:

1. **Ranking** — each board must show its "best" posts on top. Not just newest, not just most-liked — a mix ("**hot**"). Who decides the order? A ranking formula (see §6).
2. **Feed generation** — your **home feed** is a blended board made of the top posts from *all the boards you follow*. How do we build that per-user mix cheaply for 50M people? (see §5).
3. **Voting** — millions of stickers slapped per second. Counting them live on every page-load would melt the database, so we count them **in the background** (see §7).

### Why "read-heavy" changes everything

For every 1 person who **writes** (posts/comments/votes), ~100 people just **scroll and read**. So the entire design optimizes the **read path**:

> **Do the expensive work once, ahead of time, and cache the answer — so a read is just "hand back the pre-made list."**

The same subreddit ranked list is served to every visitor, so Reddit **precomputes** it once and shares it, instead of re-ranking per visitor. This one idea (precompute + cache, never recompute on read) drives §5, §6, and §7.

---

## 2. Requirements

> 💡 **Start here in the interview.** Clarify scope out loud and pin down that this is a **read-heavy, eventually-consistent** system — that single framing justifies almost every later decision (precompute + cache, async vote aggregation, shared hot lists).

**Functional**
- Create/join **subreddits**; create **posts** (text/link/image); **nested comments**.
- **Upvote/downvote** posts and comments; score-based ranking.
- Feeds: subreddit feed + personalized **home feed** (subscribed subs); sorts: hot/new/top/best.
- Search; moderation; awards.

### Non-Functional (NFRs)

| NFR | Target / Note |
| --- | --- |
| **Consistency** | **Eventual** for vote counts, scores, feed freshness (a few seconds of lag is fine). **Strong** only for "one vote per user per target" (the vote-write dedup). |
| **Availability** | High — reads should almost never fail; serve stale cached feeds/counts rather than error. |
| **Latency** | Feed read < 200ms (served from cache); vote/post writes feel instant (ack before aggregation). |
| **Scale** | **Read-heavy**, reads ≫ writes (~100:1); billions of posts/comments/votes; huge subs (10M+ subscribers). |
| **Durability** | Posts/comments/votes (source of truth) must never be lost; precomputed feeds are rebuildable and may be lossy. |

### Out of scope (state assumptions)

- Chat/DMs, ads & monetization, live video, awards economy, recommendation ML beyond ranking, spam-ML pipeline internals (mention, then defer).

---

## 3. Capacity Estimation

> Numbers are illustrative — the point is to **show the method**, not be exact. Do the read/write/storage split out loud; each number should point at a design decision.

```
Assume:
  Daily active users (DAU)   ~ 50M
  Feed opens per user/day    ~ 20      → 1B feed reads/day
  Posts per user/day         ~ 0.02    → 1M posts/day
  Comments per user/day      ~ 0.2     → 10M comments/day
  Votes per user/day         ~ 10      → 500M votes/day

Read QPS (feeds — the dominant path):
  1B / 86,400s              ~ 11.5k reads/sec average
  Peak (5–10x diurnal)      ~ 60k–120k reads/sec
  → MUST be served from precomputed/cached feeds, never rebuilt per request

Vote WRITE QPS (the firehose):
  500M / 86,400s            ~ 5,800 votes/sec average
  Peak (viral post / event) ~ 30k–60k votes/sec on hot targets
  → aggregate ASYNC (Kafka → counter workers); NEVER recount on read

Post / comment write QPS:
  (1M + 10M) / 86,400s      ~ 130 writes/sec average (tiny vs reads)
  → the write ledger is cheap; the hard part is the READ fan-out + vote volume

Storage (rough, 5-year horizon):
  Post row      ~ 1 KB  → 1M/day * 365 * 5 * 1KB   ~ 1.8 TB
  Comment row   ~ 0.5KB → 10M/day * 365 * 5 * 0.5KB ~ 9 TB   (comments dominate)
  Vote row      ~ 40 B  → 500M/day * 365 * 5 * 40B  ~ 36 TB  (largest by count)
  → partition posts/comments by subreddit/time, shard votes by target, archive cold
```

**Takeaways that drive design:** read-heavy (~100:1) → **precompute + cache feeds**; the vote firehose (tens of thousands/sec on hot targets) → **async delta aggregation**, never a read-time recount; **comments and votes dominate storage** → partition/shard by `subreddit`/`time`/`post_id`/`target` and archive cold content.

---

## 4. Architecture

```
Client → API Gateway
  ├── Post Service (create/read posts)     → posts store (sharded) + cache
  ├── Comment Service (tree)               → comments store (materialized path) + cache
  ├── Vote Service                         → votes table + Kafka events (async aggregation)
  ├── Feed Service (build/serve)           → Redis (per-sub hot lists + home feed) + ranking jobs
  ├── Search Service                       → Elasticsearch
  └── Moderation Service
             │
          Kafka (POST_CREATED, VOTE_CAST, COMMENT_CREATED → aggregation, ranking, feed, search)
```

- **CQRS:** read path = precomputed/cached feeds; write path = posts/comments/votes stores. Ranking is a **periodic job**, not per-request.

### The services and their jobs

Rather than one giant program, each service has one job, and they communicate through a shared event log (**Kafka**) instead of calling each other directly:

| Service | Its one job |
| --- | --- |
| **API Gateway** | Takes every request, routes it to the right service |
| **Post Service** | Stores & fetches posts |
| **Comment Service** | Stores the comment tree, fetches subtrees |
| **Vote Service** | Records "one vote per person," emits an event |
| **Feed Service** | Builds & serves the ranked lists people actually read |
| **Search / Moderation** | Find things; remove bad things |

**Kafka = the shared event log.** When something happens ("a vote was cast", "a post was created"), the service that did it **publishes an event** to Kafka and immediately moves on. Other services (aggregators, ranking jobs, search indexers) consume those events **later, at their own pace.** Nobody waits on anybody.

> 💡 **Why Kafka here:** it's a durable, replayable log that **decouples the write from the downstream work**. The vote firehose (§3) is absorbed by consumers reading at their own pace, and if the aggregator falls behind or crashes, events aren't lost — it just catches up. (See [Apache Kafka](../concepts/kafka.md).)

### CQRS: why the read path looks nothing like the write path

CQRS = **Command Query Responsibility Segregation** — a fancy way of saying **"the path for changing data is separate from the path for reading data."** The **write path (Command)** is what runs when you cast a vote or make a post: it goes to the "source of truth" databases, careful and correct, but not built for millions of readers. The **read path (Query)** is what runs when you scroll your feed: it's served from **precomputed, cached lists** (Redis), never by re-querying and re-ranking the raw tables.

> 💡 **CQRS in one line:** the read model is a *rebuildable projection* of the write model. Because reads never touch the write tables, you can scale, shape, and cache them independently — and a lost cache is just rebuilt from the source of truth.

In other words, the write side stores the raw source-of-truth data; the read side serves precomputed results built from it. Reads never touch the write-side tables directly. The ranking job rebuilds the read-side lists on a schedule — see §6.

---

## 5. Feed Generation (fan-out)

Two feeds: a **subreddit feed** and a **personalized home feed** (merge of subscribed subs).

| Approach | How | Trade-off |
| --- | --- | --- |
| **Pull (fan-out on read)** | At read, fetch top posts from each subscribed sub, merge + rank | Cheap writes; heavier reads |
| **Push (fan-out on write)** | On new post, push id into subscribers' feed caches | Fast reads; expensive for huge subs |
| **Hybrid** ✅ | Precompute per-sub hot lists; home feed **merges** subscribed subs' cached lists | Best of both |

> **Reddit-style (the key insight):** unlike Twitter (per-user push), Reddit precomputes a **per-subreddit ranked hot list** that is **shared by all subscribers** — so a huge sub is computed **once**, not fanned out to millions. The home feed **merges** the user's subscribed subs' cached lists + caches the result. This sidesteps the celebrity/huge-sub write-amplification problem. (See [Fan-Out / Fan-In](../concepts/fan-out-fan-in.md).)

### "Fan-out" and why the Reddit approach is clever

**"Fan-out"** just means: when one thing happens, how many places do we have to go update? Two extremes:

- **Push (fan-out on write):** the moment someone posts in `r/soccer`, immediately shove that post's id into the feed of **every one of the 30M subscribers.** Reads are then instant (your feed is pre-built), but the *write* is monstrous — one post = 30M list-inserts.
- **Pull (fan-out on read):** store nothing pre-built. When *you* open your home feed, go ask each of your subscribed subs "what's your top posts?" and merge them right then. Writes are trivial, but every read does a pile of work, and popular subs get hammered by every reader.

**The Reddit insight — compute the sub's list ONCE and share it.** A subreddit's "hot" list is **the same for everybody** (unlike a Twitter timeline, which is unique per person). So:

```
r/soccer's hot list  ──►  computed ONCE  ──►  cached  ──►  read by ALL 30M subscribers
```

Your **home feed** is then just a per-user merge: grab the top slice of each subreddit you follow, merge-and-rank those slices, and cache the small merged result.

```java
// HOME FEED = merge the already-cached per-sub hot lists (cheap!)
List<PostId> buildHomeFeed(long userId) {
    List<Long> mySubs = subscriptions.of(userId);          // e.g. [soccer, cooking, java]

    // heap that keeps the highest-hot_rank post on top
    PriorityQueue<ScoredPost> merged =
        new PriorityQueue<>(Comparator.comparingDouble(ScoredPost::hotRank).reversed());

    for (long subId : mySubs) {
        // NOTE: we read a READY-MADE cached list — we do NOT rank posts here
        List<ScoredPost> topSlice = redis.zRevRange("feed:sub:" + subId + ":hot", 0, 100);
        merged.addAll(topSlice);                            // just merging, not recomputing
    }

    List<PostId> homeFeed = takeTop(merged, 500);
    redis.set("feed:home:" + userId, homeFeed, Duration.ofMinutes(5)); // cache w/ short TTL
    return homeFeed;
}
```

> 💡 **Why a Redis sorted set backs `feed:sub:{id}:hot`:** a **sorted set** keeps members automatically ordered by a score (here `hot_rank`), so "give me the top 100 posts" is a single O(log N) range read (`ZREVRANGE`) instead of sorting the whole subreddit per request. The ranking job just re-scores members; readers always see a pre-ordered list.

#### Q: Isn't a huge subreddit (30M subs) the same "celebrity problem" as Twitter?

**No — and that's the whole point.** On Twitter, a celebrity's tweet must fan out to each follower's *personal* timeline (millions of writes) because every timeline is different. On Reddit, `r/soccer`'s hot list is **one shared list**; growing from 3M to 30M subscribers doesn't add any extra ranking work — it's still computed **once**. More subscribers just means more *readers of the same cached list* (and reads are cheap). Write-amplification vanishes.

So which part of this is "push" and which is "pull"? It's **push-ish** on the way in: each subreddit's hot list is **precomputed** (pushed into Redis by a background job), so it's ready before anyone asks. It's **pull-ish** on the way out: your **home feed** is **merged on read** from those cached lists (then cached briefly). You get fast reads (the heavy ranking is already done per sub) without the insane per-user write fan-out — best of both.

---

## 6. Ranking (the math)

Rankings are **precomputed periodically per subreddit** and cached — never computed per request.

### Hot (popularity + time decay)

```
hot = log10(max(|ups − downs|, 1)) × sign(ups − downs) + (epoch_seconds − 1134028003) / 45000
```

- **`log10(...)`** — the first 10 votes matter as much as the next 100; diminishing returns so a post doesn't dominate forever.
- **`sign(...)`** — downvoted posts sink.
- **time term** — newer posts get a higher base; the `45000` (~12.5h) means a post needs ~10× more votes to match one posted 12.5h earlier → **fresh content surfaces**. Because time is *additive*, hot scores are roughly monotonic in time → cache-friendly.

### Other sorts

| Sort | Idea |
| --- | --- |
| **Hot** | popularity + time decay (above) |
| **New** | pure recency (`created_at DESC`) |
| **Top** | pure score within a window (day/week/month/all) |
| **Best (comments)** | **Wilson score** lower bound — fair for items with few votes |

- **Wilson score** (for comments): the lower bound of a confidence interval on the upvote ratio → a comment with 5/5 upvotes doesn't outrank 900/1000 (accounts for sample size). Better than raw `ups − downs` or ratio.

> 💡 **Wilson score = "confidence-adjusted approval."** Raw ratio over-trusts tiny samples (5/5 = "100%"); raw `ups − downs` over-trusts high-traffic items. Wilson asks *"what's the pessimistic-but-fair approval given this many votes?"* — humble when data is thin, confident when it's plenty. That's why it's the "Best" comment sort.

### Decoding the "hot" formula

The scary formula is really just **two ideas added together**: "how liked is it?" **plus** "how fresh is it?"

```
hot = log10(max(|ups − downs|, 1)) × sign(ups − downs)   +   (epoch_seconds − 1134028003) / 45000
      └────────────── POPULARITY part ──────────────┘       └──────────── FRESHNESS part ─────────────┘
```

**Part 1 — Popularity, but with diminishing returns (`log10`).**

`log10` compresses big numbers — each additional point needs 10× more votes than the last, rather than growing linearly:

| Net votes | `log10` | What it means |
| --- | --- | --- |
| 10 | 1 | first 10 votes → +1 point |
| 100 | 2 | need 10× more votes for the next +1 |
| 1,000 | 3 | another 10× for the next +1 |

So the **first 10 upvotes matter as much as the jump from 100→1000.** Why? So a post that got hugely popular hours ago can't sit at the top forever — a fresh post only needs a *handful* of votes to compete. The `sign(...)` just flips the score negative for downvoted posts so they **sink**.

**Part 2 — Freshness (the time term).**

`epoch_seconds` is "seconds since 1970." Subtracting a fixed constant (`1134028003` = a date in 2005 when Reddit picked its "time zero") just makes the number smaller/cleaner. Divide by `45000` (≈ 12.5 hours):

> **A post made 12.5 hours later starts with a +1 head-start** — the *same* head-start that ~10× more votes would give (because of the `log10`). So newer posts naturally float up; to beat a fresher post, an older one needs **~10× the votes for every 12.5h it's older.**

```java
// Reddit's "hot" score. Runs in a background job, result cached as hot_rank.
double hot(long ups, long downs, Instant createdAt) {
    long score = ups - downs;                       // net votes (can be negative)
    long order = Math.max(Math.abs(score), 1);      // avoid log10(0); floor at 1

    double popularity = Math.log10(order);          // diminishing returns
    int sign = Long.signum(score);                  // +1 liked, -1 disliked, 0 neutral

    long seconds = createdAt.getEpochSecond() - 1_134_028_003L;  // seconds since Reddit epoch
    double freshness = seconds / 45_000.0;          // ~+1 every 12.5 hours

    return popularity * sign + freshness;           // POPULARITY + FRESHNESS
}
```

- **Why additive (not multiplied)?** Because time is just *added on*, a post's hot score only ever goes **up** as time passes (ignoring vote changes) — it never needs re-sorting relative to older posts. This makes the sorted list **stable and cache-friendly** (§5).

#### Q: What is the "Wilson score" for comments, in plain terms?

Raw ratio is misleading with few votes. A comment with **5 upvotes, 0 downvotes** is "100% liked" — but that's only 5 people. A comment with **900 up, 100 down** ("90%") is clearly more trustworthy. The **Wilson score** asks: *"given this sample size, what's the pessimistic-but-fair estimate of the true approval?"* Few votes → it stays humble (pulls the score down); many votes → it trusts the ratio. This is also what makes "Best" different from "Top": Best is approval *adjusted for how few votes it has* — a comment with 5/5 shouldn't beat one at 4.6/5 from 10,000 voters, and the raw ratio alone can't tell the difference.

```java
// Wilson lower bound — "confidence-adjusted upvote ratio". Higher = better.
double wilsonLowerBound(long up, long down) {
    long n = up + down;
    if (n == 0) return 0.0;
    double z = 1.96;                     // 95% confidence
    double phat = (double) up / n;       // observed upvote fraction
    return (phat + z*z/(2*n) - z * Math.sqrt((phat*(1-phat) + z*z/(4*n)) / n))
           / (1 + z*z/n);
}
// wilson(5,0)   ≈ 0.57   → humble: only 5 votes, don't over-trust
// wilson(900,100) ≈ 0.88 → confident: lots of data, ~90% really is ~90%
```

So **5/5 does NOT outrank 900/1000** — exactly what we want for fair comment ordering.

---

## 7. Voting at Scale

```
vote(user, target, +1/-1):
  1. UPSERT vote (dedupe: one vote per (user, target); a re-vote UPDATES value)
  2. do NOT recompute score synchronously on the read path
  3. emit VOTE_CAST → Kafka → async aggregator updates counters (up/down) + score
  4. periodic ranking job recomputes hot_rank per subreddit; cache the results
```

- **One vote per (user, target)** — unique constraint; changing a vote (+1 → −1) updates the row and adjusts counters by the **delta**.
- **Async aggregation** (Kafka consumer / batched) — approximate cached counts are fine; **exact recount on read doesn't scale** (a post can have millions of votes).
- **Vote fuzzing** (Reddit does this) — displayed counts are slightly randomized to deter vote-manipulation bots.
- Cache scores in Redis; ranking jobs refresh per-sub hot lists on an interval.

### Why we never count votes on the read path

Naive idea: to show a post's score, `SELECT COUNT(*) FROM votes WHERE target = post AND value = +1`. On a viral post with **2 million votes**, that counts 2M rows — **every single time anyone loads the page**. Thousands of people are loading it at once. The database dies.

**The fix — three separated steps** (this is the read-heavy pattern again):

1. **On vote (write):** just record *this one person's* vote and fire off a note. Don't touch any total.
2. **In the background (async):** a worker reads the notes and nudges a stored **counter** up or down.
3. **On read:** just read the **already-stored counter.** No counting, ever.

This is how a running total works: you don't re-sum every vote on each request. Each vote is recorded (step 1); a background process maintains the totals (step 2); reads return the stored total (step 3).

```java
// STEP 1 — casting a vote. Fast, tiny, touches ONE row. No totals computed here.
void castVote(long userId, String targetType, long targetId, int value) {  // value = +1 or -1

    // "one vote per (user, target)": if they already voted, UPDATE; else INSERT.
    // This dedupes AND lets someone flip their vote (+1 -> -1).
    Integer previous = votes.upsert(userId, targetType, targetId, value);

    // figure out how much the totals should shift (the "delta")
    int delta = value - (previous == null ? 0 : previous);
    // examples:  new +1 vote          → delta = +1
    //            flip +1 to -1        → delta = -2   (remove the up, add the down)
    //            re-click same vote   → delta =  0   (no change)

    // publish to Kafka and RETURN immediately. We do NOT update counts here.
    kafka.send("VOTE_CAST", new VoteEvent(targetType, targetId, delta, value, previous));
}
```

```java
// STEP 2 — background aggregator. Reads notes, nudges the stored counters.
@KafkaListener(topics = "VOTE_CAST", groupId = "vote-aggregator")
void onVote(VoteEvent e) {
    // adjust up/down counts and the net score by the DELTA (never a full recount)
    scoreStore.incrementScore(e.targetId(), e.delta());
    if (e.newValue() == +1) scoreStore.incrementUp(e.targetId(), +1);
    else                    scoreStore.incrementDown(e.targetId(), +1);
    // ... also decrement the old bucket if this was a flip ...

    redis.set("score:" + e.targetId(), scoreStore.score(e.targetId())); // cache for reads
}
```

```java
// STEP 3 — reading a score. Zero counting. Just hand back the cached number.
long displayScore(long targetId) {
    return redis.get("score:" + targetId);   // O(1). Might be a few seconds stale — fine.
}
```

Notice the aggregator never recomputes the total from scratch — recomputing would mean counting millions of rows again. **Delta = only the change caused by THIS vote.** A new upvote is `+1`; flipping your upvote to a downvote is `-2` (you remove one up *and* add one down). We only ever *nudge* the running counter by the delta, never re-tally all votes. And nothing stops a determined user from clicking upvote repeatedly, because they can't — the `PRIMARY KEY (user_id, target_type, target_id)` is **one row per (person, thing)**; voting again just **overwrites that one row** (an upsert), it never inserts a second. So your influence on any post is capped at exactly one vote, whatever you click.

#### Q: Isn't the displayed count now slightly wrong / delayed?

Yes — and **that's acceptable here** (it's not money). The background worker might be a few seconds behind, so a count could read 4,981 when it's "really" 4,987. Nobody cares if a Reddit score is off by a handful for a moment (**eventual consistency**). Reddit even *deliberately* fuzzes displayed counts (**vote fuzzing**) to confuse manipulation bots — so exactness was never the goal. Contrast this with ad-click *billing*, which must reconcile to the exact number.

### Moderation & anti-brigading

**Brigading** = a coordinated mob (or a bot farm) piling votes onto a target to artificially rocket it up or bury it. Because ranking is driven by vote counts, manipulation directly attacks the product. Defenses layer up, cheapest first:

| Layer | Defense | What it stops |
| --- | --- | --- |
| **Write gate** | Rate-limit votes per user/IP; account-age/karma minimums; CAPTCHA on suspicious sessions | Trivial bot spam and fresh throwaway accounts |
| **Signal obfuscation** | **Vote fuzzing** — displayed counts are slightly randomized | Bots can't tell if their votes "landed," breaking their feedback loop |
| **Detection (async)** | Anomaly jobs on the `VOTE_CAST` stream — velocity spikes, correlated voter clusters, off-site referral bursts | Organized brigades and vote rings |
| **Moderation actions** | Remove/lock/quarantine content; shadow-remove; **tombstone** deleted nodes (`[deleted]`, keep tree — §8) | Rule-breaking content while preserving thread structure |
| **Ranking dampening** | Discount suspicious votes from `hot_rank`; "controversial" sort surfaces high up+down churn | Manipulated posts silently sinking without alerting the attacker |

Moderation runs **off the hot path**: the Moderation Service consumes Kafka events and writes to a `moderation` table (§9), so it never slows down votes or reads. Because vote aggregation is already async, throwing out flagged votes is just *another consumer* adjusting the same counters by a negative delta.

#### Q: How does vote fuzzing actually deter brigading without ruining the real counts?

Fuzzing adds small, deterministic-per-viewer noise to the **displayed** count while the **true** count is kept internally for ranking. A manipulation bot upvotes and then re-reads the score to check whether its vote "worked" — with fuzzing, the number it sees jitters, so it can't confirm its influence or calibrate how many more sock-puppets to throw in. The real ranking still uses the true (or anomaly-filtered) totals, so honest users see roughly-correct scores and the sort order stays sane. It works precisely *because* exactness was never a requirement here (§7) — we already accept approximate counts, so hiding the exact number costs us nothing but costs the attacker their feedback loop. It's a deterrent, not a wall: it pairs with rate limits and anomaly detection, which do the actual removal.

---

## 8. Nested Comments (tree modeling)

Comments form a **tree** (replies to replies, arbitrarily deep). Storage options:

| Model | How | Trade-off |
| --- | --- | --- |
| **Adjacency list** (`parent_id`) | Each row points to its parent | Simple; fetching a subtree needs recursion/CTE (N queries or a recursive query) |
| **Materialized path** (`path='1/5/9'`) ✅ | Store the full path from root | Fetch a whole subtree by **prefix match** (`WHERE path LIKE '1/5/%'`); easy ordering; path can get long |
| **Closure table** | Row per ancestor-descendant pair | Flexible ancestor/descendant queries; more storage/writes |

> ⚠️ **Materialized-path pitfall:** the `path` string grows with depth and **moving a comment means rewriting every descendant's `path`** — fine for Reddit (comments rarely move) but a real cost if your tree is frequently re-parented. Also index the `path` (and cap displayed depth) or deep-thread prefix scans get slow.

- **Load pattern:** fetch top-level comments + a few levels ranked by **best**; **lazy-load** deeper threads ("load more replies") — never load a 10k-comment tree at once.
- **Rank siblings** by best/top/new (Wilson for best).
- Comment counts + scores aggregated async like votes.

### Storing a tree in a flat table

A comment thread is a **tree**: a top comment, its replies, replies-to-replies, arbitrarily deep. But a SQL table is **flat rows**. How do we fit a tree into flat rows? Three schemes:

```
Comment tree we want to store:
  #1 "Great recipe!"
   └─ #5 "Agreed, tried it"
       └─ #9 "Me too, added garlic"
   └─ #6 "Too salty for me"
```

**Option A — Adjacency list: each comment remembers its parent.**

```
comment_id | parent_id | body
1          | null      | Great recipe!
5          | 1         | Agreed, tried it
9          | 5         | Me too, added garlic
6          | 1         | Too salty for me
```

Simple (just a `parent_id` pointer). Problem: to fetch the whole subtree under #1 you must walk **level by level** — "who are #1's children? → #5, #6. Who are #5's children? → #9…" That's many queries (or a recursive query), because each row only knows its own parent.

**Option B — Materialized path: each comment stores its FULL address (the winner ✅).**

```
comment_id | path    | body
1          | 1       | Great recipe!
5          | 1/5     | Agreed, tried it
9          | 1/5/9   | Me too, added garlic
6          | 1/6     | Too salty for me
```

The `path` encodes the full ancestry (`1/5/9`), like a file-system path. To get everything under comment #1, you don't recurse — you just do a **prefix match**:

```sql
SELECT * FROM comments
WHERE post_id = 42 AND path LIKE '1/%'   -- everything whose address starts with "1/"
ORDER BY path;                           -- ordering by path keeps replies under parents
```

**One query, no recursion.** The cost: paths get long for very deep threads, and moving a comment means rewriting descendants' paths (rare on Reddit).

**Option C — Closure table:** a separate table storing *every* ancestor→descendant pair. Most flexible for arbitrary "all ancestors of X" queries, but more storage and more writes per insert. Usually overkill for Reddit.

```java
// Building the materialized path when inserting a reply = parent's path + new id.
String buildPath(Comment parent, long newId) {
    return (parent == null)
        ? String.valueOf(newId)                 // top-level comment: path = its own id
        : parent.path() + "/" + newId;          // reply: parent's path + "/newId"
}

// Fetch a subtree by prefix — the whole reason we chose materialized path.
List<Comment> loadSubtree(long postId, String rootPath) {
    return db.query(
        "SELECT * FROM comments WHERE post_id = ? AND path LIKE ? ORDER BY path",
        postId, rootPath + "/%");
}
```

### Why "lazy-load" instead of fetching the whole tree

A popular post can have **50,000 comments** nested 20 levels deep. Loading and rendering all of it would be huge and slow, and you'd never read most of it. So Reddit loads only the **top-level comments + a couple levels**, each collapsed with a **"load more replies (137)"** button. You fetch deeper branches **only when a user clicks**, instead of loading the entire tree upfront.

```java
// Initial load: top-level comments, ranked by "best", capped depth. Cheap.
List<Comment> initialLoad(long postId) {
    // depth = number of "/" segments in path; load only shallow comments first
    return db.query(
        "SELECT * FROM comments WHERE post_id = ? AND depth <= 2 ORDER BY score DESC LIMIT 200",
        postId);
}
// Later, when user clicks "load more replies" under comment #5:
List<Comment> loadMore(long postId, String parentPath) {  // parentPath = "1/5"
    return loadSubtree(postId, parentPath);               // fetch that branch on demand
}
```

### Ordering siblings, and what happens on delete

**Siblings** (comments at the same level) are ranked by **best** (Wilson score, §6), or top/new if you switch sorts. **Deleting** a comment that has replies uses a **tombstone**: the body becomes `[deleted]` but the **row (and its `path`) stays**, so the replies underneath don't become orphans and the tree structure survives.

---

## 9. Data Model (all tables)

### Database & storage choices (which DB, and why at scale)

No single database is best for every job here, so we use **polyglot persistence** — pick the store that matches each data type's access pattern. The deciding question is *"does this need transactional correctness (votes, one-per-user), or is it a hot, disposable ranking of that data (feeds, scores)?"*

| Data | Store | Why this one | Why not the alternative |
| --- | --- | --- | --- |
| Users, subreddits, posts, votes (**source of truth**) | **RDBMS**, sharded/partitioned by `subreddit_id` or time | `votes` PK `(user, target_type, target_id)` gives "one vote per user per target" for free via a unique constraint — an eventually-consistent store would need hand-rolled dedup. Posts partitioned by subreddit or time keep hot communities' data together and let cold content be archived cheaply. | A NoSQL store loses the atomic uniqueness guarantee on votes and the transactional counter updates (§7) that make re-voting a safe delta, not a race. |
| Comment trees at extreme depth/scale | **Cassandra** (optional beyond RDBMS ceiling) | Deep threads (millions of comments per hot post) are write-heavy and append-mostly — Cassandra's LSM engine and wide-column model absorb that volume once a single sharded RDBMS can't, while the materialized `path` column keeps subtree reads (`WHERE path LIKE 'prefix%'`) working the same way. | Reaching for Cassandra by default gives up the relational `votes`/comment referential checks for scale you may not need yet — only adopt it once comment write throughput, not correctness, is the bottleneck. |
| Precomputed hot/new feeds, cached vote scores | **Redis** (sorted sets) | `feed:sub:{id}:hot` as a sorted set keeps posts pre-ordered by `hot_rank`, so "top 100" is one range read instead of a per-request sort over the whole subreddit — computed **once per subreddit**, shared by everyone (§13), not per-user. | Recomputing `hot_rank` from `votes`/`posts` on every page load doesn't scale to Reddit's read volume; the feed is a rebuildable read model, safe to keep in a lossy cache. |
| Search | **Elasticsearch** | Full-text search over posts/comments needs an inverted index. | RDBMS text scans don't rank or tokenize well across millions of posts. |

**Why RDBMS wins for the vote/post ledger, and how it scales:** votes and posts need exact-once semantics (no double vote, correct delta on re-vote) that only a transactional store gives cheaply — and per-post write volume, even on a viral post, is a bounded stream an aggregator can absorb asynchronously (§11) rather than needing raw NoSQL write throughput. We partition posts/comments **by subreddit or time** (comments additionally shard by `post_id` since deep threads dominate storage) so a hot subreddit's data stays co-located and cold subreddits archive independently; votes shard by target so aggregation workers process one post/comment's vote stream on one shard. The read/write split is stark: writes (votes) go through Kafka to an async aggregator, while reads never touch the RDBMS at all — they hit the cached `feed:sub:{id}:hot` / `score:{id}` in Redis, rebuilt from the RDBMS if ever lost. (For the full engine trade-off matrix, see [Databases — Deep Dive](../concepts/databases-deep-dive.md).)

```sql
CREATE TABLE users ( user_id BIGINT PRIMARY KEY, username VARCHAR(50) UNIQUE, karma BIGINT DEFAULT 0, created_at TIMESTAMP );
CREATE TABLE subreddits ( subreddit_id BIGINT PRIMARY KEY, name VARCHAR(50) UNIQUE, description TEXT, member_count BIGINT DEFAULT 0 );
CREATE TABLE subscriptions ( user_id BIGINT, subreddit_id BIGINT, PRIMARY KEY(user_id, subreddit_id) );

CREATE TABLE posts (
    post_id BIGINT PRIMARY KEY,           -- Snowflake (time-sortable)
    subreddit_id BIGINT NOT NULL, author_id BIGINT NOT NULL,
    title TEXT, type VARCHAR(10), body TEXT, url TEXT,
    score INT DEFAULT 0, up_count INT DEFAULT 0, down_count INT DEFAULT 0,
    comment_count INT DEFAULT 0, hot_rank DOUBLE PRECISION,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_posts_sub_hot ON posts(subreddit_id, hot_rank DESC);   -- subreddit hot feed
CREATE INDEX idx_posts_sub_new ON posts(subreddit_id, created_at DESC); -- new feed

CREATE TABLE comments (
    comment_id BIGINT PRIMARY KEY, post_id BIGINT NOT NULL,
    parent_id BIGINT, path TEXT,          -- materialized path e.g. '1/5/9'
    author_id BIGINT, body TEXT, score INT DEFAULT 0, created_at TIMESTAMP
);
CREATE INDEX idx_comments_post_path ON comments(post_id, path);         -- subtree by prefix

CREATE TABLE votes (
    user_id BIGINT, target_type VARCHAR(10), target_id BIGINT,          -- POST | COMMENT
    value SMALLINT, created_at TIMESTAMP,                               -- +1 / -1
    PRIMARY KEY (user_id, target_type, target_id)                       -- one vote per user per target
);

CREATE TABLE moderation ( id BIGINT PRIMARY KEY, target_type VARCHAR(10), target_id BIGINT, action VARCHAR(20), mod_id BIGINT, at TIMESTAMP );
CREATE TABLE awards ( award_id BIGINT PRIMARY KEY, target_id BIGINT, giver_id BIGINT, type VARCHAR(20) );

-- Precomputed feeds → Redis:
--   feed:sub:{id}:hot  = sorted set of post ids (score = hot_rank)
--   feed:home:{userId} = merged, cached home feed
--   score:{targetId}   = cached vote score
```

> **Tables to consider:** users, subreddits, subscriptions, posts, comments, votes, moderation, awards, media_refs, precomputed feeds (Redis), search index (ES).

### Reading the schema

For each table, the thing to note is *which columns are precomputed and which indexes make reads fast.*

| Table | In one sentence | The clever bit |
| --- | --- | --- |
| `users` / `subreddits` | People and communities | `karma`, `member_count` are **counters** kept up-to-date async |
| `subscriptions` | "who follows which sub" | drives home-feed merge (§5) |
| `posts` | the posts themselves | stores **denormalized** `score`, `up_count`, `hot_rank` right on the row |
| `comments` | the comment tree | `path` = materialized path (§8) |
| `votes` | one row per (user, target) | the `PRIMARY KEY` **is** the "one vote per person" rule |

#### Q: Why store `score`, `up_count`, `hot_rank` ON the post row? Isn't that duplicated data?

Yes — it's **deliberate denormalization.** In a "pure" design you'd derive the score by counting the `votes` table every time. But we said counting-on-read doesn't scale (§7). So we **precompute** those numbers and **paste them onto the post row** (updated async). Reading a post then gives you its score for free, no join, no count.

> Trade-off: the stored `score` can briefly disagree with a fresh recount of `votes` (eventual consistency) — acceptable, since exact vote counts don't matter here.

The `CREATE INDEX` lines earlier are what make those precomputed columns actually pay off on read. `CREATE INDEX idx_posts_sub_hot ON posts(subreddit_id, hot_rank DESC)` keeps rows pre-sorted so the DB can jump straight to them instead of scanning everything — it keeps each subreddit's posts **pre-sorted by `hot_rank`**, so "give me r/soccer's hottest posts" is an instant top-N read, not a sort-the-whole-sub operation. The `idx_comments_post_path` index does the same for the "fetch subtree by path prefix" query in §8.

And why is some data in SQL while feeds/scores live in Redis? Different tools for different jobs. **SQL (source of truth)** is durable, correct, the real record of posts/comments/votes. **Redis (fast serving layer)** holds the precomputed feeds (`feed:sub:{id}:hot` as a **sorted set**) and cached `score:{id}` — a fast serving layer (§4) rebuilt from SQL if lost, so it can be fast and slightly lossy. A Redis **sorted set** is perfect for a hot list: it keeps ids automatically ordered by a score (the `hot_rank`), so "top 100 posts" is a single fast range read.

---

## 10. API Design

```
GET  /v1/r/{sub}/posts?sort=hot&cursor=          # subreddit feed
GET  /v1/home?sort=hot&cursor=                    # personalized feed
POST /v1/r/{sub}/posts        { title, body|url }
GET  /v1/posts/{id}/comments?sort=best&cursor=    # top-level + a few levels
POST /v1/posts/{id}/comments  { parentId?, body }
GET  /v1/comments/{id}/replies?cursor=            # "load more replies" (lazy)
POST /v1/vote                 { targetType, targetId, value }
POST /v1/subreddits/{id}/subscribe
```

### From button to backend

Tie each user action to the endpoint it hits and what happens behind it — note that the read paths are pure cache hits and the writes return **before** aggregation finishes:

| User action (button) | API call | What the backend does |
| --- | --- | --- |
| Open home feed | `GET /v1/home?sort=hot` | Read cached `feed:home:{userId}`; on miss, merge subscribed subs' cached hot lists (§5) — no ranking on the request |
| Open a subreddit | `GET /v1/r/{sub}/posts?sort=hot` | Range-read the `feed:sub:{id}:hot` sorted set (§5) — no per-request sort |
| Open a post's comments | `GET /v1/posts/{id}/comments?sort=best` | Fetch shallow comments by `path` prefix, ranked by Wilson (§8); deep threads collapsed |
| Click "load more replies" | `GET /v1/comments/{id}/replies` | Lazy subtree fetch by `path LIKE 'prefix/%'` (§8) |
| Submit a post | `POST /v1/r/{sub}/posts` | Insert into `posts` (source of truth) → emit `POST_CREATED` to Kafka → ranking/search pick it up async |
| Add a comment | `POST /v1/posts/{id}/comments` | Insert with materialized `path` → emit `COMMENT_CREATED`; comment counter bumped async |
| Click ▲ / ▼ (vote) | `POST /v1/vote` | Upsert one `votes` row (dedup) → emit `VOTE_CAST` with the **delta** → return immediately; counters/hot_rank update async (§7) |
| Join a subreddit | `POST /v1/subreddits/{id}/subscribe` | Insert into `subscriptions`; changes which subs the home-feed merge reads (§5) |

---

## 11. Sequences

### Vote (async aggregation)

```
User → VoteSvc: UPSERT vote (dedupe) → emit VOTE_CAST → Kafka
Aggregator: consume → update up/down counters + score (by delta) → cache score
Ranking job (periodic): recompute hot_rank per subreddit → refresh feed:sub:{id}:hot
(read path never recounts — reads cached score/hot lists)
```

### Home feed read

```
User → FeedSvc:
  for each subscribed sub → read cached feed:sub:{id}:hot (top slice)
  merge + rank across subs → cache feed:home:{userId} (short TTL) → hydrate post content → paginate
```

---

## 12. Consistency & Edge Cases

| Case | Handling |
| --- | --- |
| Double vote / re-vote | `UNIQUE(user, target)`; re-vote updates value, adjust counters by delta |
| Vote count exactness | Async aggregation → **approximate** cached counts (fine); vote fuzzing deters bots |
| Deleted post/comment | Tombstone; keep tree structure ("[deleted]") so replies survive |
| Huge comment thread | Materialized path + lazy "load more"; never load full tree |
| Ranking staleness | Periodic recompute; a slightly stale hot list is acceptable (eventual) |
| Hot post (viral) | Cached score + cached feed slice; DB not hit per view |
| Brigading/manipulation | Rate limit, vote fuzzing, moderation, anomaly detection |

---

## 13. Scaling & Failure

- **Read path** = cache/precomputed feeds (Redis); DB rarely hit for feed reads.
- **Vote firehose** → Kafka → async counter aggregation; approximate cached counts.
- **Per-sub hot lists** computed **once** and shared by all subscribers (not per-user fan-out) → huge subs are cheap.
- **Home feed** = merge subscribed subs' cached lists + cache the merged result (short TTL).
- **Comment trees** via materialized path; lazy-load deep threads.
- **Partition** posts/comments by subreddit or time; archive cold content; shard votes by target.
- **Eventual consistency:** vote counts / feed freshness lag briefly — acceptable.

### Where caching lives and why it saves us

Caching = **keep the answer close and pre-made so you don't redo work.** In this system there are a few layers of "pre-made answers," each avoiding an expensive operation:

| Cache | What it stores | Expensive thing it avoids |
| --- | --- | --- |
| `feed:sub:{id}:hot` | each sub's ranked post ids (Redis sorted set) | re-ranking a whole subreddit per reader |
| `feed:home:{userId}` | your merged home feed (short TTL) | re-merging your subs on every scroll |
| `score:{targetId}` | a post/comment's current score | counting millions of `votes` rows |

Most reads hit a pre-made cached result instantly; the database (the expensive recompute) only runs on a schedule in the background. So how do new posts and votes ever show up if feeds are cached? Two mechanisms refresh the pre-made answers. **TTL (time-to-live):** the home feed cache expires after a few minutes, so it gets rebuilt periodically with fresh data — a little staleness is fine. **Background ranking jobs:** every interval, a job recomputes each sub's `hot_rank` from the latest counters and **overwrites** `feed:sub:{id}:hot`, so new/rising posts flow into the cached lists on the next cycle. You accept that a brand-new post or a just-cast vote might take a few seconds to a minute to appear — **eventual consistency**, the deliberate trade for a read path that scales.

#### Q: What if a super-popular post causes a "cache miss stampede"?

If a hot cached value expires and 10,000 readers hit it at the same instant, they might **all** try to rebuild it at once and hammer the DB (a "thundering herd"). Guards: rebuild the value with a **single lock** (only one worker recomputes, others wait for it), **stagger TTLs**, or **refresh-ahead** (recompute just before expiry). For Reddit's hottest posts, the score/feed is kept warm so the DB is essentially never hit per view.

---

## 14. Interview Cheat Sheet

> **"How do you build the home feed?"**
> "Precompute a **shared per-subreddit hot list** (computed once, not fanned out to each subscriber), then **merge** the user's subscribed subs' cached lists into a home feed and cache it (hybrid). This avoids the huge-sub write-amplification problem — a 30M-subscriber sub is ranked once."

> **"How is 'hot' ranking computed?"**
> "`log10(votes) × sign + time/45000` — log-scaled votes (diminishing returns) plus an additive time term so fresh posts surface (a post needs ~10× votes to match one 12.5h older). Computed periodically and cached, never per request. Top = score in a window; Best comments = Wilson lower bound."

> **"How do you handle massive vote volume?"**
> "One vote per (user, target) with a unique constraint; emit to Kafka; async aggregator updates counters by delta and a periodic job recomputes rankings. Approximate cached counts (with vote fuzzing) — exact recount on read doesn't scale."

> **"How are nested comments stored?"**
> "Materialized path (`'1/5/9'`) → fetch a subtree by prefix, lazy-load deeper replies, rank siblings by best (Wilson). Adjacency list is simpler but needs recursive queries."

### Tricky scenarios (rapid-fire)

| Scenario | What happens / what to do |
| --- | --- |
| **Vote-count stampede** (viral post, 10k readers hit an expired `score:{id}`) | Reads serve the cached counter; keep hot scores warm + single-flight rebuild so the DB isn't recounted per view (§13). Counts are approximate anyway. |
| **Celebrity post / huge-sub fan-out** (`r/soccer` at 30M subscribers) | **No per-user fan-out** — the sub's hot list is computed **once** and shared; more subscribers = more readers of the *same* cached list, not more writes (§5). |
| **Comment tree hot path** (50k comments, 20 levels deep) | Load top-level + a couple levels by `path` prefix ranked by best; **lazy "load more replies"** on click; never fetch the whole tree (§8). |
| **Stale hot rank** (new/rising post not showing yet) | Accept it — ranking is a periodic job + short feed TTL; a post surfaces on the next cycle (**eventual consistency**, §13). Tighten the interval if freshness matters more. |
| **Re-vote / double-click** (user flips ▲ → ▼, or clicks twice) | `PRIMARY KEY (user, target_type, target_id)` upsert → one row per person; adjust counters by the **delta** (`+1`, `-2`, or `0`), never a recount (§7). |
| **Brigading spike** (coordinated vote mob) | Rate limits + account-age gates + **vote fuzzing** + async anomaly detection discounting suspicious votes (§7 moderation). |

---

## 15. Consistency & CAP Tradeoffs

> Interviewers love: "Where do you choose consistency vs availability?" Reddit's answer is mostly **AP**, with **one small CP island**.

| Path | Choice | Why |
| --- | --- | --- |
| **Vote write — "one vote per user per target"** | **CP** (strong) | The `votes` PK `(user, target_type, target_id)` must atomically dedup/flip a vote. This single row is the only place correctness matters. |
| **Vote counts / scores** | **AP** (eventual) | Aggregated async by delta; a count off by a few for a few seconds is fine (and deliberately fuzzed anyway). |
| **`hot_rank` / feeds** | **AP** (eventual) | Precomputed periodically + cached with TTL; a slightly stale ranking is acceptable — reads must never fail. |
| **Comment counts, karma, member counts** | **AP** (eventual) | Denormalized counters updated off the write path. |
| **Search index** | **AP** (eventual) | Elasticsearch is a read model rebuilt from the source of truth via events. |

- The **write ledger** (posts/comments/votes) is a durable, transactional source of truth; the **read models** (feeds, counters, search) are rebuildable projections that favor availability.
- The only strong-consistency requirement is the **vote uniqueness constraint** — everything a reader sees is allowed to lag.

> One-liner: **"CP on the one-vote-per-user write, AP (eventual) on every count, rank, and feed a reader sees."** Contrast BookMyShow, where the *inventory write* is CP because double-booking is unacceptable — here nothing a user reads needs to be exact.

---

## 16. How to Drive the Interview (framework)

> Use this order so you never freeze. Spend ~5 min on 1–4, then go deep on 5–7.

1. **Clarify requirements** (functional + NFRs, read-heavy framing) — §2
2. **Estimate scale** (read QPS ≫ vote write QPS; storage) — §3
3. **Define APIs** (feeds, comments, vote) — §10
4. **High-level architecture + data model** — §4, §9
5. **Deep dive: the hard part** → **feed generation + ranking** — §5, §6
6. **Deep dive: vote volume + nested comments** — §7, §8
7. **Address consistency, scale, failure, abuse** — §12, §13, §15, §7 (moderation)
8. **Summarize tradeoffs** — §15, §14

> 🎤 **Lead with the core insight:** a subreddit's hot list is **the same for every viewer**, so unlike Twitter you compute it **once and share it** — no per-user write fan-out even for a 30M-subscriber sub. State that up front, then show how ranking, voting, and comments all hang off the "precompute once, cache, never recompute on read" idea. That framing is what separates a senior answer from "I'd just query and sort."

---

## 17. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Strategy** | Ranking (hot/top/best/new), feed generation (push/pull/hybrid) | Swap algorithms |
| **Composite** | Nested comment tree | Uniform tree ops |
| **Observer / Pub-Sub** | Vote/post/comment events → counters, feeds, search | Decouple write from aggregation |
| **CQRS + Materialized View** | Precomputed feeds/counters vs write model | Fast reads, avoid recompute |
| **Producer-Consumer** | Async vote aggregation via Kafka | Absorb the vote firehose |
| **Repository** | Data access | Testable |
| **Decorator** | Post rendering (awards, flair, NSFW) | Compose display |
| **Facade** | Feed service over sub lists + rank + cache | Simple API |

---

## 18. Final Takeaways

- **Read-heavy** → precomputed, cached feeds; DB rarely touched on reads (**CQRS + materialized views**).
- **Shared per-subreddit hot lists** (computed once) + merged home feed = no huge-sub write amplification.
- **Hot ranking** = log-scaled votes + additive time decay; **Best** = Wilson score; computed periodically + cached.
- **Voting** = one per user/target, async delta aggregation, approximate cached counts (+ fuzzing).
- **Comments** = tree via materialized path, lazy-loaded, ranked by best.
- Patterns: Strategy, Composite, Observer/Producer-Consumer, CQRS/Materialized View.

### Related notes

- [Quora — System Design](quora-system-design.md) · [Twitter / News Feed](twitter-news-feed-system-design.md) — sibling feed/ranking platforms
- [Fan-Out / Fan-In](../concepts/fan-out-fan-in.md) · [Caching Strategies](../concepts/caching-strategies.md) · [Apache Kafka](../concepts/kafka.md) · [Database Indexing](../concepts/database-indexing.md)
