# API Paradigms — REST vs GraphQL vs gRPC

> How services expose functionality. The interview question is "which would you choose and why?" — the answer is **REST for public/CRUD, gRPC for internal service-to-service, GraphQL for flexible client-driven queries**.

> **How to read this doc:** each section has the dense summary first, then a **deep dive** (annotated sample requests/responses and the exact confusions that trip beginners up). Skim the summaries for revision; read the deep dives to actually understand.

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

### How REST works

REST models your app as **resources** (`user`, `order`, `post`), each addressed by a URL, and returned in a fixed shape the server defines. You name a resource and apply a standard HTTP verb to it — read one, create one, update it, delete it. The server decides which fields the response contains; you get that whole object.

- The **URL** identifies the resource (e.g. `/users/123`). Each resource has its own address.
- The **verb** is the action: `GET` (read), `POST` (create), `PUT`/`PATCH` (update), `DELETE` (remove).
- The **response** is a fixed JSON shape the server decides — you get all of its fields.

A real request/response, annotated:

```http
GET /users/123 HTTP/1.1        # verb GET = "read"; /users/123 = the resource
Host: api.example.com
Accept: application/json        # "please reply in JSON"
```

```http
HTTP/1.1 200 OK                 # status code: 200 = success (201 create, 404 not found, 429 too many)
Content-Type: application/json

{                               # the full object — you get every field, even ones you didn't need
  "id": 123,
  "name": "Ada",
  "email": "ada@example.com",   # ← you only wanted the name, but email + everything else comes along
  "address": { "city": "Pune", "zip": "411001" },
  "createdAt": "2026-01-02T10:00:00Z"
}
```

Getting the full object is exactly the **over-fetching** pain: you asked for a name and got the address and timestamps too. And if you now also need the user's last 3 posts, they aren't in this response — you make a **second** request to `/users/123/posts` (that extra round-trip is **under-fetching** / the N+1 round-trip problem). GraphQL (§2) exists to fix precisely these two.

#### Q: What actually makes an API "RESTful"? Is any HTTP+JSON API REST?

Not quite. REST means you model your app as **resources (nouns) addressed by URLs**, and you use **HTTP verbs + status codes** for the actions. `GET /users/123` is RESTful. `POST /getUserById` (a *verb in the URL*, a function call dressed as HTTP) is **not** REST — that's RPC style (§3). Rule of thumb: **REST URLs are nouns, RPC URLs are verbs.**

#### Q: Why is REST so easy to cache?

Because `GET /users/123` is a plain URL with no side effects, any layer in between — browser, CDN, proxy — can remember "the answer for this URL is X" and hand it back without bothering the server. GraphQL and gRPC lose this because they POST to a single endpoint (nothing distinctive in the URL to cache on).

---

## 2. GraphQL

A single endpoint; the **client specifies exactly what it wants** in a query. *(full annotated query + response in the deep dive below)*

| Pros | Cons |
| --- | --- |
| **No over/under-fetching** — client picks fields | Caching is harder (single POST endpoint) |
| One request for nested/related data | Complex queries can hammer the backend (need depth/cost limits) |
| Strong typed schema; great for varied clients (mobile/web) | Server complexity; N+1 resolver problem (needs DataLoader/batching) |

- Great when many **different clients** need different shapes of data (e.g. mobile vs web).

### How GraphQL works

With GraphQL the **client specifies exactly which fields it wants** in a query sent to a single endpoint, and the response contains **exactly** those fields — nothing more, nothing less. Contrast REST (§1), where the server decides the response shape.

- **Single endpoint** = one URL (usually `POST /graphql`) for everything, instead of one URL per resource.
- **The query** = you list the fields you want, including nested ones.
- **The response** = shaped like your query — same fields, same nesting.

The query and its response, side by side and annotated:

```graphql
# The client writes this query. Every line is a field it actually wants.
query {
  user(id: 123) {
    name              # ← give me name
    posts(last: 3) {  # ← and, in the SAME request, the 3 latest posts...
      title           #    ...but only their title
      likes           #    ...and likes (not body, not comments)
    }
  }
}
```

```json
// The response mirrors the query EXACTLY — no email, no address, no extra fields.
{
  "data": {
    "user": {
      "name": "Ada",
      "posts": [
        { "title": "Hello", "likes": 12 },
        { "title": "GraphQL tips", "likes": 30 },
        { "title": "Bye", "likes": 4 }
      ]
    }
  }
}
```

Two REST problems, solved in one shot:

- **No over-fetching** — you asked for `name`, so you got `name`. The email/address/timestamps never come along.
- **No under-fetching / no N+1 round-trips** — user *and* their posts arrive in **one** request, not `GET /users/123` followed by `GET /users/123/posts`.

#### Q: If it's so flexible, why not use GraphQL for everything?

The flexibility moves the cost onto the server. Because everything is one `POST /graphql`, that friendly HTTP caching from REST is gone (you cache at the field/resolver level instead — harder). And a client can write a nasty deep query (`user → posts → comments → author → posts → …`) that hammers the DB, so servers add **depth/cost limits**. There's also the **N+1 resolver problem**: fetching 3 posts can naïvely fire 3 separate "get author" DB calls — fixed with **DataLoader**-style batching. Power for the client = complexity for the server.

#### Q: Is GraphQL a database or a replacement for REST?

Neither. It's a **query language for your API** that sits in front of whatever you already have (databases, REST services, gRPC services). It doesn't store anything itself; it just lets clients ask for a custom shape and fans out to the real data sources behind it.

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

### How gRPC works

gRPC is designed for **fast service-to-service calls**, not for browsers or external developers. Both sides pre-agree on a compact binary contract, so messages are small and quick to parse — at the cost of not being human-readable.

- **RPC = Remote Procedure Call.** Instead of thinking in URLs/resources, you just **call a function** that happens to run on another machine: `userService.GetUser(123)`. It *feels* like a normal local method call.
- **Protobuf (Protocol Buffers)** = the pre-agreed shorthand. You write a `.proto` contract; a code generator spits out ready-made client + server classes in your language. Both sides are guaranteed to agree on the shape (strong typing).
- **Binary, not JSON** = the message goes over the wire as compact bytes, not human-readable text → smaller + faster to parse. The flip side: you can't just `curl` it and eyeball the result.

The contract, then calling it like a local function:

```proto
// user.proto — the shared CONTRACT. Run a code generator on this → client & server stubs.
service UserService {
  rpc GetUser (GetUserRequest) returns (User);   // a normal request/response call
}

message GetUserRequest { int64 id = 1; }         // the "1", "2" = field numbers (order on the wire)
message User {
  int64  id    = 1;
  string name  = 2;
  string email = 3;
}
```

```java
// Caller side — looks like a plain method call, but it's hitting another SERVICE over the network.
User u = userStub.getUser(                 // generated from the .proto above
    GetUserRequest.newBuilder().setId(123).build()
);
System.out.println(u.getName());           // "Ada" — the network trip is invisible to you
```

#### Q: When should I actually reach for gRPC (and when not)?

- **Use gRPC** for **internal service-to-service** calls where you control both ends and care about speed: microservice A calling microservice B, hundreds of times per request, low latency. Also when you need **streaming** (a long-lived flow of messages both ways) — gRPC does this natively over HTTP/2.
- **Don't use gRPC** as your **public API** or straight from a browser. Browsers can't speak raw gRPC without a proxy (grpc-web), it's not `curl`-friendly, and external developers expect REST/JSON. Typical setup: **public REST/GraphQL edge → internal gRPC** between your own services.

#### Q: How is gRPC different from just POSTing JSON to `/getUser`?

Both are "call a function on another server" (RPC in spirit). The differences are the **wire format and contract**: gRPC uses **binary protobuf over HTTP/2** with a **generated, strongly-typed contract** and multiplexing/streaming built in; hand-rolled JSON-RPC uses text over HTTP/1.1 with no enforced schema. gRPC is faster and safer for internal traffic; JSON is easier to read and debug.

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

### The three, back-to-back

| Style | Request model | You specify... | You get... |
| --- | --- | --- | --- |
| **REST** | resource + verb | "read resource #123" (`GET /users/123`) | the full object (fixed JSON) |
| **GraphQL** | field selection | the exact fields (and nesting) you want | exactly the fields you asked for |
| **gRPC** | function call | a typed call like `GetUser(123)` | a fast binary response |

The same "get user 123" in all three, so you can *see* the difference:

```http
# REST — a resource URL, get the whole object back as JSON
GET /users/123
```

```graphql
# GraphQL — one endpoint, you list the exact fields
query { user(id: 123) { name } }
```

```proto
# gRPC — a typed function call, binary on the wire
rpc GetUser (GetUserRequest) returns (User);   // called as userService.GetUser(123)
```

#### Q: REST vs RPC vs GraphQL — what's the *core* mental difference?

Think about **what you name in the request**:

- **REST** → you name a **noun** (a resource: `/users/123`) and pick an HTTP verb. "Here's a thing; do a standard action to it."
- **RPC / gRPC** → you name a **verb** (a function: `GetUser`, `ChargeCard`). "Run this procedure over there." REST models *data*; RPC models *actions*.
- **GraphQL** → you name the **exact shape of data** you want back. "Give me these specific fields, nested like this."

So they're three different questions: *which thing?* (REST), *which action?* (RPC/gRPC), *which fields?* (GraphQL).

#### Q: Over-fetching vs under-fetching — which is which, and who fixes it?

- **Over-fetching** = the response contains **more than you needed** (asked for a name, got the whole user object). Wastes bandwidth.
- **Under-fetching** = a single response has **too little**, so you make **extra round-trips** (get the user, *then* separately get their posts → the N+1 problem).
- **REST suffers from both** (fixed response shape + one-resource-per-URL). **GraphQL fixes both** by letting the client name exactly the fields and nest related data into one request.

#### Q: How does versioning work, and why does it differ per style?

Because clients and servers evolve at different speeds, you need a way to change an API without breaking existing callers:

- **REST** → usually a version in the **URL path** (`/v1/users`, `/v2/users`) or a header. Blunt but obvious; you run v1 and v2 side by side and retire v1 later.
- **GraphQL** → the community norm is **no version numbers**. You **add** new fields (safe — old clients ignore them) and **deprecate** old ones with `@deprecated`, since each client only asks for the fields it knows. The schema evolves in place.
- **gRPC / protobuf** → versioning is **baked into the wire format via field numbers**. As long as you only **add** new fields with **new numbers** (and never reuse or renumber old ones), old and new binaries stay compatible. That's why protobuf messages carry `= 1`, `= 2` tags.

One-liner: **REST versions the URL, GraphQL evolves the schema (add/deprecate), gRPC relies on stable protobuf field numbers.**

---

## 5. Related: Webhooks & Polling

- **Webhooks** — server calls *you* on an event (push). Great for async notifications (payments). Must verify signature + dedup.
- **Polling** — client asks repeatedly (simple, wasteful). **Long polling** holds the request open until data. For true real-time → **WebSocket/SSE** (see Real-Time Communication note).

### Webhooks vs polling

- **Polling** = the client repeatedly asks "is it ready yet?" on a fixed interval. Simple, but most requests are wasted because the answer is usually "not yet."
- **Long polling** = the client asks and the server *holds the request open* until data is ready, then responds. Fewer wasted requests.
- **Webhook** = the client registers a callback URL; the server **calls that URL** the moment the event happens. No wasted requests at all — the server pushes to the client on the event.

```http
# Webhook: the OTHER server POSTs to a URL YOU registered, when something happens.
POST https://your-app.com/webhooks/payments      # ← YOUR endpoint, they call it
X-Signature: sha256=abc...                        # verify this so randoms can't fake events
Content-Type: application/json

{ "event": "payment.succeeded", "orderId": 789 }  # you react, then reply 200 to acknowledge
```

Two must-dos for webhooks: **verify the signature** (confirm the event really came from the sender, not an attacker) and **dedup** (the sender may retry, so the same event can arrive twice — guard with the event id). Reach for **WebSocket/SSE** instead when you need a continuous real-time stream (chat, live prices) rather than occasional event pings.

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
