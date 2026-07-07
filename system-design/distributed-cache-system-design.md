# Distributed Cache — System Design (Redis / Memcached-like)

> **Core challenge:** build an in-memory key-value cache spanning **many nodes**, serving reads/writes in **sub-millisecond** time, **sharding with minimal reshuffling** on scaling, **evicting** intelligently under memory pressure, and optionally **replicating** for availability. The heart is **consistent hashing + eviction + replication + stampede protection**.

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

---

## 4. Architecture

```
App ─► (client library / proxy / cluster-aware client) ─► routes key → owning node
                                                          each node: in-memory KV + replicas
Miss → app fetches from the source DB → SET into cache (cache-aside)
Topology: consistent-hash ring (or Redis 16384 slots); replicas per shard; controller/sentinel for failover
```

---

## 5. Single-Node Cache Internals

- **In-memory hash table** key→value → **O(1)** get/set.
- **TTL/expiry**: **lazy** (check on access) + **active** (background sampling sweeper) expiration.
- **Eviction** when memory limit reached (see §7) — approximate LRU/LFU by **sampling** (perfect LRU's linked-list bookkeeping is costly).
- **Memory management**: slab allocation (Memcached) to reduce fragmentation; Redis uses jemalloc + efficient encodings for small values.
- **Persistence (Redis, optional)**: **RDB** snapshots + **AOF** append-only log → survive restarts (but a cache is usually rebuildable from the DB).
- **Threading**: Redis = single-threaded command execution (no locks → atomic ops); Memcached = multi-threaded.

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

---

## 8. Replication & Availability

```
Primary (leader) per shard handles writes → asynchronously replicated to replicas
Replica serves reads / takes over (failover) if the primary dies
```

- **Leader–follower** replication per shard; replicas add **read scaling + HA**.
- **Failover**: a controller/sentinel (Redis Sentinel / Cluster) detects primary death → **promotes a replica**; clients redirected.
- **Async replication** → a tiny window of possible loss on failover — acceptable for a cache (source of truth is the DB).

---

## 9. Consistency & Invalidation

- A cache is usually **eventually consistent** with the DB (source of truth).
- **Invalidation on writes**: write-through (write cache+DB together), write-around (write DB, invalidate cache), or **delete-on-update** (see Caching Strategies).
- **Write-back** (write cache, async flush to DB) = fast writes but risk on crash — rarely for a plain cache.
- **Stampede protection**: on a hot miss, **request coalescing (single-flight)** + **randomized TTL**.
- **Hot key**: replicate the hot key to multiple nodes / add a **client-local cache** layer.

---

## 10. Client & Topology (routing)

| Topology | How routing works |
| --- | --- |
| **Client-side sharding** | Client library hashes the key → picks the node (Memcached style) |
| **Proxy** | A proxy (Twemproxy/Envoy) routes; clients stay simple |
| **Cluster mode** | Nodes know the ring/slots; **redirect** clients to the right node (Redis Cluster `MOVED`/`ASK`) |

- Clients **cache the topology/ring**; refresh on membership change (gossip / control plane).

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
> "Stampede → request coalescing + randomized TTL. Penetration (missing keys) → negative caching + Bloom filter. Avalanche (mass expiry) → jittered TTLs. Hot key → replicate it or add a client-local cache."

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
