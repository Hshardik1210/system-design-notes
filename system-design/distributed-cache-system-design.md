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
- [13. Consistency & CAP Tradeoffs](#13-consistency--cap-tradeoffs)
- [14. API Design](#14-api-design)
- [15. How to Drive the Interview (framework)](#15-how-to-drive-the-interview-framework)
- [16. Interview Cheat Sheet](#16-interview-cheat-sheet)
- [17. Design Patterns (that can be used)](#17-design-patterns-that-can-be-used)
- [18. Final Takeaways](#18-final-takeaways)

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

**Hit rate** = fraction of reads served by the cache (hits ÷ total), and it matters far more than it sounds. A cache earning its keep usually runs **90%+**. The leverage is huge:

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

The diagram's "cache-aside" label names the most common way the app and cache cooperate: the **app** manages the cache, and the cache sits *aside* the main path (it doesn't talk to the DB itself). On a miss, the app fetches from the DB and back-fills the cache. Full walkthrough with code is in §9 — for now just know "miss → app loads DB → app puts it in cache."

---

## 5. Single-Node Cache Internals

- **In-memory hash table** key→value → **O(1)** get/set.
- **TTL/expiry**: **lazy** (check on access) + **active** (background sampling sweeper) expiration.
- **Eviction** when memory limit reached (see §7) — approximate LRU/LFU by **sampling** (perfect LRU's linked-list bookkeeping is costly).
- **Memory management**: slab allocation (Memcached) to reduce fragmentation; Redis uses jemalloc + efficient encodings for small values.
- **Persistence (Redis, optional)**: **RDB** snapshots + **AOF** append-only log → survive restarts (but a cache is usually rebuildable from the DB).
- **Threading**: Redis = single-threaded command execution (no locks → atomic ops); Memcached = multi-threaded.

### Database & storage choices (which store, and why at scale)

This design *is* the datastore, so "which DB" really means "what physically backs each moving part." Unlike a normal app, there's no single deciding question of "does this need transactions?" — the cache's whole job is to trade durability/transactions **away** for raw speed, while the *real* source of truth lives one layer behind it in the app's own DB.

| Data | Store | Why this one | Why not the alternative |
| --- | --- | --- | --- |
| The cached entries themselves | **In-memory hash table** (Redis/Memcached process RAM) | O(1) lookup, no disk seek, no query planner — the entire point of a cache is "faster than the DB," and RAM is the only medium that delivers sub-ms reliably. | A disk-backed KV store (RocksDB/LevelDB) still pays a disk I/O per miss-free read — that's the DB's job, not the cache's. Putting disk under the cache defeats the reason it exists. |
| Crash recovery (optional) | **Redis RDB snapshot + AOF log** | Lets a restarted node reload its own state in seconds instead of re-warming from the DB (or from scratch, for Memcached). | Memcached has neither — a restart is a **cold cache**, which is fine for small datasets but re-triggers a full stampede of misses on a large working set. |
| The source of truth behind the cache | **Whatever DB the app already uses** (RDBMS/NoSQL — out of scope here) | The cache is disposable and rebuildable; the DB is not. Every design decision in this doc (async replication, sampled eviction, TTL) is safe *because* a lost cache entry just becomes a miss, not lost data. | Treating the cache itself as the system of record (e.g. relying on write-back persistence as "durable enough") reintroduces exactly the durability risk a real DB is built to avoid (§9). |
| Cluster topology (who owns which key) | **Consistent-hash ring / Redis's 16384 hash slots**, gossiped between nodes (or held by a proxy/client) | Needs to be agreed cluster-wide and updated cheaply as nodes join/leave — a ring or fixed slot map does this with ~1/N key movement (§6). | Plain `hash(key) % N` remaps almost every key on every topology change → mass miss → stampede on the DB behind it. Not a viable topology map at any real scale. |

**Redis vs Memcached, concretely — why RAM + consistent hashing wins here:** both are in-memory hash tables, so the real fork is *richness and durability* vs *raw simplicity*. Redis adds data structures (sorted sets, lists), single-threaded atomicity, and optional AOF/RDB persistence, at the cost of being (mostly) single-core per instance. Memcached is purely multi-threaded key→bytes with no persistence — simpler, and it parallelizes across cores on one box more easily, but a restart always means a cold cache and there's no way to express "increment this counter atomically" as a single op. For **this** design, the throughput vs correctness trade-off resolves in RAM's favor either way: cache correctness only ever needs to be "eventually right within a TTL," never transactional, so we spend our design budget on **consistent hashing + vnodes** (minimal reshuffling as nodes scale) and **leader-follower replication per shard** (§8) rather than on stronger consistency the workload doesn't need. (See [Databases — Deep Dive](../concepts/databases-deep-dive.md) for the full Redis internals + cluster-routing comparison.)

### What's inside one cache node?

Zoom into a single cache node. At its core it's just a **hash map (dictionary)** living in RAM: key → value. That's why `GET`/`SET` are **O(1)** — no scanning, you jump straight to the slot.

```java
// The heart of one cache node: a plain in-memory map.
Map<String, byte[]> store = new HashMap<>();

byte[] get(String key) { return store.get(key); }   // O(1)
void   set(String key, byte[] value) { store.put(key, value); }   // O(1)
```

Everything else on the node is bookkeeping around that map: expiry, eviction, and memory management.

TTL expiry and eviction sound the same but are different reasons a key leaves the cache — a very common mix-up:

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

> 💡 **Consistent hashing, in one line:** a scheme for mapping keys to nodes so that adding or removing a node moves only a **small slice** of keys (~1/N), instead of shuffling almost everything. It's the single most important idea in this doc.

> ⚠️ **The classic mistake: `hash(key) % N`.** It looks fine in a demo but the moment `N` changes (a node joins/leaves) the modulo result changes for **almost every key** → every lookup misses → the whole cache stampedes the DB at once. Never propose `% N` for a scalable cache.

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

> 💡 **Virtual nodes (vnodes):** instead of putting each physical node at **one** spot on the ring, put it at **many** (e.g. 150). More points → smaller, evenly-mixed arcs → balanced load and, when a node dies, its share spreads across *many* neighbors instead of dumping on one.

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

Redis Cluster's "16384 hash slots" is the same idea, discretized for simplicity. Instead of a continuous clock, Redis pre-defines **16384 fixed slots**. A key maps to a slot via `CRC16(key) % 16384`, and each node owns a **range of slots**. Rebalancing = **moving slot ranges** between nodes (and only those keys move). It's consistent hashing with fixed buckets — easier to reason about and administer than a free-form ring.

```
key → CRC16(key) % 16384 = slot 8213
slot 8213 currently owned by node C → key lives on C
add a node → hand it, say, slots 0–4095 from others → only those keys move
```

This isn't something computed once at startup — the ring/slot map is **live topology**. Nodes join and leave over time; the mapping is updated and gossiped so everyone agrees on the current owner of each key. Clients cache a copy of the map and refresh it when membership changes (see §10). The point of consistent hashing is that these membership changes are *cheap* — a small slice of keys moves, not the whole cache.

> 💡 **Gossip:** a decentralized way for nodes to sync state — each node periodically tells a few random peers what it knows (who's alive, who owns which slots), and the news spreads epidemic-style until the whole cluster agrees. No central coordinator to become a bottleneck or single point of failure. Redis Cluster uses gossip to propagate topology.

---

## 7. Eviction Policies

> 💡 **LRU vs LFU in one line:** **LRU** = "least *recently* used" → evict whatever hasn't been touched for the longest (bets on recency). **LFU** = "least *frequently* used" → evict whatever has been read the fewest times overall (bets on lasting popularity).

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

"Async" replication is worth spelling out, along with why a little data loss is tolerated here:

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

#### Q: Can I read my own write back from a replica?

Not reliably — not right away. Because replication is **async**, a write lands on the primary and is acked *before* it reaches the replicas. If you `SET key=v` on the primary and then immediately `GET key` from a replica, you can get the **old** value (or a miss) during that replication lag window — the replica just hasn't received the update yet. This is **replica staleness**, and it's the price of fast async replication. Three ways to deal with it, depending on how much you care: **(1)** route reads that must see the latest write to the **primary** (read-your-writes = read from the leader); **(2)** use **quorum** reads/writes (`W + R > N`) so at least one node in your read set has the newest value — stronger, but slower, and uncommon for a plain cache; **(3)** just accept it — for most caches, a few milliseconds of staleness is fine because the **DB is the source of truth** and a stale/missing cache entry self-heals on the next load. Interview one-liner: *"Async replicas are eventually consistent; if I need read-my-own-write I read from the primary or use a quorum, otherwise I tolerate the lag because the cache isn't the source of truth."*

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

> ⚠️ **Don't *update* the cache on a write — *delete* it.** Writing the new value into the cache after the DB write feels natural but invites a stale-write race: two concurrent writers can apply their DB updates in one order but their cache `SET`s in the *opposite* order, leaving the cache pinned to the older value indefinitely. `DELETE` sidesteps this entirely — the next read simply re-loads fresh truth from the DB.

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

Someone has to answer "which node owns this key?" (the ring/slot lookup from §6), and the three rows of the table above are really three places that logic can live: the app's client library hashing straight to the right node (client-side), a middleman proxy (Twemproxy/Envoy) forwarding requests while clients stay dumb, or the nodes themselves knowing the map and **redirecting** you if you ask the wrong one (Redis `MOVED`/`ASK`).

- **Client-side** = one fewer hop (fastest) but every client must know and refresh the ring.
- **Proxy** = simplest clients, but the proxy is an extra hop and a thing to scale/operate.
- **Cluster mode** = smart nodes, thin clients; the redirect costs an occasional extra round-trip when topology just changed.

Clients avoid getting redirected on every single request because they **cache the topology** (a local copy of the ring/slot map). They route directly using that cached map, so redirects are rare. When membership changes (a node joins/leaves), the map is updated and propagated (via **gossip** between nodes or a control plane); the client refreshes and gets back to direct routing. A `MOVED` redirect is essentially the cluster telling a client "your map is stale — here's the correct node, and please refresh."

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

### Client routing with a stale map (MOVED / ASK)

> The end-to-end flow when a client's cached topology is out of date — e.g. right after a rebalance. This is exactly what Redis Cluster's `MOVED`/`ASK` redirects are for.

```
1. Client computes: slot = CRC16("user:123") % 16384 = 8213
2. Client's CACHED map says slot 8213 → node C  → sends GET user:123 to C
3. But slot 8213 was just migrated to node E, so C rejects with a redirect:

     -MOVED 8213 10.0.0.5:6379        ← concrete error response

4. Client reads the redirect: "slot 8213 now lives at node E (10.0.0.5)"
5. Client retries GET user:123 against node E → HIT
6. Client REFRESHES its slot map (so future requests go straight to E — no repeat redirect)
```

> **MOVED vs ASK:** `MOVED` = "this slot has *permanently* moved, update your map." `ASK` = "this slot is *mid-migration*; for THIS one request, ask the new node (with `ASKING`), but don't update your map yet." `MOVED` is the steady-state redirect; `ASK` is the transient one during an in-flight slot move.

### Failover drill (primary dies)

> Numbered walk-through of a primary failing, a replica taking over, and the small write-loss window that async replication leaves behind.

```
1. Primary P (shard 3) is serving reads+writes; replica R follows it asynchronously.
2. Client SET k=v2 → P applies it, acks the client, queues it for R
   → but P CRASHES before that op reaches R  (R still has k=v1)
3. Controller (Sentinel/Cluster) misses heartbeats from P → declares it down.
4. Quorum of nodes agrees, then PROMOTES R to primary (with fencing so a
   revived P can't keep acting as primary → no split-brain).
5. Topology updates + gossips; clients refresh. A client still talking to old P
   gets redirected to the new primary R (MOVED-style), then routes to R.
6. PARTIAL-WRITE-LOSS WINDOW: the un-replicated SET k=v2 is gone — R serves the
   older k=v1. This is acceptable for a cache: next read misses/stale → reload
   from the DB (source of truth) → cache self-heals. (For a database you'd use
   sync/quorum writes to avoid this; a cache trades that safety for speed.)
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

A **Bloom filter** is doing quiet but crucial work here: it's a tiny, fast, probabilistic "have we *ever* seen this key?" gate. Before hitting the DB for a possibly-nonexistent key, ask the filter: if it says "definitely no," skip the DB entirely (that's how you kill **penetration**). It can have false positives ("maybe yes" when actually no) but **never** false negatives, so it's safe as a pre-filter — a "no" is always trustworthy.

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

The "circuit breaker" for a whole-cache outage earns its own callout: if the **entire** cache cluster is down, every read becomes a miss and the full firehose hits the DB — which may not survive it. A **circuit breaker** detects the cache is failing and **stops trying it** for a while, routing reads straight to the DB (which you've provisioned to survive this) and periodically probing whether the cache is back. The point is to fail fast so one dependency's outage doesn't cascade into a total meltdown.

---

## 13. Consistency & CAP Tradeoffs

> Interviewers love: "Under a network partition, is your cache CP or AP?" The honest answer for a cache is **AP by default** — and that's the *right* choice, because the real source of truth (the DB) is the one that stays CP.

The key realization: a cache almost always sits **in front of** a strongly-consistent database. So you get to be relaxed at the cache layer precisely *because* the DB is strict behind it. A lost or stale cache entry is never lost data — it's just a **miss** that self-heals on the next read.

| Path | Choice | Why |
| --- | --- | --- |
| **Cache reads** | **AP** (availability + eventual) | Serve possibly-stale data fast; a stale/missing entry just re-loads from the DB. TTL bounds the staleness. |
| **Cache writes (default async replication)** | **AP** | Primary acks immediately, replicates a moment later → tiny loss window on failover, but writes stay fast and available. |
| **Cache writes (if you demand CP)** | **CP — at a cost** | Only achievable with **synchronous / quorum replication**: the primary waits for replicas to confirm before acking. No lost writes on failover, but every write pays the round-trip and a partition can block writes entirely. Rarely worth it for a cache. |
| **Source DB (behind the cache)** | **CP** | This is where correctness lives — transactions, durability, no lost writes. The cache leans on it. |

- **Cache is AP for reads**, always — that's the whole point of a fast, available read layer.
- **Cache is CP only if you accept slower writes** (sync/quorum replication) — most designs don't, because the DB already guarantees durability.
- **The source DB stays CP** — the cache being loose is safe precisely because the truth behind it is strict.

> One-liner: **"The cache is AP — I trade a little staleness and a tiny failover write-loss window for speed and availability, because the DB behind it is the CP source of truth that makes those losses self-healing."**

---

## 14. API Design

> A distributed cache exposes three surfaces: the **client data API** (what apps call), the **cluster/topology API** (how routing is discovered), and the **admin API** (how operators rebalance/scale). Keep the data path dead simple — that simplicity is what buys sub-ms latency.

### Client data API (what the app calls)

```
GET    key                      → value | (nil)           # O(1) lookup on the owning node
SET    key value [EX seconds]   → OK                       # optional TTL; EX = expire in N sec
DELETE key                      → 1 (deleted) | 0 (absent) # used by cache-aside on write
EXPIRE key seconds              → 1 | 0                     # set/refresh a TTL
TTL    key                      → seconds | -1 (no ttl) | -2 (absent)
INCR   key                      → new integer              # atomic counter (single-threaded → safe)
```

### Cluster / topology API (how clients route)

```
CLUSTER SLOTS      → slot ranges → node (ip:port) + replicas   # clients cache this map
CLUSTER NODES      → full membership + who owns which slots
CLUSTER KEYSLOT k  → which slot a key hashes to (CRC16(k) % 16384)

# Redirects returned inline when a client's map is stale (see §11):
-MOVED <slot> <ip:port>    # slot moved permanently → update your map
-ASK   <slot> <ip:port>    # slot mid-migration → ask new node for THIS request only
```

### Admin API (operators scale/rebalance)

```
CLUSTER ADDNODE <ip:port>              → grow the cluster
CLUSTER SETSLOT <slot> MIGRATING/IMPORTING/NODE   → move slot ranges between nodes
CLUSTER REBALANCE                      → redistribute slots evenly (moves ~1/N keys)
CLUSTER FORGET <node-id>               → remove a dead/decommissioned node
CLUSTER FAILOVER                       → manually promote a replica (planned maintenance)
```

> The data API is tiny on purpose. All the *system-design* interest lives in the topology + admin surfaces — that's where consistent hashing, slot migration, and failover (§6, §8, §11) actually show up.

---

## 15. How to Drive the Interview (framework)

> Use this order so you never freeze. Spend ~5 min on 1–4, then go deep on 5–6 (sharding + failure modes are what they're really testing).

1. **Clarify requirements** — data API (GET/SET/DELETE/TTL), scale, latency target, HA needs — §2
2. **Estimate scale** — working-set size, QPS, memory (RAM is the constraint) — §3
3. **Sketch the API + architecture** — client → router → node → DB-on-miss — §14, §4
4. **Single-node internals** — hash map, TTL (lazy+active), eviction — §5, §7
5. **Deep dive: the hard part → sharding** — **draw the hash ring**, add/remove a node moves only ~1/N keys, vnodes for balance, Redis 16384 slots — §6
6. **Deep dive: failure modes** — replication + failover, stampede/avalanche/penetration/hot key — §8, §9, §12
7. **Address consistency** — AP cache in front of a CP DB; staleness bounded by TTL + delete-on-write — §13
8. **Summarize tradeoffs** — §13, §16

> 🎤 **Lead with the headline:** say up front that "the crux is **sharding with minimal reshuffling** — consistent hashing so scaling doesn't stampede the DB," then spend most of your time on the ring, eviction, and failure modes. That's the signal they're grading.

---

## 16. Interview Cheat Sheet

> **"How do you shard keys across nodes?"**
> "**Consistent hashing with virtual nodes** (or Redis's 16384 hash slots) — adding/removing a node moves only ~1/N of keys, avoiding the mass remap of `hash % N`. Clients (or a proxy/cluster) route a key to its node; cluster mode uses `MOVED`/`ASK` redirects."

> **"How does eviction work?"**
> "Bounded memory → evict via LRU/LFU. Perfect LRU is costly, so caches **sample K keys** and evict the best candidate (approximate LRU). TTLs expire lazily + via a background sampling sweep."

> **"How do you make it highly available?"**
> "Leader–follower replication per shard; replicas serve reads and are promoted on primary failure by a controller/sentinel. Async replication trades a tiny loss window for speed — fine for a cache backed by a DB."

> **"Thundering herd / penetration / avalanche / hot key?"**
> "Stampede (one hot key) → request coalescing / single-flight. Avalanche (mass expiry of many keys) → jittered TTLs. Penetration (missing keys) → negative caching + Bloom filter. Hot key → replicate it (jitter each copy's TTL) or add a client-local cache."

> **"Draw the hash ring for me."**
> "Draw a circle (0 → 2³²-1). Hash each **node** to a point on it, and each **key** to a point too. To find a key's owner, **start at the key and walk clockwise to the first node**. Adding a node only steals the arc between it and its clockwise neighbor → ~1/N keys move. Then add **vnodes**: each physical node sits at ~150 points so arcs are small and even, and a death spreads across many neighbors. Mention Redis discretizes this into **16384 fixed slots**."

> **"A node dies mid-write — what happens to that write?"**
> "With async replication the primary may have acked a write it hadn't yet copied. If it dies before replicating, that write is **lost** on failover (the promoted replica never saw it). That's the accepted **write-loss window** — safe for a cache because the DB is the source of truth, so the value just re-loads on the next miss. If you truly can't lose it, you'd need **sync/quorum writes** (slower). And it's never *corrupt* — you get old-or-missing, never garbage, so a re-read fixes it."

> **"Cache is empty after a deploy / rebalance — cold-start problem?"**
> "A cold or freshly-rebalanced cache = every read misses = the DB eats the full firehose (a self-inflicted stampede). **Warm it**: pre-load the known-hot keys before taking traffic, migrate slots gradually so only ~1/N keys are cold at once, ramp traffic behind single-flight + a circuit breaker so the DB isn't hit by 10k identical misses, and jitter warm-up TTLs so the warmed keys don't all expire together (avalanche). Memcached restarts always cold; Redis can reload from RDB/AOF to skip re-warming."

### Tricky scenarios (rapid-fire)

| Scenario | What happens / what to do |
| --- | --- |
| **You added a node with `hash % N`** | Almost every key remaps → mass miss → DB stampede. Use **consistent hashing + vnodes** so only ~1/N keys move. |
| **Client sends a request to the wrong node after a rebalance** | Node replies `-MOVED <slot> <ip:port>`; client retries the correct node and **refreshes its cached slot map**. `ASK` for a slot mid-migration. |
| **Primary dies with un-replicated writes** | Controller promotes a replica (quorum + fencing → no split-brain); the un-replicated writes are lost → re-load from DB on next miss. |
| **One hot key melts a single node** | Sharding can't split one key. **Replicate it** to salted copies (`k#0..#3`, jitter each TTL) or add a **client-local cache**. |
| **Many keys expire at the same second** | Avalanche → **jittered TTLs** + staggered warm-up. |
| **Requests for keys that don't exist anywhere** | Penetration → **negative caching** + **Bloom filter** to reject absent keys before the DB. |
| **You `SET` the cache on write and it goes stale** | Two writers' `SET`s interleave → stale value pinned. **Delete-on-write** instead, so the next read reloads truth. |
| **Whole cache cluster is down** | **Circuit breaker** → bypass to DB (provisioned to survive it), probe for recovery, then re-warm. |

---

## 17. Design Patterns (that can be used)

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

## 18. Final Takeaways

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
