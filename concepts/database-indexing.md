# Database Indexing

> **In one line:** an index is a **shortcut data structure** (usually a B-tree) that turns a slow full-table scan into a few targeted lookups. Stored on **disk**, accelerated by **RAM** (buffer pool).

> **How to read this doc:** each section has the dense summary first, then a **Plain-English** deep dive (analogies, annotated SQL, and the exact confusions that come up while learning). Skim the summaries for revision; read the plain-English parts to actually understand.

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

### Plain-English: the book index analogy, fully

Imagine a 1,000-page textbook and you want every page that mentions "photosynthesis."

- **No index (full table scan):** you flip through **all 1,000 pages** one by one, checking each. Correct, but painfully slow. This is `WHERE email = '...'` with no index — the DB reads every single row.
- **With the index (the alphabetical list at the back):** you jump to "P", find "photosynthesis → pages 42, 88, 300", and turn straight to those pages. Three lookups instead of a thousand.

That back-of-book index is *itself* a smaller, sorted, searchable thing. That's exactly what a database index is: a **separate, sorted copy of one (or a few) columns**, each entry pointing back to the full row.

```sql
-- Slow: DB checks every row (full table scan)
SELECT * FROM users WHERE email = 'a@gmail.com';

-- Make the "back-of-book index" once:
CREATE INDEX idx_users_email ON users (email);
-- Now the same query jumps straight to the row.
```

#### Q: Does the index store the whole row?

No. A plain index stores just the **indexed column(s)** plus a **pointer** to where the full row lives (its primary key or physical location). Think "term → page number", not "term → the entire page reprinted."

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

### Plain-English: disk, RAM, and the "recently-used pages" desk

**Analogy: a library with a vast basement archive (disk) and a small desk (RAM).**

- The **basement (disk)** holds *everything* — every book (table row) and every catalog card (index entry). It's huge and permanent, but walking down to fetch something is **slow**.
- Your **desk (RAM / buffer pool)** is tiny but instant. You keep the cards and books you've touched recently right there so you don't re-walk to the basement.

When a query needs an index page:

1. Is it already on the desk (in RAM)? → use it instantly.
2. Not there? → walk to the basement (read from disk), **and leave a copy on the desk** for next time.

This "keep the hot pages on the desk" area is the **buffer pool** (Postgres calls it *shared buffers*, MySQL InnoDB the *buffer pool*).

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

### Plain-English: what a B-tree actually is

**Analogy: a phone book, or a "choose-your-own-adventure" of narrowing ranges.**

A B-tree keeps keys **sorted** and organized into pages that branch out like a tree. To find `email = 'm...'` you don't scan — you make a few "go left / go right" decisions:

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

### Plain-English: clustered = the data itself is sorted

**Analogy: a dictionary vs. a library card catalog.**

- **Clustered index = a dictionary.** The words (rows) are physically printed **in sorted order**, and the definition (the full row data) is *right there* on the page next to the word. There's no "look it up, then go somewhere else" — the index *is* the data. A book can only be printed in **one** order, so you get **only one** clustered index per table (in InnoDB, that's the primary key).
- **Non-clustered (secondary) index = the card catalog.** The cards are sorted by, say, author, but each card just says "shelf B-12" — you still have to **walk to the shelf** to read the actual book. You can have **many** catalogs (by author, by title, by subject) — many secondary indexes per table.

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
- **Postgres:** rows live in a separate unordered "heap"; **every** index (including the PK) is secondary and points at a physical row location. So Postgres has no true clustered index by default — all its indexes are the "card catalog" kind.

#### Q: Why can there be only one clustered index?

Because "clustered" means the rows are **physically stored** in that order, and data can only be laid out in one physical order at a time — just as a book can only be bound in one page sequence. Every additional index must therefore be a separate (secondary) structure.

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

### Plain-English: when the index answers the whole question

**Analogy: the back-of-book index that prints the definition next to the term.**

Normally the book index says "mitochondria → page 88", and you still flip to page 88 to read about it. But imagine a special index that wrote a one-line summary *right next to the entry*: "mitochondria → the cell's powerhouse (p.88)". If your question was just "what's the mitochondria in one line?", you never open to page 88 at all — the index **covered** your question.

A **covering index** does exactly this: it contains every column the query asks for, so the DB reads only the index and **never touches the table** (skipping the "second lookup" from §4).

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

### Plain-English: why column order is everything

**Analogy: a phone book sorted by (last name, then first name).**

A phone book is ordered by **last name first**, and only *within* the same last name is it sorted by first name.

- "Find everyone named **Sharma**" → easy, they're all grouped together. ✅
- "Find everyone named **Sharma, Hardik**" → even easier, a tiny slice within the Sharmas. ✅
- "Find everyone whose **first name is Hardik**" (any last name) → useless! The Hardiks are scattered across the entire book (one under Aggarwal, one under Sharma, one under Verma...). You'd have to read the whole thing. ❌

A composite index `(user_id, status, created_at)` is sorted **exactly like that phone book**: first by `user_id`, then by `status` within each user, then by `created_at` within each (user, status). This is the **leftmost-prefix rule**: the index only helps if your filter starts from the **first** column and goes left-to-right without skipping.

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

Because `status` values are **scattered** throughout the index (sorted first by `user_id`), just like the Hardiks scattered through the phone book. There's no single contiguous block of "all PAID orders" to jump to, so the sorted structure gives no shortcut.

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

### Plain-English: why indexes hurt writes

**Analogy: the back-of-book index has to be re-edited every time you edit the book.**

Adding an index makes *reads* faster, but there's a hidden bill on *writes*. Every index is a **second sorted copy** of some columns. So when you insert, update, or delete a row, the DB must update **the table AND every index** that touches the changed columns — keeping each one correctly sorted.

```
Table with 4 indexes, one INSERT:
  1. write the new row into the table
  2. slot the key into index #1 (in sorted position)
  3. slot the key into index #2
  4. slot the key into index #3
  5. slot the key into index #4
→ ONE insert became FIVE structures to maintain
```

Think of it like a book with five different back-of-book indexes: every time you add a paragraph, you must update all five indexes so they stay accurate. More indexes = slower edits.

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
