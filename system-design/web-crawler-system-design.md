# Web Crawler — System Design

> **Core challenge:** download billions of web pages **at scale**, following links, **without re-crawling duplicates**, **respecting politeness** (don't hammer a site / obey robots.txt), staying **fresh**, and being **fault-tolerant**. The heart is the **URL frontier** (what to crawl next) + **dedup** + **politeness**.

---

## Contents

- [1. Mental Model](#1-mental-model)
- [2. Requirements](#2-requirements)
- [3. Architecture & Crawl Loop](#3-architecture--crawl-loop)
- [4. URL Frontier (prioritization + politeness)](#4-url-frontier-prioritization--politeness)
- [5. Deduplication](#5-deduplication)
- [6. Politeness & robots.txt](#6-politeness--robotstxt)
- [7. Freshness / Recrawl](#7-freshness--recrawl)
- [8. Data Model / Stores](#8-data-model--stores)
- [9. Design Patterns (that can be used)](#9-design-patterns-that-can-be-used)
- [10. Scaling & Failure](#10-scaling--failure)
- [11. Interview Cheat Sheet](#11-interview-cheat-sheet)
- [12. Final Takeaways](#12-final-takeaways)

---

## 1. Mental Model

```
Seed URLs → fetch page → parse links → filter/dedup new URLs → add to frontier → repeat
                       └→ store page content (for indexing/search)
```

A giant BFS/priority traversal of the web graph, distributed across many crawler workers, coordinated by a **URL frontier**.

---

## 2. Requirements

**Functional**
- Crawl from seed URLs; extract + follow links; store page content.
- Avoid duplicate URLs and duplicate content.
- Respect **robots.txt** and rate limits per domain (**politeness**).
- Recrawl for **freshness**; handle many content types.

**Non-functional**
- **Scalable** (billions of pages), **fault-tolerant**, **polite**, **extensible** (new parsers), avoids traps (infinite/spider traps).

---

## 3. Architecture & Crawl Loop

```
        ┌────────────── URL Frontier (priority + per-host queues) ──────────────┐
        │                                                                        │
   Seed URLs ─► [Frontier] ─► Fetcher workers ─► DNS resolve ─► Download page     │
                                   │                                              │
                                   ▼                                              │
                            Content store (raw HTML → blob/S3)                    │
                                   │                                              │
                            Parser/Extractor ─► extract links ─► URL filter/dedup ┘
                                   │
                                   ▼
                            (index pipeline: text → search index)
```

Stateless **fetcher/parser workers** scale horizontally; the **frontier** + **seen-URL store** are the coordination state.

---

## 4. URL Frontier (prioritization + politeness)

The frontier decides **what to crawl next** and enforces **politeness**. Classic design (Mercator): **two-level queues**.

```
Front queues  = PRIORITY (important/fresh pages first)   → prioritizer
Back queues   = per-HOST (one queue per domain)          → politeness (rate per host)
A URL is only fetched when its host's back-queue is due (respecting crawl delay)
```

| Concern | Mechanism |
| --- | --- |
| Priority | Front queues by importance (PageRank-ish, freshness) |
| Politeness | Back queues per host + a timer so one host isn't hammered |
| Distribution | Frontier sharded by host hash across nodes |
| Persistence | Durable (Kafka/DB/disk) so a crash doesn't lose the frontier |

---

## 5. Deduplication

Avoid re-crawling the same URL and storing duplicate content.

| Dedup | How |
| --- | --- |
| **URL dedup** | Normalize URL (canonicalize) → check a **"seen URLs" set**; a **Bloom filter** for a fast membership test at scale (no false negatives) |
| **Content dedup** | Hash page content (e.g. **SHA/MinHash/SimHash**) → skip near-duplicate pages |

```
if bloom.mightContain(normalizedUrl): probably seen → skip (verify in store if needed)
else: add to frontier + mark seen
```

- URL **normalization**: lowercase host, remove fragments, sort query params, resolve relative links.

---

## 6. Politeness & robots.txt

- Fetch and cache **`/robots.txt`** per host; obey `Disallow` + `Crawl-delay`.
- **Rate-limit per host** (back-queue timer) — never overload one server.
- Identify with a proper User-Agent; back off on 429/5xx.

---

## 7. Freshness / Recrawl

- Pages change → schedule **recrawls** based on change frequency (news site hourly, static page monthly).
- Track `last_crawled` + observed change rate; adaptive recrawl interval.
- Avoid **spider traps** (infinite calendars, session-id URLs) → depth limits, URL pattern filters, max URLs per host.

---

## 8. Data Model / Stores

```sql
-- URL registry / seen set (huge → often a KV store + Bloom filter in front)
CREATE TABLE urls (
    url_hash    CHAR(64) PRIMARY KEY,      -- hash of normalized URL
    url         TEXT, host VARCHAR(255),
    status      VARCHAR(20),               -- DISCOVERED, CRAWLED, FAILED
    priority    INT, depth INT,
    last_crawled TIMESTAMP, content_hash CHAR(64),
    next_recrawl TIMESTAMP
);
CREATE INDEX idx_urls_host ON urls(host);

CREATE TABLE robots_cache ( host VARCHAR(255) PRIMARY KEY, rules TEXT, fetched_at TIMESTAMP );

-- Content → blob store (S3): raw HTML keyed by url_hash
-- Frontier → durable queue (Kafka/Redis/DB) partitioned by host
-- Seen-URL membership → Bloom filter (in memory) + KV backing
```

> **Stores to consider:** URL registry/seen-set (KV + Bloom filter), content blob store (S3), robots cache, durable frontier queue, link graph (for ranking), extracted-text index.

---

## 9. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Producer-Consumer** | Frontier → fetcher/parser workers | Decouple + parallelize |
| **Strategy** | URL prioritization, recrawl policy, parser per content-type | Swap algorithms |
| **Bloom Filter** | Seen-URL membership | Cheap dedup at scale |
| **Pipeline / Chain of Responsibility** | fetch → parse → extract → filter → store | Composable stages |
| **Factory** | Parser per MIME type (HTML/PDF/image) | Extensible content handling |
| **Rate Limiter / Token Bucket** | Per-host politeness | Don't overload sites |
| **Repository** | URL/content stores | Abstraction |
| **Observer/Pub-Sub** | New links event → dedup → frontier | Decouple |
| **Circuit Breaker** | Failing hosts | Back off gracefully |

---

## 10. Scaling & Failure

- **Shard frontier + workers by host hash**; each host handled in one place (politeness + locality).
- **Bloom filter** for seen-URL check keeps dedup cheap; KV backing for truth.
- **Content → S3**; text → indexing pipeline (Kafka).
- **Durable frontier** (Kafka/DB) so crashes don't lose work; retries + DLQ for failed fetches.
- **Traps** handled via depth/URL-pattern limits + per-host caps.
- Distributed DNS caching to avoid resolver bottlenecks.

---

## 11. Interview Cheat Sheet

> **"What decides what to crawl next?"**
> "The **URL frontier** — front queues for priority (important/fresh first), back queues per host for politeness (rate-limited so one site isn't hammered). Sharded by host hash and made durable so crashes don't lose it."

> **"How do you avoid crawling the same thing twice?"**
> "Normalize the URL and check a **seen-set**, using a **Bloom filter** for fast membership at scale. For duplicate content, hash the page (SimHash/MinHash) to skip near-duplicates."

> **"How do you stay polite?"**
> "Cache and obey robots.txt (Disallow + Crawl-delay), rate-limit per host via back-queue timers/token buckets, and back off on 429/5xx."

> **"How do you avoid spider traps?"**
> "Depth limits, URL-pattern filters, and per-host URL caps; skip session-id/calendar-style infinite URLs."

---

## 12. Final Takeaways

- **URL frontier** = priority (front queues) + politeness (per-host back queues), durable + sharded by host.
- **Dedup** via URL normalization + **Bloom filter**; content dedup via SimHash.
- **Politeness** = robots.txt + per-host rate limiting.
- **Freshness** = adaptive recrawl by change rate; guard against traps.
- Content → **blob store**; pipeline of fetch → parse → extract → filter → store.
- Patterns: Producer-Consumer, Strategy, Bloom Filter, Pipeline/Chain, Factory (parsers), Token Bucket.

### Related notes

- [Apache Kafka](../concepts/kafka.md) · [Caching Strategies](../concepts/caching-strategies.md) · [Consistent Hashing](../concepts/consistent-hashing.md) · [Rate Limiting](../concepts/rate-limiting.md)
