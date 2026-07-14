# Load Balancing

> **Goal:** spread incoming traffic across multiple servers so no single one is overwhelmed — improving **scalability, availability, and latency**. The interview question is usually "L4 vs L7, which algorithm, and how do you handle a server dying?"

> **How to read this doc:** each section has the dense summary first, then a **deep dive** (annotated config/code and the exact confusions that trip people up). Skim the summaries for revision; read the deep dives to actually understand.

---

## Contents

- [1. Why Load Balance?](#1-why-load-balance)
- [2. L4 vs L7 Load Balancing](#2-l4-vs-l7-load-balancing)
- [The Full Request Path](#the-full-request-path)
- [3. Algorithms](#3-algorithms)
- [4. Health Checks & Failover](#4-health-checks--failover)
- [5. Session Stickiness](#5-session-stickiness)
- [6. Where LBs Sit & High Availability](#6-where-lbs-sit--high-availability)
- [Slow Start and Ramp-Up](#slow-start-and-ramp-up)
- [Cross-Zone, Circuit Breaking, and Long-Lived Connections](#cross-zone-circuit-breaking-and-long-lived-connections)
- [Common Mistakes](#common-mistakes)
- [7. Interview Cheat Sheet](#7-interview-cheat-sheet)
- [8. Final Takeaways](#8-final-takeaways)

---

## 1. Why Load Balance?

- **Scale horizontally** — add servers behind one entry point.
- **Availability** — route around dead/unhealthy servers.
- **Latency** — send users to the least-loaded / nearest server.
- **Zero-downtime deploys** — drain one server, deploy, re-add.

> Clients hit **one address (the LB / VIP)**; the LB forwards to a healthy backend.

### Why a single entry point

If clients picked backend servers themselves, traffic would clump unevenly — many clients might hit the same server while others sit idle — and clients would need to know every server's address. Instead you put **one entry point** in front: every request goes to the load balancer first, and it decides which backend handles it. That single decision point is the whole idea:

- **Scale horizontally** — under more load, add a server. The load balancer starts routing to it too. Clients never need to know how many servers exist.
- **Availability** — a server crashes? The load balancer stops routing to it. Clients don't notice.
- **Latency** — it can send a request to the least-loaded backend, or the nearest region, so it's served faster.
- **Zero-downtime deploys** — deploying new code? Stop sending a server *new* requests, let its in-flight ones finish, then swap it back in. No request is interrupted.

The key win: clients only need to know **one address** (the VIP). They never track individual backend addresses. You can add, remove, or replace backends freely behind that one entry point.

#### Q: Isn't the load balancer now the bottleneck / single point of failure?

Yes — if there's only one load balancer and it fails, nothing gets routed. That's why real setups run **two or more load balancers** sharing one VIP, so if one drops, another keeps routing. Covered in §6 (High Availability).

---

## 2. L4 vs L7 Load Balancing

| | **L4 (transport)** | **L7 (application)** |
| --- | --- | --- |
| Operates on | TCP/UDP (IP + port) | HTTP/HTTPS (URLs, headers, cookies) |
| Decisions by | Connection tuples | Path, host, headers, cookies |
| Smarts | Fast, low overhead, protocol-agnostic | Content-based routing, TLS termination, rewrites |
| Examples | AWS NLB, LVS | AWS ALB, NGINX, Envoy, HAProxy(L7) |
| Use when | Raw throughput, non-HTTP | Microservice routing, `/api/*` → service A |

> **L7** can route `/images` to one pool and `/api` to another, terminate TLS, and do sticky sessions by cookie. **L4** is faster and simpler but blind to content.

### L4 vs L7: how much of the request the LB reads

The difference between L4 and L7 is **how much of the request the load balancer inspects before routing.**

**L4 = fast, doesn't read the request body.** It sees only the connection-level info: source/destination IP address and port. It doesn't look at the application payload. Very fast, works for *any* protocol (not just HTTP — also databases, game traffic, raw TCP/UDP), but it can't make content-based decisions because it never inspects the request.

**L7 = slower, reads the application request.** It parses the *contents* of the request — the HTTP URL, headers, cookies. With that, it can:

- Send `/images` requests to one pool and `/api` requests to another (**content-based routing**).
- Decrypt HTTPS once at the LB so backends don't have to (**TLS termination**).
- Route a given client back to the same backend via a cookie (**sticky sessions**).

```
L4:  sees  ──►  [ source IP:port, dest IP:port ]        → "backend 4"        (fast, blind to content)
L7:  sees  ──►  GET /api/orders  Host: shop.com
                Cookie: session=abc  Authorization: ...  → "API backend pool" (smart, reads request)
```

#### Q: When do I pick L4 vs L7?

- **Pick L7** when traffic is HTTP/HTTPS and you want smart routing: microservices (`/api/*` → service A, `/images/*` → service B), TLS termination, cookie stickiness, header-based rules. This is the common choice for web apps.
- **Pick L4** when you need raw speed or the traffic **isn't** HTTP: databases, game servers, gRPC/TCP streams, or extreme throughput where you don't want the LB decrypting and parsing every request.

#### Q: If L7 is smarter, why not always use it?

Reading and parsing every request costs time and CPU — an L7 LB must decrypt and inspect the full request before routing, while L4 only looks at the connection tuple (IP + port). At massive scale, or for non-HTTP traffic, that L4 speed and simplicity wins. Many big systems even chain them: a fast **L4** in front spreads raw connections, then **L7** behind it does the smart per-request routing.

---

## The Full Request Path

Before diving into algorithms and health checks, it helps to see the **whole path** a request travels. Most real systems chain several balancing layers, each doing a different job:

```
Client
  │   1. resolve name → IP of the nearest healthy region
  ▼
DNS / GeoDNS ──────────────────────────────────────────────
  │   picks the REGION (no connection yet — just an address)
  ▼
L4 load balancer   (e.g. AWS NLB, LVS) ────────────────────
  │   spreads raw TCP/UDP connections — fast, content-blind
  ▼
L7 load balancer   (e.g. ALB, NGINX, Envoy) ───────────────
  │   reads HTTP: routes /api → svc-A, /images → svc-B,
  │   terminates TLS, applies stickiness
  ▼
Backend server     (one healthy instance from the target pool)
```

- **DNS / GeoDNS** chooses the *region* — it hands back an address, no connection is made yet.
- **L4** spreads raw connections cheaply and can front many L7s.
- **L7** makes the smart per-request decision (path / host / header routing, TLS, cookies).
- The **backend** finally serves the request.

Not every system has all four layers — a small app might be just `DNS → one L7 → backends`. But knowing the full chain lets you say *which* layer does *what*, and where each decision is made.

> 💡 **tip** Each hop should be independently health-checked and redundant. A balancing layer with no failover doesn't remove the single point of failure — it just relocates it (see §6).

---

## 3. Algorithms

| Algorithm | How | Best for |
| --- | --- | --- |
| **Round Robin** | Rotate through servers in order | Uniform servers/requests |
| **Weighted Round Robin** | More traffic to bigger servers | Heterogeneous capacity |
| **Least Connections** | Pick server with fewest active conns | Long-lived / uneven request cost |
| **Least Response Time** | Fewest conns + lowest latency | Latency-sensitive |
| **IP Hash / Consistent Hash** | Hash client (or key) → server | **Session affinity**, cache locality |
| **Random (+ two choices)** | Pick 2 random, choose less loaded | Simple, near-optimal at scale |

> **Consistent hashing** (see its own note) minimizes remapping when servers are added/removed — important for cache/stateful backends.

### How the LB picks a server

The "algorithm" is just the **rule the load balancer uses to pick a backend.** Here are the common ones.

**Round Robin — cycle through servers in order.** Server 1, then 2, then 3, then back to 1. Dead simple, fair when all servers and all requests are roughly equal.

```java
// Round Robin: keep a counter, hand out servers in a rotating cycle
class RoundRobin {
    List<String> servers = List.of("server-A", "server-B", "server-C");
    AtomicInteger next = new AtomicInteger(0);

    String pick() {
        int i = next.getAndIncrement() % servers.size();  // 0,1,2,0,1,2,...
        return servers.get(i);                            // just cycle in order
    }
}
```

**Weighted Round Robin — bigger servers get more requests.** A more powerful server can handle more load, so give it more turns in the rotation. Use when servers have unequal capacity (a large box vs a small one).

```java
// Weighted: a strong server appears MORE times in the rotation list
// weights A=1, B=1, C=2  →  C gets picked twice as often
List<String> rotation = List.of("A", "B", "C", "C");
```

**Least Connections — send the next request to the server with the fewest active connections right now.** This is smarter than round robin when some requests take far longer than others (a quick lookup vs a long-running stream). Round robin would keep piling requests on a server stuck with slow ones; least-connections notices it's busy and skips it.

```java
// Least Connections: pick the server currently handling the fewest active requests
String pick(Map<String, Integer> activeConns) {   // e.g. {A:5, B:2, C:9}
    return activeConns.entrySet().stream()
        .min(Map.Entry.comparingByValue())         // fewest active → least busy
        .get().getKey();                           // → "B"
}
```

**IP Hash / Consistent Hash — the same client always maps to the same server.** The LB hashes *who the client is* (its IP, or some key) into a fixed answer, so the client lands on the same server every time. Useful when that server holds the client's session state or has its data cached.

```java
// IP Hash: same client → same server, deterministically (no memory needed)
String pick(String clientIp, List<String> servers) {
    int idx = Math.floorMod(clientIp.hashCode(), servers.size());
    return servers.get(idx);   // "49.x.x.x" ALWAYS maps to the same server
}
```

The catch with plain hashing: if a server is removed, `hashCode % N` changes for *almost every* client (N shrank), so nearly all clients get remapped to a different server — cache/session cold everywhere. **Consistent hashing** fixes this: when one server leaves, only *its* clients move; everyone else stays put. (Full details in the [Consistent Hashing](consistent-hashing.md) note.)

**Random + "two choices" — pick two servers at random, route to the less loaded one.** Surprisingly close to optimal and dirt cheap, because it avoids every request rushing to the single least-loaded server at once.

**Least Response Time — fewest active connections *and* lowest recent latency.** Least Connections only counts *how many* requests a server is handling, not how *fast*. Least Response Time also factors in each backend's recent response time, so a server with few connections that's responding slowly (GC pause, hot disk, a noisy neighbour) gets less traffic than its connection count alone would suggest. Best when latency matters and backends aren't perfectly uniform.

**Weighted Least Connections — least connections, scaled by capacity.** Plain Least Connections assumes every server is equally powerful. The weighted variant divides each server's active connections by its weight (capacity), so a box twice as large can hold twice as many connections before it counts as "as busy" as a smaller one. It combines the "uneven request cost" logic of Least Connections with the "uneven server size" logic of Weighted Round Robin.

> 💡 **tip** The "smart" algorithms (Least Response Time especially) need the LB to track live per-backend metrics. That bookkeeping is cheap on one LB, but when you run an HA pair/cluster each LB only knows the connections *it* routed — so two LBs can both think a backend is idle and pile on. Round robin has no such coordination problem.

#### Q: Round Robin vs Least Connections — which is the default?

- **Round Robin** if requests are short and uniform and servers are identical (each request takes about the same time). Simple, no bookkeeping.
- **Least Connections** if request durations vary a lot or connections are long-lived (websockets, streaming, big uploads). It reacts to who's *actually* busy instead of blindly rotating.

#### Q: When would I reach for consistent hashing?

When "the same client/key must keep hitting the same server" matters — caches (so the cached data is on the server you land on) or stateful/sticky backends — **and** you add/remove servers often enough that you can't afford a full reshuffle each time.

---

## 4. Health Checks & Failover

```
LB periodically probes each backend (e.g. GET /health every few sec)
  healthy   → keep in rotation
  failing N times → mark DOWN, stop routing to it
  recovered → add back
```

- **Active checks** (LB probes) + **passive checks** (observe real request failures).
- **Failover:** dead backend removed automatically → no user impact if capacity remains.
- **Connection draining:** on deploy, stop new conns to a server but let in-flight requests finish.

### How the LB detects and handles dead backends

A backend can fail at any moment — crash, hang, or get stuck. If the LB keeps routing requests to a dead server, those requests just time out. So the LB **constantly checks** that each backend is healthy.

**Active check = the LB probes each backend every few seconds.** It sends a request to `/health`; a healthy backend replies HTTP 200. Miss a few probes in a row → mark it down, stop routing to it.

**Passive check = the LB also watches real traffic.** Even if a backend passes its health probe, if real requests to it keep failing, the LB notices those failures and pulls it anyway.

```
# Example health-check config (NGINX / typical LB)
health_check:
  path:      /health          # the endpoint the LB probes
  interval:  5s               # probe every 5 seconds
  timeout:   2s               # a reply must come back within 2s
  unhealthy_threshold: 3      # 3 misses in a row → mark DOWN, stop routing
  healthy_threshold:   2      # 2 good replies → add back into rotation
```

Why a **threshold** (3 misses) instead of reacting to one? A single missed probe might just be a momentary blip — you don't want to yank a healthy backend over one hiccup (flapping). Requiring several misses in a row means "genuinely down," not "momentary hiccup."

> ⚠️ **pitfall** A **shallow** `/health` that returns 200 whenever the process is running keeps a backend in rotation even when its DB pool is exhausted or a downstream is unreachable — every routed request then fails. Make critical checks **deep** (probe real dependencies), but keep them cheap and cache the result briefly so the probe itself doesn't hammer the DB.

> 💡 **tip** Tune thresholds to avoid **flapping**. Too sensitive (1 miss → DOWN) yanks healthy backends on a blip; too lax (30 misses) keeps feeding a dead one. A few consecutive misses on a short interval is the usual balance.

**Connection draining = don't cut off in-flight requests.** When you retire a backend (deploy/restart), you don't drop its current connections. You **stop routing new** requests to it, let its in-flight requests finish, *then* take it out. In-flight requests complete; no request is interrupted.

```
Normal:        LB → routes new requests to server-C, server-C serving 8 connections
Deploy starts: LB → STOPS new requests to server-C  (draining)
               server-C → finishes its 8 in-flight connections
All finished:  server-C → removed safely, upgraded, added back
```

#### Q: What actually is `/health` — does it prove the server really works?

It's a lightweight endpoint the app exposes that returns 200 when healthy. A **shallow** check just says "the process is up." A **deep** check also verifies critical dependencies (can it reach the DB / cache?) before answering 200 — so a server that's running but can't talk to its database gets pulled instead of accepting doomed requests. Keep it cheap, though; it runs constantly across every backend.

#### Q: Active vs passive — why have both?

Active checks catch a server that's **totally down** even during quiet periods (no real traffic to observe). Passive checks catch a server that **passes its health ping but still fails real requests** (e.g. one specific endpoint is broken). Together they cover both "dead" and "lying about being healthy."

---

## 5. Session Stickiness

If a server holds session state, subsequent requests from a user should return to it.

| Approach | Note |
| --- | --- |
| **Cookie-based (L7)** | LB sets a cookie → routes to same backend |
| **IP hash** | Same client IP → same server (breaks behind NAT/mobile) |
| **Better: stateless servers** | Store session in Redis/JWT → **no stickiness needed** ✅ |

> Prefer **stateless** app servers (session in a shared store) so any server can handle any request — stickiness is a crutch.

### Why stickiness exists (and why to avoid it)

If a server stores a user's session in its own local memory, then a later request routed to a *different* server won't find that session — the user is effectively logged out. **Sticky sessions** = the LB always routing a given user back to the same server so its local session stays valid.

Ways to make it sticky:

- **Cookie-based (L7):** the LB sets a cookie identifying the chosen backend (e.g. `server-B`); on each request it reads the cookie and routes to that backend. Reliable, works even behind shared addresses (NAT).
- **IP hash:** the LB hashes the client IP and always routes that IP to the same backend. Simple, but **breaks behind NAT/mobile** — many clients can share one IP (corporate office, mobile carrier), so they'd all land on one backend; and a client's IP can *change* (switching WiFi→cellular), sending it to a different backend and losing its session.

**The better fix — don't keep session state on the server at all.** Store every session in a **shared store** (like Redis) or a self-contained JWT the client carries. Now *any* server can read the session, so it doesn't matter which one handles a request — no stickiness needed. This is why "stickiness is a crutch": it only exists to work around servers hoarding local state.

```
Sticky (fragile):     client → MUST return to server-B (only B has the session)
Stateless (robust):   client → ANY server works; all read the session from shared Redis / the JWT
```

> 💡 **tip** **Prefer stateless over sticky sessions.** Keep app servers stateless (session in Redis / a JWT) so any server can serve any request. Stickiness only exists to paper over servers hoarding local state — avoid it for anything new.

> ⚠️ **pitfall** **Sticky sessions + rolling deploy = mass logouts.** A rolling deploy restarts backends one by one; every user pinned to a restarting server loses their local session. With a shared session store it's a non-event. If you *must* be sticky, drain connections and externalize the session first.

#### Q: If stateless is better, why does stickiness still exist?

Some apps hold in-memory session state that's hard to externalize (legacy apps, big in-memory caches, long-lived websocket connections tied to one node). Stickiness is the pragmatic bridge. But for anything new, prefer stateless servers + a shared session store so you can add/remove/restart servers freely.

#### Q: What breaks if I use sticky sessions and a server dies?

Every client pinned to that server loses its session (it went down with the server) — they get logged out / lose cart, etc. With a **shared store**, a dead server is a non-event: the user's next request just goes to another server that reads the same session. That resilience is the real reason to go stateless.

---

## 6. Where LBs Sit & High Availability

```
DNS (GeoDNS / round-robin) → Global LB → Regional LB → [ backend pool ]
                                    │
                        (L7 for HTTP routing, L4 for raw TCP)
```

- **The LB itself must not be a SPOF** — run in an **active-passive or active-active pair** with a floating/virtual IP (VIP) + health-checked failover.
- **GSLB / GeoDNS** routes users to the nearest region; regional LBs spread within.
- **Anycast** IPs route to the closest edge (used by CDNs/DNS).

### Making the LB itself highly available (and routing by region)

Adding a load balancer fixed the "no single entry point" problem, but now the **LB itself** is the single point of failure — if the only LB dies, nothing gets routed, no matter how many backends are healthy. So the LB layer must be redundant, and the layers stack up like this:

```
Client → nearest region chosen → that region's LB → a backend
   DNS/GeoDNS       Global LB        Regional LB       backend
```

**Two LBs sharing one VIP.** Run **two** load balancers sharing a single **VIP (virtual IP)** — the one address clients connect to. If the active LB fails, the standby LB **takes over the VIP** (it "floats" to the standby) and keeps routing. Clients still connect to the same address; they never notice the swap.

- **Active-passive:** one LB serves traffic, the other stands by ready to take the VIP.
- **Active-active:** both LBs serve traffic at once, and if one drops, the other absorbs the load.

**Routing to the nearest *region* first (GeoDNS / GSLB).** With deployments in multiple regions, DNS resolves each client to the **nearest region** before any connection is made. That's **GeoDNS / global server load balancing**: route each user to the closest *region*, then the regional LB spreads them across that region's backends.

**Anycast — same address, nearest location answers.** One advertised IP, but the network automatically routes each client to the physically closest location sharing it. Used by CDNs and DNS so you always hit a nearby edge.

#### Q: Wait — is the LB one machine or many? This is confusing.

Think in layers, outermost first:

1. **DNS / GeoDNS** points you at the nearest region (not a machine — it's the "which region" decision).
2. A **redundant pair (or cluster) of LBs** in that region shares a **VIP**; that's the entry point. One address, but ≥2 machines behind it for failover.
3. Those LBs spread requests across the **backend server pool**.

So "the load balancer" is usually a *highly-available cluster*, not a lone box — otherwise it'd just move the single-point-of-failure problem up one level.

#### Q: How does the standby actually take over the VIP?

The two LBs health-check each other (protocols like VRRP / keepalived). When the standby stops hearing heartbeats from the active one, it **claims the VIP** — it starts answering for that address. Because clients only ever talk to the VIP, the swap is invisible to them; in-flight users just continue against the same address.

---

## Slow Start and Ramp-Up

When a fresh backend joins the pool (after a deploy, an autoscale event, or a recovery), it usually **isn't ready for its full share of traffic yet**:

- **Cold caches** — its in-memory / local caches are empty, so early requests miss and fall through to the DB, making them slow.
- **Cold runtime** — JITs (JVM, etc.) are slow until hot paths compile; connection pools and lazy singletons aren't initialized yet.
- **Thundering herd on join** — a "least connections" LB sees the new server at **zero** connections and floods it with the next burst, overwhelming a node that isn't warm.

**Slow start** fixes this: the LB ramps the new backend's weight up **gradually** (e.g. 0 → 100% over 30–60s) so it warms caches, pools, and hot paths under light load before taking a full share.

> ⚠️ **pitfall** Without slow start, autoscaling can make latency *worse* exactly when you add capacity: each new node gets slammed cold, spikes latency/errors, and can even fail its health checks and get pulled — a flap loop. Ramp-up plus a warm-up delay before the node is marked healthy avoids it.

---

## Cross-Zone, Circuit Breaking, and Long-Lived Connections

A few real-world concerns that surface once the basics are in place.

**Cross-zone / cross-AZ balancing — a latency and cost trade-off.** In a multi-AZ deployment, an LB node in AZ-A can route to backends in AZ-B ("cross-zone"). Enabling it spreads load *evenly* across all backends regardless of zone, but every cross-zone hop adds a little latency and, on some clouds, **inter-AZ data-transfer cost**. Keeping traffic zone-local is cheaper and faster but risks imbalance when zones have unequal backend counts. Usual answer: balance zone-locally when zones are evenly sized, enable cross-zone when they aren't (or to survive a whole zone draining).

**Circuit breaking and retry storms.** When a backend starts failing, naive **retries multiply load** — each client retry adds traffic to an already-struggling pool, causing more failures, triggering more retries: a **retry storm** that turns a small blip into an outage. Defenses at the LB / client:

- **Circuit breaker** — after N failures to a backend, stop sending it requests for a cooldown, then probe cautiously before restoring it.
- **Retry budgets / caps** — allow retries only up to a small % of traffic, never unbounded.
- **Backoff + jitter** — spread retries out in time instead of synchronized hammering.

> ⚠️ **pitfall** Retries and health checks interact badly: aggressive retries keep a dying backend (and its dependencies) *looking* busy long enough to drag down healthy peers. Cap retries and let the circuit breaker shed load.

**gRPC / WebSocket — long-lived connections need connection-aware balancing.** Plain L4 balances *connections*, but gRPC/HTTP-2 and WebSockets hold **one long-lived connection** that carries many requests. An L4 LB pins that whole connection to a single backend, so a client that opens one connection and streams thousands of gRPC calls loads exactly one backend — and new backends added later get **no** traffic from existing connections. You need **L7 / connection-aware** balancing that spreads *per request or stream* (an L7 that understands HTTP-2 multiplexing, or client-side / service-mesh balancing) so calls fan out across backends even over a persistent connection.

---

## Common Mistakes

- **Shallow health checks.** `/health` returns 200 whenever the process is up, so a backend that can't reach its DB/cache stays in rotation and swallows requests doomed to fail. Make critical checks deep (but cheap).
- **Sticky sessions across a rolling deploy.** Restarting pinned backends logs users out. Externalize session state (Redis/JWT) so any server can take over.
- **Health-check flapping with no thresholds.** Reacting to a single missed probe pulls healthy backends on a momentary blip and thrashes the pool. Require several consecutive misses (and a couple of good replies to re-add).
- **Health checks that don't hit real dependencies.** A probe that only pings the web layer misses what actually breaks (DB, downstream service). If the request path depends on it, the check should touch it.
- **No slow start.** New / autoscaled backends get full traffic cold, spike latency, and can flap. Ramp them up.
- **Unbounded retries.** Retrying failures without caps or backoff turns a blip into a retry storm. Use circuit breakers + retry budgets.

---

## 7. Interview Cheat Sheet

> **"L4 vs L7?"**
> "L4 balances on TCP/UDP (IP+port) — fast, protocol-agnostic. L7 understands HTTP — routes by path/host/header, terminates TLS, does cookie stickiness. Use L7 for microservice/content routing, L4 for raw throughput."

> **"Which algorithm?"**
> "Round robin for uniform servers, **least connections** for uneven/long-lived requests, **consistent hashing** for session affinity or cache locality, weighted for mixed capacity."

> **"How do you handle a server dying?"**
> "Health checks (active probes + passive failure observation) mark it DOWN and stop routing; capacity absorbs the rest. On deploys, connection-drain first. The LB itself is HA (active-active pair + VIP failover)."

> **"How do you avoid the LB being a SPOF?"**
> "Redundant LBs with a floating VIP and health-checked failover; GeoDNS/anycast in front for multi-region."

---

## 8. Final Takeaways

- LB = one entry point spreading traffic → **scale + availability + latency**.
- **L4** (fast, TCP/UDP) vs **L7** (smart, HTTP path/header/cookie routing + TLS).
- Algorithms: round robin, **least connections**, **consistent hashing** (affinity), weighted.
- **Health checks + failover + connection draining** keep it seamless.
- Prefer **stateless servers** (shared session store) over sticky sessions.
- Make the **LB itself HA** (VIP pair); GeoDNS/anycast for global.

### Related notes

- [Consistent Hashing](consistent-hashing.md) · [Proxies & API Gateway](proxies-and-api-gateway.md) · [Scaling Architecture](scaling-architecture.md)
