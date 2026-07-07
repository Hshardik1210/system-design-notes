# API Paradigms — REST vs GraphQL vs gRPC

> How services expose functionality. The interview question is "which would you choose and why?" — the answer is **REST for public/CRUD, gRPC for internal service-to-service, GraphQL for flexible client-driven queries**.

---

## Contents

- [1. REST](#1-rest)
- [2. GraphQL](#2-graphql)
- [3. gRPC](#3-grpc)
- [4. Comparison & When to Use](#4-comparison--when-to-use)
- [5. Related: Webhooks & Polling](#5-related-webhooks--polling)
- [6. Interview Cheat Sheet](#6-interview-cheat-sheet)
- [7. Final Takeaways](#7-final-takeaways)

---

## 1. REST

Resources addressed by URLs, manipulated with HTTP verbs.

```
GET    /users/123          # read
POST   /users              # create
PUT    /users/123          # replace
PATCH  /users/123          # partial update
DELETE /users/123          # delete
```

| Pros | Cons |
| --- | --- |
| Simple, ubiquitous, cacheable (HTTP caching) | **Over-fetching** (get more than needed) / **under-fetching** (N+1 calls) |
| Stateless, works everywhere, great tooling | Many round trips for related data |
| Clear semantics (verbs + status codes) | No strict schema by default |

- Use HTTP status codes (200/201/400/404/409/429/500), idempotent verbs (GET/PUT/DELETE), pagination (cursor), versioning (`/v1`).

---

## 2. GraphQL

A single endpoint; the **client specifies exactly what it wants** in a query.

```graphql
query {
  user(id: 123) {
    name
    posts(last: 3) { title likes }
  }
}
```

| Pros | Cons |
| --- | --- |
| **No over/under-fetching** — client picks fields | Caching is harder (single POST endpoint) |
| One request for nested/related data | Complex queries can hammer the backend (need depth/cost limits) |
| Strong typed schema; great for varied clients (mobile/web) | Server complexity; N+1 resolver problem (needs DataLoader/batching) |

- Great when many **different clients** need different shapes of data (e.g. mobile vs web).

---

## 3. gRPC

Binary RPC over HTTP/2 using **Protocol Buffers** (typed contract, code-generated stubs).

```proto
service UserService {
  rpc GetUser (GetUserRequest) returns (User);
  rpc StreamUsers (Query) returns (stream User);   // streaming
}
```

| Pros | Cons |
| --- | --- |
| **Fast + compact** (binary protobuf, HTTP/2 multiplexing) | Not human-readable; browser support needs a proxy (grpc-web) |
| Strong typed contract, code-gen, **streaming** | More setup; less ubiquitous than REST |
| Ideal **service-to-service** (microservices, low latency) | Harder to debug/curl |

- Best for **internal, high-throughput, low-latency** service communication and streaming.

---

## 4. Comparison & When to Use

| | **REST** | **GraphQL** | **gRPC** |
| --- | --- | --- | --- |
| Transport | HTTP/1.1+ | HTTP (POST) | HTTP/2 |
| Payload | JSON | JSON | Protobuf (binary) |
| Schema | Optional (OpenAPI) | Strong | Strong (proto) |
| Caching | Easy (HTTP) | Hard | Hard |
| Streaming | Limited (SSE/WS) | Subscriptions | Native bidirectional |
| Best for | **Public APIs, CRUD** | **Flexible client queries** | **Internal microservices** |

> **Rule of thumb:** REST for public/simple APIs, **gRPC for internal service-to-service**, GraphQL when diverse clients need tailored data. They coexist — public REST/GraphQL gateway → internal gRPC.

---

## 5. Related: Webhooks & Polling

- **Webhooks** — server calls *you* on an event (push). Great for async notifications (payments). Must verify signature + dedup.
- **Polling** — client asks repeatedly (simple, wasteful). **Long polling** holds the request open until data. For true real-time → **WebSocket/SSE** (see Real-Time Communication note).

---

## 6. Interview Cheat Sheet

> **"REST vs GraphQL vs gRPC — which and why?"**
> "REST for public CRUD APIs (simple, cacheable). GraphQL when clients need flexible, tailored data and you want to avoid over/under-fetching. gRPC for internal service-to-service — binary protobuf over HTTP/2, fast, typed, streaming. Often a public REST/GraphQL edge fronting internal gRPC."

> **"GraphQL downsides?"**
> "Harder caching (single endpoint), the N+1 resolver problem (mitigate with DataLoader batching), and query-cost/depth limits to prevent expensive queries."

> **"Why gRPC internally?"**
> "Compact binary payloads, HTTP/2 multiplexing, code-generated typed clients, and native streaming — low latency and strong contracts for microservices."

---

## 7. Final Takeaways

- **REST** = resources + HTTP verbs; simple, cacheable; over/under-fetching pain.
- **GraphQL** = client picks fields; no over-fetch; caching + N+1 challenges (DataLoader, cost limits).
- **gRPC** = protobuf over HTTP/2; fast, typed, streaming; best **internal** service-to-service.
- Choose per use: **public/CRUD → REST**, **flexible clients → GraphQL**, **internal → gRPC**; they coexist.

### Related notes

- [Networking Essentials](networking-essentials.md) · [Proxies & API Gateway](proxies-and-api-gateway.md) · [Real-Time Communication](real-time-communication.md)
