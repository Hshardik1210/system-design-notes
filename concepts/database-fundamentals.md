# Database Fundamentals

> Databases force you to trade off **correctness, speed, and scale** — here's the map.

> **How to read this doc:** each section gives the dense summary first, then a **deep dive** (annotated SQL, and the exact confusions that trip people up while learning). Skim the summaries for revision; read the deep dives to actually understand.

---

## Contents

- [1. SQL vs NoSQL](#1-sql-vs-nosql)
- [2. ACID vs BASE](#2-acid-vs-base)
- [3. Transaction Isolation Levels](#3-transaction-isolation-levels)
- [Locking & Deadlocks](#locking--deadlocks)
- [4. Replication](#4-replication)
- [5. CAP & PACELC](#5-cap--pacelc)
- [6. Partitioning & Sharding](#6-partitioning--sharding)
- [When NOT to shard](#when-not-to-shard)
- [7. Connection Pooling](#7-connection-pooling)
- [8. Normalization vs Denormalization](#8-normalization-vs-denormalization)
- [Common Mistakes](#common-mistakes)
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

### SQL vs NoSQL in detail

- **SQL** enforces a strict, fixed schema. Every row in a table has the *same* columns, which you define up front (`name`, `email`, `age`). Data is structured and related, and you can run precise queries across tables (joining Orders to Customers).
- **NoSQL** allows flexible, per-record structure. One document can have a phone number, the next may not. This is flexible and easy to spread across many machines, but *you* are responsible for keeping related records consistent.

In SQL you shape your data first, then query it however you like. In NoSQL you decide your queries first, then shape the data to make those queries fast.

```sql
-- SQL: rigid shape, defined up front. Every user row looks identical.
CREATE TABLE users (
  id    BIGINT PRIMARY KEY,
  name  VARCHAR(100) NOT NULL,
  email VARCHAR(200) UNIQUE NOT NULL
);
```

```json
// NoSQL (document store): each document can carry different fields.
{ "id": 1, "name": "Asha", "email": "asha@x.com", "prefs": { "theme": "dark" } }
{ "id": 2, "name": "Ravi", "phone": "+91-99..." }   // no email, extra phone — totally fine
```

#### Q: "Schemaless" means I never design anything?

No. It means the *database* doesn't force a shape — but your **application code still expects one**. If half your documents have `email` and half don't, your code has to handle both. You've just moved the schema from the database into your app. Good NoSQL modeling starts from *"what queries will I run?"* and stores data pre-shaped for those.

#### Q: Which NoSQL family do I use?

Match the family to the shape of your access pattern: **Key-Value** (Redis) for "give me the value for this key" (caching, sessions); **Document** (MongoDB) for self-contained entities (a product with its variants); **Wide-Column** (Cassandra) for write-heavy time-series; **Graph** (Neo4j) for "friends of friends" relationship traversal.

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

### What is a transaction, and what does ACID guarantee?

**A transaction is a group of steps that must all happen together, or not at all.**

**Example: transferring ₹100 from your account to a friend's.** It's two steps: (1) subtract ₹100 from you, (2) add ₹100 to your friend. If step 1 works but the server crashes before step 2, your ₹100 just *vanished*. A transaction says: "treat these two steps as **one indivisible action** — either both land, or neither does."

```sql
BEGIN;                                              -- start the transaction
  UPDATE accounts SET balance = balance - 100 WHERE id = 'me';
  UPDATE accounts SET balance = balance + 100 WHERE id = 'friend';
COMMIT;                                             -- both changes become permanent together
-- If anything fails before COMMIT, we ROLLBACK → it's as if nothing happened.
```

Now ACID is just the four promises around that group, each answering one "what if?":

- **Atomicity — "all or nothing."** If the transfer crashes halfway, the ₹100 subtraction is undone. No half-finished money movements.
- **Consistency — "rules always hold."** Any constraints you defined (balance can't go negative, `email` must be unique, a foreign key must point at a real row) are true *before and after*. The DB refuses to commit a state that breaks the rules.
- **Isolation — "transactions don't step on each other."** If two transfers run at the same time, the result is as if they ran one-after-another. Your in-progress changes are invisible to others until you commit (details in §3).
- **Durability — "committed = survives a crash."** Once `COMMIT` returns, that data is on disk. Pull the power cord and it's still there on restart.

#### Q: How is ACID's "Consistency" different from CAP's "Consistency"?

They're **different words that happen to collide** — a classic confusion:

- **ACID Consistency** = "the database's own rules/constraints are never violated" (single-node correctness).
- **CAP Consistency** (§5) = "every replica shows the same latest value" (agreement across many machines).

Same spelling, unrelated ideas.

#### Q: What does BASE trade away, and why would anyone want that?

BASE is the opposite philosophy for huge distributed systems:

- **Basically Available** — always answers, even if the answer is slightly stale.
- **Soft state** — data can be "in flux" while replicas sync up.
- **Eventual consistency** — if writes stop, all replicas *eventually* agree.

ACID suits a bank ledger (must be exact, right now). BASE suits something like a **social media like-count** — if you see 1,002 likes and your friend briefly sees 1,001, nobody cares, and the system stays fast and always-online. You trade instant correctness for **availability and scale**.

---

## 3. Transaction Isolation Levels

> Higher isolation = fewer anomalies but more locking / less concurrency.

**First, the three anomalies the levels exist to prevent** — meet them before the table, because the table is just "which of these do I tolerate?":

- **Dirty read** — you read a row another txn wrote but hasn't committed; if it rolls back, you acted on data that never existed.
- **Non-repeatable read** — you read the **same row** twice in one txn and the value changed underneath you (someone updated + committed in between).
- **Phantom read** — you run the **same query** twice and the *set* of matching rows changes (someone inserted a matching row).

Now the levels, cheapest → safest, each stopping one more anomaly:

| Level | Prevents | Still allows |
| --- | --- | --- |
| **Read Uncommitted** | — | dirty reads |
| **Read Committed** | dirty reads | non-repeatable reads |
| **Repeatable Read** | non-repeatable reads | phantom reads |
| **Serializable** | everything (acts serial) | lowest concurrency |

> Default for Postgres = Read Committed; MySQL InnoDB = Repeatable Read.

> ⚠️ **Read Uncommitted in production is almost always a bug.** It lets you act on data that may be rolled back a millisecond later. If you reached for it to "go faster," you want a better index or a read replica — not dirty reads.

### Isolation levels and the anomalies they stop

Isolation levels answer one question: **while my transaction is running, how much of other transactions' uncommitted work am I allowed to see?** More isolation = safer but slower (more locking); less isolation = faster but you risk anomalous reads.

The three anomalies:

- **Dirty read** — you read a row another transaction has written but *not yet committed*; if it rolls back, you acted on data that never really existed.
- **Non-repeatable read** — you read a row, then read the **same row** again later in the same transaction, and the value changed (another transaction updated and committed it in between).
- **Phantom read** — you run a query that matches 5 rows, run the **same query** again, and now 6 rows match (another transaction inserted a matching row). The rows you already saw didn't change — the result *set* grew.

The levels are just how many of these you're willing to tolerate, cheapest → safest:

```
Read Uncommitted → allows all three (fastest, rarely used)
Read Committed    → no dirty reads     (Postgres default)
Repeatable Read   → + no non-repeatable reads   (MySQL InnoDB default)
Serializable      → no anomalies at all; behaves as if txns ran one-by-one (slowest)
```

```sql
-- Concrete non-repeatable read:
-- Transaction A
BEGIN;
SELECT balance FROM accounts WHERE id = 1;   -- reads 500
                                             -- (meanwhile Transaction B commits balance = 300)
SELECT balance FROM accounts WHERE id = 1;   -- reads 300  ← same query, different answer!
COMMIT;
-- Under REPEATABLE READ, both SELECTs would return 500 (a stable snapshot).
```

#### Q: Why not always use Serializable if it's the safest?

Because safety costs **concurrency**. Serializable makes transactions wait for each other (via locks or by aborting-and-retrying conflicts), so throughput drops. Most apps pick the *lowest* level that's still correct for their use case — that's why Postgres defaults to Read Committed and MySQL to Repeatable Read, not Serializable.

#### Q: Isolation vs the "Consistency" in ACID — same thing?

Related but distinct. **Isolation** is specifically about *concurrent* transactions not corrupting each other's view. **Consistency** is the broader promise that constraints hold. Weak isolation can *lead* to inconsistency, which is why higher isolation exists.

---

## Locking & Deadlocks

> Isolation levels are *policy*; **locks** are the *mechanism* the DB uses to enforce them. When you contend for the same row (a seat, an inventory count, a balance), you pick one of two strategies.

### Pessimistic vs optimistic locking

- **Pessimistic** — assume conflict is likely, so **grab the lock up front** and make everyone else wait. Use `SELECT ... FOR UPDATE`: the row is locked until you commit.
- **Optimistic** — assume conflict is rare, so **don't lock**; instead detect a conflict at write time using a **version column** (or timestamp). If the version changed under you, your update fails and you retry.

```sql
-- Pessimistic: lock the row now; others block until COMMIT.
BEGIN;
SELECT * FROM seats WHERE seat_id = 'A1' FOR UPDATE;   -- row is locked
UPDATE seats SET status = 'BOOKED' WHERE seat_id = 'A1';
COMMIT;
```

```sql
-- Optimistic: no lock; the WHERE clause is the guard.
UPDATE seats SET status = 'BOOKED', version = version + 1
WHERE seat_id = 'A1' AND version = 7;   -- succeeds only if nobody else changed it
-- 0 rows affected → someone won the race → reload and retry.
```

> 💡 **Rule of thumb:** high contention on hot rows → **pessimistic** (avoid retry storms). Low contention / read-heavy → **optimistic** (no lock overhead). Pick based on how often you actually expect a collision.

### Deadlocks and how to avoid them

A **deadlock** is two transactions each holding a lock the other needs — both wait forever. Classic case: booking multiple seats.

```
Txn A locks seat A1, then wants A2
Txn B locks seat A2, then wants A1
→ each waits on the other → deadlock (the DB kills one with an error)
```

- **Fix: consistent lock ordering.** Always acquire locks in the *same* order (e.g. sort seat IDs before locking). Then two txns can never hold locks in a criss-cross pattern.
- Keep transactions **short** (lock less, for less time) and always be ready to **retry** the victim the DB aborts.

> This is exactly the seat-booking problem: see the [BookMyShow pessimistic-vs-optimistic locking](../system-design/bookmyshow-system-design.md#11-pessimistic-vs-optimistic-locking) and [multi-seat deadlock](../system-design/bookmyshow-system-design.md#13-multi-seat-booking--deadlocks) sections for the full pattern (including the Redis + DB hybrid at scale).

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

### Replication and the "I don't see my own comment" bug

**Replication = keeping identical copies of your data on multiple machines** so that if one dies you don't lose data, and so many machines can share the read load.

In leader–follower replication, the **leader** is the only node that accepts writes. Whenever data changes, it propagates the change to the **followers**, which copy it. Reads can be served from any follower (spreading read load), but only the leader accepts writes.

```
Writes → Leader          (single source of truth for changes)
           │  replicate (copy the change)
           ▼
Reads  → Followers        (many copies → scale reads)
```

**Replication lag** is the gap between "leader changed" and "followers copied it." That gap causes the most famous beginner bug:

```
1. You post a comment  → written to the LEADER
2. Page refreshes, reads from a FOLLOWER
3. The follower hasn't received the copy yet → your own comment is MISSING 😱
```

This is the **read-your-writes** problem. Fixes: for a few seconds after a user writes, read *their* data from the leader (or from a follower confirmed to be caught up).

#### Q: Synchronous vs asynchronous replication — which do I pick?

It's a **safety vs speed** trade:

- **Synchronous** — the leader waits for a follower to confirm "got it" before telling you the write succeeded. Safer (no data loss if the leader dies), but every write is slower.
- **Asynchronous** — the leader replies "done" immediately and copies to followers in the background. Fast, but if the leader crashes before copying, those last few writes are **lost**.

Many systems compromise: replicate synchronously to *one* follower, asynchronously to the rest.

#### Q: What's the difference between leader-follower, multi-leader, and leaderless?

- **Leader-follower** — one writer, many readers. Simple; the default.
- **Multi-leader** — several nodes accept writes (e.g. one per region for low latency). Fast globally, but two regions can edit the same thing → you need **conflict resolution**.
- **Leaderless** (Dynamo/Cassandra) — *any* node takes writes; correctness comes from **quorums**: require `W + R > N` (writes acked by `W` nodes, reads from `R` nodes, `N` total copies) so read and write sets always overlap on at least one up-to-date node.

> 💡 **Why `W + R > N` works:** if writes touch `W` nodes and reads touch `R` nodes out of `N`, and `W + R > N`, the two sets *must* share at least one node — so a read always sees at least one copy that has the latest write. With `N=3`, `W=2, R=2` is the common balanced choice.

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

### CAP in concrete terms

CAP is about what happens when your machines **can't talk to each other** (a **network partition** — a cable is cut, a data center is unreachable). In that moment you're forced to choose between two bad options.

Suppose two replicas of your database can no longer reach each other, and a request arrives that they can't coordinate on:

- **CP (choose Consistency)** — the replica **refuses the request or errors** rather than risk returning a wrong answer. Correct, but **unavailable**. → seat booking, payments.
- **AP (choose Availability)** — the replica **answers with its local data** and reconciles later. Always responds, but two replicas may diverge and briefly disagree. Available, but **possibly inconsistent**. → social feeds, product browsing.

```
Network partition happening?
   ├── keep answering, risk staleness   → AP
   └── refuse/error to stay correct     → CP
```

> Key nuance beginners miss: **you only make this choice *during* a partition.** When the network is healthy, you can have both C and A.

**Worked example — a booking system loses a network link between two availability zones (AZs):**

- **If you chose CP:** the shard holding seat `A1` can't confirm no other AZ is booking it, so it **refuses the write** and returns an error. The user sees *"couldn't complete booking, try again."* Annoying, but **no double-booking** — the right call for seats and payments.
- **If you chose AP:** each AZ keeps accepting bookings from its local copy. Both stay responsive, but two users in different AZs can book **the same seat**; you discover the conflict when the partition heals and must reconcile (cancel + refund one). Fine for a like-count, disastrous for a seat.
- **Takeaway:** for money/inventory, prefer **CP** (a clear error beats a silent double-book). For feeds and browsing, prefer **AP** (stay up, reconcile later).

#### Q: So what does PACELC add?

CAP only describes the rare partition case. **PACELC** adds the *everyday* case: **"if Partition → A or C; Else → Latency or Consistency."** Even when nothing is broken, keeping replicas perfectly in sync takes time, so you still trade **speed vs freshness** on every request. It's the more honest, full-time version of CAP.

#### Q: Isn't "you can only pick 2 of 3 (C, A, P)" the rule?

That's the popular-but-sloppy phrasing. You don't get to "give up P" — partitions *will* happen whether you like it or not. The real statement is: **when a partition occurs, you must sacrifice either C or A.** So it's really a choice between **CP** and **AP**.

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

> **In short:** partitioning splits data *within one database*; sharding splits data *across multiple databases/machines*.

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

### Partitioning vs sharding in one picture

- **Partitioning** = splitting a table into pieces **within one database** (e.g. by year: 2023, 2024, 2025). Same server, just organized so a query touches one partition instead of the whole table. Still limited by that one server's capacity.
- **Sharding** = splitting data **across multiple databases on different machines**. Now you have near-unlimited capacity, but to find a row you must first know *which shard* holds it.

```
Partitioning:   [ one DB ]  →  partition A | partition B | partition C     (organize)
Sharding:        [DB 1]  [DB 2]  [DB 3]  on different machines             (scale)
```

#### Q: What is a "shard key" and why does the choice matter so much?

The **shard key** is the field the system uses to decide *which shard a row lives on* (e.g. `user_id`). It matters because:

- **If your query includes the shard key** → the router walks straight to the one shard. Fast.
- **If it doesn't** → the system must ask *every* shard and merge results ("scatter-gather"). Slow.

```
shard = hash(user_id) % numberOfShards     -- same user → always same shard
```

Pick a key with **high cardinality** (lots of distinct values) and **even access** so load spreads out. Avoid a **monotonic** key like a timestamp — all new writes pile onto the newest shard, creating a **hot shard** (one overloaded machine while the rest idle).

#### Q: Why does everyone say "joins are hard once you shard"?

Because a join needs all the rows *in one place* to match them up. Within one DB (even partitioned), they're all there — joins just work. Across shards, the rows for a join might sit on different machines that can't see each other's memory, so you either join in your app code or scatter-gather. That's why teams **denormalize** (§8) before sharding — so a single shard already holds everything a query needs.

---

## When NOT to shard

> ⚠️ Sharding is a **one-way door**: it multiplies operational cost and permanently complicates every query. Exhaust the cheaper options first.

Hold off on sharding when:

- **You still have vertical headroom.** A bigger box (more RAM/CPU/NVMe) plus **read replicas** and a **cache** solves a huge fraction of "the DB is slow" problems — without touching your query layer.
- **You need cross-shard queries or transactions.** If your workload is full of joins across entities, global aggregations, or multi-row transactions, sharding turns each into a scatter-gather or a saga. That pain often outweighs the scale benefit.
- **The team is small.** Sharding adds resharding, rebalancing, hot-shard firefighting, and per-shard failure handling — real ongoing headcount cost.

> 💡 **Order of operations for "the DB can't keep up":** (1) add indexes / fix queries → (2) cache hot reads → (3) read replicas → (4) vertical scale → (5) *then* shard. Sharding is the last resort, not the first reflex.

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

> **In short:** the pool holds a fixed set of connections; requests borrow one, use it, and return it. With 10 connections, 10 requests run at once; the 11th waits for one to free up.

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

### Why a pool instead of connecting each time?

Opening a fresh DB connection per request is expensive — each one is a real TCP handshake plus authentication, often tens of milliseconds. Under load, doing that on every request would crush both your app and the DB.

A connection pool avoids this by keeping a small set of already-open connections. A request **borrows one, uses it, and returns it** for the next request to reuse — no per-request setup cost.

> ⚠️ **Create the pool once, at startup — never per request.** A pool built inside a request handler opens fresh connections every call (defeating the entire point) and leaks them under load until the DB hits `max_connections`. The pool is a long-lived, app-wide singleton.

#### Q: If more connections = more parallelism, why not set the pool huge?

Because the **database itself has a hard ceiling** (`max_connections`, often ~100–1000), and that ceiling is shared by *every* service and *every* shard connecting to it:

```
10 services × a pool of 10 each = 100 connections → a small DB is already full.
```

An oversized pool also just makes requests queue *inside the DB* instead of inside your app. The right size is usually surprisingly small (a common rule of thumb is a few per CPU core), tuned by testing.

#### Q: What's a "connection leak"?

Borrowing a connection and **forgetting to return it** (usually a missing "close" in error handling). Each leak permanently removes one connection from the pool. Slowly the pool empties, then *every* request hangs forever waiting for a connection that never comes back — a classic silent production outage. Always return connections in a `finally`/`try-with-resources` block.

---

## 8. Normalization vs Denormalization

| | Normalization | Denormalization |
| --- | --- | --- |
| Goal | remove redundancy | optimize reads |
| Writes | simpler, consistent | must update copies |
| Reads | more joins | fewer joins (fast) |
| Fits | SQL/OLTP | NoSQL / read-heavy / analytics |

> Rule of thumb: **normalize for correctness, denormalize for read performance** at scale.

### Normalization, denormalization, and keys

**Normalization = store each fact exactly once, and point at it instead of copying it.**

**Example: students and their school.** The naive approach writes the full school name and address next to *every* student. If the school moves, you must edit 500 rows and will inevitably miss some (now your data disagrees with itself). Normalization instead keeps **one** `schools` row and has each student store just the school's **id**.

```sql
-- Normalized: the school's details live in ONE place.
CREATE TABLE schools (
  id      BIGINT PRIMARY KEY,          -- PRIMARY KEY = the unique id for a school row
  name    VARCHAR(200),
  address VARCHAR(300)
);

CREATE TABLE students (
  id        BIGINT PRIMARY KEY,
  name      VARCHAR(100),
  school_id BIGINT REFERENCES schools(id)   -- FOREIGN KEY: "this must point to a real school"
);

-- Reading a student's school now needs a JOIN:
SELECT s.name, sch.name AS school
FROM students s JOIN schools sch ON s.school_id = sch.id;
```

**Denormalization** does the opposite on purpose: it **copies** the school name into the students table so reads need *no join* — faster to read, but now you must update every copy on change. You trade write-simplicity for read-speed.

#### Q: What are keys and constraints, in plain terms?

They're the **rules the database enforces for you** so bad data can't get in:

- **Primary key** — the unique identifier for a row (like `user_id`). No two rows share it; it's never null. It's how other tables refer to this row.
- **Foreign key** — a column that must point to an existing row in another table (`student.school_id` must match a real `schools.id`). This is **referential integrity** — you can't have an order for a customer who doesn't exist.
- **Unique constraint** — no duplicates allowed in this column (e.g. one account per `email`).
- **NOT NULL / CHECK** — "this field is required" / "this value must satisfy a rule" (e.g. `age >= 0`).

These are the concrete mechanisms behind ACID's **Consistency** — the DB *rejects* any write that would break them.

#### Q: When do I normalize vs denormalize?

Default to **normalized** for transactional systems (orders, users, payments) where correctness matters and data changes often — one source of truth means no contradictions. Reach for **denormalized** in read-heavy or analytics/NoSQL scenarios where joins are expensive or impossible (e.g. across shards), and you'd rather store data pre-joined for fast reads. Summary: **normalize for correctness, denormalize for read speed.**

---

## Common Mistakes

The failure modes that show up in real incidents (and interviews) far more often than exotic edge cases:

- **Wrong shard key.** Picking a low-cardinality or monotonic key (e.g. a timestamp) piles all new writes onto one shard → a **hot shard** while the rest idle. Worse, if the key doesn't match your queries, *every* read becomes a scatter-gather. Choose **high cardinality + even access, aligned with your main query** (§6).
- **Running analytics on the OLTP primary.** A heavy reporting query (`GROUP BY` over millions of rows) hogs CPU, locks, and buffer cache, and your customer-facing writes stall behind it. Send analytics to a **read replica** or a separate warehouse — never the transactional primary.
- **Reading a replica right after a write.** Replication lag means the follower may not have your write yet → the user "loses" their own change (the read-your-writes bug, §4). For read-after-write, route that user to the **leader** for a short window.
- **Sharding before exhausting vertical scale + caching.** Teams shard as a reflex and inherit permanent complexity. Add indexes, cache, and read replicas and scale the box up **first** (see [When NOT to shard](#when-not-to-shard)).
- **Read Uncommitted in production.** Chasing throughput with dirty reads means acting on data that may roll back. Fix the query or add a replica instead (§3).
- **A connection pool per request.** Building the pool inside the request path opens connections every call and leaks them until the DB is full. The pool is an **app-wide singleton created at startup** (§7).

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

### Related notes

- [Databases — Deep Dive](databases-deep-dive.md) — storage internals (B-Tree/LSM/column/vector), MySQL vs Postgres at scale, Mongo/Cassandra/Redis/Elasticsearch/Vector DBs with example queries, and how clusters route to the right node.
- [Database Indexing](database-indexing.md) · [Consistent Hashing](consistent-hashing.md) · [Caching Strategies](caching-strategies.md)
