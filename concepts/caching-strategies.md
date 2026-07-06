# Caching Strategies

> **In one line:** caching trades **freshness for speed** by keeping hot data close to the consumer. The hard parts are **which read/write pattern**, **eviction**, and **invalidation** ("the second hardest problem in CS").

---

## Contents

- [1. Why Cache?](#1-why-cache)
- [2. Read Strategies](#2-read-strategies)
- [3. Write Strategies](#3-write-strategies)
- [4. Eviction Policies](#4-eviction-policies)
- [5. Invalidation (the hard part)](#5-invalidation-the-hard-part)
- [6. Cache Problems & Fixes](#6-cache-problems--fixes)
- [7. Consistency Note](#7-consistency-note)
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

---

## 2. Read Strategies

### Cache-Aside (lazy loading) — most common

```
read(key):
    v = cache.get(key)
    if v == null:                 # cache miss
        v = db.get(key)
        cache.set(key, v, ttl)
    return v
```

- **App manages the cache.** Cache only holds what's actually requested.
- ✅ Resilient (cache down → still hit DB), simple.
- ❌ First request is a miss; cache can go stale; **thundering herd** on miss.

### Read-Through

> The **cache library** loads from the DB on a miss (app only talks to the cache).

- ✅ App code is simpler.
- ❌ Needs cache provider support; still has cold-miss latency.

---

## 3. Write Strategies

### Write-Through

```
write(key, v):
    cache.set(key, v)
    db.set(key, v)     # synchronously
```

- ✅ Cache always consistent with DB.
- ❌ Slower writes (two hops); caches data that may never be read.

### Write-Back (write-behind)

```
write(key, v):
    cache.set(key, v)
    queue async flush → db   # later, batched
```

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

---

## 5. Invalidation (the hard part)

> "There are only two hard things in CS: cache invalidation and naming things."

Ways to keep cache from serving stale data:

- **TTL / expiry** — simplest; accept bounded staleness.
- **Write-through / explicit delete on write** — update or `DEL` the key when the source changes.
- **Event-driven invalidation** — publish a change event (e.g. via Kafka/CDC) → consumers evict.
- **Versioned keys** — embed a version in the key (`user:123:v2`); bump version instead of deleting.

> **Common pattern:** on update, **delete** the key (don't update it) → next read repopulates via cache-aside. Avoids race conditions between concurrent writers.

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

## 7. Consistency Note

A cache makes the system **eventually consistent** by nature:

- **Read-heavy + tolerant of staleness** → cache aggressively (browse/catalog).
- **Strong-consistency needs** (money, inventory) → **don't cache the write decision**; the DB stays the source of truth. (e.g. you can cache *availability* for display, but the actual reservation/lock must be decided by the DB.)

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
