# Real-Time Communication — WebSocket vs SSE vs Polling

> How a server pushes updates to clients in near real time (chat, live scores, notifications, tracking, collaborative editing). The question is "which transport and why?" — the answer depends on **direction (one-way vs two-way)** and **scale of connections**.

> **How to read this doc:** each section has the dense summary first, then a **deep dive** (annotated example code, and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand.

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

### What problem are we even solving?

The web was built on a simple, one-sided rule: **the client asks, the server answers, done.** The client always speaks first. That's fine for "load this page," but poor for "tell me the moment a new chat message arrives" — because the server has data but **no way to initiate**. It has to wait for the client to ask.

So every technique here is really an answer to one question: **how does the server get fresh data to the client without the client having to guess when to ask?** They differ in *who sends*, *how often*, and *whether the connection stays open*.

The four options in one line each:

| Technique | What's happening |
| --- | --- |
| **Short polling** | Client keeps re-asking on a timer; most requests are wasted |
| **Long polling** | One request, but the server *holds the connection* until there's news |
| **SSE** | One open connection, server → client only |
| **WebSocket** | One open connection, *both sides* can send anytime |

Two dials decide everything (memorize these):

1. **Direction** — does only the *server* need to push (scores, notifications)? Or do *both* sides send constantly (chat, typing, game moves)?
2. **Connections at scale** — an open connection is not free. Millions of them held open at once is its own engineering problem (see §7).

### Why not just poll faster?

The tempting shortcut: "just have the client ask every second, close enough to real-time." Why big systems avoid it:

```
1M users × 1 request/sec = 1,000,000 requests/sec
...and ~99% of them come back with "nothing new" (wasted work)
```

Each poll pays the full cost of a fresh HTTP request (connection, headers, auth, a server thread) just to usually hear "nothing." At scale this overwhelms the server while still leaving the client up to a second behind. The rest of this doc is about **keeping the connection open** instead of re-requesting over and over.

---

## 2. Short Polling

Client requests on a fixed interval.

- ✅ Trivial; works everywhere.
- ❌ Wasteful (mostly empty responses), latency = poll interval, load scales with clients × frequency.
- Use only for low-frequency, non-urgent updates.

### Short polling in practice

The client runs a timer and re-asks at a fixed interval, whether or not there's new data. Most requests return nothing, and if data arrives just after a poll, the client won't see it until the next one — that gap is the worst-case latency.

Each ask is a brand-new, independent HTTP request:

```javascript
// SHORT POLLING — ask on a fixed interval, regardless of whether there's data
setInterval(async () => {
  const res  = await fetch("/api/messages?since=" + lastSeenId); // a full new request each time
  const msgs = await res.json();

  if (msgs.length > 0) {          // most of the time this is empty → wasted trip
    render(msgs);
    lastSeenId = msgs.at(-1).id;  // remember where we were, so we don't re-fetch old ones
  }
}, 5000);                          // every 5s → up to 5s stale, and 1 request/user/5s of load
```

The two knobs pull against each other:

- **Poll faster** (e.g. every 1s) → fresher data, but far more load and even more empty responses.
- **Poll slower** (e.g. every 30s) → less load, but staler data.

There's no setting that's both cheap *and* fresh — which is exactly why the other techniques exist.

#### Q: When is short polling actually the right call?

When updates are **rare and not urgent**, and you value dead-simple code: a dashboard that refreshes stats every 30–60s, checking a background job's status, a "new version available" banner. If a few seconds of staleness is fine and traffic is modest, don't over-engineer — short polling is genuinely the correct, boring choice.

---

## 3. Long Polling

Client sends a request; **server holds it open** until there's data (or a timeout), then the client immediately re-requests.

- ✅ Near real-time, works over plain HTTP (no special protocol), easy fallback.
- ❌ Still request/response overhead per message; many held connections.
- Good when WebSocket isn't available or updates are infrequent.

### Long polling in practice

The client sends a request; instead of replying immediately, the server holds it open and responds the *moment* there's data (or a timeout fires). The client then immediately sends another request to wait for the next update. It feels instant, and the client never spams the server with repeated "anything new?" polls.

The trick is entirely on the **server side**: it *doesn't answer right away*. It parks the request and waits until there's actually something to send (or a timeout fires).

Client side — ask, use the answer, immediately ask again (a loop of "waits"):

```javascript
// LONG POLLING — one request that may hang for a while, then loop
async function poll() {
  while (true) {
    // this fetch can sit "pending" for up to ~30s while the server holds it open
    const res  = await fetch("/api/messages/wait?since=" + lastSeenId);
    const msgs = await res.json();

    if (msgs.length > 0) {
      render(msgs);
      lastSeenId = msgs.at(-1).id;
    }
    // loop right back and re-ask → effectively "always waiting for the next update"
  }
}
```

Server side — the key difference from short polling is `await`ing new data before responding:

```javascript
// SERVER: hold the request open until there's data OR we hit a timeout
app.get("/api/messages/wait", async (req, res) => {
  const since = req.query.since;

  // block here (no busy-looping) until a new message arrives or ~30s passes
  const msgs = await waitForNewMessages(since, { timeoutMs: 30000 });

  res.json(msgs);   // could be new messages, or [] if it timed out → client just re-asks
});
```

#### Q: How is this different from short polling — isn't it still request/response?

Yes, it's still one-request-one-response, but the **waiting moved from the client to the server**:

| | Short polling | Long polling |
| --- | --- | --- |
| Who waits | **Client** waits (sleeps 5s), then asks | **Server** waits (holds the request), then answers |
| Empty responses | Tons (most polls find nothing) | Few (server answers only when there's news, or on timeout) |
| Latency | Up to the poll interval | Near-instant — you're told the moment data exists |

#### Q: Why the timeout? Why not hold forever?

Because a connection held open forever gets silently killed anyway (proxies, load balancers, and phones dropping off Wi-Fi). The ~30s timeout is a **safety reset**: the server sends back an empty answer, the client re-asks on a fresh connection, and everything stays healthy.

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

### SSE in practice

SSE is **one HTTP request that never finishes**, down which the server keeps sending messages. The client opens the stream **once** and then only listens; it never sends data back on that channel — it's one-way. If the connection drops, the client reconnects to the same endpoint and resumes.

Unlike long polling, the connection **stays open across many messages** — no re-asking after each one. The server just holds the response open and keeps writing.

The browser side is simple — the `EventSource` API does the hard parts (reconnect, parsing) for you:

```javascript
// SSE CLIENT — open one stream and listen. That's it.
const stream = new EventSource("/api/prices");

stream.onmessage = (event) => {
  const data = JSON.parse(event.data);   // e.g. {"price": 102}
  updatePrice(data.price);
};

// if the connection drops, EventSource AUTO-RECONNECTS on its own — no code needed
stream.onerror = () => console.log("reconnecting..."); // it retries automatically
```

The server keeps the response open and writes text in the tiny SSE format (`data: ...\n\n` per message):

```javascript
// SSE SERVER — never call res.end(); keep writing events down the same response
app.get("/api/prices", (req, res) => {
  res.setHeader("Content-Type", "text/event-stream");  // THE magic header → "this is a stream"
  res.setHeader("Cache-Control", "no-cache");
  res.setHeader("Connection", "keep-alive");

  const onPrice = (price) => {
    res.write(`id: ${price.seq}\n`);            // event id → client sends it back on reconnect
    res.write(`data: ${JSON.stringify(price)}\n\n`); // blank line (\n\n) = "end of this event"
  };

  priceFeed.on("update", onPrice);                    // push every update down the pipe
  req.on("close", () => priceFeed.off("update", onPrice)); // client left → stop pushing
});
```

#### Q: What do the built-in "auto-reconnect + event IDs" actually buy me?

If the stream drops (e.g. the network briefly fails), the browser reconnects **by itself** and sends back the last `id` it saw via a `Last-Event-ID` header. The server can then **resume from exactly there** — no gap, no duplicates. With long polling or raw WebSockets you'd have to build this resync yourself; SSE hands it to you for free. That's why it's the natural fit for live feeds where you can't afford to miss an event.

#### Q: If it's so simple, why not use SSE for everything (like chat)?

Because SSE is **one-way — server to client only.** The client cannot send data back up the same stream. For a live scoreboard or notifications that's perfect (the client only listens). For chat, the client *also* needs to send messages, typing indicators, etc. You'd have to bolt on separate POST requests for the upstream half — at which point a naturally two-way **WebSocket** is cleaner. Rule of thumb: **only-listening → SSE; talking-and-listening → WebSocket.**

#### Q: What's the "limited browser connection count" gotcha?

Over old **HTTP/1.1**, browsers cap open connections to the same domain at ~6. Each SSE stream eats one of those slots, so several tabs can starve the rest of your site's requests. Over **HTTP/2** (which multiplexes many streams over one connection) this limit effectively disappears — so in practice, serve SSE over HTTP/2.

---

## 5. WebSocket

A persistent, **full-duplex** TCP connection (starts as HTTP, then `Upgrade`).

```
client ⇄ server   (both can send anytime, low overhead per message)
```

- ✅ **Bidirectional**, low latency, low per-message overhead → best for **chat, gaming, collaborative editing, live trading**.
- ❌ Stateful (harder to scale/route), not plain HTTP (proxies/LB must support upgrade), need heartbeats + reconnect logic.

### WebSocket in practice

A WebSocket is *full-duplex*: once connected, **both client and server can send anytime**, over the **same open connection**, with almost no per-message overhead (no fresh headers/auth each time like HTTP).

It starts as a normal HTTP request that asks to "upgrade" the connection into a WebSocket — after that handshake, the plain HTTP rules no longer apply and the two-way connection is open:

```
client:  GET /chat HTTP/1.1
         Upgrade: websocket        ← "let's switch this HTTP connection into a WebSocket"
         Connection: Upgrade
server:  HTTP/1.1 101 Switching Protocols   ← "deal" → now it's a persistent duplex pipe
```

Client side — one object you can both listen on *and* send through:

```javascript
// WEBSOCKET CLIENT — one connection, both directions
const ws = new WebSocket("wss://example.com/chat");

// LISTEN (server → client), same as SSE
ws.onmessage = (event) => renderMessage(JSON.parse(event.data));

// SEND (client → server) — the thing SSE can't do on the same channel
sendButton.onclick = () => {
  ws.send(JSON.stringify({ type: "chat", text: input.value })); // instant, no new request
};

ws.onclose = () => reconnectWithBackoff();  // YOU must handle reconnect (not automatic like SSE)
```

Server side — receive and push over the same socket:

```javascript
// WEBSOCKET SERVER
wss.on("connection", (socket) => {
  socket.on("message", (raw) => {          // client → server (typing, chat, moves)
    const msg = JSON.parse(raw);
    broadcastToRoom(msg.room, msg);         // server → other clients
  });

  // HEARTBEAT: ping periodically so we notice dead connections (see Q below)
  const beat = setInterval(() => socket.ping(), 30000);
  socket.on("close", () => clearInterval(beat));
});
```

#### Q: WebSocket does everything SSE does *plus* two-way — why not always use it?

Because that power costs you three things SSE gave you free:

- **It's stateful.** An open WebSocket is pinned to a specific server; you can't casually move it. Millions of these open at once is a real scaling problem (see §7). Short/long polling and SSE are closer to plain HTTP and easier to spread across servers.
- **It's not plain HTTP.** Every proxy, load balancer, and firewall in the path must understand the `Upgrade` handshake and keep the connection alive, or it breaks.
- **You build your own reconnect + resync.** SSE auto-reconnects; with WebSocket, when the connection drops *you* must reconnect and catch up on what was missed.

So: reach for WebSocket when you genuinely need the client to **send** too (chat, typing, game input, collaborative edits). If the client only listens, SSE is the lighter tool.

#### Q: What are heartbeats and why are they needed?

If a client loses connectivity (drops off the network), **neither side is notified**; the TCP socket looks alive but is dead ("half-open"). So each side periodically sends a tiny **ping** and expects a **pong** back:

```
server → ping → (30s, no pong) → assume dead → close it, free the resources
```

Heartbeats (a) **detect dead connections** so you're not holding thousands of zombie sockets, and (b) **keep the connection active** so proxies don't kill an idle-looking connection. When a client notices the heartbeat stopped, it **reconnects and resyncs** from a cursor (the last message id it saw) — the same idea SSE gives you automatically.

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

### A decision flowchart

Don't memorize the table — run this checklist top to bottom and stop at the first "yes":

```
1. Are updates rare / staleness OK (dashboards, status checks)?
      → SHORT POLLING   (re-ask on a timer — dead simple)

2. Does the CLIENT need to send too (chat, typing, game moves, live edits)?
      → WEBSOCKET       (full-duplex — both sides send)

3. Only the SERVER pushes (scores, notifications, live feed, tracking)?
      → SSE             (one-way stream; free auto-reconnect)

4. Need near-real-time but can't use WebSocket/SSE (old proxy, restrictive network)?
      → LONG POLLING    (server holds the request — the universal HTTP fallback)
```

The one line to remember: **direction first, then scale.** Two-way ⇒ WebSocket; one-way ⇒ SSE; everything else is a fallback.

#### Q: WebSocket vs SSE — the interview favorite, settled simply

They overlap on the *server → client* half; the difference is the *other* half:

| | SSE | WebSocket |
| --- | --- | --- |
| Server → client | ✅ | ✅ |
| Client → server (same channel) | ❌ (need separate POSTs) | ✅ |
| Protocol | plain HTTP | HTTP upgrade → TCP |
| Auto-reconnect / resume | ✅ built in | ❌ you build it |
| Scaling | easier (HTTP-like) | harder (stateful) |

If the client only needs to listen, use **SSE**. If the client also needs to send on the same connection, use **WebSocket**. Picking WebSocket "just in case" means paying for statefulness and reconnect logic you may never use.

#### Q: Why isn't mobile push (FCM/APNS) in this list?

All four techniques above need your **app to be open and connected**. When the app is backgrounded or the phone is asleep, that connection is gone. To wake a closed app ("you have a new message"), you hand the payload to the OS-level push services — **FCM** (Android) / **APNS** (iOS) — which maintain their own single always-on connection to the device. Those are a **different layer** for a different job (reaching a *closed* app), which is why they sit outside this comparison.

---

## 7. Scaling Persistent Connections

- **Stateful gateways** hold millions of connections → scale horizontally.
- **Connection registry (Redis):** `user → {gateway node, connId}` so any server can route a message to the right connection.
- **Inter-node routing:** a pub-sub bus (Kafka/Redis) — the sender's gateway publishes, the recipient's gateway consumes and pushes.
- **Heartbeats/ping-pong** detect dead connections; clients **reconnect + resync** from a cursor.
- **Sticky routing / load balancer** must support WebSocket upgrade + affinity.

### Why open connections are hard to scale

A normal HTTP request is quick: a request comes in, gets answered, and the connection closes — any server can handle the next one. A persistent connection (WebSocket/SSE) **stays open for a long time**, tying up resources on one server the whole time. With millions of connections held open at once, the bottleneck isn't request speed — it's *holding all those connections*, plus a way to **find which server holds which client's connection** when a message needs to reach them.

That reframes scaling into three concrete problems:

**Problem 1 — Holding millions of open connections.** Each connection eats memory and a slot on a server ("gateway"). One box can't hold them all, so you run a **fleet of stateful gateway nodes** and add more as connections grow (horizontal scaling). These gateways do little logic; their job is just to *hold connections*.

**Problem 2 — Finding the right connection.** Say Alice (connected to gateway 7) messages Bob. Which gateway is holding Bob's connection? You keep a **connection registry** — usually Redis — mapping user → node:

```
Redis:  user:bob   → { node: "gateway-3", connId: "c-8821" }
        user:alice → { node: "gateway-7", connId: "c-1042" }
```

Any server can look Bob up and learn "he's on gateway-3." Without this, no server would know where to deliver a message.

**Problem 3 — Getting a message from one gateway to another.** Alice's message lands on gateway-7, but Bob is on gateway-3. The gateways talk to each other over a **pub-sub bus** (Kafka / Redis pub-sub): gateway-7 *publishes* "message for Bob," gateway-3 (subscribed) *receives* it and pushes it down Bob's open socket.

```
Alice → gateway-7 ──publish──► pub-sub bus ──deliver──► gateway-3 → (open socket) → Bob
                         (registry says Bob lives on gateway-3)
```

Two more essentials that keep the fleet healthy:

- **Heartbeats + reconnect/resync (§5):** ping/pong culls dead sockets so gateways aren't clogged with zombies; when a client reconnects (maybe to a *different* gateway), it **resyncs from a cursor** — "last message I saw was id 415, catch me up" — so nothing is missed or duplicated.
- **Sticky routing at the load balancer:** the LB must (a) understand the WebSocket `Upgrade` handshake, and (b) keep each client pinned to its gateway (**affinity**) — an established connection can't be moved to a different gateway mid-stream.

#### Q: Why not keep the registry in each server's memory instead of Redis?

Because gateway-7 needs to find a user connected to gateway-3 — a fact that lives on a *different* box. In-memory maps are per-server islands; the registry must be **shared** so *any* node can locate *any* user. Redis is the shared, fast lookup all gateways read from. (This exact machinery powers chat systems — see the WhatsApp note below.)

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
