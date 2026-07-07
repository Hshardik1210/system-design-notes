# File Storage & Sync — System Design (Dropbox / Google Drive)

> **Core challenge:** store users' files durably and **sync them across all their devices** efficiently — uploading only what changed (**chunking + dedup**), resolving concurrent edits, and sharing. The signature ideas are **content-addressed chunking**, **delta sync**, a **metadata service separate from the block store**, and a **change journal** devices sync against.

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
- [13. Design Patterns (that can be used)](#13-design-patterns-that-can-be-used)
- [14. Scaling & Failure](#14-scaling--failure)
- [15. Interview Cheat Sheet](#15-interview-cheat-sheet)
- [16. Final Takeaways](#16-final-takeaways)

---

## 1. Mental Model

```
File → split into CHUNKS → store chunks in block store (dedup by content hash)
     → metadata service tracks which chunks make up which file version
Devices sync against a CHANGE JOURNAL (cursor) → download only NEW chunks they lack
```

Two subsystems: a **metadata service** (small, transactional: files, versions, chunk lists, journal) and a **block store** (big, dumb: chunk bytes). Efficiency = only move **changed chunks**.

---

## 2. Requirements

**Functional**
- Upload/download files; **auto-sync** across a user's devices.
- Only transfer **changed parts** (delta sync); **resume** interrupted transfers.
- **Version history**; **conflict handling**; **sharing** with permissions.

**Non-functional**
- **Durable** (never lose files), available, **bandwidth-efficient**, scalable to billions of files, works **offline** (sync later).

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

---

## 5. Chunking & Deduplication

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

---

## 6. Metadata vs Block Storage

| Subsystem | Stores | Tech |
| --- | --- | --- |
| **Block store** | Chunk bytes, keyed by content hash | Blob/object store (S3) — durable, replicated, huge |
| **Metadata service** | Files, folders, versions, chunk lists, permissions, device cursors, journal | RDBMS/NoSQL (transactional), sharded by user/namespace |

- **Why split?** Blocks are huge + immutable + dumb → cheap object storage. Metadata is small + transactional + queried constantly → a database. Each scales independently.

---

## 7. Sync — Journal, Cursor & Notifications

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

---

## 8. Conflict Resolution & Versioning

- Every change creates a **new version** (metadata points to a new ordered chunk list); old chunks retained for history.
- **Concurrent edits** to the same file from two devices → detected via **base-version mismatch** (both edited version N): keep **both** as a **"conflicted copy"** (`file (Device B's conflicted copy)`) rather than silently overwriting → **never lose data**.
- **Version history** = the list of prior chunk-list snapshots; **restore** = point the file at an old version (chunks still exist).

---

## 9. Sharing & Permissions

- Share a file/folder with users or via link; roles (viewer/editor/owner).
- A **shared folder is its own namespace** with its own journal → all members' devices sync it.
- Permission checks in the metadata service; revoking access stops sync + downloads.

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

---

## 13. Design Patterns (that can be used)

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

## 14. Scaling & Failure

- **Block store (S3)** = durable, replicated, effectively infinite; **dedup + compression** cut storage massively.
- **Metadata service** sharded by user/namespace; the transactional brain; **journal** makes sync O(delta).
- **Delta sync** minimizes bandwidth; **resumable** chunk uploads survive flaky networks.
- **Notification service** (long-poll/WebSocket) avoids polling storms.
- **GC**: chunks with `ref_count = 0` are garbage-collected (with a grace period to avoid races).
- **Failure**: partial upload → missing chunks re-sent; metadata replicated; conflicts kept as copies (never lose data).

---

## 15. Interview Cheat Sheet

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

---

## 16. Final Takeaways

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
