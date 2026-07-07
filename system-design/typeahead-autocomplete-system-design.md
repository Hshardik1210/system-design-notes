# Typeahead / Search Autocomplete — System Design

> **Core challenge:** as a user types, return the **top-k most relevant completions** in **a few milliseconds**, for **every keystroke**, at massive query volume. The heart is a **prefix data structure (trie)** with **precomputed top-k per prefix**, plus a pipeline that **updates rankings** from search logs.

---

## Contents

- [1. Mental Model](#1-mental-model)
- [2. Requirements](#2-requirements)
- [3. The Trie & Top-K per Prefix](#3-the-trie--top-k-per-prefix)
- [4. Serving Path (fast reads)](#4-serving-path-fast-reads)
- [5. Building & Updating Rankings](#5-building--updating-rankings)
- [6. Data Model](#6-data-model)
- [7. API Design](#7-api-design)
- [8. Design Patterns (that can be used)](#8-design-patterns-that-can-be-used)
- [9. Scaling & Failure](#9-scaling--failure)
- [10. Interview Cheat Sheet](#10-interview-cheat-sheet)
- [11. Final Takeaways](#11-final-takeaways)

---

## 1. Mental Model

```
Type "net" → suggest ["netflix", "network", "netgear"] ranked by popularity, in <10ms
```

Extremely **read-heavy**, latency-critical. Precompute the **top-k completions for each prefix** so a query is a single lookup, not a search.

---

## 2. Requirements

**Functional**
- Given a prefix, return **top-k** (e.g. 5–10) suggestions ranked by popularity/relevance.
- Update suggestions as trends change (new popular queries appear).
- Optional: personalization, typo tolerance, multi-language.

**Non-functional**
- **Very low latency** (<10–50ms) per keystroke; **huge QPS**.
- Eventual consistency of rankings is fine (new trends can lag minutes/hours).

---

## 3. The Trie & Top-K per Prefix

A **trie (prefix tree)**: each node is a character; a path = a prefix.

```
        (root)
         └ n → e → t → [netflix, network, netgear]   ← store top-k AT the node
                    └ f → l → i → x  (netflix)
```

**Key optimization:** store the **precomputed top-k completions at each node**, so answering a prefix = walk to the node + return its cached list (O(prefix length), not a subtree scan).

| Concern | Approach |
| --- | --- |
| Fast lookup | Top-k cached per trie node |
| Memory | Trie is large → shard by prefix; compress (radix/DAWG) |
| Ranking | Each terminal word has a frequency/score; top-k = highest-score descendants |

---

## 4. Serving Path (fast reads)

```
GET /autocomplete?q=net
  1. route to shard owning prefix "net" (shard by first 1–2 chars)
  2. walk trie to node "net" → return its precomputed top-k
  3. (cache the response in Redis/edge for very hot prefixes)
```

- Serving trie is held **in memory** on suggestion servers (rebuilt from the offline pipeline).
- **Cache/CDN** hot prefixes; debounce on the client (don't fire every keystroke).

---

## 5. Building & Updating Rankings

The trie's rankings come from **what people actually search** — an offline/streaming pipeline.

```
Search logs → Kafka → aggregate query frequencies (streaming/batch)
   → compute top-k per prefix → build new trie → publish to serving nodes (swap in)
```

- **Frequencies** decay over time (recency weighting) so trends update.
- Rebuild periodically (hourly/daily) or update incrementally; **atomic swap** of the serving trie.
- Filter offensive/spam queries before adding.

---

## 6. Data Model

```sql
-- Offline aggregation
CREATE TABLE query_frequency ( query TEXT PRIMARY KEY, count BIGINT, last_updated TIMESTAMP );

-- Serving structure is the TRIE (in-memory), persisted as a snapshot:
--   trie_snapshot(version, serialized_trie)  → loaded by suggestion servers
--   Redis: cache:autocomplete:{prefix} = [top-k]  (hot prefixes)
```

> **Tables/stores to consider:** raw search logs (Kafka + warehouse), query_frequency aggregate, trie snapshots (blob/versioned), Redis cache for hot prefixes.

---

## 7. API Design

```
GET /v1/autocomplete?q=net&limit=10   → { suggestions: ["netflix","network",...] }
POST (internal) /v1/log-query { query }   # feeds the ranking pipeline (usually via search logs)
```

---

## 8. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Trie (prefix tree)** | Core data structure | Prefix lookups |
| **Strategy** | Ranking (popularity/recency/personalized), matching (exact/fuzzy) | Swap algorithms |
| **Producer-Consumer** | Search-log aggregation via Kafka | Absorb log firehose |
| **Cache-Aside** | Hot-prefix response cache | Latency |
| **Immutable snapshot + atomic swap** | Rebuild trie offline, hot-swap serving copy | Safe updates, no read locks |
| **CQRS** | Offline build (write) vs in-memory serve (read) | Separate paths |
| **Facade** | Autocomplete service over shards + cache | Simple API |
| **Sharding (by prefix)** | Distribute trie | Scale memory + QPS |

---

## 9. Scaling & Failure

- **Shard trie by prefix** (first 1–2 chars) across suggestion servers; replicate each shard for QPS + HA.
- **In-memory serving** + Redis/CDN cache for hottest prefixes; client debounce.
- **Offline pipeline** (Kafka + batch/stream) rebuilds rankings; **atomic swap** the new trie.
- **Node failure** → replicas serve; rebuild from latest snapshot.
- Eventual consistency: trending terms appear after the next rebuild — acceptable.

---

## 10. Interview Cheat Sheet

> **"How do you return suggestions in milliseconds?"**
> "A trie with the **top-k completions precomputed and cached at each node**. A query walks to the prefix node and returns its list — O(prefix length), no subtree scan. Hot prefixes are cached in Redis/edge."

> **"How are rankings updated?"**
> "Search logs stream through Kafka; a batch/stream job aggregates query frequencies (with time decay), recomputes top-k per prefix, builds a new trie offline, and atomically swaps it into the serving nodes."

> **"How do you scale it?"**
> "Shard the trie by prefix across servers, replicate shards, hold them in memory, and cache hot prefixes. Client-side debounce reduces QPS."

> **"How do you handle huge memory?"**
> "Shard by prefix; compress with a radix tree/DAWG; only store top-k per node, not full subtrees."

---

## 11. Final Takeaways

- **Trie with precomputed top-k per node** = millisecond prefix lookups.
- **Offline pipeline** (search logs → Kafka → aggregate → rebuild → **atomic swap**) keeps rankings fresh.
- **Shard by prefix**, replicate, hold in memory, cache hot prefixes, debounce client.
- Eventual consistency of rankings is fine.
- Patterns: Trie, Strategy (ranking), Producer-Consumer, Immutable snapshot + atomic swap, Cache-Aside, CQRS.

### Related notes

- [Caching Strategies](../concepts/caching-strategies.md) · [Apache Kafka](../concepts/kafka.md) · [Consistent Hashing](../concepts/consistent-hashing.md)
