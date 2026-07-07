# Bloom Filters

> A **probabilistic, space-efficient** set membership structure. It answers *"is X in the set?"* with **"definitely not"** or **"probably yes"** — **no false negatives, some false positives** — in tiny memory. Used to avoid expensive lookups (DB/disk/network) for items that don't exist.

---

## Contents

- [1. The Problem It Solves](#1-the-problem-it-solves)
- [2. How It Works](#2-how-it-works)
- [3. Properties & Trade-offs](#3-properties--trade-offs)
- [4. Sizing](#4-sizing)
- [5. Variants](#5-variants)
- [6. Where It's Used](#6-where-its-used)
- [7. Interview Cheat Sheet](#7-interview-cheat-sheet)
- [8. Final Takeaways](#8-final-takeaways)

---

## 1. The Problem It Solves

You want to check membership in a **huge set** without storing the whole set in memory or hitting a slow store every time.

```
"Has this user seen this article?"   "Is this URL already crawled?"
"Does this key exist before I hit disk?"   "Is this username taken?"
```

A Bloom filter answers most **negatives** instantly in RAM, so you only do the expensive lookup when it says "probably yes."

---

## 2. How It Works

A **bit array** of size `m` + `k` independent hash functions.

```
add(x):     set bits [ h1(x)%m, h2(x)%m, ..., hk(x)%m ] = 1
contains(x):
    if ALL of [ h1(x)%m ... hk(x)%m ] are 1 → "probably present"
    if ANY is 0                            → "definitely absent"
```

```
m = 10 bits, k = 3
add("cat"): set bits 1,4,7
contains("cat"): bits 1,4,7 all 1 → probably present ✅
contains("dog"): bit 2 is 0       → definitely absent ✅
contains("fox"): bits 1,4,7 happen to be 1 (set by others) → FALSE POSITIVE ⚠️
```

---

## 3. Properties & Trade-offs

| Property | Value |
| --- | --- |
| **False negatives** | **Never** — if it says "absent," it's truly absent |
| **False positives** | Possible — "probably present" may be wrong |
| **Space** | Tiny (bits, not full items) |
| **Operations** | `add` and `contains` are O(k) |
| **Deletion** | **Not supported** in a plain Bloom filter (use Counting Bloom filter) |

> The **guarantee that matters:** no false negatives → safe to use as a "skip the expensive lookup if absent" gate. A false positive just means an occasional unnecessary lookup (still correct, just not free).

---

## 4. Sizing

```
Given n items and target false-positive rate p:
  m (bits)  = -(n * ln p) / (ln 2)^2
  k (hashes) = (m / n) * ln 2
```

- More bits (`m`) or items removed → lower false-positive rate.
- Too many items in a fixed filter → fills up → false-positive rate climbs.
- Example: ~10 bits/item + 7 hashes → ~1% false positives.

---

## 5. Variants

| Variant | Adds |
| --- | --- |
| **Counting Bloom filter** | Counters instead of bits → supports **deletion** |
| **Scalable Bloom filter** | Grows as items are added (keeps FP rate bounded) |
| **Cuckoo filter** | Supports deletion, often better space + lookup |

---

## 6. Where It's Used

| System | Use |
| --- | --- |
| **Databases (Cassandra, HBase, Bigtable)** | Skip disk/SSTable reads for keys that don't exist |
| **Web crawler** | "Seen this URL?" without storing all URLs |
| **Caches** | Avoid **cache penetration** — skip lookups for keys known absent |
| **CDNs / proxies** | "Is this cacheable object present?" |
| **Username/email** | Fast "probably taken" pre-check before DB |
| **Recommendation/feed** | "Has the user already seen this item?" |

> Appears across these notes: URL Shortener (unknown codes), Web Crawler (seen URLs), Distributed Cache (penetration).

---

## 7. Interview Cheat Sheet

> **"What's a Bloom filter and when do you use it?"**
> "A bit array + k hashes giving probabilistic membership: **no false negatives, some false positives**, in tiny space. Use it to avoid expensive lookups (DB/disk/network) for items that don't exist — e.g. 'is this key/URL/username present?' If it says absent, skip the lookup; if 'probably present,' do the real check."

> **"What's the catch?"**
> "False positives (occasional unnecessary lookups) and **no deletion** in the plain version (use a Counting Bloom filter). It fills up as you add items, raising the false-positive rate — size it for your `n` and target `p`."

> **"Why is 'no false negatives' the key property?"**
> "It makes the filter safe as a gate: a definite 'absent' is trustworthy, so you never wrongly skip something that exists — you only occasionally do an extra lookup."

---

## 8. Final Takeaways

- Bloom filter = **space-efficient probabilistic set**: **no false negatives**, some false positives, O(k) ops.
- Use to **skip expensive lookups for absent items** (DB SSTables, crawled URLs, cache penetration).
- **No deletion** in plain form → Counting/Cuckoo/Scalable variants add it.
- Size `m` and `k` for your item count `n` and target false-positive rate `p`.

### Related notes

- [Caching Strategies](caching-strategies.md) — cache penetration defense
- [URL Shortener](../system-design/url-shortener-system-design.md) · [Web Crawler](../system-design/web-crawler-system-design.md) · [Distributed Cache](../system-design/distributed-cache-system-design.md)
