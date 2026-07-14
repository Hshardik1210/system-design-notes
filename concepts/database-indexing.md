# Database Indexing

> **In one line:** an index is a **shortcut data structure** (usually a B-tree) that turns a slow full-table scan into a few targeted lookups. Stored on **disk**, accelerated by **RAM** (buffer pool).

> **How to read this doc:** each section has the dense summary first, then a **deep dive** (annotated SQL, and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand.

---

## Contents

- [1. Why Indexes Exist](#1-why-indexes-exist)
- [2. Where Indexes Are Stored (disk vs RAM)](#2-where-indexes-are-stored-disk-vs-ram)
- [3. B-Tree (the default index)](#3-b-tree-the-default-index)
- [4. Clustered vs Non-Clustered](#4-clustered-vs-non-clustered)
- [5. Covering Index (big optimization)](#5-covering-index-big-optimization)
- [6. Composite (Multi-Column) Indexes & Leftmost Prefix](#6-composite-multi-column-indexes--leftmost-prefix)
- [7. Why Not Index Everything](#7-why-not-index-everything)
- [When to Index / When NOT To](#when-to-index--when-not-to)
- [Reading the Query Plan (EXPLAIN ANALYZE)](#reading-the-query-plan-explain-analyze)
- [Specialized Index Variants](#specialized-index-variants)
- [Index Types Beyond B-Tree](#index-types-beyond-b-tree)
- [Common Mistakes](#common-mistakes)
- [8. Interview Cheat Sheet](#8-interview-cheat-sheet)
- [9. Final Takeaways](#9-final-takeaways)

---

## 1. Why Indexes Exist

Without an index, finding a row means a **full table scan** — read every row (O(n)).

```sql
SELECT * FROM users WHERE email = 'a@gmail.com';
```

With an index on `email`, the DB jumps to the row in **~3–4 reads** (O(log n)).

> **In short:** an index is a separate sorted structure that maps column values to matching rows — so instead of scanning every row, the DB jumps straight to the matches.

### Why an index is fast

Suppose a `users` table has 1,000,000 rows and you want every row where `email = 'a@gmail.com'`.

- **No index (full table scan):** the DB reads **every single row** and checks each one. Correct, but slow — O(n).
- **With an index on `email`:** the DB walks a small sorted structure keyed by `email`, jumps straight to the matching entry, and follows it to the row — a handful of steps instead of a million. O(log n).

A database index is a **separate, sorted copy of one (or a few) columns**, where each entry points back to the full row.

```sql
-- Slow: DB checks every row (full table scan)
SELECT * FROM users WHERE email = 'a@gmail.com';

-- Create the index once:
CREATE INDEX idx_users_email ON users (email);
-- Now the same query jumps straight to the row.
```

#### Q: Does the index store the whole row?

No. A plain index stores just the **indexed column(s)** plus a **pointer** to where the full row lives (its primary key or physical location) — the column value and a reference, not a copy of the whole row.

#### Q: Do I have to change my query to use the index?

No. You write the same `SELECT`. The DB's **query planner** automatically notices an index exists and decides to use it. You just create the index; the DB does the rest.

#### Q: "O(n) vs O(log n)" — what does that actually mean here?

- **O(n)** = work grows in step with row count. 10× more rows → 10× slower. (Full scan.)
- **O(log n)** = work grows *very* slowly. 1,000 rows or 1,000,000,000 rows → still only a handful of steps. (Index lookup.) This gap is why indexes feel almost magical on big tables.

---

## 2. Where Indexes Are Stored (disk vs RAM)

> **Primary storage = disk.** Frequently used index pages are **cached in RAM** (the buffer pool / shared buffer).

```
Disk:  full table data + full index structure (B-tree)
RAM :  cached index pages + cached table rows  ← speed comes from here
```

### Query path

```sql
SELECT * FROM users WHERE email = 'a@gmail.com';
```
1. DB finds the index on `email`.
2. Loads index pages into memory (if not already cached).
3. Traverses the B-tree (in memory).
4. Fetches the row from disk (or cache).

### Why it's fast even from disk

- **B-tree depth is small (3–5 levels)** → millions of rows = only ~3–4 page reads.
- If the whole index fits in RAM → near-zero disk access 🚀.
- If it doesn't fit → partial in RAM, rest from disk (still efficient).

| Component | Stored in |
| --- | --- |
| Table data | Disk |
| Index | Disk |
| Cache (buffer pool) | RAM |

### Disk, RAM, and caching hot pages

- **Disk** holds *everything* — every table row and every index entry. It's large and permanent, but reading from it is **slow**.
- **RAM (the buffer pool)** is small but fast. It holds the pages you've accessed recently so they don't have to be re-read from disk.

When a query needs an index page:

1. Already in RAM (the buffer pool)? → use it instantly.
2. Not there? → read it from disk, **and keep a copy in RAM** for next time.

This "keep the hot pages in RAM" area is the **buffer pool** (Postgres calls it *shared buffers*, MySQL InnoDB the *buffer pool*).

```
First query for 'a@gmail.com':  index page on DISK → copied into RAM (a little slow)
Second query for 'a@gmail.com': index page already in RAM → blazing fast
```

#### Q: If it's stored on disk, why do people say indexes are "in memory"?

Both are true. The **permanent home is disk**; the **frequently used pages live in RAM** because they've been cached. A busy table's index is effectively served from RAM most of the time — the disk is just the backup-of-record.

#### Q: Why only ~3–4 disk reads for millions of rows?

Because a B-tree is **shallow** (see §3). Even for millions of rows the tree is only 3–5 levels deep, so reaching any row touches only a few pages — and the top levels are almost always cached in RAM, so the "3–4 reads" is often really "1 read, the rest from memory."

#### Q: What if the index is too big to fit in RAM?

Then only the **hot parts** stay cached and the rest is fetched from disk as needed. Still fast, because (a) the tree is shallow and (b) the upper tree levels (the most-reused pages) almost always stay resident. This is exactly the tension in §7 — indexes compete for limited RAM.

---

## 3. B-Tree (the default index)

- Balanced tree, sorted keys, shallow depth.
- Supports **equality** (`=`) **and range** (`<`, `>`, `BETWEEN`, `ORDER BY`).
- This is what `CREATE INDEX` builds by default in Postgres/MySQL.

### Hash index (alternative)

- O(1) equality lookups, but **no range queries**.
- Rarely the default; useful for pure key-value equality.

### What a B-tree actually is

A B-tree keeps keys **sorted** and organized into pages that branch out like a tree. To find `email = 'm...'` the DB doesn't scan — it makes a few comparisons, descending from the root toward the leaf:

```
Looking for 'mango@x.com':

            [ f ....... p ]              ← root page: "is it before f? between f–p? after p?"
           /      |       \
     [a–f]     [f–p]      [p–z]          ← 'm' is between f and p → go to middle child
                 |
     [ ... k, m, o ... ]                 ← leaf page: found 'm...' entries + pointers to rows
```

- **Balanced** = every leaf is the same distance from the root, so *every* lookup costs the same small number of steps. No key is "unlucky."
- **Shallow** = each page holds hundreds of keys, so even billions of rows fit in ~4 levels. Depth grows like `log(n)`, which barely moves as data grows.
- **Sorted** = this is the superpower that hash indexes lack.

#### Q: Why does a B-tree handle ranges but a hash index doesn't?

Because a B-tree keeps keys **in sorted order**, "everything between 100 and 200" is just a **contiguous stretch of leaves** — find the start, then walk sideways until you pass 200. `ORDER BY` is free for the same reason (the data is already sorted).

A **hash index** scrambles keys through a hash function (`hash('apple')` and `hash('apricot')` land in totally unrelated spots). Great for "is `apple` here? → jump straight to it" (O(1) equality), but there's no notion of "next key", so ranges, sorting, and prefix matches are impossible.

```sql
-- B-tree shines here (range + sort):
SELECT * FROM orders WHERE created_at BETWEEN '2026-01-01' AND '2026-02-01'
ORDER BY created_at;

-- Hash index can only do exact equality:
SELECT * FROM sessions WHERE token = 'abc123';   -- fine for a hash index
```

#### Q: Is it really a "B-tree" or a "B+tree"?

Most databases use a **B+tree** (a variant where all the actual data/pointers live in the leaf level, and leaves are chained left-to-right). People say "B-tree" loosely to mean both. The chained leaves are what make range scans a fast sideways walk. For interviews, "B-tree" is the expected word.

---

## 4. Clustered vs Non-Clustered

| | Clustered | Non-clustered (secondary) |
| --- | --- | --- |
| What | table rows physically **sorted by the key** | separate structure → **pointer** to the row |
| Count | one per table (the PK, in InnoDB) | many per table |
| Lookup | data is *in* the index leaf | index → then fetch row ("bookmark lookup") |

> In MySQL InnoDB the **PK is the clustered index**; secondary indexes store the PK and require a second lookup. In Postgres, the heap is separate and all indexes are secondary.

### Clustered = the data itself is sorted

- **Clustered index** — the table rows are physically stored **in sorted order by the index key**, and the full row data lives *in* the index leaf. There's no separate lookup — the index *is* the data. A table can only be physically stored in **one** order, so you get **only one** clustered index per table (in InnoDB, that's the primary key).
- **Non-clustered (secondary) index** — a separate structure sorted by the indexed column, where each entry holds a **pointer** to the row (not the row itself). You still need a second step to fetch the actual row. You can have **many** secondary indexes per table.

```
CLUSTERED (InnoDB PK):  leaf pages ARE the rows, sorted by PK
    [ id=1 {full row} ][ id=2 {full row} ][ id=3 {full row} ] ...
    → find id=2, the whole row is right here. Done.

NON-CLUSTERED (secondary on email):
    [ 'a@x.com' → id=2 ][ 'b@x.com' → id=9 ] ...
    → find email, get id=2, THEN look up id=2 in the clustered index to get the row.
```

#### Q: What's a "bookmark lookup" / "second lookup"?

It's that extra hop. A secondary index gets you a **pointer** (in InnoDB, the primary key), then the DB does a *second* traversal — into the clustered index — to fetch the full row. Two lookups instead of one. This is precisely the cost a **covering index** (§5) eliminates.

```sql
-- secondary index on email exists:
SELECT * FROM users WHERE email = 'a@x.com';
-- step 1: email index → finds pk id = 2
-- step 2: clustered (pk) index → fetch full row for id = 2   ← the second lookup
```

#### Q: MySQL InnoDB vs Postgres — why the difference?

- **InnoDB (MySQL):** the table *is* the clustered PK B-tree. Secondary indexes store the PK value (not a raw disk address), so they always do the two-step above.
- **Postgres:** rows live in a separate unordered "heap"; **every** index (including the PK) is secondary and points at a physical row location. So Postgres has no true clustered index by default — all its indexes are secondary structures.

#### Q: Why can there be only one clustered index?

Because "clustered" means the rows are **physically stored** in that order, and data can only be laid out in one physical order at a time. Every additional index must therefore be a separate (secondary) structure.

---

## 5. Covering Index (big optimization)

> An index that contains **all columns a query needs** → the DB answers from the index alone, **never touching the table**.

👉 No row fetch → much faster. (This is an "index-only scan".) *(Full annotated example in the deep dive below.)*

> ⚠️ **pitfall:** `SELECT *` silently kills a covering index. The moment you ask for a column that isn't in the index, the DB must fetch the full row — turning a fast index-only scan back into a per-row table lookup. Select **only the columns you need** if you want the covering benefit.

### When the index answers the whole question

Normally a secondary index gets you a pointer, and you still fetch the row to read the columns you need. But if the index *already contains* every column the query asks for, the DB can answer from the index alone and skip fetching the row entirely.

A **covering index** does exactly this: it contains every column the query references, so the DB reads only the index and **never touches the table** (skipping the "second lookup" from §4).

```sql
-- The query only needs two columns: email (to filter) and name (to return)
SELECT email, name FROM users WHERE email = 'a@gmail.com';

-- Non-covering index: has email, but not name
CREATE INDEX idx_email ON users (email);
--   → find email in index → THEN fetch the row to read `name`  (second lookup)

-- Covering index: has BOTH columns the query touches
CREATE INDEX idx_email_name ON users (email, name);
--   → everything the query needs is IN the index → no table fetch at all
```

#### Q: "Covers" what, exactly?

It covers **every column the query references** — both in the `WHERE`/`JOIN`/`ORDER BY` and in the `SELECT` list. If even one requested column is missing from the index, the DB must go fetch the row, and it's no longer covering *for that query*.

```sql
-- idx_email_name (email, name) covers this — all columns present:
SELECT email, name FROM users WHERE email = 'a@x.com';   -- index-only scan ✅

-- but NOT this — `age` isn't in the index, so it must fetch the row:
SELECT email, age FROM users WHERE email = 'a@x.com';    -- back to a table lookup ❌
```

#### Q: So should I just add every column to the index to make everything covering?

No — that's basically copying the whole table into the index. It bloats disk and RAM and slows writes (§7). Add extra columns to an index only for **hot queries** where skipping the row fetch genuinely matters. It's a targeted optimization, not a default.

> **`SELECT *` is usually not coverable** — it asks for every column, so the DB almost always has to fetch the full row. Covering indexes reward selecting only the columns you actually need.

---

## 6. Composite (Multi-Column) Indexes & Leftmost Prefix

```sql
CREATE INDEX idx ON orders (user_id, status, created_at);
```

The index is usable for queries that filter on a **leftmost prefix**:

| Query filter | Uses index? |
| --- | --- |
| `user_id` | ✅ |
| `user_id, status` | ✅ |
| `user_id, status, created_at` | ✅ |
| `status` alone | ❌ (skips the leftmost column) |
| `status, created_at` | ❌ |

> **Order matters.** Put the most selective / always-filtered column first.

> ⚠️ **pitfall (leftmost-prefix trap):** a composite index on `(user_id, status, created_at)` does **nothing** for `WHERE status = 'PAID'` — you skipped the leftmost column, so the DB falls back to a full scan. Don't assume "the column is *in* an index" means "the query is indexed." Only a filter that starts at the first column (and doesn't skip) can use it.

### Why column order is everything

A composite index `(user_id, status, created_at)` is sorted first by `user_id`, then by `status` within each `user_id`, then by `created_at` within each `(user_id, status)`. That ordering determines which queries it can accelerate:

- Filter on `user_id` → all matching entries are grouped together. ✅
- Filter on `user_id` and `status` → an even smaller contiguous group. ✅
- Filter on `status` alone (no `user_id`) → the matching entries are **scattered** throughout the index (because it's sorted by `user_id` first), so the index can't help. ❌

This is the **leftmost-prefix rule**: the index only helps if your filter starts from the **first** column and goes left-to-right without skipping.

```sql
CREATE INDEX idx ON orders (user_id, status, created_at);

-- ✅ starts at the left, no gaps → index used
SELECT * FROM orders WHERE user_id = 7;
SELECT * FROM orders WHERE user_id = 7 AND status = 'PAID';
SELECT * FROM orders WHERE user_id = 7 AND status = 'PAID' AND created_at > '2026-01-01';

-- ❌ skips the leftmost column (user_id) → index unusable, falls back to a scan
SELECT * FROM orders WHERE status = 'PAID';
SELECT * FROM orders WHERE status = 'PAID' AND created_at > '2026-01-01';
```

#### Q: Why can't it use the index for `status` alone?

Because `status` values are **scattered** throughout the index (which is sorted first by `user_id`). There's no single contiguous block of "all PAID orders" to jump to, so the sorted structure gives no shortcut.

#### Q: Does the order of conditions in my `WHERE` clause matter?

No — `WHERE status = 'PAID' AND user_id = 7` works just as well as `WHERE user_id = 7 AND status = 'PAID'`. The optimizer reorders conditions freely. What matters is **which columns you filter on**, not the order you type them. The **index definition's** column order is what's fixed and important.

#### Q: How do I choose the column order?

Rule of thumb:

1. Columns you filter with **equality (`=`) and always** → put first (e.g. `user_id`).
2. The column used for **ranges / sorting** (`>`, `BETWEEN`, `ORDER BY`) → put last, because once you hit a range, columns after it can't be used for further seeking.
3. More **selective** columns (that narrow results the most) generally earlier.

> One composite index `(a, b, c)` also serves queries on `(a)` and `(a, b)` for free — so you often need fewer indexes than you'd think.

---

## 7. Why Not Index Everything

Indexes are not free:

- **More disk space** — each index is a separate structure.
- **More RAM** — they compete for buffer-pool cache.
- **Slower writes** — every `INSERT/UPDATE/DELETE` must update every affected index.
- **Optimizer overhead** — too many choices.

```
Table = 10 GB, Indexes = 5 GB, RAM = 4 GB
→ not all indexes fit in RAM → performance depends on access patterns
```

> Index the columns you **filter / join / sort** on — not every column.

### Why indexes hurt writes

Adding an index makes *reads* faster, but there's a hidden cost on *writes*. Every index is a **second sorted copy** of some columns. So when you insert, update, or delete a row, the DB must update **the table AND every index** that touches the changed columns — keeping each one correctly sorted.

```
Table with 4 indexes, one INSERT:
  1. write the new row into the table
  2. slot the key into index #1 (in sorted position)
  3. slot the key into index #2
  4. slot the key into index #3
  5. slot the key into index #4
→ ONE insert became FIVE structures to maintain
```

The more indexes a table has, the more structures every write must keep in sync — so more indexes means slower writes.

> ⚠️ **pitfall (write amplification on hot insert paths):** on a high-throughput write table (event logs, order intake, IoT telemetry), each extra index multiplies the work of **every** insert. Ten indexes = ten sorted structures to update per row. On hot paths this write amplification can dominate — sometimes the fix is to *drop* indexes, not add them.

#### Q: So why not add indexes "just in case"?

Because each one has ongoing costs even if no query uses it:

- **Disk** — it's a whole extra sorted structure stored on disk.
- **RAM** — it competes for buffer-pool space, potentially evicting pages a *useful* index needed.
- **Write speed** — every `INSERT`/`UPDATE`/`DELETE` pays to maintain it (above).
- **Planner overhead** — more indexes = more options the optimizer must weigh, and occasionally it picks a worse one.

#### Q: When is an index NOT worth it?

- **Write-heavy, rarely-read tables** (e.g. an append-only event log) — you'd pay the write tax constantly for little read benefit.
- **Low-selectivity columns** — indexing a `boolean` or `gender` column that has 2 values barely narrows anything; the DB often just scans instead. Index columns that are **highly selective** (lots of distinct values, like `email` or `user_id`).
- **Small tables** — a full scan of a few hundred rows is already instant; an index adds cost with no real gain.

#### Q: How do I know which indexes I actually need?

Let real queries drive it: index the columns you **filter (`WHERE`), join (`JOIN ... ON`), or sort (`ORDER BY`)** on frequently. Use `EXPLAIN` / `EXPLAIN ANALYZE` to see whether a query does a "full scan" vs an "index scan", and add indexes to fix the slow, common ones — not every column speculatively.

```sql
-- Ask the DB how it will run the query:
EXPLAIN ANALYZE SELECT * FROM orders WHERE user_id = 7 AND status = 'PAID';
-- "Seq Scan" (Postgres) / "type: ALL" (MySQL) = full scan → an index may help
-- "Index Scan" / "type: ref" = an index is being used → good
```

---

## When to Index / When NOT To

The default instinct — "this query is slow, add an index" — is right *often enough* to be dangerous. Indexes have a real cost (§7), so the decision is a trade-off, not a reflex.

**Good candidates (index these):**

- Columns in `WHERE`, `JOIN ... ON`, and `ORDER BY` that are used **frequently**.
- **Highly selective** columns — lots of distinct values, so each lookup narrows to few rows (`email`, `user_id`, `order_id`).
- Foreign keys you join on constantly.

**Poor candidates (usually skip):**

- **Low-cardinality columns** — a handful of distinct values (`gender`, `status` with 3 states, a `boolean` flag). The index barely narrows anything, so the planner often ignores it (see selectivity below).
- **Small tables** — a few hundred rows scan instantly; an index adds maintenance cost for no measurable gain.
- **Write-heavy, rarely-read paths** — append-only logs or ingestion tables pay the write tax (§7) on every insert while almost never reading.
- Columns you never actually filter, join, or sort on.

> 💡 **tip:** let real, measured queries drive indexing. Find the slow, *common* queries (via `EXPLAIN ANALYZE` and slow-query logs), index those, and re-measure. Don't index speculatively.

### Selectivity — why the planner sometimes ignores your index

**Selectivity** = how much a filter narrows the result. An index is only worth using if it eliminates *most* rows.

Numeric example — a `users` table with 1,000,000 rows:

- Index on `gender` (2 values, ~50% each): a lookup for `gender = 'F'` still matches ~500,000 rows. Walking the index **and** doing 500k row fetches is *slower* than a straight sequential scan — so the planner **ignores the index** and scans.
- Index on `email` (≈1,000,000 distinct values): `email = 'a@x.com'` matches ~1 row. The index is enormously valuable.

Rule of thumb: an index helps when a lookup returns a **small fraction** of the table (often cited as < ~5–10%). Above that, a sequential scan wins because sequential disk reads are cheaper than thousands of scattered random fetches.

> ⚠️ **pitfall:** "the query didn't use my index" usually isn't a bug — the planner estimated the index would match too many rows and chose a scan on purpose. Check cardinality before blaming the optimizer.

---

## Reading the Query Plan (EXPLAIN ANALYZE)

`EXPLAIN` shows the plan the optimizer *intends* to run; `EXPLAIN ANALYZE` actually runs it and reports real timings and row counts. It's the single best tool for "is my index being used?"

```sql
EXPLAIN ANALYZE SELECT * FROM users WHERE email = 'a@x.com';
```

The node type at the top tells you what happened:

**Sequential scan** — no usable index, read every row:

```
Seq Scan on users  (cost=0.00..18334.00 rows=1 width=...) (actual time=42.1..119.8 rows=1 loops=1)
  Filter: (email = 'a@x.com')
  Rows Removed by Filter: 999999      ← read a million rows to return one = slow
```

**Index scan** — walk the index, then fetch each matching row from the table (the "second lookup" from §4):

```
Index Scan using idx_users_email on users  (cost=0.42..8.44 rows=1 ...) (actual time=0.03..0.04 rows=1 loops=1)
  Index Cond: (email = 'a@x.com')      ← a few page reads instead of a million
```

**Index-only scan** — a covering index (§5) answered the query entirely from the index, no table fetch:

```sql
EXPLAIN ANALYZE SELECT email, name FROM users WHERE email = 'a@x.com';
```
```
Index Only Scan using idx_email_name on users  (actual time=0.02..0.02 rows=1 loops=1)
  Index Cond: (email = 'a@x.com')      ← never touched the heap; the fastest case
```

**Bitmap scan** — the middle ground when a filter matches *many* scattered rows: the DB first builds a bitmap of matching row locations from the index, then fetches them in **physical disk order** (fewer random seeks). You'll see this when a lookup is too broad for a plain index scan but too narrow for a full seq scan.

```
Bitmap Heap Scan on orders  (actual time=...)
  Recheck Cond: (status = 'PAID')
  ->  Bitmap Index Scan on idx_orders_status  (actual time=...)
```

> 💡 **tip:** read the plan bottom-up (inner nodes run first) and watch two things: the **node type** (Seq vs Index vs Index Only) and **`Rows Removed by Filter`** — a huge number there means the DB scanned far more than it returned, a classic "missing/unused index" smell.

MySQL equivalent: `EXPLAIN` shows a `type` column — `ALL` = full scan (bad), `ref`/`range` = index used, `eq_ref`/`const` = best, and `Extra: Using index` = index-only scan.

---

## Specialized Index Variants

Beyond the plain B-tree, a few index *shapes* solve specific problems. Each is still a B-tree under the hood — just built differently.

### Partial (filtered) index

Indexes only the **subset of rows matching a condition** — smaller, cheaper to maintain, and often the exact rows you query.

```sql
-- Only index ACTIVE orders (say 2% of the table), not the archived 98%
CREATE INDEX idx_active_orders ON orders (created_at) WHERE status = 'ACTIVE';
```

Great when queries almost always target one slice (`WHERE status = 'ACTIVE'`). The index is a fraction of the size and skips maintenance for rows you never look up. (MySQL has no direct equivalent; Postgres/SQLite call it a *partial* index, SQL Server a *filtered* index.)

### Unique index

Enforces that no two rows share a value **and** doubles as a fast lookup index. A `PRIMARY KEY` or `UNIQUE` constraint is backed by one automatically.

```sql
CREATE UNIQUE INDEX idx_users_email ON users (email);   -- no duplicate emails, fast lookups
```

> 💡 **tip:** a unique index is both a **correctness guarantee** (dedup) and a **performance structure** — one object, two wins.

### Functional / expression index

Indexes the result of an **expression**, not a raw column — so a transformed lookup can still be indexed.

```sql
-- A plain index on `email` can't help a case-insensitive search:
SELECT * FROM users WHERE LOWER(email) = 'a@x.com';   -- would scan

-- Index the expression itself:
CREATE INDEX idx_users_email_lower ON users (LOWER(email));   -- now it's a fast lookup
```

The query's expression must **match** the indexed expression for the planner to use it (`LOWER(email)` in both).

---

## Index Types Beyond B-Tree

B-tree (default) and hash (§3) cover most needs, but databases offer specialized structures for data B-trees handle poorly. Names worth recognizing:

| Type | Good for |
| --- | --- |
| **B-tree** | the default — equality + range + sort on scalar values |
| **Hash** | pure equality lookups (no ranges) |
| **GIN** (Generalized Inverted Index) | "many values per row" — full-text search, `JSONB`, array containment |
| **GiST** (Generalized Search Tree) | geometric / spatial data, nearest-neighbor, range overlap |
| **BRIN** (Block Range Index) | huge, naturally-ordered tables (time-series logs) — tiny index, stores min/max per block |

> 💡 **tip:** you rarely need these early on, but recognizing the names matters. If someone mentions full-text or `JSONB` search, think **GIN**; geospatial, think **GiST**; a massive append-only time-ordered table, think **BRIN**.

---

## Common Mistakes

- **Indexing every column.** Each index taxes writes and RAM (§7). Index for real, frequent queries — not "just in case."
- **Wrong composite column order.** `(status, user_id)` won't serve a `user_id`-only filter; the leftmost column must be the one you always filter on. Order by equality-first, range/sort-last, most-selective-earlier (§6).
- **Assuming `OR` uses a composite index.** `WHERE a = 1 OR b = 2` generally **cannot** use one `(a, b)` index efficiently — the two conditions are independent, so the DB may scan or need a *separate* index per column (then combine via a bitmap). Composite indexes are for `AND`-ed leftmost prefixes, not `OR`.
- **Ignoring write cost.** A covering index that makes reads 2× faster but every insert 3× slower is a loss on a write-heavy table. Weigh both sides.
- **Trusting `SELECT *`.** It defeats covering/index-only scans (§5). Select only the columns you need.
- **Not verifying with `EXPLAIN ANALYZE`.** "I added an index" ≠ "the query uses it." Always confirm the plan actually changed.

---

## 8. Interview Cheat Sheet

> **"Where are indexes stored?"**
>
> "On disk as separate structures (usually B-trees), but frequently accessed pages are cached in the **buffer pool** in RAM for speed."

> **"Why is an index fast?"**
>
> "B-trees are shallow (3–5 levels), so even millions of rows need only a few page reads, often served from RAM."

> **"What's a covering index?"**
>
> "An index containing all columns the query needs, so it's answered from the index alone without touching the table — an index-only scan."

> **"Downside of too many indexes?"**
>
> "More storage + RAM, and slower writes since every index must be maintained on each write."

---

## 9. Final Takeaways

```
Disk  = storage
RAM   = speed (buffer pool)
Index = shortcut on disk, accelerated by RAM
```

- **B-tree** = default; supports equality + range. **Hash** = equality only.
- **Covering / composite** indexes are the big wins; respect the **leftmost-prefix** rule.
- **Don't over-index** — it slows writes and wastes RAM.
