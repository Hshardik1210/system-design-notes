# Forward Proxy, Reverse Proxy & API Gateway

> **In one line:** **Forward proxy** protects the *client*, **reverse proxy** protects the *server*, **API gateway** = reverse proxy + brains 🧠.

> **How to read this doc:** each section gives the dense summary first, then a **deep dive** (an annotated config/example and the exact confusions beginners hit). Skim the summaries for revision; read the deep dives to actually understand.

---

## Contents

- [1. Big Picture (one line each)](#1-big-picture-one-line-each)
- [2. Forward Proxy](#2-forward-proxy)
- [3. Reverse Proxy](#3-reverse-proxy)
- [4. API Gateway](#4-api-gateway)
- [5. Key Differences](#5-key-differences)
- [6. How They Fit Together](#6-how-they-fit-together)
- [Common Mistakes](#common-mistakes)
- [API Gateway vs Service Mesh](#api-gateway-vs-service-mesh)
- [CDN vs Reverse Proxy vs API Gateway](#cdn-vs-reverse-proxy-vs-api-gateway)
- [Edge Features Worth Knowing](#edge-features-worth-knowing)
- [7. Interview Cheat Sheet](#7-interview-cheat-sheet)
- [8. Final Takeaways](#8-final-takeaways)

---

## 1. Big Picture (one line each)

Two everyday pictures make this whole page click. When an **employee's browser → corporate (forward) proxy → internet**, the proxy sits on the *client's* side and speaks for the employee (hiding them, logging/blocking what they reach). When a **user → nginx (reverse) proxy → app servers**, the proxy sits on the *server's* side and speaks for the backend (hiding it, spreading load, terminating TLS).

| Component | Role |
| --- | --- |
| **Forward Proxy** | Acts on behalf of the **client** |
| **Reverse Proxy** | Acts on behalf of the **server** |
| **API Gateway** | A **smart reverse proxy** for APIs |

---

## 2. Forward Proxy

> Sits **between the client and the internet**.

```
Client → Forward Proxy → Internet (Server)
```

### Examples
- Corporate network proxy
- VPN
- Tools to access restricted websites

### Use cases
- Hide **client** identity
- Access control
- Caching
- Bypass geo restrictions

> **Mental model:** *"Client says: please fetch this for me."*

### Forward Proxy in detail

A forward proxy makes outbound requests **on behalf of the client**. Instead of connecting to the internet directly, the client sends its request to the proxy, and the proxy makes the request to the destination. The destination sees the **proxy's** IP, not the client's. The proxy can also block certain destinations (e.g. a company policy denylist) and cache responses so repeated requests don't re-fetch.

So a forward proxy sits **on the client's side**, faces **outward**, and is something **the client (or its network) chose to route through**.

```
Client  →  Forward proxy  →  Outside world
           ^ acts for the CLIENT
           ^ hides the client's identity
           ^ can block / cache / log outgoing requests
```

**A concrete config — a browser told to route through a company proxy:**

```bash
# Every outgoing web request now goes through the company's forward proxy first.
export HTTP_PROXY="http://proxy.mycorp.com:8080"
export HTTPS_PROXY="http://proxy.mycorp.com:8080"

curl https://example.com   # curl → mycorp proxy → example.com
                           # example.com sees the PROXY's IP, not yours
```

Here the proxy is the thing *you* opt into. It can log the visit, block the site, or serve a cached copy — all decisions made on behalf of the **client**.

#### Q: Who "owns" a forward proxy — me or the website?

Your side. It's set up by **you or your network** (company IT, a VPN app you installed, a school network). The websites you visit have no idea it exists; they just see requests arriving from the proxy's address.

#### Q: Why would anyone route through a forward proxy instead of connecting directly?

Three everyday reasons: **privacy** (hide your real IP/identity), **control** (a company blocks or logs which sites employees reach), and **access** (bypass a geo-block by having a proxy in another country make the request for you). Caching is a bonus — repeated requests can be answered from the proxy without re-fetching.

#### Q: Is a VPN a forward proxy?

Close enough for the mental model: both make outbound requests *for* you and hide your identity from the destination. A VPN tunnels **all** your traffic at the network level, while a classic forward proxy usually handles specific protocols (like HTTP) — but both live **on the client side, facing out**.

---

## 3. Reverse Proxy

> Sits **in front of the servers**.

```
Client → Reverse Proxy → Backend Servers
```

### Examples
- Nginx
- Load balancer

### Use cases
- Load balancing
- SSL termination
- Caching
- Security (hide the backend)

> **Mental model:** *"Server says: talk to me through this gateway."*

### Reverse Proxy in detail

A reverse proxy is the reverse direction: it sits in front of the backend servers and accepts requests **on their behalf**. Clients connect to the reverse proxy as if it were the server; it decides which backend handles each request, can reject bad traffic, and never exposes the internal layout (how many servers there are or where they live). Clients think they're "talking to the server," but they're really talking to the reverse proxy, which forwards the request inward.

So a reverse proxy sits **on the server's side**, faces **inward**, and is something the **server owner** set up. Clients don't even know it's there.

```
Many clients  →  Reverse proxy  →  the right backend server
                 ^ acts for the SERVERS
                 ^ hides how many servers there are / where they live
                 ^ can load-balance, cache, terminate SSL, block bad traffic
```

**A concrete config — Nginx as a reverse proxy + load balancer:**

```nginx
# A pool of identical backend servers hidden behind the proxy.
upstream backend_servers {
    server 10.0.0.11:8080;   # server 1
    server 10.0.0.12:8080;   # server 2
    server 10.0.0.13:8080;   # server 3
}

server {
    listen 443 ssl;                       # clients connect here with HTTPS...
    server_name api.myapp.com;

    ssl_certificate     /etc/ssl/myapp.crt;   # SSL is TERMINATED here (decrypted
    ssl_certificate_key /etc/ssl/myapp.key;   # at the proxy), so backends can be plain HTTP

    location / {
        # forward the request to ONE of the pool servers (Nginx picks / balances)
        proxy_pass http://backend_servers;
        proxy_set_header X-Real-IP $remote_addr;   # pass the client's IP along
    }
}
```

The client only ever sees `api.myapp.com` (the reverse proxy). It has no idea there are three servers behind it, or that they run plain HTTP internally. Add a fourth server to the `upstream` block and clients notice nothing.

#### Q: How is this different from a forward proxy — they're both "a proxy"?

Same machinery (a middleman that forwards requests), **opposite side and direction**:

| | Forward proxy | Reverse proxy |
| --- | --- | --- |
| Sits on | the **client's** side | the **server's** side |
| Faces | **outward** (to the internet) | **inward** (to the backends) |
| Hides | **who the client is** | **how the servers are arranged** |
| Set up by | you / your network | the site/server owner |
| Client aware of it? | Yes (you configured it) | No (it looks like "the server") |

One sentence to remember: **forward proxy hides the *caller*; reverse proxy hides the *callees*.**

#### Q: Is a load balancer a reverse proxy?

In practice, yes — a load balancer is a reverse proxy whose main job is spreading traffic across a pool of backend servers. Nginx, HAProxy, and cloud load balancers all act as reverse proxies. Load balancing is just one of the jobs (alongside SSL termination, caching, and hiding the backend).

---

## 4. API Gateway

> A **specialized reverse proxy for APIs**.

```
Client → API Gateway → Microservices
```

### Examples
- AWS API Gateway
- Kong
- Zuul (Netflix)

### Extra features (beyond a plain reverse proxy)
- **Authentication** (JWT, OAuth)
- **Rate limiting**
- **Request routing**
- **Request transformation**
- **Aggregation** (combine multiple service calls into one response)

> **Mental model:** *"Smart entry point for all APIs."*

### API Gateway in detail

A plain reverse proxy just forwards a request to the right backend. An **API gateway** is a reverse proxy that also handles API-management concerns:

- **Authentication** — reject requests without a valid token before they reach any backend.
- **Rate limiting** — cap how often each client can call, so one client can't overload the backends.
- **Routing** — read the request path and send it to the right service — `/orders` to the Order service, `/payments` to the Payment service.
- **Aggregation** — call several backend services and combine the results into one response, instead of the client making several calls itself.
- **Request transformation** — reshape the request/response into the format each backend expects.

So: **API Gateway = reverse proxy + a layer that handles cross-cutting concerns (auth, limits, routing, shaping) in one place**, so every backend service doesn't have to re-implement them.

**A concrete config — a gateway route with auth + rate limit + routing (Kong-style):**

```yaml
routes:
  - name: orders-route
    paths: ["/api/orders"]        # requests to /api/orders...
    service: order-service        # ...are routed to the Order microservice
    plugins:
      - name: jwt                 # 1) AUTH: reject requests without a valid JWT
      - name: rate-limiting       # 2) LIMIT: cap how often a client can call
        config:
          minute: 100             #    max 100 requests/minute per client

  - name: payments-route
    paths: ["/api/payments"]      # a different path...
    service: payment-service      # ...routes to a different service
    plugins:
      - name: jwt
      - name: rate-limiting
        config:
          minute: 20              # payments are more sensitive → stricter limit
```

Notice the backend services (`order-service`, `payment-service`) contain **zero** auth or rate-limit code — the gateway does it at the edge, once, for everyone.

#### Q: What does an API gateway add over a plain reverse proxy?

A reverse proxy mostly answers "**which** server should handle this?" (routing + load balancing). A gateway also answers "**should this request even be allowed, and in what shape?**" — authentication, rate limiting, request/response transformation, and aggregation. Both forward traffic to backends; the gateway adds the API-management brain on top.

#### Q: Why do auth, rate limiting, and routing "at the edge" instead of inside each service?

Because these are **cross-cutting** concerns every service needs. Doing them once at the gateway means: (1) no duplicated auth code in 20 microservices, (2) bad/unauthorized traffic is rejected **before** it ever reaches (and loads) your backends, and (3) one consistent place to change the rules. The services stay focused on business logic.

#### Q: What is "aggregation" and why is it useful?

A phone app's home screen might need the user's profile, their recent orders, and their cart — normally three separate service calls. With aggregation, the app makes **one** call to the gateway, and the gateway fans out to the three services and stitches the results into a single response. Fewer round trips over the slow mobile network = a faster app. (This is also called the *Backend-for-Frontend* pattern.)

---

## 5. Key Differences

| Feature | Forward Proxy | Reverse Proxy | API Gateway |
| --- | --- | --- | --- |
| Sits for | Client | Server | Server |
| Hides | Client | Server | Server |
| Used by | Client | Server infra | Microservices |
| Main job | Control outgoing requests | Route incoming traffic | Manage APIs |
| Intelligence | Low | Medium | High |

### One-line summary

| Component | In one line |
| --- | --- |
| **Forward Proxy** | Makes outbound requests on behalf of the client, hiding the client |
| **Reverse Proxy** | Accepts inbound requests on behalf of the servers, hiding the backends |
| **API Gateway** | A reverse proxy that also does auth, rate limiting, routing, and aggregation |

### Sorting out the confusions

All three are "a middleman that forwards requests," which is exactly why they blur together. The trick is to always ask **two questions: (1) which side is it on? (2) how much thinking does it do?**

```
Forward proxy   →  CLIENT side,  faces out,   hides the CALLER,  low intelligence
Reverse proxy   →  SERVER side,  faces in,    hides the CALLEES, medium intelligence
API gateway     →  SERVER side,  faces in,    hides the CALLEES, HIGH intelligence (auth/limits/routing/aggregation)
```

#### Q: Forward vs reverse proxy — the one-sentence tell?

**A forward proxy acts for the *client* (making outbound requests on its behalf); a reverse proxy acts for the *servers* (accepting inbound requests and routing them to the right backend).** Same technology, opposite side of the conversation. If *you* set it up to reach the outside world → forward. If the *site owner* set it up so you can reach their servers → reverse.

#### Q: Is an API gateway a reverse proxy, or something totally different?

It **is** a reverse proxy — a specialized one. Every API gateway does reverse-proxy things (routing traffic to backends, hiding them). It just adds an API-management brain (auth, rate limiting, transformation, aggregation) on top. So the relationship is: *API gateway ⊂ reverse proxy* (a reverse proxy with extra powers), **not** two unrelated boxes.

#### Q: Where do auth, rate limiting, and routing actually happen — client or server side?

At the **edge on the server side**, inside the reverse proxy / API gateway — the first piece of your infrastructure that a request touches. That's the ideal spot to reject unauthorized or abusive traffic **before** it reaches your backends, and to decide which service should handle each path. A forward proxy (client side) can also block/log *outgoing* requests, but the auth/rate-limit/routing that protects **your API** lives in the gateway.

#### Q: Do I always need all three?

No. A normal mobile app usually has **no forward proxy** at all (that's mostly corporate networks and VPNs). Many systems also **collapse the reverse proxy and API gateway into one** box, since the gateway already does reverse-proxy work (see §6). The three are a mental taxonomy, not a required checklist.

---

## 6. How They Fit Together

```
                🌐 Internet
                     |
             ┌────────────────┐
             │ Forward Proxy  │  (client side - optional)
             └────────────────┘
                     |
                📱 Client App
                     |
             ┌────────────────┐
             │  API Gateway   │  (auth, rate limit, routing)
             └────────────────┘
                     |
             ┌────────────────┐
             │ Reverse Proxy  │  (load balancer - Nginx)
             └────────────────┘
              /        |        \
   ┌────────────┐ ┌────────────┐ ┌────────────┐
   │ User Svc   │ │ Order Svc  │ │ Payment Svc│
   └────────────┘ └────────────┘ └────────────┘
         |               |               |
      ┌─────┐         ┌─────┐         ┌─────┐
      │ DB  │         │ DB  │         │ DB  │
      └─────┘         └─────┘         └─────┘
```

### Request flow

1. **Client → Forward Proxy (optional)** — corporate networks / VPNs; hides client IP, applies restrictions. *Usually absent for normal mobile apps.*
2. **API Gateway (main entry point)** — authentication (JWT), rate limiting, routing decision:
   ```
   /api/orders   → Order Service
   /api/payments → Payment Service
   ```
3. **Reverse Proxy (load balancer)** — distributes traffic across instances; SSL termination; failover.
4. **Backend services → databases** — business logic + DB operations.

> ⚠️ **Important nuance:** an API Gateway is basically a **supercharged reverse proxy**. Many systems skip a separate reverse proxy:
> ```
> Client → API Gateway → Services
> ```

> **Real-world (Netflix-like):** `User → ISP Proxy → API Gateway → Nginx → Microservices`

### Follow one request on its journey

Trace a single request — "show my orders" — through all the boxes, with each middleman's job:

```
📱 Client requests "My Orders"
   │
   │  (only if on a corporate/VPN network)
   ▼
Forward proxy — makes the outbound request for the client, hides its IP.
   │  Most home/mobile clients skip this entirely.
   ▼
API Gateway — the main entry point:
   │   1. Validates the token (JWT valid? else 401 rejected right here)
   │   2. Checks the rate limit (client not over its quota?)
   │   3. Reads the path /api/orders → route to the Order service
   ▼
Reverse proxy / load balancer — picks ONE healthy
   │   Order-service instance out of many, decrypts SSL, forwards inward.
   ▼
📦 Order Service → its DB → returns the orders
   │
   ▼
   response travels back out the same chain to the client
```

**In words:** the forward proxy (optional) makes the outbound request → the API gateway validates the token, enforces the rate limit, and decides which service the path maps to → the reverse proxy / load balancer picks a specific healthy instance of that service → the service does the actual work. On the way back, the response retraces the path to the client.

#### Q: The diagram shows gateway *then* reverse proxy — but a gateway *is* a reverse proxy. Isn't that double?

It can be, and that's fine — they play different roles here. The **gateway** focuses on API concerns (auth, limits, routing by path); the **reverse proxy** below it focuses on spreading load across many instances of one service and SSL termination. Plenty of real systems **merge them into one layer** (`Client → API Gateway → Services`), as the note above §7 points out. Showing both just makes each responsibility explicit.

#### Q: If the gateway already routes, what's left for the reverse proxy to route?

Different granularity. The **gateway** routes by **what's being requested** (`/api/orders` → the Order *service*). The **reverse proxy / load balancer** then routes among the **identical instances** of that service (Order instance #1 vs #2 vs #3) to balance load and skip unhealthy ones. Gateway = *which service*; load balancer = *which instance*.

---

## Common Mistakes

> ⚠️ **Two layers doing the *same* routing.** If your gateway already routes by path (`/api/orders` → Order service) and your load balancer *also* tries to make service-level routing decisions, you get two places that must agree — and they drift. Let the **gateway route by service** and the **load balancer route by instance** (see §6). Don't duplicate the same decision in both.

> ⚠️ **Terminating TLS at the gateway and forgetting to re-encrypt inward.** It's tempting to decrypt HTTPS once at the edge and forward plain HTTP to the backends. That's fine *inside* a trusted network, but if traffic crosses zones (different VPCs, a shared cluster, anything an attacker could sniff), you've created a plaintext hole behind your own front door. Re-encrypt gateway→backend (TLS or **mTLS**) whenever the internal hop isn't fully trusted.

> ⚠️ **Business logic creeping into the gateway.** The gateway is for *cross-cutting* concerns (auth, rate limiting, routing, shaping). The moment it starts computing prices, applying discounts, or knowing your order state machine, it becomes a second place to deploy for every feature change — a distributed monolith with a fancy name. Keep domain logic in the services; keep the gateway thin.

> 💡 **Rule of thumb:** if a change to one feature forces you to redeploy the gateway, logic has leaked into it.

---

## API Gateway vs Service Mesh

Both move and secure traffic, but they handle **different directions** of it:

- **API gateway** handles **north-south** traffic — requests coming *in from outside* (clients → your system) and responses going back out. It's a single **edge** entry point: auth, rate limiting, routing, aggregation.
- **Service mesh** handles **east-west** traffic — service-to-service calls *inside* your system (Order → Payment → Inventory). It runs as a **sidecar** proxy (e.g. **Envoy**) next to *every* service instance, transparently adding mTLS, retries, timeouts, and observability to internal calls.

| | API Gateway | Service Mesh |
| --- | --- | --- |
| Traffic direction | **North-south** (client ↔ system) | **East-west** (service ↔ service) |
| Deployment | One **edge** box | A **sidecar** next to every service |
| Typical tech | Kong, AWS API Gateway, Zuul | Istio/**Envoy**, Linkerd |
| Main jobs | Auth, rate limit, routing, aggregation | mTLS, retries, timeouts, traffic shifting, tracing |
| Who sees it | External clients | Only internal services |

> 💡 They're **complementary, not competitors.** A common setup: a gateway at the edge for external traffic, a mesh inside for service-to-service traffic. Small systems often need neither a mesh nor even a separate gateway.

---

## CDN vs Reverse Proxy vs API Gateway

These three sit "in front of your servers" and get conflated constantly. One row each:

| | Primary job | Lives where | Cares about |
| --- | --- | --- | --- |
| **CDN** | Serve cached **static/edge content** close to users | Globally distributed edge PoPs | Latency & offload (images, JS, video) |
| **Reverse proxy** | **Route + balance** traffic to backend instances | In front of your servers (one region) | Load balancing, TLS termination, hiding backends |
| **API gateway** | **Manage API traffic** (auth, limits, shaping) | At the edge of your API | Per-request policy for dynamic APIs |

> 💡 A request for a product image may hit the **CDN** and never touch your servers; a request for `/api/checkout` skips the CDN, passes the **API gateway** (auth + rate limit), then a **reverse proxy** picks a healthy instance. Same URL bar, three different front doors depending on *what* is being fetched.

---

## Edge Features Worth Knowing

A few gateway/edge capabilities that come up constantly:

- **WAF (Web Application Firewall) at the edge** — inspects incoming requests for attack patterns (SQL injection, XSS, malicious payloads) and blocks them *before* they reach the gateway logic or backends. It's a security filter bolted onto the front door, distinct from auth (which asks "who are you?") — the WAF asks "does this request look malicious?".
- **Canary / blue-green routing** — the gateway can split traffic by rule: send 1% of users to the new version (**canary**) and roll back instantly if error rates spike, or keep two identical environments and flip all traffic from **blue** (old) to **green** (new) at once. Because routing lives in one place, this needs no client changes.
- **mTLS gateway → services** — beyond terminating client TLS at the edge, the gateway re-establishes a *mutually* authenticated TLS connection to each backend, so both ends prove their identity. This is how you avoid the "plaintext behind the front door" pitfall on untrusted internal hops.
- **Request/response caching: gateway vs CDN** — a **CDN** caches mostly static, cacheable-by-URL content at global edge locations near users; a **gateway** can cache dynamic API responses (e.g. a hot `GET /api/products`) closer to your services, keyed by path + params + auth scope. Rough split: CDN for *static & geographic*, gateway for *dynamic & policy-aware*.

#### Q: What's the BFF (Backend-for-Frontend) pattern?

**Backend-for-Frontend** is a dedicated gateway/aggregation layer *per client type* — one for the mobile app, one for the web app, maybe one for a partner API. Each BFF calls the underlying services and stitches together exactly the shape *that* frontend needs (mobile wants a lean payload; web can take more). It's the aggregation idea from §4 taken one step further: instead of one generic gateway response, each frontend gets a tailored one, so client teams aren't blocked by a shared, lowest-common-denominator API.

---

## 7. Interview Cheat Sheet

> **"Difference between forward proxy, reverse proxy, API gateway?"**
>
> "A **forward proxy** sits between the client and the internet and acts on behalf of the **client**. A **reverse proxy** sits in front of servers and routes client requests to backend services. An **API gateway** is a specialized reverse proxy that adds authentication, rate limiting, and request routing for microservices."

> **"Show an architecture using all three."**
>
> "The client may optionally use a forward proxy. Requests go to the **API Gateway** (auth, rate limiting, routing), which forwards to a **reverse proxy / load balancer** that distributes traffic across backend service instances."

---

## 8. Final Takeaways

```
Forward Proxy → protects the client (outside your system)
Reverse Proxy → protects the server (internal load balancer)
API Gateway   → manages APIs (entry point, decision maker)
```

- **API Gateway = Reverse Proxy + brains** 🧠 (often replaces a separate reverse proxy).

**API-gateway feature checklist** (what "the brains" actually cover — reach for these in a design discussion):

- ✅ **Auth** — validate JWT/OAuth, reject unauthenticated requests at the edge.
- ✅ **Rate limiting** — cap per-client calls so one caller can't overload backends.
- ✅ **Routing** — map path/host to the right service (`/api/orders` → Order service).
- ✅ **Request/response transformation** — reshape payloads/headers to what each side expects.
- ✅ **Aggregation / BFF** — fan out to several services and stitch one response (tailored per frontend).
- ✅ **WAF** — filter malicious request patterns before they reach your logic.
- ✅ **Canary / blue-green** — shift a slice of traffic to a new version, roll back fast.

> 💡 In an interview, naming this checklist (and noting the gateway does them *once, at the edge*, so services stay thin) signals you understand *why* the pattern exists, not just what it is.
