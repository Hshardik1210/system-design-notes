# File Storage & Sync — System Design (Dropbox / Google Drive)

> **Core challenge:** store users' files durably and **sync them across all their devices** efficiently — uploading only what changed (**chunking + dedup**), resolving concurrent edits, and sharing. The signature ideas are **content-addressed chunking**, **delta sync**, and a **metadata service** separate from the **block store**.

---

## Contents

- [1. Mental Model](#1-mental-model)
- [2. Requirements](#2-requirements)
- [3. Chunking & Deduplication](#3-chunking--deduplication)
- [4. Metadata vs Block Storage](#4-metadata-vs-block-storage)
- [5. Sync Flow (upload / download / delta)](#5-sync-flow-upload--download--delta)
- [6. Conflict Resolution & Versioning](#6-conflict-resolution--versioning)
- [7. Sharing & Permissions](#7-sharing--permissions)
- [8. Data Model (all tables)](#8-data-model-all-tables)
- [9. Design Patterns (that can be used)](#9-design-patterns-that-can-be-used)
- [10. Scaling & Failure](#10-scaling--failure)
- [11. Interview Cheat Sheet](#11-interview-cheat-sheet)
- [12. Final Takeaways](#12-final-takeaways)

---

## 1. Mental Model

```
File → split into CHUNKS → store chunks in block store (dedup by hash)
     → metadata service tracks which chunks make up which file version
Other devices poll/subscribe → learn file changed → download only NEW chunks
```

Two subsystems: a **metadata service** (small, transactional: files, versions, chunk lists) and a **block store** (big, dumb: chunk bytes). Sync efficiency comes from only moving **changed chunks**.

---

## 2. Requirements

**Functional**
- Upload/download files; **sync** across devices automatically.
- Only transfer **changed parts** (delta sync); resume interrupted transfers.
- **Version history**; conflict handling; **sharing** with permissions.

**Non-functional**
- **Durable** (never lose files), available, efficient bandwidth, scalable to billions of files, works offline (sync later).

---

## 3. Chunking & Deduplication

Split each file into **chunks** (fixed e.g. 4MB, or content-defined boundaries). Each chunk is identified by its **content hash** (SHA-256) — **content-addressed storage**.

```
file = [chunkHashA, chunkHashB, chunkHashC, ...]   (an ordered list of chunk hashes)

Benefits:
  - Dedup: identical chunk stored ONCE (across versions AND across users)
  - Delta sync: edit one part → only that chunk's hash changes → upload just that chunk
  - Resumable: upload chunks independently; retry missing ones
```

| Chunking | Note |
| --- | --- |
| **Fixed-size** | Simple; a byte inserted early shifts all boundaries (poor dedup) |
| **Content-defined (rolling hash)** | Boundaries follow content → stable dedup under inserts |

---

## 4. Metadata vs Block Storage

| Subsystem | Stores | Tech |
| --- | --- | --- |
| **Block store** | Chunk bytes, keyed by content hash | Blob/object store (S3) — durable, replicated |
| **Metadata service** | Files, folders, versions, chunk lists, permissions, device sync state | RDBMS/NoSQL (transactional) |

- Client talks to **metadata service** to learn "what changed," then pulls/pushes **chunks** to/from the block store directly (often via pre-signed URLs).
- Separation lets each scale independently (metadata = small/transactional; blocks = huge/dumb).

---

## 5. Sync Flow (upload / download / delta)

```
Upload (edited file):
  1. client chunks file, computes chunk hashes
  2. asks metadata service: which of these hashes are NEW?
  3. uploads only NEW chunks to block store
  4. commits a new file version = ordered chunk-hash list (metadata)

Download / sync to another device:
  1. device learns file changed (long-poll / notification / WebSocket)
  2. fetches new version's chunk list from metadata
  3. downloads only chunks it doesn't already have locally
  4. reassembles the file
```

- **Change notification**: a **sync/notification service** (long-poll or WebSocket) tells devices when something changed, instead of constant polling.
- **Offline**: queue local changes; sync + resolve on reconnect.

---

## 6. Conflict Resolution & Versioning

- Every change creates a **new version** (metadata points to the new chunk list); old chunks retained for history.
- **Concurrent edits** to the same file from two devices → detect via version vectors / base-version mismatch → keep both as a **"conflicted copy"** (Dropbox style) rather than silently overwriting.
- Version history = list of prior chunk-list snapshots; restore = point file to an old version.

---

## 7. Sharing & Permissions

- Share a file/folder with users/links; roles (viewer/editor/owner).
- Permission checks in the metadata service; shared folders sync to all members' devices.

---

## 8. Data Model (all tables)

```sql
CREATE TABLE users ( user_id BIGINT PRIMARY KEY, email VARCHAR(255) UNIQUE, quota_bytes BIGINT, used_bytes BIGINT );

CREATE TABLE files (
    file_id BIGINT PRIMARY KEY, owner_id BIGINT, parent_folder_id BIGINT,
    name TEXT, is_folder BOOLEAN, current_version BIGINT,
    is_deleted BOOLEAN DEFAULT FALSE, updated_at TIMESTAMP
);
CREATE INDEX idx_files_folder ON files(parent_folder_id);

CREATE TABLE file_versions (
    version_id BIGINT PRIMARY KEY, file_id BIGINT, version_no INT,
    size_bytes BIGINT, created_by BIGINT, created_at TIMESTAMP
);
CREATE TABLE version_chunks (            -- ordered chunk list per version
    version_id BIGINT, seq INT, chunk_hash CHAR(64),
    PRIMARY KEY (version_id, seq)
);

CREATE TABLE chunks (                     -- content-addressed; dedup registry
    chunk_hash CHAR(64) PRIMARY KEY, size_bytes INT, ref_count BIGINT,  -- storage_path implicit by hash
    created_at TIMESTAMP
);

CREATE TABLE device_sync_state (          -- per device sync cursor
    device_id BIGINT, user_id BIGINT, last_sync_cursor BIGINT, last_seen TIMESTAMP,
    PRIMARY KEY (device_id)
);

CREATE TABLE shares (
    file_id BIGINT, grantee_id BIGINT, role VARCHAR(10),   -- VIEWER, EDITOR, OWNER
    PRIMARY KEY (file_id, grantee_id)
);
-- Chunk bytes live in the block store (S3), keyed by chunk_hash
```

> **Tables to consider:** users, files (tree), file_versions, version_chunks, chunks (dedup registry with ref_count), device_sync_state, shares, sync_events/journal. Bytes in block store.

---

## 9. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Content-Addressed Storage** | Chunks keyed by hash | Dedup + integrity |
| **Strategy** | Chunking (fixed vs content-defined), conflict resolution | Swap algorithms |
| **Observer / Pub-Sub** | Change notifications to devices | Push sync instead of poll |
| **Repository** | Metadata access | Abstraction |
| **Memento / Snapshot** | File version history | Restore old states |
| **Producer-Consumer** | Async chunk processing, thumbnailing, indexing | Offload work |
| **Facade** | Sync client API over metadata + block store | Simple client |
| **Reference Counting** | Chunk `ref_count` for GC of unreferenced chunks | Reclaim storage |
| **Ports & Adapters** | Block store, notification, auth | Swap infra |

---

## 10. Scaling & Failure

- **Block store (S3)** = durable, replicated, effectively infinite; dedup cuts storage massively.
- **Metadata service** sharded by user/file; the transactional brain.
- **Delta sync** minimizes bandwidth; **resumable** chunk uploads survive flaky networks.
- **Notification service** (long-poll/WebSocket) avoids polling storms.
- **GC**: chunks with `ref_count = 0` (no version references them) are garbage-collected.
- **Failure**: partial upload → missing chunks re-sent; metadata replicated; conflicts kept as copies (never lose data).

---

## 11. Interview Cheat Sheet

> **"How do you sync efficiently without re-uploading whole files?"**
> "Chunk the file (content-defined boundaries), hash each chunk, and only upload chunks whose hashes are new. A file version is just an ordered list of chunk hashes — editing one part changes one chunk."

> **"How is storage organized?"**
> "Two subsystems: a **metadata service** (files, versions, chunk lists, permissions — transactional) and a **block store** (chunk bytes, content-addressed by hash — S3). Clients get chunk lists from metadata and transfer chunks directly to/from the block store."

> **"How do devices know something changed?"**
> "A notification/sync service (long-poll or WebSocket) pushes change events; the device fetches the new chunk list and downloads only chunks it lacks."

> **"Concurrent edits?"**
> "Detect via version mismatch/version vectors; keep both as a conflicted copy rather than overwriting — never lose data."

> **"How does dedup save space?"**
> "Content-addressed chunks: identical chunks (across versions and users) are stored once, tracked by ref-count; GC reclaims unreferenced chunks."

---

## 12. Final Takeaways

- **Chunk + content-address (hash)** → dedup + **delta sync** (upload only changed chunks) + resumable transfers.
- Split **metadata service** (small, transactional: files/versions/chunk lists) from **block store** (huge, dumb: chunk bytes).
- **Push change notifications** (long-poll/WebSocket), not polling.
- **Versioning** via chunk-list snapshots; **conflicts → conflicted copies** (never lose data).
- **Ref-counted chunks** + GC reclaim space.
- Patterns: Content-Addressed Storage, Strategy (chunking), Observer, Memento, Reference Counting, Ports&Adapters.

### Related notes

- [Video Streaming — System Design](video-streaming-system-design.md) — chunked media/blob overlap
- [Google Docs — System Design](google-docs-system-design.md) — concurrent-edit conflict handling
- [Consistent Hashing](../concepts/consistent-hashing.md) · [Caching Strategies](../concepts/caching-strategies.md)
