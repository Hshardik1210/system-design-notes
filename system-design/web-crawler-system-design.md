# Web Crawler — System Design

> **Core challenge:** download billions of web pages **at scale**, following links, **without re-crawling duplicates**, **respecting politeness** (don't hammer a site / obey robots.txt), staying **fresh**, and being **fault-tolerant**. The heart is the **URL frontier** (what to crawl next) + **dedup** + **politeness**.

---

## Contents

- [1. Mental Model](#1-mental-model)
- [2. Requirements](#2-requirements)
- [3. Capacity Estimation](#3-capacity-estimation)
- [4. Architecture & Crawl Loop](#4-architecture--crawl-loop)
- [5. URL Frontier (Mercator design)](#5-url-frontier-mercator-design)
- [6. DNS Resolution](#6-dns-resolution)
- [7. Deduplication (URL & content)](#7-deduplication-url--content)
- [8. Politeness & robots.txt](#8-politeness--robotstxt)
- [9. Freshness / Recrawl & Traps](#9-freshness--recrawl--traps)
- [10. Data Model / Stores](#10-data-model--stores)
- [11. Sequences](#11-sequences)
- [12. Design Patterns (that can be used)](#12-design-patterns-that-can-be-used)
- [13. Scaling & Failure](#13-scaling--failure)
- [14. Interview Cheat Sheet](#14-interview-cheat-sheet)
- [15. Final Takeaways](#15-final-takeaways)

---

## 1. Mental Model

```
Seed URLs → fetch page → parse links → filter/dedup new URLs → add to frontier → repeat
                       └→ store page content (for indexing/search)
```

A giant BFS/priority traversal of the web graph, distributed across many crawler workers, coordinated by a **URL frontier** that balances **priority** and **politeness**.

---

## 2. Requirements

**Functional**
- Crawl from seed URLs; extract + follow links; store page content.
- Avoid duplicate URLs and duplicate content.
- Respect **robots.txt** + rate limits per domain (**politeness**).
- Recrawl for **freshness**; handle many content types.

**Non-functional**
- **Scalable** (billions of pages), **fault-tolerant**, **polite**, **extensible** (new parsers), robust to **traps** (infinite/spider traps).

---

## 3. Capacity Estimation

```
Target: 1B pages/month → ~400 pages/sec sustained; recrawls multiply this
Page size: ~100 KB avg HTML → 1B × 100 KB = ~100 TB/month raw content (+ compression) → blob store
Seen-URL set: 10B+ URLs → a Bloom filter (~a few GB) fronts a KV store
Bandwidth: 400 pages/sec × 100 KB ≈ 40 MB/s download (distribute across workers/regions)
DNS: one lookup per new host → cache aggressively (millions of hosts)
```

> Two coordination hotspots: the **frontier** (what to fetch next, politely) and the **seen-URL set** (dedup at 10B scale → Bloom filter). Fetch/parse workers are stateless + horizontal.

---

## 4. Architecture & Crawl Loop

```
        ┌──────────────── URL Frontier (priority + per-host queues) ────────────────┐
        │                                                                            │
   Seeds ─►[Frontier]─► Fetcher workers ─► DNS resolve ─► Download ─► Content store (S3)
                             │                                             │
                             ▼                                             ▼
                        Parser/Extractor ─► extract links ─► normalize ─► URL filter/dedup ─┘
                             │                                             ▲
                             ▼                                    (Bloom filter + seen-set)
                       Index pipeline (text → search index) · Link graph (ranking)
```

- **Stateless fetcher/parser workers** scale horizontally; the **frontier** + **seen-URL store** hold the coordination state.
- Stages decoupled by queues (Producer-Consumer) so fetch, parse, and index scale independently.

---

## 5. URL Frontier (Mercator design)

The frontier decides **what to crawl next** and enforces **politeness**. Classic **Mercator** design = **two levels of queues**.

```
FRONT queues (priority):  F1..Fn by importance (PageRank-ish, freshness, depth)
                          a prioritizer assigns each URL to a front queue
BACK queues (politeness): B1..Bm, each mapped to ONE host at a time
                          a HEAP of (nextFetchTime, backQueue) picks which host is due
```

```
enqueue(url): prioritizer → pick front queue by priority
route to back: a router maps url's host → a back queue (one host per back queue)
fetch loop: pop the host with the earliest nextFetchTime from the heap
            fetch one URL from its back queue → set nextFetchTime = now + crawlDelay(host)
```

| Concern | Mechanism |
| --- | --- |
| **Priority** | Front queues by importance; sample front queues weighted by priority |
| **Politeness** | One host per back queue + a min-heap of next-allowed-fetch times → never hammer a host |
| **Distribution** | Frontier **sharded by host hash** across nodes (a host lives on one node → politeness is local) |
| **Persistence** | Durable (Kafka/DB/disk) so a crash doesn't lose the frontier |

> The two-level split cleanly separates **"what's important" (front)** from **"who can I politely fetch now" (back)**.

---

## 6. DNS Resolution

- Every new **host** needs a DNS lookup → at billions of URLs across millions of hosts, DNS is a **bottleneck**.
- **Cache DNS results** aggressively (respect TTL); run a local caching resolver; pre-resolve popular hosts.
- DNS can be slow/blocking → use async resolution + a resolver pool.

---

## 7. Deduplication (URL & content)

Avoid re-crawling the same URL and storing near-duplicate content.

| Dedup | How |
| --- | --- |
| **URL dedup** | **Normalize** the URL, then check a **seen-set**; a **Bloom filter** gives O(1) membership at 10B scale (no false negatives → "definitely new" is trustworthy; a "maybe seen" is verified in the KV store) |
| **Content dedup** | Hash the page: exact = SHA; **near-duplicate = SimHash/MinHash** (mirror sites, boilerplate) → skip |

```
if not bloom.mightContain(normUrl):        # definitely new
    add to frontier + set bloom + persist to seen-set
else:                                       # maybe seen → verify in KV (avoid false-positive skip)
    if kv.contains(normUrl): skip
```

- **URL normalization:** lowercase host, strip fragments (`#...`), sort query params, resolve relative → absolute, drop tracking params, canonicalize (`http`↔`https`, trailing slash).

---

## 8. Politeness & robots.txt

- Fetch + **cache `/robots.txt` per host**; obey `Disallow` and **`Crawl-delay`**.
- **Rate-limit per host** (the back-queue heap enforces min interval) — never overload one server.
- Identify with a proper **User-Agent**; **back off** on 429/5xx (exponential); honor `Retry-After`.
- Respect `nofollow`, meta-robots, and canonical tags.

---

## 9. Freshness / Recrawl & Traps

- Pages change → schedule **recrawls** by observed **change frequency** (news hourly, static pages monthly) — adaptive interval from `last_crawled` + change history.
- **Spider traps** (infinite calendars, session-id URLs, faceted-filter explosions) → **depth limits, URL-pattern filters, per-host URL caps**, and detecting parameter explosions.
- **Politeness vs freshness** trade-off: important, fast-changing sites get higher priority + shorter recrawl.

---

## 10. Data Model / Stores

```sql
-- URL registry / seen-set (huge → KV store + Bloom filter in front)
CREATE TABLE urls (
    url_hash    CHAR(64) PRIMARY KEY,      -- hash of normalized URL
    url         TEXT, host VARCHAR(255),
    status      VARCHAR(20),               -- DISCOVERED, CRAWLED, FAILED
    priority    INT, depth INT,
    last_crawled TIMESTAMP, content_hash CHAR(64), next_recrawl TIMESTAMP
);
CREATE INDEX idx_urls_host ON urls(host);
CREATE INDEX idx_urls_recrawl ON urls(next_recrawl) WHERE status='CRAWLED';

CREATE TABLE robots_cache ( host VARCHAR(255) PRIMARY KEY, rules TEXT, crawl_delay INT, fetched_at TIMESTAMP );

-- Content → blob store (S3): raw HTML keyed by url_hash (compressed)
-- Frontier → durable queue (Kafka/DB/disk) partitioned by host
-- Seen-URL membership → Bloom filter (in memory) + KV backing (truth)
-- Link graph → adjacency (for PageRank/priority)
```

> **Stores to consider:** URL registry/seen-set (KV + Bloom filter), content blob store (S3, compressed), robots cache, durable frontier queue, link graph, extracted-text/search index, DNS cache.

---

## 11. Sequences

### Crawl one URL

```
Frontier → Fetcher: pop next politely-allowed URL (host due in heap)
  DNS resolve (cached) → download → store raw HTML in S3
  Parser: extract links → normalize each → Bloom/seen check → enqueue NEW ones to frontier
  mark url CRAWLED, set content_hash, schedule next_recrawl
  (content SimHash → skip near-duplicates from indexing)
```

### Recrawl

```
Scheduler: select urls WHERE next_recrawl <= now() → re-enqueue to frontier (priority by change rate)
```

---

## 12. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Producer-Consumer** | Frontier → fetcher/parser workers | Decouple + parallelize |
| **Strategy** | URL prioritization, recrawl policy, parser per content-type | Swap algorithms |
| **Bloom Filter** | Seen-URL membership | Cheap dedup at 10B scale |
| **Pipeline / Chain of Responsibility** | fetch → parse → extract → normalize → filter → store | Composable stages |
| **Factory** | Parser per MIME type (HTML/PDF/image) | Extensible content handling |
| **Rate Limiter / Token Bucket** | Per-host politeness (back-queue heap) | Don't overload sites |
| **Priority Queue / Heap** | Front queues + per-host next-fetch heap | Priority + politeness scheduling |
| **Repository** | URL/content stores | Abstraction |
| **Circuit Breaker** | Failing hosts | Back off gracefully |
| **Observer/Pub-Sub** | New links → dedup → frontier | Decouple |

---

## 13. Scaling & Failure

- **Shard frontier + workers by host hash** → each host handled in one place (politeness + locality); scale by adding shards.
- **Bloom filter** for the seen-set keeps dedup cheap; KV store is the durable truth (verify on "maybe").
- **Content → S3** (compressed); text → indexing pipeline (Kafka).
- **Durable frontier** (Kafka/DB) so crashes don't lose work; **retries + DLQ** for failed fetches.
- **DNS caching** to avoid resolver bottlenecks; distribute crawlers across regions.
- **Traps** handled via depth/pattern/host caps.

---

## 14. Interview Cheat Sheet

> **"What decides what to crawl next?"**
> "The **URL frontier** (Mercator two-level): **front queues** by priority (importance/freshness) and **back queues** per host for politeness, with a **min-heap** of next-allowed-fetch times so no host is hammered. Sharded by host hash and durable so a crash doesn't lose it."

> **"How do you avoid crawling the same thing twice?"**
> "Normalize the URL and check a **seen-set** via a **Bloom filter** (O(1), no false negatives) backed by a KV store; verify in the KV on a 'maybe' to avoid false-positive skips. For duplicate content, **SimHash/MinHash** to skip near-duplicates."

> **"How do you stay polite?"**
> "Cache + obey robots.txt (Disallow + Crawl-delay), rate-limit per host via the back-queue heap, proper User-Agent, and exponential back-off on 429/5xx."

> **"What breaks a crawler and how do you handle it?"**
> "DNS bottleneck → cache aggressively. Spider traps → depth/pattern/host caps. Crash → durable frontier + retries/DLQ. Duplicate content → SimHash."

---

## 15. Final Takeaways

- **URL frontier (Mercator)** = front queues (priority) + per-host back queues + a next-fetch heap (politeness); durable + sharded by host.
- **Dedup:** URL normalization + **Bloom filter** (+ KV truth); content near-dup via **SimHash**.
- **Politeness:** robots.txt + per-host rate limiting + back-off.
- **DNS caching** is essential; **freshness** = adaptive recrawl; guard **spider traps**.
- Content → **blob store**; stages decoupled by queues (fetch → parse → extract → filter → store → index).
- Patterns: Producer-Consumer, Strategy, Bloom Filter, Pipeline/Chain, Factory (parsers), Token Bucket, Priority Queue.

### Related notes

- [Bloom Filters](../concepts/bloom-filters.md) — the seen-URL dedup structure
- [Apache Kafka](../concepts/kafka.md) · [Caching Strategies](../concepts/caching-strategies.md) · [Consistent Hashing](../concepts/consistent-hashing.md) · [Rate Limiting](../concepts/rate-limiting.md)
