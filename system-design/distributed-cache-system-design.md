# Distributed Cache — System Design (Redis / Memcached-like)

> **Core challenge:** build an in-memory key-value cache that spans **many nodes**, serves reads/writes in **sub-millisecond** time, **shards data with minimal reshuffling** on scaling, **evicts** intelligently under memory pressure, and optionally **replicates** for availability. The heart is **consistent hashing + eviction + replication**.

---

## Contents

- [1. Mental Model](#1-mental-model)
- [2. Requirements](#2-requirements)
- [3. Single-Node Cache Internals](#3-single-node-cache-internals)
- [4. Sharding with Consistent Hashing](#4-sharding-with-consistent-hashing)
- [5. Eviction Policies](#5-eviction-policies)
- [6. Replication & Availability](#6-replication--availability)
- [7. Consistency & Invalidation](#7-consistency--invalidation)
- [8. Client & Topology](#8-client--topology)
- [9. Design Patterns (that can be used)](#9-design-patterns-that-can-be-used)
- [10. Failure Scenarios](#10-failure-scenarios)
- [11. Interview Cheat Sheet](#11-interview-cheat-sheet)
- [12. Final Takeaways](#12-final-takeaways)

---

## 1. Mental Model

```
GET/SET key → hash the key → route to the node owning that key → in-memory hash map lookup
```

A giant distributed hash map held in RAM, partitioned across nodes, with eviction (bounded memory) and optional replication.

---

## 2. Requirements

**Functional**
- `GET`, `SET`, `DELETE`, `TTL/expire`; optionally richer types (lists, sets, sorted sets, counters).
- Distributed across nodes; scale by adding nodes.

**Non-functional**
- **Sub-millisecond** latency; very high throughput; **bounded memory** (eviction); highly available (replication); minimal data movement on scaling.

---

## 3. Single-Node Cache Internals

- **In-memory hash table** key→value for O(1) get/set.
- **TTL/expiry**: lazy (check on access) + active (background sweeper) expiration.
- **Eviction** when memory limit reached (see §5) — often approximated LRU/LFU (sampling) to avoid maintaining a perfect list.
- **Optional persistence** (Redis): snapshots (RDB) + append-only log (AOF) for durability/restart.
- Single-threaded event loop (Redis) → no lock contention; or multi-threaded (Memcached).

---

## 4. Sharding with Consistent Hashing

Spreading keys across N nodes with `hash(key) % N` **breaks catastrophically** when N changes (almost all keys remap). Use **consistent hashing**.

```
Hash ring: nodes + keys hashed onto a ring; a key belongs to the next node clockwise.
Add/remove a node → only keys between it and its neighbor move (≈ 1/N of keys), not all.
Virtual nodes → each physical node placed at many ring points → even distribution + smooth rebalance.
```

| Approach | On adding a node |
| --- | --- |
| `hash % N` | ~all keys remap → cache stampede |
| **Consistent hashing + vnodes** ✅ | only ~1/N keys move → smooth |

> See the dedicated **Consistent Hashing** concept note. This is the #1 thing to say for a distributed cache.

---

## 5. Eviction Policies

When memory is full, evict something:

| Policy | Evicts | Use |
| --- | --- | --- |
| **LRU** | Least recently used | General purpose (temporal locality) |
| **LFU** | Least frequently used | Skewed popularity |
| **FIFO** | Oldest inserted | Simple |
| **Random / sampled-LRU** | Approx LRU by sampling K keys | Redis default (cheap, near-LRU) |
| **TTL-based** | Nearest expiry | Time-bounded data |

- Perfect LRU needs a linked list per access (costly) → real systems **sample** a few keys and evict the best candidate (approximate LRU/LFU).

---

## 6. Replication & Availability

```
Primary (leader) per shard handles writes → asynchronously replicated to replicas
Replica serves reads / takes over (failover) if primary dies
```

- **Leader–follower** replication per shard; replicas for read scaling + HA.
- **Failover**: a sentinel/controller (Redis Sentinel / Cluster) detects primary death, promotes a replica.
- Async replication → tiny window of possible loss on failover (acceptable for a cache).

---

## 7. Consistency & Invalidation

- A cache is usually **eventually consistent** with the source of truth (DB).
- **Invalidation** on writes: write-through, write-around, or explicit delete-on-update (see Caching Strategies note).
- **Stampede protection**: on a hot miss, use request coalescing (single-flight) + randomized TTL to avoid thundering herd.
- **Hot key**: replicate the hot key to multiple nodes / add a local (client) cache layer.

---

## 8. Client & Topology

| Topology | How routing works |
| --- | --- |
| **Client-side sharding** | Client library hashes key → picks node (Memcached style) |
| **Proxy** | A proxy (Twemproxy/Envoy) routes; clients are simple |
| **Cluster mode** | Nodes know the ring; redirect clients to the right node (Redis Cluster) |

- Clients cache the topology/ring; refresh on membership change.

---

## 9. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Consistent Hashing** | Key → node mapping | Minimal reshuffle on scaling |
| **Strategy** | Eviction (LRU/LFU/…), replication mode | Swap policies |
| **Proxy** | Routing layer / cluster redirection | Locate the owning node |
| **Leader-Follower (replication)** | Primary + replicas per shard | HA + read scale |
| **Cache-Aside / Write-Through** | Interaction with the source DB | Consistency strategy |
| **Observer** | Topology change → clients refresh ring | Membership updates |
| **Singleton / Object Pool** | Connection pooling | Reuse connections |
| **Circuit Breaker** | Cache down → bypass to DB | Degrade gracefully |
| **Bloom Filter** | Avoid penetration (missing keys) | Skip lookups for absent keys |

---

## 10. Failure Scenarios

| Failure | Handling |
| --- | --- |
| Node dies | Consistent hashing moves its keys to neighbors; replica promoted if replicated |
| Cache stampede (hot miss) | Request coalescing + randomized TTL |
| Cache penetration (many missing keys) | Negative caching + Bloom filter |
| Cache avalanche (mass expiry) | Jittered TTLs; staggered warmup |
| Hot key | Replicate key / client-local cache |
| Whole cache down | Circuit breaker → serve from DB (provision DB for it) |
| Memory pressure | Eviction (LRU/LFU); alert |

---

## 11. Interview Cheat Sheet

> **"How do you shard keys across nodes?"**
> "**Consistent hashing with virtual nodes** — adding/removing a node moves only ~1/N of keys, avoiding the mass remap of `hash % N`. Clients (or a proxy/cluster) route a key to its node on the ring."

> **"How does eviction work?"**
> "Bounded memory → evict via LRU/LFU. Perfect LRU is costly, so real caches **sample K keys** and evict the best candidate (approximate LRU). TTLs expire lazily + via a background sweep."

> **"How do you make it highly available?"**
> "Leader–follower replication per shard; replicas serve reads and are promoted on primary failure by a controller/sentinel. Async replication trades a tiny loss window for speed — fine for a cache."

> **"Thundering herd / hot key?"**
> "Request coalescing + randomized TTL for stampedes; replicate hot keys or add a client-local cache; Bloom filter + negative caching for penetration."

---

## 12. Final Takeaways

- It's a **distributed in-memory hash map**: O(1) get/set, sharded across nodes.
- **Consistent hashing + virtual nodes** = minimal key movement on scaling (the headline).
- **Eviction** (approximate LRU/LFU by sampling) keeps memory bounded; TTLs expire lazily + actively.
- **Leader-follower replication + failover** for HA; cache is eventually consistent with the DB.
- Guard **stampede / penetration / avalanche / hot key** (coalescing, Bloom filter, jittered TTL, replication).
- Patterns: Consistent Hashing, Strategy (eviction), Proxy, Leader-Follower, Cache-Aside, Circuit Breaker.

### Related notes

- [Consistent Hashing](../concepts/consistent-hashing.md) — the core sharding technique
- [Caching Strategies](../concepts/caching-strategies.md) — eviction, invalidation, stampede/penetration/avalanche
