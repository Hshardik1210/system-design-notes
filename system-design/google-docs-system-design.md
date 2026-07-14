# Collaborative Document Editor — System Design (Google Docs)

> **Core challenge:** let **many users edit the same document simultaneously** and see each other's changes in **real time**, with **no lost edits** and **guaranteed convergence** (everyone ends with identical content) — despite concurrent, conflicting edits and network delays. The heart is **conflict resolution**: **Operational Transformation (OT)** or **CRDTs**.

> **How to read this doc:** each section has the dense interview summary first, then a **deep dive** (annotated code, and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand.

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
- [15. Scaling & Failure](#15-scaling--failure)
- [16. Interview Cheat Sheet](#16-interview-cheat-sheet)
- [17. Consistency & CAP Tradeoffs](#17-consistency--cap-tradeoffs)
- [18. How to Drive the Interview (framework)](#18-how-to-drive-the-interview-framework)
- [19. Design Patterns (that can be used)](#19-design-patterns-that-can-be-used)
- [20. Final Takeaways](#20-final-takeaways)

---

## 1. Mental Model

```
Each editor sends small OPERATIONS ("insert 'x' at pos 5", "delete pos 8")
   → server orders + TRANSFORMS them against concurrent ops
   → broadcasts transformed ops to all clients
   → every client applies them → all CONVERGE to identical text
```

You do **not** send the whole document on every keystroke — you send **operations**, resolve conflicts, and broadcast. The two invariants: **causality** (see edits in a sensible order) and **convergence** (everyone ends identical).

### What problem are we even solving?

Picture a single Google Doc open on **five laptops at once** — you, three teammates, and your manager — all typing into the **same paragraph** at the **same moment**. Everyone should see everyone else's letters appear almost instantly, nobody's typing should get erased, and when the dust settles **all five screens must show the exact same text**. That's the whole job.

The naive idea — "whenever someone types, upload the whole document and overwrite the server copy" — fails badly:

- If you and I both save at nearly the same time, **the last save wins and wipes out the other person's edits.** That's a lost edit, and it's unacceptable.
- Sending the entire document on every keystroke is **huge and slow** for a big doc.

So instead of shipping the *whole document*, each editor ships **tiny descriptions of what changed** — "insert the letter `x` at position 5", "delete the character at position 8". These are called **operations** (ops). A central authority then figures out how to fit everyone's ops together without anyone overwriting anyone else's edits.

```java
// We never send "here is the whole document". We send small operations like these:
class Operation {
    String type;   // "INSERT" or "DELETE"
    int    pos;    // where in the text
    String ch;     // the character(s) for an insert
}

// You type "x" at the start of "HELLO":
Operation op = new Operation("INSERT", 0, "x");   // <-- this tiny thing is what travels over the network
```

### Why sending operations is better than sending the whole document

- **Small + fast.** An op is a few bytes; a document can be megabytes. Real-time typing needs tiny messages.
- **Mergeable.** Two ops ("insert at 0" and "delete at 4") can both be applied so *both people's intent survives*. Two whole-document saves can't merge — one just clobbers the other.

### What "causality" and "convergence" actually mean

- **Causality** = edits are seen in a sensible order (if I reply to your sentence, everyone should see your sentence before my reply).
- **Convergence** = no matter what order the ops arrive in on each laptop, **everyone ends up with identical text**. This is the promise the whole system exists to keep.

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

### What the requirements really ask for

Think of the features as promises you make to the people in the doc:

| Requirement | What it means in plain words |
| --- | --- |
| **Real-time** | When your teammate types, you see it within a blink (~100ms) — it feels like you're in the same room. |
| **Convergence** | Everyone's screen ends up identical, always. No "my copy says X, yours says Y." |
| **No lost edits** | If two people type at once, *both* survive. Nobody's work silently disappears. |
| **Cursors & presence** | You can see the little colored cursors of other people and who's currently in the doc. |
| **Comments/suggestions** | Notes attached to specific words that stay attached even as text moves around. |
| **Version history** | You can scroll back in time and restore an older version. |
| **Undo/redo** | Ctrl+Z removes *your* last change, even while others keep typing. |
| **Offline edits** | You can keep typing on a plane with no wifi; it all syncs when you reconnect. |

The two words that make this hard are **"simultaneously"** (everyone at once) and **"convergence"** (yet everyone identical). Almost every design decision later exists to keep those two promises at the same time.

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

### How big is this, really?

Compare it to something like Twitter. On Twitter, one celebrity tweet fans out to **millions** of followers — that's a "wide" broadcast problem. Google Docs is the opposite: a single doc usually has **a handful of editors** (1–100), so sending an edit to "everyone in the doc" is **cheap**.

The hard part isn't *volume*, it's **speed and correctness on one doc**:

- An active typist produces **several ops per second**; a busy meeting-notes doc might see hundreds of ops/sec — but only across a few people.
- Each op is **tiny** (a few bytes), *but they never stop*, so the list of ops for a hot doc grows fast. If we replayed millions of ops every time someone opened the doc, loading would crawl → that's why we take periodic **snapshots** (a saved full copy) so we only replay ops *since* the snapshot (see §9).

```
One doc's history:  [snapshot @ rev 10000]  +  op 10001, op 10002, ... op 10250
Open the doc = load the snapshot, then apply just the ~250 recent ops. Fast.
```

Key mental shift: we scale **per document**, not globally. Each doc is its own little live session. A billion docs is fine because they're independent; the challenge is making *one* doc's session fast and always-convergent.

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

### Why edits "drift apart"

The root problem: both people made their edit while looking at the **same starting text** ("HELLO"). Neither knew about the other's edit yet — the edits are **concurrent**. Now each laptop has to apply *both* edits, but the edits reference **positions** ("position 4"), and one edit can **shift** the positions the other one assumed. If A inserts a character at the start, everything slides right by one, so B's "delete position 4" no longer points at the character B meant to delete.

Here's the divergence in code — same two ops, applied in different orders, giving **different results**:

```java
// Start: "HELLO"
// A = insert 'X' at pos 0     B = delete char at pos 4 (the 'O')

// Laptop 1 applies A then B:
"HELLO" --A--> "XHELLO"        // now pos 4 is the second 'L', not 'O'!
        --B--> "XHELO"         // WRONG: deleted an 'L', kept the 'O'

// Laptop 2 applies B then A:
"HELLO" --B--> "HELL"
        --A--> "XHELL"         // this is what we actually want

// Laptop1 = "XHELO",  Laptop2 = "XHELL"  ->  DIVERGED. Two people, two different docs. 
```

The fix can't be "pick one and throw the other away" (that loses an edit). We need to **adjust the positions** so both ops land where the humans *intended*, no matter what order they're applied. That adjustment is the job of **Operational Transformation (§5)** or, alternatively, **CRDTs (§6)**.

#### Q: Why not just lock the document while someone types?

Because then only one person could edit at a time — that's not collaboration, that's taking turns. The whole point is *simultaneous* editing, so we need to merge concurrent edits, not prevent them.

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

### "Transform" = shift the position so intent survives

The one idea in OT: when two edits happened at the same time, the server **rewrites the positions** in one op to account for the other, so applying them in the server's chosen order still does what each person meant.

Concretely: suppose your op is "delete the character at position 10." If a concurrent op inserts 3 characters before position 10, everything after shifts right, so what you meant is now at position 13. OT **transforms** your op to "delete position 13" so it still targets the character you actually meant.

```java
// transform(myOp, otherOp) returns myOp adjusted for the fact that otherOp already happened.
// Example: someone INSERTED text before my position -> my position must shift right.

Operation transform(Operation mine, Operation other) {
    if (other.type.equals("INSERT") && other.pos <= mine.pos) {
        // an insert happened at or before me -> everything after it slid right by 1
        return new Operation(mine.type, mine.pos + 1, mine.ch);
    }
    if (other.type.equals("DELETE") && other.pos < mine.pos) {
        // a delete happened before me -> everything after it slid left by 1
        return new Operation(mine.type, mine.pos - 1, mine.ch);
    }
    return mine;   // the other edit was after me -> my position is unaffected
}
```

Walk through the "HELLO" example that diverged in §4, now with transform:

```java
// Start "HELLO".  A = insert('X', 0).  B = delete(4).  Server picks order: B then A.
"HELLO" --B--> "HELL"

// Now transform A against B. B deleted pos 4, which is AFTER A's pos 0,
// so A's position is NOT affected -> A stays insert('X', 0).
"HELL"  --A'--> "XHELL"   // correct, and every client that runs this converges to "XHELL"
```

### The un-acked buffer (why your typing feels instant)

Google Docs feels instant because your client **does not wait** for the server. It applies your keystroke **locally right away**, and *also* remembers it in a **pending buffer** until the server confirms (acks) it. When a remote op arrives from someone else, the client first **transforms it against your still-pending ops** before showing it — so your local edits and their edits stay consistent.

```java
class ClientDoc {
    List<Operation> pending = new ArrayList<>();   // my ops the server hasn't acked yet
    long lastAckedRevision = 0;

    // I type: show it immediately (optimistic), and remember it as pending.
    void onLocalEdit(Operation op) {
        applyLocally(op);
        pending.add(op);
        server.send(op, lastAckedRevision);   // tell the server, but DON'T block on it
    }

    // A remote op arrives: transform it past my pending ops, THEN apply.
    void onRemoteOp(Operation remote) {
        for (Operation mine : pending) {
            remote = transform(remote, mine);   // account for edits I made that the server hasn't merged yet
        }
        applyLocally(remote);
    }

    // Server confirms one of my ops: drop it from pending.
    void onAck(long revision) {
        pending.remove(0);
        lastAckedRevision = revision;
    }
}
```

### Why OT needs a central server

*Someone* has to pick the **one official order** of edits and be the authority that assigns revision numbers. Every client transforms against that single authority's order, which is what guarantees everyone converges. (This is why one doc is pinned to one "owning" server — see §7.)

### Why transform functions are considered "hard"

There are many op-pair cases: insert-vs-insert, insert-vs-delete, delete-vs-delete, formatting-vs-delete, ties at the *same* position, etc. Each must be provably correct so that *both* orders reach the same result. Getting a single case subtly wrong causes rare divergence bugs — which is exactly the pain CRDTs (§6) try to avoid.

### A second worked example — the tricky "same position" cases

The first example (insert-vs-delete at *different* spots) was the easy case. The two that actually trip people up are when both ops touch the **same position**.

**Delete-vs-delete at the same position.** Both users delete the *same* character. The naive result is that the position gets deleted twice — but the second delete would then remove an *innocent* neighbor.

```java
// Start "HELLO". A = delete(1) (the 'E').  B = delete(1) (also the 'E').  Order: A then B.
"HELLO" --A--> "HLLO"    // 'E' is gone; what was pos 2 ('L') is now pos 1

// transform B against A: A already deleted pos 1. B wanted to delete that SAME char.
// The char is already gone -> B becomes a NO-OP (don't shift it left onto the 'L'!).
"HLLO"  --B'--> "HLLO"   // correct: the shared 'E' is deleted exactly once
```

> ⚠️ **pitfall:** the subtle bug is turning a redundant delete into a *real* one. If B blindly did `delete(1)` after A, it would erase the `L` that slid into position 1 — a phantom deletion. The transform must recognize "that character is already deleted" and collapse B to a no-op.

**Insert-vs-insert at the same position (the tie-break).** Both users insert *at* position 1. Which character goes first? If two replicas break the tie differently, they diverge (`XY` vs `YX`). OT breaks ties with a **stable, deterministic rule** — e.g. compare a `siteId`/`userId` — so *every* replica orders them identically.

```java
// Start "HI". A = insert('X', 1) by user 7.  B = insert('Y', 1) by user 3.
// Both target pos 1. Tie-break by userId: lower id goes LEFT.  user3 (Y) < user7 (X).

// transform A('X'@1) against B('Y'@1): B has the lower id, so it lands first ->
// A must shift right past B -> A' = insert('X', 2)
"HI" --B--> "HYI"  --A'--> "HYXI"   // every replica applies this rule -> all get "HYXI"
```

> 💡 **tip:** the interview soundbite for ties is *"break ties with a deterministic total order (e.g. by user/site id), so every replica resolves a same-position conflict the same way."* This is exactly the ordering that CRDTs bake into their character ids (§6) instead of computing at transform time.

---

## 6. CRDTs (the alternative)

**Conflict-free Replicated Data Types** — data structures whose ops **merge automatically** in any order, no central transform.

- Each character gets a **globally unique, totally-ordered identifier** (e.g. a **fractional index** between neighbors, or an id like `(siteId, counter)` as in RGA/Logoot).
- Insert = place a char with an id between two existing ids; delete = **tombstone** the id. Because ids are unique + ordered, applying ops in any order converges — no positional transform needed.

> 💡 **tip:** **RGA** (Replicated Growable Array) and **Logoot/LSEQ** are the two id-scheme families you can name-drop. RGA gives each char an id like `(siteId, counter)` and links it after its left neighbor; Logoot/LSEQ use a dense **fractional/path index** so there's always room to insert between two ids. Both aim for the same thing: an id that sorts deterministically on every replica.

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

### Give every character a permanent id

OT fights over *positions* (which shift constantly). CRDTs sidestep the fight entirely: **every character gets its own globally-unique, permanent id** that it keeps forever. Once a character has a stable id, you never say "position 4" again; you say "the character with id `X`", which can never be confused.

The common scheme is a **fractional index**. Characters are numbered 1, 2, 3. To insert *between* 1 and 2, you don't renumber everything — you assign the new character **1.5**. Need another between 1 and 1.5? Assign **1.25**. Every replica agrees where id 1.25 sorts, regardless of the order ops arrive. To delete, you don't remove the character — you mark it as deleted (a **tombstone**) so no replica re-inserts around a gap that used to exist.

> ⚠️ **pitfall:** a real `double` runs out of precision fast — repeatedly typing between two adjacent characters (1.5, 1.25, 1.125, …) exhausts the mantissa and two inserts collapse to the *same* id, breaking the total order. Production CRDTs use **arbitrary-precision keys** (a list/string of digits like a "path", as in Logoot/LSEQ) that can always grow another level, not a fixed-width float. Treat the `double` above as a teaching simplification.

```java
// Each character carries an id that sorts it into place. No positions, ever.
class CrdtChar {
    double id;        // fractional index: 1, 2, 3 ... insert between -> 1.5, 1.25, etc.
    char   value;
    boolean deleted;  // "tombstone" — kept, but hidden, so merges stay consistent
}

// "HELLO" -> H(1) E(2) L(3) L(4) O(5)

// A inserts 'X' before H: pick an id smaller than 1, e.g. 0.5
CrdtChar x = new CrdtChar(0.5, 'X', false);

// B deletes O: don't remove it, tombstone it
o.deleted = true;

// To render, sort by id and skip tombstones:  X(0.5) H(1) E(2) L(3) L(4)  ->  "XHELL"
// ANY replica applying these two ops in ANY order gets "XHELL". No transform needed.
```

Because each op refers to an **absolute id** (not a position that shifts), two replicas can apply the same set of ops in **any order** and still land on identical text. That property is called **commutativity**, and it's the whole magic of CRDTs.

> 💡 **tip:** **commutative ops** = "order doesn't matter to the final result." That's the one-word difference vs OT: OT makes ops safe by *rewriting* them for a chosen order (needs a central authority to pick the order); CRDTs make ops *inherently* order-independent, so any replica can merge with no coordinator. When an interviewer asks "why do CRDTs work offline/P2P?", the answer is "because their ops commute."

#### Q: What is a "tombstone" and why keep deleted characters around?

A **tombstone** is a character you mark as deleted instead of actually removing. Why keep it? Imagine you delete char `L(4)` while, at the same time, a teammate inserts a character "right after `L(4)`". If you truly erased `L(4)`, their insert would point at a **hole** and merges could disagree. Keeping the tombstone gives every replica a stable anchor so merges stay consistent. The downside: tombstones pile up (memory), so they're **garbage-collected** once every replica has definitely seen the delete (see §14).

> ⚠️ **pitfall:** you can't garbage-collect a tombstone the instant it's deleted. If replica C is offline and later replays an op that references that id, GC-ing too early re-introduces the hole problem. Safe GC needs proof that **every** replica has seen the delete (e.g. version vectors / a stable causal cutoff), which is why long-lived docs with lots of edits carry real tombstone overhead — the classic CRDT memory cost.

### If CRDTs are simpler to merge, why did Google Docs use OT

OT came first and is very **compact** (ops are tiny, no per-char id overhead), and Google Docs already has a reliable central server, so OT's biggest weakness (needing a central authority) wasn't a problem for them. CRDTs shine when you *don't* want a central authority — offline-first apps, peer-to-peer, or local-first tools — which is why **newer** editors lean CRDT.

#### Q: OT vs CRDT — which do I pick in an interview?

Don't pick blindly; pick from the **deployment shape**, and say why in one breath:

- **Is there already a trusted central server that every edit flows through?** → **OT** is a fine, compact choice (this is Google Docs). One authority orders + transforms; ops stay tiny.
- **Do edits need to merge with no coordinator — offline-first, peer-to-peer, or local-first?** → **CRDT**, because its ops commute and merge in any order (Figma, Yjs/Automerge, Apple Notes).
- **Optimizing for memory / huge docs and you already have a server?** → lean **OT** — no per-character id or tombstone overhead.
- **Optimizing for simplicity of the merge logic / correctness under weird network partitions?** → lean **CRDT** — no hairy transform matrix to prove correct.

The trap answer is "CRDTs are newer so they're better." The senior answer is: *"OT if I have a central authority and want compact ops; CRDT if I need coordinator-free merging (offline/P2P) and can pay the metadata cost."* If you truly can't decide, default to **OT for a server-centric Google-Docs-style product** and call out that you'd revisit if offline/P2P became a hard requirement.

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

### One doc, one authoritative server, over a persistent connection

Two ideas make real-time sync work: an always-open connection (**WebSocket**) and a single authority per doc (**sticky routing**).

**WebSocket = a persistent two-way connection.** A normal HTTP request is one-shot request/response — too slow and one-directional for live typing. A **WebSocket** stays open so either side can send at any moment, instantly, without a new handshake. The server can *push* a teammate's edit to you the millisecond it happens.

**Sticky routing = every editor of one doc talks to the SAME server.** Remember from §5 that OT needs one authority to pick the official order of edits. So all editors of doc #42 are routed to the **one node that owns doc #42**. That node keeps the current document in memory and stamps each incoming op with the next revision number — it is the single ordering authority.

> ⚠️ **pitfall:** **sticky routing** is not the same as a dumb round-robin load balancer. If a plain LB spread doc #42's editors across three nodes, you'd have three "authorities" assigning conflicting revision numbers → divergence. You need routing that maps `doc_id → owning node` (consistent hashing / a routing table in Redis), and a plan for **re-homing** the doc when that node dies (see the crash Q below). Sticky-by-user-session is also wrong here — it must be sticky **by document**.

```
   You ─┐
Teammate├──WebSocket──►  Node 3  (owns doc #42, holds it in memory) ──► append op to log
Manager ─┘                  │                                         (durable source of truth)
                            └──WebSocket──► pushes each merged op back to all 3 of you
```

Here's the ordering-authority loop in code:

```java
class CollabSession {          // one instance PER document, living on its owning node
    String docText;            // authoritative in-memory copy
    long   revision = 0;       // the official global order counter for this doc

    // called when ANY editor of this doc sends an op over their WebSocket
    synchronized void onOp(Operation incoming, long baseRevision, Client sender) {
        // 1. transform the op against everything that happened since the client's base
        Operation merged = transformAgainstOpsSince(incoming, baseRevision);

        // 2. apply it and assign the next official revision number
        docText = apply(docText, merged);
        long newRev = ++revision;

        // 3. persist it (durability) ...
        opLog.append(docId, newRev, merged);

        // 4. ... ack the sender and broadcast to everyone else on this doc
        sender.send(new Ack(newRev));
        for (Client other : editorsExcept(sender)) {
            other.send(new RemoteOp(newRev, merged, sender.userId));
        }
    }
}
```

### Permission check on join (ACL)

Before a client is allowed to stream edits, the **WebSocket join** must pass an **authorization check** — anyone can *attempt* to open the socket, but only users with the right role may actually edit. The gate happens **once at join time** (then the session trusts the connection), and the role comes from the `doc_permissions` table (§11): `OWNER` / `EDITOR` may send ops, `COMMENTER` may only attach comments, `VIEWER` gets read-only + presence.

```
Client ──WS CONNECT /v1/docs/42/collab (auth token)──►  Collab node (owns doc 42)
   1. verify token           → who is this user?          (else 401, close socket)
   2. look up doc_permissions → role for (doc 42, user)?   (else 403, close socket)
   3. role gives edit?  EDITOR/OWNER → allow op stream
                        COMMENTER    → comments only, reject INSERT/DELETE ops
                        VIEWER       → read + presence only, reject all writes
   4. attach connection to the doc's CollabSession → start streaming
```

```java
// Runs when a WebSocket tries to JOIN a document's session. Reject before streaming any ops.
void onJoin(WebSocket ws, String token, String docId) {
    User user = auth.verify(token);                 // 401 if the token is bad/expired
    if (user == null) { ws.close(4401, "unauthenticated"); return; }

    Role role = permissions.roleFor(docId, user.id); // from doc_permissions (§11)
    if (role == null || role == Role.NONE) { ws.close(4403, "forbidden"); return; }

    session(docId).attach(ws, user, role);          // role is remembered on the connection
}

// Every incoming op is re-checked against the role the connection joined with.
synchronized void onOp(Operation op, Connection conn) {
    if (!conn.role.canEdit()) { conn.reject("read-only / comment-only role"); return; }
    // ... otherwise transform + apply + broadcast as in CollabSession.onOp ...
}
```

> ⚠️ **pitfall:** don't check permissions **only** at join. Access can be revoked mid-session (owner removes an editor), so re-validate each op against the connection's role, and drop the socket if the permission is revoked. A share-link token (`doc_shares`, §11) also carries a role and can expire — treat it the same way.

### Why all editors of a doc hit the same node

For **OT you need one authority** to order edits, so yes — one doc lives on one node at a time. It's not a real bottleneck because a single doc has few editors and ops are tiny; one node handles it easily. Different docs live on different nodes, so the *system* scales by spreading millions of docs across many nodes (this is **sharding by doc**, see §15).

### What if editors are connected to different nodes

Usually they aren't (sticky routing sends them all to the owner). If a doc's editors truly span nodes, the nodes relay ops to each other over a **pub-sub bus** (Kafka/Redis). But the common, simplest case is "one doc = one owning node."

#### Q: Why WebSocket, not SSE or long-polling?

Because editing is **bidirectional and continuous**: you're constantly *sending* your keystrokes **and** *receiving* everyone else's, both with sub-100ms latency. **Long-polling** reopens a request for every message — huge overhead and latency for keystroke-rate traffic. **SSE (Server-Sent Events)** is a great fit for *server→client* push but it's **one-directional** — you'd still need a separate HTTP channel for the client's ops, splitting one conversation across two transports and complicating ordering/acks. A single **WebSocket** carries ops, acks, remote ops, cursors, and presence over **one** long-lived, full-duplex connection — exactly the shape of a live editing session.

> 💡 **tip:** one-liner — *"WebSocket because edits are two-way and high-frequency; SSE is push-only and polling is too chatty for keystroke latency."*

#### Q: What happens if that owning node crashes?

The op log is durable, so clients just reconnect, the doc gets re-homed on another node, and it **rebuilds its in-memory state from the last snapshot + the ops after it** (see §9 and §15). Clients resync from their last acked revision — no edits lost.

---

## 8. Presence, Cursors & Comments

- **Cursors/selections + presence** are **ephemeral** → broadcast on the same channel, kept in Redis (not durably persisted).
- **Comments/suggestions** must **anchor to a position that survives edits** — anchor to a **stable op id / character id / range**, not an absolute offset (which shifts as text changes). When the anchored text is edited/deleted, reposition or orphan the comment.

### The colored cursors are "throwaway" info

Those little colored cursors and name flags showing where teammates are? That data is **ephemeral** — throwaway. If it's lost for a second, nobody cares; it'll be resent on the next keystroke. So we **don't** write it to the durable op log. We just broadcast it and cache the latest value in **Redis** (fast, in-memory).

Unlike the document text (permanent, saved forever), a cursor position is only useful live — there's no reason to persist it, and when a user leaves it simply disappears.

```java
// Cursor / presence: broadcast, cache in Redis, never persisted to the op log.
void onCursorMove(String docId, String userId, int cursorPos) {
    redis.setEx("cursors:doc:" + docId + ":" + userId, cursorPos, 30 /* sec TTL */);
    broadcastToDoc(docId, new CursorUpdate(userId, cursorPos));   // fire and forget
}
// TTL means: if a user goes silent for 30s, their cursor auto-vanishes ("left the doc").
```

### Comments must "stick" to words, not positions

A comment says "this sentence is unclear." If you anchor that comment to **"position 120"** and someone adds a paragraph above, position 120 now points at a *totally different* sentence — the comment slides onto the wrong text. So we anchor comments to a **stable id** (the same kind of permanent character id from CRDTs, §6), not a number that shifts. An offset breaks as soon as text is inserted before it; a stable id stays attached to the exact characters it was placed on.

```java
class Comment {
    String body;
    String anchorStartId;   // stable char id where the comment begins (NOT an offset like 120)
    String anchorEndId;     // stable char id where it ends
}

// Text shifts around freely; the comment still points at the right words
// because it's tied to the characters' ids, not their current positions.
// If the anchored characters are ALL deleted -> the comment is "orphaned"
// (shown as "comment on deleted text") instead of silently jumping elsewhere.
```

### Why not just save cursors in the database like the text

Cursors change many times per second and are worthless a moment later. Persisting them would be a huge write load for data nobody ever needs to recover. Redis + broadcast is cheap and perfectly good for "live, throwaway" state.

### What happens to a comment when its anchored text is deleted

You either **reposition** it (snap it to the nearest surviving text) or mark it **orphaned** ("original text was deleted"). The one thing you must *not* do is let it silently latch onto unrelated text — that's why offsets are a trap and stable ids are the fix.

---

## 9. Persistence & Versioning

```
Persistence = latest SNAPSHOT + op log SINCE that snapshot
  reconstruct doc = snapshot + replay ops after it
Version history = named snapshots / revision checkpoints
```

- Append-only **op log = source of truth** (Event Sourcing); **snapshots** bound replay cost + speed up load.
- **Autosave** continuously (it's just the op log); history lets you restore any checkpoint / see who changed what.

### The doc is an op log, not a saved file

Most apps save "the current file." A collaborative editor instead keeps the **complete list of every edit ever made** — the **op log** — and treats *that* as the truth. The current document is just what you get by **replaying** all those edits from the start. This is called **Event Sourcing**: you store the sequence of changes, and the current state is *derived* by replaying them. Benefits: perfect history ("who changed what, when"), and you can reconstruct the document at any past revision.

But replaying **millions** of edits every time someone opens the doc would be painfully slow. So periodically we save a **snapshot** — a full copy of the document at a certain revision. To open the doc, load the latest snapshot and replay only the *few* edits since it.

```java
// The doc = latest snapshot + the ops that came after it.
Document loadDoc(String docId) {
    Snapshot snap = db.latestSnapshot(docId);        // e.g. full text at revision 10000
    List<Operation> recent = db.opsAfter(docId, snap.revision);  // ops 10001..10250 only
    Document doc = snap.toDocument();
    for (Operation op : recent) {
        doc = apply(doc, op);                         // replay just the recent handful
    }
    return doc;
}

// Every few hundred/thousand ops, save a fresh snapshot so replay stays short.
void maybeSnapshot(String docId, long revision) {
    if (revision % 1000 == 0) {
        db.saveSnapshot(docId, revision, currentText(docId));
    }
}
```

### The difference between a snapshot and version history

A **snapshot** is a performance trick (so loading is fast) — automatic and internal. **Version history** is a user feature: **named checkpoints** ("v2 — before the rewrite") that a person can browse and restore. Both are "a saved state of the doc," but snapshots exist to bound replay cost, while versions exist for humans to time-travel.

#### Q: Where does "autosave" come from — is there a Save button?

There's no Save button because **every keystroke is already an op appended to the durable log.** "Autosave" is just the natural side effect of logging every op. Nothing is ever unsaved.

---

## 10. Undo/Redo in Collaboration

Undo is subtle with multiple editors — you must undo **your own** edit, not whatever happened last globally.

```
Undo = apply the INVERSE of your operation, TRANSFORMED against everything that happened since
  (e.g., you inserted 'X'; others edited around it; undo removes exactly your 'X', shifted correctly)
```

- Each client keeps an **undo stack of its own ops**; undo generates an inverse op that is transformed against intervening remote ops (OT), or removes the specific char id (CRDT).
- Redo re-applies (also transformed). This is why edits are modeled as reversible **Commands**.

### Ctrl+Z undoes YOUR edit, not the last thing that happened

Solo apps make undo easy: reverse whatever happened last. In a shared doc that's wrong. If your teammate typed after you, and you hit Ctrl+Z, you expect to undo **your own** last edit — not delete *their* sentence! Undo must reach back to *your* most recent op, not the globally most recent op.

So each client keeps a **personal undo stack of its own ops**. Undo = create the **inverse** of your op (insert → delete, delete → re-insert), but first **transform** it against everything that happened since, so it lands in the right place.

```java
class UndoManager {
    Deque<Operation> myUndoStack = new ArrayDeque<>();   // only MY ops

    void onMyEdit(Operation op) {
        myUndoStack.push(op);
    }

    void undo() {
        Operation original = myUndoStack.pop();
        Operation inverse  = invert(original);   // inserted 'X' -> now delete that 'X'

        // others edited since I did -> shift my inverse so it hits the RIGHT spot
        for (Operation since : opsSince(original)) {
            inverse = transform(inverse, since);
        }
        applyAndBroadcast(inverse);
    }

    Operation invert(Operation op) {
        return op.type.equals("INSERT")
            ? new Operation("DELETE", op.pos, op.ch)   // undo an insert = delete it
            : new Operation("INSERT", op.pos, op.ch);  // undo a delete = put it back
    }
}
```

### Why each edit is modeled as a "Command"

A Command bundles **do** *and* **undo** together (an op plus its inverse). That uniform shape is what makes undo, redo, logging, and replay all work the same way — it's the **Command pattern** (see §19).

### Whether undo works the same in CRDT

Same idea, cleaner mechanics: instead of transforming a position, you just **re-tombstone / un-tombstone the specific character id** you touched. Because ids are stable (§6), there's no position to shift.

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

### Indexes that matter

- `doc_operations (doc_id, revision)` **PK** — the workhorse. The only hot query is *"give me ops after revision N for this doc"* → a **range scan** on this composite key (`WHERE doc_id = ? AND revision > ? ORDER BY revision`). It also enforces one op per `(doc, revision)`, i.e. the global order.
- `doc_snapshots (doc_id, revision)` **PK** — **snapshot lookup**: fetch the newest full copy with `WHERE doc_id = ? ORDER BY revision DESC LIMIT 1`, then range-scan the ops after it. Load cost = one snapshot read + a short op tail.
- `doc_permissions (doc_id, user_id)` **PK** — the **ACL check on WebSocket join** (§7): a point lookup for "what role does this user have on this doc?"
- `comments (doc_id)` — load all comments when opening a doc (filter out `resolved` client-side or add `(doc_id, resolved)`).
- `doc_versions (doc_id, revision)` — list the named checkpoints for the version-history panel.

> 💡 **tip:** notice there is **no** "search the text" index here — the op log is optimized for **append + ordered range scan by doc**, not content search. That single access pattern (`(doc_id, revision)` range scan) is what makes an append-only/log-friendly store the right home for `doc_operations` (see below).

### Database & storage choices (which DB, and why at scale)

The deciding question here isn't raw throughput (fan-out per doc is tiny, §3) — it's *"what makes the doc reconstructable and durable without replaying millions of ops?"* That's why the op log, not the current text, is the source of truth, and it drives every storage pick below.

| Data | Store | Why this one | Why not the alternative |
| --- | --- | --- | --- |
| `doc_operations` (the op log — source of truth) | **Append-only log / wide-column or RDBMS**, partitioned by `doc_id`, ordered by `revision` | Every edit is a small, immutable, ordered write — pure appends, and the only query that matters is "give me ops after revision N for this doc" (a range scan). An append-optimized store makes both the write and the catch-up read cheap. | Storing just "the current doc text" in one mutable row loses history, undo, and the ability to reconstruct any past revision — and two concurrent writers would simply clobber each other (§4). |
| `doc_snapshots` | **RDBMS row per snapshot** (or object storage for very large docs) | Occasional full copies keyed by `(doc_id, revision)` — write-rarely, read-once-per-doc-open. A snapshot + a short tail of ops is far cheaper to load than replaying a doc's entire history. | Without snapshots, opening an old, heavily-edited doc means replaying potentially millions of ops — slow. Snapshots bound that replay cost (§9). |
| `documents`, `doc_permissions`, `doc_shares`, `comments`, `doc_versions` | **RDBMS** | Low-volume, relational metadata (ownership, ACLs, named checkpoints) that benefits from real joins and transactions ("does this user have edit access") — nothing here is write-hot. | A specialized log/NoSQL store buys nothing here and loses the easy joins/constraints a relational engine gives for free. |
| Presence, cursors, doc→owning-node routing | **Redis** | Ephemeral, high-churn, per-keystroke updates that are worthless a moment later (§8) — in-memory with TTL is the natural fit. | Persisting cursor positions to a durable store would be a huge write load for data nobody ever needs to recover. |
| Images/attachments | **Blob store + CDN** | Large immutable bytes, served from the edge. | Bloats the op log/RDBMS and kills cache locality for text edits. |

**Why an op log + snapshots beats storing just the latest doc:** the whole system is built around *reconstructing* state by replay (Event Sourcing, §9), which only works if every historical op is durably kept and cheaply range-scannable per doc — a single "current text" column can't give you history, undo, or safe concurrent merging. Partitioning the log by `doc_id` (with `revision` as the sort key) keeps a single doc's history together, and its owning session server (§7) is the only writer — no cross-partition contention. Scale comes from spreading millions of independent docs' logs across nodes, not from making one log bigger. (See [Databases — Deep Dive](../concepts/databases-deep-dive.md).)

### What each table is for

Don't let the SQL intimidate you — here's the plain purpose of each table:

| Table | In one sentence |
| --- | --- |
| **documents** | The doc's "cover page": title, owner, and what revision it's currently on. |
| **doc_operations** | The **star of the show** — every single edit ever made, in order. This *is* the document (Event Sourcing, §9). |
| **doc_snapshots** | Occasional full copies of the doc so we don't replay millions of ops on open. |
| **doc_permissions** | Who can do what (owner / editor / commenter / viewer). |
| **doc_shares** | The "anyone with the link" tokens and their access level. |
| **comments** | Notes anchored to stable char ids (so they survive edits, §8). |
| **doc_versions** | Human-named checkpoints for the version-history feature. |
| Redis (not a table) | Throwaway live state: cursors, presence, and which node currently owns each doc. |

The key insight is the **primary key on the op log**: `(doc_id, revision)`. That `revision` is the **official global order** for a doc — the authoritative owning server (§7) hands out revision numbers one at a time, and they're what everyone converges against.

```sql
-- Reconstruct a doc: grab the latest snapshot, then the ops after it, in revision order.
SELECT content FROM doc_snapshots
 WHERE doc_id = 42 ORDER BY revision DESC LIMIT 1;         -- newest snapshot

SELECT op_type, position, payload FROM doc_operations
 WHERE doc_id = 42 AND revision > 10000 ORDER BY revision; -- replay just these
```

### Why store operations instead of just the final text in one row

A single "current text" column gives you **no history, no undo, no way to merge concurrent edits, and no audit trail.** The op log gives all of those for free — the current text is just the log replayed (§9). The `position` column even does double duty: an index for OT, or a char id / fractional index for CRDT.

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

### The actual messages flying over the WebSocket

The protocol mixes two channels for two reasons: **WebSocket** for the fast, live stuff (edits, cursors) and plain **REST** for the occasional stuff (load the doc, fetch history, change permissions). Here's what a real editing session looks like as messages:

```jsonc
// 1. You open the doc (REST, one time):
GET /v1/docs/42  ->  { "revision": 10000, "content": "HELLO ..." }   // snapshot to start from

// 2. You type 'X' at the start. Client sends over the open WebSocket:
--> { "type": "op", "baseRevision": 10000,
      "ops": [ { "insert": "X", "pos": 0 } ] }

// 3. Server orders + transforms it, assigns the next revision, and acks YOU:
<-- { "type": "ack", "revision": 10001 }

// 4. Meanwhile a teammate deleted a char. Server pushes their edit to YOU:
<-- { "type": "remote", "revision": 10002,
      "ops": [ { "delete": true, "pos": 4 } ], "authorId": "teammate-7" }

// 5. Cursors flow on the same socket (throwaway, not persisted — see §8):
<-- { "type": "cursor", "userId": "teammate-7", "cursorPos": 3 }
```

Notice **`baseRevision`** on your outgoing op: it tells the server "I made this edit while looking at revision 10000." That's exactly the info the server needs to **transform** your op against anything that happened after 10000 (§5). The **`ack`** with a revision number is your confirmation that your edit is now part of the official order — the client can drop it from its pending buffer.

### Why WebSocket for edits but REST for loading the doc

Loading the doc is a **one-time, request/response** action — REST is perfect. Editing is **continuous, two-way, low-latency** — you need the server to push others' edits to you the instant they happen, which is exactly what a long-lived WebSocket gives you.

#### Q: What is `baseRevision`, and what if it's out of date by the time the server sees it?

`baseRevision` is the client's honest statement: *"I made this edit against the document as it looked at revision N."* It's almost **always** a little stale — between you typing and the server processing, other people's ops may have arrived (say the doc is now at rev N+3). That's not a bug; it's the whole point. The server uses `baseRevision` to know **exactly which ops your edit did not yet account for** (everything from N+1 onward) and **transforms** your op against just those before applying it (§5). So a stale `baseRevision` is expected and handled — the server never rejects you for being behind, it *reconciles* you. The only thing that must be true is that the server can still find the ops after `baseRevision` (they're in the durable op log, or since the last snapshot). This is also why an ack returns a fresh revision number: it advances your `baseRevision` for the next edit.

> 💡 **tip:** think of `baseRevision` as the OT equivalent of a **version/CAS token** (like the seat `version` column in optimistic locking) — it tells the authority what state you assumed, so it can merge rather than clobber.

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

### Reading the round-trip diagram

Follow the edit round-trip step by step:

1. **ClientA types** and sends `op@rev10` — "here's my edit, based on revision 10."
2. **The server (the ordering authority)** sees ops 11 and 12 already happened since rev 10, so it **transforms** A's op past them, applies it, and stamps it **rev13**.
3. It **appends** rev13 to the durable op log (so a crash can't lose it).
4. It **acks ClientA** ("you're rev13 now") and **broadcasts** the transformed op to **ClientB**.
5. **ClientB** transforms that incoming op against its *own* un-acked edits, then applies it — and now A and B match.

The **offline → reconnect** flow is the same machinery, just batched: while offline you pile up ops (each tagged with the base revision it was made against); on reconnect you flush them all, the server transforms each against everything that happened while you were gone, and you pull down all the edits you missed. Everyone re-converges.

```java
// Offline queue: keep editing locally, sync the backlog when the network returns.
class OfflineQueue {
    List<Operation> queued = new ArrayList<>();     // ops made while disconnected
    long baseRevision;                              // last revision I saw before going offline

    void onReconnect() {
        for (Operation op : queued) {
            server.send(op, baseRevision);          // server transforms each against ops since baseRevision
        }
        queued.clear();
        pullMissedRemoteOps(baseRevision);          // catch up on what others did while I was away
    }
}
```

> Why the note says "CRDTs make this cleanest": with CRDTs there's no base-revision transforming to do — the queued ops carry stable ids and simply **merge** on reconnect, in any order. That's why offline-first apps love CRDTs (§6).

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

### The edge cases in one breath

Each row above is really the same promise ("no lost edits, everyone converges") tested by a different nasty situation:

- **Two people type at once** → OT transforms / CRDT merges so **both** survive (§4–6).
- **You go offline and keep typing** → your ops queue up and merge back in on reconnect (§13).
- **You hit undo while others edit** → you undo *your* op, shifted to the right spot — not the global "last thing" (§10).
- **A comment's text gets deleted** → the comment orphans or repositions, it never silently jumps to unrelated words (§8).
- **The server crashes** → the op log is durable, so clients resync from their last acked revision and the doc rebuilds from snapshot + ops (§9, §15). No edits lost.
- **The op log gets huge / tombstones pile up** → snapshots bound replay, and tombstones are garbage-collected once every replica has seen the delete (§6, §9).

The unifying trick: because **edits are stored as an ordered, durable log** and merged by a **provably convergent** rule (OT or CRDT), almost every failure becomes "reconnect and replay from the last known point."

---

## 15. Scaling & Failure

- **Shard docs to session servers**; **sticky routing per doc** so edits serialize through one authority (OT). Hot docs stay in-memory on one node.
- **Op log append + periodic snapshots** bound storage/replay; archive old ops; GC CRDT tombstones.
- **Server crash** → clients reconnect, resync from last acked revision (op log durable); rebuild in-memory state from snapshot + ops.
- **Offline edits** → queue + transform/merge on reconnect (CRDTs cleanest).
- Fan-out is small (editors per doc) → cheap real-time broadcast; pub-sub only if a doc spans nodes.

### Scale by spreading docs, recover by replaying the log

Scaling here is refreshingly different from feed systems. There's no giant fan-out; the trick is just **spreading independent docs across many servers**.

Each doc lives on exactly one node, with all of that doc's editors routed to it (sticky routing). To handle more docs, add more nodes. The system scales not by making one node bigger, but by distributing millions of self-contained docs across the fleet.

```
Doc #42  -> Node 3   (all of #42's editors routed here — the "room" for this doc)
Doc #77  -> Node 8
Doc #91  -> Node 3   (a node can host many docs)
   ...spread millions of docs across the fleet by hashing doc_id -> node
```

Recovery reuses the persistence design (§9): because the **op log is durable**, a crashed node loses only *in-memory* state, never edits. The doc simply re-homes on a healthy node and **rebuilds from snapshot + ops**, and clients reconnect and resync from their last acked revision.

```java
// Node crashed -> doc #42 gets re-homed on Node 5, which rebuilds it from durable storage:
CollabSession recover(String docId) {
    Document doc = loadDoc(docId);          // latest snapshot + ops after it (from §9)
    return new CollabSession(docId, doc);   // back in memory, ready; clients reconnect
}
```

#### Q: If one doc = one node, what stops that node from being a bottleneck?

A single doc has few editors and tiny ops, so one node handles it comfortably. The **system** scales because different docs sit on different nodes — you're limited by total docs across the fleet, not by any single doc. Only an unusually "hot" doc (rare) needs the pub-sub relay across nodes.

---

## 16. Interview Cheat Sheet

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

## 17. Consistency & CAP Tradeoffs

> Interviewers love: "Where do you choose consistency vs availability?" For a collaborative editor the answer is **per-path**, and it hinges on one idea: **op ordering within a single document must be strongly consistent, everything else can be eventual.**

| Path | Choice | Why |
| --- | --- | --- |
| **Op ordering within a doc** (the edit write path) | **CP** (per document) | All editors must converge to one text. A single owning authority (§7) assigns a **total order** of revisions → linearizable *per doc*. During a partition of that owning node, the doc briefly **stops accepting edits** rather than fork into two divergent copies. |
| **Session / edit-server availability** | favors **C over A** for that one doc | Because a doc lives on one authority, a crash/partition means a short unavailability window while it **re-homes** (§15) — we accept that to protect convergence. The blast radius is *one doc*, not the platform. |
| **Presence & cursors** | **AP** (eventual) | Ephemeral (§8) — resent on the next keystroke; a stale cursor for a moment is harmless. |
| **Comments / suggestions** | **eventual** | Anchored to stable ids (§8); they can lag a beat and still land on the right text. |
| **Version history, browse, sharing metadata** | **AP** (eventual) | Read-mostly metadata; a few seconds of staleness is fine. |

- Convergence is **strong at the op-ordering layer** (one authority per doc, §5/§7) but the **whole platform stays highly available** because docs are independent — a partition affects only the docs on the down node.
- CRDTs (§6) shift this dial: they relax the need for a single authority, trading *coordinator availability* for *more metadata*, which is why they're the choice for offline/P2P where you can't guarantee one online authority.

> One-liner: **"Strong consistency on the ordering of edits within a document (CP per doc); eventual consistency for presence, comments, history, and across documents."**

---

## 18. How to Drive the Interview (framework)

> Use this order so you never freeze. Spend ~5 min on 1–3, then go **deep on the crux (4)** — that's what they're really testing.

1. **Clarify requirements** (functional + NFRs: real-time, convergence, no lost edits) — §2
2. **Estimate scale** — emphasize the twist: fan-out is tiny (editors per doc), the hard part is **latency + convergence per doc**, and you scale **per document**, not globally — §3
3. **Frame the core problem** — show that concurrent edits **diverge** if applied naïvely, and that last-write-wins loses edits — §4
4. **Deep dive: the crux → conflict resolution** → **OT** (central transform) vs **CRDT** (commutative ids). This is the heart of the whole design — spend the most time here — §5, §6
5. **Real-time sync architecture + data model** — WebSocket + one authority per doc (sticky routing), op log as source of truth — §7, §11
6. **Supporting features** — presence/cursors, persistence & snapshots, undo — §8, §9, §10
7. **Consistency/CAP, scale & failure** — CP per doc, spread docs across nodes, rebuild from snapshot + ops — §17, §15
8. **Summarize tradeoffs + patterns** — §16, §19

> 🎤 **Lead with the core challenge:** open with *"the crux is **conflict resolution** — getting concurrent edits to **converge** to identical text, via **OT** or **CRDTs** (§5–§6)."* State that up front, then let everything else (WebSocket sync, op log, snapshots, presence) hang off it. If you only get to go deep on one thing, make it OT/CRDT convergence — that's the signal they're grading.

---

## 19. Design Patterns (that can be used)

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

## 20. Final Takeaways

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
