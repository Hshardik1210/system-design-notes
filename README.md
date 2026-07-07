# 📚 Engineering & System Design Notes

Interview-prep and reference notes on backend engineering, distributed systems, and system design. Each topic is its own self-contained Markdown file — click any link below to read it (GitHub renders it formatted, and the **outline button** on each file lets you jump between sections).

---

## 🧠 Concepts

Foundational building blocks — reusable across any system design.

| Note | What's inside |
| --- | --- |
| [Authentication & Sessions](concepts/authentication-and-sessions.md) | Sessions vs JWT, signing (HS256/RS256), access + refresh tokens, logout gotchas, multi-device sessions, OAuth2/OIDC/SSO |
| [Idempotency](concepts/idempotency.md) | Idempotency keys, safe retries, HTTP method semantics, designing naturally idempotent operations |
| [Outbox & Saga Patterns](concepts/outbox-and-saga.md) | Reliable event publishing (outbox), distributed transactions (saga), choreography vs orchestration, 2PC comparison |
| [Database Fundamentals](concepts/database-fundamentals.md) | SQL vs NoSQL, ACID vs BASE, isolation levels, replication, CAP/PACELC, partitioning & sharding |
| [Database Indexing](concepts/database-indexing.md) | B-Tree vs hash, clustered vs non-clustered, covering & composite indexes, leftmost-prefix rule |
| [Databases — Deep Dive](concepts/databases-deep-dive.md) | Storage internals (B-Tree/LSM/column/inverted/vector), MySQL vs Postgres at scale, Mongo/Cassandra/Redis/Elasticsearch/Vector DBs, example queries, replication + sharding + **how clusters route to the right node** (Redis hash slots, Mongo mongos/config servers, Cassandra token ring), decision framework |
| [Caching Strategies](concepts/caching-strategies.md) | Read/write strategies, eviction policies, invalidation, thundering herd / penetration / avalanche / hot key |
| [Consistent Hashing](concepts/consistent-hashing.md) | The `hash % N` problem, the hash ring, virtual nodes, use cases & trade-offs |
| [Apache Kafka](concepts/kafka.md) | Distributed log, partitions & ordering, consumer groups, deciding partition count, scaling consumers/concurrency, consumer lag handling, delivery semantics, replication |
| [Rate Limiting](concepts/rate-limiting.md) | Fixed/sliding window, token bucket, leaky bucket, distributed rate limiting with Redis |
| [Load Balancing](concepts/load-balancing.md) | L4 vs L7, algorithms (round robin, least conn, consistent hash), health checks, failover, stickiness, HA |
| [Networking Essentials](concepts/networking-essentials.md) | DNS, TCP vs UDP, HTTP/1.1/2/3 (QUIC), TLS/HTTPS handshake, mTLS |
| [API Paradigms](concepts/api-paradigms.md) | REST vs GraphQL vs gRPC — trade-offs and when to use each; webhooks/polling |
| [Real-Time Communication](concepts/real-time-communication.md) | WebSocket vs SSE vs long/short polling, scaling persistent connections |
| [Bloom Filters](concepts/bloom-filters.md) | Probabilistic membership, no false negatives, sizing, variants, where used |
| [Fan-Out / Fan-In & Celebrity Problem](concepts/fan-out-fan-in.md) | Fan-out push vs pull vs hybrid, celebrity/write-amplification problem, scatter-gather fan-in, where used |
| [Observability](concepts/observability.md) | Logs/metrics/traces (3 pillars), RED/USE, percentiles, SLI/SLO/SLA, error budgets, alerting |
| [Proxies & API Gateway](concepts/proxies-and-api-gateway.md) | Forward vs reverse proxy, API gateway features, how they fit together |
| [Scaling Architecture](concepts/scaling-architecture.md) | How an architecture evolves from small scale to millions of users |

---

## 🏗️ System Design

End-to-end designs that apply the concepts above.

| Note | What's inside |
| --- | --- |
| [BookMyShow — System Design](system-design/bookmyshow-system-design.md) | Seat locking, atomic conditional updates, Redis+DB hybrid lock, payments, outbox+saga, scaling, CAP trade-offs |
| [BookMyShow — HLD & LLD](system-design/bookmyshow-hld-lld.md) | High- & low-level design: architecture, DDL schema, API contracts, state machines, class design & interfaces |
| [URL Shortener — System Design](system-design/url-shortener-system-design.md) | base62 encoding, distributed ID generation (KGS/Snowflake), redirects (301 vs 302), caching, analytics, scaling & reliability |
| [Food Ordering & Delivery — System Design](system-design/food-ordering-system-design.md) | Swiggy/Zomato: 3-sided marketplace, hyperlocal geo-search, real-time delivery-partner dispatch, live GPS tracking, ETA, order state machine, vs Flipkart comparison |
| [Food Ordering & Delivery — HLD & LLD](system-design/food-ordering-hld-lld.md) | Companion: **all tables** (DDL), full API contracts, class design, **design patterns**, state machines, algorithms, sequences |
| [Ride-Hailing — System Design](system-design/ride-hailing-system-design.md) | Uber/Ola: geospatial driver index, real-time matching/dispatch, live tracking, surge pricing, trip state machine, design patterns |
| [Chat & Messaging — System Design](system-design/whatsapp-chat-system-design.md) | WhatsApp: WebSocket gateways, connection registry, delivery/read receipts, offline sync, group fan-out, presence, ordering, design patterns |
| [Hotel Management & Reservation — System Design](system-design/hotel-management-system-design.md) | Date-range availability, overbooking prevention (atomic conditional update), reservations, payments, search, design patterns |
| [Airbnb — System Design](system-design/airbnb-system-design.md) | Lodging marketplace: geo+faceted search, per-listing calendar booking, instant vs request-to-book, payouts, reviews, design patterns |
| [Parking Lot — System Design (OOD/LLD)](system-design/parking-lot-system-design.md) | Object-oriented design: vehicle/spot modeling, spot assignment, pricing, and a heavy focus on **design patterns** |
| [Collaborative Editor (Google Docs) — System Design](system-design/google-docs-system-design.md) | Concurrent editing, Operational Transformation vs CRDTs, op log + snapshots, real-time sync, presence, design patterns |
| [Reddit — System Design](system-design/reddit-system-design.md) | Communities, feed fan-out, hot/top ranking, voting at scale, nested comments, design patterns |
| [Quora — System Design](system-design/quora-system-design.md) | Q&A platform: question dedup/search, answer-quality ranking, topic feeds, voting, design patterns |
| [Distributed Job Scheduler — System Design](system-design/distributed-scheduler-system-design.md) | Cron-as-a-service: finding due jobs, exactly-once-ish execution, leases, recurring jobs, retries/DLQ, design patterns |
| [Twitter / News Feed — System Design](system-design/twitter-news-feed-system-design.md) | Feed fan-out (push/pull/hybrid), celebrity hot-key, timeline cache, ranking, design patterns |
| [Facebook — System Design](system-design/facebook-system-design.md) | Bidirectional social graph (TAO-style), ranked News Feed fan-out, posts/reactions/comments, groups/pages, design patterns |
| [Instagram — System Design](system-design/instagram-system-design.md) | Media pipeline + CDN, follow-graph feed fan-out, Stories (TTL), Explore, design patterns |
| [Snake & Ladder — LLD (OOD)](system-design/snake-and-ladder-system-design.md) | Object-oriented game design: unified Jump abstraction, turn queue, rule strategies, **design patterns** |
| [Google Maps / Proximity — System Design](system-design/maps-proximity-system-design.md) | Geospatial indexing (geohash/quadtree/S2/H3), nearby search, routing/ETA, moving objects, design patterns |
| [Ad Click Aggregation — System Design](system-design/ad-click-aggregation-system-design.md) | Stream analytics: Kafka + Flink windowed aggregation, dedup/exactly-once, late events, Lambda vs Kappa, design patterns |
| [Splitwise — System Design](system-design/splitwise-system-design.md) | Expense splitting (Strategy), pairwise balances (double-entry), debt simplification (greedy), design patterns |
| [Elevator — LLD (OOD)](system-design/elevator-system-design.md) | SCAN/LOOK movement, dispatch strategy, elevator state machine, design patterns |
| [Vending Machine — LLD (OOD)](system-design/vending-machine-system-design.md) | The canonical **State pattern**: state machine, change-making strategy, design patterns |
| [LRU Cache — LLD](system-design/lru-cache-system-design.md) | O(1) get/put via HashMap + doubly linked list, eviction, TTL, approximate LRU, design patterns |
| [Typeahead / Autocomplete — System Design](system-design/typeahead-autocomplete-system-design.md) | Trie with precomputed top-k, offline ranking pipeline, sharding, design patterns |
| [Web Crawler — System Design](system-design/web-crawler-system-design.md) | URL frontier, dedup (Bloom/SimHash), politeness/robots.txt, freshness, design patterns |
| [Video Streaming (YouTube/Netflix) — System Design](system-design/video-streaming-system-design.md) | Upload + transcoding pipeline, adaptive bitrate (HLS/DASH), CDN, metadata, design patterns |
| [File Storage & Sync (Dropbox/Drive) — System Design](system-design/file-storage-sync-system-design.md) | Chunking + dedup, delta sync, metadata vs block store, versioning/conflicts, design patterns |
| [Distributed Cache (Redis-like) — System Design](system-design/distributed-cache-system-design.md) | Consistent hashing, eviction (LRU/LFU), replication/failover, stampede/penetration, design patterns |
| [Payment System — System Design](system-design/payment-system-system-design.md) | Idempotency, double-entry ledger, webhooks + reconciliation, refunds/payouts, PCI, design patterns |
| [Distributed Unique ID Generator — System Design](system-design/distributed-id-generator-system-design.md) | Snowflake vs UUIDv7 vs range allocation, clock issues, design patterns |
| [Leaderboard / Ranking — System Design](system-design/leaderboard-system-design.md) | Redis sorted sets, top-N / rank / around-me, sharding + approximate rank, design patterns |
| [Notification System — System Design](system-design/notification-system-design.md) | Async queue-based delivery, multi-channel workers, fan-out, idempotency, retries/DLQ, templates, scheduling, push/in-app/WebSocket |
| [Notification System — HLD & LLD](system-design/notification-system-hld-lld.md) | HLD/LLD companion: architecture, full DDL, API contracts, orchestrator/worker class design, state machines, sequences |

---

## 🗂️ Structure

```
.
├── README.md              ← this index
├── concepts/              ← reusable building blocks
└── system-design/         ← full end-to-end designs
```
