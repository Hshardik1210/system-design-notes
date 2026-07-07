# Typeahead / Search Autocomplete — System Design

> **Core challenge:** as a user types, return the **top-k most relevant completions** in **a few milliseconds**, for **every keystroke**, at massive query volume. The heart is a **prefix data structure (trie)** with **precomputed top-k per prefix**, plus a pipeline that continuously **updates rankings** from search logs.

---

## Contents

- [1. Mental Model](#1-mental-model)
- [2. Requirements](#2-requirements)
- [3. Capacity Estimation](#3-capacity-estimation)
- [4. Architecture](#4-architecture)
- [5. The Trie & Top-K per Prefix](#5-the-trie--top-k-per-prefix)
- [6. Serving Path (fast reads)](#6-serving-path-fast-reads)
- [7. Building & Updating Rankings](#7-building--updating-rankings)
- [8. Ranking, Personalization & Fuzzy Matching](#8-ranking-personalization--fuzzy-matching)
- [9. Sharding & Memory](#9-sharding--memory)
- [10. Data Model / Stores](#10-data-model--stores)
- [11. API Design](#11-api-design)
- [12. Sequences](#12-sequences)
- [13. Design Patterns (that can be used)](#13-design-patterns-that-can-be-used)
- [14. Scaling & Failure](#14-scaling--failure)
- [15. Interview Cheat Sheet](#15-interview-cheat-sheet)
- [16. Final Takeaways](#16-final-takeaways)

---

## 1. Mental Model

```
Type "net" → suggest ["netflix", "network", "netgear"] ranked by popularity, in <10ms
```

Extremely **read-heavy**, latency-critical. **Precompute the top-k completions for each prefix** so a query is a single lookup, not a search.

---

## 2. Requirements

**Functional**
- Given a prefix, return **top-k** (5–10) suggestions ranked by popularity/relevance.
- Update suggestions as trends change (new popular queries appear).
- Optional: **personalization**, **typo tolerance**, multi-language.

**Non-functional**
- **Very low latency** (<10–50ms) per keystroke; **huge QPS** (multiple lookups per search).
- **Eventual consistency** of rankings is fine (a new trend can lag minutes/hours).

---

## 3. Capacity Estimation

```
Searches ~ 5B/day → each search = several keystroke lookups → autocomplete QPS ≈ 5–10× search QPS
  ~ hundreds of thousands of lookups/sec at peak → must be in-memory + cached
Vocabulary: ~100M's of distinct queries; trie nodes in the billions
Memory: raw trie of all queries = many GB → shard by prefix; store only TOP-K per node (not all descendants)
Rebuild: aggregate billions of log lines → hourly/daily trie build → atomic swap
```

> The read path must be **in-memory** (a trie lookup is a few pointer hops); the write path is a **batch/stream aggregation** that rebuilds the ranked trie.

---

## 4. Architecture

```
Client (debounced) → API Gateway → Suggestion Service (in-memory trie shards) → response
                                        └─ Redis/CDN cache for hottest prefixes

Search logs → Kafka → Aggregator (batch/stream: frequency + decay) → Trie Builder
   → serialized trie snapshot (blob, versioned) → suggestion nodes load + ATOMIC SWAP
```

- **Read path** (serving) and **write path** (offline build) are fully separate (**CQRS**).

---

## 5. The Trie & Top-K per Prefix

A **trie (prefix tree)**: each node is a character; a path = a prefix.

```
        (root)
         └ n → e → t → [netflix(900), network(500), netgear(120)]   ← store TOP-K at the node
                    └ f → l → i → x  (terminal: "netflix")
```

**Key optimization:** store the **precomputed top-k completions at each node**. A query = walk to the prefix node (**O(prefix length)**) and return its cached list — **no subtree scan** at query time.

| Concern | Approach |
| --- | --- |
| Fast lookup | Top-k cached per node → O(len(prefix)) |
| Memory | Trie is huge → **shard by prefix**; compress with a **radix tree (Patricia)** or **DAWG** (shares suffixes) |
| Ranking | Each terminal query has a frequency/score; a node's top-k = highest-scoring descendants (computed offline) |

> Without cached top-k, answering "net" would DFS the whole subtree under "net" to find the best completions — too slow. Precomputing top-k per node trades build cost + memory for O(prefix) reads.

---

## 6. Serving Path (fast reads)

```
GET /autocomplete?q=net
  1. route to the SHARD owning prefix "net" (shard by first 1–2 chars)
  2. walk the in-memory trie to node "net" → return its precomputed top-k
  3. (Redis/edge cache for very hot prefixes short-circuits the walk)
```

- Serving trie held **in memory** on suggestion nodes (loaded from the offline snapshot).
- **Client debounce** (fire after ~50–100ms of no typing, not every keystroke) cuts QPS massively.
- **Cache/CDN** the hottest prefixes' responses.

---

## 7. Building & Updating Rankings

Rankings come from **what people actually search** — an offline/streaming pipeline.

```
Search logs → Kafka → aggregate query frequencies (streaming or batch, with TIME DECAY)
   → compute top-k per prefix → build a new immutable trie → publish snapshot
   → suggestion nodes load it → ATOMIC SWAP (old trie stays serving until the new one is ready)
```

- **Time decay** (recency weighting) so trends rise and stale queries fade: `score = Σ decay(age) per occurrence` (e.g., exponential decay).
- Rebuild **periodically** (hourly/daily); huge builds can be incremental.
- **Atomic swap** = no read locks, no partial state — readers see either the old or new trie.
- **Filter** offensive/spam/PII queries before adding.

---

## 8. Ranking, Personalization & Fuzzy Matching

| Feature | Approach |
| --- | --- |
| **Popularity ranking** | Query frequency with time decay (trends) |
| **Personalization** | Blend a per-user layer (your history) with the global trie at read time |
| **Typo tolerance** | Edit-distance (Levenshtein) matching, or index common misspellings; often a fuzzy layer alongside the trie |
| **Context** | Location/language-specific tries; recent-query boosting |
| **Freshness** | Newly trending terms enter on the next rebuild (eventual) |

- Fuzzy matching is expensive on a plain trie → often a separate **spell-correction / n-gram** stage, or a small edit-distance search over the trie.

---

## 9. Sharding & Memory

```
Shard the trie by prefix (first 1–2 chars):  a-c → node1, d-f → node2, ...
  each shard = a subtree held in memory; replicate each shard for QPS + HA
```

- **Skew:** some prefixes are far more popular (`"a"`, `"the"`) → finer sharding or more replicas for hot shards.
- **Memory:** store only **top-k per node** (not full descendant lists); compress with radix/DAWG; keep the serving set to popular queries (long tail can DFS on miss or be omitted).

---

## 10. Data Model / Stores

```sql
-- Offline aggregation (warehouse / KV)
CREATE TABLE query_frequency ( query TEXT PRIMARY KEY, count BIGINT, decayed_score DOUBLE PRECISION, last_updated TIMESTAMP );

-- Serving structure is the TRIE (in-memory), persisted as versioned snapshots:
--   trie_snapshot(version, shard, serialized_trie)   → loaded by suggestion nodes
--   Redis: cache:autocomplete:{prefix} = [top-k]     (hot prefixes)
```

> **Stores to consider:** raw search logs (Kafka + warehouse/lake), `query_frequency` aggregate, versioned trie snapshots (blob), Redis cache for hot prefixes, optional per-user history store for personalization.

---

## 11. API Design

```
GET /v1/autocomplete?q=net&limit=10&lang=en   → { suggestions: ["netflix","network",...] }
# query logging happens via the search pipeline (not a per-keystroke write)
```

---

## 12. Sequences

### Serve

```
Client (debounced) → Gateway → shard(prefix) → in-memory trie walk to node → return top-k
   (hot prefix → Redis/edge cache hit, skip the walk)
```

### Rebuild + swap

```
Search logs → Kafka → Aggregator (freq + decay) → Trie Builder → snapshot(vN) → blob
Suggestion nodes: load vN in background → when ready, ATOMIC SWAP pointer old→new → serve vN
```

---

## 13. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Trie (prefix tree)** | Core data structure | Prefix lookups |
| **Strategy** | Ranking (popularity/recency/personalized), matching (exact/fuzzy) | Swap algorithms |
| **Producer-Consumer** | Search-log aggregation via Kafka | Absorb the log firehose |
| **Immutable snapshot + atomic swap** | Rebuild trie offline, hot-swap serving copy | Safe updates, no read locks |
| **Cache-Aside** | Hot-prefix response cache | Latency |
| **CQRS** | Offline build (write) vs in-memory serve (read) | Separate paths |
| **Sharding (by prefix)** | Distribute the trie | Scale memory + QPS |
| **Facade** | Autocomplete service over shards + cache | Simple API |

---

## 14. Scaling & Failure

- **Shard trie by prefix**; replicate each shard for QPS + HA; extra replicas for hot prefixes (skew).
- **In-memory serving** + Redis/CDN for hottest prefixes; **client debounce** cuts QPS.
- **Offline pipeline** rebuilds rankings; **atomic swap** the new trie (no downtime).
- **Node failure** → replicas serve; a new node loads the latest snapshot.
- **Eventual consistency:** trending terms appear after the next rebuild — acceptable.

---

## 15. Interview Cheat Sheet

> **"How do you return suggestions in milliseconds?"**
> "A **trie with top-k precomputed and cached at each node** — a query walks to the prefix node (O(prefix length)) and returns the list, no subtree scan. It's served in-memory, with hot prefixes cached in Redis/edge and client-side debounce."

> **"How are rankings kept fresh?"**
> "Search logs → Kafka → a batch/stream job aggregates query frequencies with **time decay**, recomputes top-k per prefix, builds a **new immutable trie**, and **atomically swaps** it into the serving nodes."

> **"How do you scale memory/QPS?"**
> "**Shard the trie by prefix**, replicate shards (more for hot prefixes), hold in memory, compress with radix/DAWG, store only top-k per node, and cache hot prefixes. Debounce on the client."

> **"Typo tolerance / personalization?"**
> "Fuzzy matching via edit distance / a spell-correction stage; personalization by blending a per-user history layer with the global trie at read time."

---

## 16. Final Takeaways

- **Trie with precomputed top-k per node** = O(prefix) millisecond lookups (no subtree scan).
- **Offline pipeline** (logs → Kafka → aggregate w/ decay → rebuild → **atomic swap**) keeps rankings fresh.
- **Shard by prefix**, replicate (extra for hot), in-memory, compress (radix/DAWG), cache hot prefixes, debounce client.
- **Eventual consistency** of rankings is fine; **CQRS** separates build from serve.
- Patterns: Trie, Strategy (ranking/fuzzy), Producer-Consumer, Immutable snapshot + atomic swap, Cache-Aside, Sharding.

### Related notes

- [Databases — Deep Dive](../concepts/databases-deep-dive.md) (Elasticsearch/search) · [Caching Strategies](../concepts/caching-strategies.md) · [Apache Kafka](../concepts/kafka.md) · [Consistent Hashing](../concepts/consistent-hashing.md)
