# Database Fundamentals

> The "horizontal" knowledge interviewers cross-examine in almost every system design round: **SQL vs NoSQL, ACID vs BASE, replication, partitioning/sharding**.

---

## Contents

- [1. SQL vs NoSQL](#1-sql-vs-nosql)
- [2. ACID vs BASE](#2-acid-vs-base)
- [3. Transaction Isolation Levels](#3-transaction-isolation-levels)
- [4. Replication](#4-replication)
- [5. CAP & PACELC](#5-cap--pacelc)
- [6. Partitioning & Sharding](#6-partitioning--sharding)
- [7. Connection Pooling](#7-connection-pooling)
- [8. Normalization vs Denormalization](#8-normalization-vs-denormalization)
- [9. Interview Cheat Sheet](#9-interview-cheat-sheet)
- [10. Final Takeaways](#10-final-takeaways)

---

## 1. SQL vs NoSQL

| Aspect | SQL (relational) | NoSQL |
| --- | --- | --- |
| Schema | Fixed, predefined | Flexible / schemaless |
| Scaling | Vertical (mostly), harder to shard | Horizontal by design |
| Consistency | Strong (ACID) | Often eventual (BASE) |
| Joins | First-class | Limited / avoided |
| Best for | Transactions, relationships, integrity | Huge scale, flexible data, high write throughput |
| Examples | PostgreSQL, MySQL | MongoDB, Cassandra, DynamoDB, Redis |

### NoSQL families

| Type | Description | Examples | Use case |
| --- | --- | --- | --- |
| **Key-Value** | Hash map | Redis, DynamoDB | caching, sessions |
| **Document** | JSON docs | MongoDB, Couchbase | flexible entities, catalogs |
| **Wide-Column** | Rows with dynamic columns | Cassandra, HBase | time-series, write-heavy |
| **Graph** | Nodes + edges | Neo4j | social graphs, recommendations |

### How to choose (interview framing)

- **Need transactions, strong consistency, relationships?** → SQL.
- **Need massive horizontal scale, flexible schema, high write throughput, can tolerate eventual consistency?** → NoSQL.
- **Real answer is often "both"** (polyglot persistence): SQL for orders/payments, Redis for cache, Cassandra for events.

> ⚠️ "NoSQL = no schema" is misleading — you still design around **access patterns** (query-first modeling), often denormalizing.

---

## 2. ACID vs BASE

### ACID (SQL transactions)

| Property | Meaning |
| --- | --- |
| **Atomicity** | All or nothing |
| **Consistency** | Valid state → valid state (constraints hold) |
| **Isolation** | Concurrent txns don't corrupt each other |
| **Durability** | Committed data survives crashes |

### BASE (many NoSQL systems)

- **Basically Available** — system stays responsive
- **Soft state** — state may change without input (due to replication)
- **Eventual consistency** — replicas converge over time

> **One-liner:** ACID favors **correctness**; BASE favors **availability + scale**. CAP forces the trade-off (§5).

---

## 3. Transaction Isolation Levels

> Higher isolation = fewer anomalies but more locking / less concurrency.

| Level | Prevents | Still allows |
| --- | --- | --- |
| **Read Uncommitted** | — | dirty reads |
| **Read Committed** | dirty reads | non-repeatable reads |
| **Repeatable Read** | non-repeatable reads | phantom reads |
| **Serializable** | everything (acts serial) | lowest concurrency |

**Anomalies:**
- **Dirty read** — read uncommitted data from another txn.
- **Non-repeatable read** — same row read twice gives different values.
- **Phantom read** — same query returns different *set* of rows.

> Default for Postgres = Read Committed; MySQL InnoDB = Repeatable Read.

---

## 4. Replication

> Keep copies of data on multiple nodes for **availability, read scaling, and durability**.

### Leader–Follower (primary–replica)

```
Writes → Leader → replicate → Followers (read-only)
Reads  → Followers (scale reads)
```

- **Pros:** scales reads, HA via failover.
- **Cons:** **replication lag** → followers can be stale (read-your-writes problem).

### Sync vs async replication

| Mode | Durability | Latency |
| --- | --- | --- |
| **Synchronous** | strong (wait for replica ack) | slower writes |
| **Asynchronous** | risk losing recent writes on leader crash | fast writes |

### Multi-leader / leaderless

- **Multi-leader** — writes on multiple nodes (multi-region); needs **conflict resolution**.
- **Leaderless** (Dynamo/Cassandra) — any node accepts writes; uses **quorums** (`W + R > N`) for consistency.

> **Read-your-writes fix:** route a user's reads to the leader for a short window, or read from a replica known to be caught up.

---

## 5. CAP & PACELC

### CAP theorem

During a **network partition (P)** you must choose:

- **CP** — consistency over availability (reject/err to stay correct). *e.g. seat booking.*
- **AP** — availability over consistency (serve possibly-stale data). *e.g. browsing.*

> CAP only applies **during a partition**; the rest of the time you can have both.

### PACELC (the fuller picture)

> **If Partition → choose A or C; Else (normal) → choose Latency or Consistency.**

Captures the everyday trade-off CAP ignores: even without partitions, replication forces **latency vs consistency**.

---

## 6. Partitioning & Sharding

> **Partitioning** = splitting a big dataset into pieces. **Sharding** = partitioning **across machines**. Needed when one DB can't handle the data/write volume.

### Why we need it

A **`users` table with 100M rows** on one server means **slow queries**, **DB overload**, and **hard to scale** (one machine has a CPU/RAM/disk ceiling).

- **Partitioning** — split the table *within one DB* → organize data, improve performance.
- **Sharding** — split data *across multiple DBs* → scale horizontally.

| Feature | Partitioning | Sharding |
| --- | --- | --- |
| Scope | Inside one DB | Across multiple DBs |
| Machines | Single machine | Multiple machines |
| Scaling | Limited (vertical) | **Horizontal** |
| Complexity | Low | High |

> **Analogy:** partitioning = drawers in **one cupboard**; sharding = **cupboards in different rooms**.

### Vertical vs horizontal

- **Vertical partitioning** — split *columns* / tables by feature (users DB, orders DB).
- **Horizontal partitioning (sharding)** — split *rows* across nodes.

### Partitioning types (within one DB)

| Type | How it splits | Example |
| --- | --- | --- |
| **Range** | by value ranges | `user_id 1–1M → p1`, `1M–2M → p2` |
| **List** | by discrete values | `country = India → p1`, `US → p2` |
| **Hash** | by hash of key | `user_id % 4 → partition` |

```sql
-- Orders split by year → query hits only orders_2024 (partition pruning)
SELECT * FROM orders WHERE year = 2024;
```

- ✅ Faster queries, better index usage, easy maintenance (drop/archive old partitions).
- ❌ Still **one DB server** → shares resources, **can't scale infinitely**.

### Why partitioning is faster — partition pruning

> **Partition pruning** = the DB skips partitions that can't contain matching rows, so it **scans less data**.

```sql
-- Table partitioned by year: orders_2023, orders_2024, orders_2025
SELECT * FROM orders WHERE order_date = '2024-05-10';
-- → DB reads only orders_2024, not the whole table
```

**Helps when the query uses the partition key:**

- **Point lookup:** `WHERE order_date = '2024-05-10'` → one partition.
- **Range:** `WHERE order_date BETWEEN '2024-01-01' AND '2024-12-31'` → only relevant partitions.

**Does NOT help when:**

- Query **doesn't use the partition key** — `WHERE user_id = 123` on a year-partitioned table → must scan **all** partitions.
- **Poor key choice** — partitioned by `country` but you always filter by `order_date` → no pruning.

> **Rule:** the **partition key must match your query pattern**, or you get no benefit.

### Partitioning vs indexing (they team up)

| | Reduces | Role |
| --- | --- | --- |
| **Partitioning** | how many rows are *scanned* | narrows the **search space** (which partition) |
| **Indexing** | how you *locate* rows | finds data quickly **inside** a partition |

```
100M-row table:
  no partition        → scan 100M rows
  + partition by year → scan ~10M (one partition)
  + index inside it   → scan a few rows   🚀
```

👉 **Best performance = partitioning + indexing.**

**Trade-off:** faster reads + easier maintenance (drop old partitions), but **more complexity**, ongoing **partition management**, and **no help for queries that skip the partition key**. Works best for **time-based / large datasets with predictable query patterns** (logs, orders).

### Sharding: how routing works

```
App → Shard Router → correct DB shard
```

1. **Choose a shard key** (usually `user_id`).
2. **Route** — range-based (`userId < 1M → shard1`) or hash-based (`shard = userId % N`, better spread).
3. **Query** the correct DB instance → merge if needed.

### Sharding strategies

| Strategy | How | Pros | Cons |
| --- | --- | --- | --- |
| **Range-based** | shard by key ranges (A–M, N–Z) | range queries easy | **hot shards** (skew) |
| **Hash-based** | `hash(key) % N` | even distribution | range queries hard; **resharding is painful** (`% N` changes) |
| **Consistent hashing** | hash ring | minimal reshuffling on add/remove | slightly complex (see dedicated note) |
| **Geo / directory** | by region / lookup table | locality, flexible | lookup table is a dependency |

### Problems sharding introduces

- **Cross-shard joins / queries** — expensive; avoid or denormalize.
- **Cross-shard transactions** — need saga / 2PC (see Outbox & Saga note).
- **Hot shards** — one popular key (a blockbuster show) overloads a shard → combine with caching / further splitting.
- **Resharding** — moving data when adding nodes; consistent hashing minimizes this.

> **Choosing a shard key:** pick one with **high cardinality + even access** (e.g. `user_id`, `show_id`). Avoid monotonic keys (timestamps) → they create hot shards.

### Joins & global queries (the real pain)

> Key insight: **sharding optimizes per-entity queries** (everything for one `user_id`) but **breaks the "global view"** of data. Partitioning keeps joins working but can make them *slower*.

**Sharding — joins are fundamentally hard.** Different machines = different memory + execution contexts, so one engine can't join across them.

```sql
-- ✅ Same shard key → lives on one shard → normal join works
SELECT * FROM users u JOIN orders o ON u.user_id = o.user_id
WHERE u.user_id = 123;

-- ❌ Cross-entity / no shard key → data spread across ALL shards
SELECT * FROM users u JOIN orders o ON u.user_id = o.user_id
WHERE o.amount > 1000;              -- must query every shard, join in app layer

-- ❌ Global aggregation → scatter-gather across shards, then merge
SELECT COUNT(*) FROM users;         -- count1 + count2 + count3 ...
```

- The good case works **only** when the query uses the shard key. Analytics, reporting, recommendations, and global search all break it.
- Other distributed failure modes: **hot shard** (celebrity `user_id` → one shard melts), **cross-shard transactions** (partial success → inconsistency, fix with **saga / eventual consistency**), and **partial outage** (shard 2 down → those users unavailable).

**Partitioning — joins still work, but pruning is conditional.** All partitions live in one engine, so joins are *correct*; the risk is **performance** when the query can't prune.

```
Non-partitioned + index on user_id     → 1 index lookup            ✅
Partitioned by user_id, filter user_id → prune to 1 partition      ✅ fastest
Partitioned by order_date, filter user_id → N partitions × lookup  ❌ slower than plain table
```

- So "isn't it the same as a non-partitioned table?" → **No.** A wrong partition key **multiplies** work: instead of one indexed lookup, the DB does one per partition (**partition misalignment**). Too many partitions adds planning overhead too.
- **Joining two partitioned tables:** if both share the join key as their partition key, the DB does an efficient **partition-wise join** (match partition ↔ partition). If they're partitioned differently (e.g. `orders` by date, `payments` by date but joined on `order_id`), it must combine data across many partitions → expensive.
- Partitioning **only** helps when the query aligns with the **partition key**.

> **Rule of thumb:** *Partitioning = easier joins, sometimes slower. Sharding = joins fundamentally hard (app-level join or scatter-gather + merge).* Align keys with your query pattern or you pay for it.

### When to use which

| Use **Partitioning** when | Use **Sharding** when |
| --- | --- |
| Data large but manageable on one server | Massive scale (millions+ users) |
| A single DB handles the load | One DB can't handle the load |
| Queries are predictable | Need horizontal scaling |
| *Common: logs, time-series* | *Common: user data, orders, messages* |

> **Mental model:** partitioning = **organize** data; sharding = **scale** data. Reach for partitioning first; shard only when a single DB can't cope.

---

## 7. Connection Pooling

> A **connection pool** keeps a set of **reusable, already-open** DB connections. Requests **borrow → use → return** a connection instead of opening a new TCP connection each time.

### Why pool at all?

- A DB connection is a **real TCP connection** (+ auth handshake) — expensive to open per request.
- **1 connection = 1 query at a time.** Multiple connections → **parallel** query execution.

```
100 users, 1 connection  → user 1 runs, everyone else WAITS   ❌ slow
100 users, 10 connections → 10 run in parallel, rest queue     ✅ fast
```

> **Restaurant analogy:** DB = kitchen, connections = waiters, requests = customers. 10 waiters → 10 customers served at once; the 11th waits for a free waiter.

### It's configurable & dynamic (not always exactly "10")

| Param | Meaning |
| --- | --- |
| **min** | connections kept warm at idle (e.g. `2`) |
| **max** | ceiling the pool can grow to (e.g. `10`) |
| **idle timeout** | idle connections closed after some time |

```
Fixed pool  (min=10, max=10): 10 connections opened and kept alive.
Dynamic pool(min=2,  max=10): start 2 → grow with load → shrink when idle.
```

- **Created** connections = pool capacity; **active** = currently running a query; **idle** = open but unused.
- So "are 10 always connected?" → **up to** max are maintained; some may be idle, and dynamic pools close/reopen idle ones.

### Exhaustion (the key failure mode)

```
max = 10
request 1..10  → connections 1..10 (all busy)
request 11     → no free connection → WAIT ⏳ (until one is returned or timeout)
```

> Under-sized pool → requests queue and latency spikes. Over-sized pools everywhere → you hit the **DB's own limit**.

### The DB has a hard cap too

`MySQL max_connections` (~100–1000). Pools **multiply** across services and shards:

```
User svc (10) + Order svc (10) + Payment svc (10)      = 30
× each connecting to Shard1/Shard2/Shard3 (×10 each)   → grows fast 😬
10 services × 10 connections = 100 → DB full
```

> **Tuning matters:** total connections across all app instances/pools must stay under the DB's `max_connections`. **A connection leak** (borrowing but never returning) silently drains the pool until every request hangs — a classic production outage.

### Config examples

```js
// Node.js (mysql)
const pool = mysql.createPool({ connectionLimit: 10 });
```

```properties
# Spring Boot (HikariCP)
spring.datasource.hikari.maximum-pool-size=10
```

> **Mental model:** pool = **capacity**, connections = **shared resources**, requests = **borrow → use → return**. Connections are **reused**, never created per-request.

---

## 8. Normalization vs Denormalization

| | Normalization | Denormalization |
| --- | --- | --- |
| Goal | remove redundancy | optimize reads |
| Writes | simpler, consistent | must update copies |
| Reads | more joins | fewer joins (fast) |
| Fits | SQL/OLTP | NoSQL / read-heavy / analytics |

> Rule of thumb: **normalize for correctness, denormalize for read performance** at scale.

---

## 9. Interview Cheat Sheet

> **"SQL or NoSQL?"**
>
> "Depends on access patterns. SQL for transactions, relationships, strong consistency; NoSQL for horizontal scale, flexible schema, high write throughput with eventual consistency. Often polyglot — SQL for orders, Redis for cache, Cassandra for events."

> **"ACID vs BASE?"**
>
> "ACID guarantees atomic, consistent, isolated, durable transactions — correctness. BASE trades that for availability and scale with eventual consistency. CAP forces the choice during partitions."

> **"How do you scale a database?"**
>
> "Read replicas for read scaling, caching to offload reads, then **shard** writes by a high-cardinality key. Handle cross-shard queries via denormalization and cross-shard writes via saga."

> **"What shard key would you pick?"**
>
> "High cardinality + even access distribution, aligned with the main query (e.g. `user_id`). Avoid monotonic keys to prevent hot shards; use consistent hashing to minimize resharding."

> **"Does partitioning improve performance?"**
>
> "Yes — via **partition pruning** it reduces the data scanned, but only when queries include the **partition key**. Otherwise the DB scans all partitions. Best paired with indexing: partitioning narrows the search space, indexing finds rows within it."

> **"Why are joins difficult in sharding?"**
>
> "Data lives on different machines, so a single engine can't join across them. Same-shard-key joins work, but cross-entity/global queries need **application-level joins or scatter-gather + merge** — inefficient. Sharding optimizes per-entity queries but breaks the global view."

> **"Why can partitioning make joins slower?"**
>
> "Joins still work (one engine), but if the query doesn't use the **partition key**, the DB can't prune and scans multiple partitions — N lookups instead of 1. A wrong/misaligned partition key multiplies work vs. a plain indexed table."

> **"How many DB connections does an app create?"**
>
> "It uses a **connection pool** with a configurable min/max. Connections are **reused** across requests (borrow → use → return); extra requests **wait** if the pool is exhausted. Total across all services/shards must stay under the DB's `max_connections`."

---

## 10. Final Takeaways

- **SQL = correctness/relationships; NoSQL = scale/flexibility** — often use both.
- **ACID vs BASE** is the correctness-vs-availability spectrum; **CAP/PACELC** name the trade-off.
- **Replication** scales reads + gives HA, but watch **replication lag**.
- **Sharding** scales writes; the hard parts are **shard key choice, hot shards, cross-shard ops, resharding**.
- **Consistent hashing** (separate note) is the standard way to shard with minimal reshuffling.
- **Connection pools** reuse a configurable min/max of open connections; watch **exhaustion**, **leaks**, and the DB's own **`max_connections`** across all services/shards.
