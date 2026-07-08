# Leaderboard / Ranking — System Design

> **Core challenge:** maintain a **real-time ranking** of millions of players by score — support "top N", "my rank", and "players around me" — with **fast updates and reads**. The heart is a **Redis Sorted Set** (skip list) and how to scale it (sharding + approximate rank) at very large scale.

> **How to read this doc:** each section has the dense interview summary first, then a **deep dive** (annotated code and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand.

---

## Contents

- [1. Mental Model](#1-mental-model)
- [2. Requirements](#2-requirements)
- [3. Capacity Estimation](#3-capacity-estimation)
- [4. Architecture](#4-architecture)
- [5. The Naive DB Approach (why it fails)](#5-the-naive-db-approach-why-it-fails)
- [6. Redis Sorted Set (the core)](#6-redis-sorted-set-the-core)
- [7. Write Path & Durability](#7-write-path--durability)
- [8. Scaling to Millions (sharding + approximate rank)](#8-scaling-to-millions-sharding--approximate-rank)
- [9. Time-Windowed Leaderboards](#9-time-windowed-leaderboards)
- [10. Data Model](#10-data-model)
- [11. API Design](#11-api-design)
- [12. Sequences](#12-sequences)
- [13. Consistency & Edge Cases](#13-consistency--edge-cases)
- [14. Design Patterns (that can be used)](#14-design-patterns-that-can-be-used)
- [15. Interview Cheat Sheet](#15-interview-cheat-sheet)
- [16. Final Takeaways](#16-final-takeaways)

---

## 1. Mental Model

```
Player scores points → update their score → ranking recomputed implicitly
Queries: top 10, "my rank", "10 players around me", "my score"
```

Two operations dominate: **update score** and **get rank/range** — both must be fast. **Redis sorted sets** do exactly this in `O(log n)`.

### What problem are we even solving?

Picture a mobile game like **Candy Crush** or **PUBG**. Every player has a score. The game shows:

- **Top 10 players in the world** (the leaderboard everyone wants to be on).
- **"Your rank: #45,213"** — where *you* stand among everyone.
- **"Players near you"** — the handful just above and just below you, so you feel "if I score 200 more I pass this person."

So the whole system is a giant **score board that stays sorted, all the time**, for millions of players. Two things happen constantly:

1. **Scores change** — every kill, every level, every match bumps someone's number. Millions of updates.
2. **People peek at the board** — everyone wants to see their rank *right now*. Even more reads than writes.

The entire design is about doing both — **"change a score"** and **"tell me where someone ranks"** — extremely fast, even with 100 million players.

### Why is "rank" the hard part?

Storing a score is trivial (`user → number`). The hard question is: **"how many people are ahead of me?"**

Naively, to answer "your rank is #45,213" you'd have to **count everyone with a higher score than you** — walk 45,212 players every single time someone asks. With millions of players asking constantly, that's impossible.

> **Key insight that drives the whole design:** keep the players **already sorted** in a special structure so "how many are ahead of me?" is answered instantly — you don't count, you just *look up a position*.

That structure is a **Redis Sorted Set** (§6). Everything else is "how do we keep it fast, durable, and scaled to millions."

---

## 2. Requirements

**Functional**
- Update a player's score (set/increment).
- Get **top N**; a player's **rank**; **players around a player** (neighbors); a player's score.
- Optionally: **time-windowed** (daily/weekly/all-time), **per-region/game**.

**Non-functional**
- **Real-time** updates + reads (sub-ms); scale to **millions/billions** of players; highly available. **Eventual consistency** usually fine (rank lagging a moment is OK).

### What "eventual consistency is fine" really means here

For a bank balance, being off by even a moment is unacceptable. For a leaderboard, it's totally OK if your rank is a **second or two stale**.

Example: you just scored, jumping from #500 to #480. If a friend loads the board half a second before it updates and briefly sees you at #500 — nobody cares. The number self-corrects almost instantly.

> This relaxed requirement is a **gift**: it lets us serve reads from fast in-memory copies and update the durable database **lazily in the background**, instead of forcing everything to be perfectly in sync on every single click. (This is why "write-behind" in §7 is safe.)

---

## 3. Capacity Estimation

```
Players ~ 100M's · score updates ~ high (every game event) · reads ~ very high (everyone checks rank)
One Redis ZSET: ~100 bytes/member → 10M members ≈ ~1 GB (fits); 100M+ → shard or bigger nodes
Ops: ZADD/ZINCRBY O(log n) + ZREVRANK O(log n) → 100k+ ops/sec/node easily (in-memory)
Durable store: scores table + score_events (audit) → modest
```

> The serving structure is **in-memory (Redis)**; the DB is the durable backing. Sizing = members × ~100 bytes; shard when a single ZSET gets too big or too hot.

### Does the whole leaderboard really fit in memory?

Yes — and that's the surprising, wonderful part. People assume "millions of players" means "huge storage," but a leaderboard entry is tiny: just a **user id + a number**.

```
one entry  ≈  userId (8 bytes) + score (8 bytes) + skip-list overhead  ≈  ~100 bytes
10,000,000 players  ×  ~100 bytes  ≈  ~1 GB
```

1 GB fits comfortably in RAM on a single Redis node. That's why we can keep the *entire* live leaderboard in memory and answer every query in microseconds.

#### Q: Reads are higher than writes — why does that matter?

Because it tells us where to spend effort. Everyone constantly refreshes to check their rank (**reads**), far more often than they score (**writes**). So we optimize the read path hard: keep it in memory, **cache the top-N** (it barely changes), and add **read replicas** (copies of Redis that only serve reads). See §8.

#### Q: When do these numbers force us to shard?

Two triggers, and either one is enough:

- **Too big** — 100M+ players won't fit in one node's RAM → split across nodes.
- **Too hot** — even if it fits, one node can't handle the *traffic* (updates + reads) → split the load.

"Shard" just means "split the one giant board into several smaller boards on different machines" (§8).

---

## 4. Architecture

```
Game/App → Score Service → ZINCRBY Redis ZSET (serving)   ─┐
                        └→ emit score event (Kafka)         │ async write-behind
                                                            ▼
                                                      Scores DB (durable truth) + score_events (audit)
Reads (top-N / rank / around) → Redis ZSET (+ cached top-N) ; DB only for cold-start rebuild
```

- **CQRS-ish:** Redis is the fast read/serve model; the DB is the source of truth (write-behind).

### Two copies of the score, and why

This design keeps the same score in **two places on purpose**, each good at a different job:

| Copy | What it is | Great at | Bad at |
| --- | --- | --- | --- |
| **Redis ZSET** (serving) | In-memory sorted set | Instant rank/top-N/around; blazing reads & updates | If the box dies, RAM is wiped (needs rebuilding) |
| **Scores DB** (truth) | A normal durable database (e.g. Postgres) | Never loses data; survives restarts | Slow at "who's ahead of me?" ranking queries (§5) |

Redis is the fast serving copy that every read hits; the DB is the durable copy that survives restarts and is the source of truth if Redis is lost.

#### Q: What does "write-behind" mean in this picture?

When a player scores, we update the **fast** copy (Redis) immediately so everyone sees it, and we tell the **slow** copy (DB) to catch up **a moment later, in the background** — instead of making the player wait for the slow database.

```
player scores → update Redis NOW (instant, visible)         ← what the user experiences
              → drop a "score event" on Kafka
                    → background writer updates the DB later ← durability, off the hot path
```

This is safe precisely because a leaderboard tolerates being a moment behind (§2). We get **fast writes** *and* **durable truth** — the DB just lags slightly, which is fine.

#### Q: What is "CQRS-ish" here in plain words?

**CQRS = Command Query Responsibility Segregation** — a fancy way of saying "**use a different model for writing than for reading.**" Here: writes ultimately land in the DB (truth), but reads are served from Redis (a purpose-built *view* optimized for ranking). Same data, two shapes, each tuned for its job.

---

## 5. The Naive DB Approach (why it fails)

```sql
SELECT COUNT(*)+1 FROM scores WHERE score > (SELECT score FROM scores WHERE user_id=?)   -- my rank
SELECT * FROM scores ORDER BY score DESC LIMIT 10                                          -- top 10
```

- "My rank" = a **COUNT over all higher scores** → O(n) (or heavy index work) **per query**.
- With frequent updates + millions of rows, this crushes the DB. **Ranking is fundamentally an ordered-set problem, not a relational one.**

### Why the obvious SQL melts

The obvious first attempt: "just put scores in a table and ask the database."

```sql
-- "What's my rank?" = count everyone with a higher score, then add 1
SELECT COUNT(*) + 1
FROM   scores
WHERE  score > (SELECT score FROM scores WHERE user_id = 123);
```

This reads correctly in English and works fine for 100 players. It **falls apart** at millions because of *what the database physically has to do*:

- To answer "how many scores are above mine?", the DB must **look at (or index-scan) a huge chunk of rows and tally them** — that's **O(n)** work. One query = potentially millions of rows touched.
- Now multiply by **everyone checking their rank at once** → the DB is doing millions of these giant counts per second. It grinds to a halt.
- And scores are **changing constantly**, so you can't just cache the answer — every update can shift thousands of people's ranks.

In other words, the SQL approach recomputes the full ranking from scratch on every single query — hopeless at scale.

> **The fix (the whole insight):** don't count on demand — keep all players **stored in sorted order permanently.** Then "what's my position?" is a direct position lookup — instant, no counting. That's what a Redis Sorted Set gives you (§6).

**Ranking is an ordered-set problem, not a relational one** — a relational DB is built for "fetch/update this specific row," not "maintain a live sorted order of millions."

---

## 6. Redis Sorted Set (the core)

A **sorted set (ZSET)** — backed by a **skip list + hash map** — keeps members ordered by score with `O(log n)` ops.

```
ZADD    leaderboard <score> <userId>        # add/update score          O(log n)
ZINCRBY leaderboard <delta> <userId>        # increment score           O(log n)
ZREVRANGE leaderboard 0 9 WITHSCORES        # TOP 10                     O(log n + k)
ZREVRANK  leaderboard <userId>              # MY RANK (0-based)          O(log n)
ZREVRANGE leaderboard <rank-5> <rank+5>     # players AROUND me          O(log n + k)
ZSCORE   leaderboard <userId>               # my score                   O(1)
```

- The skip list gives ordered range/rank; the hash map gives O(1) member→score lookup.
- All the "hard" queries become single `O(log n)` (or `+k`) commands — that's the whole trick.

### What a Sorted Set actually is

A **Redis Sorted Set (ZSET)** is a collection that keeps its members sorted by score, forever, automatically.

You put in pairs of **(member, score)** — here `(userId, points)`. No matter the order you insert them, Redis always keeps them ordered from highest to lowest. When you change someone's score, they instantly move to their new correct position — you never re-sort anything yourself. Queries like "top 3" or "what position is Alice in?" are answered instantly because the set is *always* in order.

### The commands, annotated

Here's the same command list, walking through a real game session:

```redis
# A player earns points — set or increment their score.
ZADD    game:leaderboard 500 "alice"     # alice now has 500 points        O(log n)
ZINCRBY game:leaderboard 300 "alice"     # alice scored +300 → now 800     O(log n)
ZADD    game:leaderboard 950 "bob"       # bob has 950                      O(log n)
ZADD    game:leaderboard 200 "carol"     # carol has 200                    O(log n)

# Show the TOP 3 (REV = highest first). WITHSCORES = include the numbers.
ZREVRANGE game:leaderboard 0 2 WITHSCORES
#   → bob 950, alice 800, carol 200

# "What's MY rank?" (0-based, highest = rank 0). alice is 2nd highest → rank 1.
ZREVRANK  game:leaderboard "alice"       # → 1   (i.e. #2 on the board)     O(log n)

# "My score?"  — instant, no ordering needed.
ZSCORE    game:leaderboard "alice"       # → 800                            O(1)
```

- `ZADD` = **set** a score (overwrite). `ZINCRBY` = **add** to it (great for "+300 this match").
- `ZREVRANGE` walks the board **high → low**; `ZRANGE` walks **low → high**.
- Rank is **0-based**: rank 0 = the very top. To show "#1", add 1 for humans.

### "Players around me" — the neat trick

Every game shows *"here's you, and the people just above and below you."* This is two commands:

```redis
# Step 1: find my position on the board.
myRank = ZREVRANK game:leaderboard "alice"      # say myRank = 45212

# Step 2: grab the slice of players from a bit above me to a bit below me.
ZREVRANGE game:leaderboard (myRank-5) (myRank+5) WITHSCORES
#   → the 5 players ahead of alice, alice herself, and the 5 behind her
```

Illustrative pseudo-Java wrapping the two calls:

```java
List<Entry> playersAroundMe(String userId, int range) {
    // 1) where does this user sit? (0-based, highest = 0)
    Long rank = redis.zrevrank("game:leaderboard", userId);
    if (rank == null) return List.of();          // user not on the board yet

    // 2) take a window [rank-range .. rank+range]; clamp so we never go below 0
    long start = Math.max(0, rank - range);
    long end   = rank + range;

    // returns members high→low WITH their scores; O(log n + k), k = window size
    return redis.zrevrangeWithScores("game:leaderboard", start, end);
}
```

Because the set is already sorted, "grab the 11 people around position 45,212" is just **slicing a range by index** — cheap (`O(log n + k)`), no scanning millions.

### Q&A

#### Q: Why a *sorted set* instead of a normal database or a sorted list?

Because it's purpose-built for *exactly* our two operations and does both in `O(log n)`:

| Operation | Plain DB / `ORDER BY` | Plain sorted array | **Redis ZSET** |
| --- | --- | --- | --- |
| Update one score | fast write, but... | O(n) to re-insert in order | **O(log n)** |
| "My rank?" | **O(n)** count (§5) | O(log n) find, but updates are O(n) | **O(log n)** |
| Top-N | O(n log n) sort or index scan | O(k) | **O(log n + k)** |

A plain array is fast to *read* a rank but painfully slow to *update* (shift everything). A DB is fine to update one row but slow to rank. The ZSET is the rare structure that's fast at **both** — which is the whole point, since a leaderboard does both constantly.

#### Q: What's this "skip list + hash map" under the hood?

Redis stores a ZSET as **two structures working together**:

- **Skip list** — keeps members in **sorted order**, so range/rank queries (top-N, "my rank", around-me) are `O(log n)`. Think of it as a sorted linked list with express lanes that let you jump ahead instead of stepping one-by-one.
- **Hash map** — a plain `userId → score` lookup, so `ZSCORE` ("what's my score?") is instant `O(1)` without touching the sorted structure.

You don't manage these — Redis keeps them in sync for you. You just call the `Z*` commands.

#### Q: Rank is 0-based and highest-first — won't I show the wrong number?

`ZREVRANK` returns `0` for the top player. Humans expect "#1". So the display layer does `humanRank = zrevrank + 1`. Also note **REV**: use `ZREVRANK`/`ZREVRANGE` for "highest score wins" (games), and the plain `ZRANK`/`ZRANGE` if lowest wins (e.g. fastest race time).

#### Q: Is it really this simple? Where's the catch?

For one Redis node holding one board — yes, it's genuinely this simple, and it handles millions of players. The complexity only appears when a single node isn't **big enough** or **fast enough** for your scale — then you shard and approximate rank (§8) — or when you need it to survive crashes (§7).

---

## 7. Write Path & Durability

```
onScore(user, delta):
  ZINCRBY leaderboard delta user        # update serving ZSET (atomic)
  append score_event (Kafka) → async writer updates the DB (write-behind)
```

- **`ZINCRBY` is atomic** (Redis single-threaded) → no lost updates on concurrent increments.
- **Write-behind:** the DB is updated asynchronously (batched) → fast writes, durable truth.
- **Cold start / Redis loss:** rebuild the ZSET from the DB (`scores` table); Redis AOF/RDB persistence speeds recovery.

### Why `ZINCRBY` saves us from lost updates

Suppose Alice scores twice at almost the same instant — say a +10 and a +5 arrive together. A classic bug (the **race condition**):

```
Server A reads Alice = 100
Server B reads Alice = 100      (both saw 100!)
Server A writes 100 + 10 = 110
Server B writes 100 +  5 = 105  ← overwrites A's work; the +10 vanished
```

Redis dodges this entirely because it's **single-threaded** — it does **one command at a time, start to finish**, never two at once. And `ZINCRBY` says *"add this delta"* rather than *"set to this value"*, so Redis itself does the read-add-write as one indivisible step:

```redis
ZINCRBY game:leaderboard 10 "alice"   # Redis: 100 → 110
ZINCRBY game:leaderboard  5 "alice"   # Redis: 110 → 115   ✓ both counted
```

> **Rule of thumb:** prefer `ZINCRBY delta` ("add 10") over `ZADD newValue` ("set to 110") whenever multiple sources update the same score, so you never clobber a concurrent update.

### Durability — what happens if Redis dies?

Redis lives in **RAM**. Pull the plug and memory is wiped — the whole board is gone. That would be a disaster if Redis were the *only* copy. It isn't. Two safety nets:

1. **The DB is the real truth.** Every score also lands in a durable database (via write-behind). If Redis vanishes, no *data* is lost — only the fast serving copy.
2. **Rebuild on cold start.** When Redis restarts empty, we stream the `scores` table back in and re-`ZADD` everyone. The board is reconstructed.

```java
// "Cold start": Redis came up empty → refill it from the durable DB
void rebuildLeaderboard() {
    for (ScoreRow row : db.streamAllScores()) {      // read the source of truth
        redis.zadd("game:leaderboard", row.score(), row.userId());
    }
    // board fully restored; serving resumes
}
```

#### Q: What are AOF and RDB, and why do they matter if the DB is already truth?

They're Redis's own **on-disk backups**, so it can recover *fast* without replaying the entire DB:

| | **RDB** (snapshot) | **AOF** (append-only file) |
| --- | --- | --- |
| What | Periodic full snapshot of memory to disk | Log of every write command, replayed on restart |
| Recovery | Fast to load, but loses writes since last snapshot | More complete (up to last fsync), slower to replay |

With AOF/RDB, a restarted Redis reloads *itself* in seconds instead of re-reading millions of rows from the DB. The DB rebuild is the ultimate fallback; AOF/RDB is the fast path.

#### Q: Isn't write-behind risky — what if the background writer crashes before saving to the DB?

That's why the score event is put on **Kafka** (a durable log) first, not just handed to an in-process thread. The event sits safely in Kafka until the writer confirms it saved to the DB. If the writer crashes, it restarts and picks up where it left off — nothing is lost. (This is the **Producer-Consumer** pattern; the `score_events` table is also an audit trail to rebuild/verify from — see §10.)

---

## 8. Scaling to Millions (sharding + approximate rank)

| Concern | Approach |
| --- | --- |
| **Memory / size** | One ZSET holds millions; beyond that, **shard** (by region/game/score-band) |
| **Sharding** | Partition into sub-leaderboards; **merge top-N** across shards for a global view |
| **Exact global rank across shards** | Expensive → use **approximate rank** |
| **Durability** | DB is truth; rebuild ZSET on cold start; AOF/RDB |
| **Hot updates** | `ZINCRBY` atomic; batch write-behind |
| **Read scale** | Redis replicas for read-heavy top-N; **cache top-N** (changes slowly) |

**Approximate global rank (the key large-scale trick):**
```
Maintain a HISTOGRAM of score buckets (e.g. counts per score range), globally aggregated.
my_rank ≈ (Σ counts in all buckets with score > my bucket) + my position within my bucket
→ O(#buckets), avoids exact ordering across all shards; exact only within a bucket.
```
- Top-N is still exact (merge each shard's top-N); **exact global rank** is what's expensive, so approximate it.

### When one board isn't enough

Everything so far assumed **one** Redis holding **one** board. That's great until:

- **It's too big** — 500M players won't fit in one node's RAM.
- **It's too hot** — one node can't keep up with the flood of updates + reads.

The fix is **sharding**: split the one giant board into several smaller boards, each on its own machine.

Sharding turns one huge board into several smaller boards, each easy to manage on its own. The only tricky part becomes *"combine the shards into one global picture"* — which is exactly what the two problems below are about.

### Sharding by score range vs. by hashing

There are two common ways to split, and the choice changes how ranking works:

| Split strategy | How | Global rank |
| --- | --- | --- |
| **By hash of userId** | `shard = hash(userId) % N` — players scattered evenly | Hard: a player's rank depends on *all* shards (their score could beat anyone anywhere) |
| **By score range (band)** | shard 0 = scores 0–999, shard 1 = 1000–1999, ... | Easier: shards are *already* ordered; higher bands are entirely ahead of lower ones |

**Score-range sharding** is the neat one for ranking. If you're in the 1000–1999 band, everyone in the 2000+ bands is automatically ahead of you, and everyone in 0–999 is behind — no cross-shard comparison needed for those. You only rank precisely *within* your own band.

```
shard 2:  scores 2000+     ← entirely ahead of you
shard 1:  scores 1000–1999 ← YOU are here; rank exactly within this band
shard 0:  scores    0–999  ← entirely behind you

my global rank ≈ (everyone in shard 2) + (my exact rank inside shard 1)
```

### Top-N across shards (still exact, and cheap)

"Who are the global top 10?" stays **exact and easy** even when sharded — a **merge**:

```java
// Ask each shard for ITS own top 10, then merge to get the global top 10.
List<Entry> globalTopN(int n) {
    List<Entry> candidates = new ArrayList<>();
    for (Shard s : shards) {
        // each shard's top-N is a cheap ZREVRANGE on that node
        candidates.addAll(s.redis.zrevrangeWithScores(s.key, 0, n - 1));
    }
    // the global top-N must be among the per-shard top-Ns — sort & take n
    candidates.sort((a, b) -> Long.compare(b.score(), a.score()));
    return candidates.subList(0, Math.min(n, candidates.size()));
}
```

Why this works: the #1 player globally is obviously #1 on *their* shard too. So the global top 10 is guaranteed to be hiding inside the union of each shard's top 10. Grab those, sort the small combined list, done.

### Why exact global rank is the expensive one

"My rank" is the painful query across shards. If sharded by hash, to know exactly how many players beat your score, you'd have to ask **every shard** "how many of yours are above my score?" and sum — every time, for everyone. That's costly at scale.

The clever workaround: **don't compute it exactly — estimate it with a histogram.**

```
Keep a small, global HISTOGRAM = counts of players per score bucket:
  [0–99]: 4,200,000     [100–199]: 3,100,000     [200–299]: 900,000     ...

To estimate YOUR rank:
  1) Sum the counts of ALL buckets with a HIGHER score than your bucket.
  2) Add your position WITHIN your own bucket (this part can be exact).
  → rank ≈ (sum of higher buckets) + (your spot inside your bucket)
```

Annotated:

```java
long approxRank(long myScore) {
    long ahead = 0;
    // 1) everyone in a strictly-higher bucket is ahead of me — just add the counts
    for (Bucket b : histogram.bucketsAbove(myScore)) {
        ahead += b.count();                    // O(#buckets), NOT O(#players)
    }
    // 2) within my own bucket, count those above me (small, so can be exact)
    ahead += countAboveWithinBucket(myScore);
    return ahead + 1;                          // human-friendly #1-based rank
}
```

This estimates rank from a handful of bucket totals instead of comparing against every player — close enough for the deep middle of the board, and vastly cheaper than an exact cross-shard count.

#### Q: Isn't an approximate rank a problem?

Rarely. The person at rank #45,213 does **not** care if the true number is #45,207 — it's meaningless precision. And the places where precision *does* matter — the **top of the board** — stay **exact**, because top-N is a cheap exact merge. So: exact where humans care (the top), approximate where they don't (the deep middle). Best of both.

#### Q: Why cache the top-N and use read replicas?

Two cheap, high-impact read optimizations:

- **Cache top-N** — the top 10 changes *slowly* (the same champions sit there for a while), but *everyone* loads it. Cache it with a short TTL so you're not recomputing the same list for millions of viewers.
- **Read replicas** — extra copies of Redis that only answer reads. Since reads vastly outnumber writes (§3), spread the read load across replicas while the primary handles updates.

---

## 9. Time-Windowed Leaderboards

- **Daily/weekly** boards = separate ZSETs keyed by window (`lb:daily:2026-07-07`) with **TTL** → auto-expire.
- **All-time** = a persistent ZSET.
- A score update **increments all active windows** (daily + weekly + all-time).

### "Today's top players" vs "all-time greats"

Games usually show several boards at once: **Today**, **This Week**, and **All-Time**. Each is its own separate sorted set — you don't try to cram time logic into one board.

The naming trick: bake the time window right into the key.

```
lb:all                     ← all-time board, lives forever
lb:daily:2026-07-08        ← just today's points
lb:weekly:2026-W28         ← this week's points
```

When a player scores, you simply bump **all the boards that are currently live**:

```java
void onScore(String userId, long delta) {
    String today = "lb:daily:"  + LocalDate.now();       // e.g. lb:daily:2026-07-08
    String week  = "lb:weekly:" + isoWeek(LocalDate.now());

    redis.zincrby("lb:all", delta, userId);              // all-time
    redis.zincrby(today,    delta, userId);              // today
    redis.zincrby(week,     delta, userId);              // this week

    // set TTL so the daily board auto-deletes ~2 days after it's irrelevant
    redis.expire(today, Duration.ofDays(2));
}
```

#### Q: How do old daily boards get cleaned up — do we run a delete job?

No cron job needed — that's the beauty of **TTL** (time-to-live). When you create today's board, you tell Redis "delete this automatically after N days." Once nobody needs `lb:daily:2026-07-01` anymore, Redis **evicts it on its own**. Yesterday's boards quietly disappear; you never write cleanup code.

#### Q: Why separate boards instead of filtering one big board by time?

Because a sorted set only knows **(member, score)** — it has no notion of "when." To answer "today's top 10," a single board would have to store per-player timestamps and filter, which sorted sets can't do efficiently. Separate per-window boards keep each one a clean, fast, already-sorted list. The small cost — a few extra `ZINCRBY`s per score — is trivial.

---

## 10. Data Model

```sql
-- Durable backing store (source of truth)
CREATE TABLE scores ( user_id BIGINT PRIMARY KEY, score BIGINT NOT NULL, updated_at TIMESTAMP );
CREATE TABLE score_events ( event_id BIGINT PRIMARY KEY, user_id BIGINT, delta BIGINT, at TIMESTAMP );  -- audit / rebuild

-- Serving layer (Redis):
--   ZSET leaderboard:all              member=userId score=score
--   ZSET leaderboard:daily:{date}     (TTL)  · leaderboard:region:{r}
--   score_histogram (buckets)         for approximate global rank
--   cache: top-N list (short TTL)
```

> **Stores to consider:** scores (durable), score_events (audit/rebuild), Redis ZSETs per window/region, score histogram (approx rank), cached top-N. Redis ZSET = serving; DB = truth.

### What each store is for

Two SQL tables plus the Redis structures — each earns its place:

| Store | Holds | Why it exists |
| --- | --- | --- |
| **`scores` table** | one row per user: their *current* score | The durable **source of truth**; rebuild Redis from it on cold start |
| **`score_events` table** | one row per score *change* (a log) | **Audit trail** — replay/verify history, catch cheating, recompute if needed |
| **Redis ZSET(s)** | sorted `(userId → score)` per board | The fast **serving** layer (rank/top-N/around) |
| **score histogram** | counts per score bucket | Powers **approximate global rank** at scale (§8) |
| **cached top-N** | the current top 10 list | Serve the most-viewed query without recomputing |

#### Q: Why keep BOTH a `scores` table (current) and a `score_events` log (history)?

They answer different questions:

- **`scores`** answers *"what is Alice's score right now?"* — one row, always current. Fast to read, easy to rebuild Redis from.
- **`score_events`** answers *"how did Alice get here?"* — every +10, +300, etc. This is your safety net: if you suspect cheating or a bug, you can **replay the events** to recompute the true score, or roll back a bad one.

Put differently: `scores` is the *current total* (a quick lookup); `score_events` is the *history of changes* (to explain, audit, or recompute it). You want both. (This is the **Event Sourcing** idea: the log of changes is the ground truth you can always rebuild from.)

---

## 11. API Design

```
POST /v1/scores            { userId, delta }          # ZINCRBY (+ event)
GET  /v1/leaderboard/top?n=10&window=daily
GET  /v1/leaderboard/rank?userId=            → { rank, score }
GET  /v1/leaderboard/around?userId=&range=5
```

### Each endpoint mapped to a Redis command

The API is a thin wrapper — every endpoint is basically **one Redis command** dressed up as HTTP:

| Endpoint | What the user wants | Under the hood |
| --- | --- | --- |
| `POST /v1/scores` | "I just earned points" | `ZINCRBY` (+ emit a score event for the DB) |
| `GET /leaderboard/top?n=10` | "Show the top 10" | `ZREVRANGE 0 9 WITHSCORES` (often served from cache) |
| `GET /leaderboard/rank?userId=` | "What's my rank & score?" | `ZREVRANK` + `ZSCORE` |
| `GET /leaderboard/around?userId=&range=5` | "Who's near me?" | `ZREVRANK` then a `ZREVRANGE` window |

Annotated handler for the two most interesting ones:

```java
// "What's my rank?"  → POST body: { userId }
RankResponse getRank(String userId) {
    Long rank  = redis.zrevrank("game:leaderboard", userId);  // 0-based, highest first
    Double sc  = redis.zscore("game:leaderboard", userId);
    if (rank == null) return RankResponse.notOnBoard();
    return new RankResponse(rank + 1, sc);                     // +1 → human "#1" style
}

// "I scored" → POST /v1/scores { userId, delta }
void addScore(String userId, long delta) {
    redis.zincrby("game:leaderboard", delta, userId);         // update serving board NOW
    kafka.send("score_events", new ScoreEvent(userId, delta)); // durability, in background
}
```

#### Q: Why does `POST /v1/scores` take a `delta` (+300) instead of the new total (800)?

Because sending a **delta** ("add 300") lets Redis apply it atomically with `ZINCRBY`, so two near-simultaneous scores both count (§7). If the client sent an absolute total ("set to 800"), two concurrent updates could overwrite each other and lose points. Let the client say *what changed*, not *what the total should be*.

---

## 12. Sequences

### Score update + read rank

```
Game → ScoreSvc: ZINCRBY leaderboard delta user  (atomic)  → emit score_event → Kafka
       async writer → update scores DB (batched, write-behind)
User → ScoreSvc: ZREVRANK leaderboard user → rank ; ZSCORE → score  (O(log n))
```

### Cold start (Redis lost)

```
On Redis restart with empty ZSET → stream scores table → ZADD each → serving restored
(AOF/RDB persistence makes this fast; DB is the ultimate truth)
```

### Walking through a score update

Follow a single "+300" from Alice, step by step:

```
1. Alice's phone → POST /v1/scores { userId: alice, delta: 300 }
2. Score Service → ZINCRBY game:leaderboard 300 alice   (Redis: 500 → 800, atomic, instant)
3. Score Service → emit score_event to Kafka             (fire-and-forget, durable)
4. (background) async writer reads Kafka → UPDATE scores SET score=800 ... (batched)
5. Alice refreshes → ZREVRANK + ZSCORE → "Rank #2, 800 pts"   (served from Redis, O(log n))
```

The key thing to notice: **step 2 is all the user waits for.** Everything durable (steps 3–4) happens *after* the response, in the background. That's why updates feel instant even though the durable DB is slower.

#### Q: In cold start, why rebuild from the `scores` table and not replay every event?

Because `scores` already holds each player's **final** number — one `ZADD` per user and you're done. Replaying `score_events` would mean re-applying millions of individual `+10`/`+300` changes to arrive at the same totals — far slower. You only replay events for **auditing or recovering from a bug**, not for routine restarts. (AOF/RDB is faster still, and is the first line of recovery.)

---

## 13. Consistency & Edge Cases

| Case | Handling |
| --- | --- |
| Concurrent increments | `ZINCRBY` atomic (Redis single-threaded) — no lost updates |
| Ties (equal scores) | ZSET orders by score then lexicographically by member; or pack a timestamp into the score for deterministic ties |
| Redis loss | Rebuild ZSET from DB (+ AOF/RDB) |
| Exact global rank at scale | Approximate via bucket histogram; exact within bucket |
| Score rollback/cheating | `score_events` audit; recompute; anomaly detection |
| Read during update | Eventual — a momentarily stale rank is fine |
| Time-window rollover | New ZSET per window with TTL; writes hit all active windows |

### Ties — what happens when two players have the same score?

Say Alice and Bob both have exactly **800**. Who's shown first? A sorted set needs *some* rule to break the tie, and by default Redis breaks it **lexicographically by member name** — "alice" sorts before "bob". Consistent, but arbitrary (alphabetical order has nothing to do with skill).

If you want ties broken by **who got there first** (fairer for games — reaching 800 earlier should rank higher), the classic trick is to **pack a timestamp into the score itself**:

```java
// Make the score a single number that encodes BOTH points and time.
// Big multiplier keeps points dominant; the time part only decides ties.
long compositeScore(long points, long timestampMs) {
    // earlier timestamp should rank higher, so subtract it (invert)
    long inverseTime = (Long.MAX_VALUE >> 21) - timestampMs;  // smaller ts → bigger value
    return points * 1_000_000_000L + inverseTime;             // points dominate; time breaks ties
}
```

Now two players at 800 points are separated by *when* they hit 800 — deterministic and fair, still a single `ZADD`/`ZREVRANK`, no extra queries. The packed timestamp is just a tiebreaker so equal-point players still get a stable, fair order.

### Q&A on the other edge cases

#### Q: A player is cheating / a score was wrong — can we roll it back?

Yes — that's what the **`score_events` audit log** is for (§10). Because every change is recorded, you can subtract a fraudulent gain, or recompute a player's true score by replaying their legitimate events. Anomaly detection can flag suspicious jumps ("+1,000,000 in one second") for review.

#### Q: What if someone reads their rank *during* an update?

They might see a rank that's a heartbeat stale — and that's **fine** (§2). The number corrects itself within moments. We deliberately chose eventual consistency here because a perfectly-synchronized rank isn't worth the performance cost for a leaderboard.

#### Q: What happens exactly at midnight when the daily board rolls over?

Nothing dramatic — the key name simply changes (`lb:daily:2026-07-08` → `lb:daily:2026-07-09`). New scores flow into the new day's board; the old one keeps its final standings until its **TTL** expires and Redis deletes it (§9). Writes always target *all currently active windows*, so nothing is dropped during the switch.

---

## 14. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Sorted Set (skip list)** | Core ranking structure | O(log n) rank/range |
| **Write-Behind (write-back) cache** | Redis serves; async persist to DB | Fast writes + durability |
| **Strategy** | Ranking scope (global/region/time), exact vs approximate rank | Swap approach |
| **Sharding** | Split ZSET by region/score-band | Scale beyond one node |
| **CQRS / Materialized View** | Redis serving view vs DB truth | Fast reads |
| **Observer / Pub-Sub** | Score events → boards, notify, DB | Decouple |
| **Cache-Aside** | Cached top-N | Read performance |
| **Producer-Consumer** | Async score-event processing | Absorb bursts |

---

## 15. Interview Cheat Sheet

> **"How do you get 'my rank' fast?"**
> "A **Redis sorted set** (skip list) — `ZREVRANK` gives rank in `O(log n)`, `ZREVRANGE` gives top-N and 'players around me', `ZSCORE` is O(1). A relational `COUNT(*) WHERE score > mine` is O(n) per query and doesn't scale."

> **"How is it durable?"**
> "Redis is the serving layer with **write-behind** to the DB (source of truth); `ZINCRBY` is atomic. On cold start, rebuild the ZSET from the `scores` table; Redis AOF/RDB speeds recovery."

> **"How do you scale to hundreds of millions of players?"**
> "**Shard** leaderboards by region/game/score-band and merge top-N. Exact global rank across shards is expensive → use **approximate rank via a score-bucket histogram** (count higher buckets + position within your bucket). Cache top-N and use read replicas."

> **"Ties / windows?"**
> "Ties: order by score then member, or pack a timestamp into the score. Windows: separate ZSETs per day/week with TTL; a score update increments all active windows."

---

## 16. Final Takeaways

- Ranking is an **ordered-set** problem → **Redis sorted set** (`ZADD`/`ZINCRBY`/`ZREVRANK`/`ZREVRANGE`), all `O(log n)`.
- The relational `COUNT` approach is O(n) per query — doesn't scale.
- **Redis serves, DB is truth** (write-behind, atomic ZINCRBY); rebuild ZSET on cold start.
- **Shard** by region/score-band + **approximate rank** (bucket histogram) at massive scale; cache top-N.
- **Time-windowed** boards = per-window ZSETs with TTL.
- Patterns: Sorted Set, Write-Behind, Strategy, Sharding, CQRS/Materialized View, Cache-Aside.

### Related notes

- [Distributed Cache](distributed-cache-system-design.md) · [Databases — Deep Dive](../concepts/databases-deep-dive.md) (Redis sorted sets) · [Caching Strategies](../concepts/caching-strategies.md) · [Consistent Hashing](../concepts/consistent-hashing.md)
