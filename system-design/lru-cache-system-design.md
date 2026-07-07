# LRU Cache — Low-Level Design

> A staple **coding + LLD** question: an in-memory cache with a fixed capacity that evicts the **Least Recently Used** entry when full — with **O(1)** `get` and `put`. The trick is the **HashMap + Doubly Linked List** combo.

---

## Contents

- [1. Requirements](#1-requirements)
- [2. The Core Idea — HashMap + Doubly Linked List](#2-the-core-idea--hashmap--doubly-linked-list)
- [3. Implementation](#3-implementation)
- [4. Thread Safety & Variants](#4-thread-safety--variants)
- [5. Design Patterns (that can be used)](#5-design-patterns-that-can-be-used)
- [6. Interview Cheat Sheet](#6-interview-cheat-sheet)
- [7. Final Takeaways](#7-final-takeaways)

---

## 1. Requirements

- `get(key)` → value if present (and mark as most-recently-used), else miss.
- `put(key, value)` → insert/update; if at capacity, **evict the least-recently-used** entry.
- Both operations in **O(1)** time.
- Optional: TTL/expiry, thread-safety, size by bytes.

---

## 2. The Core Idea — HashMap + Doubly Linked List

Neither structure alone gives O(1) for both "look up by key" and "know/evict the LRU":

| Need | Structure |
| --- | --- |
| O(1) lookup by key | **HashMap** `key → node` |
| O(1) move-to-recent + O(1) evict-oldest | **Doubly Linked List** (recency order) |

```
Most-recently-used ↔ ... ↔ Least-recently-used
   [head] ⇄ [B] ⇄ [A] ⇄ [tail]
HashMap: A→nodeA, B→nodeB   (points into the list for O(1) splice)

get/put a key → move its node to the head (MRU)
evict → remove the node at the tail (LRU)
```

- Doubly linked (not singly) so you can **remove/move a node in O(1)** given a pointer to it.

---

## 3. Implementation

```java
class LRUCache {
    class Node { int key, val; Node prev, next; }
    private final int capacity;
    private final Map<Integer, Node> map = new HashMap<>();
    private final Node head, tail;                 // sentinels

    LRUCache(int capacity) {
        this.capacity = capacity;
        head = new Node(); tail = new Node();
        head.next = tail; tail.prev = head;        // empty list
    }

    int get(int key) {
        Node n = map.get(key);
        if (n == null) return -1;                  // miss
        moveToFront(n);                            // mark MRU
        return n.val;
    }

    void put(int key, int val) {
        Node n = map.get(key);
        if (n != null) { n.val = val; moveToFront(n); return; }
        if (map.size() == capacity) {              // evict LRU
            Node lru = tail.prev;
            remove(lru); map.remove(lru.key);
        }
        Node fresh = new Node(); fresh.key = key; fresh.val = val;
        map.put(key, fresh); addFront(fresh);
    }

    // ---- O(1) DLL helpers ----
    private void remove(Node n) { n.prev.next = n.next; n.next.prev = n.prev; }
    private void addFront(Node n){ n.next = head.next; n.prev = head; head.next.prev = n; head.next = n; }
    private void moveToFront(Node n){ remove(n); addFront(n); }
}
```

- **Sentinel head/tail** nodes remove edge-case null checks.
- `get`, `put`, evict are all **O(1)**.
- In practice: Java's `LinkedHashMap(accessOrder=true)` implements this; Redis uses **approximate LRU** (sampling) to save memory.

---

## 4. Thread Safety & Variants

| Concern | Approach |
| --- | --- |
| **Thread safety** | Lock around ops, or a concurrent design (striped locks); simplest = `synchronized`/`ReentrantLock` |
| **TTL / expiry** | Store `expiresAt` per node; lazy-expire on `get` + background sweep |
| **LFU (frequency)** | Track counts + frequency buckets (different structure) |
| **Approximate LRU** | Sample K entries, evict oldest (Redis) — avoids maintaining a perfect list at scale |
| **Size by bytes** | Track total bytes; evict until under limit |
| **Distributed** | Shard by key (consistent hashing) → many LRU nodes = a distributed cache |

---

## 5. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Strategy** | Eviction policy (LRU/LFU/FIFO/approx) behind an interface | Swap policies without changing the cache API |
| **Decorator** | Add TTL, metrics, thread-safety as wrappers around a base cache | Compose features |
| **Facade / Adapter** | Uniform `Cache` interface over the map+DLL internals | Clean API |
| **Observer** | Notify on eviction (write-back, metrics) | Decouple side-effects |
| **Template Method** | Common get/put skeleton, policy-specific eviction step | Reuse flow |
| **Proxy** | Caching proxy in front of a data source (cache-aside) | Transparent caching |

---

## 6. Interview Cheat Sheet

> **"Design an LRU cache with O(1) get/put."**
> "**HashMap + doubly linked list.** The map gives O(1) key lookup to a node; the DLL maintains recency order — most-recently-used at the head, least at the tail. `get`/`put` move the node to the head; when full, evict the tail node. Doubly linked so any node splices out in O(1); sentinel head/tail avoid edge cases."

> **"Why a doubly linked list, not an array or singly linked?"**
> "You must remove/move an arbitrary node in O(1) given its pointer — a singly linked list can't fix the predecessor's `next` in O(1), and an array shift is O(n)."

> **"How would you scale it / make it approximate?"**
> "At large scale, maintaining exact LRU order is costly — sample K entries and evict the oldest (Redis-style approximate LRU). Distributed = shard keys via consistent hashing across many cache nodes. Add TTL via per-entry expiry + lazy/background eviction."

> **"Thread safety?"**
> "Guard with a lock (simplest) or a concurrent/striped-lock design; `LinkedHashMap(accessOrder=true)` gives a quick single-threaded implementation."

---

## 7. Final Takeaways

- **HashMap (O(1) lookup) + Doubly Linked List (O(1) recency move/evict)** = O(1) `get`/`put`.
- MRU at head, LRU at tail; evict the tail; sentinels simplify edges.
- **Approximate LRU (sampling)** at scale (Redis); **shard + consistent hashing** for distributed.
- Eviction policy is a **Strategy**; TTL/metrics/locking via **Decorator**.

### Related notes

- [Caching Strategies](../concepts/caching-strategies.md) — eviction policies, invalidation
- [Distributed Cache](distributed-cache-system-design.md) — scaling this across nodes
