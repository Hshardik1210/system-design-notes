# Distributed Cache — System Design (Redis / Memcached-like)

> **Core challenge:** build an in-memory key-value cache spanning **many nodes**, serving reads/writes in **sub-millisecond** time, **sharding with minimal reshuffling** on scaling, **evicting** intelligently under memory pressure, and optionally **replicating** for availability. The heart is **consistent hashing + eviction + replication + stampede protection**.

> **How to read this doc:** each section has the dense interview summary first, then a **deep dive** (annotated Java/pseudo-code and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand.

---

## Contents

- [1. Mental Model](#1-mental-model)
- [2. Requirements](#2-requirements)
- [3. Capacity Estimation](#3-capacity-estimation)
- [4. Architecture](#4-architecture)
- [5. Single-Node Cache Internals](#5-single-node-cache-internals)
- [6. Sharding with Consistent Hashing](#6-sharding-with-consistent-hashing)
- [7. Eviction Policies](#7-eviction-policies)
- [8. Replication & Availability](#8-replication--availability)
- [9. Consistency & Invalidation](#9-consistency--invalidation)
- [10. Client & Topology (routing)](#10-client--topology-routing)
- [11. Sequences](#11-sequences)
- [12. Failure Scenarios](#12-failure-scenarios)
- [13. Design Patterns (that can be used)](#13-design-patterns-that-can-be-used)
- [14. Interview Cheat Sheet](#14-interview-cheat-sheet)
- [15. Final Takeaways](#15-final-takeaways)

---

## 1. Mental Model

```
GET/SET key → hash the key → route to the node owning that key → in-memory hash map lookup
```

A giant distributed hash map held in **RAM**, partitioned across nodes, with **eviction** (bounded memory) and optional **replication** (HA).

### What problem are we even solving?

Two layers, with very different speeds:

- The **database** (Postgres/MySQL) is your source of truth, but it's on disk, and looking things up takes real work (parse SQL, seek disk, lock rows). Under heavy traffic it gets slow and can fall over.
- The **cache** (Redis/Memcached) lives in **RAM**, so a read is essentially instant. It sits in front of the DB.

The rule the app follows:

> "Need something? **Check the cache first.** If it's there (a **hit**), return it. If it's not (a **miss**), read from the DB, then *store a copy in the cache* so the next read is a hit."

That's the entire idea. Everything else in this doc is answering *"how do we run this cache when it's so big and so busy that one machine can't hold it or keep up?"*

### Why not just use the database directly?

The database is slower and more expensive **per read** than RAM, by a lot:

| | Database (disk/SSD) | Cache (RAM) |
| --- | --- | --- |
| Typical read latency | ~1–10 ms (or worse under load) | ~0.1 ms (sub-millisecond) |
| Cost of a repeated read | Re-does the query every time | Trivial map lookup |
| Under a traffic spike | Can saturate, queue, time out | Absorbs millions of ops/sec |

Most real workloads are **read-heavy and skewed** (the classic 80/20): a small set of "hot" items — the trending tweet, the logged-in user's profile, the front-page product — get read over and over. Caching those hot items means the database only sees the *occasional* miss instead of the full firehose.

> **Key insight that drives the whole design:** don't hit the slow DB repeatedly for the same popular items. Keep the hot items in a fast shared RAM layer, and only touch the DB on a miss.

```
Without cache:   every request ─────────────► Database  (slow, overloaded)

With cache:      request ─► Cache ─ hit ─► done (fast)
                              │
                              └─ miss ─► Database ─► copy back into Cache
```

---

## 2. Requirements

**Functional**
- `GET`, `SET`, `DELETE`, `TTL/expire`; optionally richer types (lists, sets, sorted sets, counters).
- Distributed across nodes; scale by adding nodes.

**Non-functional**
- **Sub-millisecond** latency; very high throughput; **bounded memory** (eviction); **highly available** (replication); **minimal data movement** on scaling.

---

## 3. Capacity Estimation

```
Working set: e.g. 1B hot keys × ~1 KB value = ~1 TB → spread across nodes (e.g. ~16 nodes × 64 GB)
QPS: hundreds of thousands to millions/sec → each node handles 100k+ ops/sec (in-memory)
Latency budget: sub-ms → one network hop + a hash-map lookup
Memory is the constraint (RAM is expensive) → eviction + right-sizing the hot set
```

> Cache sizing is the key estimation: **cache the hot working set** (80/20), not the whole dataset. RAM cost drives node count.

### How big should the cache be?

The trap: "let's just cache *everything* in the database." RAM is ~10–100× more expensive per GB than disk, so caching a 1 PB database in RAM is absurd. You don't need to.

**You only cache the hot working set** — the small slice of data that's actually being read right now. The cache physically can't hold the whole dataset, and it doesn't need to. It holds the handful of keys everyone keeps asking for.

- **Working set** = the set of keys read frequently in a given window (say, last hour). Often ~20% of data serves ~80% of reads.
- Size the cache to hold *that*, plus headroom. Anything colder just misses and gets fetched from the DB — that's fine and expected.

```
Total data:        1 PB in the database
Hot working set:   ~1 TB actually being read a lot  → THIS is what we cache
Cache nodes:       ~16 nodes × 64 GB RAM ≈ 1 TB      → holds the hot set
```

#### Q: What's a good hit rate, and why does it matter so much?

**Hit rate** = fraction of reads served by the cache (hits ÷ total). A cache earning its keep usually runs **90%+**. The leverage is huge:

- At 95% hit rate, the DB sees only **5%** of read traffic — a 20× reduction.
- Drop to 90% and the DB load *doubles* (10% vs 5%). So a few percentage points of hit rate is the difference between a calm DB and a melting one. That's why eviction policy (§7) and avoiding mass expiry (§12) matter.

---

## 4. Architecture

```
App ─► (client library / proxy / cluster-aware client) ─► routes key → owning node
                                                          each node: in-memory KV + replicas
Miss → app fetches from the source DB → SET into cache (cache-aside)
Topology: consistent-hash ring (or Redis 16384 slots); replicas per shard; controller/sentinel for failover
```

### Who decides which node holds a key?

One node isn't enough — we have many cache nodes, each holding part of the data. So there's a new question every request must answer: **"which node is this key on?"**

Nobody wants to search all 16 nodes. Instead, the key itself tells you the node via **hashing**: run the key through a hash function, and the result maps to exactly one node. Same key → same node, every time. This is the **router's** job (a client library, a proxy, or the cluster itself — see §10).

```
key "user:123"
   → hash("user:123")            // turns the key into a number
   → maps to node #4 on the ring // deterministic: always node #4
   → GET on node #4
```

The three moving parts of the architecture, in plain terms:

| Piece | Job |
| --- | --- |
| **Router** (client/proxy/cluster) | Turn a key into the one node that owns it |
| **Cache node** | Hold a slice of keys in RAM; answer GET/SET in O(1) |
| **Source DB** | Truth; consulted only on a miss |

#### Q: What is "cache-aside" that the diagram mentions?

It's the most common way the app and cache cooperate: the **app** manages the cache, and the cache sits *aside* the main path (it doesn't talk to the DB itself). On a miss, the app fetches from the DB and back-fills the cache. Full walkthrough with code is in §9 — for now just know "miss → app loads DB → app puts it in cache."

---

## 5. Single-Node Cache Internals

- **In-memory hash table** key→value → **O(1)** get/set.
- **TTL/expiry**: **lazy** (check on access) + **active** (background sampling sweeper) expiration.
- **Eviction** when memory limit reached (see §7) — approximate LRU/LFU by **sampling** (perfect LRU's linked-list bookkeeping is costly).
- **Memory management**: slab allocation (Memcached) to reduce fragmentation; Redis uses jemalloc + efficient encodings for small values.
- **Persistence (Redis, optional)**: **RDB** snapshots + **AOF** append-only log → survive restarts (but a cache is usually rebuildable from the DB).
- **Threading**: Redis = single-threaded command execution (no locks → atomic ops); Memcached = multi-threaded.

### What's inside one cache node?

Zoom into a single cache node. At its core it's just a **hash map (dictionary)** living in RAM: key → value. That's why `GET`/`SET` are **O(1)** — no scanning, you jump straight to the slot.

```java
// The heart of one cache node: a plain in-memory map.
Map<String, byte[]> store = new HashMap<>();

byte[] get(String key) { return store.get(key); }   // O(1)
void   set(String key, byte[] value) { store.put(key, value); }   // O(1)
```

Everything else on the node is bookkeeping around that map: expiry, eviction, and memory management.

#### Q: What's the difference between TTL expiry and eviction? They sound the same.

They're different reasons a key leaves the cache, and it's a very common mix-up:

| | **Expiry (TTL)** | **Eviction** |
| --- | --- | --- |
| Trigger | *Time*: you said "this key lives 60s" | *Memory pressure*: the box is full |
| Which key leaves | The one whose clock ran out | Whichever the policy picks (LRU/LFU — §7) |

#### Q: "Lazy" vs "active" expiration — why both?

Say a key's TTL passed. When does it actually get removed?

- **Lazy**: only when someone next tries to read it. On `GET`, the node checks "is this expired? yes → delete it, return miss." Cheap, but a dead key nobody reads just sits there wasting RAM.
- **Active**: a background sweeper periodically **samples** random keys and deletes expired ones, so dead keys don't accumulate. It samples rather than scanning *everything* because scanning millions of keys would stall the node.

Real Redis does both: lazy on access + a sampling sweeper in the background.

```java
// LAZY: piggyback the check on a normal read
byte[] get(String key) {
    Entry e = store.get(key);
    if (e == null) return null;              // never existed → miss
    if (e.expireAt < now()) {                // TTL passed
        store.remove(key);                   // delete on access
        return null;                         // treat as miss
    }
    return e.value;
}

// ACTIVE: background timer samples a few keys and reaps expired ones
@Scheduled(fixedRate = 100)                  // ~10×/sec
void sweep() {
    for (String key : store.sampleRandomKeys(20)) {  // sample, don't scan all
        Entry e = store.get(key);
        if (e != null && e.expireAt < now()) store.remove(key);
    }
}
```

#### Q: Why is Redis single-threaded — isn't that slower?

Counterintuitively it's a feature. One thread executing commands one at a time means **no locks and no race conditions** — every command is naturally atomic (`INCR` can't interleave with another `INCR`). And because the work is just RAM lookups (no slow disk waits to overlap), a single core already pushes 100k+ ops/sec. Memcached takes the other route (multi-threaded) because its model is simpler. Either way, the app doesn't notice.

---

## 6. Sharding with Consistent Hashing

`hash(key) % N` **breaks** when N changes (almost all keys remap → mass miss → DB stampede). Use **consistent hashing**.

```
Hash ring: nodes + keys hashed onto a ring; a key belongs to the next node clockwise.
Add/remove a node → only keys between it and its neighbor move (≈ 1/N) — not all.
Virtual nodes (vnodes) → each physical node at many ring points → even distribution + smooth rebalance.
```

| Approach | On adding a node |
| --- | --- |
| `hash % N` | ~all keys remap → cache stampede |
| **Consistent hashing + vnodes** ✅ | only ~1/N keys move → smooth |
| **Redis Cluster** | 16384 fixed **hash slots**; each node owns a slot range; move slots to rebalance |

> See the **Consistent Hashing** concept note and the routing comparison in **Databases — Deep Dive** (Redis slots vs Mongo config servers vs Cassandra ring). This is the #1 thing to say.

### Why `hash % N` is a trap

The obvious way to pick a node for a key: number the nodes 0..N-1 and do `node = hash(key) % N`. Works great — until you **add or remove a node**.

The instant `N` changes (say 4 → 5), the `% N` math changes for **almost every key**, so almost every key now maps to a *different* node than before. Every lookup misses. All that traffic slams the database at once — a **cache stampede** that can take the DB down. Adding capacity shouldn't nuke your cache.

```
N = 4:   hash=101 → 101 % 4 = 1  (node 1)
Add one node, N = 5:
         hash=101 → 101 % 5 = 1  (still 1, lucky)
         hash=102 → 102 % 4 = 2  →  102 % 5 = 2   ... but MOST keys shift:
         hash=103 → 103 % 4 = 3  →  103 % 5 = 3
         hash=100 → 100 % 4 = 0  →  100 % 5 = 0
   In practice ~all keys land on a different node → mass miss.
```

### The ring (a clock everyone agrees on)

**Consistent hashing** fixes this. Picture a **clock face** (a ring of positions, say 0 to 2³²-1). Two things get placed on this same clock:

1. **Nodes** — each cache node is hashed to a position on the clock.
2. **Keys** — each key is hashed to a position on the clock.

Rule to find a key's owner: **start at the key's position and walk clockwise; the first node you bump into owns the key.**

```
        node A (12 o'clock)
             ●
   node D ●     ● node B
             ●
        node C (6 o'clock)

key "user:123" lands at ~1 o'clock → walk clockwise → first node is B → B owns it.
```

Now the magic: **add node E** between B and C. The only keys that move are the ones sitting between B and E — they used to walk past to C, now they stop at E. **Everyone else is untouched.** Removing a node is the mirror image: only *its* keys shift to the next node clockwise. Either way ≈ **1/N of keys move**, not all of them.

```java
// A consistent-hash ring. A TreeMap keeps ring positions sorted so we can
// "walk clockwise" cheaply with ceilingKey / firstKey.
class HashRing {
    // ring position -> node name. Sorted by position (that's the "clock").
    private final TreeMap<Long, String> ring = new TreeMap<>();

    void addNode(String node) {
        ring.put(hash(node), node);      // place the node on the clock
    }
    void removeNode(String node) {
        ring.remove(hash(node));         // only this node's arc is affected
    }

    // find which node owns a key: walk CLOCKWISE to the next node.
    String getNode(String key) {
        long pos = hash(key);
        // first ring entry at position >= pos ...
        Map.Entry<Long, String> e = ring.ceilingEntry(pos);
        // ... or wrap around the clock back to the start (12 o'clock).
        if (e == null) e = ring.firstEntry();
        return e.getValue();
    }

    long hash(String s) { /* e.g. MD5/murmur → 64-bit */ return /* ... */ 0; }
}
```

### Virtual nodes (vnodes) — why one point per node isn't enough

Problem with placing each node at a *single* clock position: the gaps between nodes are random, so some nodes end up owning a huge arc (lots of keys) and others a tiny arc. Load is lopsided. And when a node dies, its **entire** arc dumps onto one neighbor.

Fix: give each physical node **many** positions on the clock (e.g. 150 "virtual" points each). Now the keyspace is chopped into many small arcs sprinkled evenly, so:

- **Even distribution** — each physical node owns lots of little arcs that average out.
- **Smooth failure** — when a node dies, its many little arcs spread across *many* neighbors, not one.

```java
void addNode(String node, int vnodes) {
    for (int i = 0; i < vnodes; i++) {
        // same physical node, many ring points: "A#0", "A#1", ...
        ring.put(hash(node + "#" + i), node);
    }
}
```

#### Q: How does Redis Cluster's "16384 hash slots" relate to a ring?

Same idea, discretized for simplicity. Instead of a continuous clock, Redis pre-defines **16384 fixed slots**. A key maps to a slot via `CRC16(key) % 16384`, and each node owns a **range of slots**. Rebalancing = **moving slot ranges** between nodes (and only those keys move). It's consistent hashing with fixed buckets — easier to reason about and administer than a free-form ring.

```
key → CRC16(key) % 16384 = slot 8213
slot 8213 currently owned by node C → key lives on C
add a node → hand it, say, slots 0–4095 from others → only those keys move
```

#### Q: So does this run once at startup, or continuously?

The ring/slot map is **live topology**. Nodes join and leave over time; the mapping is updated and gossiped so everyone agrees on the current owner of each key. Clients cache a copy of the map and refresh it when membership changes (see §10). The point of consistent hashing is that these membership changes are *cheap* — a small slice of keys moves, not the whole cache.

---

## 7. Eviction Policies

When memory is full, evict something:

| Policy | Evicts | Use |
| --- | --- | --- |
| **LRU** | Least recently used | General purpose (temporal locality) |
| **LFU** | Least frequently used | Skewed popularity |
| **FIFO** | Oldest inserted | Simple |
| **Random / sampled-LRU** | Approx LRU by sampling K keys | Redis default (cheap, near-LRU) |
| **TTL-based** | Nearest expiry | Time-bounded data |

- Perfect LRU needs per-access list maintenance (costly) → real systems **sample a few keys** and evict the best candidate (**approximate LRU/LFU**).

### Memory is full — which key gets evicted?

The cache has finite space. When it's full and a new key needs to go in, you must **remove one**. The eviction policy is just *"which one?"* — and the goal is always **pick the key least likely to be needed soon**, so hit rate stays high.

| Policy | Evicts the key that… | Best when |
| --- | --- | --- |
| **LRU** | …hasn't been *read* in the longest time | General use — recently-used stuff tends to be used again |
| **LFU** | …has been read the *fewest times* overall | A few items are permanently popular; one-off spikes shouldn't evict them |
| **FIFO** | …has been cached the longest, regardless of use | Simple, rarely ideal |
| **TTL-based** | …is closest to its expiry time | Data that's time-bounded anyway |

**LRU** ("least recently used") is the workhorse: a key read a second ago is probably needed again; the one untouched for hours is the safe one to remove.

### Why "approximate" LRU (sampling)

Perfect LRU means *always* knowing the exact global order of last-access. That requires moving a key to the front of a linked list on **every single read** — extra bookkeeping on the hottest code path, millions of times a second. Too costly.

So real caches cheat: on eviction, **randomly sample K keys** (Redis default ~5) and evict the "worst" of that little sample. It's not perfectly the least-recently-used key, but it's *almost always* a cold one — and it's O(K) instead of maintaining a global structure. Great trade.

```java
// Approximate LRU: each entry remembers when it was last touched.
class Entry { byte[] value; long lastAccess; long accessCount; }

void onRead(String key) {
    Entry e = store.get(key);
    e.lastAccess = now();       // cheap: one field write, no list surgery
    e.accessCount++;            // (LFU would lean on this counter)
}

// When full, sample K keys and drop the least-recently-used of the sample.
void evictOne() {
    Entry victim = null; String victimKey = null;
    for (String key : store.sampleRandomKeys(5)) {   // K = 5, not the whole map
        Entry e = store.get(key);
        if (victim == null || e.lastAccess < victim.lastAccess) {  // "oldest touch"
            victim = e; victimKey = key;
        }
    }
    store.remove(victimKey);    // make room for the new entry
}
```

For **LFU**, the same loop compares `accessCount` instead of `lastAccess` ("fewest looks" rather than "oldest look").

#### Q: LRU vs LFU — when does the choice actually matter?

Consider a nightly batch job that reads thousands of cold keys once. Under **LRU**, those just-read cold keys look "recently used" and can push out your genuinely popular keys → hit rate tanks. Under **LFU**, the cold keys have a low frequency count, so they get evicted first and the perennial favorites survive. Rule of thumb: **LRU** for general temporal locality, **LFU** when popularity is skewed and stable and you want to resist one-off scans.

---

## 8. Replication & Availability

```
Primary (leader) per shard handles writes → asynchronously replicated to replicas
Replica serves reads / takes over (failover) if the primary dies
```

- **Leader–follower** replication per shard; replicas add **read scaling + HA**.
- **Failover**: a controller/sentinel (Redis Sentinel / Cluster) detects primary death → **promotes a replica**; clients redirected.
- **Async replication** → a tiny window of possible loss on failover — acceptable for a cache (source of truth is the DB).

### Keeping a spare copy of each node

If a cache node dies and its data is gone, all *its* keys instantly become misses → a surge of traffic to the DB for that slice. To avoid that, keep a **copy** of each node's data.

One node is the **primary** (leader) — all writes happen here. Whenever a value changes, the change is copied to the **replica** (follower). If the primary dies, you promote the replica to be the new primary and carry on.

```
             writes
App ────────────────► Primary (shard 1) ──copies──► Replica (shard 1)
                          │                              │
reads can be served by ───┴──────────────────────────────┘
```

- **Read scaling**: replicas can serve reads too, so a hot shard's reads spread across primary + replicas.
- **High availability**: if the primary dies, a controller (Redis **Sentinel** / Cluster) notices and **promotes a replica** to primary, then points clients at it. This is **failover**.

#### Q: What is "async" replication, and why is a little data loss OK here?

- **Sync**: the primary waits for the replica to confirm every write before telling the client "done." Safe, but slower — you pay the network round-trip on every write.
- **Async** (what caches use): the primary replies "done" immediately and copies to the replica **a moment later**. Faster, but if the primary crashes in that tiny gap, the last few writes weren't copied yet — they're lost.

For a **cache**, that loss is acceptable: the database is the source of truth. A lost cache entry just becomes a miss, and the next read re-fetches it from the DB. Losing speed on *every* write to prevent a rare, self-healing loss isn't worth it. (For a *database*, you'd feel very differently — there, sync/quorum matters.)

```java
// Primary, async style: reply fast, replicate in the background.
void set(String key, byte[] value) {
    store.put(key, value);                 // 1. apply locally
    ack();                                 // 2. tell client "done" NOW
    replicationQueue.offer(new Op(key, value));  // 3. copy to replica shortly after
}
```

#### Q: What's "split-brain" on failover?

If the network splits and *both* the old primary and a freshly-promoted replica think they're in charge, they accept conflicting writes → two divergent copies. Controllers prevent this with **quorum** (a majority must agree who's primary) and **fencing** (the old primary is told to stand down). Covered again in §12.

---

## 9. Consistency & Invalidation

- A cache is usually **eventually consistent** with the DB (source of truth).
- **Invalidation on writes**: write-through (write cache+DB together), write-around (write DB, invalidate cache), or **delete-on-update** (see Caching Strategies).
- **Write-back** (write cache, async flush to DB) = fast writes but risk on crash — rarely for a plain cache.
- **Stampede protection**: on a hot miss, **request coalescing (single-flight)** + **randomized TTL**.
- **Hot key**: replicate the hot key to multiple nodes / add a **client-local cache** layer.

### The core tension — the cache can go stale

The cache is a *copy*. The moment the database changes, the cached value might be **wrong** until we fix it. The strategies below are all answers to *"when data changes, how do we keep the cache from serving stale data?"* Each trades off speed, freshness, and complexity.

### The three big write patterns

**Cache-aside (lazy loading)** — the app manages the cache; the cache doesn't know about the DB.

> Read: check the cache; on a miss, read the DB, then store a copy. Write: update the DB, then **delete the stale cache entry**. Next read re-fetches the fresh value.

```java
// CACHE-ASIDE — the most common pattern.
Object read(String key) {
    Object v = cache.get(key);
    if (v != null) return v;              // HIT
    v = db.query(key);                    // MISS → go to the source of truth
    cache.set(key, v, ttl);               // back-fill so next read is a hit
    return v;
}

void write(String key, Object v) {
    db.update(key, v);                    // 1. update the DB (source of truth)
    cache.delete(key);                    // 2. DELETE the stale entry (don't update!)
    //  → next read misses, reloads the fresh value from DB
}
```

Why **delete** rather than update the cache on write? Deleting is simpler and dodges a nasty race: if two writers each *set* the cache, they can interleave and leave a stale value. Deleting just says "this is now unknown — reload it," which is always safe.

**Write-through** — the cache sits *in front* of the DB; every write goes **through** the cache, which writes to the DB synchronously.

> You never write directly to the DB. You write to the cache; the cache updates itself **and** the DB before returning "done." Cache and DB are always in sync — at the cost of a slower write.

```java
// WRITE-THROUGH — write cache + DB together, synchronously.
void write(String key, Object v) {
    cache.set(key, v);                    // update cache
    db.update(key, v);                    // AND the DB, before returning
    // reads are always warm & consistent; writes pay both costs
}
```

**Write-back (write-behind)** — write to the cache only; flush to the DB **later**, asynchronously.

> Write to the cache and return immediately. A background process flushes batches of writes to the DB every so often. Blazing-fast writes, but if the node dies before the flush, those writes are **lost**. Rare for a plain cache; used when write speed dominates and some loss is tolerable.

```java
// WRITE-BACK — fast write now, persist later (risk on crash).
void write(String key, Object v) {
    cache.set(key, v);                    // instant
    dirtyQueue.add(key);                  // remember to flush
    // background flusher writes batches to the DB every N ms
}
```

| Pattern | Write path | Reads | Speed | Risk / cost |
| --- | --- | --- | --- | --- |
| **Cache-aside** | DB, then delete cache | Lazy-loaded on miss | Fast writes | First read after a write is a miss |
| **Write-through** | Cache **and** DB (sync) | Always warm | Slower writes | Every write pays DB latency |
| **Write-back** | Cache now, DB later | Always warm | Fastest writes | Data loss if node dies before flush |

#### Q: Cache-aside vs write-through — which do I pick?

- **Cache-aside** is the default for read-heavy apps: only data that's actually read ever enters the cache (no wasted RAM on write-only keys), and it degrades gracefully if the cache is down. Downside: the read right after a write is a miss, and there's a brief inconsistency window between the DB update and the cache delete.
- **Write-through** keeps the cache always fresh (great when you read what you just wrote), but it wastes RAM on keys that may never be read and makes every write slower. Often people combine write-through with a TTL, or just use cache-aside with delete-on-write.

### Cache stampede / thundering herd

**The scene:** one super-popular key (the front-page item) is cached. Its entry **expires**. In the very next instant, 10,000 requests all ask for it, all miss, and all **stampede the database** with the same query at once. The DB, which was fine serving one query, gets hit with 10,000 identical ones and can fall over. Also called the **thundering herd**.

Two standard defenses:

**1. Request coalescing (single-flight):** when many requests miss the same key at once, let **one** of them go fetch from the DB; the rest **wait** for that single result and share it. One DB query instead of 10,000.

```java
// SINGLE-FLIGHT: collapse concurrent misses on the same key into ONE DB load.
ConcurrentHashMap<String, CompletableFuture<Object>> inflight = new ConcurrentHashMap<>();

Object read(String key) {
    Object v = cache.get(key);
    if (v != null) return v;                       // HIT

    // Only the FIRST caller creates the loader; others get the same future.
    CompletableFuture<Object> f = inflight.computeIfAbsent(key, k ->
        CompletableFuture.supplyAsync(() -> {
            Object loaded = db.query(k);           // exactly ONE DB hit
            cache.set(k, loaded, ttl);
            inflight.remove(k);                    // clear once done
            return loaded;
        })
    );
    return f.join();                               // everyone else waits & shares
}
```

**2. Randomized (jittered) TTL:** if 10,000 keys were all cached at the same moment with the exact same TTL, they'd all expire at the *same* instant → a mass stampede (this is **cache avalanche**). Add a little random jitter to each TTL so expirations spread out.

```java
// JITTERED TTL: base 60s ± up to 10s, so keys don't all expire together.
int ttl = 60 + ThreadLocalRandom.current().nextInt(10);
cache.set(key, value, ttl);
```

#### Q: Stampede vs avalanche vs penetration — what's the difference?

Easy to conflate; they're distinct (see §12 for the table):

- **Stampede / thundering herd** — *one* hot key expires; a crowd all miss it at once. Fix: **single-flight** (the real fix — one loader, the rest wait and share). Plain per-key TTL jitter doesn't help a lone key (there's only one expiry to spread); it only matters once that hot key has been fanned out into multiple copies — see the note below.
- **Avalanche** — *many* keys expire at the *same time* (e.g. all set together), causing a broad miss surge. Fix: **jittered TTLs** (spread the expiries) + staggered warmup. This is jitter's real home: it needs a *population* of keys to spread.
- **Penetration** — requests for keys that **don't exist anywhere** (not in cache, not in DB), so they always miss and always hit the DB. Fix: **negative caching** (cache the "not found") + a **Bloom filter** to reject absent keys before touching the DB.

### TTL — the entry's expiry date

**TTL (time-to-live)** is how long a cached value is allowed to stay before it's considered stale and removed (see lazy/active expiry in §5). It's your main knob for freshness vs load:

- **Short TTL** → data is fresher (the cache re-syncs with the DB often) but more misses → more DB load.
- **Long TTL** → fewer misses, less DB load, but the cache can be stale longer.

For data that changes on writes, pair a reasonable TTL with **delete-on-write** (cache-aside) so you don't rely on the TTL alone to catch changes. TTL is the safety net; the delete is the prompt fix.

### Hot key (one key everyone hammers)

Distinct from a hot *shard*. A **hot key** is a *single* key (one celebrity's profile) so popular that **all** its traffic lands on the one node that owns it — that node melts while others idle. Sharding doesn't help, because one key can't be split across nodes by hashing.

Fixes:

- **Replicate the hot key** to several nodes and spread reads across the copies (e.g. store it under a few salted names `celebrity:42#0..#3` and pick one at random per read).
- **Client-local cache**: each app server keeps a tiny in-process cache of the hottest keys (with a short TTL), so most reads never even reach the cache cluster.

```java
// Spread reads of ONE hot key across R replica copies.
int r = ThreadLocalRandom.current().nextInt(REPLICAS);   // 0..R-1
Object v = cache.get("celebrity:42#" + r);               // different node per read
```

> **Where jitter fits a hot key:** on its own, jittering a single key's TTL does nothing — one key has one expiry. But the moment you apply the fixes above (salted copies `#0..#3`, or per-app-server local caches), that one key becomes *many* physical copies. If they all share the same TTL they'll expire together and re-stampede, so give **each copy a jittered TTL**. The single-key stampede is solved by **single-flight**; jitter only earns its keep once the key is multiplied out.

---

## 10. Client & Topology (routing)

| Topology | How routing works |
| --- | --- |
| **Client-side sharding** | Client library hashes the key → picks the node (Memcached style) |
| **Proxy** | A proxy (Twemproxy/Envoy) routes; clients stay simple |
| **Cluster mode** | Nodes know the ring/slots; **redirect** clients to the right node (Redis Cluster `MOVED`/`ASK`) |

- Clients **cache the topology/ring**; refresh on membership change (gossip / control plane).

### Who holds the "which node has what" map?

Someone has to answer "which node owns this key?" (the ring/slot lookup from §6). There are three places that logic can live:

| Topology | Who does the routing |
| --- | --- |
| **Client-side sharding** | The app's client library hashes the key and connects straight to the right node |
| **Proxy** | A middleman (Twemproxy/Envoy) takes the request and forwards it; clients stay dumb |
| **Cluster mode** | The nodes themselves know the map; if you ask the wrong node it **redirects** you (Redis `MOVED`/`ASK`) |

- **Client-side** = one fewer hop (fastest) but every client must know and refresh the ring.
- **Proxy** = simplest clients, but the proxy is an extra hop and a thing to scale/operate.
- **Cluster mode** = smart nodes, thin clients; the redirect costs an occasional extra round-trip when topology just changed.

#### Q: How do clients avoid getting redirected on every request?

They **cache the topology** (a local copy of the ring/slot map). They route directly using that cached map, so redirects are rare. When membership changes (a node joins/leaves), the map is updated and propagated (via **gossip** between nodes or a control plane); the client refreshes and gets back to direct routing. A `MOVED` redirect is essentially the cluster telling a client "your map is stale — here's the correct node, and please refresh."

---

## 11. Sequences

### Read (cache-aside)

```
App → route key to node → GET
  hit  → return value
  miss → app reads DB → SET into cache (TTL, jittered) → return
  (concurrent misses on the same hot key → single-flight: one loader, others wait)
```

### Add a node (rebalance)

```
New node joins the ring (vnodes) → owns ~1/N of the keyspace
→ ~1/N of keys migrate from neighbors (Redis: move slots) → clients refresh topology
→ only a fraction of keys move (no mass remap)
```

---

## 12. Failure Scenarios

| Failure | Handling |
| --- | --- |
| Node dies | Consistent hashing/slots move its keys to neighbors; replica promoted if replicated |
| Cache stampede (hot miss) | Request coalescing + randomized TTL |
| Cache penetration (many missing keys) | Negative caching + **Bloom filter** |
| Cache avalanche (mass expiry) | **Jittered TTLs** + staggered warmup |
| Hot key | Replicate key / client-local cache |
| Whole cache down | **Circuit breaker** → serve from DB (provision DB to survive it) |
| Memory pressure | Eviction (LRU/LFU) + alert |
| Split-brain on failover | Fencing / quorum in the controller |

### The "three cache disasters," disambiguated

These three sound alike and get mixed up constantly. They differ by *what's being asked for* and *why it hits the DB*:

| Disaster | What happens | Fix |
| --- | --- | --- |
| **Penetration** | Requests for keys that exist **nowhere** (not in cache, not in DB) — often malicious; every request finds nothing but still hits the DB | **Negative caching** (cache "not found") + **Bloom filter** to reject absent keys upfront |
| **Avalanche** | A **big batch** of keys expire at the same moment → broad miss surge that all hits the DB at once | **Jittered TTLs** + staggered warmup |
| **Stampede** (thundering herd) | **One hot key** expires and a crowd all miss it at once, all hitting the DB with the same query | **Single-flight** (one loader; the real fix). Jitter only helps once the key is fanned out into copies |

#### Q: What's a Bloom filter doing here?

A **Bloom filter** is a tiny, fast, probabilistic "have we *ever* seen this key?" gate. Before hitting the DB for a possibly-nonexistent key, ask the filter: if it says "definitely no," skip the DB entirely (that's how you kill **penetration**). It can have false positives ("maybe yes" when actually no) but **never** false negatives, so it's safe as a pre-filter — a "no" is always trustworthy.

```java
// Guard against penetration: reject keys the Bloom filter has never seen.
Object read(String key) {
    if (!bloom.mightContain(key)) return NOT_FOUND;  // definitely absent → skip DB
    Object v = cache.get(key);
    if (v != null) return v;
    v = db.query(key);
    cache.set(key, v == null ? NULL_SENTINEL : v, ttl);  // negative-cache misses too
    return v;
}
```

#### Q: What is the "circuit breaker" for a whole-cache outage?

If the **entire** cache cluster is down, every read becomes a miss and the full firehose hits the DB — which may not survive it. A **circuit breaker** detects the cache is failing and **stops trying it** for a while, routing reads straight to the DB (which you've provisioned to survive this) and periodically probing whether the cache is back. The point is to fail fast so one dependency's outage doesn't cascade into a total meltdown.

---

## 13. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Consistent Hashing** | Key → node mapping | Minimal reshuffle on scaling |
| **Strategy** | Eviction (LRU/LFU/…), replication/invalidation mode | Swap policies |
| **Proxy** | Routing layer / cluster redirection | Locate the owning node |
| **Leader-Follower** | Primary + replicas per shard | HA + read scale |
| **Cache-Aside / Write-Through** | Interaction with the DB | Consistency strategy |
| **Observer** | Topology change → clients refresh ring | Membership updates |
| **Singleton / Object Pool** | Connection pooling | Reuse connections |
| **Circuit Breaker** | Cache down → bypass to DB | Degrade gracefully |
| **Bloom Filter** | Avoid penetration (missing keys) | Skip lookups for absent keys |
| **Single-Flight** | Request coalescing on hot miss | Prevent stampede |

---

## 14. Interview Cheat Sheet

> **"How do you shard keys across nodes?"**
> "**Consistent hashing with virtual nodes** (or Redis's 16384 hash slots) — adding/removing a node moves only ~1/N of keys, avoiding the mass remap of `hash % N`. Clients (or a proxy/cluster) route a key to its node; cluster mode uses `MOVED`/`ASK` redirects."

> **"How does eviction work?"**
> "Bounded memory → evict via LRU/LFU. Perfect LRU is costly, so caches **sample K keys** and evict the best candidate (approximate LRU). TTLs expire lazily + via a background sampling sweep."

> **"How do you make it highly available?"**
> "Leader–follower replication per shard; replicas serve reads and are promoted on primary failure by a controller/sentinel. Async replication trades a tiny loss window for speed — fine for a cache backed by a DB."

> **"Thundering herd / penetration / avalanche / hot key?"**
> "Stampede (one hot key) → request coalescing / single-flight. Avalanche (mass expiry of many keys) → jittered TTLs. Penetration (missing keys) → negative caching + Bloom filter. Hot key → replicate it (jitter each copy's TTL) or add a client-local cache."

---

## 15. Final Takeaways

- It's a **distributed in-memory hash map**: O(1) get/set, sharded across nodes, sub-ms.
- **Consistent hashing + vnodes** (or Redis 16384 slots) = minimal key movement on scaling (the headline).
- **Eviction** (approximate LRU/LFU by sampling) bounds memory; TTLs expire lazily + actively.
- **Leader-follower replication + failover** for HA; cache is **eventually consistent** with the DB.
- Guard **stampede / penetration / avalanche / hot key** (coalescing, Bloom filter, jittered TTL, replication).
- Patterns: Consistent Hashing, Strategy (eviction), Proxy, Leader-Follower, Cache-Aside, Circuit Breaker, Single-Flight.

### Related notes

- [Consistent Hashing](../concepts/consistent-hashing.md) — the core sharding technique
- [Caching Strategies](../concepts/caching-strategies.md) — eviction, invalidation, stampede/penetration/avalanche
- [Databases — Deep Dive](../concepts/databases-deep-dive.md) — Redis internals + cluster routing (hash slots)
- [Bloom Filters](../concepts/bloom-filters.md) · [LRU Cache — LLD](lru-cache-system-design.md)
