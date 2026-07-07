# Chat & Messaging — System Design (WhatsApp / Messenger / Slack DMs)

> **Core challenge:** deliver messages between users **in real time**, **reliably** (never lose a message), **in order**, with **delivery/read receipts**, **presence**, **group chat**, **multi-device sync**, and **offline delivery** — over hundreds of millions of **persistent connections**, optionally **end-to-end encrypted**, at billions-of-messages/day scale.

> **How to read this doc:** each section has the dense interview summary first, then a **Plain-English** deep dive (analogies, annotated code, and the exact confusions that come up while learning). Skim the summaries for revision; read the plain-English parts to actually understand.

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
- [19. Design Patterns (that can be used)](#19-design-patterns-that-can-be-used)
- [20. Scaling & Failure](#20-scaling--failure)
- [21. Interview Cheat Sheet](#21-interview-cheat-sheet)
- [22. Final Takeaways](#22-final-takeaways)

---

## 1. Mental Model

```
Sender ──WebSocket──► Gateway A ──► [persist message] ──► find recipient's Gateway B ──► push to device
                                                       └► if offline, keep in store + push notification (APNS/FCM)
```

Persistent connections + a **message store** + a **router** that finds where the recipient is connected. The hard parts: **connection management at scale**, **reliable + ordered delivery**, **offline sync**, **multi-device**, and (WhatsApp) **end-to-end encryption**.

### Plain-English: what are we actually building?

Think about texting a friend. You type "hey", hit send, and a moment later a little checkmark appears. Your friend's phone buzzes. If their phone is off, the message isn't lost — it shows up the second they turn it back on. That entire experience, but for **two billion people at once**, is what we're designing.

Break the magic into three plain jobs:

1. **Keep a live wire open to every phone.** So the server can push a message *down* to you the instant it arrives — you shouldn't have to keep asking "any new messages? any new messages?". That live wire is a **WebSocket** (§4).
2. **Write every message down before saying "sent".** Like a post office stamping and filing your letter before promising to deliver it. If the building loses power a second later, your letter is still safe on the shelf. This is the **message store** (§9).
3. **Find where the other person is.** Your friend's phone is connected to some server in a data center somewhere. We keep a phone-book (the **connection registry**, §5) that says "user 42's phone is currently plugged into gateway server B" so we know where to push the message.

Everything else in this doc — ticks, presence, groups, encryption — is a refinement on those three jobs.

#### Q: Why is this harder than a normal website with a database?

A normal website is **pull**: your browser asks the server for a page, the server answers, done. Chat is **push**: the server has to reach *out* to your phone at a random moment (when someone messages you), and your phone might be asleep, on a train, or dead. Holding a live connection open to hundreds of millions of phones — and remembering which server each one is on — is the whole ballgame.

---

## 2. Requirements

**Functional**
- 1:1 and **group** messaging; real-time delivery.
- **Delivery receipts** (sent ✓, delivered ✓✓, read ✓✓ blue), **typing**, **presence** (online/last-seen).
- **Offline** users get messages on reconnect; **history sync across multiple devices**.
- Media (images/video/docs/voice); **end-to-end encryption**.

**Non-functional**
- **Low latency** (<100ms in-region), **reliable** (no loss), **ordered per conversation**, **highly available**, huge scale (billions msg/day), **multi-device**, **secure**.

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

### Plain-English: the phone line that stays open

**Analogy: a phone call vs. sending letters.** A normal web request (REST) is like mailing a letter: you send it, you get one reply, and the conversation is over — to ask again you mail another letter. A **WebSocket** is like keeping a phone call open the whole time. Nobody has to redial. The moment the other side has something to say, they just say it and you hear it instantly.

That "always-open line" is exactly what chat needs, because messages arrive at *unpredictable* times and the **server** must be able to start talking (push to you), not just answer when asked.

#### Q: WebSocket vs. polling — why not just ask the server every few seconds?

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

#### Plain-English: what "handling a connection" looks like in code

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

        // tell the shared phone-book "this user's device is reachable on THIS node"
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
        registry.remove("conn:user:" + userId, deviceId); // remove from the phone-book
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
- The **registry** entry (in Redis, shared) = the phone-book other nodes read to find this user (§5).
- **Heartbeats + TTL:** mobile networks die silently (subway, dead battery) — no "goodbye" packet. So each entry expires after ~60s unless the phone keeps pinging. No ping → entry vanishes → the user is treated as offline.

#### Q: Why can't I just move a connection to a different, less-busy server?

Because the open socket is a physical thing pinned to one machine's memory and network card — you can't teleport a live phone call to another operator. That's why we **balance by connection count** (send new connections to emptier nodes) and, on deploy, **drain gracefully** (stop accepting new connections, let existing ones migrate as clients reconnect) instead of yanking them.

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

### Plain-English: your message and your friend are on different servers

**Analogy: a huge office with hundreds of receptionists.** You call reception (Gateway A) to leave a message for a colleague. But your colleague sits on a different floor served by a *different* receptionist (Gateway B). Gateway A doesn't have a wire to your colleague. So it (1) looks them up in the **company directory** ("Bob is on floor 7, desk B") and (2) uses the **internal intercom** to tell floor 7's receptionist "push this to Bob." That directory is the Redis **connection registry**; the intercom is the **pub-sub bus**.

Why not have Gateway A talk to the phone directly? Because A has **no open wire** to Bob's phone — only B does. The socket is pinned to B (§4). So A must relay through B.

```java
public class MessageRouter {

    public void route(Message m) {
        // 1. WHERE is the recipient connected? ask the shared phone-book
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

    @KafkaListener(topics = "gateway-B")   // this node only reads its own mailbox
    public void onEnvelope(Envelope e) {
        gateway.pushToDevice(e.deviceId(), e.message());   // down the local WebSocket
    }
}
```

#### Q: Bus (pub-sub) vs. direct RPC — which one?

Both are shown in the table above. The **bus** (Kafka/Redis pub-sub) decouples nodes: A doesn't need to know B's address or whether B is healthy — it just publishes "for gateway-B" and B picks it up. **Direct RPC** (A calls B's IP directly) is a hop faster but couples A to B's location and liveness. Most designs pick the bus for resilience at scale; a latency-obsessed design might RPC within a region.

#### Q: What if the recipient reconnects to a *different* server mid-message?

That's exactly why the registry has a **short TTL and heartbeats** (§4). If Bob's phone hops from B to D, the old entry expires and a new one ("Bob is on D") appears within seconds. If a message is published to B just as Bob leaves, B's push fails — the message is still safely stored (§6 persists first), so Bob gets it on resync (§9). Nothing is lost.

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

### Plain-English: send, file it, then confirm

**Analogy: the post office holding a letter until delivered.** You hand your letter to the clerk. Before promising anything, the clerk **stamps and files it** in the back room (durable write). *Then* they hand you a receipt ("we've got it — ✓"). Now even if the delivery van crashes, your letter is safe on the shelf and will go out later. What they must **never** do is say "got it!" and then drop the letter on the floor unfiled — that's a lost message.

That ordering — **file first, confirm second** — is the single most important rule in the whole design:

```
persist message  ─►  THEN ack the sender   ✅  (crash after persist = message safe, retry delivery)
ack the sender    ─►  THEN persist message  ❌  (crash in between = sender thinks it sent, message gone)
```

#### Plain-English: the delivery + ack flow in code

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

Walk the numbered steps against the analogy: **(1)** file the letter, **(2)** hand back the receipt, **(3)** attempt delivery. Steps 2 and 3 can even fail entirely — the message is already safe, so we just retry delivery later.

#### Q: What does "at-least-once" mean, and won't I see duplicate messages?

"At-least-once" means we'd rather deliver a message **twice** than risk delivering it **zero** times. So on a flaky network the sender's app may resend, and the server may re-push. To stop you from *seeing* a message twice, every message carries a **`message_id` (a UUID the sender generates once)**. The server and the receiving app both check "have I already got this id?" and ignore repeats. Net effect: sent at-least-once on the wire, shown **exactly once** on your screen. This is the `store.exists(...)` check above and the client-side dedup.

#### Q: Why does the sender get a ✓ so fast, before the recipient has it?

Because the ✓ only means "**the server has safely stored it**" — not "delivered." Delivery to the other person is a separate, slower step (they might be offline). That's the whole point of the different ticks in §7: ✓ = stored, ✓✓ = on their device, blue = they read it.

---

## 7. Delivery Semantics & Receipts

| Receipt | Meaning | Trigger |
| --- | --- | --- |
| **Sent ✓** | Server accepted + stored | after the durable write |
| **Delivered ✓✓** | Reached recipient's device | device ack |
| **Read ✓✓ (blue)** | Recipient opened the chat | read event |

- **At-least-once + client dedup** by client-generated **`message_id` (UUID)** → safe retries, no dupes.
- Receipts are themselves small messages routed the same way (and update `message_receipts`).

### Plain-English: the one, two, and blue ticks

**Analogy: tracking a package.** "Label created" (you got a tracking number), "Out for delivery" (it reached the truck), "Delivered — signed for" (someone actually took it in). WhatsApp's ticks are the same three milestones for your message:

| What you see | Package equivalent | Really means | Who reports it |
| --- | --- | --- | --- |
| **Single ✓** | Label created | Server stored it | the server, after the durable write |
| **Double ✓✓** | Out for delivery / arrived | It reached the recipient's *device* | the recipient's **device** sends a "delivered" receipt |
| **Blue ✓✓** | Delivered — signed for | The recipient **opened the chat** | the recipient's app sends a "read" receipt |

The key insight: a **receipt is just another tiny message flowing the other way.** When Bob's phone gets your message, it sends a "delivered" note back through the exact same pipes (gateway → bus → your gateway → your phone), which flips your ✓ into ✓✓.

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

#### Q: What if I turn off read receipts?

Then the client simply **doesn't send the "read" receipt** — the message still gets delivered (✓✓) and rendered, but no blue tick is ever emitted, so the sender never learns you read it. It's purely a client choice about whether to send that one little receipt message.

---

## 8. Ordering

- **Per-conversation ordering** via a **server-assigned monotonic `seq`** per conversation (or timestamp + tiebreak).
- Client renders by `seq`; **buffers out-of-order arrivals** until the gap fills.
- **No global ordering** across conversations — unnecessary and unscalable.
- For groups, the `seq` is per-group (assigned by the group's partition/coordinator).

### Plain-English: numbering the pages so nothing reads out of order

**Analogy: numbered pages of a letter.** If you mail someone a 5-page letter in separate envelopes, they might arrive as pages 3, 1, 2, 5, 4. As long as every page has a **page number**, the reader can lay them out in the right order regardless of arrival order. The server stamps each message in a conversation with an ever-increasing **`seq`** (1, 2, 3, ...) — that's the page number.

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

#### Q: Why per-conversation ordering, not one global order for everything?

Because you only care that **one chat** reads in order — messages within your conversation with Alice must line up. You do **not** care whether your message to Alice is "before" or "after" someone else's unrelated message to Bob. Enforcing a single global counter across billions of messages would need one coordination bottleneck for the whole planet — impossible to scale, and pointless. Per-conversation `seq` keeps ordering exactly where it matters and lets different conversations run fully in parallel.

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

### Plain-English: the post office holds your letter until you're home

**Analogy: the post office holding a letter until delivered.** If nobody's home, the mail carrier doesn't throw your letter away — they take it back and hold it. They also slip a "we tried to deliver" slip through the door (that's the **push notification**). Next time you're around, you pick it up. Offline delivery is exactly this: the message sits safely in the store, a push notification nudges the phone, and on reconnect the phone collects everything it missed.

The clever bit is how the phone catches up: it just remembers **"the last page number I have is 42"** and asks the server for everything after that.

```
Recipient offline → message already stored (undelivered) + push notif fired
Recipient reconnects → "my last_delivered_seq for conv 7 is 42"
Server → give me messages in conv 7 WHERE seq > 42 → stream them → mark delivered
```

#### Plain-English: why a wide-column store, and the actual query

We store messages in a **wide-column** database (Cassandra/HBase) laid out so all of one conversation's messages sit **together, sorted by `seq`**. That makes "everything after seq 42" a single fast **range scan** — like flipping to page 43 of a book and reading on, instead of searching the whole library.

```sql
-- Layout: PARTITION by conversation (all its messages on one node, together)
--         CLUSTER (sort on disk) by seq (so ranges are contiguous & fast)
CREATE TABLE messages (
    conversation_id BIGINT,
    seq             BIGINT,      -- the per-conversation page number (§8)
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
- **`last_delivered_seq` per device** is the "which page am I on" bookmark. Each of your devices (phone, laptop) has its own bookmark, so each catches up independently (§13).

#### Q: Where do offline messages actually "queue"? Is there a separate queue table?

Usually **no separate queue** — the "undelivered queue" is just a *view* of the message store: for a given device, it's `messages WHERE seq > that device's last_delivered_seq`. The messages are already stored durably (§6 persists before ack), so "undelivered" simply means "the device's bookmark hasn't reached them yet." Deliver = push them + advance the bookmark.

#### Q: WhatsApp deletes messages after delivery — so where's my history?

On your **devices**, not the server. Because WhatsApp is end-to-end encrypted (§12), the server can't read your messages and deletes the ciphertext once every device has received it. Your chat history lives in your phone's local database (and encrypted backups). Slack/Messenger are different: they keep history **server-side** (so you can search it and load it on any fresh device), which is why they need a much bigger store plus a search index.

#### Q: What's the push notification for if the message is already stored?

The stored message just sits there silently — the phone won't know to reconnect and grab it. The push notification (APNS on iOS, FCM on Android) is the **"you've got mail" slip**: it wakes the phone/app enough to buzz the user and trigger a reconnect + resync. Without it, an offline user wouldn't see the message until they happened to open the app.

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

### Plain-English: one message, many mailboxes

**Analogy: sending the same party invite to 8 friends.** You wrote one invite, but 8 people need to receive it. Two ways to handle it:

- **Fan-out on write** — you photocopy the invite 8 times and drop one into **each friend's mailbox** now. Their mailbox is ready instantly (fast to read later), but *you* did 8 copies of work up front. Great for small groups; brutal for a 5,000-person group.
- **Fan-out on read** — you pin **one** invite on the shared community board. Nobody's mailbox gets a copy; each friend walks over and reads the board when they check. Cheap for you (write once), a bit more work for each reader. Great for huge groups.

```java
public void deliverGroupMessage(GroupMessage gm) {
    long seq = seqGenerator.next(gm.groupId());          // ONE per-group page number (§8)
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

#### Q: Why not always fan-out on write? It makes reads so fast.

Because for a **10,000-member** group, one message would trigger 10,000 writes/pushes — and if 50 people are chatting, that's half a million deliveries a second for a single group. The sender would be blocked forever and the store would melt. So for big groups we **write once** to the group log and let members pull. Real systems go **hybrid**: fan-out on write for small groups (fast, cheap enough), fan-out on read for large ones.

#### Q: Never block the sender — what does that mean?

The sender should get their ✓ the instant the message is **stored**, not after all N members receive it. So fan-out happens **asynchronously and in batches** *after* acking the sender. Whether a group has 3 or 3,000 members, the sender's "sent" is equally fast; the deliveries fan out in the background.

#### Q: How do groups stay in order if everyone's sending?

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

### Plain-English: the "online" dot and "last seen"

**Analogy: an "open / closed" sign on a shop door, on a spring timer.** When the shop opens, they flip the sign to **OPEN** — but the sign is on a timer that flips back to CLOSED unless someone keeps resetting it. As long as the shopkeeper is around, they tap it every minute so it stays OPEN. The moment they leave (or forget), the timer lapses and it reads CLOSED — and a little note records *when* it last flipped. That's presence: an **online flag with a short expiry (TTL)** that heartbeats keep alive, plus a **last_seen** timestamp captured when it lapses.

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

#### Q: Why Redis and not the main database for presence?

Because presence is **high-churn and disposable**. Millions of people flip online/offline constantly — that's a storm of tiny, short-lived writes. A durable SQL database would choke and it'd be pointless to persist "online" (it's stale in seconds). Redis is in-memory, blazing fast, and has **TTL built in** (the auto-expiry above). We only persist the occasional `last_seen` value durably.

#### Q: What's the N² storm, and why "fan out only to interested parties"?

Imagine 100 million people come online at 8am. If we notified **every contact of every user**, that's each person's presence change pushed to hundreds of contacts × millions of people = a catastrophic flood (roughly N² messages). Instead, we only tell the people who are **actually looking right now** — someone with your chat open, or viewing your contact. Everyone else finds out your status lazily, the next time they open your chat. Same idea for **typing indicators**: a pure ephemeral "Bob is typing…" event pushed only to whoever's in that chat, and **never stored** at all.

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
CREATE TABLE blocked_users ( user_id BIGINT, blocked_id BIGINT, PRIMARY KEY (user_id, blocked_id) );

-- Ephemeral (Redis):
--   conn:user:{id}     → [{device, gatewayNode, connId}]     (connection registry)
--   presence:user:{id} → online (TTL, heartbeat-refreshed)
```

> **Tables to consider:** users, devices, prekeys, conversations, conversation_members, messages, message_receipts, group_metadata, blocked_users. Connection/presence = Redis; media = blob/CDN.

### Plain-English: a tour of the tables

Think of these tables as the **filing cabinets** behind the app. Here's what each drawer is for and the one thing that makes it tick:

| Table | Plain-English "it's the drawer for…" | Key detail |
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

The three "cursors" are the heart of correctness — they're all just **page numbers** (§8) pointing into the `messages` table:

```
messages.seq              → the page number stamped on each message
devices.last_delivered_seq → "this device has received up to page N"      (drives offline sync, §9)
conversation_members.last_read_seq → "this user has read up to page N"    (drives unread badges + blue ticks)
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

#### Q: Why is `messages` a wide-column table but the rest look like normal SQL?

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

## 19. Design Patterns (that can be used)

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

## 20. Scaling & Failure

- **Gateways** scale horizontally; Redis **connection registry** so any node can route; ~1M conns/node.
- **Inter-gateway routing** via pub-sub bus; sender's node publishes, recipient's node pushes.
- **Message store** = wide-column, partitioned by conversation, replicated (LSM handles the write firehose).
- **Gateway crash** → clients reconnect elsewhere + resync from last seq; presence TTL clears.
- **No message loss** → persist before ack; client dedup; resync on reconnect.
- **Huge groups** → async batched fan-out (read-fan-out); Sender Keys for one-encrypt.
- **Hot conversation** → partition can be hot; keep partitions per-conversation and cache recent messages.

---

## 21. Interview Cheat Sheet

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

---

## 22. Final Takeaways

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
