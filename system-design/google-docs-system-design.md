# Collaborative Document Editor — System Design (Google Docs)

> **Core challenge:** let **many users edit the same document simultaneously** and see each other's changes in **real time**, with **no lost edits** and **guaranteed convergence** (everyone ends with identical content) — despite concurrent, conflicting edits and network delays. The heart is **conflict resolution**: **Operational Transformation (OT)** or **CRDTs**.

---

## Contents

- [1. Mental Model](#1-mental-model)
- [2. Requirements](#2-requirements)
- [3. Capacity Estimation](#3-capacity-estimation)
- [4. The Core Problem — Concurrent Edits](#4-the-core-problem--concurrent-edits)
- [5. Operational Transformation (OT)](#5-operational-transformation-ot)
- [6. CRDTs (the alternative)](#6-crdts-the-alternative)
- [7. Real-Time Sync Architecture](#7-real-time-sync-architecture)
- [8. Presence, Cursors & Comments](#8-presence-cursors--comments)
- [9. Persistence & Versioning](#9-persistence--versioning)
- [10. Undo/Redo in Collaboration](#10-undoredo-in-collaboration)
- [11. Data Model (all tables)](#11-data-model-all-tables)
- [12. API / Protocol](#12-api--protocol)
- [13. Sequences](#13-sequences)
- [14. Consistency & Edge Cases](#14-consistency--edge-cases)
- [15. Design Patterns (that can be used)](#15-design-patterns-that-can-be-used)
- [16. Scaling & Failure](#16-scaling--failure)
- [17. Interview Cheat Sheet](#17-interview-cheat-sheet)
- [18. Final Takeaways](#18-final-takeaways)

---

## 1. Mental Model

```
Each editor sends small OPERATIONS ("insert 'x' at pos 5", "delete pos 8")
   → server orders + TRANSFORMS them against concurrent ops
   → broadcasts transformed ops to all clients
   → every client applies them → all CONVERGE to identical text
```

You do **not** send the whole document on every keystroke — you send **operations**, resolve conflicts, and broadcast. The two invariants: **causality** (see edits in a sensible order) and **convergence** (everyone ends identical).

---

## 2. Requirements

**Functional**
- Multiple users edit the same doc concurrently; changes appear in **real time** (<100ms feel).
- **Convergence** — everyone ends with the same content.
- **Cursors/selections** of others, **presence**, **comments/suggestions**.
- **Version history**, **undo/redo**, **offline** edits that sync later.
- Access control (view/comment/edit), sharing.

**Non-functional**
- Real-time, **eventually convergent**, **durable** (no lost edits), available, scales to many concurrent editors per doc.

---

## 3. Capacity Estimation

```
Docs ~ billions · concurrent editors per doc ~ 1–100s (usually small; occasionally big)
Keystroke ops: an active editor emits several ops/sec → per hot doc maybe 100s ops/sec
Op size: tiny (a few bytes) → op log grows fast for busy docs → SNAPSHOT to bound replay
Storage: op log + periodic snapshots; media/images → blob; most docs are small text
Fan-out: an op → all N editors of that doc (N small) → cheap real-time broadcast
```

> Unlike feeds, fan-out is tiny (editors per doc), but **latency + convergence** are strict. The scaling unit is the **per-document session**, not global.

---

## 4. The Core Problem — Concurrent Edits

Two users edit "HELLO" at the same time:

```
Start: "HELLO"
User A: insert "X" at pos 0   → "XHELLO"
User B: delete pos 4 ("O")    → "HELL"

Applied naïvely in different orders on each client → they DIVERGE.
Both edits were based on "HELLO" (concurrent). We must reconcile so both converge to "XHELL".
```

- **Last-write-wins on the whole doc = lost edits** (one user's change clobbers the other's). Unacceptable.
- Need **fine-grained op merging** that preserves both intents and converges.

---

## 5. Operational Transformation (OT)

The classic Google Docs approach: a **central server** orders ops and **transforms** them so intent is preserved.

- Every edit is an **operation** (`insert(pos, char)`, `delete(pos)`), tagged with the **base revision** it was made against.
- **Transform:** adjust an op's position to account for concurrent ops that happened "before" it in the server order.

```
transform(opA, opB) → opA'  such that:  apply(apply(doc, opB), opA') == apply(apply(doc, opA), opB')
(both orders reach the same result → convergence)
```

### Worked example

```
Doc "HELLO".  A = insert('X', pos 0);  B = delete(pos 4)   (both based on "HELLO")
Server order: B then A.
  apply B → "HELL"
  transform A against B: B deleted pos 4 (after A's pos 0) → A's position unaffected → A' = insert('X', 0)
  apply A' → "XHELL"  ✓   (both clients converge to "XHELL")
If A had been insert at pos 5, B's delete at 4 would shift it → A' = insert('X', 4).
```

### Client-server protocol

```
Client keeps:  a BUFFER of un-acked local ops + the last acked server revision
Send op @baseRev → server transforms it against ops since baseRev → applies → assigns new rev → acks
Incoming remote op → client transforms it against its own pending un-acked ops before applying
```

| Property | Detail |
| --- | --- |
| Central authority | Server assigns the **global order + revision numbers** |
| Client | Holds un-acked local ops; transforms incoming ops against them |
| Pros | Compact ops; proven at scale (Google Docs) |
| Cons | **Transform functions are hard to get right** (many op-pair cases); needs a central server |

---

## 6. CRDTs (the alternative)

**Conflict-free Replicated Data Types** — data structures whose ops **merge automatically** in any order, no central transform.

- Each character gets a **globally unique, totally-ordered identifier** (e.g. a **fractional index** between neighbors, or an id like `(siteId, counter)` as in RGA/Logoot).
- Insert = place a char with an id between two existing ids; delete = **tombstone** the id. Because ids are unique + ordered, applying ops in any order converges — no positional transform needed.

```
"HELLO": chars carry ids  H(1) E(2) L(3) L(4) O(5)
A inserts 'X' before H → new id between start and H(1)  → e.g. X(0.5)
B deletes O(5) → tombstone O(5)
Any replica applying both in any order → "XHELL"  ✓ (ids are absolute)
```

| | **OT** | **CRDT** |
| --- | --- | --- |
| Coordination | Central server orders + transforms | Merges anywhere, order-independent |
| Complexity | Tricky transform functions | Metadata/tombstone overhead (memory); GC needed |
| Used by | Google Docs (OT) | Figma, Yjs/Automerge, Apple Notes, newer editors |
| Offline / P2P | Harder | **Natural fit** |

> **Interview:** know both. OT = central server + transform (compact, but hard transforms). CRDT = commutative ops with unique ids that merge anywhere (great offline/P2P, more metadata). Modern editors trend to **CRDTs**.

---

## 7. Real-Time Sync Architecture

```
Clients ⇄ WebSocket ⇄ Collaboration Server (per-document session)
                            │  authoritative revision + in-memory doc state
                            ▼
                     Op log store (append-only) + periodic snapshots (durable)
                            │
                        Kafka / Redis pub-sub (fan-out across nodes; persistence; history)
```

- **Session server per document** (doc sharded to a node) holds the authoritative revision + in-memory state; **sticky routing** so all editors of a doc hit the **same node** (they must serialize through one authority for OT).
- Ops appended to a **per-doc op log**; **snapshots** periodically so you don't replay millions of ops.
- **WebSocket** for low-latency bidirectional sync; ack + revision numbers.
- Multi-node fan-out (if a doc's editors span nodes) via a pub-sub bus — but usually one doc = one owning node.

---

## 8. Presence, Cursors & Comments

- **Cursors/selections + presence** are **ephemeral** → broadcast on the same channel, kept in Redis (not durably persisted).
- **Comments/suggestions** must **anchor to a position that survives edits** — anchor to a **stable op id / character id / range**, not an absolute offset (which shifts as text changes). When the anchored text is edited/deleted, reposition or orphan the comment.

---

## 9. Persistence & Versioning

```
Persistence = latest SNAPSHOT + op log SINCE that snapshot
  reconstruct doc = snapshot + replay ops after it
Version history = named snapshots / revision checkpoints
```

- Append-only **op log = source of truth** (Event Sourcing); **snapshots** bound replay cost + speed up load.
- **Autosave** continuously (it's just the op log); history lets you restore any checkpoint / see who changed what.

---

## 10. Undo/Redo in Collaboration

Undo is subtle with multiple editors — you must undo **your own** edit, not whatever happened last globally.

```
Undo = apply the INVERSE of your operation, TRANSFORMED against everything that happened since
  (e.g., you inserted 'X'; others edited around it; undo removes exactly your 'X', shifted correctly)
```

- Each client keeps an **undo stack of its own ops**; undo generates an inverse op that is transformed against intervening remote ops (OT), or removes the specific char id (CRDT).
- Redo re-applies (also transformed). This is why edits are modeled as reversible **Commands**.

---

## 11. Data Model (all tables)

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
    position   INT,               -- OT: index;  CRDT: char id / fractional index
    payload    JSONB,             -- char(s), attributes
    author_id  BIGINT, created_at TIMESTAMP,
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
CREATE TABLE doc_shares ( doc_id BIGINT, link_token VARCHAR(64), role VARCHAR(10), expires_at TIMESTAMP );

CREATE TABLE comments (
    comment_id BIGINT PRIMARY KEY, doc_id BIGINT, author_id BIGINT,
    anchor JSONB,                 -- stable char-id / range reference (survives edits)
    body TEXT, resolved BOOLEAN DEFAULT FALSE, created_at TIMESTAMP
);
CREATE TABLE doc_versions ( version_id BIGINT PRIMARY KEY, doc_id BIGINT, revision BIGINT, name TEXT, created_by BIGINT, created_at TIMESTAMP );

-- Ephemeral (Redis): presence:doc:{id}, cursors:doc:{id}, doc→owning-node routing
```

> **Tables to consider:** documents, **doc_operations** (op log — key), doc_snapshots, doc_permissions, doc_shares, comments, doc_versions, users. Presence/cursors + routing = Redis; images → blob.

---

## 12. API / Protocol

```
WS  /v1/docs/{id}/collab                       # join editing session (auth + permission check)
→ op:      { baseRevision, ops:[{insert/delete/format...}] }
← ack:     { revision }                         # server-assigned global order
← remote:  { revision, ops, authorId }          # others' transformed ops
← presence/cursor: { userId, cursorPos, selection }
REST: GET /v1/docs/{id}         (snapshot + current revision)
      GET /v1/docs/{id}/history
      POST /v1/docs/{id}/comments · PUT /v1/docs/{id}/permissions
```

---

## 13. Sequences

### Edit round-trip (OT)

```
ClientA  CollabServer(doc)  OpLog  ClientB
  │ op@rev10 │                │       │
  ├─────────►│ transform vs ops 11..12 → apply → rev13 │
  │          ├─ append to op log ────►│       │
  │◄─ ack rev13 ─────────────────────┤       │
  │          ├─ broadcast op' rev13 ──────────►│ (ClientB transforms vs its pending, applies)
```

### Offline → reconnect

```
Client edits offline → queues local ops (with base revisions)
On reconnect → send queued ops → server transforms each against everything since baseRev → applies → acks
Client receives all missed remote ops → transforms against its pending → converges
(CRDTs make this cleanest — ops just merge)
```

---

## 14. Consistency & Edge Cases

| Case | Handling |
| --- | --- |
| Concurrent conflicting edits | OT transform / CRDT merge → convergence (both intents preserved) |
| Divergence | Central authority (OT) or commutative ids (CRDT) guarantee convergence |
| Offline edits | Queue local ops → transform/merge on reconnect |
| Undo with others editing | Inverse of *your* op, transformed against intervening ops |
| Comment anchor shifts | Anchor to stable char id/range, not offset; reposition/orphan on delete |
| Server crash | Op log durable → clients resync from last acked rev; rebuild from snapshot + ops |
| Huge op log | Periodic snapshots bound replay; archive old ops |
| CRDT tombstone growth | Garbage-collect tombstones once all replicas have seen the delete |

---

## 15. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Command** | Each edit is an op/command (with inverse for undo) | Uniform apply/undo/redo, log, replay |
| **Memento** | Snapshots / version checkpoints | Restore prior states |
| **Event Sourcing** | Op log as source of truth; snapshot to bound replay | Rebuild by replay; full history |
| **Observer / Pub-Sub** | Broadcast ops + presence to collaborators | Real-time fan-out |
| **Mediator** | Collaboration server coordinates clients (OT ordering) | Central authority |
| **Strategy** | Conflict resolution (OT vs CRDT), serialization | Swap algorithm |
| **State** | Document/session lifecycle | Manage editing state |
| **Proxy** | WebSocket gateway | Connection handling |
| **Ports & Adapters** | Storage, pub-sub, auth | Swap infra |

> The **op log + snapshots** design *is* Event Sourcing; edits *are* the Command pattern; undo is the command's inverse (transformed); version history is Memento.

---

## 16. Scaling & Failure

- **Shard docs to session servers**; **sticky routing per doc** so edits serialize through one authority (OT). Hot docs stay in-memory on one node.
- **Op log append + periodic snapshots** bound storage/replay; archive old ops; GC CRDT tombstones.
- **Server crash** → clients reconnect, resync from last acked revision (op log durable); rebuild in-memory state from snapshot + ops.
- **Offline edits** → queue + transform/merge on reconnect (CRDTs cleanest).
- Fan-out is small (editors per doc) → cheap real-time broadcast; pub-sub only if a doc spans nodes.

---

## 17. Interview Cheat Sheet

> **"How do concurrent edits not conflict?"**
> "Edits are fine-grained **operations** tagged with a base revision. In **OT**, a central server orders them and **transforms** each op against concurrent ops so intent is preserved (positions shift correctly), then broadcasts — all clients converge. In **CRDTs**, each char has a unique ordered id so ops commute and merge in any order without a central transform."

> **"OT vs CRDT?"**
> "OT = central server orders + transforms ops (Google Docs) — compact but the transform functions are hard. CRDT = ops carry unique ids and merge anywhere — great for offline/P2P but more metadata/tombstones. Modern editors trend to CRDTs."

> **"How is the doc persisted / versioned?"**
> "Append-only **op log** (Event Sourcing) as source of truth + periodic **snapshots** to bound replay; reconstruct = snapshot + replay ops. Version history = named revision checkpoints."

> **"Real-time sync architecture?"**
> "WebSocket to a **per-doc session server** (sticky routing so all editors hit one authority); ops get acked with revision numbers and broadcast; presence/cursors ephemeral in Redis."

> **"Undo with multiple editors?"**
> "Undo your own op's inverse, transformed against everything that happened since — not a global 'undo last'. Edits are reversible Commands with an undo stack."

---

## 18. Final Takeaways

- Send **operations, not the whole doc**; converge via **OT** (central transform) or **CRDT** (commutative ids that merge anywhere).
- **Op log (Event Sourcing) + snapshots** = durable source of truth with bounded replay; enables history + undo.
- **Per-doc session server + sticky WebSocket routing** (one authority per doc); hot docs in-memory.
- **Presence/cursors** ephemeral (Redis); **comments** anchored to stable char ids (survive edits).
- **Undo** = inverse of *your* op, transformed; **offline** = queue + merge on reconnect (CRDT-friendly).
- Patterns: Command, Event Sourcing, Memento, Observer, Mediator, Strategy (OT/CRDT), State.

### Related notes

- [Real-Time Communication](../concepts/real-time-communication.md) — WebSocket, scaling persistent connections
- [Apache Kafka](../concepts/kafka.md) — op fan-out / persistence backbone
- [Notification System](notification-system-design.md) — WebSocket real-time delivery overlap
