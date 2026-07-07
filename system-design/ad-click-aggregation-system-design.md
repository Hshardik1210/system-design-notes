# Ad Click Aggregation — System Design (Stream Analytics)

> **Core challenge:** ingest a **firehose of click events** (millions/sec), aggregate them into **near-real-time counts** (clicks per ad per minute) for dashboards + billing, while handling **duplicates, out-of-order/late events, and huge scale** — the canonical **stream-processing** problem. Billing needs **accuracy**; dashboards need **freshness**.

---

## Contents

- [1. Mental Model](#1-mental-model)
- [2. Requirements](#2-requirements)
- [3. Capacity Estimation](#3-capacity-estimation)
- [4. Pipeline Architecture](#4-pipeline-architecture)
- [5. Aggregation (windowing)](#5-aggregation-windowing)
- [6. Exactly-Once, Dedup & Late Events](#6-exactly-once-dedup--late-events)
- [7. Lambda vs Kappa (accuracy vs freshness)](#7-lambda-vs-kappa-accuracy-vs-freshness)
- [8. Data Model](#8-data-model)
- [9. Design Patterns (that can be used)](#9-design-patterns-that-can-be-used)
- [10. Interview Cheat Sheet](#10-interview-cheat-sheet)
- [11. Final Takeaways](#11-final-takeaways)

---

## 1. Mental Model

```
click event → ingest (Kafka) → stream aggregator (windowed counts) → store rollups → query/dashboard/billing
```

You almost never store-and-recount raw clicks per query — you **pre-aggregate as a stream** into time buckets, and serve those.

---

## 2. Requirements

**Functional**
- Record ad **click events** (ad_id, user, ts, ...); aggregate counts per ad per time window (minute/hour/day).
- Query aggregates (dashboards) + feed **billing** (accurate totals).
- Filter fraud/bot clicks; support drill-down (by region/campaign).

**Non-functional**
- **Massive write throughput** (millions/sec); **near-real-time** dashboards (seconds); **accurate** billing (reconcile); handle **duplicates + late/out-of-order** events; scalable, fault-tolerant.

---

## 3. Capacity Estimation

```
Clicks ~ 10B/day → ~115k/sec avg, peaks much higher
Raw event ~ 100–200 bytes → keep raw in cheap storage (S3), aggregates in fast store
Aggregates: ad_id × minute → far smaller, query-friendly
```

---

## 4. Pipeline Architecture

```
Ad clients ─► Ingestion API ─► Kafka (partitioned by ad_id) ─► Stream processor (Flink/Spark/Kafka Streams)
                                                                    │  windowed aggregation
                                                                    ▼
                                              Aggregate store (OLAP: Druid/ClickHouse/Cassandra)
                                                                    │
                                                          Dashboards · Billing · Alerts
                              raw events also archived → S3 / data lake (for reprocessing + audit)
```

- **Kafka** buffers the firehose + decouples ingest from processing; partition by `ad_id` for parallel aggregation + per-ad ordering.
- **Stream processor** (Flink is the standard) does windowed aggregation with state + checkpoints.
- **OLAP store** (Druid/ClickHouse) serves fast aggregate queries; raw events archived for reprocessing.

---

## 5. Aggregation (windowing)

Aggregate counts over **time windows** keyed by `ad_id`.

| Window | Meaning |
| --- | --- |
| **Tumbling** | Fixed, non-overlapping (per-minute buckets) — most common for counts |
| **Sliding** | Overlapping (last 5 min, updated each min) |
| **Session** | Grouped by activity gaps |

```
key = (ad_id, minute_bucket)
aggregate: count(clicks), unique_users (HyperLogLog), sum(revenue)
→ emit rollup per window → write to OLAP store
```

- **Event time vs processing time**: aggregate by **event time** (when the click happened) using timestamps, not when it arrived — else late events land in the wrong bucket.
- **Watermarks**: track "we've probably seen all events up to time T" to know when to close a window.
- **HyperLogLog** for approximate unique counts cheaply.

---

## 6. Exactly-Once, Dedup & Late Events

Billing can't over/under-count.

| Problem | Handling |
| --- | --- |
| **Duplicate clicks** (retries, at-least-once Kafka) | Each event carries a unique `click_id`; **dedup** in the processor (state store / keyed dedup) |
| **Late events** (mobile offline, network) | Watermarks + **allowed lateness**; update the window or route very-late events to a correction job |
| **Exactly-once counts** | Flink checkpoints + transactional sinks (Kafka EOS) → exactly-once aggregation |
| **Fraud/bots** | Filtering stage (rate per user/IP, ML) before counting |
| **Reprocessing** | Raw events in S3 → replay to rebuild aggregates if logic changes / bug |

---

## 7. Lambda vs Kappa (accuracy vs freshness)

Billing wants **accuracy**; dashboards want **freshness**. Two architectures:

| | **Lambda** | **Kappa** |
| --- | --- | --- |
| Paths | **Batch** (accurate, slow) + **Streaming** (fast, approximate) | **Streaming only** (reprocess by replaying the log) |
| Pros | Batch corrects streaming's approximations | One codebase; simpler |
| Cons | Two codebases to maintain | Relies on replayable log + strong stream processing |

```
Lambda: fast streaming rollups for live dashboards
      + nightly batch recompute from raw events for accurate billing (source of truth)
Kappa:  one streaming pipeline; reprocess by replaying Kafka/S3 when needed
```

> **Common answer:** streaming for real-time dashboards + a **batch reconciliation** for billing (Lambda-style), or Kappa with replay if the stream layer is trusted for exactly-once.

---

## 8. Data Model

```sql
-- Aggregates (served to dashboards/billing) — OLAP store
CREATE TABLE click_aggregates (
    ad_id      BIGINT, window_start TIMESTAMP, granularity VARCHAR(10),  -- MINUTE/HOUR/DAY
    clicks     BIGINT, unique_users BIGINT, revenue NUMERIC(14,2),
    PRIMARY KEY (ad_id, window_start, granularity)
);

-- Dedup state (in stream processor / KV): seen click_ids per window (TTL)
-- Raw events → S3 / data lake:  { click_id, ad_id, user_id, ts, ip, ... }  (audit + reprocess)
CREATE TABLE ads ( ad_id BIGINT PRIMARY KEY, campaign_id BIGINT, advertiser_id BIGINT );
```

> **Stores to consider:** Kafka (ingest buffer), stream-processor state (dedup/windows), OLAP store (Druid/ClickHouse — aggregates), raw event lake (S3), ads/campaign metadata (RDBMS).

---

## 9. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Producer-Consumer** | Ingest → Kafka → stream processors | Absorb + parallelize firehose |
| **Windowed Aggregation (stream)** | Tumbling/sliding windows by event time | Core rollup |
| **Idempotency / Dedup** | `click_id` dedup in processor | No double-count |
| **CQRS + Materialized View** | Pre-aggregated rollups vs raw events | Fast queries |
| **Lambda / Kappa** | Batch+stream vs stream-only | Accuracy vs freshness |
| **Event Sourcing** | Raw event log as truth; replay to rebuild | Reprocessing/audit |
| **Sketch / Probabilistic (HLL)** | Approx unique counts | Cheap cardinality |
| **Sharding / Partitioning** | Kafka + store partitioned by ad_id | Scale |
| **Watermark** | Handle late/out-of-order events | Correct windows |

---

## 10. Interview Cheat Sheet

> **"How do you count clicks at millions/sec?"**
> "Ingest into Kafka (partitioned by ad_id), aggregate with a stream processor (Flink) into **event-time windows** (per-minute buckets), write rollups to an OLAP store (Druid/ClickHouse) for dashboards, and archive raw events to S3. You never recount raw events per query."

> **"How do you avoid double-counting for billing?"**
> "Each click has a unique id; dedup in the processor's state store; use exactly-once stream processing (Flink checkpoints + transactional sinks). For billing accuracy, run a **batch reconciliation** over the raw event lake (Lambda) as the source of truth."

> **"Late / out-of-order events?"**
> "Aggregate by **event time** with **watermarks + allowed lateness**; very-late events go to a correction/batch job. Raw events in S3 let you reprocess if logic changes."

> **"Lambda vs Kappa?"**
> "Lambda = fast approximate streaming + accurate batch recompute (two codebases). Kappa = streaming only, reprocess by replaying the log (simpler, needs a trusted stream layer)."

---

## 11. Final Takeaways

- **Pre-aggregate as a stream** into event-time windows; serve rollups from an OLAP store — never recount raw per query.
- **Kafka (partition by ad_id) → Flink windowed aggregation → OLAP store**; archive raw to S3.
- **Dedup by click_id + exactly-once + watermarks** for late/out-of-order events.
- **Lambda** (stream + batch reconcile) for accurate billing, or **Kappa** (stream + replay).
- Patterns: Producer-Consumer, Windowed Aggregation, Idempotency/Dedup, CQRS/Materialized View, Event Sourcing, HLL, Watermark.

### Related notes

- [Apache Kafka](../concepts/kafka.md) — the ingest backbone + exactly-once
- [Leaderboard](leaderboard-system-design.md) · [Distributed Cache](distributed-cache-system-design.md) · [Caching Strategies](../concepts/caching-strategies.md)
