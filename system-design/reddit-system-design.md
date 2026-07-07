# Reddit — System Design (Communities, Feeds & Voting)

> **Core challenge:** communities (subreddits) of **posts** and deeply **nested comments**, ranked **feeds** ("hot"/"top"/"new"/"best"), **voting at massive scale**, and personalized home feeds — a **read-heavy** system where **ranking**, **feed generation**, and **vote aggregation** dominate.

> **How to read this doc:** each section has the dense interview summary first, then a **Plain-English** deep dive (analogies, annotated Java/pseudocode, and the exact confusions that come up while learning). Skim the summaries for revision; read the plain-English parts to actually understand.

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
- [13. Design Patterns (that can be used)](#13-design-patterns-that-can-be-used)
- [14. Scaling & Failure](#14-scaling--failure)
- [15. Interview Cheat Sheet](#15-interview-cheat-sheet)
- [16. Final Takeaways](#16-final-takeaways)

---

## 1. Mental Model

```
User joins subreddits → posts + votes + comments → home feed = ranked merge of subscribed subreddits
```

Read-heavy content platform. The interesting parts: **how the feed is built** (fan-out), **how content is ranked** (hot/top/best), and **vote throughput** (aggregate async, never recount on read).

### Plain-English: what are we even building?

Picture Reddit as a giant set of **notice boards in a community hall.** Each board is a **subreddit** (`r/cooking`, `r/soccer`). People pin **posts** on a board, others scribble **comments** underneath (and comments under comments — deeply nested), and everyone slaps an **upvote** or **downvote** sticker on the stuff they like or hate.

The whole product is really three jobs:

1. **Ranking** — each board must show its "best" posts on top. Not just newest, not just most-liked — a mix ("**hot**"). Who decides the order? A ranking formula (see §6).
2. **Feed generation** — your **home feed** is a blended board made of the top posts from *all the boards you follow*. How do we build that per-user mix cheaply for 50M people? (see §5).
3. **Voting** — millions of stickers slapped per second. Counting them live on every page-load would melt the database, so we count them **in the background** (see §7).

### Plain-English: why "read-heavy" changes everything

For every 1 person who **writes** (posts/comments/votes), ~100 people just **scroll and read**. So the entire design optimizes the **read path**:

> **Do the expensive work once, ahead of time, and cache the answer — so a read is just "hand back the pre-made list."**

Analogy: a newspaper doesn't re-typeset the front page every time a reader opens it. Editors decide the layout **once**, print thousands of identical copies, and each reader just grabs one. Reddit does the same — it **precomputes** each subreddit's ranked list once and shares it with everyone, instead of re-ranking per visitor. This one idea (precompute + cache, never recompute on read) drives §5, §6, and §7.

---

## 2. Requirements

**Functional**
- Create/join **subreddits**; create **posts** (text/link/image); **nested comments**.
- **Upvote/downvote** posts and comments; score-based ranking.
- Feeds: subreddit feed + personalized **home feed** (subscribed subs); sorts: hot/new/top/best.
- Search; moderation; awards.

**Non-functional**
- **Read-heavy** (reads ≫ writes, ~100:1) → cache + precomputed feeds.
- **Eventual consistency** fine (vote counts, feed freshness lag OK).
- Scale to billions of votes/posts/comments.

---

## 3. Capacity Estimation

```
DAU ~ 50M · reads:writes ~ 100:1
Feed reads (peak) ~ 100k+/sec → MUST be cached/precomputed (never rebuilt per request)
Votes ~ very high write volume → aggregate ASYNC; approximate cached counts
Posts/comments grow forever → partition by time/subreddit + archive cold
Storage: comments dominate (deep threads); shard by post_id
```

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

### Plain-English: the services as a team of specialists

Don't picture one giant program. Picture a **team where each person has one job**, and they leave notes for each other on a shared bulletin board (**Kafka**) instead of interrupting each other:

| Service | Real-world analogy | Its one job |
| --- | --- | --- |
| **API Gateway** | The front desk / receptionist | Takes every request, routes it to the right specialist |
| **Post Service** | The librarian | Stores & fetches posts |
| **Comment Service** | The archivist of threaded replies | Stores the comment tree, fetches subtrees |
| **Vote Service** | The ballot box | Records "one vote per person," emits an event |
| **Feed Service** | The newspaper editor | Builds & serves the ranked lists people actually read |
| **Search / Moderation** | Search desk / the janitors | Find things; remove bad things |

**Kafka = the shared bulletin board.** When something happens ("a vote was cast", "a post was created"), the service that did it **pins a note** on Kafka and immediately moves on. Other services (aggregators, ranking jobs, search indexers) read those notes **later, at their own pace.** Nobody waits on anybody.

#### Q: What is CQRS and why split "read" from "write"?

CQRS = **Command Query Responsibility Segregation** — a fancy way of saying **"the path for changing data is separate from the path for reading data."**

- **Write path (Command):** you cast a vote / make a post. Goes to the "source of truth" databases. Careful, correct, but not built for millions of readers.
- **Read path (Query):** you scroll your feed. Served from **precomputed, cached lists** (Redis), never by re-querying and re-ranking the raw tables.

Analogy: a restaurant **kitchen** (write side: raw ingredients, cooking) is separate from the **buffet counter** (read side: ready-to-grab dishes). Diners take from the buffet (fast); they never walk into the kitchen and cook their own plate. The ranking job is the chef **restocking the buffet on a schedule** — see §6.

---

## 5. Feed Generation (fan-out)

Two feeds: a **subreddit feed** and a **personalized home feed** (merge of subscribed subs).

| Approach | How | Trade-off |
| --- | --- | --- |
| **Pull (fan-out on read)** | At read, fetch top posts from each subscribed sub, merge + rank | Cheap writes; heavier reads |
| **Push (fan-out on write)** | On new post, push id into subscribers' feed caches | Fast reads; expensive for huge subs |
| **Hybrid** ✅ | Precompute per-sub hot lists; home feed **merges** subscribed subs' cached lists | Best of both |

> **Reddit-style (the key insight):** unlike Twitter (per-user push), Reddit precomputes a **per-subreddit ranked hot list** that is **shared by all subscribers** — so a huge sub is computed **once**, not fanned out to millions. The home feed **merges** the user's subscribed subs' cached lists + caches the result. This sidesteps the celebrity/huge-sub write-amplification problem. (See [Fan-Out / Fan-In](../concepts/fan-out-fan-in.md).)

### Plain-English: "fan-out" and why the Reddit trick is clever

**"Fan-out"** just means: when one thing happens, how many places do we have to go update? Two extremes:

- **Push (fan-out on write):** the moment someone posts in `r/soccer`, immediately shove that post's id into the feed of **every one of the 30M subscribers.** Reads are then instant (your feed is pre-built), but the *write* is monstrous — one post = 30M list-inserts.
- **Pull (fan-out on read):** store nothing pre-built. When *you* open your home feed, go ask each of your subscribed subs "what's your top posts?" and merge them right then. Writes are trivial, but every read does a pile of work, and popular subs get hammered by every reader.

**The Reddit insight — compute the sub's list ONCE and share it.** A subreddit's "hot" list is **the same for everybody** (unlike a Twitter timeline, which is unique per person). So:

```
r/soccer's hot list  ──►  computed ONCE  ──►  cached  ──►  read by ALL 30M subscribers
```

Analogy: `r/soccer` is a **newspaper**. The editor lays out the "top stories" page **once** and prints identical copies. It would be insane to hand-write a personalized front page for each of 30M readers (that's push). It's also wasteful to make each reader re-rank all the stories themselves at the newsstand (that's pull). Print once, share the copy.

Your **home feed** is then just a **"custom bundle of newspapers"**: grab the top slice of each subreddit you follow, merge-and-rank those slices, and cache the little merged result for you.

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

#### Q: Isn't a huge subreddit (30M subs) the same "celebrity problem" as Twitter?

**No — and that's the whole point.** On Twitter, a celebrity's tweet must fan out to each follower's *personal* timeline (millions of writes) because every timeline is different. On Reddit, `r/soccer`'s hot list is **one shared list**; growing from 3M to 30M subscribers doesn't add any extra ranking work — it's still computed **once**. More subscribers just means more *readers of the same cached list* (and reads are cheap). Write-amplification vanishes.

#### Q: Why "hybrid"? Which part is push and which is pull?

- **Push-ish:** each subreddit's hot list is **precomputed** (pushed into Redis by a background job) — so it's ready before you ask.
- **Pull-ish:** your **home feed** is **merged on read** from those cached lists (then cached briefly).

You get fast reads (the heavy ranking is already done per sub) without the insane per-user write fan-out. Best of both.

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

### Plain-English: decoding the "hot" formula

The scary formula is really just **two ideas added together**: "how liked is it?" **plus** "how fresh is it?"

```
hot = log10(max(|ups − downs|, 1)) × sign(ups − downs)   +   (epoch_seconds − 1134028003) / 45000
      └────────────── POPULARITY part ──────────────┘       └──────────── FRESHNESS part ─────────────┘
```

**Part 1 — Popularity, but with diminishing returns (`log10`).**

`log10` compresses big numbers. Think of it like **volume knobs**, not a straight line:

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

#### Q: "Top" vs "Hot" vs "Best" — when do I use which?

| Sort | Plain meaning | Analogy |
| --- | --- | --- |
| **Hot** | popular *right now* (votes + freshness) | the "trending" shelf |
| **New** | newest first, ignore votes | a live feed, chronological |
| **Top** | most votes in a time window (day/week/all) | "hall of fame for this week" |
| **Best** (comments) | high approval, *adjusted for how few votes it has* | a movie rated 5/5 by 3 people vs 4.6/5 by 10,000 |

#### Q: What is the "Wilson score" for comments, in plain terms?

Raw ratio is misleading with few votes. A comment with **5 upvotes, 0 downvotes** is "100% liked" — but that's only 5 people. A comment with **900 up, 100 down** ("90%") is clearly more trustworthy. The **Wilson score** asks: *"given this sample size, what's the pessimistic-but-fair estimate of the true approval?"* Few votes → it stays humble (pulls the score down); many votes → it trusts the ratio.

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

### Plain-English: why we never count votes on the read path

Naive idea: to show a post's score, `SELECT COUNT(*) FROM votes WHERE target = post AND value = +1`. On a viral post with **2 million votes**, that counts 2M rows — **every single time anyone loads the page**. Thousands of people are loading it at once. The database dies.

**The fix — three separated steps** (this is the read-heavy pattern again):

1. **On vote (write):** just record *this one person's* vote and fire off a note. Don't touch any total.
2. **In the background (async):** a worker reads the notes and nudges a stored **counter** up or down.
3. **On read:** just read the **already-stored counter.** No counting, ever.

Analogy: an election. You don't recount all ballots every time a reporter asks for the tally. Each vote drops in the box (step 1); counters tally in the back room (step 2); reporters read the posted number (step 3).

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

    // pin a note on Kafka and RETURN immediately. We do NOT update counts here.
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

#### Q: "Adjust by delta" — why not just recompute the total?

Because recomputing means counting millions of rows again. **Delta = only the change caused by THIS vote.** A new upvote is `+1`; flipping your upvote to a downvote is `-2` (you remove one up *and* add one down). We only ever *nudge* the running counter, like a scoreboard operator pressing "+1", never re-tallying the crowd.

#### Q: Isn't the displayed count now slightly wrong / delayed?

Yes — and **that's acceptable here** (it's not money). The background worker might be a few seconds behind, so a count could read 4,981 when it's "really" 4,987. Nobody cares if a Reddit score is off by a handful for a moment (**eventual consistency**). Reddit even *deliberately* fuzzes displayed counts (**vote fuzzing**) to confuse manipulation bots — so exactness was never the goal. Contrast this with ad-click *billing*, which must reconcile to the exact number.

#### Q: What stops me from upvoting the same post 1000 times?

The `PRIMARY KEY (user_id, target_type, target_id)` — **one row per (person, thing).** Voting again just **overwrites your one row** (an upsert), it never inserts a second. So your influence on any post is capped at exactly one vote, whatever you click.

---

## 8. Nested Comments (tree modeling)

Comments form a **tree** (replies to replies, arbitrarily deep). Storage options:

| Model | How | Trade-off |
| --- | --- | --- |
| **Adjacency list** (`parent_id`) | Each row points to its parent | Simple; fetching a subtree needs recursion/CTE (N queries or a recursive query) |
| **Materialized path** (`path='1/5/9'`) ✅ | Store the full path from root | Fetch a whole subtree by **prefix match** (`WHERE path LIKE '1/5/%'`); easy ordering; path can get long |
| **Closure table** | Row per ancestor-descendant pair | Flexible ancestor/descendant queries; more storage/writes |

- **Load pattern:** fetch top-level comments + a few levels ranked by **best**; **lazy-load** deeper threads ("load more replies") — never load a 10k-comment tree at once.
- **Rank siblings** by best/top/new (Wilson for best).
- Comment counts + scores aggregated async like votes.

### Plain-English: storing a tree in a flat table

A comment thread is a **family tree**: a top comment, its replies, replies-to-replies, arbitrarily deep. But a SQL table is **flat rows**. How do we fit a tree into flat rows? Three schemes:

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

Simple (just a `parent_id` pointer). Problem: to fetch the whole subtree under #1 you must walk **level by level** — "who are #1's children? → #5, #6. Who are #5's children? → #9…" That's many queries (or a fancy recursive query). Like asking "list all of Grandma's descendants" when everyone only knows their *own* parent.

**Option B — Materialized path: each comment stores its FULL address (the winner ✅).**

```
comment_id | path    | body
1          | 1       | Great recipe!
5          | 1/5     | Agreed, tried it
9          | 1/5/9   | Me too, added garlic
6          | 1/6     | Too salty for me
```

The `path` is like a **file-system folder path** (`/1/5/9`) or a book's section number (`3.2.1`). To get everything under comment #1, you don't recurse — you just do a **prefix match**:

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

#### Q: Why "lazy-load" — why not just fetch the whole tree?

A popular post can have **50,000 comments** nested 20 levels deep. Loading and rendering all of it would be huge and slow, and you'd never read most of it. So Reddit loads only the **top-level comments + a couple levels**, each collapsed with a **"load more replies (137)"** button. You fetch deeper branches **only when a user clicks** — like expanding folders in a file explorer one at a time instead of opening every folder at once.

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

#### Q: How are sibling comments ordered, and what happens when one is deleted?

- **Siblings** (comments at the same level) are ranked by **best** (Wilson score, §6), or top/new if you switch sorts.
- **Deleting** a comment that has replies uses a **tombstone**: the body becomes `[deleted]` but the **row (and its `path`) stays**, so the replies underneath don't become orphans and the tree structure survives. Like whiting-out a name on a family tree without erasing the branch.

---

## 9. Data Model (all tables)

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

### Plain-English: reading the schema like a story

Each table is just a **spreadsheet**; the design story is *which columns are precomputed and which indexes make reads fast.*

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

#### Q: What are those `CREATE INDEX` lines actually doing?

```sql
CREATE INDEX idx_posts_sub_hot ON posts(subreddit_id, hot_rank DESC);
```

An index is like the **tab dividers in a binder** — it keeps rows pre-sorted so the DB can jump straight to them instead of scanning everything. This one keeps each subreddit's posts **pre-sorted by `hot_rank`**, so "give me r/soccer's hottest posts" is an instant top-N read, not a sort-the-whole-sub operation. The `idx_comments_post_path` index does the same for the "fetch subtree by path prefix" query in §8.

#### Q: Why is some data in SQL but feeds/scores live in Redis?

Different tools for different jobs:

- **SQL (source of truth):** durable, correct, the real record of posts/comments/votes.
- **Redis (fast serving layer):** the precomputed feeds (`feed:sub:{id}:hot` as a **sorted set**) and cached `score:{id}`. It's a "buffet counter" (§4) — rebuilt from SQL if lost, so it can be fast and slightly lossy.

A Redis **sorted set** is perfect for a hot list: it keeps ids automatically ordered by a score (the `hot_rank`), so "top 100 posts" is a single fast range read.

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

## 13. Design Patterns (that can be used)

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

## 14. Scaling & Failure

- **Read path** = cache/precomputed feeds (Redis); DB rarely hit for feed reads.
- **Vote firehose** → Kafka → async counter aggregation; approximate cached counts.
- **Per-sub hot lists** computed **once** and shared by all subscribers (not per-user fan-out) → huge subs are cheap.
- **Home feed** = merge subscribed subs' cached lists + cache the merged result (short TTL).
- **Comment trees** via materialized path; lazy-load deep threads.
- **Partition** posts/comments by subreddit or time; archive cold content; shard votes by target.
- **Eventual consistency:** vote counts / feed freshness lag briefly — acceptable.

### Plain-English: where caching lives and why it saves us

Caching = **keep the answer close and pre-made so you don't redo work.** In this system there are a few layers of "pre-made answers," each avoiding an expensive operation:

| Cache | What it stores | Expensive thing it avoids |
| --- | --- | --- |
| `feed:sub:{id}:hot` | each sub's ranked post ids (Redis sorted set) | re-ranking a whole subreddit per reader |
| `feed:home:{userId}` | your merged home feed (short TTL) | re-merging your subs on every scroll |
| `score:{targetId}` | a post/comment's current score | counting millions of `votes` rows |

Analogy: a coffee shop with a **pastry case up front** (cache) vs baking each croissant to order (recompute). 99% of customers grab from the case instantly; the oven (database) only runs on a schedule in the back.

#### Q: If feeds are cached, how do new posts/votes ever show up?

Two mechanisms refresh the pre-made answers:

1. **TTL (time-to-live):** the home feed cache expires after a few minutes, so it gets rebuilt periodically with fresh data. A little staleness is fine.
2. **Background ranking jobs:** every interval, a job recomputes each sub's `hot_rank` from the latest counters and **overwrites** `feed:sub:{id}:hot`. So new/rising posts flow into the cached lists on the next cycle.

You accept that a brand-new post or a just-cast vote might take a few seconds to a minute to appear — **eventual consistency**, the deliberate trade for a read path that scales.

#### Q: What if a super-popular post causes a "cache miss stampede"?

If a hot cached value expires and 10,000 readers hit it at the same instant, they might **all** try to rebuild it at once and hammer the DB (a "thundering herd"). Guards: rebuild the value with a **single lock** (only one worker recomputes, others wait for it), **stagger TTLs**, or **refresh-ahead** (recompute just before expiry). For Reddit's hottest posts, the score/feed is kept warm so the DB is essentially never hit per view.

---

## 15. Interview Cheat Sheet

> **"How do you build the home feed?"**
> "Precompute a **shared per-subreddit hot list** (computed once, not fanned out to each subscriber), then **merge** the user's subscribed subs' cached lists into a home feed and cache it (hybrid). This avoids the huge-sub write-amplification problem — a 30M-subscriber sub is ranked once."

> **"How is 'hot' ranking computed?"**
> "`log10(votes) × sign + time/45000` — log-scaled votes (diminishing returns) plus an additive time term so fresh posts surface (a post needs ~10× votes to match one 12.5h older). Computed periodically and cached, never per request. Top = score in a window; Best comments = Wilson lower bound."

> **"How do you handle massive vote volume?"**
> "One vote per (user, target) with a unique constraint; emit to Kafka; async aggregator updates counters by delta and a periodic job recomputes rankings. Approximate cached counts (with vote fuzzing) — exact recount on read doesn't scale."

> **"How are nested comments stored?"**
> "Materialized path (`'1/5/9'`) → fetch a subtree by prefix, lazy-load deeper replies, rank siblings by best (Wilson). Adjacency list is simpler but needs recursive queries."

---

## 16. Final Takeaways

- **Read-heavy** → precomputed, cached feeds; DB rarely touched on reads (**CQRS + materialized views**).
- **Shared per-subreddit hot lists** (computed once) + merged home feed = no huge-sub write amplification.
- **Hot ranking** = log-scaled votes + additive time decay; **Best** = Wilson score; computed periodically + cached.
- **Voting** = one per user/target, async delta aggregation, approximate cached counts (+ fuzzing).
- **Comments** = tree via materialized path, lazy-loaded, ranked by best.
- Patterns: Strategy, Composite, Observer/Producer-Consumer, CQRS/Materialized View.

### Related notes

- [Quora — System Design](quora-system-design.md) · [Twitter / News Feed](twitter-news-feed-system-design.md) — sibling feed/ranking platforms
- [Fan-Out / Fan-In](../concepts/fan-out-fan-in.md) · [Caching Strategies](../concepts/caching-strategies.md) · [Apache Kafka](../concepts/kafka.md) · [Database Indexing](../concepts/database-indexing.md)
