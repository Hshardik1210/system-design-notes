# Distributed Unique ID Generator — System Design

> **Core challenge:** generate **globally unique** IDs across many machines, **at high throughput**, ideally **roughly time-sortable**, **without a single bottleneck** and without coordination on every request. The canonical answer is **Snowflake**.

---

## Contents

- [1. Why Not Just Auto-Increment / UUID?](#1-why-not-just-auto-increment--uuid)
- [2. Requirements](#2-requirements)
- [3. Approaches](#3-approaches)
- [4. Snowflake (the standard answer)](#4-snowflake-the-standard-answer)
- [5. Ticket Server / Range Allocation](#5-ticket-server--range-allocation)
- [6. Clock Issues & Gotchas](#6-clock-issues--gotchas)
- [7. Design Patterns (that can be used)](#7-design-patterns-that-can-be-used)
- [8. Interview Cheat Sheet](#8-interview-cheat-sheet)
- [9. Final Takeaways](#9-final-takeaways)

---

## 1. Why Not Just Auto-Increment / UUID?

| Option | Problem |
| --- | --- |
| **DB auto-increment** | Single point of contention; bottleneck; couples you to one DB; blocks sharding |
| **UUIDv4 (random 128-bit)** | Unique + no coordination, **but** 128-bit (large), **not sortable** → bad as a DB primary key (random inserts hurt B-tree locality) |
| **UUIDv7 / ULID** | Time-ordered UUIDs — good modern option (sortable + unique) |

We often want: **64-bit** (compact), **unique across nodes**, **time-sortable** (good index locality, natural chronological ordering), **no per-ID coordination**.

---

## 2. Requirements

- **Globally unique** IDs, no collisions.
- **High throughput** (100k+/sec), **low latency**, **highly available**.
- **Roughly time-ordered** (k-sorted) — nice for DB keys + sorting.
- **No single bottleneck**; scalable across many nodes.

---

## 3. Approaches

| Approach | Unique? | Sortable? | Coordination |
| --- | --- | --- | --- |
| DB auto-increment | ✅ | ✅ | Central bottleneck |
| Multi-DB with step/offset | ✅ | ~ | Config per node |
| UUIDv4 | ✅ | ❌ | None (but large + random) |
| **UUIDv7 / ULID** | ✅ | ✅ | None |
| **Snowflake** ✅ | ✅ | ✅ (time-ordered) | None per-ID (needs a machine id) |
| Ticket server / range allocation | ✅ | ✅ | Rare (per block) |

---

## 4. Snowflake (the standard answer)

A **64-bit** integer composed of time + machine + sequence — generated locally, no coordination per ID.

```
| 1 bit  | 41 bits            | 10 bits      | 12 bits          |
| sign=0 | timestamp (ms)     | machine id   | sequence          |
          ~69 years            1024 machines   4096 ids/ms/machine
```

```
nextId():
    now = currentMillis()
    if now == lastTs: sequence = (sequence + 1) & 4095
        if sequence == 0: now = waitNextMillis()   # sequence exhausted this ms
    else: sequence = 0
    lastTs = now
    return (now - epoch) << 22 | (machineId << 12) | sequence
```

| Field | Role |
| --- | --- |
| **Timestamp** | Makes IDs time-sortable + gives ~69 years of range from a custom epoch |
| **Machine id** | Uniqueness across nodes (assigned via config/ZooKeeper/etcd) |
| **Sequence** | Uniqueness within the same millisecond on one machine (4096/ms) |

- **Throughput**: 4096 ids/ms/machine × 1024 machines = ~4B ids/sec theoretical.
- **No coordination** per ID (only the one-time machine-id assignment).
- **k-sorted**: IDs increase with time (great for DB primary keys / cursors).

---

## 5. Ticket Server / Range Allocation

Alternative when you want **short, dense** numbers (e.g. URL shortener codes):

```
A central counter store; each node claims a BLOCK (e.g. 1000 ids) atomically,
serves them locally, refills when exhausted.
```

- Coordination only **once per block** (not per ID).
- Smaller numbers than Snowflake → shorter base62 codes.
- Trade-off: a crashed node "wastes" its unused block (fine — keyspace is huge).

---

## 6. Clock Issues & Gotchas

| Issue | Handling |
| --- | --- |
| **Clock skew across machines** | Machine id guarantees uniqueness even if two clocks agree |
| **Clock moving backwards (NTP)** | If `now < lastTs`, **wait** until the clock catches up, or reject briefly — never emit an ID from the past (could collide) |
| **Sequence overflow in 1ms** | Spin-wait to the next millisecond |
| **Machine id assignment** | Via ZooKeeper/etcd/config; must be unique per running instance |
| **Epoch exhaustion** | 41 bits ≈ 69 years from your custom epoch — pick a recent epoch |

---

## 7. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Strategy** | ID scheme (Snowflake / UUIDv7 / range) | Swap per need |
| **Factory** | ID generator instance per node | Encapsulate config (machine id, epoch) |
| **Singleton / Object Pool** | One generator per process | Thread-safe shared state (lastTs, sequence) |
| **Leader Election / Coordination** | Machine-id assignment (ZooKeeper/etcd) | Unique node ids |
| **Ticket / Range Allocation** | Block-based counters | Rare coordination, short ids |

---

## 8. Interview Cheat Sheet

> **"How do you generate unique IDs across many servers without a bottleneck?"**
> "**Snowflake** — a 64-bit ID = timestamp + machine id + per-ms sequence, generated locally with no per-ID coordination. Time-sortable, ~4B/sec across the fleet, machine id ensures cross-node uniqueness."

> **"Why not UUIDv4 or DB auto-increment?"**
> "Auto-increment is a central bottleneck and blocks sharding. UUIDv4 is unique but 128-bit and **random** — poor as a DB key (bad index locality, no sort order). Snowflake/UUIDv7 give compact, time-sortable IDs."

> **"What breaks Snowflake and how do you handle it?"**
> "Clock moving backwards → you could re-use a (timestamp, sequence) → so if `now < lastTimestamp`, wait until the clock catches up. Sequence overflow within a ms → spin to the next ms. Machine ids assigned uniquely via ZooKeeper/etcd."

> **"When would you use a range/ticket server instead?"**
> "When you want short, dense numbers (e.g. URL shortener codes) — nodes claim blocks of ids and serve locally, coordinating only per block."

---

## 9. Final Takeaways

- Want **64-bit, unique, time-sortable, no per-ID coordination** → **Snowflake** (timestamp | machine id | sequence).
- **UUIDv7/ULID** = simpler no-coordination alternative (time-ordered).
- **Range/ticket allocation** when you need short dense numbers.
- Avoid **auto-increment** (bottleneck) and **UUIDv4** (large, unsortable) as scalable keys.
- Guard **clock-backwards** (wait), **sequence overflow** (next ms), and **unique machine ids** (ZooKeeper/etcd).
- Patterns: Strategy, Factory, Singleton, Leader Election, Range Allocation.

### Related notes

- [URL Shortener — System Design](url-shortener-system-design.md) — uses base62 of generated IDs (range allocation vs Snowflake)
- [Consistent Hashing](../concepts/consistent-hashing.md) · [Apache Kafka](../concepts/kafka.md)
