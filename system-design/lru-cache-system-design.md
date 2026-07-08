# LRU Cache — Low-Level Design

> A staple **coding + LLD** question: an in-memory cache with a fixed capacity that evicts the **Least Recently Used** entry when full — with **O(1)** `get` and `put`. The trick is the **HashMap + Doubly Linked List** combo.

> **How to read this doc:** each section has the dense interview summary first, then a **deep dive** (annotated Java and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand.

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

### What is an LRU cache, really?

An LRU cache holds a fixed number of entries in a defined recency order. Take a cache with capacity **3**. The most-recently-used entry sits at one end and the least-recently-used at the other. On every access an entry is moved to the most-recently-used end. When a **4th** entry is inserted and the cache is full, the entry that hasn't been touched the longest (the least-recently-used one) is evicted to make space.

The moving parts:

- **Cache** — fixed capacity (here 3).
- **Entry** — a cached `key → value` pair.
- **Marking "recently used"** — moving an entry to the most-recently-used end; happens on every `get` *and* every `put`.
- **Eviction** — removing the **Least Recently Used** entry when the cache is full.

Why a cache at all? Fetching data from the real source (a database, disk, or a slow API) is expensive. A cache keeps a small set of "hot" items in fast memory so repeat requests are instant. But memory is limited, so you can't keep everything — you keep the stuff most likely to be used again. "Recently used → likely to be used again" is the bet LRU makes.

#### Q: Why *Least Recently Used*? Why not evict randomly or the oldest-inserted?

Because of **temporal locality**: something you used a moment ago is very likely to be used again soon (think of the file you're actively editing). Evicting randomly might throw out a hot item. Evicting the **oldest-inserted** (that's a plain FIFO/queue) ignores usage — an item inserted long ago but *used constantly* would still get thrown out. LRU tracks **last use**, not insertion time, so it protects the items you actually keep touching.

#### Q: What does "O(1) get/put" mean and why does it matter?

**O(1) = constant time** — the operation takes the same amount of time whether the cache holds 10 items or 10 million. It does **not** loop over the entries. A cache sits on the hot path (called constantly), so if `get`/`put` slowed down as the cache grew, the cache would become the bottleneck it was meant to remove. The whole design challenge below is: *how do we do all of `get`, `put`, and eviction without ever scanning the list?*

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

### Why *two* data structures glued together?

Two things happen constantly, and **no single structure does both fast**:

1. **"Do I already have this key, and where is it?"** — you need to find any entry instantly by its key. That's a **HashMap** (`key → entry`). A HashMap finds anything in O(1), but it has **no sense of order** — it can't tell you which entry you touched longest ago.
2. **"Which entry have I not touched the longest, and can I move an entry to the front instantly?"** — you need to keep entries in **recency order** and reorder cheaply. That's the **Doubly Linked List**: front = most-recently-used (MRU), back = least-recently-used (LRU).

So we **combine** them: the HashMap answers *"where is this key?"*, and it doesn't store a copy of the value off to the side — it stores a **pointer to the exact node inside the linked list**. That pointer is what makes it work: once you have the node, you can unhook it and move it to the front in O(1), no searching required.

```
Most-recently-used ↔ ... ↔ Least-recently-used
   [head] ⇄ [B] ⇄ [A] ⇄ [tail]
              ▲       ▲
HashMap ──────┘       │      "B" → nodeB,  "A" → nodeA
        ──────────────┘      (each key points straight AT its node)
```

#### Q: Why does the HashMap point *at a list node* instead of just storing the value?

If the map only stored the value, you could look up the value fast — but you'd have **no idea where that item sits in the recency order**, so to move it to the front you'd have to **scan the list to find it (O(n))**. By storing a pointer to the node itself, "find it" and "reorder it" both become O(1). The map and the list describe the *same* entries from two angles: the map for lookup, the list for order.

#### Q: Why a *doubly* linked list — why not singly, or an array?

Because on every access you must **splice a node out of the middle** and move it to the front, in O(1):

| Structure | Move an arbitrary node to front | Why |
| --- | --- | --- |
| **Array / ArrayList** | O(n) | Removing from the middle shifts everything after it. |
| **Singly linked list** | O(n) | To unhook a node you must fix its **predecessor's** `next`, but a singly linked node can't reach its predecessor — you'd scan from the head to find it. |
| **Doubly linked list** | **O(1)** | Each node knows both `prev` and `next`, so you can rewire its neighbors directly: `n.prev.next = n.next; n.next.prev = n.prev`. |

That `prev` pointer is the whole reason we pay the extra memory for a *doubly* linked list.

#### Q: Which end is which — where's the "recent" one?

By convention here: **head = MRU** (just used), **tail = LRU** (stalest). Every `get`/`put` moves the touched node to the **head**; eviction always removes the node just before the **tail**. (You could flip the convention; just be consistent.)

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

### The same code, fully annotated

Here's the identical logic with a comment on every meaningful line:

```java
class LRUCache {
    // one cache entry: its key, its value (val),
    // and links to the neighbours in front (prev) and behind (next).
    class Node { int key, val; Node prev, next; }

    private final int capacity;                                  // how many entries fit in the cache
    private final Map<Integer, Node> map = new HashMap<>();      // key -> the entry's node
    private final Node head, tail;                               // sentinels (fixed boundary nodes)

    LRUCache(int capacity) {
        this.capacity = capacity;
        head = new Node(); tail = new Node();
        head.next = tail; tail.prev = head;   // empty cache: the two sentinels point at each other
    }

    int get(int key) {
        Node n = map.get(key);                // O(1): is this key in the cache?
        if (n == null) return -1;             // not here -> cache miss
        moveToFront(n);                        // just used it -> move to the front (MRU)
        return n.val;
    }

    void put(int key, int val) {
        Node n = map.get(key);
        if (n != null) {                      // key already present:
            n.val = val;                      //   update its value
            moveToFront(n);                   //   and mark it most-recently-used
            return;
        }
        if (map.size() == capacity) {         // cache is FULL -> must evict the LRU
            Node lru = tail.prev;             // the last real node before the tail sentinel
            remove(lru);                      //   unhook it from the list  (O(1))
            map.remove(lru.key);              //   and drop its key from the map
        }
        Node fresh = new Node(); fresh.key = key; fresh.val = val;
        map.put(key, fresh);                  // record where the new node is
        addFront(fresh);                      // place it right at the front (MRU)
    }

    // ---- O(1) doubly-linked-list helpers ----

    // splice a node OUT: connect its two neighbours directly to each other.
    private void remove(Node n) {
        n.prev.next = n.next;
        n.next.prev = n.prev;
    }

    // splice a node IN, right after head (the MRU position).
    private void addFront(Node n) {
        n.next = head.next;      // new node points to the old first node
        n.prev = head;           // and back to head
        head.next.prev = n;      // old first node points back to new node
        head.next = n;           // head now points to new node
    }

    // "I just used this node" = remove it from wherever it is, re-add it at the front.
    private void moveToFront(Node n) {
        remove(n);
        addFront(n);
    }
}
```

#### Q: What are the sentinel `head`/`tail` nodes for?

They are **fake, permanent boundary nodes** that hold no real data. Without them, inserting into an empty list or removing the last remaining node means "the neighbor might be `null`" — so every helper needs `if (n.prev == null) ...` special cases. With sentinels there is **always** a node on both sides of every real node, so `n.prev.next = n.next` never touches `null`. Fewer edge cases, fewer bugs — a classic linked-list trick.

#### Q: Walk me through evicting the tail — which node exactly gets removed?

`tail` itself is a sentinel (fake), so the real least-recently-used entry is **`tail.prev`** — the last real node before the tail sentinel. We `remove(lru)` to unhook it from the list, then `map.remove(lru.key)` so the HashMap forgets it too. **Both** structures must be updated together, or the map would keep pointing at a node that's no longer in the list (a leak / stale reference).

#### Q: Why does `get` also reorder the list? Isn't a read supposed to be read-only?

For an LRU cache, **reading an item counts as "using" it** — that's the entire point. An entry you keep reading should stay near the front and never be evicted. So `get` is *not* side-effect-free: on a hit it moves the node to the head. This is exactly why a plain `HashMap` can't be an LRU cache on its own — it has no notion of "I just touched this."

#### Q: Where does the O(1) actually come from, step by step?

Trace a `get` hit: (1) `map.get(key)` → O(1) hash lookup gives the node directly; (2) `remove(n)` → rewire 2 neighbor pointers, O(1); (3) `addFront(n)` → rewire 3–4 pointers, O(1). No loops, no scanning — total O(1). `put` is the same plus, when full, grabbing `tail.prev` (O(1)) to evict. **Nothing in the hot path depends on how many items the cache holds.**

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

### Thread-safety

The code above assumes a **single thread**. In a real server, **many threads** call `get`/`put` on the same cache at once. Trouble: while thread A is halfway through rewiring the `prev`/`next` pointers, thread B jumps in and reads them mid-rewire → the linked list gets corrupted (a node lost, a cycle, or a crash). This is a **race condition**.

**Simplest fix — one lock around every operation** (only one thread mutating the cache at a time):

```java
class ThreadSafeLRUCache {
    private final LRUCache cache;
    private final ReentrantLock lock = new ReentrantLock();

    ThreadSafeLRUCache(int capacity) { this.cache = new LRUCache(capacity); }

    int get(int key) {
        lock.lock();                 // acquire the lock; others wait
        try { return cache.get(key); }
        finally { lock.unlock(); }   // ALWAYS release, even if something throws
    }

    void put(int key, int val) {
        lock.lock();
        try { cache.put(key, val); }
        finally { lock.unlock(); }
    }
}
```

(You could equally mark the methods `synchronized` — same idea, a single lock.)

- **Trade-off:** correct but a bottleneck — every thread queues for the one lock, even readers. Remember: in an LRU cache even a `get` **writes** (it moves a node), so you can't just let reads run in parallel without care.
- **Doing better — striped locks:** split the keyspace into N independent shards, each its own small LRU + its own lock (`lock = locks[hash(key) % N]`). Threads touching *different* shards never block each other. This is roughly how Guava/Caffeine caches scale, and it's the same "shard to reduce contention" idea as the distributed row in the table.

#### Q: Isn't there already a ready-made LRU in Java? (`LinkedHashMap`)

Yes. `java.util.LinkedHashMap` maintains a linked list *through* its entries. Construct it with **access order** on, override `removeEldestEntry`, and you get an LRU cache in a few lines — no hand-rolled DLL:

```java
// accessOrder = true  -> every get/put moves the entry to the end (MRU)
LinkedHashMap<Integer, Integer> lru =
    new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Integer> eldest) {
            return size() > CAPACITY;   // true -> auto-evict the least-recently-used
        }
    };
```

It's exactly the HashMap-plus-doubly-linked-list design, done for you internally. Two caveats: it's **not thread-safe** (wrap with `Collections.synchronizedMap` or a lock), and in an interview asking for LRU you'll usually be expected to **build it by hand** to prove you understand the mechanics — mention `LinkedHashMap` as the real-world shortcut.

#### Q: LRU vs LFU — what's the difference?

- **LRU (Least Recently Used)** evicts by **how long ago** an item was last touched → the entry untouched the longest is removed.
- **LFU (Least Frequently Used)** evicts by **how often** an item is used → an entry accessed only twice is evicted before one accessed 500 times.

LFU keeps a **usage count** per entry (plus frequency buckets to still hit O(1)), so it's more complex. Neither is universally better: LRU adapts fast to changing patterns but can be fooled by a one-off scan that sweeps hot items out; LFU protects long-term favorites but can cling to items that *used* to be popular. Real caches (e.g. Redis, Caffeine) offer both, and some use hybrids (like "LRU-K" or Caffeine's frequency-aware **TinyLFU**).

#### Q: What's "approximate LRU" and why would you *not* keep a perfect list?

At massive scale, maintaining an exact recency list (a pointer rewire on **every single access**) costs memory and CPU. Redis instead does **sampling**: when it needs to evict, it picks a handful of random keys and evicts the oldest **among that sample** — not the true global LRU, but very close, for a fraction of the bookkeeping. It trades a little accuracy for a lot of speed and memory savings.

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
