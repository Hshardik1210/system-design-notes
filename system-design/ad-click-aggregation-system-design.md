# Ad Click Aggregation — System Design (Stream Analytics)

> **Core challenge:** ingest a **firehose of click events** (millions/sec), aggregate them into **near-real-time counts** (clicks per ad per minute) for dashboards + billing, while handling **duplicates, out-of-order/late events, hot keys, and huge scale** — the canonical **stream-processing** problem. Billing needs **accuracy**; dashboards need **freshness**.

---

## Contents

- [1. Mental Model](#1-mental-model)
- [2. Requirements](#2-requirements)
- [3. Capacity Estimation](#3-capacity-estimation)
- [4. Pipeline Architecture](#4-pipeline-architecture)
- [5. Ingestion](#5-ingestion)
- [6. Aggregation (windowing)](#6-aggregation-windowing)
- [7. Exactly-Once, Dedup & Late Events](#7-exactly-once-dedup--late-events)
- [8. Hot Keys & Skew](#8-hot-keys--skew)
- [9. Lambda vs Kappa (accuracy vs freshness)](#9-lambda-vs-kappa-accuracy-vs-freshness)
- [10. Data Model](#10-data-model)
- [11. Sequences](#11-sequences)
- [12. Consistency & Failure](#12-consistency--failure)
- [13. Design Patterns (that can be used)](#13-design-patterns-that-can-be-used)
- [14. Interview Cheat Sheet](#14-interview-cheat-sheet)
- [15. Final Takeaways](#15-final-takeaways)

---

## 1. Mental Model

```
click event → ingest (Kafka) → stream aggregator (windowed counts) → store rollups → query (dashboards/billing)
```

You almost never store-and-recount raw clicks per query — you **pre-aggregate as a stream** into time buckets and serve those. (Same family as YouTube view counting.)

---

## 2. Requirements

**Functional**
- Record ad **click events** (ad_id, user, ts, ...); aggregate counts per ad per time window (minute/hour/day).
- Query aggregates (dashboards) + feed **billing** (accurate totals); drill-down by region/campaign.
- Filter **fraud/bot** clicks.

**Non-functional**
- **Massive write throughput** (millions/sec); **near-real-time** dashboards (seconds); **accurate** billing (reconcile); handle **duplicates + late/out-of-order** events; scalable, fault-tolerant.

---

## 3. Capacity Estimation

```
Clicks ~ 10B/day → ~115k/sec avg, peaks 2–5×
Raw event ~ 100–200 bytes → 10B × 150 B ≈ ~1.5 TB/day raw → cheap storage (S3/lake)
Aggregates: ad_id × minute → far smaller (few rows per ad per minute) → OLAP store, query-friendly
Dedup state: seen click_ids within a window → bounded by window size (TTL)
```

> Two cost centers: the **ingest firehose** (Kafka + parallel processors) and **exact billing** (dedup + reconciliation). Aggregates are tiny vs raw.

---

## 4. Pipeline Architecture

```
Ad clients ─► Ingestion API ─► Kafka (partitioned by ad_id) ─► Stream processor (Flink/Spark/Kafka Streams)
                                                                    │  windowed aggregation (event time)
                                                                    ▼
                                              Aggregate store (OLAP: Druid/ClickHouse/Cassandra)
                                                                    │
                                                          Dashboards · Billing · Alerts
                              raw events also archived → S3 / data lake (audit + reprocess)
                              fraud filter stage before counting
```

- **Kafka** buffers the firehose + decouples ingest from processing; **partition by `ad_id`** for parallel aggregation + per-ad ordering.
- **Stream processor** (Flink is the standard) does windowed aggregation with **state + checkpoints** (exactly-once).
- **OLAP store** serves fast aggregate queries; **raw events archived** for reprocessing/audit.

---

## 5. Ingestion

- **Ingestion API** validates + enriches (geo from IP, campaign lookup) and writes to Kafka.
- **Client batching**: SDKs batch clicks to reduce request overhead; each event carries a **client-generated `click_id`** (for dedup) + **event timestamp**.
- **Backpressure**: if the pipeline lags, Kafka buffers (its whole point); shed/queue at the edge only if necessary.
- **Fraud filter** stage (rate per user/IP, ML scoring) drops bots **before** counting so billing isn't inflated.

---

## 6. Aggregation (windowing)

Aggregate counts over **time windows** keyed by `ad_id`.

| Window | Meaning |
| --- | --- |
| **Tumbling** | Fixed, non-overlapping (per-minute buckets) — most common for counts |
| **Sliding** | Overlapping (last 5 min, updated each min) |
| **Session** | Grouped by activity gaps |

```
key = (ad_id, minute_bucket)
aggregate: count(clicks), unique_users (HyperLogLog), sum(revenue)
→ emit rollup per window → upsert into OLAP store
```

- **Event time vs processing time:** aggregate by **event time** (when the click happened, from the event's timestamp), not arrival time — else late events land in the wrong bucket.
- **Watermarks:** track "we've probably seen all events up to time T" → know when to **close/emit** a window.
- **HyperLogLog** for cheap approximate unique counts (distinct users) without storing every id.
- **Roll up** minute → hour → day for cheaper long-range queries.

---

## 7. Exactly-Once, Dedup & Late Events

Billing can't over/under-count.

| Problem | Handling |
| --- | --- |
| **Duplicate clicks** (retries, at-least-once Kafka) | Each event carries a unique **`click_id`**; **dedup** in the processor's keyed state store (with TTL per window) |
| **Late events** (mobile offline, network) | **Watermarks + allowed lateness**; update the window if within grace, else route very-late events to a **correction/batch** job |
| **Exactly-once counts** | Flink **checkpoints** + **transactional (two-phase-commit) sinks** (Kafka EOS / transactional OLAP write) → exactly-once aggregation |
| **Fraud/bots** | Filtering stage (rate limits, ML) **before** counting |
| **Reprocessing** | Raw events in S3 → **replay** to rebuild aggregates if logic changes / a bug is found |

---

## 8. Hot Keys & Skew

A viral ad = one **hot `ad_id`** → its Kafka partition + aggregation task is overloaded.

```
Mitigation: SALT the key → (ad_id, salt 0..K) spreads one hot ad across K partitions/tasks
  → aggregate per (ad_id, salt) → then SUM the K partials into the final ad_id count
```

- Trade-off: a second aggregation step to combine partials, but it removes the single-task bottleneck.
- Also: pre-aggregate on the **client/edge** (batch counts per ad before sending) to cut event volume.

---

## 9. Lambda vs Kappa (accuracy vs freshness)

Billing wants **accuracy**; dashboards want **freshness**.

| | **Lambda** | **Kappa** |
| --- | --- | --- |
| Paths | **Batch** (accurate, slow) + **Streaming** (fast, approximate) | **Streaming only** (reprocess by replaying the log) |
| Pros | Batch corrects streaming's approximations | One codebase; simpler |
| Cons | Two codebases to maintain | Relies on a replayable log + strong exactly-once stream processing |

```
Lambda: fast streaming rollups for live dashboards
      + nightly BATCH recompute from raw events (S3) for accurate billing (source of truth)
Kappa:  one streaming pipeline; reprocess by REPLAYING Kafka/S3 when needed
```

> **Common answer:** streaming for real-time dashboards + a **batch reconciliation** for billing (Lambda-style); or Kappa with replay if the stream layer is trusted for exactly-once.

---

## 10. Data Model

```sql
-- Aggregates (served to dashboards/billing) — OLAP store
CREATE TABLE click_aggregates (
    ad_id BIGINT, window_start TIMESTAMP, granularity VARCHAR(10),   -- MINUTE/HOUR/DAY
    clicks BIGINT, unique_users BIGINT, revenue NUMERIC(14,2),
    PRIMARY KEY (ad_id, window_start, granularity)
);
CREATE TABLE ads ( ad_id BIGINT PRIMARY KEY, campaign_id BIGINT, advertiser_id BIGINT );

-- Dedup state: seen click_ids per window (stream-processor state / KV with TTL)
-- Raw events → S3 / data lake: { click_id, ad_id, user_id, ts, ip, region, ... } (audit + reprocess)
```

> **Stores to consider:** Kafka (ingest buffer), stream-processor state (dedup/windows), **OLAP store** (Druid/ClickHouse — aggregates), **raw event lake** (S3), ads/campaign metadata (RDBMS).

---

## 11. Sequences

### Ingest → aggregate → serve

```
Client (batched, click_id+ts) → Ingestion API → fraud filter → Kafka (partition by ad_id)
Flink: keyBy ad_id → dedup by click_id (state) → tumbling window by EVENT TIME (watermarks)
     → on window close: emit (ad_id, minute, count, HLL, revenue) → transactional upsert to OLAP
Dashboard/Billing → query OLAP aggregates (fast)
Raw events → also archived to S3
```

### Reconciliation (billing)

```
Nightly batch over S3 raw events → recompute exact per-ad totals → compare with streamed aggregates
→ correct discrepancies → billing uses the reconciled numbers (source of truth)
```

---

## 12. Consistency & Failure

| Concern | Handling |
| --- | --- |
| Double-count | `click_id` dedup + exactly-once processing |
| Late/out-of-order | Event-time windows + watermarks + allowed lateness; correction job for very-late |
| Hot ad | Key salting → partial aggregates → sum |
| Processor crash | Flink checkpoints → resume exactly-once; Kafka retains offsets |
| Bad logic / bug | Reprocess from S3 raw events (Event Sourcing) |
| Dashboard vs billing mismatch | Dashboards approximate (fast); billing reconciled from raw (accurate) |
| Fraud inflation | Filter before counting; post-hoc clawback via reconciliation |

---

## 13. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Producer-Consumer** | Ingest → Kafka → stream processors | Absorb + parallelize firehose |
| **Windowed Aggregation (stream)** | Tumbling/sliding windows by event time | Core rollup |
| **Idempotency / Dedup** | `click_id` dedup in processor | No double-count |
| **CQRS + Materialized View** | Pre-aggregated rollups vs raw events | Fast queries |
| **Lambda / Kappa** | Batch+stream vs stream-only | Accuracy vs freshness |
| **Event Sourcing** | Raw event log as truth; replay to rebuild | Reprocessing/audit |
| **Sketch / Probabilistic (HLL)** | Approx unique counts | Cheap cardinality |
| **Sharding / Partitioning (+ salting)** | Kafka + store partitioned by ad_id; salt hot keys | Scale + skew |
| **Watermark** | Handle late/out-of-order events | Correct windows |

---

## 14. Interview Cheat Sheet

> **"How do you count clicks at millions/sec?"**
> "Ingest into Kafka (partitioned by ad_id), aggregate with a stream processor (Flink) into **event-time tumbling windows** (per-minute), write rollups to an **OLAP store** (Druid/ClickHouse) for dashboards, and archive raw events to S3. You never recount raw events per query — you pre-aggregate."

> **"How do you avoid double-counting for billing?"**
> "Each click has a unique `click_id`; dedup in the processor's keyed state; use **exactly-once** stream processing (Flink checkpoints + transactional sinks). For billing accuracy, run a **batch reconciliation** over the raw event lake (Lambda) as the source of truth."

> **"Late / out-of-order events?"**
> "Aggregate by **event time** with **watermarks + allowed lateness**; very-late events go to a correction/batch job. Raw events in S3 let you reprocess if logic changes."

> **"A viral ad overloads one partition — hot key?"**
> "**Salt the key** — spread `(ad_id, salt)` across K partitions/tasks, aggregate partials, then sum. Optionally pre-aggregate on the client to cut volume."

> **"Lambda vs Kappa?"**
> "Lambda = fast approximate streaming + accurate batch recompute (two codebases). Kappa = streaming only, reprocess by replaying the log (simpler, needs trusted exactly-once)."

---

## 15. Final Takeaways

- **Pre-aggregate as a stream** into event-time windows; serve rollups from an **OLAP store** — never recount raw per query.
- **Kafka (partition by ad_id) → Flink windowed aggregation → OLAP**; archive raw to **S3** (reprocess/audit).
- **Dedup by click_id + exactly-once + watermarks** for late/out-of-order; **HLL** for unique counts.
- **Hot key → salting** (partials → sum); fraud filter before counting.
- **Lambda** (stream + batch reconcile) for accurate billing, or **Kappa** (stream + replay).
- Patterns: Producer-Consumer, Windowed Aggregation, Dedup/Idempotency, CQRS/Materialized View, Event Sourcing, HLL, Watermark, Salting.

### Related notes

- [Apache Kafka](../concepts/kafka.md) — ingest backbone + exactly-once
- [Video Streaming](video-streaming-system-design.md) (view counting) · [Leaderboard](leaderboard-system-design.md) · [Databases — Deep Dive](../concepts/databases-deep-dive.md) (OLAP/column stores)
