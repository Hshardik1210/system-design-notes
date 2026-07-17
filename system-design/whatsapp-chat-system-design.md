# Chat & Messaging — System Design (WhatsApp / Messenger / Slack DMs)

> **Core challenge:** deliver messages between users **in real time**, **reliably** (never lose a message), **in order**, with **delivery/read receipts**, **presence**, **group chat**, **multi-device sync**, and **offline delivery** — over hundreds of millions of **persistent connections**, optionally **end-to-end encrypted**, at billions-of-messages/day scale.

> **How to read this doc:** each section has the dense interview summary first, then a **deep dive** (annotated code and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand.

---

## Contents

- [1. Mental Model](#1-mental-model)
- [2. Requirements](#2-requirements)
- [3. Capacity Estimation](#3-capacity-estimation)
- [4. Connection Layer (WebSocket Gateway)](#4-connection-layer-websocket-gateway)
- [5. Inter-Gateway Routing (across nodes)](#5-inter-gateway-routing-across-nodes)
- [6. Message Flow — 1:1](#6-message-flow--11)
- [7. Delivery Semantics & Receipts](#7-delivery-semantics--receipts)
- [8. Ordering](#8-ordering)
- [9. Offline Delivery & Message Store](#9-offline-delivery--message-store)
- [10. Group Chat & Fan-out](#10-group-chat--fan-out)
- [11. Presence & Typing Indicators](#11-presence--typing-indicators)
- [12. End-to-End Encryption (Signal protocol)](#12-end-to-end-encryption-signal-protocol)
- [13. Multi-Device Sync](#13-multi-device-sync)
- [14. Media / Attachments](#14-media--attachments)
- [15. Data Model (all tables)](#15-data-model-all-tables)
- [16. API / Protocol](#16-api--protocol)
- [17. Sequences](#17-sequences)
- [18. Consistency & Correctness](#18-consistency--correctness)
- [19. Scaling & Failure](#19-scaling--failure)
- [20. Interview Cheat Sheet](#20-interview-cheat-sheet)
- [21. Consistency & CAP Tradeoffs](#21-consistency--cap-tradeoffs)
- [22. How to Drive the Interview (framework)](#22-how-to-drive-the-interview-framework)
- [23. Reliability & Observability](#23-reliability--observability)
- [24. Design Patterns (that can be used)](#24-design-patterns-that-can-be-used)
- [25. Final Takeaways](#25-final-takeaways)

---

## 1. Mental Model

```
Sender ──WebSocket──► Gateway A ──► [persist message] ──► find recipient's Gateway B ──► push to device
                                                       └► if offline, keep in store + push notification (APNS/FCM)
```

Persistent connections + a **message store** + a **router** that finds where the recipient is connected. The hard parts: **connection management at scale**, **reliable + ordered delivery**, **offline sync**, **multi-device**, and (WhatsApp) **end-to-end encryption**.

### What are we actually building?

Texting: you type "hey", hit send, and a moment later a checkmark appears. The recipient's phone buzzes. If their phone is off, the message isn't lost — it shows up the second they turn it back on. That entire experience, but for **two billion people at once**, is what we're designing.

Break it into three jobs:

1. **Keep a live connection open to every phone.** So the server can push a message *down* to you the instant it arrives, instead of you repeatedly asking "any new messages?". That connection is a **WebSocket** (§4).
2. **Persist every message before acknowledging it as "sent".** Write the message durably before returning the ack, so a crash a moment later can't lose it. This is the **message store** (§9).
3. **Find where the other person is.** Each phone is connected to some gateway server. We keep a **connection registry** (§5) that records "user 42's phone is currently connected to gateway server B" so we know where to push the message.

Everything else in this doc — ticks, presence, groups, encryption — is a refinement on those three jobs.

### Why this is harder than a normal website with a database

A normal website is **pull**: your browser asks the server for a page, the server answers, done. Chat is **push**: the server has to reach *out* to your phone at a random moment (when someone messages you), and your phone might be asleep, on a train, or dead. Holding a live connection open to hundreds of millions of phones — and remembering which server each one is on — is the whole ballgame.

---

## 2. Requirements

> 💡 **Always start the interview here.** Clarifying scope out loud — and naming what you're *deferring* — frames every later decision and reads as senior.

**Functional**
- 1:1 and **group** messaging; real-time delivery.
- **Delivery receipts** (sent ✓, delivered ✓✓, read ✓✓ blue), **typing**, **presence** (online/last-seen).
- **Offline** users get messages on reconnect; **history sync across multiple devices**.
- Media (images/video/docs/voice); **end-to-end encryption**.

### Non-Functional (NFRs)

| NFR | Target / Note |
| --- | --- |
| **Consistency** | **Ordered per conversation** (via per-conversation `seq`, §8) and **no message loss** (persist-before-ack, §6). *Global* ordering across conversations is neither needed nor scalable. |
| **Availability** | **High** — the connection/send path stays up under node loss (reconnect + resync). Presence can be stale (AP); the durable message write favors consistency (CP, §21). |
| **Latency** | In-region delivery **<100–200ms** end-to-end for online users; "sent ✓" should feel instant (it's just the durable write). |
| **Durability** | An accepted message must **never be lost** — persisted before the ack, retained until every device has it. |
| **Scale** | Billions of msgs/day (~1.15M msg/sec avg, 2–3× peaks), hundreds of millions of concurrent connections, multi-device per user. |

### Out of scope (state assumptions)

- **Voice/video calls** (a separate real-time media/WebRTC problem) and **server-side full-text search** (impossible under true E2E — nothing readable to index, §12). Call these out, then focus on messaging. (**Delete-for-everyone / edit** are a light add-on — covered as revoke/edit control messages in §7.)

---

## 3. Capacity Estimation

```
Users            ~ 2B, DAU ~ 1B
Messages/day     ~ 100B → ~1.15M msg/sec average; peaks 2–3× (evenings, events)
Concurrent conns ~ hundreds of millions of persistent WebSockets simultaneously

Connection cost:
  Each idle WebSocket holds memory (buffers, TLS state) — say ~10 KB
  100M conns × 10 KB = ~1 TB RAM just for connections → many gateway nodes
  A tuned gateway holds ~1M connections/node → need ~hundreds of nodes

Message store:
  Text msg ~ 100–300 bytes; 100B/day → tens of TB/day if retained
  WhatsApp (E2E): server stores ciphertext only UNTIL delivered, then deletes → tiny footprint
  Slack/Messenger (server-side history): retain → wide-column store (Cassandra/HBase), partition by conversation
```

> Two cost centers: **millions of persistent connections** (memory/CPU on gateways) and **message throughput** (a partitioned write-optimized store). E2E designs shrink storage by deleting delivered messages.

### Reading the "~1 TB RAM" number — it's fleet-wide, not one machine

That 1 TB is the **aggregate across all gateways**, not a single box. Divide it by per-node capacity:

```
Total RAM for conns = 100M conns × ~10 KB = ~1 TB    (whole fleet)
Per node            = ~1M conns × ~10 KB  = ~10 GB   (ordinary hardware)
Number of nodes     = 100M ÷ 1M           = ~100+ gateway nodes
```

You never build one impossible 1 TB machine — you **scale horizontally**: each gateway is a normal ~32–64 GB instance holding ~1M connections, and you run hundreds of them. This is exactly why you need the **connection registry** (§5) to find *which* node holds a given user, and why load balancing is **by connection count** (§4).

---

## 4. Connection Layer (WebSocket Gateway)

Clients hold a **persistent WebSocket** (WhatsApp historically used an XMPP variant; MQTT/WebSocket are common) to a **gateway** node.

```
Client ⇄ WebSocket Gateway (stateful)  ── on connect, register: user/device → {gatewayNode, connId}
```

- **Connection registry (Redis):** `conn:user:{id} → [{device, gatewayNode, connId}]` so **any** node can find where a user's devices are connected. TTL + refreshed by heartbeat.
- **Stateful gateways** — a connection lives on one node; the registry lets other nodes route to it.
- **Heartbeats / ping-pong** detect dead connections (mobile networks drop silently); clients auto-reconnect + resync.
- **Load balancer** must support WebSocket upgrade; connections are long-lived so balance by connection count, drain gracefully on deploy.
- Gateways are **horizontally scaled**; a user's multiple devices may land on different nodes.

### The persistent connection

A normal web request (REST) is one-shot: the client sends a request, gets one reply, and the exchange is over — to ask again it must open a new request. A **WebSocket** keeps a single connection open the whole time. Neither side has to reconnect: the moment either side has something to send, it sends it and the other receives it instantly.

That always-open connection is exactly what chat needs, because messages arrive at *unpredictable* times and the **server** must be able to initiate (push to you), not just answer when asked.

### WebSocket vs. polling — why not just ask the server every few seconds

**Polling** = your phone asks "any new messages?" every, say, 3 seconds.

```
phone → server: "anything for me?"   (3s later)
phone → server: "anything for me?"   (3s later)
phone → server: "anything for me?"   ... forever, mostly hearing "no"
```

Two problems: (1) **slow** — a message can sit up to 3 seconds before you even ask; (2) **wasteful** — a billion phones asking every 3 seconds is a billion pointless requests when 99% say "nothing new." A WebSocket flips it: open the line **once**, then the server pushes the instant something happens. One connection, zero wasted "anything?" round-trips, near-instant delivery.

| | **Polling** | **WebSocket** |
| --- | --- | --- |
| Who starts talking | Client keeps asking | Either side, anytime (server can push) |
| Latency | Up to the poll interval | Near-instant |
| Wasted traffic | Tons ("nothing new") | Almost none |
| Connection | New request each time | One long-lived connection |

#### What "handling a connection" looks like in code

When a phone connects, the gateway does two things: keep the live socket object in local memory (so it can push later), and write a note in the shared registry saying "this user is here, on me."

```java
// A gateway node. It literally holds a live socket per connected device in RAM.
@Component
public class WebSocketGateway {

    private final String nodeId = "gateway-B";   // which gateway node this is

    // device_id -> the live open connection to that phone (kept in memory)
    private final Map<String, WsConnection> liveConns = new ConcurrentHashMap<>();

    // called when a phone opens its WebSocket to us
    public void onConnect(WsConnection conn, String userId, String deviceId) {
        liveConns.put(deviceId, conn);                    // remember the live wire locally

        // tell the shared registry "this user's device is reachable on THIS node"
        registry.add("conn:user:" + userId,
                     new Route(deviceId, nodeId, conn.id()),
                     Duration.ofSeconds(60));             // TTL — refreshed by heartbeats
    }

    // called when the phone sends a heartbeat "ping" — proves it's still alive
    public void onHeartbeat(String userId, String deviceId) {
        registry.refreshTtl("conn:user:" + userId, deviceId, Duration.ofSeconds(60));
    }

    // called when the socket drops (app closed, network died, phone slept)
    public void onDisconnect(String userId, String deviceId) {
        liveConns.remove(deviceId);                       // drop the local wire
        registry.remove("conn:user:" + userId, deviceId); // remove from the registry
    }

    // how another part of the system pushes a message down to this device
    public boolean pushToDevice(String deviceId, Message m) {
        WsConnection conn = liveConns.get(deviceId);
        if (conn == null) return false;                   // not connected here anymore
        conn.send(m);                                     // shove it down the open wire
        return true;
    }
}
```

- `liveConns` (in RAM on this one node) = the actual open wires. This is why gateways are **stateful**: the connection *is* the state, and it can't move to another machine.
- The **registry** entry (in Redis, shared) = what other nodes read to find this user (§5).
- **Heartbeats + TTL:** mobile networks die silently (subway, dead battery) — no "goodbye" packet. So each entry expires after ~60s unless the phone keeps pinging. No ping → entry vanishes → the user is treated as offline.

### Why you can't just move a connection to a different, less-busy server

The open socket is a physical thing pinned to one machine's memory and network card — you can't move a live connection to another machine. That's why we **balance by connection count** (send new connections to emptier nodes) and, on deploy, **drain gracefully** (stop accepting new connections, let existing ones migrate as clients reconnect) instead of yanking them.

### How a connection is actually established (handshake + auth)

`onConnect(conn, …)` above is a simplification: the gateway doesn't *create* the connection, it **reacts** to one the WebSocket framework already established. The real sequence: the app opens `wss://…/v1/ws?token=…`; the framework completes the TCP + TLS + HTTP-`Upgrade` handshake and constructs the live socket object; **then** it calls your handler. In Spring that handler method is `afterConnectionEstablished(session)` — `session` *is* the `conn` you were handed (that's why `conn` is a **parameter**, not something you `new` yourself).

Identity (`userId`/`deviceId`) is attached **during** the handshake by an auth interceptor that runs *before* the connection is accepted (§16: "auth + device key on handshake"):

```java
// 1. Validate the token BEFORE accepting the socket; stash identity for later.
public class AuthHandshakeInterceptor implements HandshakeInterceptor {
    public boolean beforeHandshake(ServerHttpRequest req, ServerHttpResponse res,
                                   WebSocketHandler h, Map<String,Object> attrs) {
        Claims c = tokens.verify(tokenFrom(req));   // reject → handshake refused, no connection
        if (c == null) { res.setStatusCode(HttpStatus.UNAUTHORIZED); return false; }
        attrs.put("userId", c.userId());
        attrs.put("deviceId", c.deviceId());
        return true;
    }
    public void afterHandshake(ServerHttpRequest req, ServerHttpResponse res,
                               WebSocketHandler h, Exception ex) {}
}

// 2. The framework calls THIS once the socket is live — the real "onConnect".
public class ChatWebSocketHandler extends TextWebSocketHandler {
    protected void afterConnectionEstablished(WebSocketSession session) {
        String userId   = (String) session.getAttributes().get("userId");
        String deviceId = (String) session.getAttributes().get("deviceId");
        gateway.onConnect(new SpringWsConnection(session), userId, deviceId); // → liveConns + registry
        presence.markOnline(userId);
    }
    protected void handleTextMessage(WebSocketSession s, TextMessage m) { /* parse frame, dispatch */ }
    public void afterConnectionClosed(WebSocketSession s, CloseStatus st)  { /* onDisconnect + offline */ }
}
```

Client side is just: open the socket, render pushes, auto-reconnect on close, and send a periodic `PING` so the registry TTL never lapses.

```javascript
const ws = new WebSocket("wss://chat.example.com/v1/ws?token=" + jwt);
ws.onopen    = () => {};                                    // triggers the server's onConnect
ws.onmessage = (e) => renderMessage(JSON.parse(e.data));    // server pushed something
ws.onclose   = () => scheduleReconnect();                   // reconnect + resync (§9)
setInterval(() => ws.send(JSON.stringify({ type:"PING" })), 15000);  // keep TTL alive
```

### Where the gateway's `nodeId` comes from

A **gateway node is just a server process** (on an EC2 instance, a K8s pod, a container — the substrate doesn't matter; its whole job is to hold ~1M WebSockets and push down them). The `nodeId = "gateway-B"` in the code is **hardcoded only for illustration**: you deploy the *same* image to all N instances, so the id must be **injected at runtime**, not baked into the code. Common ways:

| Method | How the id is set |
| --- | --- |
| **Env var** (most common) | `System.getenv("NODE_ID")` — orchestrator injects a unique value |
| **Hostname** | `InetAddress.getLocalHost().getHostName()` |
| **K8s StatefulSet** | Stable ordinal hostnames `gateway-0..N` — *preferred for stateful gateways* (stable across restarts, plays nice with graceful drain) |
| **IP:port** | Inherently unique + directly routable |

Only two requirements: the id must be **unique** across the fleet (so registry entries don't collide) and **addressable** (others must be able to route to it — via a bus topic named after it, or its IP:port).

---

## 5. Inter-Gateway Routing (across nodes)

Sender and recipient are usually connected to **different gateway nodes**. How does A's message reach B's node?

```
Sender (Gateway A) → Chat Service persists msg
   → look up recipient in connection registry → recipient's device is on Gateway B
   → route to Gateway B via a PUB-SUB bus (Kafka topic per node / Redis pub-sub / internal RPC)
   → Gateway B pushes down B's WebSocket to the device
```

| Approach | Note |
| --- | --- |
| **Pub-sub bus (Kafka/Redis)** | Sender's node publishes `{recipientNode, payload}`; recipient's node subscribes and pushes. Decoupled, scalable |
| **Direct RPC** | A looks up B's node and RPCs it directly (lower latency, more coupling) |
| **Registry lookup** | Redis `conn:user:{id}` returns the node(s) + connId to target |

> **Key idea:** the gateway a user is connected to is dynamic, so routing = **registry lookup + a message bus between gateways**. No single node holds all connections.

### Your message and your friend are on different servers

The sender is connected to Gateway A, but the recipient's device holds its socket on a *different* node, Gateway B. Gateway A has no open socket to the recipient. So it (1) looks the recipient up in the **connection registry** (which returns "on gateway-B") and (2) relays the message over the **pub-sub bus** to Gateway B, which pushes it down its socket. The registry lives in Redis; the bus is Kafka/Redis pub-sub.

Why not have Gateway A talk to the device directly? Because A has **no open socket** to the recipient's device — only B does. The socket is pinned to B (§4). So A must relay through B.

```java
public class MessageRouter {

    public void route(Message m) {
        // 1. WHERE is the recipient connected? ask the shared registry
        List<Route> routes = registry.lookup("conn:user:" + m.recipientId());

        if (routes.isEmpty()) {
            offlineHandler.storeAndPush(m);   // nobody online → store + push notif (§9)
            return;
        }

        // 2. a user can be on several nodes (phone on B, laptop on C) — send to each
        for (Route r : routes) {
            // publish onto the bus, addressed to that specific gateway node
            bus.publish(r.gatewayNode(),                      // e.g. "gateway-B"
                        new Envelope(r.connId(), m));
        }
    }
}

// Each gateway subscribes to "messages addressed to ME" and pushes them down the wire.
@Component
public class GatewayBusListener {

    @KafkaListener(topics = "gateway-B")   // this node only reads its own topic
    public void onEnvelope(Envelope e) {
        gateway.pushToDevice(e.deviceId(), e.message());   // down the local WebSocket
    }
}
```

### Bus (pub-sub) vs. direct RPC — which one

Both are shown in the table above. The **bus** (Kafka/Redis pub-sub) decouples nodes: A doesn't need to know B's address or whether B is healthy — it just publishes "for gateway-B" and B picks it up. **Direct RPC** (A calls B's IP directly) is a hop faster but couples A to B's location and liveness. Most designs pick the bus for resilience at scale; a latency-obsessed design might RPC within a region.

### What if the recipient reconnects to a different server mid-message

That's exactly why the registry has a **short TTL and heartbeats** (§4). If Bob's phone hops from B to D, the old entry expires and a new one ("Bob is on D") appears within seconds. If a message is published to B just as Bob leaves, B's push fails — the message is still safely stored (§6 persists first), so Bob gets it on resync (§9). Nothing is lost.

### Subscribing to your own node's topic with a runtime `nodeId`

The listener shows `@KafkaListener(topics = "gateway-B")`, but since `nodeId` is decided at runtime (§4) you can't hardcode the topic. An annotation value must resolve at startup, so feed the id in as a property (resolved once, before the listener is built):

```java
@KafkaListener(topics = "${node.id}")          // resolves to e.g. "gateway-7" for THIS instance
public void onEnvelope(Envelope e) {
    gateway.pushToDevice(e.deviceId(), e.message());
}
```

If the id is only known programmatically, skip the annotation and build the container yourself:

```java
@PostConstruct
void subscribe() {
    var container = factory.createContainer(nodeId);   // runtime topic name
    container.getContainerProperties().setMessageListener(
        (MessageListener<String, Envelope>) rec ->
            gateway.pushToDevice(rec.value().deviceId(), rec.value().message()));
    container.start();
}
```

The **same `nodeId`** is used on both sides: the router **publishes** to it (`bus.publish(r.gatewayNode(), …)`) and the owning node **subscribes** to it — and it must match the id written into the registry during `onConnect` (§4).

⚠️ **Scaling caveat:** a Kafka *topic-per-node* doesn't scale to thousands of autoscaling nodes (partition limits, churn from create/delete). At large scale prefer **Redis Pub/Sub channels per node** (cheap, ephemeral — the alternative listed above) or a smaller set of sharded topics. Topic-per-node is the clean *conceptual* model; Redis pub-sub is often the more practical one for "route to one ephemeral node."

---

## 6. Message Flow — 1:1

```
1. Sender → WebSocket (Gateway A) → Chat Service
2. Chat Service:
     a. persist message (status = SENT)                 # DURABILITY FIRST
     b. ACK sender ("sent ✓") with server seq
     c. look up recipient's device(s) in the registry
     d. online  → route to recipient's gateway → push to device
        offline → leave in store (undelivered) + trigger push notification (APNS/FCM)
3. Recipient device receives → sends "delivered" receipt → status ✓✓ → notify sender
4. Recipient opens chat → "read" receipt → status ✓✓ blue → notify sender
```

> **Persist before ack** → a crash after accepting never loses the message (at-least-once). The client **dedups by `message_id`** so retries don't create duplicates.

### Persist, then confirm

The server must **persist the message durably before acknowledging it**. When a message arrives, the server writes it to the store (durable write) and only *then* returns the "✓ sent" ack. If the server crashes right after persisting, the message is safe and delivery can be retried later. The failure to avoid: acking "sent" and then crashing before the write lands — that loses the message.

That ordering — **persist first, confirm second** — is the single most important rule in the whole design:

```
persist message  ─►  THEN ack the sender   ✅  (crash after persist = message safe, retry delivery)
ack the sender    ─►  THEN persist message  ❌  (crash in between = sender thinks it sent, message gone)
```

#### The delivery + ack flow in code

```java
@Component
public class ChatService {

    public Ack handleSend(IncomingMessage in, String senderConnId) {
        // 0. the client made a UUID for this message BEFORE sending — used for dedup
        //    if this exact message_id already exists, this is a retry: don't double-store
        if (store.exists(in.messageId())) {
            long existingSeq = store.seqOf(in.messageId());
            return new Ack(in.clientMsgId(), existingSeq, "SENT");   // idempotent reply
        }

        // 1. assign the per-conversation order number (see §8) and PERSIST — durability first
        long seq = seqGenerator.next(in.conversationId());
        store.append(new StoredMessage(
                in.conversationId(), seq, in.messageId(),
                in.senderId(), in.ciphertext(), Status.SENT, now()));

        // 2. ONLY NOW tell the sender "✓ sent" — the message is safely on disk
        Ack ack = new Ack(in.clientMsgId(), seq, "SENT");
        gateway.pushToConn(senderConnId, ack);

        // 3. try to deliver to the recipient's device(s)
        router.route(store.get(in.conversationId(), seq));   // online → push; offline → store + notif

        return ack;
    }
}
```

Walk the numbered steps: **(1)** persist the message, **(2)** ack the sender, **(3)** attempt delivery. Steps 2 and 3 can even fail entirely — the message is already safe, so we just retry delivery later.

#### Q: What does "at-least-once" mean, and won't I see duplicate messages?

"At-least-once" means we'd rather deliver a message **twice** than risk delivering it **zero** times. So on a flaky network the sender's app may resend, and the server may re-push. To stop you from *seeing* a message twice, every message carries a **`message_id` (a UUID the sender generates once)**. The server and the receiving app both check "have I already got this id?" and ignore repeats. Net effect: sent at-least-once on the wire, shown **exactly once** on your screen. This is the `store.exists(...)` check above and the client-side dedup.

### Why the sender gets a ✓ so fast, before the recipient has it

The ✓ only means "**the server has safely stored it**" — not "delivered." Delivery to the other person is a separate, slower step (they might be offline). That's the whole point of the different ticks in §7: ✓ = stored, ✓✓ = on their device, blue = they read it.

### Where the `conversationId` comes from (conversation creation)

`handleSend` assumes `in.conversationId()` already exists — so who creates it, and when? A **conversation** is the chat thread a message belongs to (its `type` is `DIRECT` or `GROUP`, §15); it's the anchor for storage (**partition key**), ordering (scope of `seq`), and membership (who receives it).

- **GROUP** → created **explicitly** when the group is made (tap "New Group"): one `INSERT` into `conversations (type='GROUP')`, plus `conversation_members` rows and `group_metadata`. The id exists before any message.
- **DIRECT (1:1)** → usually created **lazily on the first message** via a find-or-create:

```java
long conversationId = conversations.findOrCreateDirect(senderId, recipientId);

long findOrCreateDirect(long a, long b) {
    long lo = Math.min(a, b), hi = Math.max(a, b);    // canonicalize the pair!
    Long existing = lookupDirect(lo, hi);
    if (existing != null) return existing;             // already chatting → reuse the same thread
    long id = insertConversation("DIRECT");
    addMembers(id, lo, hi);
    return id;
}
```

Two details that matter: **canonicalize the participant pair** (sort the two user ids) so "A→B" and "B→A" map to the *same* conversation, and make find-or-create **atomic/idempotent** (unique constraint on the sorted pair) so two simultaneous first messages don't create duplicate threads. Either way the id exists **before** `handleSend` stamps it onto the stored message. (Some designs instead create it when you *open* the chat via `POST /v1/conversations`.)

---

## 7. Delivery Semantics & Receipts

| Receipt | Meaning | Trigger |
| --- | --- | --- |
| **Sent ✓** | Server accepted + stored | after the durable write |
| **Delivered ✓✓** | Reached recipient's device | device ack |
| **Read ✓✓ (blue)** | Recipient opened the chat | read event |

- **At-least-once + client dedup** by client-generated **`message_id` (UUID)** → safe retries, no dupes.
- Receipts are themselves small messages routed the same way (and update `message_receipts`).

### The one, two, and blue ticks

WhatsApp's ticks are three delivery milestones for your message:

| What you see | Really means | Who reports it |
| --- | --- | --- |
| **Single ✓** | Server stored it | the server, after the durable write |
| **Double ✓✓** | It reached the recipient's *device* | the recipient's **device** sends a "delivered" receipt |
| **Blue ✓✓** | The recipient **opened the chat** | the recipient's app sends a "read" receipt |

The key insight: a **receipt is just another tiny message flowing the other way.** When the recipient's phone gets your message, it sends a "delivered" note back through the exact same path (gateway → bus → your gateway → your phone), which flips your ✓ into ✓✓.

```java
// On the RECIPIENT's device: as soon as a message lands, fire a "delivered" receipt back.
void onMessageReceived(Message m) {
    render(m);
    sendReceipt(new Receipt(m.conversationId(), m.seq(), "DELIVERED", myUserId));
}

// Later, when the user actually opens that chat, fire a "read" receipt.
void onChatOpened(String conversationId, long uptoSeq) {
    sendReceipt(new Receipt(conversationId, uptoSeq, "READ", myUserId));
}

// On the SERVER: a receipt updates the DB, then is routed to the ORIGINAL sender.
@Component
public class ReceiptService {
    public void onReceipt(Receipt r) {
        // record it: "user X marked msg seq Y as DELIVERED/READ"
        store.upsertReceipt(r.conversationId(), r.seq(), r.userId(), r.status(), now());

        // tell the original sender so their UI updates ✓ → ✓✓ → blue
        long senderId = store.senderOf(r.conversationId(), r.seq());
        router.route(new Message(r.conversationId(), senderId,
                                 "RECEIPT", r));   // same routing as a normal message
    }
}
```

#### Q: Why is "delivered" (✓✓) separate from "read" (blue)? Isn't reaching the phone the same as being read?

No — that gap is the whole point. **✓✓** means the message *arrived on the device* (the app received it in the background, phone buzzed). **Blue** means the human actually *opened the chat and looked*. Your phone can receive 20 messages while in your pocket (all ✓✓) without you reading any of them (none blue yet). That's why "delivered" is reported automatically by the app on receipt, while "read" waits for you to open the conversation.

### What happens if you turn off read receipts

Then the client simply **doesn't send the "read" receipt** — the message still gets delivered (✓✓) and rendered, but no blue tick is ever emitted, so the sender never learns you read it. It's purely a client choice about whether to send that one little receipt message.

#### Q: Is delivery at-least-once or exactly-once? How do I get "in-order, no duplicates"?

The wire is **at-least-once** — true **exactly-once delivery is impossible** over an unreliable network (the sender can never be sure its ack wasn't the thing that got lost, so it must be allowed to retry). We *simulate* exactly-once at the application layer with two independent guarantees:

- **No duplicates** → **idempotent dedup by `message_id`** (the UUID the sender minted once). The server ignores a re-send of an id it already stored; the receiving app ignores a re-push of an id it already rendered. Retries are therefore free.
- **In order** → **per-conversation `seq` + per-partition ordering** (§8, §9). All of one conversation's messages live on one partition (`partition by conversation_id`), so they're written and read back in a single monotonic `seq` order; the client renders by `seq` and buffers gaps.

> 💡 Put together: **at-least-once transport + idempotent apply + per-conversation ordering = "exactly-once, in-order" from the user's point of view.** You never *achieve* exactly-once on the wire; you make duplicates harmless and order deterministic.

### Delete-for-everyone (message revoke)

"Delete for everyone" isn't a remote wipe — you can't reach into someone's phone and erase data. It's **another message**: a small **REVOKE control message** referencing the original's `message_id`, sent through the *same* pipeline (§5, §6), which each client **cooperatively** obeys.

```
Bob taps "Delete for everyone" on message_id = M123
  → app sends { type:"REVOKE", conversationId, targetMessageId:"M123" }  (encrypted like any msg)
  → server routes it like a normal message (online push / offline store + notif)
  → if M123 is still UNDELIVERED in the store, server drops that pending copy
  → each recipient device finds local M123, deletes it, renders "This message was deleted"
  → also self-fanned-out to Bob's OTHER devices (§13) so it's consistent everywhere
```

Two cases for the original: if **already delivered**, it now lives in the recipient's **local DB** (§9) — only the *client* can remove it, hence "cooperative"; if **still undelivered**, the server simply drops the pending ciphertext before delivery.

⚠️ It's **best-effort and client-enforced**: a modified client could ignore the REVOKE, and someone may have already seen a notification preview or screenshot. Time limits also apply. Contrast **"delete for me"**, which is purely local — it removes the message from your own device and sends nothing. Under E2E the server routes REVOKE by its (unencrypted) `message_id`/`conversationId` metadata without ever reading content. **Message edit** works the same way: an edit control message referencing `message_id`, applied cooperatively by clients.

---

## 8. Ordering

- **Per-conversation ordering** via a **server-assigned monotonic `seq`** per conversation (or timestamp + tiebreak).
- Client renders by `seq`; **buffers out-of-order arrivals** until the gap fills.
- **No global ordering** across conversations — unnecessary and unscalable.
- For groups, the `seq` is per-group (assigned by the group's partition/coordinator).

### Per-conversation sequence numbers

Messages can arrive out of order (different network paths), e.g. as 3, 1, 2, 5, 4. The server stamps each message in a conversation with an ever-increasing **`seq`** (1, 2, 3, ...), and the receiver reorders by `seq` regardless of arrival order.

```java
// The server hands out a strictly increasing seq PER conversation.
long seq = seqGenerator.next(conversationId);   // conv 7: 1,2,3...  conv 9: 1,2,3... (independent)
```

On the receiving phone, render by `seq`, and if a gap appears (got 5 but not 4 yet), **hold** the later ones until the gap fills:

```java
class ConversationView {
    long lastShown = 0;                          // highest seq displayed so far
    TreeMap<Long, Message> buffer = new TreeMap<>();  // out-of-order arrivals, sorted by seq

    void onMessage(Message m) {
        buffer.put(m.seq(), m);
        // show messages only while they're contiguous — no skipping over a missing seq
        while (buffer.containsKey(lastShown + 1)) {
            display(buffer.remove(lastShown + 1));
            lastShown++;
        }
        // if seq 4 is missing, seq 5+ just wait in the buffer until 4 arrives
    }
}
```

### Why per-conversation ordering, not one global order for everything

You only care that **one chat** reads in order — messages within your conversation with Alice must line up. You do **not** care whether your message to Alice is "before" or "after" someone else's unrelated message to Bob. Enforcing a single global counter across billions of messages would need one coordination bottleneck for the whole planet — impossible to scale, and pointless. Per-conversation `seq` keeps ordering exactly where it matters and lets different conversations run fully in parallel.

#### Q: Two people send at "the same time" — whose message is first?

Whoever's message the server assigns the next `seq` to first. The `seq` is decided **at the server**, not by wall-clock time on each phone (phone clocks drift and can't be trusted). So there's always a single, definite order per conversation, and both people's apps will show that same order once they sync.

---

## 9. Offline Delivery & Message Store

```
Recipient offline:
  message stays in store as undelivered + push notification sent (APNS/FCM)
On reconnect:
  client sends its last-synced seq per conversation
  server streams all messages after that seq → client catches up → mark delivered
```

- **Message store:** wide-column (**Cassandra/HBase**) — partition by `conversation_id`, cluster by `seq` → "give me messages after seq X" is a fast range scan on one partition. LSM = write-optimized (perfect for message firehose).
- **Retention:** WhatsApp (E2E) **deletes from the server once delivered to all devices**; Slack/Messenger **retain full history** server-side → bigger store + search index.
- **Undelivered queue** can be derived from `messages` where `seq > device.last_delivered_seq`.

### Offline delivery and catch-up

If the recipient is offline, the message isn't discarded — it stays in the store as undelivered, and a **push notification** (APNS/FCM) nudges the device. On reconnect, the phone collects everything it missed.

The catch-up is simple: the phone remembers **the last `seq` it has (42)** and asks the server for everything after that.

```
Recipient offline → message already stored (undelivered) + push notif fired
Recipient reconnects → "my last_delivered_seq for conv 7 is 42"
Server → give me messages in conv 7 WHERE seq > 42 → stream them → mark delivered
```

#### Why a wide-column store, and the actual query

We store messages in a **wide-column** database (Cassandra/HBase) laid out so all of one conversation's messages sit **together, sorted by `seq`**. That makes "everything after seq 42" a single fast **range scan** over a contiguous slice, instead of scanning the whole table.

```sql
-- Layout: PARTITION by conversation (all its messages on one node, together)
--         CLUSTER (sort on disk) by seq (so ranges are contiguous & fast)
CREATE TABLE messages (
    conversation_id BIGINT,
    seq             BIGINT,      -- the per-conversation sequence number (§8)
    message_id      UUID,        -- client-generated, for dedup
    sender_id       BIGINT,
    ciphertext      BLOB,
    created_at      TIMESTAMP,
    PRIMARY KEY (conversation_id, seq)   -- partition key = conversation_id, sort key = seq
);

-- Offline catch-up = ONE fast range scan on ONE partition:
SELECT seq, message_id, sender_id, ciphertext, created_at
FROM   messages
WHERE  conversation_id = 7
  AND  seq > 42               -- everything the device hasn't seen
ORDER  BY seq ASC;
```

The server code around that query:

```java
@Component
public class SyncService {

    // called when a device reconnects and reports where it left off
    public void resync(String deviceId, long conversationId, long lastDeliveredSeq) {
        // pull only the missed messages — a contiguous range, cheap to read
        List<StoredMessage> missed =
                store.rangeScan(conversationId, /* afterSeq= */ lastDeliveredSeq);

        for (StoredMessage m : missed) {
            gateway.pushToDevice(deviceId, m);   // stream them down in seq order
        }

        // advance this device's cursor so we don't resend next time
        store.setLastDeliveredSeq(deviceId, conversationId, missed.isEmpty()
                ? lastDeliveredSeq
                : missed.get(missed.size() - 1).seq());
    }
}
```

- **Why LSM / write-optimized?** Chat is a firehose of *writes* (100B messages/day). Wide-column stores use LSM trees, which turn writes into fast sequential appends — ideal for this.
- **`last_delivered_seq` per device** is a cursor marking how far that device has received. Each of your devices (phone, laptop) has its own cursor, so each catches up independently (§13).

### Where offline messages actually "queue"

Usually **no separate queue** table — the "undelivered queue" is just a *view* of the message store: for a given device, it's `messages WHERE seq > that device's last_delivered_seq`. The messages are already stored durably (§6 persists before ack), so "undelivered" simply means "the device's bookmark hasn't reached them yet." Deliver = push them + advance the bookmark.

#### Q: WhatsApp deletes messages after delivery — so where's my history?

On your **devices**, not the server. Because WhatsApp is end-to-end encrypted (§12), the server can't read your messages and deletes the ciphertext once every device has received it. Your chat history lives in your phone's local database (and encrypted backups). Slack/Messenger are different: they keep history **server-side** (so you can search it and load it on any fresh device), which is why they need a much bigger store plus a search index.

### What the push notification is for if the message is already stored

The stored message just sits there silently — the phone won't know to reconnect and grab it. The push notification (APNS on iOS, FCM on Android) is the external nudge: it wakes the phone/app enough to alert the user and trigger a reconnect + resync. Without it, an offline user wouldn't see the message until they happened to open the app.

#### Q: If history lives on devices, why keep a server `messages` table at all?

Because the table is a **durable in-transit buffer, not the archive.** It holds a message safely between "sender sent it" and "every device received it" — which can be days if the recipient is offline. Three jobs, none of which are long-term history:

1. **Persist-before-ack (§6)** — the durable write that lets us ack "✓ sent" without risking loss *is* this table.
2. **Offline delivery (§9)** — a message to an offline user has to wait *somewhere*; it waits here, and the device range-scans it on reconnect.
3. It stores **ciphertext** the server can't read, and in the E2E model it's **deleted once delivered to all devices** (retention above).

So think **durable outbox/queue**, not log. Permanent, readable history then lives on **devices**. The identical schema in Slack/Messenger simply **retains** the rows → the table *becomes* the searchable server-side history (bigger store + index). Same table, different retention policy.

---

## 10. Group Chat & Fan-out

```
Group message → per member: route to online devices, queue for offline
  write-fan-out : write into each member's inbox/timeline (fast reads, costly for huge groups)
  read-fan-out  : store once in the group log; members read (cheap write, heavier read)
```

| Approach | Trade-off |
| --- | --- |
| **Fan-out on write** | Fast per-user reads; expensive for large groups |
| **Fan-out on read** | Cheap write; heavier read; good for large groups |
| **Hybrid** | Small groups write-fan-out; large groups read-fan-out |

- Very large groups → **async, batched** fan-out; never block the sender on N deliveries.
- **Encrypted groups** use **Sender Keys** (§12) so the sender encrypts once, not once per member.
- See the [Fan-Out / Fan-In](../concepts/fan-out-fan-in.md) note for the general pattern + celebrity/huge-group problem.

### One message, many recipients (fan-out)

One message must reach many members. Two ways to handle it:

- **Fan-out on write** — write a copy into **each member's inbox** now. Reads are instant later, but the write does N copies of work up front. Great for small groups; brutal for a 5,000-person group.
- **Fan-out on read** — write the message **once** into the shared group log. No per-member copies; each member reads the log when they catch up. Cheap write, a bit more work per reader. Great for huge groups.

```java
public void deliverGroupMessage(GroupMessage gm) {
    long seq = seqGenerator.next(gm.groupId());          // ONE per-group seq (§8)
    store.appendToGroupLog(gm.groupId(), seq, gm);       // store once in the group's log

    List<Member> members = groupMembers(gm.groupId());

    if (members.size() <= SMALL_GROUP_LIMIT) {
        // FAN-OUT ON WRITE: push into each member's delivery path right now
        for (Member m : members) {
            if (m.userId() == gm.senderId()) continue;   // don't echo to the author's origin device
            router.route(toMessage(gm, seq, m.userId())); // online → push; offline → notif (§9)
        }
    } else {
        // FAN-OUT ON READ: the message is already in the group log.
        // just nudge members; each catches up by range-scanning the group log on its own.
        for (Member m : members) {
            notifier.nudge(m.userId(), gm.groupId(), seq); // light "there's something new" ping
        }
    }
}
```

| Approach | You do… | Reader does… | Best for |
| --- | --- | --- | --- |
| **Fan-out on write** | N copies now | just read own inbox | small groups |
| **Fan-out on read** | 1 write | scan the shared group log | large groups |
| **Hybrid** | small→write, large→read | either | real systems |

### Why not always fan-out on write, since it makes reads so fast

For a **10,000-member** group, one message would trigger 10,000 writes/pushes — and if 50 people are chatting, that's half a million deliveries a second for a single group. The sender would be blocked forever and the store would melt. So for big groups we **write once** to the group log and let members pull. Real systems go **hybrid**: fan-out on write for small groups (fast, cheap enough), fan-out on read for large ones.

### "Never block the sender" — what that means

The sender should get their ✓ the instant the message is **stored**, not after all N members receive it. So fan-out happens **asynchronously and in batches** *after* acking the sender. Whether a group has 3 or 3,000 members, the sender's "sent" is equally fast; the deliveries fan out in the background.

### How groups stay in order if everyone's sending

Same trick as 1:1 (§8), but the **`seq` is per-group**, assigned by the group's coordinator/partition. Everyone's messages funnel through that one sequencer, so every member ends up displaying the identical order. And encrypted groups use **Sender Keys** (§12) so the sender encrypts the message **once** rather than N times — otherwise big-group encryption would be as expensive as the naive fan-out.

---

## 11. Presence & Typing Indicators

```
Presence: on connect → SET presence:user:{id}=online (Redis, TTL); refresh on heartbeat
          disconnect / TTL expiry → offline + record last_seen
Typing:   ephemeral event pushed to the other party; never stored
```

- Presence is **high-churn + ephemeral** → Redis, not the DB.
- **Fan out presence only to interested parties** (open chats / contacts currently viewing) to avoid an N² storm when millions come online.

### The "online" dot and "last seen"

Presence is an **online flag with a short expiry (TTL)**: on connect (and on every heartbeat) the flag is set with a short TTL, so it stays "online" only while heartbeats keep refreshing it. When heartbeats stop, the TTL lapses and the user flips to offline, and a **last_seen** timestamp records when that happened.

```java
@Component
public class PresenceService {

    private static final Duration TTL = Duration.ofSeconds(30);

    // on connect (and on every heartbeat): set online with a SHORT expiry
    public void markOnline(String userId) {
        redis.setEx("presence:user:" + userId, "online", TTL);  // auto-expires in 30s
    }

    // heartbeats (every ~15s) just re-set the key so it never expires while connected
    public void onHeartbeat(String userId) {
        redis.setEx("presence:user:" + userId, "online", TTL);
    }

    // clean disconnect: drop online + stamp last_seen
    public void markOffline(String userId) {
        redis.del("presence:user:" + userId);
        redis.set("last_seen:user:" + userId, String.valueOf(System.currentTimeMillis()));
    }

    // reading someone's status
    public Presence get(String userId) {
        if (redis.exists("presence:user:" + userId)) return Presence.online();
        long ts = redis.getLong("last_seen:user:" + userId);   // when the TTL last lapsed / clean exit
        return Presence.lastSeen(ts);
    }
}
```

The subtle part: mobile connections die **silently** (subway, dead battery) with no "goodbye" packet, so we can't rely on `markOffline` being called. The **TTL is the safety net** — if heartbeats stop, the `presence:` key simply expires on its own within 30s and the user flips to offline automatically.

### Why Redis and not the main database for presence

Presence is **high-churn and disposable**. Millions of people flip online/offline constantly — that's a storm of tiny, short-lived writes. A durable SQL database would choke and it'd be pointless to persist "online" (it's stale in seconds). Redis is in-memory, blazing fast, and has **TTL built in** (the auto-expiry above). We only persist the occasional `last_seen` value durably.

### What the N² storm is, and why we "fan out only to interested parties"

Imagine 100 million people come online at 8am. If we notified **every contact of every user**, that's each person's presence change pushed to hundreds of contacts × millions of people = a catastrophic flood (roughly N² messages). Instead, we only tell the people who are **actually looking right now** — someone with your chat open, or viewing your contact. Everyone else finds out your status lazily, the next time they open your chat. Same idea for **typing indicators**: a pure ephemeral "Bob is typing…" event routed only to that conversation's other participants, and **never stored** at all.

### Why you see "typing…" before opening the chat

A subtlety: WhatsApp shows "typing…" (and voice-recording) in the **chat-list row**, before you open the chat. So the trigger to *display* it isn't "the chat screen is open" — it's simply that your app **received a typing event for a conversation you're in**, and it renders that in whatever view you're on. **Delivery** is to the conversation's participants; **display** is a client choice.

This clears up an easy conflation — typing is **not** the same fan-out problem as presence:

| | **Typing** | **Presence broadcast** |
| --- | --- | --- |
| Sent to | Participants of **one conversation** | Potentially **all your contacts** |
| 1:1 fan-out | Exactly **1** person → **no N² risk** | Hundreds → **real N² risk** |

So for **1:1**, typing goes to exactly one person and is freely shown in the list — no reason to gate on "chat open." The "fan out only to interested parties" rule genuinely bites for **presence** (the true N² case) and for **large-group typing**, where apps *do* throttle hard / limit to active viewers. It is not a real constraint for 1:1 typing.

Delivery of a typing event still rides the same **registry lookup + inter-gateway bus + `pushToDevice`** path as a real message (§5) — the only difference is the "persist to store" step is skipped entirely. The sender **throttles** it (once every few seconds, not per keystroke) and the receiver **auto-hides** it on a ~5s timeout, so a lost STOP event self-heals.

---

## 12. End-to-End Encryption (Signal protocol)

WhatsApp uses the **Signal protocol** — the server **routes ciphertext it cannot read**.

### Key building blocks

| Piece | Role |
| --- | --- |
| **Identity key** | Long-term key pair per user/device |
| **Prekeys** | Batches of one-time public keys uploaded to the server so others can start a session while you're offline |
| **X3DH** (Extended Triple Diffie-Hellman) | Initial key agreement → a shared secret between two devices |
| **Double Ratchet** | Derives a **new key per message** → **forward secrecy** (a leaked key can't decrypt past msgs) + break-in recovery |

```
1. Each device publishes its identity key + a batch of prekeys to the server (public keys only)
2. Sender fetches recipient's prekey bundle → X3DH → shared session secret
3. Double Ratchet advances keys per message → encrypt with a fresh message key
4. Server stores/forwards ONLY the ciphertext; it never has the private keys
```

- **Server can't read messages** — it only sees **metadata** (who talks to whom, when, sizes) and routes ciphertext.
- **Group encryption = Sender Keys:** each member generates a *sender key*, distributes it (pairwise-encrypted) to the group once; then encrypts each group message **once** with its sender key → recipients decrypt with the stored sender key. Avoids encrypting N times per message.
- **Trade-off:** true E2E makes **server-side search/history impossible** (server can't read content) → history lives on devices; multi-device needs careful key sharing (§13).

### The pieces in plain language (analogies)

The jargon is the scary part; the ideas are simple. First encounter with each term below gets an everyday analogy:

- **Prekeys** 💡 — think of a stack of **pre-addressed, sealed envelopes** you leave at the front desk (the server). Anyone can grab one and send you a secret message *even while you're asleep* — they don't need you online to start a conversation. Each envelope is single-use.
- **X3DH** (Extended Triple Diffie-Hellman) 💡 — the **handshake that agrees on a shared secret** without ever sending it. Like two people mixing paints: each keeps a private color, they exchange public colors, and both mix to the *same* final shade — but an eavesdropper who saw only the public colors can't reproduce it. It combines several key pairs (identity + prekeys) so a session can be set up from just the recipient's published bundle.
- **Double Ratchet** 💡 — a **key that changes with every single message**, like a rotating one-time pad. Two "ratchets" click forward (one per message, one per reply) so each message uses a fresh key. Result: **forward secrecy** (stealing today's key can't decrypt yesterday's messages) and **break-in recovery** (it self-heals on the next exchange).
- **Sender Keys** 💡 — for groups: instead of re-encrypting a message once *per member*, you hand each member a **shared group "decoder" key** (delivered privately, once), then broadcast **one** ciphertext everyone can open. Like giving each guest a copy of the same house key rather than escorting each one in individually.

#### Q: If it's end-to-end encrypted, what can the server *still* see?

Plenty of **metadata**, just not content. The server routes ciphertext, so it can see **who is talking to whom, when, how often, message sizes/timing, who's in which group, online/last-seen, and IP/device info** — everything except the actual words, which only the endpoints can decrypt. ⚠️ E2E protects *message content*, not *the fact that you messaged someone*. That metadata is exactly what powers routing, receipts, and presence — and it's the honest caveat to state in an interview.

#### Q: What happens if a user's one-time prekeys run out?

Prekeys are consumed one-per-new-session, so a popular account can exhaust the uploaded batch before the client re-uploads more. To avoid ever being un-messageable, each device also publishes a **signed "last-resort" prekey** that the server can hand out **repeatedly** when the one-time stack is empty. A session can still be established; it's just slightly weaker (that one key is reused until the client tops up its batch). Clients replenish the one-time prekeys whenever they come online, so the shared stack normally stays full.

#### Q: In a group, how do we avoid N separate encryptions per message (Sender Keys)?

Naively, encrypting a group message means running the pairwise (Double Ratchet) encryption **once for every recipient** — a 256-member group = 256 encryptions per message. **Sender Keys** fix this: each sender generates one **sender key** and distributes it **once** to every member over the existing pairwise-encrypted channels. After that, the sender encrypts each message **a single time** with its sender key and the server fans out that **one** ciphertext to everyone. The expensive per-member work happens once at setup, not on every message.

### Group membership changes → Sender Key rotation

When the group roster changes, sender keys must be re-managed so the right people (and only them) can read new traffic:

- **Add a member** → each existing member sends **their current sender key** to the new member (pairwise-encrypted). The newcomer can now decrypt subsequent messages — but **not** past ones (it never had the earlier keys), which is the desired behavior.
- **Remove a member** ⚠️ → every remaining member must **rotate (regenerate) their sender key** and redistribute it to the *remaining* members. Otherwise the removed member, who still holds the old sender key, could keep decrypting new messages. This "rotate on removal" step is the group-chat equivalent of changing the locks when someone leaves.

#### Q: If the Sender Key is shared with members, can't the server read it from the DB and decrypt?

No — and this is the crux of E2E. The keys uploaded to the server are **public keys only**; **private keys never leave the device**, so the server can hold every public key and still decrypt nothing (public key encrypts, only the matching private key decrypts). Two more points close the gap:

- **1:1 has no transmitted key at all** — X3DH *derives* a shared secret on both devices from public material (the "mixing paints" analogy above). A middleman sees only the public colors and can't reproduce the secret.
- **The Sender Key *is* distributed, but "pairwise-encrypted"** — wrapped inside each existing 1:1 encrypted channel before sending. The server relays only **ciphertext** of the sender key; to steal it you'd first have to break the pairwise session, which needs a private key that never left a device.

⚠️ In true E2E the sender key is **not** stored server-side in readable form (it lives on member devices). The `group_metadata.sender_keys` column in §15 is therefore misleading — in a real design it would be absent or hold only **opaque/encrypted** blobs. Net: a compromised server still can't read messages, because decryption needs private keys it never possessed.

### Design fork — server-side (Slack) vs E2E (WhatsApp)

Whether the server can read content is the single biggest fork in this design. It's worth stating explicitly which product you're building:

| Aspect | **E2E (WhatsApp/Signal)** | **Server-side encryption (Slack/Messenger default)** |
| --- | --- | --- |
| Who can read content | **Only the endpoints** | Server can (encrypted at rest, but service holds keys) |
| Server-side search | ❌ Impossible (nothing readable) | ✅ Full-text search index |
| History on a fresh device | From device transfer / encrypted backup | ✅ Loads from server instantly |
| Message store retention | **Delete ciphertext once delivered** to all devices → tiny | **Retain full history** → big store + search index |
| Multi-device | Hard — per-device keys + key sharing (§13) | Easy — server just serves history to any device |
| Compliance/moderation | Limited (can't scan content) | Server-side scanning possible |

> 💡 The trade is **privacy vs. features**: E2E buys ironclad confidentiality but forfeits server-side search, easy multi-device, and cheap history restore. Name the product early so the interviewer knows which set of constraints you've signed up for.

---

## 13. Multi-Device Sync

A user has phone + laptop + tablet — all must show the same conversations.

| Concern | Approach |
| --- | --- |
| **Each device = its own identity** | Every device has its own key pair; a message is encrypted **per recipient device** (fan-out per device) |
| **Companion devices** | WhatsApp's multi-device: linked devices work without the phone online; each maintains its own Signal session |
| **History sync** | On linking a new device, transfer recent history (encrypted device-to-device or via an encrypted backup) |
| **Consistent read state** | `last_read_seq` synced across devices so unread counts match everywhere |
| **Send from any device** | Message also delivered back to the sender's **other devices** (self-fan-out) to keep them in sync |

> **Interview point:** multi-device turns 1:1 into **device-level fan-out** — encrypt/deliver per device, sync read state, and keep each device's Signal session. It's why E2E multi-device is genuinely hard.

### Linking a new device (the sequence)

Each device is its **own** Signal identity, so linking a laptop isn't "log in" — it's "enroll a new endpoint and catch it up":

```
1. LINK        laptop shows a QR; phone scans it → authenticates the new device to the account
2. REGISTER    laptop generates its own identity key + prekeys → uploads them to the server
               → other people's clients now encrypt to this device too (per-device fan-out)
3. HISTORY     recent history transferred to the laptop (encrypted device-to-device,
               or restored from an encrypted backup) — the server never sees plaintext
4. SELF-FANOUT from now on, every NEW message (sent to OR by any of the user's devices) is
               also encrypted + delivered to all the user's OTHER devices → they stay in sync
5. READ STATE  last_read_seq syncs across devices so unread badges match everywhere
```

The key mental shift: a "user" is really a **set of devices**, and every message the user is party to must be fanned out to **each** of those devices (each with its own encrypted copy).

#### Q: My phone is off — can my laptop still receive messages?

Yes, on WhatsApp's modern **multi-device** design: each linked device holds its **own** Signal session and is a first-class endpoint, so senders encrypt directly to the laptop's keys and the server routes to it independently. The phone does **not** have to relay. (Older "companion mode" tethered everything to the phone — if the phone was offline, linked devices couldn't sync. The move to independent per-device sessions is exactly what removed that limitation.) Whichever devices are offline just **resync from their own `last_delivered_seq`** (§9) when they reconnect.

---

## 14. Media / Attachments

```
1. Sender requests an upload URL → uploads (E2E-encrypted) media to blob store (S3) via pre-signed URL
2. Message carries a media REFERENCE + decryption key (in the E2E payload), not the bytes
3. Recipient downloads ciphertext from blob/CDN → decrypts locally
```

- Large binaries stay **off the messaging path**; CDN delivers; thumbnails/blurhash generated (client-side for E2E).
- Media is encrypted with a per-blob key carried inside the encrypted message.

---

## 15. Data Model (all tables)

```sql
CREATE TABLE users ( user_id BIGINT PRIMARY KEY, phone VARCHAR(20) UNIQUE, name TEXT, last_seen TIMESTAMP );
CREATE TABLE devices (
    device_id BIGINT PRIMARY KEY, user_id BIGINT, push_token TEXT, platform VARCHAR(10),
    identity_key TEXT, last_delivered_seq BIGINT DEFAULT 0        -- per-device sync cursor
);
CREATE TABLE prekeys ( device_id BIGINT, key_id INT, public_key TEXT, used BOOLEAN DEFAULT FALSE,
                       PRIMARY KEY (device_id, key_id) );        -- one-time prekeys for X3DH

CREATE TABLE conversations ( conversation_id BIGINT PRIMARY KEY, type VARCHAR(10), created_at TIMESTAMP ); -- DIRECT/GROUP
CREATE TABLE conversation_members (
    conversation_id BIGINT, user_id BIGINT, role VARCHAR(10),
    last_read_seq BIGINT DEFAULT 0, PRIMARY KEY (conversation_id, user_id)
);

-- Messages: wide-column, partition by conversation_id, cluster by seq (range scans for sync)
CREATE TABLE messages (
    conversation_id BIGINT, seq BIGINT, message_id UUID, sender_id BIGINT,
    type VARCHAR(20), ciphertext BLOB, created_at TIMESTAMP,
    PRIMARY KEY (conversation_id, seq)
);
CREATE TABLE message_receipts (
    conversation_id BIGINT, seq BIGINT, user_id BIGINT, status VARCHAR(12), at TIMESTAMP,
    PRIMARY KEY (conversation_id, seq, user_id)      -- DELIVERED / READ
);
CREATE TABLE group_metadata ( conversation_id BIGINT PRIMARY KEY, name TEXT, admin_ids BIGINT[], sender_keys JSONB );
-- ⚠️ Under true E2E, sender_keys are CLIENT-HELD; the server would store only opaque/encrypted blobs (or omit the column) — never readable keys (§12).
CREATE TABLE blocked_users ( user_id BIGINT, blocked_id BIGINT, PRIMARY KEY (user_id, blocked_id) );

-- Ephemeral (Redis):
--   conn:user:{id}     → [{device, gatewayNode, connId}]     (connection registry)
--   presence:user:{id} → online (TTL, heartbeat-refreshed)
```

> **Tables to consider:** users, devices, prekeys, conversations, conversation_members, messages, message_receipts, group_metadata, blocked_users. Connection/presence = Redis; media = blob/CDN.

### Database & storage choices (which DB, and why at scale)

No single database fits every job here, so we use **polyglot persistence** — pick the store that matches each data type's access pattern. The deciding question for the message data is *"is this a write-heavy, append-only firehose, or a low-volume relational lookup?"* Messages are the firehose; everything else is a lookup.

| Data | Store | Why this one | Why not the alternative |
| --- | --- | --- | --- |
| Messages (the firehose) | **Wide-column** (Cassandra/ScyllaDB) | Partition by `conversation_id`, cluster by `seq` → "give me everything after seq X" is one contiguous range scan on one partition; the LSM-tree write path absorbs ~1.15M msg/sec of pure appends (§3). | An RDBMS row-store chokes on this write volume — every insert also maintains B-tree indexes, and once one table gets this hot, vertical scaling runs out. Wide-column's horizontal partitioning is built for exactly this shape. |
| Users, devices, prekeys, conversations, conversation_members, message_receipts, group_metadata | **RDBMS** (PostgreSQL/MySQL) | Low-volume, relational lookups with joins ("who's in this group," "what's my last-read seq") — a normal transactional model is simplest here, and none of it sees firehose write pressure. | Forcing everything into wide-column just for consistency loses real joins/transactions on data that's naturally relational and doesn't need write-scale. |
| Connection registry + presence | **Redis** | High-churn, ephemeral, TTL-based (§4, §11) — millions of connect/disconnect/heartbeat events per second that are stale within seconds. In-memory + built-in expiry is exactly the shape needed. | A durable DB would be pointless write load for data nobody needs to recover, and it can't auto-expire cheaply at this churn rate. |
| Media (images/video/voice) | **Blob store + CDN** (S3/CloudFront) | Large binary payloads served from the edge, keeping big bytes off the message-store hot path. | Storing media bytes in the message store would bloat partitions and wreck the append-only write pattern that makes wide-column fast. |

**Why wide-column wins for messages at this scale:** the access pattern is always "append the newest message" and "range-scan everything after seq X for one conversation" (§9) — never an ad-hoc filter across conversations. That maps perfectly onto **partition by `conversation_id`, cluster by `seq`**: writes land on the tail of one partition (a cheap LSM append) and catch-up reads are a contiguous scan. Scale further by adding nodes — conversations spread across the cluster by partition key, so one hot group chat is isolated to its own partition instead of contending with the rest of the table. (See [Databases — Deep Dive](../concepts/databases-deep-dive.md).)

### A tour of the tables

Here's what each table stores and the one detail that makes it tick:

| Table | What it stores | Key detail |
| --- | --- | --- |
| `users` | who you are (phone, name) | `last_seen` snapshot |
| `devices` | each gadget you own | `last_delivered_seq` = that device's catch-up bookmark (§9) |
| `prekeys` | one-time keys so people can message you while offline | consumed during X3DH (§12) |
| `conversations` | a chat's identity (DIRECT or GROUP) | just the container |
| `conversation_members` | who's in a chat + their read cursor | `last_read_seq` drives unread counts |
| `messages` | the actual messages | partition by `conversation_id`, sort by `seq` → fast range scans |
| `message_receipts` | who delivered/read which message | one row per (message, user) |
| `group_metadata` | group name, admins, sender keys | `sender_keys` for one-encrypt groups |
| `blocked_users` | who blocked whom | simple pair lookup |

The three "cursors" are the heart of correctness — they're all just **`seq` values** (§8) pointing into the `messages` table:

```
messages.seq              → the sequence number stamped on each message
devices.last_delivered_seq → "this device has received up to seq N"      (drives offline sync, §9)
conversation_members.last_read_seq → "this user has read up to seq N"    (drives unread badges + blue ticks)
```

```sql
-- Unread count for a user in a conversation = messages past their read cursor
SELECT COUNT(*)
FROM   messages m
JOIN   conversation_members cm
       ON cm.conversation_id = m.conversation_id
WHERE  m.conversation_id = 7
  AND  cm.user_id = 99
  AND  m.seq > cm.last_read_seq;   -- everything after where they last read
```

### Block / unblock (using `blocked_users`)

Blocking is a **routing filter**, not a delete. **Block** = insert `(user_id, blocked_id)`; **unblock** = delete that row. On the send path, before routing a message from A to B, the server checks "has B blocked A?" — if so it silently **accepts and drops** it: A still sees a single ✓ (it was stored), but it's never delivered, so no ✓✓ ever appears and B is never notified. That asymmetry is deliberate — the blocker's privacy is preserved and the blocked user isn't told they've been blocked.

```java
if (blockedUsers.isBlocked(/* blocker= */ recipientId, /* blocked= */ senderId)) {
    return new Ack(clientMsgId, seq, "SENT");   // accept + drop; never route to B
}
```

⚠️ Enforce blocking **server-side**, not just by hiding messages on the client — otherwise a modified client could bypass it. Presence/typing to the blocked party are suppressed the same way.

### Why `messages` is a wide-column table but the rest look like normal SQL

`messages` is the **firehose** — billions of writes a day, and the hot query is always "give me a conversation's messages after seq X." Partitioning by `conversation_id` and sorting by `seq` makes that a single contiguous read, and wide-column stores (Cassandra/HBase) absorb massive write volume via LSM trees. The other tables (`users`, `devices`, membership) are **low-volume, relational lookups** — a normal relational model is clearer there. The SQL shown is illustrative; a real deployment mixes a wide-column store for `messages` with a relational/KV store for the metadata.

#### Q: Why store `ciphertext` and not the text? Where's the plaintext?

Because of end-to-end encryption (§12): the server only ever holds the **encrypted blob** and literally cannot read it. The readable text exists only on the sender's and recipient's devices after they decrypt locally. That's also why some columns you might expect (message *text*, search index) don't exist server-side in the E2E design — there's nothing readable to index.

---

## 16. API / Protocol

```
WS connect  /v1/ws                          # persistent; auth + device key on handshake
→ send:     { type:"MSG", clientMsgId, conversationId, ciphertext }
← ack:      { type:"ACK", clientMsgId, seq, status:"SENT" }
← receive:  { type:"MSG", conversationId, seq, senderId, ciphertext }
→ receipt:  { type:"DELIVERED"|"READ", conversationId, seq }
← presence: { type:"PRESENCE", userId, status }
REST:  GET /v1/conversations
       GET /v1/conversations/{id}/messages?afterSeq=      # offline sync
       POST /v1/media/upload-url
       POST /v1/keys/prekeys        GET /v1/keys/{userId}/bundle   # E2E key exchange
```

---

## 17. Sequences

### 1:1 online (cross-gateway)

```
Sender  GatewayA  ChatSvc  Store  Registry  GatewayB  Recipient
  │ MSG   │         │        │       │         │          │
  ├──────►├────────►│ persist│       │         │          │
  │       │         ├───────►│ (SENT)│         │          │
  │◄─ ACK(seq) ─────┤        │       │         │          │
  │       │         ├─ lookup recipient ──────►│          │
  │       │         ├─ route via bus ─────────►│ push ───►│
  │       │         │◄──────── DELIVERED receipt ─────────┤
  │◄─ ✓✓ ───────────┤        │       │         │          │
```

### Offline → reconnect sync

```
msg persisted (undelivered) → push notification (APNS/FCM)
... recipient comes online ...
Recipient → GatewayX: connect + "my last seq = 42"
ChatSvc → range scan messages WHERE conversation_id=? AND seq>42 → stream → mark DELIVERED
```

---

## 18. Consistency & Correctness

| Concern | Mechanism |
| --- | --- |
| No message loss | **Persist before ack** (at-least-once) |
| No duplicates | Client dedup by **`message_id`** |
| Ordering | Per-conversation **`seq`**; client buffers gaps |
| Exactly-once feel | at-least-once + idempotent client apply |
| Offline delivery | Undelivered kept in store; **resync from last seq** |
| Multi-device consistency | Per-device `last_delivered_seq` + `last_read_seq` sync |
| Gateway crash | Registry TTL expires; client reconnects + resyncs |
| Presence storms | Fan out only to interested parties |

---

## 19. Scaling & Failure

- **Gateways** scale horizontally; Redis **connection registry** so any node can route; ~1M conns/node.
- **Inter-gateway routing** via pub-sub bus; sender's node publishes, recipient's node pushes.
- **Message store** = wide-column, partitioned by conversation, replicated (LSM handles the write firehose).
- **Gateway crash** → clients reconnect elsewhere + resync from last seq; presence TTL clears.
- **No message loss** → persist before ack; client dedup; resync on reconnect.
- **Huge groups** → async batched fan-out (read-fan-out); Sender Keys for one-encrypt.
- **Hot conversation** → partition can be hot; keep partitions per-conversation and cache recent messages.

---

## 20. Interview Cheat Sheet

> **"How does a message reach an online recipient across servers?"**
> "Sender's gateway hands it to the Chat Service, which persists it (durability first) and acks 'sent'. It looks up the recipient in a Redis **connection registry** (user/device → gateway node) and routes the message over an **inter-gateway pub-sub bus** to the recipient's node, which pushes it down their WebSocket. Offline → keep in store + push notification, deliver on reconnect."

> **"No loss / no duplicates / ordering?"**
> "Persist before acking (at-least-once), dedup on the client by a client-generated `message_id`, and order by a per-conversation `seq` (client buffers gaps). On reconnect the client resyncs from its last seq."

> **"How does end-to-end encryption work?"**
> "Signal protocol: each device has identity keys + one-time prekeys on the server; sender does X3DH to establish a session, then the Double Ratchet derives a fresh key per message (forward secrecy). The server only routes ciphertext — it can't read content. Groups use Sender Keys so you encrypt once, not per member. The trade-off: no server-side history/search."

> **"Multi-device?"**
> "Each device has its own keys; messages are encrypted/delivered per device, and read state (`last_read_seq`) syncs across devices. It turns 1:1 into device-level fan-out."

> **"Millions of connections?"**
> "Stateful WebSocket gateways (~1M conns each) + a Redis registry so any node can locate a recipient; heartbeats detect dead conns; reconnect + resync on failure."

> **"Huge group messages?"**
> "Hybrid fan-out (read-fan-out for large groups), async + batched; per-group seq for order; Sender Keys so encryption is once, not per member."

### Tricky scenarios (rapid-fire)

| Scenario | What happens / what to do |
| --- | --- |
| **Celebrity / 100k-member group blasts a message** | Never fan-out-on-write to 100k inboxes synchronously → **read-fan-out**: write once to the group log, members pull on catch-up; **async + batched** nudges; the group's partition is a **hot partition** → isolate it (dedicated/partitioned sequencer) and cache recent messages. Encrypt **once** via Sender Keys (§12). |
| **User offline for a week, then reconnects** | No loss — messages sat durably in the store. Client sends its `last_delivered_seq` per conversation; server does a **range scan for `seq > cursor`** and streams the backlog in order (§9), then advances the cursor. A big backlog is just a bigger contiguous scan. |
| **Lost / changed device (re-install)** | New device = **new Signal identity**: it uploads fresh identity key + prekeys, so **sessions reset** and the contact's **safety number changes** (the "security code changed" warning ⚠️). History isn't on the server (E2E) → restore from encrypted backup / device transfer (§13). |
| **Duplicate delivery on retry** | Harmless by design — **dedup by `message_id`** (§6): the server skips storing an id it already has (idempotent ack) and the receiving app skips rendering an id it already showed. At-least-once wire, exactly-once on screen (§7). |
| **Recipient hops gateways mid-send** | Registry entry has a **short TTL + heartbeats** (§4); a push to the stale node fails, but the message is already persisted → delivered on resync. Nothing lost (§5). |

> **Ultimate layer model:** persist-before-ack = no loss · `message_id` dedup = no duplicates · per-conversation `seq` = order · registry + bus = routing · Signal/Sender Keys = privacy.

---

## 21. Consistency & CAP Tradeoffs

> Interviewers love: "Where do you choose consistency vs availability?" Chat's answer differs per data type.

| Path | Choice | Why |
| --- | --- | --- |
| **Message accept (durable write)** | **CP** (consistency/durability) | **Persist before ack** (§6) — we'd rather reject/retry a send than acknowledge a message we might lose. Durability wins over raw availability on the write. |
| **Ordering within a conversation** | **Strong, per-conversation** | A single monotonic `seq` per conversation (§8) gives one definite order; no global order (unnecessary and unscalable). |
| **Message delivery / offline sync** | **Eventually consistent** | At-least-once + dedup + resync-from-cursor (§7, §9) — a device converges to the full, in-order history whenever it reconnects. |
| **Presence / typing** | **AP** (availability + eventual) | High-churn, disposable, TTL-based in Redis (§11). Stale "online/last-seen" for a few seconds is fine; never block messaging on presence. |
| **Receipts (✓✓ / blue)** | **Eventual** | Receipts are just tiny messages flowing back (§7); they converge but aren't on the critical send path. |

- The message write is **strongly consistent and durable at the store**; everything user-visible around it (delivery, receipts, presence, multi-device state) is **eventually consistent** and self-heals on reconnect.
- There is **no global order** across conversations — ordering is strong exactly where it matters (one chat) and fully parallel everywhere else.

> One-liner: **"Strong durability + per-conversation order on the write path; eventual consistency for delivery, presence, and receipts — the client converges on reconnect."**

---

## 22. How to Drive the Interview (framework)

> Use this order so you never freeze. Spend ~5 min on 1–4, then go deep on 5–6.

1. **Clarify requirements** (functional + NFRs) and **name what's out of scope** — §2
2. **Estimate scale** (connections vs message throughput — the two cost centers) — §3
3. **Sketch the connection + routing model** (gateways, registry, inter-gateway bus) — §4, §5
4. **Walk the 1:1 send path**, leading with **persist-before-ack** — §6
5. **Deep dive: the hard parts** → reliable/ordered delivery, offline sync, groups/fan-out — §7–§10
6. **Deep dive: E2E encryption + multi-device** (the WhatsApp-specific crux) — §12, §13
7. **Address scale + failure + edge cases** — §19, §21, §23, cheat sheet
8. **Summarize tradeoffs** — §21, §24

> 🎤 **Lead with the core challenge:** state up front that "the crux is pushing messages reliably and in order to hundreds of millions of persistent connections, with offline sync and (for WhatsApp) end-to-end encryption," then spend most of your time there. Decide **E2E vs server-side (§12)** early — it reshapes storage, search, and multi-device.

---

## 23. Reliability & Observability

- **No single point of failure** — gateways scale horizontally (any node can route via the Redis registry + bus); message store is partitioned + replicated; multi-AZ Redis for registry/presence.
- **Graceful degradation** — on deploy, gateways **drain** (stop new connections, let clients reconnect elsewhere); if presence/Redis is degraded, messaging still works (presence just goes stale).
- **Idempotent everywhere** — dedup by `message_id` on both server and client makes every retry safe.
- **Backpressure** — never block the sender on N-member fan-out; deliver async + batched (§10).

### Key signals to monitor

| Signal | Why it matters |
| --- | --- |
| **Concurrent connection count / node** | Capacity + balance — a node near its ~1M-conn ceiling needs shedding; sudden drops = mass disconnects. |
| **Message ingest lag** (accept → persisted) | Guards the persist-before-ack contract; rising lag risks send timeouts. |
| **Delivery latency percentiles** (p50/p95/**p99**) | The real user-felt "did it arrive fast?"; tail latency exposes hot partitions / slow gateways. |
| **Gateway-registry staleness** | Stale `conn:user` entries (missed heartbeats) cause failed pushes → over-reliance on resync; watch TTL expiry vs reconnect rates. |
| **Fan-out backlog / hot partition** | A celebrity group or spike can back up delivery workers — alert before it cascades. |
| **Push-notification (APNS/FCM) success rate** | The only nudge for offline users; silent failures mean missed messages until manual app open. |

---

## 24. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Observer / Pub-Sub** | Route messages/presence to subscribers | Decouple sender from recipients |
| **Publish-Subscribe (broker)** | Kafka/Redis bus between gateways | Route across gateway nodes |
| **Mediator** | Chat Service coordinates sender↔recipient gateways | Central routing |
| **Registry** | Connection registry (user/device → node) | Locate recipients |
| **Strategy** | Group fan-out (write/read/hybrid) | Swap per group size |
| **State** | Message status (SENT→DELIVERED→READ) | Guarded transitions |
| **Command** | Client actions (send/receipt/typing) as typed frames | Uniform protocol |
| **Proxy** | Gateway proxies client ⇄ backend | Connection handling |
| **Ports & Adapters** | Push (APNS/FCM), blob store, message store | Swap providers |
| **Producer-Consumer** | Offline queue + delivery workers | Buffer + async deliver |

---

## 25. Final Takeaways

- **Persistent WebSocket gateways** + **Redis connection registry** (user/device → node) + **inter-gateway pub-sub bus** = routing across nodes.
- **Persist before ack** + **client dedup by message_id** + **per-conversation seq** = reliable, ordered, no-dupe (at-least-once).
- **Offline** → store + push notification; **resync from last seq** on reconnect (wide-column store, range scan).
- **E2E encryption (Signal):** identity keys + prekeys + X3DH + Double Ratchet (forward secrecy); server routes ciphertext only; **Sender Keys** for groups; no server-side search.
- **Multi-device** = per-device keys + delivery + synced read state.
- **Presence/typing** = ephemeral Redis; fan out only to interested parties.
- **Media** → blob/CDN (encrypted), messages carry references.
- Patterns: Observer/Pub-Sub, Mediator, Registry, State, Strategy, Producer-Consumer, Ports&Adapters.

### Related notes

- [Notification System — System Design](notification-system-design.md) — push, WebSocket, fan-out overlap
- [Fan-Out / Fan-In](../concepts/fan-out-fan-in.md) · [Real-Time Communication](../concepts/real-time-communication.md) — WebSocket vs SSE, scaling connections
- [Apache Kafka](../concepts/kafka.md) · [Databases — Deep Dive](../concepts/databases-deep-dive.md) (wide-column message store) · [Consistent Hashing](../concepts/consistent-hashing.md)
