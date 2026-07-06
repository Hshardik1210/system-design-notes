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
| [Caching Strategies](concepts/caching-strategies.md) | Read/write strategies, eviction policies, invalidation, thundering herd / penetration / avalanche / hot key |
| [Consistent Hashing](concepts/consistent-hashing.md) | The `hash % N` problem, the hash ring, virtual nodes, use cases & trade-offs |
| [Rate Limiting](concepts/rate-limiting.md) | Fixed/sliding window, token bucket, leaky bucket, distributed rate limiting with Redis |
| [Proxies & API Gateway](concepts/proxies-and-api-gateway.md) | Forward vs reverse proxy, API gateway features, how they fit together |
| [Scaling Architecture](concepts/scaling-architecture.md) | How an architecture evolves from small scale to millions of users |

---

## 🏗️ System Design

End-to-end designs that apply the concepts above.

| Note | What's inside |
| --- | --- |
| [BookMyShow — System Design](system-design/bookmyshow-system-design.md) | Seat locking, atomic conditional updates, Redis+DB hybrid lock, payments, outbox+saga, scaling, CAP trade-offs |
| [BookMyShow — HLD & LLD](system-design/bookmyshow-hld-lld.md) | High- & low-level design: architecture, DDL schema, API contracts, state machines, class design & interfaces |
| [URL Shortener — System Design](system-design/url-shortener-system-design.md) | base62 encoding, distributed ID generation (KGS/Snowflake), redirects (301 vs 302), scaling & reliability |

---

## 🗂️ Structure

```
.
├── README.md              ← this index
├── concepts/              ← reusable building blocks
└── system-design/         ← full end-to-end designs
```
