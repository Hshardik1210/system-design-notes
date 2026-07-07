# Distributed Unique ID Generator — System Design

> **Core challenge:** generate **globally unique** IDs across many machines, **at high throughput**, ideally **roughly time-sortable**, **without a single bottleneck** and without coordination on every request. The canonical answer is **Snowflake**.

---

## Contents

- [1. Why Not Auto-Increment / UUIDv4?](#1-why-not-auto-increment--uuidv4)
- [2. Requirements](#2-requirements)
- [3. Approaches](#3-approaches)
- [4. Snowflake (the standard answer)](#4-snowflake-the-standard-answer)
- [5. Machine-ID Assignment](#5-machine-id-assignment)
- [6. Ticket Server / Range Allocation](#6-ticket-server--range-allocation)
- [7. UUIDv7 / ULID](#7-uuidv7--ulid)
- [8. Deployment: Library vs Service](#8-deployment-library-vs-service)
- [9. Clock Issues & Gotchas](#9-clock-issues--gotchas)
- [10. Design Patterns (that can be used)](#10-design-patterns-that-can-be-used)
- [11. Interview Cheat Sheet](#11-interview-cheat-sheet)
- [12. Final Takeaways](#12-final-takeaways)

---

## 1. Why Not Auto-Increment / UUIDv4?

| Option | Problem |
| --- | --- |
| **DB auto-increment** | Single point of contention; bottleneck; couples you to one DB; blocks sharding |
| **UUIDv4 (random 128-bit)** | Unique + no coordination, **but** 128-bit (large), **not sortable** → poor DB primary key (random inserts hurt B-tree locality, page splits, cache misses) |
| **UUIDv7 / ULID** | Time-ordered UUIDs — good modern no-coordination option (sortable + unique) |

We usually want: **64-bit** (compact), **unique across nodes**, **time-sortable** (good index locality + natural chronological order), **no per-ID coordination**.

---

## 2. Requirements

- **Globally unique** IDs, no collisions.
- **High throughput** (100k+/sec), **low latency** (local generation), **highly available**.
- **Roughly time-ordered** (k-sorted) — nice for DB keys, cursors, and sorting by creation.
- **No single bottleneck**; scalable across many nodes.

---

## 3. Approaches

| Approach | Unique? | Sortable? | Coordination |
| --- | --- | --- | --- |
| DB auto-increment | ✅ | ✅ | Central bottleneck |
| Multi-DB step/offset (e.g. node k: k, k+N, k+2N) | ✅ | ~ | Config per node |
| UUIDv4 | ✅ | ❌ | None (but large + random) |
| **UUIDv7 / ULID** | ✅ | ✅ | None |
| **Snowflake** ✅ | ✅ | ✅ (time-ordered) | None per-ID (needs a machine id) |
| Ticket server / range allocation | ✅ | ✅ | Rare (per block) |

---

## 4. Snowflake (the standard answer)

A **64-bit** integer = time + machine + sequence, generated **locally** with no per-ID coordination.

```
| 1 bit  | 41 bits            | 10 bits      | 12 bits          |
| sign=0 | timestamp (ms)     | machine id   | sequence          |
          ~69 years            1024 machines   4096 ids/ms/machine
```

```
nextId():
    now = currentMillis()
    if now == lastTs:
        sequence = (sequence + 1) & 4095
        if sequence == 0: now = waitNextMillis()   # this ms is exhausted (>4096 ids)
    else:
        sequence = 0
    lastTs = now
    return ((now - epoch) << 22) | (machineId << 12) | sequence
```

| Field | Role | Tunable |
| --- | --- | --- |
| **Timestamp** | Time-sortable + ~69 years from a **custom epoch** | more bits → longer lifespan |
| **Machine id** | Cross-node uniqueness (assigned once) | more bits → more nodes, fewer per-ms ids |
| **Sequence** | Uniqueness within one ms on one machine (4096/ms) | more bits → higher per-ms throughput |

- **Throughput**: 4096 ids/ms/machine × 1024 machines ≈ **~4B ids/sec** theoretical.
- **No coordination** per ID (only the one-time machine-id assignment).
- **k-sorted**: IDs increase with time → great for DB primary keys, time-range scans, and cursors.
- **Bit layout is tunable** to your scale (e.g., more machine bits if you have >1024 nodes, fewer sequence bits).

---

## 5. Machine-ID Assignment

Each generating node needs a **unique machine id** (else collisions). Options:

| Method | How |
| --- | --- |
| **ZooKeeper/etcd** ✅ | On startup, claim an **ephemeral sequential znode** → your machine id; released on disconnect (reusable) |
| **Config/env** | Statically assign per deployment (simple, error-prone at scale) |
| **From infra** | Derive from a stable host attribute (careful with reuse) |

- The id must be **unique among *running* instances** at any time — ZooKeeper ephemeral nodes handle churn (a dead node's id can be reclaimed).
- With only 10 bits (1024), reuse of freed ids is important for large/elastic fleets.

---

## 6. Ticket Server / Range Allocation

Alternative when you want **short, dense** numbers (e.g. URL shortener codes):

```
A central counter store holds the next id; each node claims a BLOCK (e.g. 1000 ids) atomically,
serves them locally, and refills when exhausted.
```

- Coordination only **once per block** (not per ID) → minimal contention.
- **Smaller numbers** than Snowflake → shorter base62 codes.
- **Trade-off:** a crashed node "wastes" its unused block (fine — keyspace is huge); ids aren't strictly time-ordered globally.
- HA: replicate the counter store; the block claim must be atomic (`INCRBY`).

---

## 7. UUIDv7 / ULID

Modern no-coordination alternatives that fix UUIDv4's unsortability:

```
UUIDv7 / ULID = [ 48-bit millisecond timestamp | random bits ]
  → time-ordered (sortable) + globally unique + NO coordination (no machine id needed)
```

- **Pros:** dead simple (no machine-id management), time-sortable, unique. Great default for many apps.
- **Cons:** 128-bit (bigger than Snowflake's 64-bit); slightly less compact as a key.
- Use when you want zero coordination and don't need 64-bit compactness.

---

## 8. Deployment: Library vs Service

| Model | How | Trade-off |
| --- | --- | --- |
| **Embedded library** ✅ | Each app instance generates ids in-process (Snowflake) | Zero network hop, lowest latency; needs machine-id assignment per instance |
| **ID service** | A dedicated service (cluster) hands out ids via RPC | Centralized machine-id mgmt; adds a network hop + a dependency |
| **Range/ticket service** | Service hands out blocks; app serves locally | Rare coordination; short ids |

- **Embedded Snowflake** is usually best (no hop). A **service** is used when you want central control or non-JVM clients sharing one scheme.

---

## 9. Clock Issues & Gotchas

| Issue | Handling |
| --- | --- |
| **Clock skew across machines** | Machine id guarantees uniqueness even if two clocks read the same ms |
| **Clock moving backwards (NTP)** | If `now < lastTs`, **wait** until the clock catches up (or briefly reject) — never emit an id from the past (could collide with already-issued ids) |
| **Sequence overflow in 1ms** | >4096 ids in a ms → **spin-wait** to the next ms |
| **Machine id reuse** | Ephemeral ZooKeeper node → reclaim freed ids; ensure no two live instances share one |
| **Epoch exhaustion** | 41 bits ≈ 69 years from your custom epoch — pick a **recent epoch** so you get the full range |
| **Leap seconds / NTP slew** | Prefer monotonic clock for `lastTs` comparison |

---

## 10. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Strategy** | ID scheme (Snowflake / UUIDv7 / range) | Swap per need |
| **Factory** | ID generator per node (config: machine id, epoch, bit layout) | Encapsulate creation |
| **Singleton / Object Pool** | One generator per process (shared `lastTs`/`sequence`) | Thread-safe shared state |
| **Leader Election / Coordination** | Machine-id assignment (ZooKeeper/etcd) | Unique node ids |
| **Ticket / Range Allocation** | Block-based counters | Rare coordination, short ids |

---

## 11. Interview Cheat Sheet

> **"How do you generate unique IDs across many servers without a bottleneck?"**
> "**Snowflake** — a 64-bit id = timestamp + machine id + per-ms sequence, generated locally with no per-ID coordination. Time-sortable, ~4B/sec across the fleet; the machine id (assigned once via ZooKeeper) ensures cross-node uniqueness."

> **"Why not UUIDv4 or auto-increment?"**
> "Auto-increment is a central bottleneck and blocks sharding. UUIDv4 is unique but 128-bit and **random** — poor as a DB key (bad index locality). Snowflake (64-bit) or **UUIDv7/ULID** give compact/time-sortable ids."

> **"How is the machine id assigned?"**
> "A ZooKeeper/etcd **ephemeral sequential znode** on startup → a unique id that's reclaimed if the node dies. With 10 bits (1024 nodes), reclaiming freed ids matters for elastic fleets."

> **"What breaks Snowflake?"**
> "Clock moving backwards → if `now < lastTs`, wait so you never re-issue a (timestamp, sequence). Sequence overflow within a ms → spin to the next ms. Duplicate machine ids → collisions (so assignment must be unique)."

> **"When a range/ticket server instead?"**
> "When you want short dense numbers (URL shortener codes) — nodes claim blocks and serve locally, coordinating only per block."

---

## 12. Final Takeaways

- Want **64-bit, unique, time-sortable, no per-ID coordination** → **Snowflake** (timestamp | machine id | sequence); bit layout is tunable.
- **Machine id** assigned once via **ZooKeeper/etcd ephemeral node** (reclaimable); the only coordination.
- **UUIDv7/ULID** = zero-coordination alternative (128-bit, time-ordered); **range/ticket** allocation for short dense ids.
- Avoid **auto-increment** (bottleneck) and **UUIDv4** (large, unsortable) as scalable keys.
- Guard **clock-backwards** (wait), **sequence overflow** (next ms), **unique machine ids**, and pick a **recent epoch**.
- Patterns: Strategy, Factory, Singleton, Leader Election, Range Allocation.

### Related notes

- [URL Shortener](url-shortener-system-design.md) — base62 of generated ids (range allocation vs Snowflake)
- [Databases — Deep Dive](../concepts/databases-deep-dive.md) (why time-sortable keys matter for B-trees) · [Consistent Hashing](../concepts/consistent-hashing.md)
