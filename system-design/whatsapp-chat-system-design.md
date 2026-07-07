# Chat & Messaging — System Design (WhatsApp / Messenger / Slack DMs)

> **Core challenge:** deliver messages between users **in real time**, **reliably** (never lose a message), **in order**, with **delivery/read receipts**, **presence**, **group chat**, **multi-device sync**, and **offline delivery** — over hundreds of millions of **persistent connections**, optionally **end-to-end encrypted**, at billions-of-messages/day scale.

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

---

## 7. Delivery Semantics & Receipts

| Receipt | Meaning | Trigger |
| --- | --- | --- |
| **Sent ✓** | Server accepted + stored | after the durable write |
| **Delivered ✓✓** | Reached recipient's device | device ack |
| **Read ✓✓ (blue)** | Recipient opened the chat | read event |

- **At-least-once + client dedup** by client-generated **`message_id` (UUID)** → safe retries, no dupes.
- Receipts are themselves small messages routed the same way (and update `message_receipts`).

---

## 8. Ordering

- **Per-conversation ordering** via a **server-assigned monotonic `seq`** per conversation (or timestamp + tiebreak).
- Client renders by `seq`; **buffers out-of-order arrivals** until the gap fills.
- **No global ordering** across conversations — unnecessary and unscalable.
- For groups, the `seq` is per-group (assigned by the group's partition/coordinator).

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

---

## 11. Presence & Typing Indicators

```
Presence: on connect → SET presence:user:{id}=online (Redis, TTL); refresh on heartbeat
          disconnect / TTL expiry → offline + record last_seen
Typing:   ephemeral event pushed to the other party; never stored
```

- Presence is **high-churn + ephemeral** → Redis, not the DB.
- **Fan out presence only to interested parties** (open chats / contacts currently viewing) to avoid an N² storm when millions come online.

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
