# Real-Time Communication — WebSocket vs SSE vs Polling

> How a server pushes updates to clients in near real time (chat, live scores, notifications, tracking, collaborative editing). The question is "which transport and why?" — the answer depends on **direction (one-way vs two-way)** and **scale of connections**.

---

## Contents

- [1. The Options](#1-the-options)
- [2. Short Polling](#2-short-polling)
- [3. Long Polling](#3-long-polling)
- [4. Server-Sent Events (SSE)](#4-server-sent-events-sse)
- [5. WebSocket](#5-websocket)
- [6. Comparison & When to Use](#6-comparison--when-to-use)
- [7. Scaling Persistent Connections](#7-scaling-persistent-connections)
- [8. Interview Cheat Sheet](#8-interview-cheat-sheet)
- [9. Final Takeaways](#9-final-takeaways)

---

## 1. The Options

```
Short polling  → client asks every N sec ("anything new?")            simple, wasteful
Long polling   → client asks, server holds until data or timeout      near real-time over HTTP
SSE            → one long-lived HTTP stream, server → client only      one-way push
WebSocket      → full-duplex persistent connection                    two-way real-time
```

---

## 2. Short Polling

Client requests on a fixed interval.

- ✅ Trivial; works everywhere.
- ❌ Wasteful (mostly empty responses), latency = poll interval, load scales with clients × frequency.
- Use only for low-frequency, non-urgent updates.

---

## 3. Long Polling

Client sends a request; **server holds it open** until there's data (or a timeout), then the client immediately re-requests.

- ✅ Near real-time, works over plain HTTP (no special protocol), easy fallback.
- ❌ Still request/response overhead per message; many held connections.
- Good when WebSocket isn't available or updates are infrequent.

---

## 4. Server-Sent Events (SSE)

A single long-lived HTTP response streaming events **server → client only**.

```
Content-Type: text/event-stream
data: {"price": 101}\n\n
data: {"price": 102}\n\n
```

- ✅ Simple, over HTTP, **auto-reconnect** + event IDs built in, works with HTTP/2.
- ❌ **One-way only** (server→client); limited browser connection count on HTTP/1.1.
- Great for **feeds, live scores, notifications, dashboards** (no client→server stream needed).

---

## 5. WebSocket

A persistent, **full-duplex** TCP connection (starts as HTTP, then `Upgrade`).

```
client ⇄ server   (both can send anytime, low overhead per message)
```

- ✅ **Bidirectional**, low latency, low per-message overhead → best for **chat, gaming, collaborative editing, live trading**.
- ❌ Stateful (harder to scale/route), not plain HTTP (proxies/LB must support upgrade), need heartbeats + reconnect logic.

---

## 6. Comparison & When to Use

| | Short poll | Long poll | SSE | WebSocket |
| --- | --- | --- | --- | --- |
| Direction | c→s | c→s | **s→c** | **two-way** |
| Real-time | ❌ | ~ | ✅ | ✅✅ |
| Transport | HTTP | HTTP | HTTP | TCP (upgrade) |
| Overhead | high | medium | low | low |
| Use | simple/rare | fallback | feeds, notifications | chat, games, docs |

> **Pick by direction:** need **two-way** (chat, editing) → **WebSocket**; **server→client only** (notifications, live feed) → **SSE**; can't use either → **long polling**. Push notifications (app in background) use FCM/APNS, not these.

---

## 7. Scaling Persistent Connections

- **Stateful gateways** hold millions of connections → scale horizontally.
- **Connection registry (Redis):** `user → {gateway node, connId}` so any server can route a message to the right connection.
- **Inter-node routing:** a pub-sub bus (Kafka/Redis) — the sender's gateway publishes, the recipient's gateway consumes and pushes.
- **Heartbeats/ping-pong** detect dead connections; clients **reconnect + resync** from a cursor.
- **Sticky routing / load balancer** must support WebSocket upgrade + affinity.

---

## 8. Interview Cheat Sheet

> **"WebSocket vs SSE vs polling — which?"**
> "By direction and scale: two-way real-time (chat, collaborative editing) → **WebSocket**; server→client only (notifications, live feed, tracking) → **SSE** (simpler, auto-reconnect); as a universal fallback → **long polling**. Short polling only for infrequent updates."

> **"How do you scale millions of WebSocket connections?"**
> "Stateful gateway fleet + a Redis connection registry (user→node) so any server can locate a recipient; route across nodes via a pub-sub bus; heartbeats detect dead conns; clients reconnect and resync from a cursor."

> **"SSE vs WebSocket specifically?"**
> "SSE is one-way (server→client) over plain HTTP with built-in reconnect — great for feeds. WebSocket is full-duplex — needed when the client also streams (typing, edits, moves)."

---

## 9. Final Takeaways

- **Direction decides:** two-way → **WebSocket**; one-way push → **SSE**; fallback → **long polling**; avoid short polling except for rare updates.
- WebSocket = full-duplex, low-latency, **stateful** (harder to scale); SSE = simple one-way over HTTP with auto-reconnect.
- Scale persistent connections with **stateful gateways + Redis connection registry + pub-sub routing + heartbeats/reconnect**.
- Background mobile push = **FCM/APNS**, not these.

### Related notes

- [Networking Essentials](networking-essentials.md) · [API Paradigms](api-paradigms.md)
- [Chat & Messaging (WhatsApp)](../system-design/whatsapp-chat-system-design.md) · [Notification System](../system-design/notification-system-design.md) · [Google Docs](../system-design/google-docs-system-design.md)
