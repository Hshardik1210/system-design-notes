# Databases — Deep Dive (SQL, NoSQL, Storage Internals & Scaling)

> **Goal:** understand **how each database physically stores data**, **when to use which**, and **how to scale each** — including the hard part: *how a cluster decides which node holds a given key* (Redis hash slots, Mongo config servers, Cassandra token ring). This is the "which DB and why" note.
>
> Builds on **Database Fundamentals** (SQL vs NoSQL, ACID/BASE, CAP) and **Database Indexing** (B-Tree internals).

---

## Contents

- [1. How to Choose a Database (framework)](#1-how-to-choose-a-database-framework)
- [2. Storage Engine Internals (how data is physically stored)](#2-storage-engine-internals-how-data-is-physically-stored)
- [3. SQL Databases](#3-sql-databases)
- [4. MySQL vs PostgreSQL (and which for high scale)](#4-mysql-vs-postgresql-and-which-for-high-scale)
- [5. Distributed / NewSQL (SQL at high scale)](#5-distributed--newsql-sql-at-high-scale)
- [6. NoSQL — The Families](#6-nosql--the-families)
- [7. MongoDB (document)](#7-mongodb-document)
- [8. Cassandra (wide-column)](#8-cassandra-wide-column)
- [9. Redis (in-memory KV)](#9-redis-in-memory-kv)
- [10. Elasticsearch (search)](#10-elasticsearch-search)
- [11. Vector Databases](#11-vector-databases)
- [12. DynamoDB & others (quick hits)](#12-dynamodb--others-quick-hits)
- [13. Replication, Partitioning & Routing (the scaling core)](#13-replication-partitioning--routing-the-scaling-core)
- [14. Decision Cheat Sheet](#14-decision-cheat-sheet)
- [15. Interview Cheat Sheet](#15-interview-cheat-sheet)
- [16. Final Takeaways](#16-final-takeaways)

---

## 1. How to Choose a Database (framework)

Ask these, in order:

| Question | Pushes you toward |
| --- | --- |
| **Access pattern?** (key lookup / range / analytics / search / graph / similarity) | KV / SQL / column-store / search / graph / vector |
| **Structure?** (rigid schema + relations vs flexible/nested) | SQL vs document |
| **Consistency need?** (strong ACID vs eventual OK) | SQL/NewSQL vs eventually-consistent NoSQL |
| **Read vs write heavy?** | B-Tree stores (balanced) vs **LSM** stores (write-heavy) |
| **Scale?** (single node fine vs must shard horizontally) | Postgres/MySQL vs Cassandra/Mongo/DynamoDB |
| **Query flexibility?** (joins, ad-hoc) vs known queries | SQL vs denormalized NoSQL |
| **Latency?** (sub-ms) | in-memory (Redis) |

> **Default advice:** start with a **relational DB (Postgres)** — it handles most workloads, gives ACID + flexible queries, and scales further than people think. Reach for NoSQL when you hit a **specific** need it solves (massive write scale, flexible schema, key-value latency, full-text search, similarity search).

---

## 2. Storage Engine Internals (how data is physically stored)

The single most important thing to understand — **the storage engine determines read/write performance**.

### 2.1 B-Tree / B+Tree (most SQL DBs: InnoDB, Postgres)

Data + indexes stored in **fixed-size pages** (e.g. 8–16 KB) arranged as a balanced tree; leaves hold the data (or pointers), sorted by key.

```
          [ 50 | 100 ]                 ← internal nodes (keys guide the search)
         /     |      \
   [10 20 30] [60 70] [110 120]        ← leaf pages (sorted); B+Tree links leaves for range scans
```

- **Reads:** O(log n) — few page reads to find a key; **great for point + range queries** (leaves are sorted & linked).
- **Writes:** update-in-place → may cause **random I/O** + page splits → slower under very high write volume.
- **Clustered index** (InnoDB): the table **is** the primary-key B-Tree (rows stored in PK order → PK lookups are fast; secondary indexes store the PK, needing a second lookup).
- **Heap + separate indexes** (Postgres): rows in an unordered **heap**; every index (incl. PK) points to a row location (`ctid`).

### 2.2 LSM Tree (Log-Structured Merge — Cassandra, RocksDB, LevelDB, ScyllaDB)

Optimized for **writes**. Writes go to memory + an append-only log, flushed to immutable sorted files.

```
write → append to commit log (durability) + insert into MemTable (in-memory sorted)
MemTable full → flush to disk as an immutable SSTable (sorted)
Background COMPACTION merges SSTables, drops overwritten/deleted (tombstone) rows
read → check MemTable + multiple SSTables (Bloom filters skip files that lack the key)
```

- **Writes: extremely fast** (sequential append, no in-place update) → ideal for write-heavy (logs, time-series, feeds).
- **Reads: slower** (may touch several SSTables) → mitigated with **Bloom filters** + caching.
- **Compaction** reclaims space and keeps read amplification down.

> **B-Tree vs LSM in one line:** B-Tree = balanced read/write, in-place, great range queries (SQL default). **LSM = write-optimized**, append-only, needs compaction (Cassandra, RocksDB).

### 2.3 Row-store vs Column-store (OLTP vs OLAP)

| | **Row store** (OLTP) | **Column store** (OLAP) |
| --- | --- | --- |
| Layout | All columns of a row together | Each column stored together |
| Good for | Fetch/update whole rows (transactions) | Scan few columns over billions of rows (analytics) |
| Compression | Lower | **High** (similar values adjacent) |
| Examples | MySQL, Postgres | Redshift, ClickHouse, BigQuery, Druid, Parquet |

> Analytics query like `SUM(revenue) GROUP BY day` reads only 2 columns → column stores read far less data. Don't run heavy analytics on your OLTP DB; ship to a warehouse.

### 2.4 Other storage models

| Model | Stored as | Engine |
| --- | --- | --- |
| **Document** | BSON/JSON documents (self-contained, nested) | MongoDB (WiredTiger = B-Tree/LSM hybrid) |
| **Key-Value (in-memory)** | Hash table of rich data structures in RAM | Redis |
| **Inverted index** | term → list of docs containing it | Elasticsearch/Lucene |
| **Vector index** | high-dim vectors + ANN graph (HNSW) | Pinecone, Milvus, pgvector |
| **Graph** | nodes + edges (adjacency) | Neo4j |

---

## 3. SQL Databases

Relational: rigid **schema**, **ACID** transactions, **joins**, SQL. Data in tables (rows); relationships via foreign keys; strong consistency.

```sql
-- storage: rows in B-Tree pages; indexes are separate B-Trees
CREATE TABLE users (
  id BIGINT PRIMARY KEY,          -- clustered index (InnoDB) / heap + PK index (Postgres)
  email VARCHAR(255) UNIQUE,
  city VARCHAR(100)
);
CREATE INDEX idx_users_city ON users(city);   -- secondary B-Tree

SELECT u.email, o.total
FROM users u JOIN orders o ON o.user_id = u.id   -- JOIN: relational superpower
WHERE u.city = 'BLR' AND o.total > 1000;
```

**Strengths:** ACID, joins, ad-hoc queries, strong consistency, mature tooling.
**Weakness:** horizontal write scaling is harder (single primary for writes) → sharding is manual (until NewSQL).

---

## 4. MySQL vs PostgreSQL (and which for high scale)

Both are excellent relational DBs. Differences that matter:

| Aspect | **MySQL (InnoDB)** | **PostgreSQL** |
| --- | --- | --- |
| Storage | **Clustered index** (table = PK B-Tree); secondary index → PK → row | **Heap** + separate indexes (index → `ctid`) |
| Concurrency | MVCC (undo logs) | **MVCC** (row versions in heap; `VACUUM` cleans dead tuples) |
| Feature depth | Simpler, very fast reads, huge ecosystem | Richer: JSONB, arrays, GIS (PostGIS), full-text, window fns, CTEs, extensions |
| Replication | Async/semi-sync; **read replicas**; Group Replication | Streaming replication; logical replication |
| Write scale | Vitess (YouTube) shards MySQL | Citus extension shards Postgres |
| Reputation | Web-scale reads, simplicity | Correctness, complex queries, extensibility |

### Which is "better for high scale," and how?

- **Neither scales writes on a single node forever** — both have **one primary for writes**. High scale comes from the **same techniques** on top:
  1. **Read replicas** — offload reads (both).
  2. **Connection pooling** (PgBouncer / ProxySQL) — avoid connection exhaustion.
  3. **Partitioning** — split big tables by range/hash within one DB.
  4. **Sharding** — split data across many DBs: **Vitess** (MySQL, powers YouTube/Slack) or **Citus** (Postgres). This is how you get horizontal write scale.
  5. **Caching** (Redis) in front to cut read load.
- **Postgres** is usually the better *default* (richer features, JSONB, extensions like Citus/PostGIS/pgvector). **MySQL** shines for simple high-read web workloads and has the battle-tested **Vitess** sharding layer.
- If you need **transparent horizontal SQL scale out of the box**, use **distributed/NewSQL** (next section) rather than bolting sharding onto MySQL/Postgres.

> **Interview answer:** "Both use a single write primary, so raw MySQL/Postgres scale reads via replicas and writes via **sharding (Vitess for MySQL, Citus for Postgres)** or **partitioning**. For built-in horizontal scale with SQL semantics, I'd reach for CockroachDB/Spanner. I default to Postgres for its feature set."

---

## 5. Distributed / NewSQL (SQL at high scale)

Give you **SQL + ACID + horizontal scale** by sharding automatically under the hood.

| DB | Notes |
| --- | --- |
| **Google Spanner** | Globally distributed, strong consistency via **TrueTime** (synced clocks); the gold standard |
| **CockroachDB** | Spanner-inspired, open source; auto-sharded ranges, Raft consensus per range |
| **YugabyteDB** | Postgres-compatible distributed SQL |
| **Vitess** | Sharding layer over MySQL (YouTube) |
| **Citus** | Extension turning Postgres into a sharded cluster |
| **Amazon Aurora** | Cloud MySQL/Postgres with a shared distributed storage layer (scales reads + storage, single writer) |

> **How they scale writes:** data is split into **ranges/shards**, each replicated via **consensus (Raft/Paxos)**; different shards have different leaders on different nodes → writes parallelize across the cluster while keeping ACID.

---

## 6. NoSQL — The Families

"NoSQL" = non-relational, usually **schema-flexible**, **horizontally scalable**, often **eventually consistent** (BASE). Choose by **data model + access pattern**.

| Family | Model | Examples | Best for |
| --- | --- | --- | --- |
| **Key-Value** | `key → value` | Redis, DynamoDB, Memcached | Cache, sessions, sub-ms lookups |
| **Document** | JSON/BSON docs | MongoDB, Couchbase | Flexible/nested entities, evolving schema |
| **Wide-Column** | rows with dynamic columns, partitioned | Cassandra, ScyllaDB, HBase, Bigtable | Massive write scale, time-series, feeds |
| **Search** | inverted index | Elasticsearch, OpenSearch, Solr | Full-text search, log analytics |
| **Graph** | nodes + edges | Neo4j, JanusGraph | Relationships, recommendations, fraud |
| **Time-Series** | timestamped points | InfluxDB, TimescaleDB, Prometheus | Metrics, IoT, monitoring |
| **Vector** | embeddings + ANN | Pinecone, Milvus, Weaviate, pgvector | Semantic search, RAG, recommendations |

> **Key trade-off:** NoSQL scales by **denormalizing + partitioning** and dropping cross-shard joins/transactions. You **model for your queries** up front (query-first design), unlike SQL's flexible ad-hoc queries.

---

## 7. MongoDB (document)

Stores **BSON documents** (binary JSON) in collections; flexible/nested schema.

```javascript
// A document is self-contained (embeds related data → fewer joins)
db.users.insertOne({
  _id: 123, name: "Hardik", city: "BLR",
  orders: [ { id: 789, total: 1500 }, { id: 790, total: 200 } ]   // embedded
});

db.users.find({ city: "BLR", "orders.total": { $gt: 1000 } });    // query
db.users.aggregate([                                               // aggregation pipeline
  { $match: { city: "BLR" } },
  { $group: { _id: "$city", revenue: { $sum: "$orders.total" } } }
]);
db.users.createIndex({ city: 1 });                                 // B-Tree index
```

- **Storage engine: WiredTiger** — B-Tree-based, document-level concurrency, compression.
- **Schema-flexible**: documents in a collection can differ; great when the schema evolves.
- **Model for reads**: embed data you read together; reference when data is large/shared.

### How MongoDB scales — replica sets + sharding

**Replica set** (HA): 1 **primary** (writes) + secondaries (replicate, serve reads). Primary fails → **election** promotes a secondary.

**Sharding** (horizontal scale) — this answers *"how does it know which node has the data?"*:

```
Client → mongos (query router) → asks CONFIG SERVERS for metadata → routes to the right SHARD

Components:
  - Shards        : each is a replica set holding a SUBSET of the data
  - Config servers: store the METADATA — which CHUNK (shard-key range) lives on which shard
  - mongos        : stateless router; caches config metadata; routes queries
```

- You pick a **shard key**. Data is split into **chunks** (contiguous shard-key ranges, or hashed).
- **Config servers** hold the map `chunk range → shard`. `mongos` consults it (cached) to route.
- **Targeted query** (includes shard key) → routed to the **one shard** owning that chunk. ✅ fast.
- **Scatter-gather** (no shard key in query) → sent to **all shards**, results merged. ⚠️ slower — so **choose a shard key that matches your queries**.
- A **balancer** migrates chunks between shards to keep them even.
- **Adding a node/shard**: register it; the balancer moves some chunks onto it → capacity grows.

> **Shard key choice is critical:** high cardinality + even distribution + present in common queries. A bad key (e.g. monotonically increasing) creates a **hot shard**.

---

## 8. Cassandra (wide-column)

**Write-optimized (LSM)**, **masterless**, linearly scalable, tunable consistency. Data modeled **query-first**.

```sql
-- Table with a composite key: PARTITION KEY + CLUSTERING columns
CREATE TABLE messages (
  chat_id   uuid,            -- PARTITION KEY → decides the node
  sent_at   timestamp,       -- CLUSTERING KEY → sort order within the partition
  sender    text,
  body      text,
  PRIMARY KEY (chat_id, sent_at)
) WITH CLUSTERING ORDER BY (sent_at DESC);

-- Efficient: query by partition key (goes to the right node) + range on clustering key
SELECT * FROM messages WHERE chat_id = ? AND sent_at > ? LIMIT 50;
-- Inefficient / disallowed: query by a non-key column (needs ALLOW FILTERING = full scan)
```

- **Partition key** → hashed → placed on the **ring**; all rows with that key live together on the same node(s), sorted by clustering key.
- **Model per query**: you often duplicate data into multiple tables, one per query shape (no joins).

### How Cassandra scales — the token ring (no master)

```
Consistent hashing ring: hash(partition key) = a TOKEN → owned by the node responsible for that token range
Every node knows the ring (gossip protocol). Any node can be the COORDINATOR for a request.
```

- **No master** — every node is equal. A client connects to any node = the **coordinator**, which forwards to the replica nodes owning that token (token-aware clients skip a hop).
- **Replication factor (RF)**: each partition stored on RF nodes (e.g. 3) around the ring.
- **Tunable consistency** per query: `ONE`, `QUORUM`, `ALL`. `R + W > RF` → strong consistency (e.g. QUORUM read + QUORUM write). Otherwise eventual.
- **Adding a node**: it claims token ranges (vnodes) → takes over a slice of data → **linear scale**, minimal reshuffle (consistent hashing).
- **Writes never blocked by reads** (LSM append) → ideal for huge write throughput.

> **Cassandra vs Mongo scaling:** Cassandra is **masterless** (consistent-hashing ring, any node coordinates); Mongo has a **primary per shard** + a router (`mongos`) + config servers.

---

## 9. Redis (in-memory KV)

Data lives **in RAM** → sub-millisecond. Not just strings — **rich data structures**. (AWS **ElastiCache** = managed Redis/Memcached.)

```
SET user:123 "Hardik"                 EX 3600      # string + TTL
HSET user:123 name Hardik city BLR                 # hash (object)
LPUSH feed:123 post1 post2                          # list
ZADD leaderboard 500 user:123                       # sorted set (leaderboards!)
INCR counter:page_views                             # atomic counter
SET lock:seat NX EX 30                              # distributed lock
GEOADD drivers 77.6 12.9 driver:1                   # geospatial
```

- **Persistence** (optional): **RDB** (periodic snapshots) + **AOF** (append-only log of writes) → durability on restart; but Redis is usually a **cache**, DB is source of truth.
- **Eviction** when memory full: LRU/LFU (approximate — see Distributed Cache note).
- **Uses:** cache, sessions, rate limiting, leaderboards (sorted sets), queues, pub/sub, distributed locks, geo.

### Why Redis (ElastiCache) is fast — and what it's *not*

| Why fast | Detail |
| --- | --- |
| **In-memory** | Data in RAM, not disk → **microsecond** access (RAM is ~100,000× faster than disk seeks) |
| **O(1) hash lookup** | `GET key` is a hash-table lookup — no scan, no index tree to walk |
| **Single-threaded event loop** | No lock contention / context switching for commands; simple + predictable |
| **Purpose-built data structures** | Sorted set = skip list → `O(log n)` rank/range (leaderboards); no query planner overhead |
| **No query parsing/planning** | You issue a direct command against a known key — nothing to optimize |

> ⚠️ **Redis is fast for lookups by *key*, not for *search*.** `GET user:123` is instant, but "find all users whose name contains 'har'" is **not** what core Redis does — that's a **search** problem (→ Elasticsearch's inverted index, §10). Redis *can* do sorted-set range queries and geo, and the **RediSearch** module adds secondary indexes, but plain key-value Redis is not a text-search engine. **ElastiCache = the cache; Elasticsearch = the search engine.**

### How is Redis concurrent if it's single-threaded?

**Concurrency ≠ parallelism.** Redis handles **thousands of concurrent connections** on one thread, but **executes one command at a time**. It never runs two commands in parallel — and that's a feature, not a bug.

```
Many clients ─┐
              ├─►  [ single event loop ]  ─► process cmd A ─► process cmd B ─► ...
Many clients ─┘     (I/O multiplexing:        (one at a time, each ~microseconds)
                     epoll/kqueue watches
                     thousands of sockets)
```

- **Event loop + I/O multiplexing (epoll/kqueue):** one thread watches thousands of sockets with **non-blocking I/O** (the *reactor* pattern, like Node.js). When a socket has a ready command, Redis runs it to completion, then moves to the next ready socket. So it's **concurrent at the connection level, serial at the execution level**.
- **Why single-threaded is still blazing fast:** everything is **in memory**, so each command takes **microseconds** — there's nothing to wait on (no disk, no network mid-command). The bottleneck is usually **network/RAM bandwidth, not CPU**. Meanwhile it pays **zero cost** for locks, mutexes, context switches, or race conditions.

**The big payoff — atomicity for free:**
- Because commands run **one at a time to completion**, every command is **inherently atomic**. No interleaving → no race conditions.
- This is *why* `INCR`, `SETNX`, `LPUSH`, `ZADD` etc. are atomic **without any locking** — perfect for counters, **rate limiting**, and **distributed locks** (`SET key val NX EX 30`).
- **`MULTI`/`EXEC` transactions** and **Lua scripts** also run atomically — no other client's command can sneak in between.

**Caveats & nuances:**
| Point | Detail |
| --- | --- |
| **Slow command = blocks everything** | A single O(n) command (`KEYS *`, big `SORT`, huge `LRANGE`) stalls all clients. Use `SCAN` (cursor) instead of `KEYS`; avoid big blocking ops on the hot path |
| **Uses only one CPU core** | To use more cores, run **multiple instances / shards** (Redis Cluster), one per core — parallelism comes from *more processes*, not threads |
| **Redis 6+ threaded I/O** | Network read/parse and response write can be **multi-threaded**, but **command execution stays single-threaded** — so atomicity + lock-free design are preserved while the network layer scales |

> **One-liner:** "Redis is single-threaded for *command execution* but concurrent for *connections* via a non-blocking event loop (epoll). Since everything's in memory, each command is microseconds, so serial execution isn't a bottleneck — and running commands one-at-a-time makes every operation atomic with no locks. Scale across cores by sharding into multiple instances; slow O(n) commands are the thing to avoid since they block the loop."

### How Redis Cluster scales — 16384 hash slots (this is the routing answer)

Redis Cluster shards data across masters using **hash slots** (not consistent hashing):

```
There are exactly 16384 hash slots.
slot = CRC16(key) mod 16384
Each MASTER node owns a RANGE of slots, e.g.:
   Node A: slots 0     – 5460
   Node B: slots 5461  – 10922
   Node C: slots 10923 – 16383
```

**How the right node is found (no central router!):**
- The client computes `CRC16(key) mod 16384` → the slot → looks up which node owns that slot.
- **Smart clients** cache the full **slot → node** map (fetched via `CLUSTER SLOTS`) and connect **directly** to the correct node.
- If a client hits the **wrong** node, that node replies **`MOVED <slot> <ip:port>`** → client updates its map and retries on the right node.
- During resharding, a slot mid-migration returns **`ASK`** (redirect just this request to the new node).
- Nodes learn topology from each other via a **gossip protocol** (each master also has replicas for failover).

> **Key point:** Redis Cluster has **no single master deciding routing** — it's **client-side routing by hash slot**, with `MOVED`/`ASK` redirects as the fallback. Multiple masters each own a slice of the 16384 slots; replicas provide HA.

- **Multi-key ops** must land in the **same slot** → use a **hash tag**: `{user123}:profile` and `{user123}:orders` hash only on `user123` → same slot → same node.
- **Adding a node**: assign it some slots; the cluster **migrates those slots' keys** to it → capacity grows.

---

## 10. Elasticsearch (search)

> ⚠️ **Don't confuse ElastiCache with Elasticsearch.** **ElastiCache** = AWS-managed **Redis/Memcached** (an in-memory *cache*, §9) — fast for **key lookups**, not text search. **Elasticsearch** = a **search engine** (this section) — fast for **searching** text/attributes via an inverted index. Different tools, similar name.

Full-text **search** + log analytics via an **inverted index** (Lucene).

### Why it's fast for search — the inverted index

A normal DB would **scan every row** to find text matches (`LIKE '%fox%'` = O(n), no index help). Elasticsearch flips it around, exactly like the index at the back of a book:

```
Forward (normal):  doc1 → "the quick brown fox"          (to search, read every doc)
Inverted:          term → sorted list of doc ids (a "postings list")
   "quick" → [1, 7, 42]      "fox" → [1, 9, 42]      "brown" → [1, 5]

Query "quick fox":  fetch postings for "quick" and "fox" → INTERSECT the two sorted lists
                    → docs [1, 42] match instantly (no full scan)
```

So search becomes **"look up the term → get matching docs directly"** — you never touch documents that don't contain the term.

### What makes it *really* fast (beyond the inverted index)

| Technique | Why it speeds search |
| --- | --- |
| **Inverted index** | Term → docs directly; skip all non-matching docs (the core win) |
| **Sorted postings + skip lists** | Intersecting two sorted lists (AND queries) is near-linear; skip pointers jump ahead |
| **Term dictionary as an FST** | The term→postings map is a compact **finite-state transducer** kept **in memory** → term lookup is tiny + fast |
| **Immutable segments** | Lucene writes new immutable **segment** files (append-only); immutable = safe to **cache aggressively**, no locks on read |
| **OS page cache** | Hot segments live in RAM (filesystem cache) → most reads never hit disk |
| **Filter bitsets (cached)** | `filter` clauses (e.g. `price <= 5000`) are computed as **bitsets and cached/reused** across queries; non-scoring → very cheap |
| **Precomputed scoring stats** | Term frequency / doc frequency stored in the index → **BM25** relevance is computed without scanning |
| **doc_values (columnar)** | Sorting/aggregations read a compact columnar structure, not the source docs |
| **Sharding + replicas** | Each shard searches **in parallel** (scatter-gather), replicas add read throughput |

```json
POST /products/_search
{ "query": { "bool": {
    "must":   { "match": { "title": "wireless earbuds" } },   // scored (relevance)
    "filter": { "range": { "price": { "lte": 5000 } } }        // cached bitset, not scored → fast
}}}
```

> **Scored vs filtered:** put "does it match my keywords" in `must` (BM25-scored); put yes/no constraints (price, category, in-stock) in `filter` — filters are cached bitsets and skip scoring, so they're far cheaper and reusable.

### Storage, routing & operational notes

- **Documents → shards** (each shard is a self-contained **Lucene index**) + **replicas** for HA/read scale.
- **Routing:** `shard = hash(_routing or _id) % number_of_primary_shards` decides which shard a doc lives on; a search **scatter-gathers** across all shards, each returns its top-k, then the coordinating node **merges + re-ranks**.
- **Near-real-time, not instant:** new docs become searchable after a **refresh** (default ~1s) when a new segment opens — a tiny lag, the price of the append-only design.
- **Not a source of truth** — it's a derived read model; populate it from your primary DB via **CDC/ETL**; eventually consistent.
- **Use for:** search boxes, autocomplete, log/observability analytics (ELK/OpenSearch), faceted filtering. **Don't** use it as your primary transactional DB.

> **One-liner:** "Elasticsearch is fast because of the **inverted index** — it maps each term to a sorted list of matching documents, so a search is a term lookup + list intersection instead of a full scan — plus in-memory term dictionaries, immutable cacheable segments, cached filter bitsets, and parallel sharded search."

---

## 11. Vector Databases

Store **embeddings** (high-dimensional vectors from ML models) and find the **nearest** vectors by similarity — powering semantic search, recommendations, and **RAG** (retrieval-augmented generation for LLMs).

```
text/image → embedding model → vector [0.12, -0.3, ...] (e.g. 768 dims)
query vector → find top-k nearest vectors by cosine / dot / L2 distance
```

- **Exact nearest-neighbor is O(n)** over millions of vectors → too slow. Use **ANN (Approximate Nearest Neighbor)** indexes:
  - **HNSW** (Hierarchical Navigable Small World) — a navigable graph; fast, high recall (most popular).
  - **IVF** (inverted file / clustering) + **PQ** (product quantization) — cluster then search nearest clusters; compresses vectors.
- **Options:** dedicated (**Pinecone, Milvus, Weaviate, Qdrant**) or add-ons (**pgvector** for Postgres, Elasticsearch/OpenSearch, Redis vector).

```sql
-- pgvector example
CREATE TABLE docs ( id BIGINT, embedding vector(768) );
CREATE INDEX ON docs USING hnsw (embedding vector_cosine_ops);
SELECT id FROM docs ORDER BY embedding <=> '[...]' LIMIT 5;   -- <=> = cosine distance, top-5
```

- **Use when:** "find similar" by meaning (not keywords) — semantic search, recommendations, dedup, RAG.
- **Scaling:** shard vectors across nodes; ANN index per shard; scatter-gather + merge top-k. Trade **recall vs latency** via index params.

---

## 12. DynamoDB & others (quick hits)

| DB | Model / note |
| --- | --- |
| **DynamoDB** | Managed KV/document; **partition key hashed → partition**; single-digit-ms; auto-scales; pick keys for even distribution + query pattern (like Cassandra, fully managed) |
| **HBase / Bigtable** | Wide-column on HDFS/GFS; huge scale, strong within a row |
| **Neo4j** | Graph; Cypher; relationship-heavy queries (friends-of-friends, fraud) |
| **InfluxDB / TimescaleDB** | Time-series (metrics/IoT); time-partitioned, downsampling |
| **Memcached** | Pure in-memory cache (simpler than Redis, multi-threaded, no data structures/persistence) |

---

## 13. Replication, Partitioning & Routing (the scaling core)

Two orthogonal techniques; almost every scalable DB combines both.

### Replication (copies for HA + read scale)

| Model | How | Examples |
| --- | --- | --- |
| **Leader–follower** (single-leader) | One primary takes writes → replicates to read replicas | MySQL, Postgres, Mongo replica set, Redis |
| **Multi-leader** | Multiple write nodes (conflict resolution needed) | multi-region setups |
| **Leaderless** | Any replica takes writes; quorum reads/writes (`R+W>N`) | Cassandra, DynamoDB |

- **Sync vs async**: sync = no data loss on failover but slower; async = fast but a failover can lose the last writes.
- Replication gives **HA + read scaling**, *not* write scaling (single leader still bottlenecks writes) → that needs partitioning.

### Partitioning / Sharding (split data for write + storage scale)

| Strategy | How | Trade-off |
| --- | --- | --- |
| **Range** | Split by key ranges (A–M, N–Z) | Range queries easy; **hot spots** if keys skewed |
| **Hash** | `hash(key) % N` → shard | Even spread; range queries scatter; **resharding moves everything** |
| **Consistent hashing** | Hash ring + virtual nodes | Adding a node moves only ~1/N keys ✅ |

### How the cluster routes a request to the right node (side-by-side)

> This is the crux of your question — *"how does it decide which node to search?"* Each system does it differently:

| System | Who routes | Mechanism |
| --- | --- | --- |
| **Redis Cluster** | **Client-side** (smart client) | `CRC16(key) mod 16384` → slot → node map (cached); `MOVED`/`ASK` redirects if stale. No central router. |
| **MongoDB** | **`mongos` router** | Consults **config servers** (chunk-range → shard map); targeted if query has shard key, else scatter-gather |
| **Cassandra** | **Any node = coordinator** | `hash(partition key)` = token → node on the ring (gossip-shared); token-aware clients hit it directly |
| **DynamoDB** | **Managed request router** | `hash(partition key)` → partition; internal routing (opaque) |
| **Elasticsearch** | **Any node = coordinating node** | `hash(routing/_id) % primary_shards` → shard; searches scatter-gather + merge |

- **Common thread:** a **partition key is hashed** to locate the owning node(s). The differences are *who* computes it (client vs router vs any node) and *how topology is tracked* (client cache, config servers, gossip ring).
- **Rebalancing on add/remove:** consistent-hashing systems (Cassandra, Redis slots, Dynamo) move only a fraction of data; naive `hash % N` would move almost everything.

---

## 14. Decision Cheat Sheet

| Need | Use |
| --- | --- |
| Transactions, joins, relationships, ad-hoc queries | **PostgreSQL / MySQL** |
| SQL semantics **+** horizontal scale | **CockroachDB / Spanner / Vitess / Citus** |
| Flexible/nested schema, evolving entities | **MongoDB** |
| Massive **write** throughput, time-series, feeds | **Cassandra / ScyllaDB** |
| Sub-ms cache, sessions, counters, leaderboards, locks | **Redis** |
| Full-text search, log analytics, faceted filters | **Elasticsearch** |
| Semantic "find similar", RAG, recommendations | **Vector DB (Pinecone/pgvector/Milvus)** |
| Relationship traversal (friends-of-friends, fraud) | **Neo4j (graph)** |
| Fully-managed KV at any scale (AWS) | **DynamoDB** |
| Analytics / OLAP over billions of rows | **ClickHouse / Redshift / BigQuery (column store)** |

> **Polyglot persistence:** real systems use **several** — e.g. Postgres (source of truth) + Redis (cache) + Elasticsearch (search) + a warehouse (analytics) + a vector DB (semantic). Pick per access pattern; keep one source of truth and sync others via CDC/events.

---

## 15. Interview Cheat Sheet

> **"SQL or NoSQL — how do you decide?"**
> "By access pattern + consistency + scale. Default to relational (Postgres) for ACID, joins, and flexible queries. Go NoSQL for a specific need: Redis for sub-ms cache, Cassandra for write-heavy scale, Mongo for flexible documents, Elasticsearch for search, a vector DB for similarity. Most real systems are polyglot."

> **"How does data get stored — B-Tree vs LSM?"**
> "B-Tree (SQL, Mongo/WiredTiger): balanced, in-place, great point+range reads. LSM (Cassandra, RocksDB): writes append to a memtable + commit log, flush to immutable SSTables, compacted in the background — write-optimized, reads use Bloom filters to skip files. Choose LSM for write-heavy, B-Tree for balanced/read-heavy."

> **"MySQL vs Postgres for high scale?"**
> "Both have a single write primary, so you scale reads with replicas and writes with **sharding — Vitess for MySQL, Citus for Postgres** — or partitioning. Postgres has richer features (JSONB, extensions); MySQL has the proven Vitess layer. For built-in distributed SQL, use CockroachDB/Spanner."

> **"In a Redis/Mongo cluster, how does it know which node has a key?"**
> "**Redis Cluster:** `CRC16(key) mod 16384` = a hash slot; each master owns a slot range; the smart client caches the slot→node map and connects directly, with `MOVED`/`ASK` redirects if stale — no central router. **Mongo:** `mongos` router consults **config servers** that map shard-key **chunk ranges → shards**; queries with the shard key are targeted to one shard, otherwise scatter-gather. **Cassandra:** hash the partition key to a token on a consistent-hashing ring; any node coordinates and forwards to the owning replicas."

> **"How do you add capacity?"**
> "Add nodes; consistent-hashing systems (Cassandra, Redis slots, Dynamo) migrate only a fraction of data. Mongo's balancer moves chunks to the new shard. Replicas give HA + read scale; sharding gives write + storage scale."

> **"Strong vs eventual consistency in NoSQL?"**
> "Leaderless systems (Cassandra/Dynamo) are tunable: `R + W > N` (e.g. QUORUM reads + writes) gives strong consistency; smaller quorums give speed + eventual consistency. Single-leader (Mongo primary) reads from primary = strong, from secondaries = eventual."

---

## 16. Final Takeaways

- **Storage engine is destiny:** **B-Tree** = balanced/read + range (SQL, Mongo); **LSM** = write-heavy append + compaction (Cassandra); **column store** = analytics; **inverted index** = search; **ANN/HNSW** = vectors.
- **SQL (Postgres/MySQL)** = ACID + joins + flexible queries; single write primary → scale reads via **replicas**, writes via **sharding (Vitess/Citus)** or **NewSQL (Spanner/CockroachDB)**.
- **NoSQL = model for your queries** + partition + denormalize; pick by data model (KV/document/wide-column/search/graph/vector).
- **Routing to the right node:** hash a **partition key** → node. **Redis** = client-side hash slots (16384) + MOVED/ASK; **Mongo** = mongos + config-server chunk map; **Cassandra** = token ring (any node coordinates); **Dynamo/ES** = hashed partition/shard.
- **Replication** = HA + read scale (not write scale); **partitioning/sharding** = write + storage scale; **consistent hashing** minimizes reshuffle on scaling.
- **Consistency is tunable** in leaderless stores (`R+W>N`); choose per workload.
- **Be polyglot:** one source of truth + Redis (cache) + ES (search) + warehouse (analytics) + vector DB (semantic), synced via CDC/events.

### Related notes

- [Database Fundamentals](database-fundamentals.md) — SQL vs NoSQL, ACID/BASE, isolation, CAP/PACELC
- [Database Indexing](database-indexing.md) — B-Tree index internals, composite/covering indexes
- [Consistent Hashing](consistent-hashing.md) · [Caching Strategies](caching-strategies.md) · [Distributed Cache](../system-design/distributed-cache-system-design.md) · [Apache Kafka](kafka.md)
