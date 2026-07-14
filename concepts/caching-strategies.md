# Caching Strategies

> **In one line:** caching trades **freshness for speed** by keeping hot data close to the consumer. The hard parts are **which read/write pattern**, **eviction**, and **invalidation** ("the second hardest problem in CS").

> **How to read this doc:** dense summary first, then a **deep dive**. Each section starts with the compact reference (tables, pseudo-code) for quick revision, then a deep dive with annotated code and Q&A for the confusions that trip up beginners. Skim the summaries to review; read the deep dives to actually understand.

---

## Contents

- [1. Why Cache?](#1-why-cache)
- [2. Read Strategies](#2-read-strategies)
- [3. Write Strategies](#3-write-strategies)
- [4. Eviction Policies](#4-eviction-policies)
- [5. Invalidation (the hard part)](#5-invalidation-the-hard-part)
- [6. Cache Problems & Fixes](#6-cache-problems--fixes)
- [Cache Warming (Pre-population)](#cache-warming-pre-population)
- [7. Consistency Note](#7-consistency-note)
- [When NOT to Cache](#when-not-to-cache)
- [8. Interview Cheat Sheet](#8-interview-cheat-sheet)
- [9. Final Takeaways](#9-final-takeaways)

---

## 1. Why Cache?

- **Lower latency** — RAM (Redis) ≫ faster than disk/DB.
- **Reduce DB load** — absorb read traffic so the DB survives.
- **Higher throughput** — serve more with the same backend.

> Cache the **read-heavy, expensive-to-compute, rarely-changing** data.

### Where caches live

| Layer | Example |
| --- | --- |
| Client | browser cache, app memory |
| CDN / edge | static assets, images |
| Application | local in-process cache (Caffeine, Guava) |
| Distributed | **Redis / Memcached** |
| Database | query cache, buffer pool |

### The cache hierarchy (multi-level)

These layers aren't alternatives — real systems stack them. A request falls through each level until something answers, and each level shields the one below it:

```
browser cache        ← per-user; avoids the network entirely (0 ms)
   ↓ miss
CDN / edge           ← shared static assets, close to the user (images, JS, video)
   ↓ miss
local / in-process   ← per-app-server RAM (Caffeine/Guava); nanoseconds, no network
   ↓ miss
Redis (distributed)  ← shared across all app servers; ~1 ms over the network
   ↓ miss
database             ← source of truth; slowest, must be protected
```

Each hop up the stack is faster but smaller and less shared; each hop down is slower but more authoritative. The higher a hit lands, the less load reaches the DB.

> 💡 **CDN vs Redis — different jobs, not competitors.** A **CDN** caches *static, public, byte-identical* content (images, video, JS/CSS) at edge locations physically **close to the user** to cut network distance. **Redis** caches *dynamic, private, application* data (a user's cart, a computed feed) **close to your servers** to cut DB load. CDN optimizes geography; Redis optimizes recomputation. You typically use both.

### What a cache really is

The **database** holds *everything*, but it's slow to reach (disk, possibly over the network). A **cache** holds only the data you access most often, in fast storage (RAM) close to whoever is asking. It's small, so you don't put the entire dataset in it — you keep the **hot, frequently-used** data there and go to the database only for the rest.

That's the whole idea: a cache is a small, fast copy of the data you touch most often, sitting closer to the consumer.

```
without cache:   request → database (slow: disk seek, maybe over the network) → answer
with cache:      request → cache (fast: RAM)  → answer         ← "cache hit"
                 request → cache MISS → database → put copy in cache → answer  ← "cache miss"
```

- **Cache hit** = the data was in the cache. Fast.
- **Cache miss** = the data wasn't cached, so you read from the DB and leave a copy in the cache for next time.
- **Hit ratio** = fraction of requests that were hits. A cache is only worth it if this is high — which is why you cache **read-heavy, expensive-to-compute, rarely-changing** data (see the one-liner above).

#### Q: If RAM is so much faster, why not put *everything* in the cache?

Because RAM is **small and expensive** compared to disk. Your database might hold terabytes; your Redis instance holds gigabytes. It can't fit the whole dataset. So you keep only what pays off — the data that's asked for over and over. Rarely-read data would just take up space (and get evicted anyway — see §4).

#### Q: Is a cache the same thing as a database?

No — and the difference is the mindset. A database is the **source of truth**: if it says your balance is ₹500, that's the truth. A cache is a **disposable, best-effort copy**: if it vanishes, you lose nothing permanent — you just refill it from the DB (a few slow requests). Never treat the cache as the place data *lives*; treat it as a fast copy of data that lives in the DB. This single idea explains most of the rules later in this doc.

---

## 2. Read Strategies

### Cache-Aside (lazy loading) — most common

- **App manages the cache.** Cache only holds what's actually requested.
- ✅ Resilient (cache down → still hit DB), simple.
- ❌ First request is a miss; cache can go stale; **thundering herd** on miss.

### Read-Through

> The **cache library** loads from the DB on a miss (app only talks to the cache).

- ✅ App code is simpler.
- ❌ Needs cache provider support; still has cold-miss latency.

### Cache-aside vs read-through

Both load data into the cache on a miss; the difference is **who owns that logic**.

- **Cache-aside** = the **application** manages the cache. It checks the cache; on a miss, the app reads from the DB and writes the value back into the cache. The cache itself knows nothing about the DB — the app wires the two together.
- **Read-through** = the **cache library** manages loading. The app only ever asks the cache; on a miss, the cache itself fetches from the DB, stores the value, and returns it. The app never talks to the DB directly.

Same end result; the difference is **who owns the "on miss, load from DB" logic** — your app code (cache-aside) or the cache layer itself (read-through).

Cache-aside, annotated:

```java
Object read(String key) {
    Object v = cache.get(key);          // 1. check the cache
    if (v == null) {                    // 2. cache MISS
        v = db.get(key);                //    read from the DB
        cache.set(key, v, TTL_SECONDS); //    store a copy in the cache, with an expiry (TTL)
    }
    return v;                           // 3. hit or freshly-loaded → return it
}
```

Read-through — notice your app never mentions the DB:

```java
Object read(String key) {
    // the cache library is configured with a "loader" that knows how to fetch from the DB.
    // on a miss it calls that loader itself, stores the result, and returns it.
    return cache.get(key);   // that's it — the cache library handles the miss internally
}
```

#### Q: Why is cache-aside called "lazy loading"?

Because data is loaded **only when someone actually asks for it** — never in advance. The cache stays empty until the first request for a key pulls it in ("populate on miss"). Lazy = "don't do work until forced to." The upside: the cache only ever holds data that's genuinely being used. The downside: the **first** request for any key is always a miss (a cold start).

#### Q: What's the very first read after the cache is empty (a "cold cache")?

Always a miss → it hits the DB. If a *lot* of requests for the same key arrive during that cold window, they *all* miss at once and stampede the DB — that's the **thundering herd** problem (§6.1). This is the main risk of cache-aside, and why it pairs with single-flight locking.

#### Q: Cache-aside is "resilient" — what does that mean?

If the cache (Redis) goes **down**, cache-aside code still works: every `cache.get` just returns null (a miss), so you fall through to the DB. Slower, but the app keeps serving. Read-through can be more fragile here because the app has no DB path of its own — it depends entirely on the cache layer being up.

---

## 3. Write Strategies

### Write-Through

- ✅ Cache always consistent with DB.
- ❌ Slower writes (two hops); caches data that may never be read.

### Write-Back (write-behind)

- ✅ **Fast writes**, absorbs bursts.
- ❌ **Risk of data loss** if cache dies before flush; more complex.

### Write-Around

> Write goes **straight to the DB**, skipping the cache (cache filled on later reads via cache-aside).

- ✅ Avoids caching write-once data.
- ❌ Recently written data is a cache miss.

| Strategy | Write path | Consistency | Risk |
| --- | --- | --- | --- |
| Write-through | cache + DB (sync) | strong | slower writes |
| Write-back | cache → async DB | weak (until flush) | data loss |
| Write-around | DB only | strong | read miss after write |

### The three ways to handle a write

When data changes, where do you write it — the cache, the DB, or both?

- **Write-through** = write the cache **and** the DB synchronously, before returning. Both always agree. Slower (two writes every time), but nothing is ever out of sync, and you never lose the write.
- **Write-back (write-behind)** = write only the cache now, and flush to the DB **later** (batched). Very fast — but if the cache crashes before the flush, that write is **lost**.
- **Write-around** = skip the cache entirely; write straight to the DB. The cache is only populated later, the next time that item is *read*. Good for data you write once and rarely read back.

Write-through, annotated:

```java
void write(String key, Object v) {
    cache.set(key, v);   // 1. update the cache
    db.set(key, v);      // 2. update the DB — SYNCHRONOUSLY, before returning
    // both are now consistent; caller waited for both
}
```

Write-back — fast, but note the risk window:

```java
void write(String key, Object v) {
    cache.set(key, v);          // 1. update cache immediately and return to the caller (fast!)
    writeQueue.enqueue(key, v); // 2. remember to flush to the DB later
    // a background worker drains the queue every so often:
    //     for (entry : writeQueue.drainBatch()) db.set(entry.key, entry.v);
    // ⚠ if the cache dies AFTER step 1 but BEFORE the flush, that write is lost.
}
```

Write-around — the write never touches the cache:

```java
void write(String key, Object v) {
    db.set(key, v);        // straight to the DB; cache is not written
    cache.delete(key);     // (optional but smart) drop any stale copy so the next read reloads fresh
}
```

#### Q: Cache-aside vs write-through — aren't these the same category?

Common confusion. They answer **different questions**:

- **Cache-aside / read-through** are **read** strategies — "how do I *fetch* data?"
- **Write-through / write-back / write-around** are **write** strategies — "how do I *save* a change?"

You combine one of each. A very common pairing is **cache-aside for reads + write-around (or delete-on-write) for writes**: reads populate the cache lazily, and a write just updates the DB and *deletes* the cached key so the next read reloads a fresh copy. (Why *delete* rather than *update* the key? See §5.)

#### Q: Why would anyone risk write-back's data loss?

For **write-heavy bursts** where speed matters more than durability of the last few seconds — e.g. counters, metrics, "last seen" timestamps, game state. Losing a few seconds of view-counts on a crash is survivable; losing a payment is not. **Rule: never write-back money or anything you can't recompute.** For those, use write-through (or keep the DB authoritative, see §7).

#### Q: Does write-through mean reads are always fast?

Not necessarily — write-through keeps the cache **consistent**, but it caches *everything you write*, including data nobody ever reads back. That wastes cache space. If most written data is rarely read, write-**around** is better (don't pollute the cache with write-once data; let reads pull in only what's actually needed).

#### Q: Which write strategy do I actually pick?

A quick decision guide — match the strategy to what the data *is*:

- **Write-around / delete-on-write** — the **default** for most app data (profiles, listings, config). Update the DB, drop the cached key, let the next read reload. Simple, safe, no cache pollution.
- **Write-through** — when you need the cache and DB to **never disagree** *and* the written data is read back soon (session state, a hot record updated then immediately re-read). You pay a second synchronous write for consistency.
- **Write-back** — only for **write-heavy, loss-tolerant** data where throughput beats durability (counters, metrics, "last seen"). Never for money or anything you can't recompute.

> 💡 **The default pattern (use this unless you have a reason not to):** **cache-aside reads + TTL + delete-on-write**, plus **single-flight** on hot keys. Reads populate lazily and stay resilient if Redis blinks; the TTL bounds staleness as a safety net; deleting (not updating) the key on write avoids race conditions (§5); and single-flight (§6.1) stops a hot key's expiry from stampeding the DB. Reach for write-through/write-back only when this default doesn't fit.

---

## 4. Eviction Policies

> Cache RAM is finite → must evict when full.

| Policy | Evicts | Good for |
| --- | --- | --- |
| **LRU** (Least Recently Used) | oldest-accessed | general purpose (default) |
| **LFU** (Least Frequently Used) | least-accessed | stable hot sets |
| **FIFO** | oldest-inserted | simple, rarely ideal |
| **TTL** | expired entries | time-bounded data |
| **Random** | random | cheap, surprisingly OK |

> Redis supports `maxmemory-policy` (e.g. `allkeys-lru`, `volatile-lru`).

### Eviction vs expiry (they're different!)

The cache is full and a new item needs to go in. Something has to be removed. **Eviction** = "the cache is full, remove something **now** to fit the new item." That's a *space* problem. **Expiry (TTL)** = "this entry has a time limit; remove it once it's past, full cache or not." That's a *time* problem. They often work together but solve different things.

- **Eviction policy** answers: *when I'm out of room, WHICH item do I remove?*
- **TTL** answers: *how long is any item allowed to live before it's considered stale?*

> 💡 **One-liner to keep them straight:** eviction is triggered by **pressure** (the cache is full); expiry is triggered by **time** (the clock ran out). An item can be evicted long before its TTL, and an item can expire with tons of free space left. You usually want both on.

**LRU (Least Recently Used)** — the default — evicts whatever you **touched longest ago**, betting that "recently used = likely to be used again":

```
capacity = 3
get(A), get(B), get(C)        # cache: [A, B, C]   (C most recent, A least)
get(A)                        # cache: [B, C, A]   (touching A moves it to "most recent")
put(D)                        # FULL → evict least-recently-used = B
                              # cache: [C, A, D]
```

**LFU (Least Frequently Used)** instead evicts the item with the **fewest total hits** — better when a stable set of keys is always hot and you don't want a one-off burst to push them out.

#### Q: LRU vs LFU — when do I pick which?

- **LRU** — general purpose, and what you want when access patterns **shift over time** (today's hot items differ from yesterday's). Cheap to implement, rarely a bad choice — hence the default.
- **LFU** — when there's a **stable, long-lived hot set** (e.g. the top 100 products everyone always views). LRU can accidentally evict a perennial favorite during a temporary flood of new keys; LFU protects it because its hit count stays high.

#### Q: If I set a TTL, do I even need an eviction policy?

Yes — they cover different failure modes. TTL bounds **staleness** (data won't be older than N seconds). Eviction bounds **memory** (cache won't exceed its size). A cache with TTL but no eviction policy can still fill up *before* things expire — then Redis, depending on `maxmemory-policy`, either evicts anyway or starts **rejecting writes**. You typically want both: TTL for freshness, an eviction policy for the "we're full" case.

#### Q: What do `allkeys-lru` and `volatile-lru` mean in Redis?

- **`allkeys-lru`** — when full, LRU-evict from **all** keys, even ones with no TTL. Good for a pure cache.
- **`volatile-lru`** — only evict keys that have a **TTL set** (the "volatile" ones); never touch keys meant to be permanent. Good when your Redis mixes cache data with data you want to keep. (`volatile-*` refuses new writes if there's nothing evictable, so beware.)

---

## 5. Invalidation (the hard part)

> "There are only two hard things in CS: cache invalidation and naming things."

Ways to keep cache from serving stale data:

- **TTL / expiry** — simplest; accept bounded staleness.
- **Write-through / explicit delete on write** — update or `DEL` the key when the source changes.
- **Event-driven invalidation** — publish a change event (e.g. via Kafka/CDC) → consumers evict.
- **Versioned keys** — embed a version in the key (`user:123:v2`); bump version instead of deleting.

> **Common pattern:** on update, **delete** the key (don't update it) → next read repopulates via cache-aside. Avoids race conditions between concurrent writers.

> ⚠️ **Order matters: DB first, then cache.** Always write the source of truth **before** invalidating the cache. If you delete the cache *first*, a concurrent read can miss, reload the **old** DB value, and re-cache it — right before your DB write lands — leaving a stale entry that no one will fix until the TTL. Update the DB, *then* delete the key.

### Keeping the copy honest

The DB holds the current value; the cache holds a copy. When the DB value changes, the cached copy is now **stale** until it's updated or removed. Invalidation is the discipline of keeping cached copies from serving stale data — and it's famously hard because you must catch *every* cached copy, every time the underlying data changes.

Four approaches, from laziest to most precise:

```java
// 1. TTL — the entry auto-expires. Simplest. Accepts "stale for at most N seconds."
cache.set("price:123", 499, /* ttl */ 60);   // re-checked at least once a minute

// 2. Delete-on-write — when the price changes, delete the cached entry.
void updatePrice(long id, int newPrice) {
    db.set("price:" + id, newPrice);   // update source of truth first
    cache.delete("price:" + id);       // then remove stale copy → next read reloads fresh
}

// 3. Event-driven — a change event is published; every cache subscribes and deletes the entry.
onPriceChangedEvent(id -> cache.delete("price:" + id));   // via Kafka / CDC

// 4. Versioned keys — never edit an entry; write a NEW key and point reads at it.
String key = "price:123:v" + currentVersion(123);   // bump version = instant "invalidate all"
```

#### Q: Why *delete* the key instead of *updating* it on a write?

This is the single most important invalidation tip. If two writers update the same key concurrently and each also *writes* the new value into the cache, their cache writes can **interleave** and land in the wrong order — leaving the cache holding an older value than the DB (a permanent stale copy). If instead each writer just **deletes** the key, the worst case is an extra cache miss: the next reader reloads the current value straight from the DB. Delete is safe under races; update is not.

```
BAD (update the cache):
  Writer1: db=10, then (slow) cache=10
  Writer2: db=20, then       cache=20
  ...but if Writer1's cache write lands LAST → cache=10 while db=20 forever. Stale!

GOOD (delete the cache):
  Both writers just DELETE the key. Next read misses → reloads db=20. Correct.
```

#### Q: TTL vs explicit invalidation — which do I use?

- **TTL** — you're okay with data being a little stale for a bounded time (product listings, profiles, config). Zero coordination; it just expires. This is the default and covers most cases.
- **Explicit delete / event-driven** — you need changes reflected **fast** (near-instant), so you actively evict the moment the DB changes. More moving parts (you must hook every write path or wire up CDC), but low staleness.
- Many systems use **both**: delete-on-write for freshness *plus* a modest TTL as a safety net in case a delete is ever missed.

---

## 6. Cache Problems & Fixes

> Quick reference, then a deep dive on each.

| Problem | What happens | Fix |
| --- | --- | --- |
| **Thundering herd / stampede** | key expires → many requests hit DB at once | lock/single-flight (one loader), early/staggered refresh, jittered TTL |
| **Cache penetration** | queries for non-existent keys always miss → hit DB | cache the "null" result; **bloom filter** |
| **Cache avalanche** | many keys expire simultaneously → DB spike | **randomize TTLs** (add jitter) |
| **Hot key** | one key gets all traffic | local cache layer, replicate the key, shard it |
| **Big key** | one value is huge (MBs) → slow ops, network spikes | split/shard the value, compress, paginate |
| **Stale data** | cache out of sync with DB | TTL + invalidate on write |

---

### 6.1 Thundering Herd / Cache Stampede

**What happens:** A popular ("hot") key expires. Between the moment it expires and the moment it's repopulated, *every* concurrent request misses and slams the DB simultaneously to recompute the same value. The DB sees a sudden spike of identical, expensive queries.

```
t=0   key expires
t=0+  1000 requests arrive → all miss → all run db.get(key) → DB melts
```

**Fixes:**

- **Single-flight / lock (mutex):** only the *first* request acquires a lock and recomputes; everyone else waits for the result or briefly serves stale.

```
read(key):
    v = cache.get(key)
    if v != null: return v
    if acquire_lock(key):          # only one winner
        v = db.get(key)
        cache.set(key, v, ttl)
        release_lock(key)
    else:
        wait_or_serve_stale()      # others don't hit the DB
    return v
```

- **Early / probabilistic refresh:** refresh the value *before* it expires (e.g. when TTL is 80% elapsed) so the key is never actually empty. *XFetch* adds randomness so one request refreshes early.
- **Jittered TTL:** spread expiry so not everything refreshes at the same instant.
- **Stale-while-revalidate:** serve the old value while a single background task refreshes it.

---

### 6.2 Cache Penetration

**What happens:** Requests are for keys that **don't exist in the DB at all** (e.g. random/invalid IDs, often malicious). They always miss the cache *and* find nothing in the DB, so the cache never absorbs them — every request hits the DB. The cache provides zero protection.

**Fixes:**

- **Cache the null result:** store a "not found" marker with a short TTL so repeat lookups stop at the cache.

```
v = db.get(key)
cache.set(key, v == null ? NULL_SENTINEL : v, ttl)
```

- **Bloom filter:** a compact probabilistic set of all valid keys. Check it before touching the DB; if the filter says "definitely not present," reject immediately. (Bloom filters can have false positives but never false negatives — so a "no" is always trustworthy.)
- **Input validation / rate limiting:** drop obviously bogus keys at the edge.

---

### 6.3 Cache Avalanche

**What happens:** A large number of keys expire **at the same time** (e.g. they were all loaded together at startup with the same TTL), or the cache itself goes down. Suddenly a huge fraction of traffic falls through to the DB at once and overwhelms it.

> Difference from stampede: stampede = *one* hot key; avalanche = *many* keys expiring together (or whole cache failing).

**Fixes:**

- **Jittered / randomized TTLs:** `ttl = base + random(0, spread)` so expiries are spread over time instead of bunched.
- **High availability for the cache:** replicas / cluster so a single node failure doesn't drop the whole layer.
- **Request throttling + circuit breaker:** cap concurrent DB loads so the DB degrades gracefully instead of collapsing.
- **Multi-level cache:** a local in-process cache in front of Redis absorbs the spike if Redis blinks.

---

### 6.4 Hot Key

**What happens:** A single key (e.g. a celebrity's profile, a flash-sale item) receives a disproportionate share of all traffic. Even though it's cached, the *single* cache node/shard holding it becomes a bottleneck — CPU, network, or connection limits get saturated.

**Fixes:**

- **Local (client-side) cache:** cache the hot key in app memory so most reads never even reach Redis.
- **Replicate the key:** store copies on multiple nodes and read from a random replica to spread load.
- **Key sharding:** split into `key#1 … key#N`; readers pick a random suffix, distributing the load across shards.

---

### 6.5 Big Key

**What happens:** A single cached value is very large (e.g. a multi-MB list, a giant serialized object). Reading or writing it is slow, ties up the cache thread (Redis is single-threaded per command), and causes network bandwidth spikes. One `GET` can stall other clients.

**Fixes:**

- **Split / shard the value** into smaller chunks under separate keys.
- **Use the right data structure** (e.g. Redis Hash/Set fields, paginated reads) instead of one blob.
- **Compress** the payload before storing.
- **Paginate** large collections rather than caching them whole.

---

### 6.6 Stale Data

**What happens:** The source of truth (DB) changes but the cached copy doesn't, so reads serve outdated values. Inherent to caching — the question is how much staleness you tolerate.

**Fixes:**

- **TTL** for bounded staleness (accept "at most N seconds old").
- **Invalidate on write:** delete (preferred) or update the key when the DB changes — see [§5](#5-invalidation-the-hard-part).
- **Event-driven invalidation** (CDC/Kafka) for fast, system-wide eviction.
- Prefer **delete over update** to avoid races between concurrent writers.

---

### Stampede vs avalanche vs penetration (the disambiguation)

These three cache failures all look similar (a sudden spike of DB load) but have **different causes and fixes** — mixing them up means applying the wrong fix:

| Problem | What happens | Root cause |
| --- | --- | --- |
| **Thundering herd / stampede** | one hot key expires; a flood of requests all recompute it at once | **one** hot key expired |
| **Avalanche** | many keys expire at the same moment → broad miss surge | **many** keys expire together (or cache dies) |
| **Penetration** | requests for keys that don't exist anywhere always reach the DB | requests for keys absent from cache *and* DB |
| **Hot key** | one key receives a disproportionate share of all traffic | one key gets a disproportionate share of traffic |
| **Big key** | one cached value is huge; reading it blocks other operations | a single cached value is huge |

> ⚠️ **Common mistakes to avoid here:** (1) Reaching for a **single-flight lock** during an **avalanche** — a lock only helps when many requests want the *same* key (stampede); an avalanche is *many different* keys, so the fix is **jittered TTLs**, not a lock. (2) Trying to fix **penetration** by caching harder — the key doesn't exist in the DB, so there's nothing to cache; you must cache the *absence* (null sentinel) or reject unknown keys with a **bloom filter**. (3) Calling any DB-load spike a "stampede" — always ask first: *one key or many? does the data even exist?*

#### Q: Stampede vs avalanche — what's the real difference?

**Count of keys.** Stampede = **one** popular key expires and a swarm of requests all recompute *the same value* at once. Avalanche = **many** keys expire at the **same moment** (often because they were all loaded together with an identical TTL), so a broad slice of traffic falls through to the DB simultaneously. Fixes differ: stampede → **single-flight lock** (let one request recompute); avalanche → **jittered TTLs** (spread the expiries out so they don't bunch up).

Single-flight lock (the stampede fix), annotated:

```java
Object read(String key) {
    Object v = cache.get(key);
    if (v != null) return v;                 // hit → done

    // MISS on a hot key. Don't let everyone hit the DB — only one "winner" reloads.
    if (lock.tryAcquire(key)) {              // exactly ONE request wins the lock
        try {
            v = db.get(key);                 // the winner recomputes
            cache.set(key, v, TTL_SECONDS);  // and repopulates the cache
        } finally {
            lock.release(key);
        }
    } else {
        v = waitForCacheOrServeStale(key);   // everyone else waits / serves the old value
    }
    return v;
}
```

Jittered TTL (the avalanche fix) — one line:

```java
// instead of everyone getting exactly 300s, spread expiries across 300–360s
int ttl = 300 + ThreadLocalRandom.current().nextInt(60);
cache.set(key, v, ttl);   // now keys don't all expire at the same instant
```

#### Q: Penetration vs a normal cache miss — aren't all misses fine?

A normal miss is self-healing: you fetch from the DB and **cache the result**, so the *next* request is a hit. **Penetration** is nasty because the key doesn't exist in the DB either — there's nothing to cache, so **every** request misses forever and pounds the DB. Fix by caching the *absence* (a short-TTL "null" sentinel) or rejecting unknown keys up front with a **bloom filter**.

#### Q: "Hot key" appears in eviction and here — same thing?

Same *concept* (one key gets outsized traffic), different *pain*. Here the concern is that even though the key is cached, the **single node holding it** saturates (CPU/network). Fixes: a **local in-process cache** in front of Redis (most reads never leave the app), **replicating** the key across nodes, or **sharding** it into `key#1..key#N` and reading a random shard.

---

## Cache Warming (Pre-population)

Everything above populates the cache **lazily** — the first request for each key is a miss (cache-aside, §2). **Cache warming** flips that: you actively load hot data into the cache *before* real traffic arrives, so users never pay the cold-miss penalty.

**When it matters:**

- **After a deploy or restart** — a fresh cache (or a flushed local cache) is empty. Without warming, the first wave of traffic all misses at once — effectively a self-inflicted **avalanche** (§6.3). A rolling restart makes this worse: each restarted node starts cold.
- **Before a known traffic spike** — a flash sale, ticket on-sale, or product launch where you *know in advance* which keys will be hot. Pre-load them so the first million users hit warm cache instead of stampeding the DB.
- **For a stable, predictable hot set** — the top-N products, trending items, homepage data. A scheduled job refreshes them on a cadence so they're always warm.

**How:** run a startup/scheduled job that reads the known-hot keys straight into the cache (or replay recent access logs to rebuild the working set).

> 💡 **Warm only what you'll actually use.** Warming the *whole* dataset wastes memory and time, and most of it gets evicted before it's read. Warm the **predictably hot** keys (top products, the on-sale show) — for everything else, lazy loading is fine. Pair warming with **jittered TTLs** so the pre-loaded keys don't all expire together later and trigger the very avalanche you were avoiding.

---

## 7. Consistency Note

A cache makes the system **eventually consistent** by nature:

- **Read-heavy + tolerant of staleness** → cache aggressively (browse/catalog).
- **Strong-consistency needs** (money, inventory) → **don't cache the write decision**; the DB stays the source of truth. (e.g. you can cache *availability* for display, but the actual reservation/lock must be decided by the DB.)

### Why a cache means "eventually consistent"

Consider a seat-availability display that reads a cached count refreshed every few seconds, not on every sale. For a moment it can show "available" *after* the last seat actually sold. That's fine for **browsing** (showing an approximate count), but you'd never let that cached value **decide the sale** — the actual reservation goes through the DB, which locks the seat so two people can't buy the same one. The cache is a *display copy*; the DB is the *source of truth*.

That gap — cache says X, DB already moved to Y — is exactly what "**eventually consistent**" means: the copies converge *eventually*, but for a short window they can disagree.

```java
// FINE: cache a display value; being a few seconds stale is harmless.
int seatsLeftForDisplay = cache.getOrLoad("seats:show42");   // "~12 seats" on the page

// NOT FINE: never let the cache decide a money/inventory action.
boolean booked = db.reserveSeatTransactionally(show42, seatId);  // DB locks + decides, atomically
// (you may cache seatsLeft for the UI, but the reservation MUST go through the DB)
```

#### Q: So when is stale data acceptable and when is it dangerous?

- **Acceptable** — read-heavy, tolerant-of-staleness data: catalogs, profiles, article bodies, "number of likes." Being a few seconds behind hurts nobody, and caching aggressively is a huge win.
- **Dangerous** — anything where two actors acting on a stale copy causes a real conflict: **money** (double-spend), **inventory** (overselling the last item), **locks/reservations**. For these, keep the DB authoritative and let it decide the write transactionally. You can still cache the *display* of availability — just not the *decision*.

#### Q: Doesn't invalidation (§5) make the cache strongly consistent?

It gets you *closer*, but not truly strong. Even with delete-on-write there's a tiny window between "DB updated" and "cache entry deleted/reloaded" where a reader can see the old value — and event-driven invalidation adds propagation delay. For display data that's totally fine. For correctness-critical decisions, don't rely on "the cache is probably fresh"; route the decision through the source of truth.

---

## When NOT to Cache

Caching is not free — it adds a moving part, a staleness window, and invalidation work. Sometimes the right answer is **don't cache this**. Skip the cache when:

- **The value decides money or inventory.** Balances, payment authorizations, "is the last seat still free?" — the *decision* must read the source of truth transactionally (§7). You may cache a *display* copy, but never let a cached value authorize a spend or a reservation.
- **The data is highly personalized / per-request.** If almost every request produces a unique result (a per-user search ranked by live context, a one-off computed report), the cache barely ever gets a second hit for the same key — you carry all the cost and get almost no reuse.
- **The hit ratio would be low.** Rarely-repeated keys (long-tail lookups, mostly-unique queries) mean nearly every request misses anyway. You pay the extra hop *and* the DB load, and just add complexity for no speedup.
- **The data changes faster than you read it.** If a value is updated more often than it's served, you spend more effort invalidating than you save on reads — and readers mostly see stale data regardless.
- **The source is already fast enough.** A single indexed primary-key lookup on a healthy DB is often sub-millisecond. Adding Redis in front can *increase* p99 latency (extra network hop + occasional miss). Measure before adding a layer.

> ⚠️ **Caching is not a default "make it faster" button.** Every cache you add is a second copy you now have to keep honest. If the hit ratio is low, the data is correctness-critical, or the DB is already fast, a cache adds latency, bugs, and staleness for little gain. **First fix the query/index; cache only what's measurably read-heavy and reuse-friendly.**

---

## 8. Interview Cheat Sheet

> **"How would you add caching?"**
>
> "Start with **cache-aside + TTL** for read-heavy data. On writes, **invalidate** (delete) the key. For consistency-critical writes, keep the DB authoritative and don't cache the decision."

> **"Cache-aside vs write-through?"**
>
> "Cache-aside loads lazily on a miss and is resilient; write-through writes to cache + DB synchronously for consistency at the cost of write latency. Write-back is fastest but risks data loss."

> **"How do you prevent a cache stampede?"**
>
> "Single-flight locking so only one request repopulates, jittered TTLs, and early refresh. For non-existent keys, cache nulls or use a bloom filter."

> **"How do you invalidate?"**
>
> "TTL for bounded staleness, delete-on-write for freshness, or event-driven invalidation via CDC. Prefer deleting the key over updating it to avoid write races."

---

## 9. Final Takeaways

- **Read:** cache-aside (default) / read-through.
- **Write:** write-through (consistent) / write-back (fast, risky) / write-around (write-once data).
- **Evict** with LRU/LFU + TTL; **invalidate** by deleting keys on write.
- Watch **stampede, penetration, avalanche, hot keys** — fix with locks, bloom filters, **jittered TTLs**.
- Cache = **eventual consistency**; keep the DB authoritative for money/inventory.
