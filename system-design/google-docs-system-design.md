# Collaborative Document Editor — System Design (Google Docs)

> **Core challenge:** let **many users edit the same document simultaneously** and see each other's changes in **real time**, with **no lost edits** and **everyone converging to the same final state** — despite concurrent, conflicting edits and network delays. The heart is **conflict resolution**: **Operational Transformation (OT)** or **CRDTs**.

---

## Contents

- [1. Mental Model](#1-mental-model)
- [2. Requirements](#2-requirements)
- [3. The Core Problem — Concurrent Edits](#3-the-core-problem--concurrent-edits)
- [4. Operational Transformation (OT)](#4-operational-transformation-ot)
- [5. CRDTs (the alternative)](#5-crdts-the-alternative)
- [6. Real-Time Sync Architecture](#6-real-time-sync-architecture)
- [7. Presence, Cursors & Comments](#7-presence-cursors--comments)
- [8. Persistence & Versioning](#8-persistence--versioning)
- [9. Data Model (all tables)](#9-data-model-all-tables)
- [10. API / Protocol](#10-api--protocol)
- [11. Design Patterns (that can be used)](#11-design-patterns-that-can-be-used)
- [12. Scaling & Failure](#12-scaling--failure)
- [13. Interview Cheat Sheet](#13-interview-cheat-sheet)
- [14. Final Takeaways](#14-final-takeaways)

---

## 1. Mental Model

```
Each editor sends small OPERATIONS ("insert 'x' at pos 5", "delete pos 8")
   → server orders + transforms them against concurrent ops
   → broadcasts transformed ops to all clients
   → every client applies them → all converge to identical text
```

You do **not** send the whole document on every keystroke — you send **operations**, resolve conflicts, and broadcast.

---

## 2. Requirements

**Functional**
- Multiple users edit the same doc concurrently; changes appear in real time (<100ms feel).
- **Convergence** — everyone ends with the same content.
- **Cursors/selections** of others, **presence**, **comments/suggestions**.
- **Version history**, undo/redo, offline edits that sync later.
- Access control (view/comment/edit), sharing.

**Non-functional**
- Real-time, consistent (eventual convergence), durable (no lost edits), available, scales to many concurrent editors per doc.

---

## 3. The Core Problem — Concurrent Edits

Two users edit "HELLO" at the same time:

```
Start: "HELLO"
User A: insert "X" at pos 0   → "XHELLO"
User B: delete pos 4 ("O")    → "HELL"

If applied naïvely in different orders on each client → they diverge.
Need: transform each op against the other so BOTH clients converge to "XHELL".
```

Last-write-wins on the whole doc = lost edits. We need **fine-grained op merging**.

---

## 4. Operational Transformation (OT)

The classic Google Docs approach.

- Every edit is an **operation** (`insert(pos, char)`, `delete(pos)`), tagged with the document **version/revision** it was based on.
- A central server maintains the **authoritative op sequence**. When it receives an op based on an older revision, it **transforms** it against the ops that happened since, so its intent is preserved at the correct position.

```
transform(opA, opB) → opA' such that applying opB then opA' == applying opA then opB'
```

```
Client sends op @rev 10 → server is at rev 12
  → server transforms the op against ops 11,12 → applies → new rev 13
  → broadcasts transformed op to all clients (each transforms against its pending local ops)
```

| Property | Detail |
| --- | --- |
| Central authority | Server assigns global order + revision numbers |
| Client | Keeps unacked local ops; transforms incoming ops against them |
| Pros | Compact ops; proven (Google Docs) |
| Cons | Transform functions are **hard to get right**; needs a central server |

---

## 5. CRDTs (the alternative)

**Conflict-free Replicated Data Types** — data structures that **merge automatically** without a central transform.

- Each character gets a unique, ordered identifier (e.g. fractional index / logical position) so inserts/deletes commute.
- Any replica can apply ops in any order and still converge — great for **offline** and **P2P**, no central server required.

| | **OT** | **CRDT** |
| --- | --- | --- |
| Coordination | Central server orders ops | Merges anywhere, order-independent |
| Complexity | Tricky transforms | Tricky metadata/tombstones (memory) |
| Used by | Google Docs (OT) | Figma, Yjs/Automerge, newer editors |
| Offline/P2P | Harder | Natural fit |

> **Interview:** know both. OT = central server + transform; CRDT = commutative ops that merge anywhere. Modern systems trend toward CRDTs.

---

## 6. Real-Time Sync Architecture

```
Clients ⇄ WebSocket ⇄ Edit/Collaboration Server (per-document session)
                              │  ordered op log per doc
                              ▼
                       Op log store (append-only) + periodic snapshots
                              │
                          Kafka (fan-out to other server nodes / persistence / history)
```

- **Session server per document** (or doc sharded to a node) holds the authoritative revision and in-memory doc state; **sticky routing** so all editors of a doc hit the same node.
- Ops are appended to a **per-doc op log**; **snapshots** taken periodically so you don't replay millions of ops.
- WebSocket for low-latency bidirectional sync; ack + rev numbers.

---

## 7. Presence, Cursors & Comments

- **Cursors/selections** and **presence** are ephemeral → broadcast via the same channel, stored in Redis (not durably).
- **Comments/suggestions** are anchored to positions (that must survive edits — anchor to stable op ids/ranges) and persisted.

---

## 8. Persistence & Versioning

```
Persistence = latest snapshot + op log since snapshot
  reconstruct doc = snapshot + replay ops
Version history = named snapshots / revision checkpoints
Undo/redo = inverse operations applied locally
```

- Append-only **op log** is the source of truth; **snapshots** bound replay cost.
- Autosave continuously; history lets you restore any checkpoint.

---

## 9. Data Model (all tables)

```sql
CREATE TABLE documents (
    doc_id BIGINT PRIMARY KEY, owner_id BIGINT, title TEXT,
    current_revision BIGINT DEFAULT 0, created_at TIMESTAMP, updated_at TIMESTAMP
);

-- Append-only operation log (source of truth) — often a wide-column/append store
CREATE TABLE doc_operations (
    doc_id     BIGINT,
    revision   BIGINT,            -- global order per doc
    op_type    VARCHAR(10),       -- INSERT, DELETE, FORMAT
    position   INT,               -- or CRDT identifier
    payload    JSONB,             -- char(s), attributes
    author_id  BIGINT,
    created_at TIMESTAMP,
    PRIMARY KEY (doc_id, revision)
);

CREATE TABLE doc_snapshots (
    doc_id BIGINT, revision BIGINT, content BYTEA,   -- serialized doc at that revision
    created_at TIMESTAMP, PRIMARY KEY (doc_id, revision)
);

CREATE TABLE doc_permissions (
    doc_id BIGINT, user_id BIGINT, role VARCHAR(10),  -- OWNER, EDITOR, COMMENTER, VIEWER
    PRIMARY KEY (doc_id, user_id)
);

CREATE TABLE comments (
    comment_id BIGINT PRIMARY KEY, doc_id BIGINT, author_id BIGINT,
    anchor JSONB,                 -- stable position/range reference
    body TEXT, resolved BOOLEAN DEFAULT FALSE, created_at TIMESTAMP
);

CREATE TABLE doc_versions ( version_id BIGINT PRIMARY KEY, doc_id BIGINT, revision BIGINT, name TEXT, created_by BIGINT, created_at TIMESTAMP );

-- Ephemeral (Redis): presence:doc:{id}, cursors:doc:{id}, session routing
```

> **Tables to consider:** documents, doc_operations (op log — key), doc_snapshots, doc_permissions, comments, doc_versions, users, shares/links. Presence/cursors = Redis.

---

## 10. API / Protocol

```
WS  /v1/docs/{id}/collab                       # join editing session
→ op:      { baseRevision, ops:[{insert/delete...}] }
← ack:     { revision }                         # server-assigned
← remote:  { revision, ops, authorId }          # others' transformed ops
← presence/cursor: { userId, cursorPos, selection }
REST: GET /v1/docs/{id}  (snapshot),  GET /v1/docs/{id}/history,
      POST /v1/docs/{id}/comments,   PUT /v1/docs/{id}/permissions
```

---

## 11. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Command** | Each edit is an operation/command (with inverse for undo) | Uniform apply/undo/redo, log, replay |
| **Memento** | Snapshots / version checkpoints | Restore prior states |
| **Observer / Pub-Sub** | Broadcast ops + presence to collaborators | Real-time fan-out |
| **State** | Document/session state; op application | Manage editing lifecycle |
| **Strategy** | Conflict resolution (OT vs CRDT), serialization | Swap algorithm |
| **Event Sourcing** | Op log as source of truth; snapshot to bound replay | Rebuild by replay; full history |
| **Mediator** | Collaboration server coordinates clients | Central routing/ordering (OT) |
| **Proxy** | WebSocket gateway | Connection handling |
| **Ports & Adapters** | Storage, pub-sub, auth | Swap infra |

> **Note:** the **op log + snapshots** design *is* Event Sourcing; edits *are* the Command pattern; undo is the command's inverse; version history is Memento.

---

## 12. Scaling & Failure

- **Shard docs to session servers**; sticky routing per doc; hot docs (many editors) stay on one node with in-memory state.
- **Op log append + periodic snapshots** bound storage/replay; archive old ops.
- **Server crash** → clients reconnect, resync from last acked revision (op log is durable); rebuild in-memory state from latest snapshot + ops.
- **Offline edits** → queue local ops, transform/merge on reconnect (CRDTs make this cleanest).
- Fan-out across nodes via Kafka/Redis pub-sub for multi-node doc sessions.

---

## 13. Interview Cheat Sheet

> **"How do concurrent edits not conflict?"**
> "Edits are fine-grained **operations** tagged with a base revision. A central server (OT) orders them and **transforms** each op against concurrent ops so intent is preserved, then broadcasts; all clients converge. Alternatively **CRDTs** give commutative ops that merge in any order without a central transform."

> **"OT vs CRDT?"**
> "OT = central server orders + transforms ops (Google Docs); compact but tricky transforms. CRDT = ops carry unique ordered ids and merge anywhere, great for offline/P2P, but more metadata/memory. Modern editors trend to CRDTs."

> **"How is the doc persisted?"**
> "Append-only **op log** (event sourcing) as source of truth + periodic **snapshots** to bound replay; reconstruct = snapshot + replay. Version history = named checkpoints."

> **"How does real-time sync work?"**
> "WebSocket to a per-doc session server (sticky routing) that holds the authoritative revision; ops get acked with rev numbers and broadcast to collaborators; presence/cursors are ephemeral via Redis."

> **"Which patterns?"**
> "Command (ops + undo), Event Sourcing (op log), Memento (snapshots), Observer (broadcast), Mediator (server ordering), Strategy (OT/CRDT)."

---

## 14. Final Takeaways

- Send **operations, not the whole doc**; converge via **OT** (central transform) or **CRDT** (commutative merge).
- **Op log (event sourcing) + snapshots** = durable source of truth with bounded replay; enables history + undo.
- **Per-doc session server + sticky WebSocket routing**; hot docs stay in-memory on one node.
- **Presence/cursors** ephemeral (Redis); **comments** anchored to stable positions.
- **Offline** = queue + merge on reconnect (CRDT-friendly).
- Patterns: Command, Event Sourcing, Memento, Observer, Mediator, Strategy.

### Related notes

- [Apache Kafka](../concepts/kafka.md) — op fan-out / persistence backbone
- [Notification System — System Design](notification-system-design.md) — WebSocket real-time delivery overlap
