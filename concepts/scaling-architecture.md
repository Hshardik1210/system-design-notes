# Scaling Architecture (single box → distributed ecosystem)

> **Core idea:** scaling is a **journey** — you evolve the architecture one bottleneck at a time, from a single server to a globally distributed system. The skill is knowing **which bottleneck to fix next** and **which lever** (vertical vs horizontal, cache, replicas, shard, async) to pull.

---

## Contents

- [1. Vertical vs Horizontal Scaling](#1-vertical-vs-horizontal-scaling)
- [2. The Evolution Journey (1 user → millions)](#2-the-evolution-journey-1-user--millions)
- [3. Stateless Services (the enabler)](#3-stateless-services-the-enabler)
- [4. Scaling the Database (the usual bottleneck)](#4-scaling-the-database-the-usual-bottleneck)
- [5. High-Scale Architecture](#5-high-scale-architecture)
- [6. What Changes at Massive Scale](#6-what-changes-at-massive-scale)
- [7. How to Find the Bottleneck](#7-how-to-find-the-bottleneck)
- [8. Before vs After](#8-before-vs-after)
- [9. Interview Cheat Sheet](#9-interview-cheat-sheet)
- [10. Final Takeaways](#10-final-takeaways)

---

## 1. Vertical vs Horizontal Scaling

| | **Vertical (scale up)** | **Horizontal (scale out)** |
| --- | --- | --- |
| How | Bigger box (more CPU/RAM) | More boxes |
| Pros | Simple, no code changes | Near-limitless, fault-tolerant |
| Cons | **Hard ceiling**, expensive, single point of failure | Needs statelessness, load balancing, distributed data |
| Use | Quick early wins | The real answer at scale |

> Start vertical (cheap, easy) → hit the ceiling → go **horizontal** (the only path to millions of users). Horizontal scaling **requires stateless services** (§3) and **distributed data** (§4).

---

## 2. The Evolution Journey (1 user → millions)

The classic progression — each step fixes the bottleneck the previous step exposed:

```
Stage 0 — Single server
  [ App + DB on one box ]                        ← simplest; dies under load / any failure

Stage 1 — Split app and DB
  [ App server ] → [ DB server ]                 ← independent scaling; DB gets its own resources

Stage 2 — Multiple app servers + Load Balancer
  Client → [ LB ] → [ App1, App2, App3 ] → DB    ← horizontal app scaling; needs STATELESS apps
                                                    (sessions → Redis/JWT, not local memory)

Stage 3 — Add a Cache
  App → [ Redis cache ] → DB                      ← offload hot reads; cut DB load massively

Stage 4 — Database read replicas
  writes → Primary ─replicate→ Replicas ← reads   ← scale READS; beware replication lag

Stage 5 — CDN for static content
  Client → [ CDN ] → App                          ← images/JS/CSS/video from the edge; global latency

Stage 6 — Shard the database (scale WRITES)
  users 1–1M → Shard A · 1M–2M → Shard B · ...    ← one primary can't take all writes; shard by key

Stage 7 — Microservices + async (Kafka)
  monolith → User/Order/Payment services; events  ← independent deploy/scale; decouple with a queue

Stage 8 — Multi-region + GeoDNS
  DNS → nearest region (Mumbai/Virginia)          ← global low latency + DR/failover
```

> **Order matters:** cache before replicas, replicas before sharding, sharding before multi-region — you always **fix the current bottleneck**, don't over-engineer early.

---

## 3. Stateless Services (the enabler)

Horizontal scaling only works if **any request can hit any server**.

```
Stateful (bad):  session/data stored in the app server's local memory
   → user must always hit the SAME server (sticky sessions) → can't freely scale/replace
Stateless (good): app servers hold NO per-user state
   → session in Redis / a JWT; files in blob store; any server serves any request
```

- Move state **out**: sessions → Redis/JWT, uploads → blob store (S3), cache → Redis.
- Stateless servers = **freely add/remove/replace** behind the load balancer; zero-downtime deploys.

---

## 4. Scaling the Database (the usual bottleneck)

The DB is almost always the first hard limit. The ladder:

| Step | Technique | Scales | Watch out |
| --- | --- | --- | --- |
| 1 | **Indexes + query tuning** | Both | Over-indexing slows writes |
| 2 | **Caching (Redis)** in front | Reads | Invalidation, stampede |
| 3 | **Read replicas** | Reads + HA | **Replication lag** (read-your-writes) |
| 4 | **Vertical scale** the primary | Both | Ceiling |
| 5 | **Partitioning** (one DB, split tables by time/range) | Both | Must query by partition key |
| 6 | **Sharding** (many DBs by key) | **Writes** + storage | Cross-shard queries, hot shards, resharding |
| 7 | **NewSQL / different store** | Both | Migration cost |

- **Reads scale easily** (cache + replicas); **writes are the hard part** → sharding (or NewSQL like Spanner/CockroachDB). See the **Databases — Deep Dive** note.

---

## 5. High-Scale Architecture

```
                    🌍 Global Users
                          |
                 ┌──────────────────┐
                 │     DNS (Geo)    │
                 └──────────────────┘
                          |
                 ┌──────────────────┐
                 │    CDN / Edge    │
                 └──────────────────┘
                          |
                 ┌────────────────────────────┐
                 │ API Gateway (multi-region)  │
                 └────────────────────────────┘
                          |
                 ┌──────────────────┐
                 │ Reverse Proxy/LB │
                 └──────────────────┘
                    /        |        \
          ┌────────────┐ ┌────────────┐ ┌────────────┐
          │ User Svc   │ │ Order Svc  │ │ Payment Svc│
          └────────────┘ └────────────┘ └────────────┘
                 |              |              |
           ┌──────────┐  ┌──────────┐   ┌──────────┐
           │ DB Shard │  │ DB Shard │   │ DB Shard │  (+ replicas, + Redis, + Kafka)
           └──────────┘  └──────────┘   └──────────┘
```

---

## 6. What Changes at Massive Scale

| # | Change | Why |
| --- | --- | --- |
| 1 | **Global routing (GeoDNS)** | Client → nearest region → lower latency, HA |
| 2 | **CDN** | Static/media from the edge → massive backend offload |
| 3 | **API Gateway per region** | Regional rate limiting, auth caching, routing |
| 4 | **Smart L7 load balancing** | Round-robin / least-conn / latency-based |
| 5 | **Services auto-scale** | `Order Svc: 100+ pods` via Kubernetes HPA |
| 6 | **DB sharding** | One DB can't take millions of writes |
| 7 | **Heavy caching (Redis)** | Sessions, hot data → cut DB load |
| 8 | **Event-driven (Kafka)** | Decouple services, async, independent scale |
| 9 | **Rate limiting** | Throttle abuse at millions of req/sec |
| 10 | **Observability** | Logs + metrics + traces become mandatory (see Observability note) |
| 11 | **Failures are normal** | Multi-region + failover; design for partial failure |

---

## 7. How to Find the Bottleneck

Don't scale blindly — **measure, then fix the actual limit**:

```
1. Metrics first: CPU, memory, DB QPS/latency, cache hit ratio, queue lag, p99
2. Identify the saturated resource (the one at ~100%):
     app CPU high      → add app servers (horizontal) / optimize code
     DB reads high     → cache + read replicas
     DB writes high    → shard (or NewSQL)
     cache miss high   → size the cache / fix TTLs / warm it
     latency spikes tail→ check p99, GC, hot keys, N+1 queries
     queue lag growing → more consumers (≤ partitions), faster processing
3. Fix that one → re-measure → the bottleneck moves → repeat
```

> **Fixing one bottleneck exposes the next** (e.g. more workers → DB overload). Scale is an iterative loop, not a one-shot.

---

## 8. Before vs After

| Component | Small Scale | Massive Scale |
| --- | --- | --- |
| App servers | 1 | Many (auto-scaled), **stateless** |
| Load balancer | none/basic | Smart L7, multi-region |
| DB | Single | **Sharded** + replicas |
| Cache | Optional | **Mandatory** |
| Kafka | Optional | **Core** (event backbone) |
| CDN | Optional | **Critical** |
| Regions | 1 | Multi-region + GeoDNS + failover |

```
Small system:  Request → Server → DB
Large system:  Request → Edge(CDN) → Gateway → LB → Service → Cache → DB(shards+replicas) → Events(Kafka)
```

---

## 9. Interview Cheat Sheet

> **"How would you scale this from 1 to millions of users?"**
> "Iteratively, fixing one bottleneck at a time: split app/DB → add a load balancer + **stateless** app servers → cache hot reads (Redis) → read replicas → CDN for static → **shard** the DB for writes → microservices + Kafka for async → multi-region with GeoDNS. Measure, fix the saturated resource, repeat."

> **"Vertical vs horizontal?"**
> "Vertical (bigger box) is simple but hits a ceiling and is a SPOF. Horizontal (more boxes) is the real answer — it needs **stateless services** and **distributed data**, but scales near-limitlessly with fault tolerance."

> **"What's usually the bottleneck?"**
> "The database. Reads scale with cache + replicas; **writes** are the hard part → shard by key (or NewSQL). Watch replication lag and hot shards."

> **"Why stateless?"**
> "So any request can hit any server → you can freely add/remove/replace servers behind the LB. Push state to Redis/JWT and blob storage."

---

## 10. Final Takeaways

- Scaling is a **journey**: single box → split → LB + stateless app tier → cache → replicas → CDN → shard → microservices/Kafka → multi-region. **Fix one bottleneck at a time.**
- **Vertical** (up) hits a ceiling; **horizontal** (out) + distribution reaches millions — enabled by **stateless services**.
- **DB is the usual bottleneck**: reads → cache + replicas; **writes → sharding** (or NewSQL).
- At massive scale: **GeoDNS, CDN, multi-region gateways, smart LBs, sharded DBs, caching, Kafka, observability, failover** — scale *every* layer.
- **Measure → fix the saturated resource → re-measure**; don't over-engineer early.

### Related notes

- [Databases — Deep Dive](databases-deep-dive.md) · [Database Fundamentals](database-fundamentals.md) — replication, partitioning, sharding
- [Caching Strategies](caching-strategies.md) · [Consistent Hashing](consistent-hashing.md) · [Load Balancing](load-balancing.md) · [Apache Kafka](kafka.md) · [Observability](observability.md)
