# Chat & Messaging — System Design (WhatsApp / Messenger / Slack DMs)

> **Core challenge:** deliver messages between users **in real time**, **reliably** (never lose a message), **in order**, with **delivery/read receipts**, **presence** (online/last-seen), **group chat**, and **offline delivery** — over **persistent connections** at billions-of-messages scale.

---

## Contents

- [1. Mental Model](#1-mental-model)
- [2. Requirements](#2-requirements)
- [3. Capacity Estimation](#3-capacity-estimation)
- [4. Connection Layer (WebSocket Gateway)](#4-connection-layer-websocket-gateway)
- [5. Message Flow — 1:1](#5-message-flow--11)
- [6. Delivery Semantics & Receipts](#6-delivery-semantics--receipts)
- [7. Offline Delivery & Message Store](#7-offline-delivery--message-store)
- [8. Group Chat & Fan-out](#8-group-chat--fan-out)
- [9. Presence & Typing Indicators](#9-presence--typing-indicators)
- [10. Ordering](#10-ordering)
- [11. Media / Attachments](#11-media--attachments)
- [12. Data Model (all tables)](#12-data-model-all-tables)
- [13. API / Protocol](#13-api--protocol)
- [14. Design Patterns (that can be used)](#14-design-patterns-that-can-be-used)
- [15. Scaling & Failure](#15-scaling--failure)
- [16. Interview Cheat Sheet](#16-interview-cheat-sheet)
- [17. Final Takeaways](#17-final-takeaways)

---

## 1. Mental Model

```
Sender ──WebSocket──► Chat Server ──► [store message] ──► route to recipient's connection
                                                       └► if offline, queue for later + push notification
```

Persistent connections + a **message store** + a **router** that finds where the recipient is connected. The hard parts are **connection management at scale**, **reliable + ordered delivery**, and **offline sync**.

---

## 2. Requirements

**Functional**
- 1:1 and **group** messaging; real-time delivery.
- **Delivery receipts** (sent ✓, delivered ✓✓, read ✓✓ blue) and **typing**/**presence** (online, last-seen).
- **Offline** users get messages on reconnect; **message history** sync across devices.
- Media (images/video/docs); optional E2E encryption.

**Non-functional**
- **Low latency** (<100ms in-region), **reliable** (no loss), **ordered per conversation**, **highly available**, huge scale (billions msg/day), **multi-device**.

---

## 3. Capacity Estimation

```
Users              ~ 2B, DAU ~ 1B
Messages/day       ~ 100B  → ~1.15M msg/sec avg, peaks higher
Concurrent conns   ~ hundreds of millions of persistent WebSockets
Storage            messages are huge → store recent hot, archive/expire; or E2E (server stores only until delivered)
```

> Two big cost centers: **millions of persistent connections** and **message write throughput** → connection gateways + partitioned message store (Cassandra-like).

---

## 4. Connection Layer (WebSocket Gateway)

Clients hold a **persistent WebSocket** (or MQTT — WhatsApp uses XMPP-like) to a **gateway** server.

```
Client ⇄ WebSocket Gateway (stateful)  ── registers: user → {connId, gatewayNode}
```

- **Connection registry (Redis):** `conn:user:{id} → {gatewayNode, connId}` so any server can find where a user is connected. TTL + heartbeat.
- Gateways are **stateful** → use a **session/registry** so the router can locate a recipient across nodes.
- Multi-device → a user maps to **multiple connections**.
- Load balancer with sticky routing; heartbeats/ping-pong to detect dead connections.

---

## 5. Message Flow — 1:1

```
1. Sender → WebSocket → Chat Service
2. Chat Service:
     a. persist message (status=SENT)                  # durability first
     b. ACK sender ("sent ✓")
     c. look up recipient connection in registry
     d. if online → push via recipient's gateway → recipient device
        if offline → leave in store / offline queue + trigger push notification (APNS/FCM)
3. Recipient device receives → sends "delivered" receipt → update status ✓✓ → notify sender
4. Recipient reads → "read" receipt → status ✓✓ blue → notify sender
```

> **Persist before ack** → a crash never loses an accepted message (at-least-once). Client dedups by message id.

---

## 6. Delivery Semantics & Receipts

| Receipt | Meaning | Trigger |
| --- | --- | --- |
| **Sent ✓** | Server accepted + stored | after DB write |
| **Delivered ✓✓** | Reached recipient device | device ack |
| **Read ✓✓ (blue)** | Recipient opened chat | read event |

- **At-least-once + client dedup** by unique `message_id` (client-generated UUID) → safe retries, no dupes.
- Every message carries a client-generated id for idempotency and ordering.

---

## 7. Offline Delivery & Message Store

```
Recipient offline:
  message stays in store with status=SENT (undelivered)
  send push notification via APNS/FCM
On reconnect:
  client sends last-synced message id / timestamp per conversation
  server streams all messages after that → client catches up (sync)
  mark delivered
```

- **Message store:** wide-column (Cassandra/HBase) partitioned by `conversation_id`, clustered by time/message_id → fast "messages after X" reads.
- Delivered messages may be retained (history) or deleted (WhatsApp deletes from server once delivered to all devices in E2E model).

---

## 8. Group Chat & Fan-out

```
Group message → for each member:
    write to member's conversation timeline (fan-out on write)  OR
    write once to group log; members read (fan-out on read)
    route to each online member's connection; queue for offline
```

| Approach | Trade-off |
| --- | --- |
| **Fan-out on write** | Fast reads; expensive for huge groups |
| **Fan-out on read** | Cheap write; heavier read; good for large groups |
| **Hybrid** | Small groups write-fan-out, large groups read-fan-out |

- Large groups (e.g. 100k) → don't fan out to every device synchronously; batch + async.
- Ordering within a group via a per-group sequence / server timestamp.

---

## 9. Presence & Typing Indicators

```
Presence (online/last-seen):
  on connect → SET presence:user:{id} = online (Redis, TTL, refresh on heartbeat)
  on disconnect / TTL expiry → offline + record last_seen
Typing:
  ephemeral event pushed to the conversation's other party; not stored
```

- Presence is **high-churn + ephemeral** → Redis, not the DB.
- Fan-out presence only to **interested** users (open chats / contacts) to avoid storms.

---

## 10. Ordering

- **Per-conversation ordering** via a monotonic sequence (server-assigned `seq` per conversation) or message timestamp + tiebreak.
- Client orders by `seq`; handles out-of-order arrival by buffering.
- Global ordering across conversations is unnecessary.

---

## 11. Media / Attachments

```
1. Client requests upload URL → uploads media to blob store (S3) directly (pre-signed URL)
2. Message carries a media reference (URL/id), not the bytes
3. Recipient downloads from blob store / CDN
```

Keeps large binaries off the messaging path; CDN for delivery; thumbnails generated async.

---

## 12. Data Model (all tables)

```sql
CREATE TABLE users ( user_id BIGINT PRIMARY KEY, phone VARCHAR(20) UNIQUE, name TEXT, last_seen TIMESTAMP );
CREATE TABLE devices ( device_id BIGINT PRIMARY KEY, user_id BIGINT, push_token TEXT, platform VARCHAR(10) );

CREATE TABLE conversations (
    conversation_id BIGINT PRIMARY KEY,
    type            VARCHAR(10),        -- DIRECT, GROUP
    created_at      TIMESTAMP
);
CREATE TABLE conversation_members (
    conversation_id BIGINT, user_id BIGINT, role VARCHAR(10),
    last_read_seq   BIGINT DEFAULT 0,   -- for unread counts + read receipts
    PRIMARY KEY (conversation_id, user_id)
);

-- Messages: wide-column store, partition by conversation_id, cluster by seq
CREATE TABLE messages (
    conversation_id BIGINT,
    seq             BIGINT,              -- per-conversation ordering
    message_id      UUID,                -- client-generated, idempotency
    sender_id       BIGINT,
    type            VARCHAR(20),         -- TEXT, IMAGE, VIDEO, DOC
    content         TEXT,                -- or media ref
    created_at      TIMESTAMP,
    PRIMARY KEY (conversation_id, seq)
);

CREATE TABLE message_receipts (
    conversation_id BIGINT, seq BIGINT, user_id BIGINT,
    status VARCHAR(12),                  -- DELIVERED, READ
    at TIMESTAMP,
    PRIMARY KEY (conversation_id, seq, user_id)
);

-- Ephemeral (Redis, shown for completeness):
--   conn:user:{id}     → {gatewayNode, connId}
--   presence:user:{id} → online (TTL)
--   offline_queue:user:{id} (or derive from undelivered messages)
```

> **Tables to consider:** users, devices, conversations, conversation_members, messages, message_receipts, blocked_users, group_metadata. Connection/presence/offline = Redis.

---

## 13. API / Protocol

```
WS connect  /v1/ws                         # persistent; auth on handshake
→ send:     { type:"MSG", clientMsgId, conversationId, content }
← ack:      { type:"ACK", clientMsgId, seq, status:"SENT" }
← receive:  { type:"MSG", conversationId, seq, senderId, content }
→ receipt:  { type:"DELIVERED"|"READ", conversationId, seq }
← presence: { type:"PRESENCE", userId, status }
REST:  GET /v1/conversations, GET /v1/conversations/{id}/messages?afterSeq=, POST /v1/media/upload-url
```

---

## 14. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Observer / Pub-Sub** | Route messages to subscribers; presence fan-out | Decouple sender from recipients |
| **Publish-Subscribe (broker)** | Kafka/Redis pub-sub between gateways | Route across gateway nodes |
| **Mediator** | Chat Service coordinates sender↔recipient gateways | Central routing logic |
| **Strategy** | Group fan-out (write vs read vs hybrid) | Swap per group size |
| **State** | Message status (SENT→DELIVERED→READ) | Guarded transitions |
| **Command** | Client actions (send/receipt/typing) as typed messages | Uniform protocol handling |
| **Registry** | Connection registry (user→node) | Locate recipients |
| **Proxy** | Gateway proxies client ⇄ backend | Connection handling |
| **Ports & Adapters** | Push (APNS/FCM), blob store, message store | Swap providers |
| **Producer-Consumer** | Offline queue + delivery workers | Buffer + async deliver |

---

## 15. Scaling & Failure

- **Gateways** scale horizontally; connection registry in Redis so any node can route.
- **Message store** partitioned by conversation; replicate for durability.
- **Inter-gateway routing** via a pub-sub bus (Kafka/Redis) — sender's gateway publishes, recipient's gateway consumes and pushes.
- **Gateway crash** → clients reconnect (to another node), re-sync from last seq; presence TTL expires.
- **Message loss prevention** → persist before ack; client dedup by message id; re-sync on reconnect.
- **Hot group** → async batched fan-out, read-fan-out for very large groups.

---

## 16. Interview Cheat Sheet

> **"How does a message reach an online recipient?"**
> "Sender → gateway → Chat Service persists it (durability first) and acks 'sent'. It looks up the recipient's connection in a Redis registry and pushes via their gateway. Offline → keep in store + push notification; deliver on reconnect."

> **"How do you guarantee no message loss and no duplicates?"**
> "Persist before acking (at-least-once) and dedup on the client by a client-generated message id. On reconnect, the client re-syncs from its last seen seq."

> **"How is ordering maintained?"**
> "A per-conversation monotonic sequence; clients order by seq and buffer out-of-order arrivals. No global ordering needed."

> **"How do you handle group messages to huge groups?"**
> "Hybrid fan-out — write-fan-out for small groups, read-fan-out for large; async + batched routing; per-group sequence for order."

> **"How do you manage millions of connections?"**
> "Stateful WebSocket gateways + a Redis connection registry (user→node) so any server can locate a recipient; heartbeats detect dead conns; reconnect + resync on failure."

> **"Presence at scale?"**
> "Redis with TTL refreshed by heartbeat; fan out presence only to interested contacts/open chats to avoid storms."

---

## 17. Final Takeaways

- **Persistent WebSocket gateways** + **Redis connection registry** (user→node) to route across nodes.
- **Persist before ack** + **client dedup by message id** → reliable, no dupes (at-least-once).
- **Per-conversation sequence** for ordering; client buffers out-of-order.
- **Offline** → store + push notification; **resync from last seq** on reconnect.
- **Group fan-out** = write vs read vs hybrid by group size.
- **Presence/typing** = ephemeral Redis, not the DB.
- **Media** goes to blob store/CDN; messages carry references.
- Patterns: Observer/Pub-Sub, Mediator, Registry, State, Strategy, Producer-Consumer, Ports&Adapters.

### Related notes

- [Notification System — System Design](notification-system-design.md) — push, WebSocket, fan-out overlap
- [Apache Kafka](../concepts/kafka.md) · [Caching Strategies](../concepts/caching-strategies.md) · [Consistent Hashing](../concepts/consistent-hashing.md)
