# Load Balancing

> **Goal:** spread incoming traffic across multiple servers so no single one is overwhelmed — improving **scalability, availability, and latency**. The interview question is usually "L4 vs L7, which algorithm, and how do you handle a server dying?"

> **How to read this doc:** each section has the dense summary first, then a **Plain-English** deep dive (a running restaurant analogy — a host seating diners across waiters — plus simple annotated config/code and the exact confusions that trip people up). Skim the summaries for revision; read the Plain-English parts to actually understand.

---

## Contents

- [1. Why Load Balance?](#1-why-load-balance)
- [2. L4 vs L7 Load Balancing](#2-l4-vs-l7-load-balancing)
- [3. Algorithms](#3-algorithms)
- [4. Health Checks & Failover](#4-health-checks--failover)
- [5. Session Stickiness](#5-session-stickiness)
- [6. Where LBs Sit & High Availability](#6-where-lbs-sit--high-availability)
- [7. Interview Cheat Sheet](#7-interview-cheat-sheet)
- [8. Final Takeaways](#8-final-takeaways)

---

## 1. Why Load Balance?

- **Scale horizontally** — add servers behind one entry point.
- **Availability** — route around dead/unhealthy servers.
- **Latency** — send users to the least-loaded / nearest server.
- **Zero-downtime deploys** — drain one server, deploy, re-add.

> Clients hit **one address (the LB / VIP)**; the LB forwards to a healthy backend.

### Plain-English: the host at a busy restaurant

**Analogy used throughout this doc: a load balancer is the host (maître d') at the door of a busy restaurant.**

- **Diners walking in** = incoming requests (users).
- **Waiters** = your backend servers.
- **The host at the door** = the load balancer.

Imagine diners just wandered in and picked their own waiter. Everyone would swarm the one friendly waiter near the entrance — he'd be buried in orders while three other waiters stand idle. Chaos, slow food, angry diners.

So you put **one host at the door**. Every diner talks to the host first, and the host decides which waiter takes them. That single decision point is the whole idea:

- **Scale horizontally** — busy night? Hire another waiter (add a server). The host just starts seating diners with them too. Diners never need to know how many waiters exist.
- **Availability** — a waiter goes home sick (server crashes)? The host simply stops seating people with them. Diners don't notice.
- **Latency** — the host can send you to the waiter with the fewest tables (least-loaded) or the one nearest your seat (nearest region), so you get served faster.
- **Zero-downtime deploys** — need to retrain a waiter (deploy new code)? Stop giving them *new* tables, let them finish their current ones, then swap them back in. Nobody's meal gets interrupted.

The key win: diners remember **one thing — "ask the host"** (one address / the VIP). They never memorize waiter names. You can hire, fire, or retrain waiters freely behind that one front door.

#### Q: Isn't the host now the bottleneck / single point of failure?

Great instinct — yes, if there's only one host and they faint, nobody gets seated. That's why real setups run **two or more hosts** sharing one podium (the VIP), so if one drops, the other keeps seating. Covered in §6 (High Availability).

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

### Plain-English: two kinds of host — the fast one and the smart one

Back to the restaurant. There are two styles of host, and the difference is **how much they're allowed to look at before seating you.**

**L4 host = fast but doesn't read your request.** This host only sees *where you came from* — basically your car's license plate and which door you walked through (your IP address + port). They don't ask what you want to eat. They just point: "table 4, go." Super fast, works for *any* kind of visitor (dinner, takeaway, a delivery driver — any protocol, not just HTTP), but they can't make clever choices because they never looked at your order.

**L7 host = slower but reads your actual request.** This host reads the *contents* of what you're asking for — "Are you here for the sushi bar or the pizza counter? Do you have a loyalty cookie? Reservation under a name?" (i.e. the HTTP URL, headers, cookies). With that, they can do smart things:

- Send `/images` requests to the photo-serving waiters and `/api` requests to the API waiters (**content-based routing**).
- Handle your coat/security check at the door so waiters don't have to (**TLS termination** — decrypt HTTPS once at the LB).
- Remember you and always send you back to "your" waiter via a cookie (**sticky sessions**).

```
L4:  sees  ──►  [ source IP:port, dest IP:port ]        → "table 4"          (fast, blind to content)
L7:  sees  ──►  GET /api/orders  Host: shop.com
                Cookie: session=abc  Authorization: ...  → "API waiter pool" (smart, reads request)
```

#### Q: When do I pick L4 vs L7?

- **Pick L7** when traffic is HTTP/HTTPS and you want smart routing: microservices (`/api/*` → service A, `/images/*` → service B), TLS termination, cookie stickiness, header-based rules. This is the common choice for web apps.
- **Pick L4** when you need raw speed or the traffic **isn't** HTTP: databases, game servers, gRPC/TCP streams, or extreme throughput where you don't want the LB decrypting and parsing every request.

#### Q: If L7 is smarter, why not always use it?

Reading and parsing every request costs time and CPU — the L7 host has to open and read your whole order before seating you. L4 just glances at the license plate. At massive scale, or for non-HTTP traffic, that L4 speed and simplicity wins. Many big systems even chain them: a fast **L4** in front spreads raw connections, then **L7** behind it does the smart per-request routing.

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

### Plain-English: how the host decides which waiter gets you

The "algorithm" is just the **rule the host uses to pick a waiter.** Here are the common rules, in restaurant terms.

**Round Robin — go around the table, one by one.** Waiter 1, then 2, then 3, then back to 1. Dead simple, fair when all waiters and all diners are roughly equal.

```java
// Round Robin: keep a counter, hand out servers in a rotating cycle
class RoundRobin {
    List<String> servers = List.of("waiter-A", "waiter-B", "waiter-C");
    AtomicInteger next = new AtomicInteger(0);

    String pick() {
        int i = next.getAndIncrement() % servers.size();  // 0,1,2,0,1,2,...
        return servers.get(i);                            // just cycle in order
    }
}
```

**Weighted Round Robin — big waiters get more tables.** One waiter is a seasoned pro who can handle double the tables; give them twice the turns in the rotation. Use when servers have unequal capacity (a beefy box vs a small one).

```java
// Weighted: a strong server appears MORE times in the rotation list
// weights A=1, B=1, C=2  →  C gets picked twice as often
List<String> rotation = List.of("A", "B", "C", "C");
```

**Least Connections — send the next diner to the waiter with the fewest tables right now.** This is smarter than round robin when some meals take way longer than others (a quick coffee vs a 3-hour tasting menu). Round robin would keep piling tables on a waiter stuck with slow diners; least-connections notices they're busy and skips them.

```java
// Least Connections: pick the server currently handling the fewest active requests
String pick(Map<String, Integer> activeConns) {   // e.g. {A:5, B:2, C:9}
    return activeConns.entrySet().stream()
        .min(Map.Entry.comparingByValue())         // fewest active → least busy
        .get().getKey();                           // → "B"
}
```

**IP Hash / Consistent Hash — the same diner always gets the same waiter.** The host looks at *who you are* (your IP, or some key) and computes a fixed answer, so you land on the same waiter every visit. Great when the waiter remembers something about you (session state) or has your data cached.

```java
// IP Hash: same client → same server, deterministically (no memory needed)
String pick(String clientIp, List<String> servers) {
    int idx = Math.floorMod(clientIp.hashCode(), servers.size());
    return servers.get(idx);   // "49.x.x.x" ALWAYS maps to the same waiter
}
```

The catch with plain hashing: if a waiter quits, `hashCode % N` changes for *almost everyone* (N shrank), so nearly every diner gets reshuffled to a new waiter — cache/session cold everywhere. **Consistent hashing** fixes this: when one waiter leaves, only *their* diners move; everyone else stays put. (Full details in the [Consistent Hashing](consistent-hashing.md) note.)

**Random + "two choices" — pick two waiters at random, seat with the less busy one.** Surprisingly close to optimal and dirt cheap, because you avoid the herd all rushing to the single least-loaded waiter at once.

#### Q: Round Robin vs Least Connections — which is the default?

- **Round Robin** if requests are short and uniform and servers are identical (each table takes about the same time). Simple, no bookkeeping.
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

### Plain-English: the host keeps checking if each waiter is still standing

A waiter can collapse at any moment — trip in the kitchen, walk out mid-shift, freeze up. If the host keeps seating diners with a waiter who's face-down on the floor, those diners just wait forever. So the host **constantly checks** that each waiter is alive and okay.

**Active check = the host taps each waiter on the shoulder every few seconds.** "You good?" A healthy waiter says "yep" (HTTP 200 from `/health`). Miss a few taps in a row → assume they're down, stop seating them.

**Passive check = the host also watches real service.** Even if a waiter *says* they're fine, if diners at their tables keep complaining "my order failed," the host notices the real failures and pulls them anyway.

```
# Example health-check config (NGINX / typical LB)
health_check:
  path:      /health          # the "you good?" endpoint the LB hits
  interval:  5s               # tap every 5 seconds
  timeout:   2s               # a reply must come back within 2s
  unhealthy_threshold: 3      # 3 misses in a row → mark DOWN, stop routing
  healthy_threshold:   2      # 2 good replies → welcome back into rotation
```

Why a **threshold** (3 misses) instead of reacting to one? A single missed tap might just be the waiter briefly turning around — you don't want to yank a healthy waiter over one blip (flapping). Requiring several misses in a row means "genuinely down," not "momentary hiccup."

**Connection draining = don't cut a waiter off mid-meal.** When you retire a waiter (deploy/restart), you don't grab plates out of diners' hands. You **stop seating new** diners with them, let their current tables finish eating, *then* send them home. In-flight requests complete; nobody's meal is interrupted.

```
Normal:        host → seats new diners with waiter-C, waiter-C serving 8 tables
Deploy starts: host → STOPS new diners to waiter-C  (draining)
               waiter-C → finishes its 8 in-flight tables
All finished:  waiter-C → removed safely, upgraded, added back
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

### Plain-English: "please send me back to my waiter"

Imagine a waiter jots your order on **their own personal notepad** — "table 4 wants no onions, allergic to nuts." If the host sends you to a *different* waiter next time, that new waiter has no idea about your notes; your order is lost. **Sticky sessions** = the host promising "I'll always send you back to *your* waiter" so your notepad stays valid.

Ways to make it sticky:

- **Cookie-based (L7):** the host slips you a coat-check ticket (a cookie) that says "waiter-B." Every visit you show the ticket, the host sends you to waiter-B. Reliable, works even behind shared addresses.
- **IP hash:** the host recognizes you by your license plate (IP) and always routes that plate to the same waiter. Simple, but **breaks behind NAT/mobile** — many diners can share one plate (corporate office, mobile carrier), so they'd all pile onto one waiter; and your plate can *change* (switching WiFi→cellular), sending you to a different waiter and losing your notes.

**The better fix — don't let waiters keep private notepads.** Put every diner's notes on a **shared whiteboard in the kitchen** (a shared session store like Redis, or a self-contained JWT the diner carries). Now *any* waiter can read your notes, so it doesn't matter who serves you — no stickiness needed. This is why "stickiness is a crutch": it only exists to work around servers hoarding state.

```
Sticky (fragile):     you → MUST return to waiter-B (only B has your notepad)
Stateless (robust):   you → ANY waiter works; all read notes from shared Redis / your JWT
```

#### Q: If stateless is better, why does stickiness still exist?

Some apps hold in-memory session state that's hard to externalize (legacy apps, big in-memory caches, long-lived websocket connections tied to one node). Stickiness is the pragmatic bridge. But for anything new, prefer stateless servers + a shared session store so you can add/remove/restart servers freely.

#### Q: What breaks if I use sticky sessions and a server dies?

Everyone stuck to that waiter loses their session (their notepad went down with them) — they get logged out / lose cart, etc. With a **shared store**, a dead server is a non-event: the user's next request just goes to another server that reads the same session. That resilience is the real reason to go stateless.

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

### Plain-English: who watches the host? (and picking the right city)

We solved "one waiter is a bottleneck" by adding a host. But now the **host** is the single point of failure — if the only host faints, the whole restaurant seizes up, no matter how many waiters are free. So the host role itself must be redundant, and the layers stack up like this:

```
You (diner) → GPS picks the nearest branch → that branch's front-door host → a waiter
   DNS/GeoDNS         Global LB                    Regional LB              backend
```

**Two hosts sharing one podium (VIP).** Run **two** hosts. They share a single sign at the door — the **VIP (virtual IP)** — the one address diners actually walk up to. If the active host faints, the standby host **grabs the same podium/sign** (the VIP "floats" over to it) and keeps seating people. Diners still walked up to the same door; they never knew a host was swapped.

- **Active-passive:** one host works, the other stands by ready to take the podium.
- **Active-active:** both hosts seat diners at once, and if one drops, the other absorbs the load.

**Picking the right *city* first (GeoDNS / GSLB).** A restaurant chain has branches in many cities. Before you even reach a door, your phone's GPS sends you to the **nearest branch** — no point driving cross-country. That's **GeoDNS / global server load balancing**: route each user to the closest *region*, then the regional host spreads them across that branch's waiters.

**Anycast — "same address, nearest building answers."** One advertised address, but the network automatically routes you to the physically closest location sharing it. Used by CDNs and DNS so you always hit a nearby edge.

#### Q: Wait — is the LB one machine or many? This is confusing.

Think in layers, outermost first:

1. **DNS / GeoDNS** points you at the nearest region (not a machine — it's the "which city" decision).
2. A **redundant pair (or cluster) of LBs** in that region shares a **VIP**; that's the front door. One address, but ≥2 machines behind it for failover.
3. Those LBs spread requests across the **backend server pool** (the waiters).

So "the load balancer" is usually a *highly-available cluster*, not a lone box — otherwise it'd just move the single-point-of-failure problem up one level.

#### Q: How does the standby actually take over the VIP?

The two LBs health-check each other (protocols like VRRP / keepalived). When the standby stops hearing "I'm alive" from the active one, it **claims the VIP** — it starts answering for that address. Because clients only ever talk to the VIP, the swap is invisible to them; in-flight users just continue against the same front-door address.

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
