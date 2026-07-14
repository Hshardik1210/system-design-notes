# File Storage & Sync — System Design (Dropbox / Google Drive)

> **Core challenge:** store users' files durably and **sync them across all their devices** efficiently — uploading only what changed (**chunking + dedup**), resolving concurrent edits, and sharing. The signature ideas are **content-addressed chunking**, **delta sync**, a **metadata service separate from the block store**, and a **change journal** devices sync against.

> **How to read this doc:** each section has the dense interview summary first, then a **deep dive** (annotated Java/SQL, and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand.

---

## Contents

- [1. Mental Model](#1-mental-model)
- [2. Requirements](#2-requirements)
- [3. Capacity Estimation](#3-capacity-estimation)
- [4. Architecture (client + server)](#4-architecture-client--server)
- [5. Chunking & Deduplication](#5-chunking--deduplication)
- [6. Metadata vs Block Storage](#6-metadata-vs-block-storage)
- [7. Sync — Journal, Cursor & Notifications](#7-sync--journal-cursor--notifications)
- [8. Conflict Resolution & Versioning](#8-conflict-resolution--versioning)
- [9. Sharing & Permissions](#9-sharing--permissions)
- [10. Data Model (all tables)](#10-data-model-all-tables)
- [11. Sequences](#11-sequences)
- [12. Consistency & Edge Cases](#12-consistency--edge-cases)
- [13. Scaling & Failure](#13-scaling--failure)
- [14. Interview Cheat Sheet](#14-interview-cheat-sheet)
- [15. API Design](#15-api-design)
- [16. Consistency & CAP Tradeoffs](#16-consistency--cap-tradeoffs)
- [17. Real-World Extensions](#17-real-world-extensions)
- [18. How to Drive the Interview (framework)](#18-how-to-drive-the-interview-framework)
- [19. Design Patterns (that can be used)](#19-design-patterns-that-can-be-used)
- [20. Final Takeaways](#20-final-takeaways)

---

## 1. Mental Model

```
File → split into CHUNKS → store chunks in block store (dedup by content hash)
     → metadata service tracks which chunks make up which file version
Devices sync against a CHANGE JOURNAL (cursor) → download only NEW chunks they lack
```

Two subsystems: a **metadata service** (small, transactional: files, versions, chunk lists, journal) and a **block store** (big, dumb: chunk bytes). Efficiency = only move **changed chunks**.

### What problem are we even solving?

Imagine you install **Dropbox** (or Google Drive / iCloud) on your laptop, your phone, and your work desktop. You drop a 2 GB video into the Dropbox folder on your laptop, and a few minutes later it just... **appears** on your phone and desktop. Later you fix one typo in a 50-page Word doc, and within seconds the corrected copy shows up everywhere — but it clearly didn't re-upload the whole document, because that would take forever every time. That "magic folder that stays identical on every device" is the whole product.

So the system has to answer four hard questions:

1. **Where do the bytes actually live?** (Your files must survive even if your laptop is stolen and dropped in a lake — **durability**.)
2. **How do we move only the changed part** of a file, not the whole thing every time? (**delta sync** — the typo example.)
3. **How does each device find out something changed** without constantly asking the server "anything new? anything new? anything new?" (**the change journal + notifications**.)
4. **What happens when two devices edit the same file at once?** (**conflict resolution** — never silently lose someone's work.)

Everything else in this doc is just the machinery to answer those four questions cheaply and at massive scale.

### Why not just "upload the whole file to a server"?

First instinct: keep a folder on a server, and whenever a file changes, upload the entire file and download it on the other devices. Why that falls apart fast:

- **Bandwidth explosion** — you change one line in a 2 GB video project file, and you re-upload all 2 GB. Do that 50 times a day and your internet melts.
- **Wasted storage** — a million users each save the same popular PDF or the same OS install file. Naively you store a million identical copies.
- **No good "what changed" story** — a device that was offline for a week has to figure out what to re-download. Comparing every file one-by-one is slow.

**Key insight that drives the entire design:**

> **Don't think in terms of "files." Think in terms of small, fixed-ish blocks of bytes called *chunks*, each named by a fingerprint of its own content. A "file" is just an ordered list of chunk fingerprints.**

Once you think that way, everything gets easy: identical bytes anywhere in the world share one stored chunk (**dedup**); editing a file changes only a couple of its chunks (**delta sync**); and "what does this device still need?" becomes "which chunk fingerprints is it missing?"

```
Old way:  file "report.docx"  →  one giant blob you re-upload whole
New way:  file "report.docx"  →  [chunk#a1b2, chunk#c3d4, chunk#e5f6]   (a recipe of fingerprints)
          edit one paragraph  →  only chunk#c3d4 changes → upload just that ONE chunk
```

> 💡 **Jargon, once:** **Delta sync** = only transferring the *changed* chunks of a file, not the whole file. The typo-in-a-50-page-doc example above is delta sync: one chunk changes, one chunk moves.

That "files are recipes of content-fingerprinted chunks" idea is detailed in §5, and the split between the recipe-keeper (metadata) and the byte store (block store) is §6.

---

## 2. Requirements

**Functional**
- Upload/download files; **auto-sync** across a user's devices.
- Only transfer **changed parts** (delta sync); **resume** interrupted transfers.
- **Version history**; **conflict handling**; **sharing** with permissions.

**Non-functional**
- **Durable** (never lose files), available, **bandwidth-efficient**, scalable to billions of files, works **offline** (sync later).

### Reading the requirements

Two lists show up in every system-design answer, and beginners mix them up:

- **Functional** = *what the product does* — the features a user would notice ("I can share a folder," "my edits sync," "I can restore last week's version").
- **Non-functional** = *how well it must do them* — the qualities behind the scenes ("it must never lose my file," "it must not waste my bandwidth," "it keeps working on a plane with no wifi").

The two requirements that most shape this design:

| Requirement | Why it dominates the design |
| --- | --- |
| **Durable — never lose a file** | This is the #1 promise of cloud storage. It's why file bytes go to a replicated object store (S3) that keeps many copies, and why concurrent edits become *"conflicted copies"* instead of overwrites (§8). |
| **Bandwidth-efficient — only move what changed** | This is why we can't just upload whole files. It forces **chunking + delta sync** (§5) and the **change journal** (§7) as the core mechanics. |

"Works offline" is a requirement even for a *cloud* app because your laptop keeps a **real local copy** of the folder (Dropbox isn't only-in-the-browser). You can edit on a plane; the app just remembers "these changed" locally and syncs them when the internet comes back. That's why the client is a genuine software component with its own little database, not just a web page (see §4).

---

## 3. Capacity Estimation

```
Users ~ 500M · files ~ 100B · avg file ~ few MB · chunk ~ 4 MB
Storage: exabytes raw, but DEDUP + compression cut it dramatically (many identical chunks)
Metadata: files/versions/chunk-lists → billions of rows → sharded RDBMS/NoSQL
Sync: mostly small deltas; a busy user's device polls/subscribes for changes
Reads/writes: uploads modest; downloads (sync) higher; block store handles the bytes
```

> **Dedup is the storage superpower** (same chunk across versions and users stored once). Metadata is the transactional brain; blocks are the bulk (S3).

### What these back-of-envelope numbers mean

Capacity estimation is just sanity-checking "is this even physically possible, and where's the cost?" You don't need exact numbers — you need the *shape*.

Let's walk the numbers slowly:

```
500,000,000 users, each with some files          → ~100 billion files total
avg file "a few MB", split into ~4 MB chunks      → the raw bytes add up to EXABYTES
```

An **exabyte** is a billion gigabytes. Storing that naively would be absurdly expensive — which is exactly why **dedup + compression** matter so much here:

- **Dedup** — if 10 million people saved the same 4 MB chunk of a popular installer, you store it **once**, not 10 million times. The same trick works *within* one user's history: version 5 of a document shares almost all its chunks with version 4.
- So the "exabytes raw" number shrinks dramatically once identical chunks collapse into one.

Two very different cost centers, and it's worth naming them separately:

| Cost center | Size | What lives here | Store |
| --- | --- | --- | --- |
| **The bytes (blocks)** | Huge (the exabytes) | The actual chunk contents | Cheap bulk object store (S3) |
| **The bookkeeping (metadata)** | Comparatively tiny, but *busy* | "which chunks make up which file version," cursors, the journal | A real (sharded) database |

100 billion files isn't impossible for a database once you **shard** the metadata: split those billions of rows across many database servers, each owning a slice of users/namespaces. No single machine holds all of it. (More in §13.) The bytes themselves are S3's problem, and S3 is built to be effectively infinite.

"Sync mostly small deltas" is good for capacity because the expensive part (moving bytes) mostly *doesn't happen*. Most of the time a device just needs "here are the 3 tiny journal entries since you last checked" and maybe one changed chunk — not gigabytes. The design is tuned so the common case is cheap.

---

## 4. Architecture (client + server)

The **client** is a real component here (not just a browser).

```
CLIENT (desktop/mobile):
  Watcher  → detects local file changes (FS events)
  Chunker  → splits changed files into chunks, hashes them
  Indexer  → maintains a local DB of file → chunk-hash lists + cursor
  Syncer   → talks to metadata service; up/downloads chunks to/from block store

SERVER:
  Metadata Service → files, versions, chunk lists, permissions, per-namespace CHANGE JOURNAL
  Block Store (S3) → chunk bytes keyed by content hash
  Notification Service → long-poll/WebSocket: "your namespace changed"
```

- Client talks to **metadata** to learn "what changed," then transfers **chunks directly** to/from the block store (pre-signed URLs).

### The client is a real program — what each piece does

Unlike most system-design problems where the "client" is just a browser, here the Dropbox desktop/mobile app is a genuine piece of software with jobs to do. Four components live inside it:

| Component | Its one job |
| --- | --- |
| **Watcher** | Notice when a file in the folder changes on this device (filesystem events) |
| **Chunker** | Cut a changed file into chunks and compute each chunk's hash (fingerprint) |
| **Indexer** | Keep a small **local database**: "this file = these chunk hashes," plus my sync **cursor** |
| **Syncer** | Talk to the server: ask what changed, upload/download the actual chunk bytes |

(The three server-side components — Metadata Service, Block Store, Notification Service — are listed in the architecture summary above.)

### Why metadata calls and chunk transfers are split

The client talks to metadata first, then transfers chunks separately, because the two are wildly different kinds of work, and mixing them would be wasteful:

- **Metadata calls are small and chatty** — "what changed since cursor N?", "do you already have these 5 chunk hashes?". These hit the database (the brain).
- **Chunk transfers are big and dumb** — literally moving megabytes of bytes. These should go **straight to S3**, not through your application servers (which would become an expensive bottleneck just forwarding bytes).

The trick that makes the direct-to-S3 transfer safe is a **pre-signed URL**: the metadata service says "you're allowed to PUT this one chunk to this exact S3 location for the next 10 minutes," hands the client a temporary signed link, and the client uploads directly to S3.

```
1. Client → Metadata:  "I want to upload chunk#c3d4"
2. Metadata → Client:   pre-signed URL  (temporary, chunk-specific, expires soon)
3. Client → S3 directly: PUT the bytes to that URL     ← the heavy transfer skips our servers
4. Client → Metadata:  "done — here's my new file = [a1b2, c3d4, e5f6]"
```

```java
// The server never streams the bytes itself — it just authorizes a direct S3 transfer.
class UploadController {
    // Client asks permission to upload a specific chunk
    PreSignedUrl requestUpload(String chunkHash) {
        // (permission + quota checks happen here, in the metadata service)
        return s3.generatePresignedPutUrl(
            "chunks/" + chunkHash,   // the key = the content hash (see §5)
            Duration.ofMinutes(10)   // link self-destructs so it can't be reused forever
        );
    }
}
```

It isn't insecure to let the client talk to S3 directly, because the pre-signed URL is the safeguard. It's scoped to **one specific chunk**, it **expires quickly**, and the metadata service only issues it after checking the user's permissions and quota. The client can't wander around S3; it can only do the one narrow thing the URL authorizes.

---

## 5. Chunking & Deduplication

> 💡 **Jargon, once:** **Chunking** = slicing a file into small blocks. **CDC (content-defined chunking)** = choosing block boundaries by the *content* (a rolling hash) instead of a fixed ruler, so an early insert doesn't reshuffle every block. **Dedup (deduplication)** = storing identical bytes exactly once, keyed by their hash.

Split each file into **chunks**; identify each by its **content hash (SHA-256)** — **content-addressed storage**.

```
file version = [chunkHashA, chunkHashB, chunkHashC, ...]   (ordered list of chunk hashes)

Benefits:
  - Dedup: identical chunk stored ONCE (across versions AND across users)
  - Delta sync: edit one part → only that chunk's hash changes → upload just that chunk
  - Resumable: upload chunks independently; retry only missing ones
  - Integrity: hash verifies the chunk wasn't corrupted
```

| Chunking | Note |
| --- | --- |
| **Fixed-size** (e.g. 4MB) | Simple; **but a byte inserted early shifts all boundaries** → every chunk hash changes → poor dedup |
| **Content-defined (rolling hash, e.g. Rabin)** ✅ | Boundaries are chosen by content patterns → an insert only affects nearby chunks → **stable dedup under edits** |

### What "content-addressed" actually means

This is the heart of the whole system, so let's go slow.

**A chunk is just a blob of bytes** — say, a 4 MB slice of a file. We give each chunk a name, but not a name *we* pick. We name it by running its bytes through a **hash function** (SHA-256) and using the result as the name. That result is a **content hash** — a short, fixed-length fingerprint that's essentially unique to those exact bytes.

```
chunk bytes ── SHA-256 ──►  "a1b2c3...e5f6"   (64 hex characters — the chunk's name)
```

"**Content-addressed**" means: **the address (name) of the data IS a fingerprint of the data itself.** Two consequences that beginners find surprising but are the whole point:

1. **Identical bytes → identical name, always and everywhere.** If you and I both have a chunk containing the exact same bytes, we compute the exact same hash — so it's literally the *same* entry in the store. That's how **dedup** happens automatically: same content can only be stored under one name.
2. **Different bytes → different name (practically guaranteed).** Change a single byte and the hash comes out completely different. So a name change signals "the content changed," which is exactly what lets us detect which chunks are new.

### A file is a *recipe* (ordered list of chunk hashes)

Once chunks are fingerprinted, a **file version is just an ordered list of chunk fingerprints** — a recipe:

```
report.docx (version 7) = [ a1b2, c3d4, e5f6, 7890 ]
                             ↑     ↑     ↑     ↑
                          chunk1 chunk2 chunk3 chunk4  (each is 4 MB of actual bytes in S3)
```

To reconstruct the file, you fetch the bytes for each fingerprint in order and glue them together. This is why editing is cheap:

```
version 7 = [ a1b2, c3d4, e5f6, 7890 ]     ← you edit the text inside chunk 2
version 8 = [ a1b2, XXXX, e5f6, 7890 ]     ← only chunk 2 got a new fingerprint
                     ↑
             upload ONLY this one new chunk; the other three already exist in S3
```

Here's the whole chunk-hash-dedup loop in simple annotated Java:

```java
// Turn a file into chunks, hash each, upload only the ones the server lacks.
List<String> syncFile(byte[] fileBytes) {
    List<byte[]> chunks = splitIntoChunks(fileBytes);   // cut into ~4 MB pieces (see below)
    List<String> recipe = new ArrayList<>();            // the ordered list of hashes

    for (byte[] chunk : chunks) {
        String hash = sha256(chunk);                    // the chunk's content-address (name)
        recipe.add(hash);

        // ask the server: do you ALREADY have a chunk with this fingerprint?
        if (!server.hasChunk(hash)) {
            server.uploadChunk(hash, chunk);            // new bytes → upload once
        }
        // else: someone (maybe another user!) already stored identical bytes → skip. DEDUP.
    }

    server.commitVersion("report.docx", recipe);        // store the recipe = the new version
    return recipe;
}
```

The magic line is `if (!server.hasChunk(hash))`. Because the name is a fingerprint of the content, "have you seen these exact bytes before?" is answered by a single fast lookup — no comparing file contents byte-by-byte.

### Fixed-size vs content-defined chunking (the boundary-shift problem)

The table above is the single trickiest idea in the doc, so here's the concrete version.

**Fixed-size chunking** = cut every 4 MB, no matter what. Simple. But watch what happens if you insert **one byte at the very start** of the file:

```
BEFORE (cut every 4 chars for illustration):
  "HELL" | "OWOR" | "LDXX"          → hashes:  H1 | H2 | H3

Insert "A" at the front:
  "AHEL" | "LOWO" | "RLDX" | "X"    → hashes:  H4 | H5 | H6 | H7
```

Every single boundary shifted right by one, so **every chunk now contains different bytes → every hash changed → you re-upload the ENTIRE file.** The one-byte insert defeated dedup completely. This is the "byte inserted early shifts all boundaries" note.

**Content-defined chunking** fixes this by letting the *data* decide where to cut, instead of a fixed ruler. You slide a small window across the bytes computing a cheap **rolling hash**, and you declare a chunk boundary wherever that hash hits a special pattern (e.g. its low bits are all zero). Because boundaries are anchored to byte *patterns* that travel with the content, inserting a byte early only disturbs the chunk it landed in — the later boundaries re-appear at the same content landmarks.

```java
// Content-defined boundaries: cut where the rolling hash matches a pattern,
// so the SAME content lands at the SAME boundaries even after an early insert.
List<byte[]> splitContentDefined(byte[] data) {
    List<byte[]> chunks = new ArrayList<>();
    RollingHash roll = new RollingHash();   // cheap hash over a small sliding window
    int start = 0;

    for (int i = 0; i < data.length; i++) {
        roll.add(data[i]);                  // O(1) update as the window slides

        // boundary condition: pattern in the content itself, not a fixed offset.
        // ~13 low bits zero → an average chunk size of ~8 KB (tune to taste).
        boolean isBoundary = (roll.value() & 0x1FFF) == 0;

        if (isBoundary || i - start >= MAX_CHUNK) {   // MAX_CHUNK caps runaway chunks
            chunks.add(Arrays.copyOfRange(data, start, i + 1));
            start = i + 1;
        }
    }
    if (start < data.length) chunks.add(Arrays.copyOfRange(data, start, data.length));
    return chunks;
}
```

> **Put differently:** fixed-size chunking cuts text every 80 characters — add one word at the top and every line reflows, so nothing matches. Content-defined chunking cuts at the end of every *sentence* — add a word to sentence 1 and only sentence 1 changes; sentences 2, 3, 4 are still cut in exactly the same places.

#### Q: If two users have the same chunk, whose is it? Can one user see another's data?

The chunk is **shared in storage but not in access.** Physically there's one copy of those bytes in S3 (dedup). But *permission* to read a file comes from the **metadata** — you can only fetch a chunk if a file recipe you're allowed to see references it. You never get to browse chunks by hash directly, so dedup across users leaks nothing. (Real systems also guard against a subtle "does this hash exist?" probing attack, but the core answer is: access is decided by metadata, not by owning a fingerprint.)

### Hash collisions are a theoretical, not practical, risk

There's in theory a risk two different chunks get the same hash (a collision); in practice, with SHA-256, essentially never — the space of possible hashes is so astronomically large (2^256) that you'd sooner win the lottery every day for a lifetime. Systems treat SHA-256 equality as "the bytes are identical." That's what makes content-addressing trustworthy.

### Why hash instead of just tracking byte offsets

Offsets don't tell you whether the *content* is the same. Two files can have completely different bytes at "0–4MB." The hash is what lets you say "these exact bytes already exist, skip the upload" and "this chunk didn't get corrupted in transit" (re-hash after download and compare) — dedup **and** integrity from the same fingerprint.

---

## 6. Metadata vs Block Storage

| Subsystem | Stores | Tech |
| --- | --- | --- |
| **Block store** | Chunk bytes, keyed by content hash | Blob/object store (S3) — durable, replicated, huge |
| **Metadata service** | Files, folders, versions, chunk lists, permissions, device cursors, journal | RDBMS/NoSQL (transactional), sharded by user/namespace |

- **Why split?** Blocks are huge + immutable + dumb → cheap object storage. Metadata is small + transactional + queried constantly → a database. Each scales independently.

### Database & storage choices (which DB, and why at scale)

Polyglot persistence again, but the fork here is sharper than most systems: two data models with **opposite** requirements live side by side. Deciding question: *"does this need ACID transactions and rich queries, or is it an immutable blob keyed by its own hash?"*

| Data | Store | Why this one | Why not the alternative |
| --- | --- | --- | --- |
| Files/folders, versions, chunk lists, sharing ACLs | **RDBMS**, sharded by user/namespace | Rename/move/share are metadata-only transactions; base-version conflict detection (§8) needs an atomic compare-and-set on `current_version`; ACLs need joins | A KV/object store has no cross-row transactions over `files` + `file_versions` + `shares` — a rename or permission grant could land half-applied |
| Chunk bytes (content-addressed, deduped) | **Block/object store (S3)** | Chunks are immutable and named by their own hash — a perfect fit for cheap, infinite, replicated blob storage; dedup is essentially free (same hash = same object) | Putting 4MB chunks in the metadata DB blows up its size/cost for data that's never queried by content, only fetched by key |
| Locks, device sessions, hot cursors | **Redis** | Sync polling and lock checks are high-frequency and latency-sensitive; TTL-based keys naturally expire stale sessions | Hitting the RDBMS for every "am I locked?" check on a busy namespace adds load for data that doesn't need durability beyond a few seconds |
| Sync/notify events (journal fan-out, device wake-ups) | **Queue (Kafka) / notification service** | Devices need push, not poll; a durable log lets a device offline for days replay exactly what it missed | Polling the metadata DB from millions of idle devices is a self-inflicted DDoS; a queue/pub-sub absorbs the fan-out |

**Why split metadata (relational) from blocks (object store), and how chunk dedup works in the object store:** the metadata service is small per user but *constantly* mutated — every edit, cursor advance, and share — exactly the transactional, low-latency workload an RDBMS is built for, and it shards cleanly by `user_id`/`namespace_id` since virtually every query is scoped to one user's tree (§13). The block store, by contrast, holds the actual bytes — huge, write-once, named by content hash — so "does this chunk already exist?" is a single key lookup (`hasChunk(hash)`) rather than a byte-for-byte comparison; two users who happen to upload identical bytes collide on the same S3 key, and the object is physically stored **once**, with the metadata layer tracking `ref_count` so it's only garbage-collected once nobody's recipe points at it anymore (§12). Neither store could do the other's job well: cramming blobs into the RDBMS wrecks its size and backup times; cramming transactional metadata into S3 means hand-rolling ACID. (See [Databases — Deep Dive](../concepts/databases-deep-dive.md).)

### Block store vs metadata service

The two subsystems have opposite jobs:

- The **block store** stores raw bytes keyed by content hash. It's enormous, cheap per object, and *dumb* — it doesn't know what any chunk means, who owns it, or which chunks go together. You hand it a fingerprint, it hands back the bytes. That's S3.
- The **metadata service** is small but constantly consulted, and it's *smart*: it knows "the file `report.docx` version 7 is made of chunks [a1b2, c3d4, e5f6]," who's allowed to open it, what changed recently, and how far each device has caught up.

You don't touch the block store to rename a file — you just update the metadata. That single idea (move metadata, not bytes) is why rename/move/restore are near-instant.

### Why not just keep everything in one big database

Bytes and bookkeeping have opposite needs, and forcing them into one store makes both worse:

| | **Block store (bytes)** | **Metadata (bookkeeping)** |
| --- | --- | --- |
| Size of each item | Big (megabytes) | Tiny (a few fields) |
| How often it changes | Never — a chunk is immutable (new content = new hash = new chunk) | Constantly — every edit, cursor advance, share |
| Access pattern | "Give me the bytes for this one fingerprint" | Rich queries, transactions, joins |
| Right tool | Cheap object store (S3) | A real (sharded) database |

Databases are expensive per gigabyte and terrible at storing exabytes of blobs; object stores are cheap and infinite but can't do `WHERE owner_id = 42 AND is_deleted = false`. Split them and each does what it's good at — and each **scales independently** (add S3 capacity without touching the DB, and vice versa).

### What "chunks are immutable" means and why it matters

Immutable = **a chunk, once written, is never modified in place.** If you edit a file, you don't rewrite an existing chunk; you create a *new* chunk with new bytes and therefore a new hash, and point the new file version at it. The old chunk sits untouched (still used by old versions or other users). This is what makes:

- **Versioning** trivial — old versions still point at the old chunks, which still exist.
- **Caching/CDNs** safe — a fingerprint's bytes never change, so they can be cached forever.
- **Concurrency** simpler — nobody is overwriting shared bytes, so there's no "who wrote last" race on the byte level (races move up to the metadata/version level — see §8).

```java
// A tiny "metadata knows the recipe; block store knows the bytes" reassembly:
byte[] downloadFile(long versionId) {
    List<String> recipe = metadata.getChunkHashes(versionId);   // ask the metadata service
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (String hash : recipe) {
        out.write(blockStore.get(hash));   // fetch each chunk from the block store, in order
    }
    return out.toByteArray();              // glue the chunks back into the original file
}
```

---

## 7. Sync — Journal, Cursor & Notifications

> 💡 **Jargon, once:** a **namespace** is one self-contained sync scope with its own change log — your personal Drive is one, each shared folder is another. A **cursor** is a device's bookmark ("I've applied up to seq N") into that log. Full definition below.

Devices don't poll every file — they sync against a **per-namespace change journal** using a **cursor**.

```
CHANGE JOURNAL: an append-only, monotonically-increasing log of changes per namespace (user/shared folder)
  entry = { seq, file_id, new_version, op }
DEVICE CURSOR: the last journal seq this device has applied

Sync:
  1. Notification Service pushes "namespace changed" (long-poll/WebSocket) → wake the device
  2. Device: GET /delta?cursor=N → server returns journal entries after N
  3. For each changed file: fetch its new chunk list → download only chunks it LACKS → reassemble
  4. Advance cursor to the latest seq
```

- **Long-poll / WebSocket** notification avoids constant polling; the journal makes "what changed since I last synced" an O(delta) query.
- **Offline:** queue local changes; on reconnect, upload deltas + pull journal → merge/resolve.

### The change journal

The core sync mechanism is an **append-only log** of changes. Every time anything in a namespace changes, a new numbered entry is appended: `seq 41`, `seq 42`, `seq 43`. To catch up, a device reads **only the entries after the last sequence number it already applied** — it never re-scans the whole folder.

- The **change journal** is that log: an append-only list of "what changed," each entry stamped with an ever-increasing sequence number (`seq`). One journal per **namespace** (a user's own folder, or a shared folder).
- Your device's **cursor** is the marker of progress: "I've applied up to seq 42." To catch up, it asks "give me everything after 42" and gets back exactly the new entries — nothing more.

```
CHANGE JOURNAL for namespace "alice-home":
  seq 40  { file: budget.xlsx,  new_version: 5, op: UPDATE }
  seq 41  { file: photo.jpg,    new_version: 1, op: CREATE }
  seq 42  { file: notes.txt,    new_version: 9, op: UPDATE }   ← phone's cursor is here
  seq 43  { file: report.docx,  new_version: 7, op: UPDATE }   ┐ laptop wrote these while
  seq 44  { file: old.pdf,      new_version: -, op: DELETE }   ┘ the phone was asleep

Phone wakes up, says "anything after 42?" → gets 43 and 44 → applies them → cursor = 44.
```

Why this beats the naive "compare every file":

- **O(delta), not O(everything).** A user with 100,000 files who changed 2 of them produces 2 journal lines. The catching-up device does 2 units of work, not 100,000. Comparing every file every time would be brutal at scale.
- **Ordered and resumable.** Because entries are numbered, a device that dies mid-sync just resumes from its last cursor. No "did I miss something?" ambiguity.

### Why notifications (don't just poll constantly)

If every device asked the server "anything new?" every second, a billion devices would hammer the server pointlessly — 99.9% of those calls answer "nope." So instead the **Notification Service** pushes: the device opens a connection and *waits*; the server notifies it only when that namespace actually changes, and then the device does its `GET /delta`.

Two common ways to build that push channel:

| Mechanism | How it works | Trade-off |
| --- | --- | --- |
| **Long-poll** | Device sends a request that the server *holds open* until there's news (or a timeout), then answers | Simple, works everywhere; slightly wasteful reconnects |
| **WebSocket** | A persistent two-way connection stays open; server pushes the instant something changes | Most efficient/instant; more connection state to manage |

```java
// The sync loop from the device's point of view — fetch only what's after the cursor.
void syncNamespace(long namespaceId) {
    notificationService.waitForChange(namespaceId);        // block until "your namespace changed"

    long cursor = localDb.getCursor(namespaceId);          // "I'm caught up through here"
    List<JournalEntry> changes = server.getDelta(namespaceId, cursor);   // ONLY newer entries

    for (JournalEntry e : changes) {
        List<String> recipe = server.getChunkHashes(e.versionId());      // the new file's recipe
        for (String hash : recipe) {
            if (!localDb.hasChunk(hash)) {                 // download ONLY chunks I lack
                byte[] bytes = blockStore.get(hash);       // (delta sync in action)
                localDb.storeChunk(hash, bytes);
            }
        }
        reassembleFile(e.fileId(), recipe);
        cursor = e.seq();                                   // advance the bookmark as we go
    }
    localDb.setCursor(namespaceId, cursor);                 // remember how far we got
}
```

### What exactly is a "namespace"

A namespace is **one journal's worth of stuff** — a self-contained scope that changes together and syncs together. Your personal Drive is one namespace. A folder shared with your team is a *separate* namespace with its *own* journal, so everyone who has it gets those changes (and only those). Splitting into namespaces is what lets a shared folder sync to five people without dumping everyone's private files into one giant log. (More in §9.)

### From "what changed" to which bytes to download

The journal only says WHICH file changed; the device figures out which *bytes* to download in two steps, and this is the payoff of everything before it: (1) the journal entry gives the device the new **version id**; (2) the device fetches that version's **chunk recipe** and downloads only the chunk hashes it doesn't already have locally. So "file changed" → "here's the new recipe" → "I already have 9 of these 10 chunks, fetch 1." That's **delta sync**, powered by the content-addressed chunks from §5.

#### Q: How does offline mode fit in?

While offline, the device can't receive notifications or upload — so it just **queues its local changes** in its own little database (Watcher noticed them, Chunker chunked them). On reconnect it does both directions: **push** its queued deltas up (upload new chunks, commit new versions → new journal entries), and **pull** the journal from its cursor to learn what others did. If both sides touched the same file while it was offline, that's a conflict → §8.

### Initial sync & bootstrap

Everything above describes the *steady state* — a device that already has the folder and just needs the delta. But what about a **brand-new device** that installs the app and has to pull down a whole 500 GB folder for the first time? That's the **bootstrap** case, and it's the same journal mechanic pointed at `cursor = 0`.

```
Fresh device: cursor = 0  →  GET /delta?cursor=0
  → server streams the ENTIRE current state as journal entries (or a compacted snapshot)
  → device downloads chunks it lacks (which, on a fresh device, is ALL of them)
  → advances cursor to the latest seq once caught up
```

Three things make first sync survivable instead of a thundering, all-or-nothing download:

- **Cursor = 0 is just "I've applied nothing."** The journal doesn't special-case new devices — the same "give me everything after N" query with `N = 0` returns the full state. For huge namespaces the server may hand back a **compacted snapshot** (current recipes only, not every historical entry) plus the latest `seq`, so the device doesn't replay years of history.
- **Backpressure** — the client paces itself: bounded parallelism (e.g. 4–8 chunk downloads at a time), and it **checkpoints its cursor as it goes** so a crash at 300 GB resumes from 300 GB, not from 0. It also backs off when the network or disk is saturated rather than opening a thousand connections.
- **500 GB bootstrap strategy** — prioritize **metadata first** (show the folder tree and file names immediately, even before bytes arrive), then stream chunk bytes lazily / on-demand or lowest-folders-first. Dedup helps here too: if the same machine already had another synced account or a **LAN peer** has the bytes (see §17), many chunks are fetched locally instead of from S3.

> 💡 **The insight:** first sync isn't a different system — it's the ordinary delta sync with `cursor = 0` and a lot more missing chunks. Checkpoint the cursor so a 500 GB pull is *resumable*, never a restart-from-scratch.

> ⚠️ **Pitfall:** don't let a fresh device fetch every chunk with unbounded parallelism — you'll saturate the user's uplink, hammer S3, and (across millions of new installs) create a self-inflicted load spike. Bounded concurrency + backpressure is mandatory, not optional.

---

## 8. Conflict Resolution & Versioning

- Every change creates a **new version** (metadata points to a new ordered chunk list); old chunks retained for history.
- **Concurrent edits** to the same file from two devices → detected via **base-version mismatch** (both edited version N): keep **both** as a **"conflicted copy"** (`file (Device B's conflicted copy)`) rather than silently overwriting → **never lose data**.
- **Version history** = the list of prior chunk-list snapshots; **restore** = point the file at an old version (chunks still exist).

### What a "version" really is (it's cheap)

A **version is just a saved recipe** — an ordered list of chunk hashes at a moment in time. Because chunks are immutable and shared, saving a new version is nearly free: you only store the *recipe* plus any genuinely new chunks. The old recipe still points at its old chunks, which still exist.

```
report.docx history (each version = one recipe; shared chunks stored ONCE):
  v5 = [ a1, b2, c3 ]
  v6 = [ a1, b2, XX ]     ← edited the end; only chunk XX is new
  v7 = [ a1, YY, XX ]     ← edited the middle; only chunk YY is new

Chunks physically stored: a1, b2, c3, XX, YY   (5 chunks power 3 full versions)
Restore v5  =  just point the file back at recipe [a1, b2, c3]. No downloads if chunks are local.
```

That's why "restore to last Tuesday" is instant: nothing is recomputed or re-uploaded — the metadata simply points the file at an older recipe whose chunks were never deleted.

### The conflict problem, and why we keep both copies

Two devices both read the *same* version (say version 5), make different edits, and both try to commit their result as the successor of version 5. Which wins? If the system blindly kept the last write, the first device's edits vanish silently — the cardinal sin for a storage product ("**never lose data**").

How the system detects this: every edit records **which version it was based on** (its *base version*). When you upload, the server checks whether that base is still the current version.

```
Device A: opens v5, edits, uploads saying "based on v5"   → server: current is still v5 ✅ → becomes v6
Device B: also opened v5 earlier, edits, uploads "based on v5"
          → server: but current is now v6, NOT v5!  → BASE-VERSION MISMATCH → conflict
```

Instead of overwriting, the server keeps **both**: your changes land as a **"conflicted copy"** so nothing is lost and a human decides.

```java
CommitResult commitVersion(long fileId, long baseVersion, List<String> newRecipe, String device) {
    long current = metadata.getCurrentVersion(fileId);

    if (baseVersion == current) {
        // clean case: nobody changed it underneath us → just advance the version
        return metadata.createVersion(fileId, newRecipe);          // becomes current + 1
    } else {
        // CONFLICT: someone else already moved the file past our base.
        // Never overwrite. Save our work as a separate, clearly-named file. Both survive.
        String name = originalName + " (" + device + "'s conflicted copy)";
        long conflictFileId = metadata.createFile(name, newRecipe);
        return CommitResult.conflict(conflictFileId);              // e.g. "report (Device B's conflicted copy).docx"
    }
}
```

#### Q: Why not just auto-merge the two edits like Google Docs does?

Because Dropbox stores **arbitrary files** — a Word doc, a Photoshop file, a zipped video project. It has no idea what "merging" two edited binary blobs even means; a naive byte-merge would corrupt the file. Google Docs *can* merge because it understands its own document structure and edits (that's a different technique — operational transforms / CRDTs; see the Google Docs note). For a general file store, the safe, honest answer is: **keep both, let the human sort it out.** Data is never lost.

### A conflicted copy is not a wasteful full duplicate

Thanks to dedup (§5), the conflicted copy **shares all the chunks it has in common** with the original. If the two versions differ in just one chunk, the "duplicate" costs one extra chunk plus a small recipe, not a whole second file.

#### Q: Why not use CRDTs or OT to merge concurrent edits, like collaborative editors do?

Because those techniques need to **understand the data they're merging**, and a file store deliberately doesn't. It helps to lay the three strategies side by side:

| Strategy | What it does | Fits |
| --- | --- | --- |
| **Three-way merge** (base + A + B) | Diff each side against the common ancestor and combine non-overlapping changes; flag true overlaps | Structured, line-oriented text (source code, `git`) |
| **CRDT / OT** | Model every edit as a commutative operation so concurrent ops converge automatically with no conflict | *Live* collaborative editors on a known document model (Google Docs) |
| **LWW (last-writer-wins)** | Keep whichever write arrived last; silently drop the other | Caches, presence — places where losing a write is acceptable |
| **Conflicted copies** ✅ | Keep **both** sides as separate files; a human reconciles | **Arbitrary binary files** (the file-store case) |

CRDTs and OT work for Google Docs because the app owns the document's structure — it knows an edit is "insert character at position 12" and can prove two such ops commute. Dropbox stores a **Photoshop file, a zip, an encrypted blob** — opaque bytes with no operation model, so there's nothing to make commute; a byte-level "merge" would just corrupt the file. **Three-way merge** needs a meaningful diff/ancestor, which again assumes text-like structure. **LWW** is off the table outright: silently discarding a write violates the cardinal rule (**never lose data**). That leaves **conflicted copies** — the only strategy that's *correct for content you can't interpret*: keep both, lose nothing, let the human decide. (See the Google Docs note for how OT/CRDT work when you *do* control the document model.)

---

## 9. Sharing & Permissions

- Share a file/folder with users or via link; roles (viewer/editor/owner).
- A **shared folder is its own namespace** with its own journal → all members' devices sync it.
- Permission checks in the metadata service; revoking access stops sync + downloads.

### A shared folder is its own namespace

Recall from §7 that each **namespace** has its own change journal. Sharing is basically: **make the shared folder a namespace, and let multiple people's devices subscribe to its journal.**

Your private Drive is a namespace only your devices follow. A shared team folder is a *separate* namespace whose journal is followed by *every* member's devices. When Alice commits `seq 57` to the shared journal, Bob's and Carol's devices all see entry 57 and pull the change.

```
alice-home  namespace → journal A   (only Alice's laptop + phone follow it)
team-launch namespace → journal T   (Alice, Bob, Carol ALL follow it)

Bob edits a file in team-launch → new entry in journal T → Alice & Carol's devices wake and sync.
```

Roles decide what each member may do:

| Role | Can read | Can edit/upload | Can manage sharing |
| --- | --- | --- | --- |
| **Viewer** | yes | no | no |
| **Editor** | yes | yes | no |
| **Owner** | yes | yes | yes (add/remove members, delete) |

Every meaningful action checks the `shares` table first, inside the metadata service:

```java
byte[] downloadFileForUser(long userId, long fileId) {
    long namespaceId = metadata.namespaceOf(fileId);
    Role role = metadata.getRole(namespaceId, userId);   // look up the share grant

    if (role == null) {                                   // no grant at all
        throw new AccessDenied();                         // can't even see it
    }
    // viewer/editor/owner may all READ; only editor+ may write (checked on upload paths)
    return reassemble(fileId);
}
```

### Dedup is not the same thing as sharing permission

If chunks are deduped across users, sharing doesn't "just work" merely because we already store the bytes once — those are two different things. **Dedup** is a storage optimization (identical bytes live once, invisibly). **Sharing** is a *permission* grant recorded in metadata. You can only access a file if a `shares`/namespace grant lets you — not merely because the underlying bytes happen to be stored. So the byte you're downloading might physically be the same chunk another user also uses, but you're allowed to read it because *your file's recipe* references it and you have a role on *your* namespace.

### What happens the moment access is revoked

Every sync and download re-checks permissions in the metadata service, revoking a share **immediately** stops that user's devices from pulling new journal entries or downloading chunks for that namespace. (Bytes already downloaded to their disk are, realistically, already on their disk — revocation stops *future* sync, not time travel.)

---

## 10. Data Model (all tables)

```sql
CREATE TABLE users ( user_id BIGINT PRIMARY KEY, email VARCHAR(255) UNIQUE, quota_bytes BIGINT, used_bytes BIGINT );

CREATE TABLE files (
    file_id BIGINT PRIMARY KEY, namespace_id BIGINT, owner_id BIGINT, parent_folder_id BIGINT,
    name TEXT, is_folder BOOLEAN, current_version BIGINT,
    is_deleted BOOLEAN DEFAULT FALSE, updated_at TIMESTAMP
);
CREATE INDEX idx_files_folder ON files(parent_folder_id);

CREATE TABLE file_versions (
    version_id BIGINT PRIMARY KEY, file_id BIGINT, version_no INT,
    size_bytes BIGINT, created_by BIGINT, created_at TIMESTAMP
);
CREATE TABLE version_chunks (            -- ordered chunk list per version
    version_id BIGINT, seq INT, chunk_hash CHAR(64), PRIMARY KEY (version_id, seq)
);
CREATE TABLE chunks (                     -- content-addressed dedup registry
    chunk_hash CHAR(64) PRIMARY KEY, size_bytes INT, ref_count BIGINT, created_at TIMESTAMP
);

-- Per-namespace change journal (devices sync against this)
CREATE TABLE change_journal (
    namespace_id BIGINT, seq BIGINT, file_id BIGINT, version_id BIGINT, op VARCHAR(10),
    at TIMESTAMP, PRIMARY KEY (namespace_id, seq)
);
CREATE TABLE device_sync_state ( device_id BIGINT PRIMARY KEY, user_id BIGINT, namespace_id BIGINT, cursor BIGINT, last_seen TIMESTAMP );

CREATE TABLE shares ( namespace_id BIGINT, grantee_id BIGINT, role VARCHAR(10), PRIMARY KEY (namespace_id, grantee_id) );
-- Chunk bytes live in the block store (S3), keyed by chunk_hash
```

> **Tables to consider:** users, files (tree), file_versions, version_chunks, chunks (dedup registry + ref_count), **change_journal**, device_sync_state (cursor), shares/namespaces. Bytes → block store.

### How the tables fit together

Don't memorize the columns — understand the **shape**. Each table maps directly to one idea we've already met:

| Table | Role | Ties back to |
| --- | --- | --- |
| `users` | Who you are + your storage quota | §2 |
| `files` | The folder tree: names, parent folders, which version is current, soft-delete flag | §1 |
| `file_versions` | One row per saved version of a file (the history) | §8 versioning |
| `version_chunks` | **The recipe**: for a version, the ordered list of chunk hashes | §5 "file = recipe" |
| `chunks` | The dedup registry: each unique chunk hash + how many recipes point at it (`ref_count`) | §5 dedup + §13 GC |
| `change_journal` | The append-only log of "what changed," numbered by `seq` | §7 journal |
| `device_sync_state` | Each device's bookmark (`cursor`) into the journal | §7 cursor |
| `shares` | Who has what role on which namespace | §9 sharing |

The one join that "reconstructs a file" walks recipe → chunks:

```sql
-- Give me the ordered chunk hashes that make up version 7 of a file (its recipe).
SELECT vc.seq, vc.chunk_hash
FROM   version_chunks vc
WHERE  vc.version_id = 7
ORDER  BY vc.seq;          -- order matters: chunks are glued back together in this sequence
```

### What `ref_count` tracks and why it lives in the `chunks` table

> 💡 **Jargon, once:** **ref-count (reference count)** = a tally of how many recipes point at a chunk. It's the bookkeeping that makes dedup safe to *undo*: a chunk is only deletable once its count hits 0. ⚠️ Don't delete on "this one file stopped using it" — another user or an old version may still reference the exact same bytes.

`ref_count` = **how many file versions currently point at this chunk.** Because chunks are shared (dedup), you can't delete a chunk just because *one* file stopped using it — five other files (or old versions) might still need it. So each chunk keeps a tally:

```sql
-- New version references chunk a1b2 → one more recipe depends on it
UPDATE chunks SET ref_count = ref_count + 1 WHERE chunk_hash = 'a1b2...';

-- A version is deleted/expired and no longer references it → one fewer
UPDATE chunks SET ref_count = ref_count - 1 WHERE chunk_hash = 'a1b2...';

-- Only chunks nobody references anymore are safe to garbage-collect (see §13)
SELECT chunk_hash FROM chunks WHERE ref_count = 0;
```

This is classic **reference counting** — the same idea a language runtime uses to know when an object is safe to free. GC (and the race it creates) is covered in §13.

### Why delete is an `is_deleted` flag instead of removing the row

That's a **soft delete (tombstone)**: mark it deleted but keep the data for a restore window (the "deleted files" trash you can recover from). It also plays nicely with sync — a `DELETE` becomes a normal journal entry other devices apply, rather than a mysteriously-vanished row. Hard deletion (really freeing chunks) happens later via GC once `ref_count` hits 0.

### Indexes that matter

Don't list every index — name the few that back the hot paths, and say *what query each one serves*. These three carry the system:

- **`change_journal (namespace_id, seq)`** — the sync superpower. It's already the composite primary key, and it makes the single most-run query, *"give me entries after my cursor"*, a **range scan** (`WHERE namespace_id = ? AND seq > ?`) instead of a full-log scan. Every device wake-up hits this; without it, sync degrades to O(journal size).
- **`chunks (ref_count)`** (partial, `WHERE ref_count = 0`) — the **GC candidate scan**. The sweeper (§13, §17) periodically asks "which chunks does nobody reference anymore?"; a plain scan over billions of chunk rows would be brutal, so index the `ref_count = 0` set directly.
- **`files (parent_folder_id)`** — **folder-tree traversal**. Rendering a folder, or walking a subtree to compute what a device must sync, is "give me the children of this folder"; this index turns each level of the walk into a fast lookup. (Already present in the schema above as `idx_files_folder`.)

> 💡 **The pattern:** each index maps 1:1 to a query in the critical path — cursor catch-up, GC sweep, tree walk. If you can't name the query an index serves, you probably don't need it.

---

## 11. Sequences

### Upload an edited file (delta)

```
Client: watcher detects change → chunker splits → hashes chunks
  → GET /has-chunks {hashes} → server returns which are NEW
  → upload only NEW chunks to block store (pre-signed URLs; increment ref_count)
  → POST new version = ordered chunk-hash list → server appends to change_journal (seq++)
```

### Sync down to another device

```
Notification: "namespace X changed" → wake device
Device: GET /delta?cursor=N → journal entries after N
  for each changed file: get chunk list → download chunks it LACKS → reassemble → advance cursor
```

### The two flows, step by step

**Uploading an edit (device A):** you save a change → the *Watcher* notices → the *Chunker* re-chunks and hashes → the app asks the server "of these chunk hashes, which are **new**?" (`/has-chunks`) → it uploads **only the new ones** straight to S3 → it posts the new recipe → the server appends a line to the journal (`seq++`). Notice the pattern: *check what's needed, move only that, then record it.*

```
Client                              Server
  |-- POST /has-chunks [a1,c3,XX] --->|   (which of these do you NOT have?)
  |<-- {new: [XX]} ------------------|   (a1 & c3 already exist — dedup!)
  |-- PUT chunk XX (to S3 URL) ------>|   (upload ONLY the new bytes)
  |-- POST /version [a1,c3,XX] ------>|   (commit the recipe)
  |                                   |-- append journal entry seq=44 → wake other devices
```

**Syncing down (device B):** the notification arrives ("namespace X changed") → device asks "what's after my cursor N?" → for each changed file it grabs the recipe and downloads only the chunks it's **missing** → reassembles → advances its cursor. Same pattern in reverse: *learn what changed, fetch only the missing bytes, record how far you got.*

> **The symmetry to remember:** both directions are "compute the delta, move only the delta, then update a marker (journal seq on upload, cursor on download)." That's the entire sync engine in one sentence.

---

## 12. Consistency & Edge Cases

| Case | Handling |
| --- | --- |
| Concurrent edits (two devices) | Base-version mismatch → keep both as **conflicted copy** (never lose data) |
| Partial upload | Chunks uploaded independently; retry missing; version committed only when all present |
| Offline edits | Queue locally; on reconnect upload deltas + pull journal → merge |
| Large file | Chunked + resumable; only changed chunks on edit |
| Dedup integrity | Content hash verifies chunk; identical chunks shared |
| GC race (chunk still needed) | Ref-count; GC only when `ref_count=0`; careful with concurrent new refs (grace period) |
| Deleted file | Soft-delete (tombstone) + retain versions for restore window |
| Move/rename | Metadata-only op (no chunk transfer) |

### The edge cases that trip people up

Each row is a "but what if...?" — here they are in plain terms:

- **Partial upload** — your wifi dies after uploading 3 of 5 chunks. No problem: chunks upload independently, so on retry you only re-send the 2 missing ones. Crucially, the new **version is committed only when *all* its chunks are present** — so other devices never see a half-uploaded, corrupt file.
- **Move / rename is free** — renaming `report.docx` → `final.docx` or dragging it to another folder touches **only metadata** (a name/parent field). Not a single byte moves, because the recipe and chunks are unchanged. This is the big payoff of separating metadata from blocks (§6).
- **GC race** — the trickiest one, expanded below.

```java
// Version commit is all-or-nothing: only flip "current" once every chunk exists.
void commitVersionSafely(long fileId, List<String> recipe) {
    for (String hash : recipe) {
        if (!blockStore.exists(hash)) {
            throw new IncompleteUpload(hash);   // missing chunk → do NOT publish this version
        }
    }
    metadata.setCurrentVersion(fileId, recipe); // every chunk present → now it's safe & visible
}
```

#### Q: What's the "GC race," and why does it need a grace period?

Garbage collection frees chunks nobody uses anymore (`ref_count = 0`, see §10/§13). The danger is a **race** between "this chunk looks unused" and "someone is about to use it":

```
1. GC scans:  chunk XX has ref_count = 0  → "looks unused, I'll delete it"
2. MEANWHILE: a new upload dedups against XX (same bytes!) and is about to bump its ref_count
3. GC deletes XX  →  the new file now points at a chunk that no longer exists  →  corruption 💥
```

The fix is a **grace period**: don't delete a chunk the instant it hits 0; wait a while (e.g. mark it "deletable at time T," and only actually delete if it's *still* unreferenced after T). That window gives in-flight uploads time to re-reference it. It trades a little wasted space for safety — the right call, since silently corrupting a file is unforgivable.

> **General theme of this table:** at every risky moment (partial upload, delete, dedup+GC) the system prefers "**keep the data, be a bit wasteful, resolve later**" over "act fast and risk losing bytes." Durability beats tidiness.

---

## 13. Scaling & Failure

- **Block store (S3)** = durable, replicated, effectively infinite; **dedup + compression** cut storage massively.
- **Metadata service** sharded by user/namespace; the transactional brain; **journal** makes sync O(delta).
- **Delta sync** minimizes bandwidth; **resumable** chunk uploads survive flaky networks.
- **Notification service** (long-poll/WebSocket) avoids polling storms.
- **GC**: chunks with `ref_count = 0` are garbage-collected (with a grace period to avoid races).
- **Failure**: partial upload → missing chunks re-sent; metadata replicated; conflicts kept as copies (never lose data).

### How each part scales (and survives failure)

The design scales well because its two halves scale in completely different, independent ways:

| Part | How it scales | Why it holds up |
| --- | --- | --- |
| **Block store (S3)** | Just add more storage — S3 is effectively infinite and replicates each object across machines/datacenters | Bytes are immutable + content-addressed, so they cache and replicate trivially; **dedup + compression** shrink the raw exabytes dramatically |
| **Metadata service** | **Shard by user/namespace** — split the billions of rows across many DB servers, each owning a slice | It's small per user and the **journal** makes sync O(delta), so no single server drowns |
| **Sync bandwidth** | **Delta sync** — move only changed chunks; **resumable** so flaky networks don't restart transfers | The expensive work (moving bytes) is minimized to just what changed |
| **Notifications** | Server-push instead of polling | A billion idle devices cost almost nothing until something actually changes |

**On sharding metadata:** a single database can't hold billions of rows for the whole user base. Split the keyspace by user/namespace — users A–F on server 1, G–M on server 2, and so on. Each server answers quickly for its slice, and you add servers as the population grows.

#### Q: What actually protects me from losing a file?

Several layers, each covering a different failure:

- **Durability of bytes** — S3 keeps multiple replicas across locations, so a dead disk (or a dead datacenter) doesn't lose your chunks.
- **Metadata replication** — the database is replicated too, so the "recipes" survive a server dying.
- **Conflicts → conflicted copies** — concurrent edits never overwrite; both survive (§8).
- **Versioning + soft delete** — even *you* deleting or mangling a file is recoverable within the retention window, because old versions (and their chunks) are retained.

### Why a garbage collector is needed instead of deleting chunks immediately

Chunks can't be deleted the instant a file is deleted, because of dedup: the chunk you'd delete might still be referenced by another version, another file, or another *user*. That's why deletion is a two-step dance: (1) drop the reference (`ref_count--`), (2) a background **GC** later frees only chunks whose count reached 0 — and even then, after a **grace period** to dodge the race in §12. Deleting bytes is the one truly irreversible action, so the system is deliberately slow and careful about it.

---

## 14. Interview Cheat Sheet

> **"How do you sync efficiently without re-uploading whole files?"**
> "Chunk the file (**content-defined boundaries** so an early insert doesn't reshuffle everything), hash each chunk, and only upload chunks whose hashes are new. A file version is just an ordered list of chunk hashes — editing one part changes one chunk."

> **"How is storage organized?"**
> "Two subsystems: a **metadata service** (files, versions, chunk lists, permissions, change journal — transactional) and a **block store** (chunk bytes, content-addressed — S3). Clients get chunk lists from metadata and move chunks directly to/from the block store."

> **"How do devices know what changed?"**
> "A **per-namespace change journal** + a **device cursor**: a notification (long-poll/WebSocket) wakes the device, which asks 'give me changes after my cursor', fetches the new chunk lists, and downloads only chunks it lacks."

> **"Concurrent edits?"**
> "Base-version mismatch → keep both as a **conflicted copy** rather than overwriting — never lose data."

> **"How does dedup save space + handle GC?"**
> "Content-addressed chunks are stored once (across versions/users), tracked by **ref_count**; GC reclaims chunks at ref_count 0 (with a grace period to avoid races)."

### Tricky scenarios (rapid-fire)

| Scenario | What happens / what to do |
| --- | --- |
| **GC race with an in-flight upload** | A chunk hits `ref_count = 0` just as a new upload dedups against it. **Grace period**: mark "deletable at T," only free it if *still* unreferenced after T, and have commits re-check/re-bump `ref_count` — so the in-flight upload wins and no live chunk is deleted (§12). |
| **Share revoked mid-upload** | The uploader loses access while chunks are still going up. Bytes may land in the block store (harmless — content-addressed, unreferenced), but the **version commit re-checks the `shares` grant and fails**, so no journal entry is created and other members never see it. Orphaned chunks get GC'd. |
| **Offline a week, then reconnect** | Device does both directions: **pull** journal from its stale cursor (may be thousands of entries → server may return a **compacted snapshot** instead of every entry), and **push** its queued local deltas. Files touched on *both* sides → **conflicted copies** (§8). Same mechanic as first sync (§7), just a bigger delta. |
| **Two devices upload identical new bytes at once** | Both compute the same hash and both may `PUT` to the same S3 key — idempotent (same content), so it's safe; `ref_count` ends correct because commits bump it transactionally. Dedup still holds. |
| **Partial upload then crash** | Chunks upload independently; the version is **committed only when all its chunks exist** (§12), so peers never see a half-file. Retry re-sends only the missing chunks. |
| **Rename during another device's sync** | Rename is a **metadata-only** journal entry; the other device applies it as an ordinary op (no bytes move). Ordering by `seq` keeps it deterministic. |

> **Ultimate layer model:** content-hash = dedup + integrity · journal + cursor = O(delta) sync · ref-count + grace period = safe GC · base-version check = conflict detection · conflicted copies = never lose data.

---

## 15. API Design

> 💡 Distinguish **metadata operations** (small, transactional, hit the DB) from **block-store transfers** (big, dumb bytes, go straight to S3 via pre-signed URLs). This **two-phase upload** — probe/authorize on the metadata service, then transfer on the block store — is the shape of every write.

```
--- Metadata service (the "brain": small, transactional) ---
POST /v1/has-chunks                                → dedup probe: which of these hashes are NEW?
     body: { hashes: [a1b2, c3d4, ...] }
     → 200 { missing: [c3d4] }                       (only c3d4 must be uploaded)

POST /v1/upload-url                                → authorize a direct block upload
     body: { hash: c3d4, size }
     → 200 { url: <pre-signed S3 PUT>, expiresAt }   (temporary, chunk-scoped)

POST /v1/version                                   → commit a new file version (the recipe)
     body: { fileId, baseVersion, chunkHashes: [a1b2, c3d4, e5f6] }
     header: Idempotency-Key: <uuid>
     → 201 { versionId, seq }                        | 409 { conflict, conflictedCopyId }

GET  /v1/delta?namespaceId=..&cursor=N             → pull changes since a cursor
     → 200 { entries: [ {seq, fileId, versionId, op} ... ], nextCursor }

GET  /v1/versions?fileId=..                        → version history (for restore)
POST /v1/restore                                   → point a file at an old version
     body: { fileId, versionId }

POST /v1/shares                                    → grant a role on a namespace
     body: { namespaceId, granteeId, role }
DELETE /v1/shares                                  → revoke (stops future sync immediately)

--- Notification service (push, not poll) ---
GET  /v1/notify?namespaceIds=..                    → long-poll / WebSocket: "namespace X changed"
     → wakes the device, which then calls GET /delta

--- Block store (the "muscle": bytes only, NOT our servers) ---
PUT  <pre-signed S3 URL>                            → upload one chunk's bytes directly to S3
GET  <pre-signed S3 URL>                            → download one chunk's bytes directly from S3
```

### Two-phase upload: metadata first, bytes second

Every upload is **two phases against two different systems**, and keeping them separate is the whole point (§4):

```
Phase 1 — METADATA (cheap, chatty, hits the DB):
  POST /has-chunks [a1,c3,XX]  → { missing: [XX] }   (dedup probe: a1 & c3 already exist)
  POST /upload-url {hash: XX}  → { url, expiresAt }  (authorize ONE chunk, briefly)

Phase 2 — BLOCK STORE (heavy, dumb, skips our servers):
  PUT <pre-signed url> <XX bytes>                    (client → S3 directly)

Back to METADATA (commit the recipe):
  POST /version {baseVersion, [a1,c3,XX]}            → seq++ → wake other devices
```

- **`/has-chunks`** is the **dedup probe**: hand the server a list of hashes, get back only the ones it lacks. This is what turns "upload a file" into "upload the one chunk that actually changed" — the API-level face of delta sync.
- **`/upload-url`** returns a **pre-signed, chunk-scoped, short-lived** S3 URL. The metadata service checks permission + quota *before* issuing it; the client then transfers bytes straight to S3 so gigabytes never flow through our application servers.
- **`/version`** is the transactional commit. It carries the **`baseVersion`** so the server can detect a concurrent edit (base-version mismatch → conflicted copy, §8) and appends a **journal entry** so other devices get woken.
- **`/delta`** + **`/notify`** are the read/sync side: `/notify` pushes "something changed," `/delta` returns only the entries after your cursor (§7).

> ⚠️ **Pitfall:** don't stream chunk bytes *through* a `POST /upload` on your metadata/app servers. It turns your transactional brain into a dumb byte pump, wrecks its latency, and wastes bandwidth. Metadata authorizes; **the block store moves the bytes**.

> The write path (`/has-chunks` → `/upload-url` → `/version`) and the read path (`/notify` → `/delta`) are the same two flows drawn in §11, just named as endpoints.

---

## 16. Consistency & CAP Tradeoffs

> Interviewers love: "Where do you pick consistency, and where do you pick availability?" A file-sync system is a great answer because it deliberately picks **differently for metadata vs sync**.

| Path | Choice | Why |
| --- | --- | --- |
| **Metadata commit** (new version, rename, share, `ref_count`) | **CP** (strong consistency) | The version commit needs an **atomic compare-and-set on `current_version`** to detect conflicts (§8) and keep recipes correct. A split-brain that accepted two "current" versions would corrupt history or lose data. Correctness > availability here. |
| **Sync notifications** (`/notify` wake-ups) | **AP** (availability + eventual) | A missed or delayed push is harmless — the device still catches up via `/delta` from its cursor. Better to stay available and be a few seconds late than to block. |
| **Block store (chunk bytes)** | **AP-ish, but trivially consistent** | Chunks are immutable and content-addressed, so there's no "conflicting write" to a chunk — any replica that has the bytes is correct. Replicate freely for availability. |
| **Downstream (thumbnails, indexing, analytics)** | **Eventual** | Async, retryable, off the critical path. |

The signature move is the **"conflicted copy" itself as a CAP decision**: when two devices commit against the same base version, the system *could* block one writer until it resolves (favoring consistency) — but instead it **accepts both writes and reconciles later** (a conflicted copy). That's choosing **availability over strict single-value consistency**: the write is never rejected or blocked, at the cost of two copies existing until a human merges them.

- Metadata is **strongly consistent per file/namespace** (the compare-and-set is serializable on that row), and **eventually consistent across the fleet** (devices converge as they drain the journal).
- Bytes are safe by construction: **content-addressing means a chunk can't be "stale"** — its name *is* its content.

> One-liner: **"Strong consistency where the recipe/version is committed (CP metadata), eventual/available everywhere the bytes flow and sync — and a conflict becomes a *conflicted copy* rather than a blocked write (availability over consistency)."**

---

## 17. Real-World Extensions

> Brief, high-signal add-ons that show breadth. Each is a sentence or two — enough to raise, not derail, an interview.

### Client-side / end-to-end encryption (zero-knowledge)

Encrypt chunks **on the device** with a key the server never sees, so the provider stores ciphertext it can't read ("zero-knowledge"). The catch: **dedup and encryption fight each other** — random per-user keys mean identical files encrypt to *different* bytes, killing cross-user dedup. Real systems use **convergent encryption** (derive the key from the content's own hash) so identical plaintext still yields identical ciphertext (dedup survives), accepting a known confirmation-of-file weakness. ⚠️ Server-side features (search, thumbnails, web preview) mostly stop working once the server can't read the bytes.

### Selective sync & bandwidth throttling

**Selective sync** lets a device subscribe to only *part* of a namespace (don't pull the 500 GB video folder onto a laptop with a 256 GB SSD) — it applies journal entries but downloads bytes lazily / only for chosen subtrees. **Bandwidth throttling** caps up/download rate so sync doesn't hog the user's connection; pairs naturally with the **backpressure** from first sync (§7).

### Trash / restore flow

Delete is a **soft delete** (§10): the row is tombstoned and chunks are retained for a **retention window**, so "Trash → Restore" just clears the flag and re-points the file — no re-upload, because `ref_count` kept the chunks alive. Only after the window expires does GC drop the references and reclaim bytes.

### LAN sync

When two devices on the **same local network** both belong to a namespace, a new device can fetch chunks **peer-to-peer over the LAN** instead of from S3. The metadata service still decides *what* to sync (recipes, permissions); the LAN just provides a **faster/cheaper source for the bytes** — a huge win for the 500 GB bootstrap (§7). Chunks are content-addressed, so a LAN-fetched chunk is verified by re-hashing exactly like an S3-fetched one.

### GC sweeper design

The garbage collector is a **background sweeper**, not part of any request. Sketch:

```
1. Candidate scan   → SELECT chunk_hash FROM chunks WHERE ref_count = 0   (uses the ref_count index, §10)
2. Grace period     → skip chunks whose ref_count hit 0 more recently than T (dodge the in-flight-upload race, §12)
3. Verify + delete  → re-check ref_count is STILL 0 in a transaction, then delete the S3 object
4. Rate-limit       → sweep in small batches so GC never competes with live traffic
```

- Runs on a **scheduler** (periodic), reads the **`chunks(ref_count = 0)`** partial index, and honors the **grace period** so an upload that's about to re-reference a chunk isn't beaten to the delete.
- Deleting bytes is the **only irreversible action** in the system, so the sweeper is deliberately conservative: verify-under-transaction, batch, back off. Wasted space is cheap; a deleted-but-needed chunk is unforgivable.

---

## 18. How to Drive the Interview (framework)

> Use this order so you never freeze. Spend ~5 min on 1–4, then go deep on 5–6.

1. **Clarify requirements** (functional + NFRs) — §2
2. **Estimate scale** (files, bytes, dedup savings, sync deltas) — §3
3. **Define APIs** (metadata ops vs block-store transfers; two-phase upload) — §15
4. **High-level architecture + data model** — §4, §10
5. **Deep dive: the hard part** → **chunking + dedup + delta sync** (content-addressing) — §5, §6
6. **Deep dive: sync engine** → **change journal + cursor + notifications**, then **conflicts** — §7, §8
7. **Address scale + edge cases** → sharding metadata, GC + grace period, first sync — §12, §13, §17
8. **Summarize tradeoffs** — §16, §14

> 🎤 **Lead with the core insight:** state up front that "the crux is *don't move whole files* — content-address chunks so dedup and delta sync fall out, and sync devices against a per-namespace journal." Then spend most of your time on chunking and the journal. That's what they're testing.

---

## 19. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Content-Addressed Storage** | Chunks keyed by hash | Dedup + integrity |
| **Strategy** | Chunking (fixed vs content-defined), conflict resolution | Swap algorithms |
| **Observer / Pub-Sub** | Change notifications to devices | Push sync instead of poll |
| **Event Sourcing / Journal** | Per-namespace change journal + cursor | Efficient "what changed since" |
| **Memento / Snapshot** | File version history | Restore old states |
| **Reference Counting** | Chunk `ref_count` → GC unreferenced chunks | Reclaim storage |
| **Producer-Consumer** | Async chunk processing, thumbnailing, indexing | Offload work |
| **Repository** | Metadata access | Abstraction |
| **Facade** | Sync client API over metadata + block store | Simple client |
| **Ports & Adapters** | Block store, notification, auth | Swap infra |

---

## 20. Final Takeaways

- **Chunk + content-address (hash)** → dedup + **delta sync** (only changed chunks) + resumable + integrity; use **content-defined chunking** for stable dedup under edits.
- Split **metadata service** (small, transactional: files/versions/chunk lists/**journal**) from **block store** (huge, dumb: chunk bytes).
- **Sync via a per-namespace change journal + device cursor** + push notifications (long-poll/WebSocket) — O(delta), not full scans.
- **Versioning** via chunk-list snapshots; **conflicts → conflicted copies** (never lose data).
- **Ref-counted chunks + GC** reclaim space (grace period for races).
- Patterns: Content-Addressed Storage, Strategy (chunking), Observer, Event-Sourcing/Journal, Memento, Reference Counting.

### Related notes

- [Video Streaming](video-streaming-system-design.md) — chunked media/blob overlap
- [Google Docs](google-docs-system-design.md) — concurrent-edit conflict handling
- [Databases — Deep Dive](../concepts/databases-deep-dive.md) · [Consistent Hashing](../concepts/consistent-hashing.md) · [Caching Strategies](../concepts/caching-strategies.md)
