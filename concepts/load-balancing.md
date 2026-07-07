# Load Balancing

> **Goal:** spread incoming traffic across multiple servers so no single one is overwhelmed — improving **scalability, availability, and latency**. The interview question is usually "L4 vs L7, which algorithm, and how do you handle a server dying?"

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

---

## 5. Session Stickiness

If a server holds session state, subsequent requests from a user should return to it.

| Approach | Note |
| --- | --- |
| **Cookie-based (L7)** | LB sets a cookie → routes to same backend |
| **IP hash** | Same client IP → same server (breaks behind NAT/mobile) |
| **Better: stateless servers** | Store session in Redis/JWT → **no stickiness needed** ✅ |

> Prefer **stateless** app servers (session in a shared store) so any server can handle any request — stickiness is a crutch.

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
