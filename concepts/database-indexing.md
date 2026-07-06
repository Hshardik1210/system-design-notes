# Database Indexing

> **In one line:** an index is a **shortcut data structure** (usually a B-tree) that turns a slow full-table scan into a few targeted lookups. Stored on **disk**, accelerated by **RAM** (buffer pool).

---

## Contents

- [1. Why Indexes Exist](#1-why-indexes-exist)
- [2. Where Indexes Are Stored (disk vs RAM)](#2-where-indexes-are-stored-disk-vs-ram)
- [3. B-Tree (the default index)](#3-b-tree-the-default-index)
- [4. Clustered vs Non-Clustered](#4-clustered-vs-non-clustered)
- [5. Covering Index (big optimization)](#5-covering-index-big-optimization)
- [6. Composite (Multi-Column) Indexes & Leftmost Prefix](#6-composite-multi-column-indexes--leftmost-prefix)
- [7. Why Not Index Everything](#7-why-not-index-everything)
- [8. Interview Cheat Sheet](#8-interview-cheat-sheet)
- [9. Final Takeaways](#9-final-takeaways)

---

## 1. Why Indexes Exist

Without an index, finding a row means a **full table scan** — read every row (O(n)).

```sql
SELECT * FROM users WHERE email = 'a@gmail.com';
```

With an index on `email`, the DB jumps to the row in **~3–4 reads** (O(log n)).

> **Analogy:** an index is the index at the back of a book — instead of reading every page, you look up the term and jump to the page.

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

---

## 3. B-Tree (the default index)

- Balanced tree, sorted keys, shallow depth.
- Supports **equality** (`=`) **and range** (`<`, `>`, `BETWEEN`, `ORDER BY`).
- This is what `CREATE INDEX` builds by default in Postgres/MySQL.

### Hash index (alternative)

- O(1) equality lookups, but **no range queries**.
- Rarely the default; useful for pure key-value equality.

---

## 4. Clustered vs Non-Clustered

| | Clustered | Non-clustered (secondary) |
| --- | --- | --- |
| What | table rows physically **sorted by the key** | separate structure → **pointer** to the row |
| Count | one per table (the PK, in InnoDB) | many per table |
| Lookup | data is *in* the index leaf | index → then fetch row ("bookmark lookup") |

> In MySQL InnoDB the **PK is the clustered index**; secondary indexes store the PK and require a second lookup. In Postgres, the heap is separate and all indexes are secondary.

---

## 5. Covering Index (big optimization)

> An index that contains **all columns a query needs** → the DB answers from the index alone, **never touching the table**.

```sql
-- Query:
SELECT email, name FROM users WHERE email = 'a@gmail.com';

-- Covering index:
CREATE INDEX idx_users_email_name ON users (email, name);
```

👉 No row fetch → much faster. (This is an "index-only scan".)

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
