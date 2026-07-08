# Typeahead / Search Autocomplete — System Design

> **Core challenge:** as a user types, return the **top-k most relevant completions** in **a few milliseconds**, for **every keystroke**, at massive query volume. The heart is a **prefix data structure (trie)** with **precomputed top-k per prefix**, plus a pipeline that continuously **updates rankings** from search logs.

> **How to read this doc:** each section has the dense interview summary first, then a **deep dive** (annotated Java and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand.

---

## Contents

- [1. Mental Model](#1-mental-model)
- [2. Requirements](#2-requirements)
- [3. Capacity Estimation](#3-capacity-estimation)
- [4. Architecture](#4-architecture)
- [5. The Trie & Top-K per Prefix](#5-the-trie--top-k-per-prefix)
- [6. Serving Path (fast reads)](#6-serving-path-fast-reads)
- [7. Building & Updating Rankings](#7-building--updating-rankings)
- [8. Ranking, Personalization & Fuzzy Matching](#8-ranking-personalization--fuzzy-matching)
- [9. Sharding & Memory](#9-sharding--memory)
- [10. Data Model / Stores](#10-data-model--stores)
- [11. API Design](#11-api-design)
- [12. Sequences](#12-sequences)
- [13. Design Patterns (that can be used)](#13-design-patterns-that-can-be-used)
- [14. Scaling & Failure](#14-scaling--failure)
- [15. Interview Cheat Sheet](#15-interview-cheat-sheet)
- [16. Final Takeaways](#16-final-takeaways)

---

## 1. Mental Model

```
Type "net" → suggest ["netflix", "network", "netgear"] ranked by popularity, in <10ms
```

Extremely **read-heavy**, latency-critical. **Precompute the top-k completions for each prefix** so a query is a single lookup, not a search.

### What problem are we even solving?

Picture the **Google search box**. You start typing `net` and — before you finish — a little dropdown appears:

```
net
 ├ netflix
 ├ network
 └ netgear
```

That dropdown is the whole system. Two things make it hard:

- **It fires on every keystroke.** You type `n`, then `ne`, then `net` — that's *three* separate requests, each wanting fresh suggestions. Billions of people typing = a firehose of tiny requests.
- **It must feel instant.** If suggestions lag behind your typing, they're useless (you've already finished the word). So each answer must come back in **a few milliseconds**.

So the entire system is a giant, super-fast **"given these first few letters, what are people most likely about to type?"** machine. Everything else is just "how do we answer that in milliseconds, billions of times, and keep the answers up to date."

### Why not just query a database with `LIKE 'net%'`?

First instinct: keep all queries in a table and, for each keystroke, run:

```sql
SELECT query FROM searches WHERE query LIKE 'net%' ORDER BY popularity DESC LIMIT 10;
```

Why it falls apart:

- **Too slow per keystroke.** `LIKE 'net%'` scans/searches an index over *hundreds of millions* of queries, sorts them by popularity, and does this **for every letter you type**. Milliseconds? No chance — tens to hundreds of ms, and that's before load.
- **Too many requests.** Autocomplete QPS is several times search QPS (many keystrokes per search). A normal DB melts under hundreds of thousands of these fuzzy prefix queries per second.
- **We're re-doing the same work constantly.** The answer for `net` barely changes minute to minute, yet we'd recompute the top-10 from scratch on every single request.

**Key insight that drives the entire design:**

> **Don't search for completions at query time. Precompute the answer for every prefix ahead of time, and at query time just look it up.**

Instead of "search all queries starting with `net`," store — once — a tiny note attached to the prefix `net`: *"the top 10 are netflix, network, netgear, ..."*. A keystroke then becomes a **lookup**, not a **search**. The structure that makes "walk letter by letter and read the note" fast is a **trie** (§5), and the offline job that fills in those notes from real search logs is the **build pipeline** (§7).

---

## 2. Requirements

**Functional**
- Given a prefix, return **top-k** (5–10) suggestions ranked by popularity/relevance.
- Update suggestions as trends change (new popular queries appear).
- Optional: **personalization**, **typo tolerance**, multi-language.

**Non-functional**
- **Very low latency** (<10–50ms) per keystroke; **huge QPS** (multiple lookups per search).
- **Eventual consistency** of rankings is fine (a new trend can lag minutes/hours).

---

## 3. Capacity Estimation

```
Searches ~ 5B/day → each search = several keystroke lookups → autocomplete QPS ≈ 5–10× search QPS
  ~ hundreds of thousands of lookups/sec at peak → must be in-memory + cached
Vocabulary: ~100M's of distinct queries; trie nodes in the billions
Memory: raw trie of all queries = many GB → shard by prefix; store only TOP-K per node (not all descendants)
Rebuild: aggregate billions of log lines → hourly/daily trie build → atomic swap
```

> The read path must be **in-memory** (a trie lookup is a few pointer hops); the write path is a **batch/stream aggregation** that rebuilds the ranked trie.

### Reading the numbers

- **`5B searches/day → ~5–10× that in autocomplete lookups`** — you type `netflix` (7 letters), and each keystroke (after debounce) is a lookup. One search = several lookups, so the autocomplete system is busier than search itself. That's why the read path has to be trivially cheap.
- **`trie nodes in the billions`** — with hundreds of millions of distinct queries, the letter-by-letter tree of all of them is enormous. Too big for one machine's RAM → we **shard by prefix** (§9) and store only the **top-k per node**, not every descendant.
- **`hourly/daily trie build → atomic swap`** — we don't update rankings live on every search. We collect logs, rebuild the ranked trie in the background, and hot-swap it in. Rankings being a few minutes/hours stale is fine (eventual consistency); latency being a few ms slow is not.

The one-liner: **reads are tiny and constant (memory lookups); writes are rare and huge (rebuild the whole ranked structure offline).** That asymmetry is why the two paths are split.

---

## 4. Architecture

```
Client (debounced) → API Gateway → Suggestion Service (in-memory trie shards) → response
                                        └─ Redis/CDN cache for hottest prefixes

Search logs → Kafka → Aggregator (batch/stream: frequency + decay) → Trie Builder
   → serialized trie snapshot (blob, versioned) → suggestion nodes load + ATOMIC SWAP
```

- **Read path** (serving) and **write path** (offline build) are fully separate (**CQRS**).

### Two completely separate machines

There are really **two jobs** here, and the design keeps them in totally different places:

#### Part A — The reader (serving path): answering keystrokes

When you type, the request goes to a **Suggestion Service** that holds the whole ranked trie **in memory**, walks a few letters, and reads back the pre-written list. No computing, no sorting, no database query. That's why it's fast.

```
you type "net" → API gateway → Suggestion Service (trie already in RAM) → walk n→e→t → read the list → return
                                     └─ (super-hot prefixes like "a" cached in Redis/CDN so we skip even the walk)
```

#### Part B — The writer (build path): deciding what the lists say

Separately — and on a totally different schedule — an **offline pipeline** figures out *what those lists should contain*, by looking at what people actually searched:

```
every search gets logged → Kafka → Aggregator counts how often each query appears (with time decay)
   → Trie Builder writes a brand-new ranked trie → publishes it as a versioned snapshot (a big file)
   → Suggestion Services load the new snapshot and ATOMICALLY SWAP to it
```

The new snapshot is built offline and, once ready, every serving node switches to it.

#### Q: Why keep read and write totally separate? (this split is called CQRS)

Because their needs are **opposites**:

| | Read path (serve) | Write path (build) |
| --- | --- | --- |
| How often | Millions of times/sec | Once an hour/day |
| Speed need | Must be milliseconds | Can take minutes |
| Shape of work | Tiny lookup | Huge aggregation over billions of logs |
| Data | In-memory trie (read-only) | Kafka + warehouse + builder |

If we tried to update rankings *live* on the same structure people are reading, every read would risk hitting a half-updated tree (locks, partial state, slowness). Instead we **build a fresh immutable trie off to the side and swap it in** — readers never see a lock or a half-built list. This "reads and writes are different systems" idea is **CQRS** (Command Query Responsibility Segregation).

---

## 5. The Trie & Top-K per Prefix

A **trie (prefix tree)**: each node is a character; a path = a prefix.

```
        (root)
         └ n → e → t → [netflix(900), network(500), netgear(120)]   ← store TOP-K at the node
                    └ f → l → i → x  (terminal: "netflix")
```

**Key optimization:** store the **precomputed top-k completions at each node**. A query = walk to the prefix node (**O(prefix length)**) and return its cached list — **no subtree scan** at query time.

| Concern | Approach |
| --- | --- |
| Fast lookup | Top-k cached per node → O(len(prefix)) |
| Memory | Trie is huge → **shard by prefix**; compress with a **radix tree (Patricia)** or **DAWG** (shares suffixes) |
| Ranking | Each terminal query has a frequency/score; a node's top-k = highest-scoring descendants (computed offline) |

> Without cached top-k, answering "net" would DFS the whole subtree under "net" to find the best completions — too slow. Precomputing top-k per node trades build cost + memory for O(prefix) reads.

### What a trie actually is

All words starting with `net` live down the same branch. To find them you don't scan every stored query — you follow `n`, then `ne`, then `net`, and everything under that node begins with `net`.

A **trie** ("try", from re-**trie**-val) is exactly that as a tree:

- Each **edge** is one letter.
- A **path from the root** spells out a prefix.
- Words that share a prefix **share the same path** up to where they differ.

```
        (root)
          │ n
          ▼
         (n) ── e ──► (ne) ── t ──► (net) ── f ─► (netf) ─ ... ─► (netflix ✓ terminal)
                                      │  g
                                      └────────► (netg) ─ ... ─► (netgear ✓ terminal)
```

To find completions of `net`, you walk `n → e → t` — **3 hops, no matter how many millions of queries exist** — then look at everything hanging below `net`. That "cost depends only on the length of what you typed, not on how big the dataset is" property is the whole reason a trie fits here.

### The killer trick — store the top-k *at each node*

Walking to `net` is cheap. But then *finding the best 10 completions under `net`* would mean exploring the entire subtree (netflix, network, netgear, netbanking, netcat, ...) and sorting them — slow, and we'd redo it every keystroke. Millions of times per second, that's fatal.

So we **precompute the answer once, offline, and staple it to the node:**

```
(net) → cached top-k: [ netflix(900), network(500), netgear(120) ]
```

Now a query is: **walk to the node, read its cached list, done.** No subtree scan at read time. We pay for this with (a) extra memory (a small list on every node) and (b) build cost (the offline job computes these lists), but reads become trivially cheap — the trade every read-heavy system loves.

#### Annotated code — the trie node and a lookup

```java
// One node in the trie. Represents ONE prefix (e.g. "net").
class TrieNode {
    // children keyed by the next character: 'f' -> node for "netf", 'g' -> "netg", ...
    Map<Character, TrieNode> children = new HashMap<>();

    // THE optimization: the precomputed answer for THIS prefix.
    // Filled in by the offline builder; read directly at query time. No scanning.
    List<Suggestion> topK = new ArrayList<>();   // e.g. ["netflix","network","netgear"]

    boolean isWord;   // true if the path to here is itself a complete query
}

record Suggestion(String text, long score) {}   // score = popularity (higher = shown first)
```

Answering a keystroke is just a walk + a read:

```java
class Trie {
    private final TrieNode root;

    // O(length of prefix) — a handful of pointer hops, then return the cached list.
    List<Suggestion> complete(String prefix) {
        TrieNode node = root;
        for (char c : prefix.toCharArray()) {
            node = node.children.get(c);
            if (node == null) return List.of();   // prefix not in the trie → no suggestions
        }
        return node.topK;   // <-- the whole answer, already computed. No subtree scan.
    }
}
```

#### Contrast — the slow version we're avoiding (no cached top-k)

```java
// DON'T do this at query time: walk to the node, then explore the ENTIRE subtree,
// collect every complete word under it, sort by score, take k. Way too slow per keystroke.
List<Suggestion> completeSlow(String prefix) {
    TrieNode node = walkTo(prefix);
    List<Suggestion> all = new ArrayList<>();
    dfsCollectAllWords(node, all);              // could be thousands of descendants
    all.sort((a, b) -> Long.compare(b.score(), a.score()));
    return all.subList(0, Math.min(k, all.size()));
}
```

Same result, but this runs the expensive part **on every request** instead of **once at build time**. Caching top-k at the node moves that work off the hot path.

#### Q: Why a trie instead of a hash map from prefix → list?

You *could* use a plain `Map<String, List<Suggestion>>` (`"net" → [...]`), and some systems do exactly that (see §6/§9 — a flat prefix→top-k map in Redis is a common real implementation). The trie is preferred conceptually because:

- **Shared structure saves memory** — `net`, `netf`, `netfl` all live on one branch instead of storing each full prefix string as a separate map key.
- **Natural for range/next-char logic** — you can walk incrementally as the user types one more letter (reuse the node you were at), and support radix/DAWG compression (§9).

For pure serving, a flat map is simpler and often faster; the trie is the mental model and the thing the builder produces. Both store **the precomputed top-k** — that's the part that matters.

#### Q: Why store only top-k and not the full list of descendants at each node?

Because `net` might have thousands of completions, but the dropdown only shows ~10. Storing all of them at every node would blow up memory (§9). We keep just the **top-k** (say 10) — the only ones a user will ever see — and let the rare "give me more" case fall back to a subtree scan or be omitted (long tail).

---

## 6. Serving Path (fast reads)

```
GET /autocomplete?q=net
  1. route to the SHARD owning prefix "net" (shard by first 1–2 chars)
  2. walk the in-memory trie to node "net" → return its precomputed top-k
  3. (Redis/edge cache for very hot prefixes short-circuits the walk)
```

- Serving trie held **in memory** on suggestion nodes (loaded from the offline snapshot).
- **Client debounce** (fire after ~50–100ms of no typing, not every keystroke) cuts QPS massively.
- **Cache/CDN** the hottest prefixes' responses.

### Debounce — don't ask on every single keystroke

If you fire a request for `n`, then `ne`, then `net`, then `netf`... you send a request per letter, and the `n`/`ne` answers are thrown away the instant the next letter arrives. Wasteful.

**Debounce** = wait until the user *pauses* typing (say 50–100ms of no new keystroke) before sending the request. Fast typists produce **one** request for the whole word instead of seven.

```java
// Browser-side (conceptual). Only fire after the user stops typing for `delayMs`.
let timer;
input.addEventListener("keyup", () => {
    clearTimeout(timer);                       // cancel the previous pending request
    timer = setTimeout(() => {
        fetch("/v1/autocomplete?q=" + input.value);   // fires only after a real pause
    }, 80);                                     // 80ms debounce
});
```

This alone can cut autocomplete QPS by 3–5×, before the server does anything.

### Caching the hottest prefixes

Some prefixes are searched *constantly* — `a`, `f`, `the`, `you`. Their top-k basically never changes between rebuilds, yet millions of people ask for them. Rather than walk the trie every time, keep those answers in a **Redis / edge (CDN) cache** keyed by prefix.

```java
List<Suggestion> autocomplete(String prefix) {
    String key = "ac:" + prefix;
    List<Suggestion> cached = redis.get(key);
    if (cached != null) return cached;          // hot prefix → skip the trie walk entirely

    List<Suggestion> result = trie.complete(prefix);   // cold prefix → do the walk
    redis.setex(key, 60, result);               // remember it briefly (rankings are ~stable)
    return result;
}
```

This is **cache-aside**: check the cache, fall back to the source (the trie) on a miss, then populate the cache. The short TTL is fine because rankings only change on rebuild.

#### Q: If the trie is already in-memory and fast, why cache on top of it?

Two reasons: (1) a Redis/CDN hit can be served **closer to the user** (edge) and offloads the suggestion nodes entirely for the few super-hot prefixes that dominate traffic; (2) it protects the suggestion nodes from being hammered by the same handful of prefixes. The trie handles the long tail; the cache absorbs the head.

---

## 7. Building & Updating Rankings

Rankings come from **what people actually search** — an offline/streaming pipeline.

```
Search logs → Kafka → aggregate query frequencies (streaming or batch, with TIME DECAY)
   → compute top-k per prefix → build a new immutable trie → publish snapshot
   → suggestion nodes load it → ATOMIC SWAP (old trie stays serving until the new one is ready)
```

- **Time decay** (recency weighting) so trends rise and stale queries fade: `score = Σ decay(age) per occurrence` (e.g., exponential decay).
- Rebuild **periodically** (hourly/daily); huge builds can be incremental.
- **Atomic swap** = no read locks, no partial state — readers see either the old or new trie.
- **Filter** offensive/spam/PII queries before adding.

### Where do the suggestions even come from?

**They come from what everyone actually searched.** Nobody hand-writes "netflix should be the top suggestion for `net`." The system *learns* it: netflix is searched millions of times, so it floats to the top of the `net` list. Autocomplete is a mirror of the crowd's recent searches.

So the build pipeline is really just: **count how often each query was searched, then turn those counts into per-prefix top-k lists.**

#### Step 1 — count query frequencies from the logs

Every completed search is logged. A job (batch or streaming over Kafka) tallies how many times each full query appeared:

```java
// Conceptual aggregation over the search-log firehose.
// Input:  a stream of finished searches: "netflix", "netflix", "network", "netflix", ...
// Output: query -> how popular it is.
Map<String, Long> frequency = new HashMap<>();

for (SearchLog log : searchLogs) {
    if (isSpamOrOffensiveOrPII(log.query())) continue;   // filter BEFORE it can ever be suggested
    frequency.merge(log.query(), 1L, Long::sum);          // "netflix" -> 3, "network" -> 1
}
```

#### Step 2 — apply time decay so trends rise and stale queries fade

A raw all-time count would keep last year's viral query on top forever. We want **recent** popularity to matter more. So instead of `+1` per search, we add a **decayed** weight based on how old the search is — recent searches count almost fully, old ones count for less.

```java
// Exponential time decay: a search from `ageDays` ago contributes less than a fresh one.
// HALF_LIFE_DAYS = how long until a search's weight halves (e.g. 7 days).
double decayedWeight(long ageDays) {
    return Math.pow(0.5, (double) ageDays / HALF_LIFE_DAYS);
}

Map<String, Double> score = new HashMap<>();
for (SearchLog log : searchLogs) {
    long ageDays = (now - log.timestamp()) / MILLIS_PER_DAY;
    score.merge(log.query(), decayedWeight(ageDays), Double::sum);   // recent searches dominate
}
```

Now a query trending *this week* outranks one that was huge a year ago but is now quiet. That's how "gangnam style" fades and today's viral term rises.

#### Step 3 — turn scores into a top-k trie

Insert each scored query into a fresh trie, and at every node keep only the **best k** completions seen below it:

```java
// Build a brand-new, immutable trie from the scored queries.
Trie buildTrie(Map<String, Double> score) {
    Trie trie = new Trie();
    for (var e : score.entrySet()) {
        String query = e.getKey();
        double s     = e.getValue();
        trie.insert(query, s);   // walk/create nodes for each char; update top-k along the path
    }
    return trie;
}

// Inside insert: as we walk n → e → t → ... for "netflix",
// we offer ("netflix", score) to EACH node on the path and keep only its best k.
void offerToNode(TrieNode node, Suggestion cand) {
    node.topK.add(cand);
    node.topK.sort((a, b) -> Long.compare(b.score(), a.score())); // highest first
    if (node.topK.size() > K) node.topK.remove(K);                 // keep only top-k
}
```

Because `netflix` sits under `n`, `ne`, `net`, `netf`, ... it gets offered to each of those nodes, so **every prefix ends up knowing its own best completions**. (Real builders use a bounded min-heap per node instead of sort-then-trim, but the idea is identical.)

### Atomic swap — replacing the trie without downtime

You don't mutate the live trie in place while people are reading it (they'd see half-updated, inconsistent state). You build the **whole new trie** off to the side, then in one instant switch everyone to it.

The serving node holds a **pointer** to the current trie. Building happens on a *new* trie; when it's fully ready, we flip the pointer in one atomic step:

```java
class SuggestionService {
    // 'volatile' = every reader instantly sees the latest pointer once it's swapped.
    private volatile Trie current;

    // Millions of reads/sec just follow the current pointer — never blocked, never locked.
    List<Suggestion> complete(String prefix) {
        return current.complete(prefix);
    }

    // Called when a new snapshot finishes loading. ONE assignment = the swap.
    void swap(Trie freshlyBuilt) {
        this.current = freshlyBuilt;   // old trie keeps serving until this exact line runs
        // old trie now unreferenced → garbage collected once in-flight reads finish
    }
}
```

Why this is safe: a reader is always pointing at *some* fully-built trie — either the old complete one or the new complete one, **never a half-built mix**. No locks on the read path, no downtime, no partial state.

#### Q: Why rebuild on a schedule instead of updating the trie live on every search?

- **Reads must stay lock-free.** Mutating the shared trie while millions read it means locks or half-updated nodes → slow or wrong.
- **A single search barely matters.** One more "netflix" search won't change the top-10; rankings are statistical. Batching thousands of searches into a periodic rebuild is far cheaper than touching the structure per search.
- **Staleness is acceptable.** The requirements say eventual consistency is fine — a new trend showing up a few minutes/hours late is OK. Latency being slow is not. So we optimize reads and let writes be lazy/batched.

#### Q: What is Kafka doing in this pipeline?

Kafka is the **buffer/pipe** that absorbs the search-log firehose (producer-consumer). Search servers just *append* each query-logged event to Kafka and move on; the aggregator consumes at its own pace. Same role as in any log-ingestion system — decouple the fast producers from the slower batch consumer.

---

## 8. Ranking, Personalization & Fuzzy Matching

| Feature | Approach |
| --- | --- |
| **Popularity ranking** | Query frequency with time decay (trends) |
| **Personalization** | Blend a per-user layer (your history) with the global trie at read time |
| **Typo tolerance** | Edit-distance (Levenshtein) matching, or index common misspellings; often a fuzzy layer alongside the trie |
| **Context** | Location/language-specific tries; recent-query boosting |
| **Freshness** | Newly trending terms enter on the next rebuild (eventual) |

- Fuzzy matching is expensive on a plain trie → often a separate **spell-correction / n-gram** stage, or a small edit-distance search over the trie.

### Ranking by popularity (with a twist for you)

The **global** trie ranks purely by crowd popularity + recency (§7). But Google's box often shows *you* something different — because it mixes in **your** history. That's **personalization**: blend a small per-user layer with the global list at read time.

```java
// Global list from the shared trie + a small per-user boost, merged when you query.
List<Suggestion> personalized(String prefix, String userId) {
    List<Suggestion> global = trie.complete(prefix);              // crowd's top-k for "net"
    Map<String, Long> mine  = userHistory.recentFor(userId, prefix); // e.g. you often search "netbanking"

    // re-score: global popularity + a bonus for things THIS user searches
    return global.stream()
        .map(s -> new Suggestion(s.text(), s.score() + 5 * mine.getOrDefault(s.text(), 0L)))
        .sorted((a, b) -> Long.compare(b.score(), a.score()))
        .limit(K)
        .toList();
    // (real systems may also inject the user's frequent queries that aren't in the global top-k)
}
```

Key point: **we don't build a whole trie per user** (billions of them — impossible). We keep one shared global trie and layer a *small* per-user signal on top at read time. Personalization is a thin blend, not a separate index.

### Typo tolerance (fuzzy matching)

A plain trie is **exact** — type `netfli`, walk `n→e→t→f→l→i`, done. But type `nteflix` (swapped letters) and the walk dies at `nt` → zero suggestions. Humans mistype constantly, so we need **fuzzy** matching.

Why it's hard on a trie: to allow "off by one or two letters" you can't follow a single path — you'd have to explore *many* branches (what if they meant `e` not `t`? inserted a letter? dropped one?). That branching is expensive on the hot read path.

Common approaches (usually a **separate stage**, not the main trie walk):

| Approach | Idea |
| --- | --- |
| **Edit distance (Levenshtein)** | Find dictionary words within 1–2 edits of what was typed; a bounded fuzzy walk over the trie |
| **Precomputed misspellings** | Index common typos → correct query (`gooogle` → `google`) so the fix is a lookup |
| **n-gram / spell-correction service** | A dedicated component suggests a corrected query, then autocomplete runs on the correction |

```java
// Sketch: if the exact walk returns nothing, fall back to a bounded fuzzy search.
List<Suggestion> completeFuzzy(String prefix) {
    List<Suggestion> exact = trie.complete(prefix);
    if (!exact.isEmpty()) return exact;                 // common case: exact match, cheap

    String corrected = spellCorrector.fix(prefix);      // "nteflix" -> "netflix" (separate stage)
    return trie.complete(corrected);
}
```

Point: keep the **fast exact path fast**, and only pay for fuzzy matching when the exact lookup finds nothing.

#### Q: Why not just make the main trie fuzzy so every lookup tolerates typos?

Because every lookup would then explore many branches instead of one path — turning an O(prefix) walk into something far more expensive, on the millions-per-second hot path. We keep the hot path exact and cheap, and treat fuzzy matching as a rarer fallback / separate service.

---

## 9. Sharding & Memory

```
Shard the trie by prefix (first 1–2 chars):  a-c → node1, d-f → node2, ...
  each shard = a subtree held in memory; replicate each shard for QPS + HA
```

- **Skew:** some prefixes are far more popular (`"a"`, `"the"`) → finer sharding or more replicas for hot shards.
- **Memory:** store only **top-k per node** (not full descendant lists); compress with radix/DAWG; keep the serving set to popular queries (long tail can DFS on miss or be omitted).

### The trie is too big for one machine

Hundreds of millions of distinct queries → **billions of trie nodes** → many gigabytes. That won't fit (comfortably) in one server's RAM, and one server couldn't handle the QPS anyway. So we **split the trie across machines by prefix**.

Split by the first letter(s) of the prefix: prefixes `a–c` on node 1, `d–f` on node 2, etc. A request for `net...` goes straight to the node that owns `n`.

```
Shard by first 1–2 characters of the prefix:
   prefixes "a".."c"  → shard 1   (holds that subtree of the trie in RAM)
   prefixes "d".."f"  → shard 2
   prefixes "ne.."    → shard N
Each shard is replicated (copies) for QPS + high availability.
```

Routing a request is just "look at the first letters, send it to the owning shard":

```java
// The gateway picks the shard from the prefix, then that shard walks its in-memory subtree.
String shardFor(String prefix) {
    char first = prefix.charAt(0);
    if (first <= 'c') return "shard-1";
    if (first <= 'f') return "shard-2";
    // ... routing table, or consistent hashing on the prefix
    return "shard-N";
}
```

#### Q: Why shard by *prefix* specifically, and not randomly?

Because a single autocomplete request only cares about **one** prefix (`net`), and everything it needs (`net`, `netf`, `netfl`, ...) lives under the **same branch**. Shard by prefix and the whole answer is on **one** shard — no fan-out, no combining results from multiple machines. Random sharding would scatter one prefix's data everywhere and force every request to hit many shards.

### The skew problem (hot prefixes)

Sharding by prefix creates an uneven load: tons of people type `a`, `the`, `you`; almost nobody types `qz`. So the shard owning popular prefixes gets **hammered** while others idle — the **hot key / skew** problem.

Fixes:

- **More replicas for hot shards** — give the `a`/`the` shard 20 copies behind a load balancer, the `qz` shard just 1–2.
- **Finer sharding of hot ranges** — split a hot first-letter into deeper buckets (`a` → `aa`, `ab`, `ac`, ...) so its load spreads across more machines.
- **Cache the hot prefixes** (§6) — the busiest prefixes are served from Redis/CDN, taking pressure off the shard entirely.

### Shrinking memory (radix / DAWG)

Two big memory savers beyond "top-k only":

- **Radix tree (Patricia trie)** — collapse long single-child chains into one node. Instead of separate nodes for `n → e → t → f → l → i → x`, store the run `netflix` on one edge. Fewer node objects → less overhead.

```
plain trie:   (n)-(e)-(t)-(f)-(l)-(i)-(x)      ← 7 nodes for a unique tail
radix trie:   (net)---"flix"--->(netflix)       ← the unbranching run is one edge
```

- **DAWG (Directed Acyclic Word Graph)** — words often share **suffixes** too (`...tion`, `...ing`). A DAWG lets different branches **share** the same suffix subtree instead of duplicating it, squeezing memory further.

And the simplest lever of all, restated: **store only the top-k at each node, never the full descendant list.** The dropdown shows ~10; keeping thousands per node would multiply memory for suggestions nobody sees. The rare "show more" falls back to a subtree scan or is simply omitted (the long tail).

#### Q: If we only keep top-k per node and drop the long tail, do we lose rare queries?

Mostly that's fine — autocomplete exists to speed up *common* queries; obscure ones the user can just type fully. If needed, rare completions can be recovered by a fallback subtree scan on a cache miss, or served from a secondary (slower) store. The serving trie deliberately optimizes for the popular head.

---

## 10. Data Model / Stores

```sql
-- Offline aggregation (warehouse / KV)
CREATE TABLE query_frequency ( query TEXT PRIMARY KEY, count BIGINT, decayed_score DOUBLE PRECISION, last_updated TIMESTAMP );

-- Serving structure is the TRIE (in-memory), persisted as versioned snapshots:
--   trie_snapshot(version, shard, serialized_trie)   → loaded by suggestion nodes
--   Redis: cache:autocomplete:{prefix} = [top-k]     (hot prefixes)
```

> **Stores to consider:** raw search logs (Kafka + warehouse/lake), `query_frequency` aggregate, versioned trie snapshots (blob), Redis cache for hot prefixes, optional per-user history store for personalization.

### Which store does which job?

The confusing part is that there are **several** stores, each for a different phase. Map them to the two paths (§4):

| Store | Path | What it holds | Why |
| --- | --- | --- | --- |
| **Kafka + warehouse/lake** (raw logs) | write | every search event, untouched | source of truth to (re)build rankings from |
| **`query_frequency`** table | write | each query → decayed score | the aggregated popularity the builder reads |
| **Trie snapshot** (blob, versioned) | bridge | the serialized ranked trie, e.g. `v42` | the artifact the build produces + serving loads |
| **In-memory trie** (on suggestion nodes) | read | the live trie being queried | the actual millisecond lookups |
| **Redis** (`cache:autocomplete:{prefix}`) | read | top-k for hot prefixes | skip the trie walk for the busiest prefixes |
| **Per-user history** (optional) | read | your recent searches | blended in for personalization (§8) |

The flow of a query's data: **raw log → frequency table → trie snapshot → in-memory trie (+ Redis cache) → your dropdown.**

#### Q: Why is the trie stored as a "versioned snapshot" blob and not in a database?

Because the serving trie is **rebuilt whole and swapped atomically** (§7). Versioning (`v41`, `v42`) means: build the new one as a fresh blob, have suggestion nodes download and load it, then flip the pointer. If `v42` turns out bad, you can roll back to `v41` instantly. A big immutable file that you load into RAM fits this "build-once, read-many, swap-atomically" pattern far better than row-by-row DB updates.

#### Q: Where does the `query_frequency` table live — is it OLTP or a warehouse?

It's an **analytics/aggregate** store, produced by the offline pipeline over billions of log lines — think a data warehouse or a big KV table, not your app's transactional DB. The serving path never touches it directly; it only reads the **trie snapshot** built from it. (Same read/write split as everything else here.)

---

## 11. API Design

```
GET /v1/autocomplete?q=net&limit=10&lang=en   → { suggestions: ["netflix","network",...] }
# query logging happens via the search pipeline (not a per-keystroke write)
```

---

## 12. Sequences

### Serve

```
Client (debounced) → Gateway → shard(prefix) → in-memory trie walk to node → return top-k
   (hot prefix → Redis/edge cache hit, skip the walk)
```

### Rebuild + swap

```
Search logs → Kafka → Aggregator (freq + decay) → Trie Builder → snapshot(vN) → blob
Suggestion nodes: load vN in background → when ready, ATOMIC SWAP pointer old→new → serve vN
```

---

## 13. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Trie (prefix tree)** | Core data structure | Prefix lookups |
| **Strategy** | Ranking (popularity/recency/personalized), matching (exact/fuzzy) | Swap algorithms |
| **Producer-Consumer** | Search-log aggregation via Kafka | Absorb the log firehose |
| **Immutable snapshot + atomic swap** | Rebuild trie offline, hot-swap serving copy | Safe updates, no read locks |
| **Cache-Aside** | Hot-prefix response cache | Latency |
| **CQRS** | Offline build (write) vs in-memory serve (read) | Separate paths |
| **Sharding (by prefix)** | Distribute the trie | Scale memory + QPS |
| **Facade** | Autocomplete service over shards + cache | Simple API |

---

## 14. Scaling & Failure

- **Shard trie by prefix**; replicate each shard for QPS + HA; extra replicas for hot prefixes (skew).
- **In-memory serving** + Redis/CDN for hottest prefixes; **client debounce** cuts QPS.
- **Offline pipeline** rebuilds rankings; **atomic swap** the new trie (no downtime).
- **Node failure** → replicas serve; a new node loads the latest snapshot.
- **Eventual consistency:** trending terms appear after the next rebuild — acceptable.

---

## 15. Interview Cheat Sheet

> **"How do you return suggestions in milliseconds?"**
> "A **trie with top-k precomputed and cached at each node** — a query walks to the prefix node (O(prefix length)) and returns the list, no subtree scan. It's served in-memory, with hot prefixes cached in Redis/edge and client-side debounce."

> **"How are rankings kept fresh?"**
> "Search logs → Kafka → a batch/stream job aggregates query frequencies with **time decay**, recomputes top-k per prefix, builds a **new immutable trie**, and **atomically swaps** it into the serving nodes."

> **"How do you scale memory/QPS?"**
> "**Shard the trie by prefix**, replicate shards (more for hot prefixes), hold in memory, compress with radix/DAWG, store only top-k per node, and cache hot prefixes. Debounce on the client."

> **"Typo tolerance / personalization?"**
> "Fuzzy matching via edit distance / a spell-correction stage; personalization by blending a per-user history layer with the global trie at read time."

---

## 16. Final Takeaways

- **Trie with precomputed top-k per node** = O(prefix) millisecond lookups (no subtree scan).
- **Offline pipeline** (logs → Kafka → aggregate w/ decay → rebuild → **atomic swap**) keeps rankings fresh.
- **Shard by prefix**, replicate (extra for hot), in-memory, compress (radix/DAWG), cache hot prefixes, debounce client.
- **Eventual consistency** of rankings is fine; **CQRS** separates build from serve.
- Patterns: Trie, Strategy (ranking/fuzzy), Producer-Consumer, Immutable snapshot + atomic swap, Cache-Aside, Sharding.

### Related notes

- [Databases — Deep Dive](../concepts/databases-deep-dive.md) (Elasticsearch/search) · [Caching Strategies](../concepts/caching-strategies.md) · [Apache Kafka](../concepts/kafka.md) · [Consistent Hashing](../concepts/consistent-hashing.md)
