# Facebook — System Design (Social Network)

> **Core challenge:** model a huge **bidirectional social graph** (friends), generate a **ranked News Feed** from friends'/groups'/pages' activity, and support posts, reactions, comments, and notifications — read-heavy, at billions of users, with **privacy** enforced on every read. The distinctive parts vs Twitter are the **mutual friend graph** and **rich ML feed ranking**.

> **How to read this doc:** each section has the dense interview summary first, then a **deep dive** (annotated Java and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand.

---

## Contents

- [1. Mental Model & vs Twitter/Instagram](#1-mental-model--vs-twitterinstagram)
- [2. Requirements](#2-requirements)
- [3. Capacity Estimation](#3-capacity-estimation)
- [4. Architecture](#4-architecture)
- [5. The Social Graph (TAO)](#5-the-social-graph-tao)
- [6. News Feed Generation](#6-news-feed-generation)
- [7. Feed Ranking Pipeline](#7-feed-ranking-pipeline)
- [8. Privacy / Visibility](#8-privacy--visibility)
- [9. Posts, Reactions & Comments](#9-posts-reactions--comments)
- [10. Data Model (all tables)](#10-data-model-all-tables)
- [11. API Design](#11-api-design)
- [12. Sequences](#12-sequences)
- [13. Consistency & Edge Cases](#13-consistency--edge-cases)
- [14. Design Patterns (that can be used)](#14-design-patterns-that-can-be-used)
- [15. Scaling & Failure](#15-scaling--failure)
- [16. Interview Cheat Sheet](#16-interview-cheat-sheet)
- [17. Final Takeaways](#17-final-takeaways)

---

## 1. Mental Model & vs Twitter/Instagram

```
Friends (mutual) → post activity → your News Feed = ranked merge of friends' + groups' + pages' posts
```

| | **Facebook** | **Twitter** | **Instagram** |
| --- | --- | --- | --- |
| Graph | **Bidirectional** (friend request → accept) | Unidirectional follow | Unidirectional follow |
| Content | Mixed (text/photo/video/links/events) | Short text | Photo/video-first |
| Feed | Heavily **ML-ranked** | Chrono or ranked | Ranked + Stories |
| Extra | Groups, Pages, Messenger, Events | Retweets | Stories, Reels, Explore |

Read-heavy; the two hard parts are the **friend graph at scale** and **feed generation + ranking**, plus **privacy on every read**.

### What are we actually building?

Picture Facebook the way a normal user experiences it. You have a list of **friends** (people you both agreed to connect with). Each friend **posts** stuff — a photo, a status, a link. When you open the app, you see a **News Feed**: a scrolling list mixing your friends' posts, posts from **groups** you joined, and **pages** you follow, sorted "best first" instead of purely newest-first.

So there are really only three big nouns to keep in your head:

- **The friend graph** — who is connected to whom. Think of a giant web where every person is a dot and every friendship is a line joining two dots.
- **Posts** — the content people create.
- **The feed** — the personalized, ranked merge of posts from everyone you're connected to.

Everything hard about this system comes from **scale** (billions of people, trillions of friendship lines) and from the fact that people **read** far more than they **write** (you scroll for an hour but post once a week).

### Why is the friend graph "bidirectional" and why does it matter?

Twitter/Instagram follows are **one-way**: you can follow a celebrity without them following you back. Facebook friendship is **two-way**: both people must agree (send request → accept), and once you're friends, you're friends *to each other*.

```
Twitter:   Alice ───follows──►  Celebrity        (one direction, no permission needed)

Facebook:  Alice ◄──friends──►  Bob              (two directions, both agreed)
```

Why this matters for the design: with a two-way friendship, "Alice's friends" and "the people who see Alice's posts" are (mostly) the **same set**. That symmetry lets us store and read the relationship efficiently, but it also means every friendship is really **two edges** to keep in sync (Alice→Bob *and* Bob→Alice).

#### Q: Isn't a "graph" just a fancy word? Do we literally store dots and lines?

Pretty much, yes. A **graph** = **nodes** (the dots: users, posts, pages) + **edges** (the lines: "is friends with", "authored", "likes"). We don't draw pictures; we store it as **adjacency lists** — for each user, the list of ids they're connected to:

```
friends[Alice] = [Bob, Carol, Dave, ...]     // Alice's ~300 friends
friends[Bob]   = [Alice, Eve, ...]           // Bob's list (contains Alice back)
```

#### Q: Why compare against Twitter/Instagram so much?

Because they share ~80% of the design (fan-out feeds, caching, the celebrity problem), so the interview is really about the **20% that's different**: Facebook's **mutual** (two-way) graph and its **heavy ML ranking** that blends friends + groups + pages. Knowing the shared parts lets you spend your breath on the distinctive parts.

---

## 2. Requirements

**Functional**
- Send/accept **friend requests** (bidirectional); unfriend/block.
- Create **posts** (text/photo/video/link) with **privacy scope**; **react**, **comment**, **share**.
- **News Feed** ranked from friends/groups/pages.
- Groups, Pages, notifications, search. (Messenger = separate chat system.)

**Non-functional**
- **Read-heavy** (reads ≫ writes); low-latency feed; huge scale (billions); **eventual consistency OK** for feed/counts; **privacy is strict** (never leak a restricted post).

---

## 3. Capacity Estimation

```
Users ~ 3B, DAU ~ 2B · avg ~300 friends
Posts ~ hundreds of millions/day; reactions/comments ~ billions/day
Feed reads ~ millions/sec at peak → precompute + cache (never rebuild per request)
Fan-out: 1 post × ~300 friends = ~300 feed writes; a page with 100M followers = the celebrity problem
Graph: 3B users × ~300 edges ≈ ~1 trillion edges → sharded graph store + heavy caching
```

> Two pressures: a **trillion-edge graph** (read on every feed build → TAO-style cache) and **millions of feed reads/sec** (precompute + rank + cache).

### Where do these numbers come from?

The point of this section isn't the exact digits — it's to **prove to yourself that the naive design is impossible**, which forces the real design. Let's walk each line like a back-of-napkin sketch.

- **~3B users, ~300 friends each.** Multiply: `3,000,000,000 × 300 ≈ 1 trillion friendship lines`. That's the number that says "you cannot keep the friend graph on one machine" → it must be **sharded** (split across thousands of machines) and **cached**.
- **Feed reads ~ millions/sec.** Every time anyone opens the app or scrolls, that's a feed read. Two billion daily users scrolling many times a day = millions of reads *every second*. If each read tried to freshly gather + rank posts from 300 friends, you'd do billions of graph lookups per second. Impossible → so we **precompute** feeds ahead of time and **cache** the result.
- **Fan-out math.** When you post once, that post has to reach ~300 friends' feeds → **one write becomes ~300 writes**. Usually fine. But a **page with 100M followers** posting once = 100M feed writes for a single action. That explosion is the **celebrity/high-degree problem** (see §6).

The same operation ("deliver a post to followers") has wildly different cost depending on recipient count: fanning out to 300 friends is cheap; fanning out to 100 million followers is not.

#### Q: What does "read-heavy" really mean, and why do we keep repeating it?

It means **reads ≫ writes** — people consume way more than they produce. You might scroll past 500 posts today (500 reads) but write only 1 post (1 write). When a system is read-heavy, the winning strategy is: **do the expensive work once at write time, save the result, and make reads cheap.** That single idea (precompute + cache) explains most of this doc.

#### Q: A trillion edges sounds huge — is that data or just pointers?

Just pointers (ids). An edge is tiny — basically "user A is friends with user B", a couple of numbers. But a *trillion* tiny things is still enormous in total, and more importantly you **read** these edges constantly (every feed build needs "who are X's friends?"). It's the **read frequency**, not the storage size, that forces the TAO-style cache (§5).

---

## 4. Architecture

```
Client → API Gateway
  ├── Graph Service (friendships, suggestions)   → sharded graph store + TAO-style cache
  ├── Post Service (create/read posts)           → sharded store + cache
  ├── Fan-out Service (workers)                  → write friends' feed caches (Kafka)
  ├── Feed Service (build/rank/serve)            → Redis feed cache + ML ranking
  ├── Privacy/ACL Service                        → visibility checks on read
  ├── Comment/Reaction Service                   → async counters
  ├── Notification + Media (blob/CDN)
  └── Search (Elasticsearch)
             │
          Kafka (POST_CREATED, REACTION, COMMENT → fan-out, ranking features, index, notifications)
```

### Why so many separate services?

Each service does **one thing well** and hands work to the others. If one service (say, the search indexer) is slow or breaks, the others (feed, posts) keep working.

Walking the boxes in plain terms:

- **API Gateway** — every request from your phone hits it first; it routes you to the right service and checks you're logged in.
- **Graph Service** — answers "who are Alice's friends?" (§5).
- **Post Service** — stores and fetches the actual content of posts.
- **Fan-out Service** — when you post, it copies the post id into all your friends' feeds (§6).
- **Feed Service** — assembles + ranks + serves your News Feed (§6, §7).
- **Privacy/ACL Service** — checks "is this viewer allowed to see this post?" on every read (§8).
- **Comment/Reaction Service** — handles likes/comments, and counts them **asynchronously** (later, in the background) because there are billions.
- **Notification / Media / Search** — pings, photo/video storage on a CDN, and the search index.

#### Q: What is Kafka doing sitting in the middle?

**Kafka is a shared event log.** When something happens ("Alice posted", "Bob liked"), the service that noticed it just **publishes an event to Kafka and moves on** — it does *not* wait for fan-out, ranking, indexing, and notifications to finish. Other services (workers) consume those events and do their part on their own time.

```java
// Post Service just records the event and returns immediately — fast for the user.
void createPost(Post p) {
    postStore.save(p);                                   // 1. save the content
    kafka.publish("POST_CREATED", new PostEvent(p.id, p.authorId));  // 2. announce it
    // returns NOW. Fan-out, ranking-features, search-index, notifications
    // all happen later, triggered by that one event. This is "decoupling".
}
```

**Why bother?** (1) The user's post feels instant because we don't make them wait for 300 feed-writes. (2) If the search indexer is down, posting still works — the note waits on Kafka until search recovers. (3) One event ("POST_CREATED") can trigger **many** independent reactions (fan-out, features, index, notify) without the poster knowing about any of them. This is the **Observer / Pub-Sub** pattern (see §14).

#### Q: Why split "Graph", "Post", and "Feed" instead of one big app?

Because they scale and fail **independently**. The graph is read insanely often (every feed build), so it needs its own giant cache. Posts are write-then-read-many. Feeds are CPU-heavy (ranking). Separating them lets you throw hardware at exactly the bottleneck, and a bug in ranking can't take down friend requests.

---

## 5. The Social Graph (TAO)

The friend graph is the foundation — **~1 trillion edges**. Facebook's real system is **TAO** (a graph-aware cache over sharded MySQL).

| Concept | Detail |
| --- | --- |
| **Objects** | Nodes = users, posts, comments, pages (typed, with fields) |
| **Associations** | Edges = friendship, likes, authored, member-of (typed, bidirectional pairs) |
| **Storage** | Sharded MySQL (objects + assoc tables), sharded by id |
| **TAO cache** | A **read-through, write-through graph cache** in front of MySQL — optimized for `assoc_get`, `assoc_count`, `assoc_range` (e.g. "friends of X", "likes on post Y") |
| **Sharding** | By user/object id; a user's friend list co-located |

```
Common queries (served from TAO cache, not MySQL):
  friends(X)              → assoc_range(X, 'friend')
  mutual(X, Y)            → set intersection of friends(X), friends(Y)
  friend suggestions      → friends-of-friends (2-hop) ranked
  is X friends with Y?    → assoc_get(X, 'friend', Y)
```

- **Read-heavy graph** → the cache absorbs it; MySQL is the durable backing.
- **Friendship is symmetric** → store both directions (or normalize + query both) so "friends of X" is one shard read.

> **Interview line:** "sharded adjacency lists (objects + typed associations) behind a **read-through graph cache (TAO-style)** since friend lists are read on every feed build; sharded by id; mutuals = set intersection; suggestions = 2-hop friends-of-friends."

### Objects and associations (the whole model in one idea)

TAO looks intimidating but it's built from just **two primitives**:

- **Objects** = the **things** (nouns). A user, a post, a comment, a page. Each has an id and some fields.
- **Associations** = the **relationships** (verbs) between two things. "Alice *is friends with* Bob." "Alice *authored* post 99." "Bob *likes* post 99."

Objects hold an entity's fields (a user's name, photo, birthday). Associations are the typed links between them: "these two are friends", "this person wrote that post". To answer almost any Facebook question, you either look up an object or follow its associations.

```java
// An OBJECT: a typed node with fields.
class GraphObject {
    long   id;
    String type;                 // "user", "post", "comment", "page"
    Map<String,Object> fields;   // name, body, created_at, ...
}

// An ASSOCIATION: a typed, directed edge from one object to another.
class Assoc {
    long   from;                 // e.g. Alice's id
    String type;                 // "friend", "authored", "likes", "member_of"
    long   to;                   // e.g. Bob's id
    long   createdAt;            // edges are time-ordered (newest first)
}
```

Notice friendship is stored as **two** associations so it reads fast in both directions:

```java
// Making Alice and Bob friends = writing BOTH edges (that's what "bidirectional" costs).
graph.addAssoc(alice, "friend", bob);
graph.addAssoc(bob,   "friend", alice);
```

### The four queries TAO is built to answer

Almost every graph question is one of four shapes. TAO exposes exactly these, and the cache makes them fast:

```java
// 1. assoc_get — "Is Alice friends with Bob?"  (does this specific edge exist?)
boolean areFriends = graph.assocGet(alice, "friend", bob) != null;

// 2. assoc_range — "Who are Alice's friends?" (list the edges, newest first, paginated)
List<Assoc> friends = graph.assocRange(alice, "friend", /*offset*/0, /*limit*/50);

// 3. assoc_count — "How many friends does Alice have?" (just the number, no list)
long friendCount = graph.assocCount(alice, "friend");

// 4. two-hop — "People you may know" = friends of my friends that I'm not friends with yet
Set<Long> suggestions = new HashSet<>();
for (Assoc f : graph.assocRange(alice, "friend", 0, 300))       // my friends
    for (Assoc ff : graph.assocRange(f.to, "friend", 0, 300))   // their friends
        if (ff.to != alice.id && !areFriendsWith(alice, ff.to))
            suggestions.add(ff.to);                             // a friend-of-a-friend
```

**Mutual friends** = the overlap between two friend lists:

```java
// mutual(Alice, Bob) = "which friends do we share?" = set intersection.
Set<Long> mutual = new HashSet<>(friendIds(alice));
mutual.retainAll(friendIds(bob));   // keep only ids present in BOTH lists
```

### What "read-through / write-through cache" means

The cache sits in front of MySQL (the durable store):

- **Read-through:** on a request, if the value is in the cache (hit), return it instantly. If not (miss), the cache fetches it from MySQL, **stores a copy**, and returns it. Callers never query MySQL directly.
- **Write-through:** on a change, the cache updates **both** its copy **and** MySQL together, so they never disagree.

```java
// READ-THROUGH: cache fetches from MySQL on a miss and remembers it.
List<Assoc> friendsOf(long user) {
    List<Assoc> cached = taoCache.get(user, "friend");
    if (cached != null) return cached;              // hit → fast
    List<Assoc> fromDb = mysql.loadAssocs(user, "friend");  // miss → hit the DB
    taoCache.put(user, "friend", fromDb);           // remember for next time
    return fromDb;
}

// WRITE-THROUGH: update cache AND MySQL together on a change.
void addFriend(long a, long b) {
    mysql.insertAssoc(a, "friend", b);   // durable source of truth
    mysql.insertAssoc(b, "friend", a);   // both directions
    taoCache.invalidate(a, "friend");    // so next read reloads the fresh list
    taoCache.invalidate(b, "friend");
}
```

Why this is the entire point: friend lists are read on **every single feed build** for **billions** of users. MySQL alone would be crushed. The cache serves ~99% of those reads from memory; MySQL is just the durable backup that survives restarts.

#### Q: Why not use a "real" graph database like Neo4j?

Great instinct, but at Facebook's scale a single graph DB can't hold a trillion edges or take millions of reads/sec. TAO's trick is boring on purpose: **plain sharded MySQL** for durability (battle-tested, easy to operate) **+ a huge custom cache** for speed. You give up fancy multi-hop graph traversals you don't need, and you get scale and reliability you do need.

#### Q: What does "sharded by id" mean, and why co-locate a user's friends?

**Sharding** = splitting the data across many machines so no single one holds it all. The rule here is usually `shard = hash(user_id) % numShards`. Because we shard by the **user's** id, **all of Alice's friendship edges live on the same shard**. So "who are Alice's friends?" is **one machine, one lookup** — not a scatter-gather across thousands of machines.

```
shard(Alice) → machine 7 → holds Alice's object + all of Alice's "friend" edges
shard(Bob)   → machine 2 → holds Bob's object + all of Bob's "friend" edges
```

#### Q: If friendship is two edges, what happens on unfriend?

You delete **both** edges (Alice→Bob and Bob→Alice) and invalidate both cached lists. This is also why "make/break friendship" is one of the few places we want **strong-ish consistency** — you don't want a half-friendship where Alice sees Bob as a friend but Bob doesn't see Alice.

---

## 6. News Feed Generation

Same **fan-out** trade-off as Twitter (see [Fan-Out / Fan-In](../concepts/fan-out-fan-in.md)), but merging **friends + groups + pages** and heavily **ranked**.

| Model | On post | On read | Trade-off |
| --- | --- | --- | --- |
| **Push (fan-out on write)** | Push post id into friends' feed caches | Read precomputed feed (fast) | Write amplification for high-degree users/pages |
| **Pull (fan-out on read)** | Store the post | Gather + rank friends' recent posts at read | Cheap write; heavy read |
| **Hybrid** ✅ | Push for normal users; **pull for high-degree pages/celebrities** | Merge precomputed + pulled → **rank** | Balances both |

```
Build feed:
  candidates = recent posts from friends + joined groups + followed pages (+ some recs/ads)
  rank by ML relevance (see §7)
  cache the ranked feed (post IDs) per user; hydrate content on read
```

- Store **post ids** in the feed cache; hydrate content from a shared post cache (a viral post cached once).
- Because ranking is heavy, feeds are often **pull + rank at read with cached candidates**, or precomputed candidate sets re-ranked at read.

### Fan-out on write vs fan-out on read (the central trade-off)

This is *the* concept for feeds. The question: **when Alice posts, when do we do the work of getting it into her 300 friends' feeds — right now (at write) or later when each friend opens the app (at read)?**

- **Push (fan-out on write)** = the moment Alice posts, copy the post id into all 300 friends' feeds. When a friend opens the app it's already there — instant. But that's 300 writes up front, even for friends who never open the app today.
- **Pull (fan-out on read)** = store **one** copy of the post and nothing else. When a friend opens the app, gather the latest posts from everyone they follow, right then. Cheap to publish; expensive every time someone reads.

```
PUSH (fan-out on WRITE):   Alice posts ──► copy id into Bob's feed, Carol's feed, ... (300 writes NOW)
                           Bob opens app ──► his feed is already built → FAST read

PULL (fan-out on READ):    Alice posts ──► store the one post, do nothing else (1 write)
                           Bob opens app ──► go fetch recent posts from ALL Bob's friends → build feed NOW (heavy read)
```

**Push code — do the work at write time:**

```java
// Runs when POST_CREATED fires. Copies the post id into every friend's feed cache.
void fanOutOnWrite(long postId, long authorId) {
    List<Long> friends = graph.friendIds(authorId);      // ~300 ids from TAO
    for (long friend : friends) {
        // feed:{friend} is a Redis sorted set (score = timestamp) of post ids.
        redis.zadd("feed:" + friend, System.currentTimeMillis(), postId);
    }
    // 1 post → ~300 tiny writes. The friend's read later is trivial.
}
```

**Pull code — do the work at read time:**

```java
// Runs when Bob opens the app. Gathers fresh, builds on the spot.
List<Long> fanOutOnRead(long viewer) {
    List<Long> feed = new ArrayList<>();
    for (long friend : graph.friendIds(viewer))          // for each of ~300 friends
        feed.addAll(postStore.recentPostIds(friend, 20)); // grab their latest posts
    feed.sort(byRecencyOrScore());                        // merge + order
    return feed;                                          // heavy, but always fresh
}
```

| | Push (write) | Pull (read) |
| --- | --- | --- |
| Work happens | When you **post** | When each friend **opens the app** |
| Read speed | **Fast** (already built) | **Slow** (build every time) |
| Write cost | **High** (1 post → N feed writes) | **Low** (store one post) |
| Wasteful when | Friends rarely log in | Content re-fetched on every scroll |
| Breaks on | **Celebrities** (N = 100M writes per post) | Very active users (rebuild huge feed constantly) |

### The celebrity problem and why hybrid wins

Push is great for a normal person (300 copies, no big deal). But a **page with 100 million followers** posting once would trigger **100 million feed writes** — a single button press overwhelming the fan-out pipeline. That's the **celebrity / high-degree problem**.

**The fix = hybrid.** Use push for **normal** accounts (most posts, small fan-out) and pull for a handful of **celebrities/huge pages** (skip the 100M writes; instead, mix their recent posts in *at read time* for the few thousand people currently online).

```java
void onPost(long postId, long authorId) {
    long followers = graph.assocCount(authorId, "friend")   // or follower count for a page
                   + graph.assocCount(authorId, "follows_page");
    if (followers > CELEBRITY_THRESHOLD) {                  // e.g. > 100k
        // DON'T fan out. Their post is fetched (pulled) when fans build their feed.
        return;
    }
    fanOutOnWrite(postId, authorId);                        // normal user → push
}

// At read time, blend both sources:
List<Long> buildFeed(long viewer) {
    List<Long> pushed = redis.zrange("feed:" + viewer, 0, 500);   // precomputed (from normal friends)
    List<Long> pulled = pullRecentFromCelebrities(viewer);        // fetched live (from big accounts)
    return rank(merge(pushed, pulled), viewer);                   // combine, then rank (§7)
}
```

So most posts are pre-delivered into feeds (push), but the few very high-degree accounts are not fanned out — their recent posts are pulled in at read time. The reader sees one combined feed; behind the scenes two methods were used.

#### Q: Why store only post **ids** in the feed, not the whole post?

Because a viral post would otherwise be **copied in full into millions of feeds** — massive duplication. Instead each feed holds lightweight **ids**, and the actual content lives once in a shared **post cache**. At read time we "**hydrate**" — swap ids for real content by one cache lookup per post. One viral post = **one** cached copy, referenced by millions of feeds.

```java
List<Post> hydrate(List<Long> postIds) {
    // one shared cache lookup per id; the viral post is cached ONCE, reused everywhere.
    return postCache.multiGet(postIds);
}
```

#### Q: What happens to my feed when I accept a *new* friend?

Two options (see §13): **backfill** their recent posts into your precomputed feed asynchronously, or just **rely on pull** to naturally include them next time you build the feed. Either way you don't need to rebuild everyone's feed — only the two people whose relationship changed.

#### Q: Is the feed strongly consistent — will a post show the instant it's made?

No, and it doesn't need to be. Feeds are **eventually consistent**: because fan-out happens asynchronously via Kafka, a brand-new post may take a few seconds to appear in friends' feeds. That's a fine trade for the huge scalability win. (Contrast: friendship *state* is one of the few things we keep strongly consistent.)

---

## 7. Feed Ranking Pipeline

Facebook's feed is **relevance-ranked** (historically "EdgeRank", now ML). Treat the model as a black box; know the **pipeline**:

```
1. Candidate generation → all eligible recent posts (friends + groups + pages), filtered by privacy
2. Feature extraction   → affinity (how close you are to the author), post type, recency,
                          engagement so far, your past behavior
3. Scoring (ML model)   → predict p(you engage) → a relevance score per candidate
4. Ranking + diversity  → sort by score, apply diversity/spacing rules, insert ads
5. Cache + paginate     → serve top-N; recompute periodically or at read with cached features
```

- Classic **EdgeRank** intuition: `score ≈ affinity × content_weight × time_decay`.
- Ranking runs on **candidate sets** (bounded), not the whole graph, so it's tractable.

### Why rank at all, and what the pipeline does

A purely **newest-first** feed sounds fair but is bad: your best friend's baby photo gets buried under 50 auto-posts from a page you barely follow. **Ranking** = "show the stuff you're most likely to care about near the top."

Ranking sorts posts by predicted relevance rather than arrival time. The **feature extraction** step computes signals about each post (who it's from, how recent, how you've engaged with similar content before); the **model** turns those signals into a predicted-engagement score used to order the feed.

Walk the 5 stages with a concrete example — you open the app and there are 800 eligible posts:

```
1. Candidate generation  → gather those ~800 posts (friends + groups + pages), DROP anything
                            you're not allowed to see (privacy filter runs FIRST — §8).
2. Feature extraction    → for each post, compute signals:
                             - affinity: how close are you to the author? (DMs, past likes)
                             - recency: how old is it?
                             - type: photo/video/text/link
                             - engagement: how many others already reacted?
                             - your history: do you usually engage with this author/topic?
3. Scoring (ML model)    → feed those signals to a model → p(you engage) = a number 0..1 per post
4. Ranking + diversity   → sort by that score, but avoid 5 posts from the same person in a row;
                            sprinkle in ads.
5. Cache + paginate      → serve the top ~20, remember the rest for the next scroll.
```

Simple, readable version of the classic EdgeRank intuition:

```java
// A transparent, non-ML score to build intuition (real Facebook uses a big ML model here).
double score(Post post, long viewer) {
    double affinity   = closeness(viewer, post.authorId);   // 0..1: how tight are you two?
    double weight     = typeWeight(post.type);              // photos/videos often weigh more
    double ageHours   = hoursSince(post.createdAt);
    double timeDecay  = 1.0 / (1.0 + ageHours);             // newer → closer to 1, older → shrinks
    return affinity * weight * timeDecay;                   // higher = show nearer the top
}

// Rank a bounded candidate set — NOT the whole graph.
List<Post> rank(List<Post> candidates, long viewer) {
    candidates.sort((a, b) -> Double.compare(score(b, viewer), score(a, viewer)));  // high → low
    return applyDiversityAndAds(candidates);                // no 5-in-a-row from one author; insert ads
}
```

#### Q: Do we run this expensive ML on all trillion edges / every post ever?

**No — that's the key trick.** Ranking runs only on a **bounded candidate set** (a few hundred to a couple thousand recent, eligible posts), never the whole graph. Candidate generation shrinks "everything" down to "the handful of things that could plausibly appear in your feed right now," and only *then* do we score. Cheap enough to do per feed build.

#### Q: Where does privacy fit in the pipeline?

**Step 1, before anything else.** Restricted posts are filtered out during **candidate generation**, so a post you're not allowed to see never even reaches the scoring model. You can't accidentally rank-and-show something the model never received (§8).

#### Q: "Affinity" — what is that in plain terms?

How **close** you are to the author, learned from behavior: do you message them, like their stuff, view their profile, get tagged together? High affinity → their posts float up. It's why your feed is full of your 10 closest people even though you have 300 friends.

---

## 8. Privacy / Visibility

Every post has a **privacy scope**; visibility is checked **on read** (never leak a restricted post).

| Scope | Who sees it |
| --- | --- |
| PUBLIC | Anyone |
| FRIENDS | Author's friends only |
| CUSTOM | Specific lists / except-people |
| ONLY_ME | Author |

```
canView(viewer, post):
  PUBLIC → yes
  FRIENDS → viewer ∈ friends(author)
  CUSTOM → check allow/deny lists
  + author hasn't blocked viewer
```

- Enforced at **feed build + post fetch**; a blocked user never sees your content.
- Privacy filtering happens **during candidate generation** so restricted posts never enter a feed.

### Privacy is checked on every read

Privacy isn't a one-time setting you trust forever — it's a **check performed on every single read** (feed build, direct link, search result), because your relationships change (you unfriend, you block) and the same post might be visible to one viewer and forbidden to another.

The four scopes, in plain words:

- **PUBLIC** — anyone on Earth. (A page's announcement.)
- **FRIENDS** — only people the author is friends with.
- **CUSTOM** — a specific allow-list ("close friends") or deny-list ("everyone except my boss").
- **ONLY_ME** — just the author (a private draft/diary).

```java
// Access check. Returns true only if THIS viewer may see THIS post, right now.
boolean canView(long viewer, Post post) {
    if (isBlocked(post.authorId, viewer)) return false;   // blocked people see nothing, always first

    switch (post.privacy) {
        case PUBLIC:  return true;
        case FRIENDS: return graph.areFriends(viewer, post.authorId);   // must be a current friend
        case CUSTOM:  return post.audience.allows(viewer);              // allow/deny list check
        case ONLY_ME: return viewer == post.authorId;
        default:      return false;                                     // fail CLOSED, never open
    }
}
```

### Why filter during candidate generation (not after ranking)

You filter **as early as possible** so a forbidden post never even enters the pipeline. If you filtered *after* ranking, a bug in the ranking/pagination code could leak a restricted post — the safest design is: it was never a candidate in the first place.

```java
List<Post> candidatesFor(long viewer) {
    return gatherRecentPosts(viewer).stream()
             .filter(p -> canView(viewer, p))   // privacy FIRST — restricted posts are dropped here
             .toList();                          // everything downstream (features, scoring) is already safe
}
```

#### Q: Isn't checking privacy on every read slow? Why not compute it once?

It feels wasteful, but it's the only correct choice, because visibility **depends on the current state of two people's relationship**, which changes. If Bob unfriends Alice at 3pm, every FRIENDS-only post of Alice's must vanish from Bob's next read at 3:01 — with no rebuild of stored data. Checking on read makes revocation **instant**. The friend-graph lookups it needs are exactly what TAO's cache makes cheap (§5), so in practice it's fast.

#### Q: What's the difference between "unfriend" and "block"?

- **Unfriend** — you're no longer friends, so FRIENDS-scoped posts stop being visible between you. But you can still see each other's PUBLIC posts.
- **Block** — a hard wall: the blocked person sees **none** of your content and effectively can't interact with you. That's why the `canView` check does the block test **first** — it overrides every scope.

#### Q: "Fail closed" — what does that mean?

If the code is ever unsure (unknown scope, missing data, an error), it defaults to **deny**, not allow. In privacy, a false "you can't see this" is a minor annoyance; a false "here you go" is a data leak. So the safe default is always **no**.

---

## 9. Posts, Reactions & Comments

- **Post** = author + type + content + **privacy scope**; media → blob/CDN (post stores a reference).
- **Reactions** (like/love/haha…) + **comments** → **high write volume** → **async counter aggregation**, cached approximate counts.
- **Comments** = tree (materialized path), lazy-loaded, ranked (top/newest).
- Shares create a new post referencing the original.

### Why counts are "async" and "approximate"

When a post goes viral, **millions of people hit Like within seconds**. If every Like did `UPDATE posts SET like_count = like_count + 1 WHERE post_id = 99`, all those writes fight over **one row** (the classic hot-row problem — same as a viral ad in a click-counter). It would melt.

Instead of every Like updating the shared counter immediately, reactions are recorded individually and their counts are aggregated in batches every few seconds. The displayed count is a second or two behind reality — and that's totally fine for a Like count.

So we **record the reaction** (durable, exact) but **update the displayed count in the background**:

```java
// FAST PATH: record that Bob reacted. One clean write, keyed by (user, post) so it's idempotent.
void react(long userId, long postId, String type) {
    reactionStore.upsert(userId, postId, type);        // exact record of WHO reacted (for un-like, etc.)
    kafka.publish("REACTION", new ReactionEvent(postId, type));  // announce it, return immediately
    // NOTE: we did NOT touch like_count here — no hot-row fight.
}

// BACKGROUND: a consumer batches events and bumps the cached count occasionally.
@KafkaListener(topics = "REACTION")
void aggregate(List<ReactionEvent> batch) {
    Map<Long,Long> deltas = new HashMap<>();
    for (var e : batch) deltas.merge(e.postId(), 1L, Long::sum);   // 5000 likes → one +5000
    deltas.forEach((postId, delta) ->
        countCache.incrBy("likes:" + postId, delta));              // update the cached count in bulk
}
```

The displayed number is **eventually consistent** — it might read "1,204,900" while the true value is "1,205,050" for a second. Nobody cares about ±150 on a million-like post, and we avoided the hot row. The **exact** truth still lives in `reactionStore` if we ever need to recount (e.g., you toggling your own like on/off must be exact).

### Comments as a tree (materialized path)

Comments have **replies**, replies have **replies** — that's a **tree**, not a flat list. The neat trick to store a tree in a plain SQL table is a **materialized path**: each comment stores the full ancestry as a string.

This works like hierarchical section numbering: `2.3.1` sits under `2.3`, which sits under `2`. The path *is* the position in the tree.

```
Post 99
├── c1  "Nice!"                        path = "c1"
│   └── c3  "I agree"                  path = "c1/c3"        (reply to c1)
│       └── c5 "me too"                path = "c1/c3/c5"     (reply to c3)
└── c2  "Where is this?"               path = "c2"
    └── c4  "Paris"                    path = "c2/c4"        (reply to c2)
```

```java
class Comment {
    long   commentId;
    long   postId;
    long   parentId;    // the comment this replies to (0 = top-level)
    String path;        // materialized path, e.g. "c1/c3" — encodes all ancestors
    long   authorId;
    String body;
}

// Fetch a whole thread in ORDER with a single indexed range scan on the path prefix:
//   SELECT * FROM comments WHERE post_id = 99 ORDER BY path;
// Rows come back already grouped by branch — no recursive queries needed.
```

- **Lazy-loaded:** a post with 50,000 comments doesn't load all of them — you fetch the top few, and "view more replies" pulls the next branch on demand.
- **Ranked:** top-level comments can be ordered by "top" (most liked) or "newest", just like the feed but smaller scale.

#### Q: If I share a post, is it copied?

No — a **share creates a new post that *references* the original** (stores its id). The original stays in one place; your share points at it. That way edits/deletes to the original are reflected, and you don't duplicate content (same spirit as storing post ids in feeds, §6).

#### Q: Why keep the exact `reactions` table if we also cache a count?

Two different jobs. The **count** is a fast display number (approximate, cached). The **`reactions` table** (keyed by `(user_id, post_id)`) is the exact record of *who* reacted — needed so you can **un-like** (toggle), so we don't double-count if you tap twice, and so we can recompute the true count if the cache is ever wrong.

---

## 10. Data Model (all tables)

```sql
CREATE TABLE users ( user_id BIGINT PRIMARY KEY, name TEXT, email VARCHAR(255) UNIQUE, created_at TIMESTAMP );

CREATE TABLE friendships (
    user_a BIGINT, user_b BIGINT,
    status VARCHAR(12) NOT NULL,          -- PENDING, ACCEPTED, BLOCKED
    requested_by BIGINT, created_at TIMESTAMP,
    PRIMARY KEY (user_a, user_b)          -- store both directions (or normalize a<b + query both)
);
CREATE INDEX idx_friend_user ON friendships(user_a) WHERE status='ACCEPTED';

CREATE TABLE posts (
    post_id BIGINT PRIMARY KEY,           -- Snowflake (time-sortable)
    author_id BIGINT NOT NULL, type VARCHAR(15), content TEXT, media_ref TEXT,
    privacy VARCHAR(15) DEFAULT 'FRIENDS', audience JSONB,   -- PUBLIC/FRIENDS/CUSTOM/ONLY_ME
    like_count INT DEFAULT 0, comment_count INT DEFAULT 0, share_count INT DEFAULT 0,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_posts_author ON posts(author_id, created_at DESC);

CREATE TABLE reactions ( user_id BIGINT, post_id BIGINT, type VARCHAR(10), created_at TIMESTAMP, PRIMARY KEY (user_id, post_id) );
CREATE TABLE comments (
    comment_id BIGINT PRIMARY KEY, post_id BIGINT, parent_id BIGINT, path TEXT,
    author_id BIGINT, body TEXT, created_at TIMESTAMP
);
CREATE INDEX idx_comments_post ON comments(post_id, path);

CREATE TABLE groups ( group_id BIGINT PRIMARY KEY, name TEXT, privacy VARCHAR(10) );
CREATE TABLE group_members ( group_id BIGINT, user_id BIGINT, role VARCHAR(10), PRIMARY KEY(group_id, user_id) );
CREATE TABLE pages ( page_id BIGINT PRIMARY KEY, name TEXT, owner_id BIGINT, follower_count BIGINT DEFAULT 0 );
CREATE TABLE page_follows ( page_id BIGINT, user_id BIGINT, PRIMARY KEY(page_id, user_id) );
CREATE TABLE blocks ( user_id BIGINT, blocked_id BIGINT, PRIMARY KEY(user_id, blocked_id) );
CREATE TABLE notifications ( notif_id BIGINT PRIMARY KEY, user_id BIGINT, type VARCHAR(30), data JSONB, is_read BOOLEAN DEFAULT FALSE, created_at TIMESTAMP );

-- Feeds precomputed → Redis: feed:{userId} = sorted set of post ids (+ cached features)
-- Media → blob store + CDN; search → Elasticsearch
```

> **Tables to consider:** users, friendships, posts, reactions, comments, groups, group_members, pages, page_follows, blocks, notifications, precomputed feeds (Redis), media_refs, privacy/ACL, search index. Chat = separate messaging system.

### Reading the schema table by table

Don't memorize columns — understand **why each table exists** and the one clever bit in each. Each table holds one kind of thing:

| Table | Purpose | The clever bit |
| --- | --- | --- |
| `users` | One card per person | `email UNIQUE` so no two accounts share an email |
| `friendships` | The friend graph, as rows | Stores **both directions** + a `status` (PENDING/ACCEPTED/BLOCKED) — one table models the whole friend-request lifecycle |
| `posts` | The content | Denormalized `like_count`/`comment_count` **cached on the row** so the feed doesn't recount; `privacy` + `audience` drive the visibility check (§8) |
| `reactions` | Who liked what | PK `(user_id, post_id)` → you can only react **once** per post (toggle) |
| `comments` | The comment trees | `path` = materialized path (§9) for cheap threaded reads |
| `blocks` | Hard walls | Checked first in `canView` |
| feeds in **Redis** | Precomputed News Feeds | Not SQL at all — a **sorted set** of post ids per user, ordered by score/time |

#### Q: Why is `friendship`'s primary key `(user_a, user_b)` and why store both directions?

The PK `(user_a, user_b)` guarantees you can't have two rows for the same pair (no duplicate friendship). Storing **both directions** (a row for a→b *and* b→a) means "who are Alice's friends?" is a single indexed lookup on `user_a = Alice`, no matter which person originally sent the request. The alternative — store one row with `a < b` and query both `user_a` and `user_b` — saves space but makes every read do an OR/union. At Facebook's read-heavy scale, we happily pay double storage to make the read trivial.

```sql
-- "Alice's accepted friends" — one clean indexed scan thanks to storing both directions:
SELECT user_b FROM friendships WHERE user_a = :alice AND status = 'ACCEPTED';
```

#### Q: Why are `like_count`/`comment_count` stored *on the post* instead of counted from the `reactions` table?

That's **denormalization** — deliberately keeping a redundant copy for speed. Counting `SELECT COUNT(*) FROM reactions WHERE post_id = 99` on a post with 2M likes, on every feed render, would be brutal. So we keep a running number right on the post row (updated async, §9) and read it for free. The exact `reactions` rows remain the source of truth if we need to fix the count.

#### Q: Why does the feed live in **Redis**, not a SQL table?

The feed is a **hot, throwaway, per-user list** read millions of times/sec and constantly reordered. That's exactly what an in-memory store like Redis is for. A **sorted set** (`ZADD feed:{userId} score postId`) keeps ids ordered by score/time and lets you grab "top 20" instantly. It's a **cache/materialized view** — if it's lost, we can rebuild it from posts + the graph.

```
feed:42  →  { post_991: 1699999999, post_985: 1699999881, ... }   // Redis sorted set, score = time/rank
```

#### Q: Why is `media_ref` just a string, not the image itself?

Databases are terrible at storing big binary blobs. The photo/video lives in **blob storage + CDN** (built for large files, served fast worldwide); the post row stores only a **reference** (a URL/key). Same principle as feeds storing ids: keep the heavy thing in one specialized place, point at it from everywhere else.

---

## 11. API Design

```
POST /v1/friend-requests { toUserId }   · POST /v1/friend-requests/{id}/accept · /decline
POST /v1/users/{id}/block
GET  /v1/feed?cursor=                     # ranked news feed (privacy-filtered)
POST /v1/posts { type, content, privacy, audience }
GET  /v1/posts/{id}/comments?cursor=      · POST /v1/posts/{id}/comments { parentId?, body }
POST /v1/posts/{id}/react { type }
GET  /v1/users/{id}/friends               · GET /v1/users/{id}/mutual/{otherId}
POST /v1/groups/{id}/join                 · GET /v1/notifications
```

---

## 12. Sequences

### Post → feed

```
Author  PostSvc  Kafka  Fan-outWorkers  GraphSvc  Redis(feeds)
  │ post  │        │          │             │          │
  ├──────►│ persist│          │             │          │
  │◄─ id ─┤─ POST_CREATED ───►│             │          │
  │       │        │          ├─ friends(author) (TAO) ►│
  │       │        │          ├─ filter by privacy, ZADD each friend's feed ►│
  │       │        │  (high-degree page → skip; pulled at read)
```

### Read feed (rank at read)

```
User → FeedSvc:
  candidates = pushed feed ids + pull recent posts of followed pages/high-degree
  privacy-filter → extract features → ML rank → hydrate content → paginate
```

---

## 13. Consistency & Edge Cases

| Case | Handling |
| --- | --- |
| Privacy leak | Filter by visibility during candidate generation + on fetch; blocked users excluded |
| High-degree page/celebrity | Skip push; pull at read (hybrid) |
| Friend accepted | Backfill recent posts into feed (or rely on pull) |
| Unfriend/block | Filter on read; rebuild feed async |
| Deleted post | Tombstone; skip on hydrate |
| Counter accuracy | Async aggregation → approximate cached counts |
| Feed freshness | Eventual (a post may appear a few seconds late) |

---

## 14. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Strategy** | Feed fan-out (push/pull/hybrid), ranking model | Swap per user/experiment |
| **Producer-Consumer** | Fan-out + counter aggregation (Kafka) | Absorb + parallelize |
| **Observer / Pub-Sub** | Post/reaction/comment events → feed, notifications, search | Decouple |
| **CQRS + Materialized View** | Precomputed ranked feed vs write model | Fast reads |
| **Graph adjacency + Cache-Aside (TAO)** | Friend graph reads | Edge queries at scale |
| **Composite** | Nested comment trees | Uniform tree ops |
| **Facade** | Feed service over graph + posts + privacy + rank + cache | Simple API |
| **Chain of Responsibility** | Privacy/ACL checks | Composable visibility rules |
| **Decorator** | Post rendering (privacy, tags, attachments) | Compose display |
| **State** | Friend-request lifecycle (PENDING→ACCEPTED/BLOCKED) | Guard transitions |

---

## 15. Scaling & Failure

- **Graph** sharded by id + **TAO-style read-through cache** (friend lists read on every feed build); MySQL durable backing.
- **Feed** = hybrid fan-out + ML ranking on candidate sets; store ids, hydrate content, cache per user.
- **Counters** (reactions/comments) async-aggregated; approximate cached values.
- **Privacy** enforced on read (ACL/Chain) — never leak restricted posts.
- **Media** → blob + CDN; posts store references.
- Eventual consistency for feed/counts; strong only where needed (friendship state).

---

## 16. Interview Cheat Sheet

> **"How is the friend graph stored?"**
> "Sharded objects + typed associations behind a **read-through graph cache (TAO-style)** over MySQL — friend lists are read on every feed build so caching is essential. Sharded by id; mutuals = set intersection; friend suggestions = 2-hop friends-of-friends."

> **"How is the News Feed built and ranked?"**
> "Hybrid fan-out (push for normal friends, pull for high-degree pages), then a ranking pipeline: candidate generation (privacy-filtered) → feature extraction (affinity, recency, engagement) → ML scoring → rank + diversity + ads → cache. Ranking runs on bounded candidate sets."

> **"How is privacy enforced?"**
> "Each post has a scope (public/friends/custom/only-me); visibility is checked during **candidate generation and on fetch**, and blocked users are excluded — restricted posts never enter a feed."

> **"Facebook vs Twitter?"**
> "Bidirectional friend graph + heavy ML ranking merging friends/groups/pages, vs Twitter's unidirectional follows and more chronological feed. Same fan-out trade-offs + high-degree/celebrity problem."

---

## 17. Final Takeaways

- Two hard parts: a **trillion-edge friend graph** (TAO-style read-through cache over sharded MySQL) + a **ranked News Feed**.
- **Feed** = hybrid fan-out + **ML ranking pipeline** (candidates → features → score → rank) on bounded candidate sets; store ids, hydrate, cache.
- **Privacy enforced on read** during candidate generation — never leak restricted posts.
- **Counters async**, **media on CDN**, **comments as trees**.
- Patterns: Strategy (fan-out/rank), Producer-Consumer, CQRS/Materialized View, Graph+Cache-Aside (TAO), Composite, Chain (privacy), Observer.

### Related notes

- [Fan-Out / Fan-In & Celebrity Problem](../concepts/fan-out-fan-in.md) — push/pull/hybrid in depth
- [Twitter / News Feed](twitter-news-feed-system-design.md) · [Instagram](instagram-system-design.md) — sibling feed systems
- [Caching Strategies](../concepts/caching-strategies.md) · [Apache Kafka](../concepts/kafka.md) · [Databases — Deep Dive](../concepts/databases-deep-dive.md)
