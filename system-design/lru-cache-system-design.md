# LRU Cache — Low-Level Design

> **Core challenge:** do `get`, `put`, **and** eviction in **O(1)** — every single call, no matter how large the cache grows. No one data structure gives you both O(1) lookup *and* O(1) "find & evict the least-recently-used", so the whole design is about **gluing two structures together (HashMap + doubly linked list) and keeping them perfectly in sync**.

> A staple **coding + LLD** question: an in-memory cache with a fixed capacity that evicts the **Least Recently Used** entry when full — with **O(1)** `get` and `put`. The trick is the **HashMap + Doubly Linked List** combo.

> **How to read this doc:** each section has the dense interview summary first, then a **deep dive** (annotated Java and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand.

---

## Contents

- [1. Requirements](#1-requirements)
- [2. The Core Idea — HashMap + Doubly Linked List](#2-the-core-idea--hashmap--doubly-linked-list)
- [3. Implementation](#3-implementation)
- [4. Thread Safety & Variants](#4-thread-safety--variants)
- [5. Interview Cheat Sheet](#5-interview-cheat-sheet)
- [6. Design Patterns (that can be used)](#6-design-patterns-that-can-be-used)
- [7. Final Takeaways](#7-final-takeaways)

---

## 1. Requirements

- `get(key)` → value if present (and mark as most-recently-used), else miss.
- `put(key, value)` → insert/update; if at capacity, **evict the least-recently-used** entry.
- Both operations in **O(1)** time.
- Optional: TTL/expiry, thread-safety, size by bytes.

> 💡 **tip:** in an interview, say the two hard requirements out loud first — **O(1) for both ops** and **evict the LRU on overflow**. Everything else (TTL, threads, byte-size limits) is an *extension* you layer on after the core works.

### Non-functional requirements (NFRs)

| NFR | Target / Note |
| --- | --- |
| **Latency** | `get`/`put` are **O(1)**, ~constant regardless of size — it's on the hot path. |
| **Memory** | Bounded by `capacity`; the DLL + map pointers add fixed per-entry overhead (see [§4 memory overhead](#memory-overhead-rough)). |
| **Consistency** | The map and the list must **always agree** — every mutation touches both, atomically under a lock if concurrent. |
| **Durability** | **None.** A cache is never the source of truth; losing it = a cold cache, never lost data. |

### Out of scope (state assumptions)

- Persistence / crash recovery, cross-process replication, and cache **coherence** across nodes (that's a *distributed* cache — see §4). Mention, then defer to keep the core single-process design tight.

### What is an LRU cache, really?

An LRU cache holds a fixed number of entries in a defined recency order. Take a cache with capacity **3**. The most-recently-used entry sits at one end and the least-recently-used at the other. On every access an entry is moved to the most-recently-used end. When a **4th** entry is inserted and the cache is full, the entry that hasn't been touched the longest (the least-recently-used one) is evicted to make space.

The moving parts:

- **Cache** — fixed capacity (here 3).
- **Entry** — a cached `key → value` pair.
- **Marking "recently used"** — moving an entry to the most-recently-used end; happens on every `get` *and* every `put`.
- **Eviction** — removing the **Least Recently Used** entry when the cache is full.

Why a cache at all? Fetching data from the real source (a database, disk, or a slow API) is expensive. A cache keeps a small set of "hot" items in fast memory so repeat requests are instant. But memory is limited, so you can't keep everything — you keep the stuff most likely to be used again. "Recently used → likely to be used again" is the bet LRU makes.

Why evict the *least recently used* item specifically, rather than a random one or the oldest-inserted one? Because of **temporal locality**: something you used a moment ago is very likely to be used again soon (think of the file you're actively editing). Evicting randomly might throw out a hot item. Evicting the **oldest-inserted** (that's a plain FIFO/queue) ignores usage — an item inserted long ago but *used constantly* would still get thrown out. LRU tracks **last use**, not insertion time, so it protects the items you actually keep touching.

And "O(1) get/put" means **constant time** — the operation takes the same amount of time whether the cache holds 10 items or 10 million. It does **not** loop over the entries. A cache sits on the hot path (called constantly), so if `get`/`put` slowed down as the cache grew, the cache would become the bottleneck it was meant to remove. The whole design challenge below is: *how do we do all of `get`, `put`, and eviction without ever scanning the list?*

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

By convention here: **head = MRU** (just used), **tail = LRU** (stalest). Every `get`/`put` moves the touched node to the **head**; eviction always removes the node just before the **tail**. (You could flip the convention; just be consistent.)

---

## 3. Implementation

- **Sentinel head/tail** nodes remove edge-case null checks. 💡 A *sentinel* (aka dummy/guard node) is a fake permanent node holding no data that sits at each boundary, so every real node **always** has a non-null `prev` and `next`.
- `get`, `put`, evict are all **O(1)**.
- In practice: Java's `LinkedHashMap(accessOrder=true)` implements this; Redis uses **approximate LRU** (sampling) to save memory.
- Full annotated implementation below.

### The implementation, annotated

Here's the full class with a comment on every meaningful line:

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

The sentinel `head`/`tail` nodes are **fake, permanent boundary nodes** that hold no real data. Without them, inserting into an empty list or removing the last remaining node means "the neighbor might be `null`" — so every helper needs `if (n.prev == null) ...` special cases. With sentinels there is **always** a node on both sides of every real node, so `n.prev.next = n.next` never touches `null`. Fewer edge cases, fewer bugs — a classic linked-list trick.

Because `tail` itself is a sentinel (fake), the real least-recently-used entry to evict is **`tail.prev`** — the last real node before the tail sentinel. We `remove(lru)` to unhook it from the list, then `map.remove(lru.key)` so the HashMap forgets it too. **Both** structures must be updated together, or the map would keep pointing at a node that's no longer in the list (a leak / stale reference).

#### Q: Why does `get` also reorder the list? Isn't a read supposed to be read-only?

For an LRU cache, **reading an item counts as "using" it** — that's the entire point. An entry you keep reading should stay near the front and never be evicted. So `get` is *not* side-effect-free: on a hit it moves the node to the head. This is exactly why a plain `HashMap` can't be an LRU cache on its own — it has no notion of "I just touched this."

Tracing where the O(1) actually comes from, step by step, for a `get` hit: (1) `map.get(key)` → O(1) hash lookup gives the node directly; (2) `remove(n)` → rewire 2 neighbor pointers, O(1); (3) `addFront(n)` → rewire 3–4 pointers, O(1). No loops, no scanning — total O(1). `put` is the same plus, when full, grabbing `tail.prev` (O(1)) to evict. **Nothing in the hot path depends on how many items the cache holds.**

### Worked example — trace both structures step by step

The single best way to *lock in* the mechanics: run a sequence of ops on a **capacity-3** cache and write out the HashMap **and** the doubly linked list after each one. Ops (values = keys for brevity): `put(1) → put(2) → get(1) → put(3) → put(4)`. List is shown **head (MRU) … tail (LRU)**; sentinels omitted.

```
start           DLL:  (empty)                    map: {}

put(1)          DLL:  1                           map: {1→n1}
                add new node 1 at front.

put(2)          DLL:  2 → 1                        map: {1→n1, 2→n2}
                add new node 2 at front; 1 is now the LRU (tail side).

get(1)  → 1     DLL:  1 → 2                        map: {1→n1, 2→n2}
                HIT: splice node 1 out, move it to front. get MUTATED order.
                2 is now the LRU.

put(3)          DLL:  3 → 1 → 2                    map: {1→n1, 2→n2, 3→n3}
                size was 2 < 3, so no eviction; add 3 at front.

put(4)          DLL:  4 → 3 → 1                    map: {1→n1, 3→n3, 4→n4}
                size was 3 == capacity → EVICT tail.prev = node 2 (the LRU):
                  remove(2) from list  AND  map.remove(2)  ← both structures!
                then add new node 4 at front.
```

> ⚠️ **pitfall:** the eviction victim is **2**, not 1 — even though 1 was inserted *first*. The `get(1)` refreshed 1's recency, so 2 became the least-recently-used. This is exactly what separates LRU from a plain FIFO/insertion-order queue.

Two things to internalize from the trace: (1) `get` is **not read-only** — it reorders the list; (2) on eviction the key is removed from **both** the list and the map in the same step, or you leak a stale map entry pointing at a detached node.

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

**Doing better — striped (sharded) locks:** split the keyspace into N independent shards, each its own small LRU + its own lock. A key always maps to the *same* shard via `hash(key) % N`, so threads touching *different* shards never block each other. This is roughly how Guava/Caffeine caches scale:

```java
class StripedLRUCache {
    private final LRUCache[] shards;      // N independent little caches
    private final ReentrantLock[] locks;  // one lock per shard
    private final int n;

    StripedLRUCache(int capacity, int n) {
        this.n = n;
        shards = new LRUCache[n];
        locks  = new ReentrantLock[n];
        for (int i = 0; i < n; i++) {         // each shard holds capacity/n entries
            shards[i] = new LRUCache(capacity / n);
            locks[i]  = new ReentrantLock();
        }
    }

    private int idx(int key) { return (key ^ (key >>> 16)) % n; }  // spread bits, pick a shard

    int get(int key) {
        int i = idx(key);
        locks[i].lock();                       // lock ONLY this shard
        try { return shards[i].get(key); }
        finally { locks[i].unlock(); }
    }

    void put(int key, int val) {
        int i = idx(key);
        locks[i].lock();
        try { shards[i].put(key, val); }
        finally { locks[i].unlock(); }
    }
}
```

> ⚠️ **pitfall:** striping makes eviction **per-shard**, not global. A hot key can be evicted from its shard while a colder key survives in another — you get *approximate* global LRU, not exact. Usually a fine trade for the concurrency win.

#### Q: Why not just use a `ConcurrentHashMap` and skip the locking?

Because a `ConcurrentHashMap` only makes the **map** operations thread-safe — it says nothing about the **doubly linked list** that lives alongside it. Every `get`/`put` has to **reorder that list** (splice a node out, move it to the head), and that pointer surgery is a multi-step mutation the map has no idea about. Two threads reordering the DLL at once still corrupt it (lost node, cycle, crash), even if the map itself never tears. So the DLL reorder needs its **own** lock. And note *both* operations mutate: in an LRU cache **`get` is a write** — it changes recency — so you can't treat reads as lock-free. The map's concurrency guarantees simply don't cover the ordering structure that makes it an *LRU*.

### TTL / expiry — a sketch

Add a per-entry expiry so stale data self-evicts even before it becomes the LRU. Two mechanisms, usually combined:

```java
class Node { int key, val; long expiresAt; Node prev, next; }   // absolute epoch-ms deadline

// 1) LAZY expiry — check on read. Cheap: only paid when you touch a key.
int get(int key) {
    Node n = map.get(key);
    if (n == null) return -1;
    if (n.expiresAt <= now()) {        // found, but stale
        remove(n); map.remove(key);    // drop from BOTH structures
        return -1;                     // treat as a miss
    }
    moveToFront(n);
    return n.val;
}
```

- **Lazy expiry** (above): only checked when a key is accessed. Zero background cost, but a key that's *never read again* lingers in memory until it's evicted as LRU — so it still occupies capacity.
- **Active/background sweeper** (optional): a periodic job scans (or *samples*) entries and drops expired ones, reclaiming memory that lazy expiry would leave sitting. Redis pairs both: lazy on access **plus** a background sampler.

> 💡 **tip:** store an **absolute** `expiresAt` (deadline), not a remaining-TTL countdown — you'd otherwise have to update every node as time passes. Compare against `now()` on read.

### Approximate LRU (Redis-style sampling)

At massive scale, a pointer rewire on **every single access** costs memory and CPU. Redis skips the exact recency list entirely: on eviction it **samples K random keys** and evicts the oldest *among the sample* — close to true LRU for a fraction of the bookkeeping.

```
# no doubly linked list at all — each entry just stores its last-access time
on evict_needed():
    victim = null
    for i in 1..K:                      # K is small, e.g. 5 (Redis default)
        candidate = random_key()
        if victim == null or candidate.lastAccess < victim.lastAccess:
            victim = candidate          # keep the least-recently-used seen so far
    delete(victim)                      # evict the sample's oldest — "good enough"
```

Bigger `K` → closer to exact LRU but more work per eviction. It trades a little accuracy for a lot of speed and memory savings — no list to maintain, just a timestamp per entry.

<a id="memory-overhead-rough"></a>
### Memory overhead (rough)

The map + DLL aren't free — each entry carries bookkeeping beyond its key/value. A rough per-node budget on a 64-bit JVM:

```
Per node (object header + fields):
  object header            ~16 bytes
  key + value (2 ints)     ~ 8 bytes   (boxed Integer objects cost far more)
  prev + next references   ~16 bytes
  ---------------------------------
  ~40 bytes / node in the DLL
Plus the HashMap entry (Node + hash + next ref) ~ 32–48 bytes / key.

So: total ≈ (node size + map-entry size) × capacity
   e.g. capacity 1,000,000 × ~80 bytes ≈ ~80 MB just in structure overhead,
   before counting the actual value payloads.
```

> ⚠️ **pitfall:** the `prev`/`next` pointers and per-key map entries can **dwarf** tiny values. For millions of small entries this overhead is exactly why production systems reach for **approximate LRU** (drop the DLL) or size the cache **by bytes**, not entry count.

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
- **LFU (Least Frequently Used)** 💡 evicts by **how often** an item is used → an entry accessed only twice is evicted before one accessed 500 times.

LFU keeps a **usage count** per entry (plus frequency buckets to still hit O(1)), so it's more complex. Neither is universally better: LRU adapts fast to changing patterns but can be fooled by a one-off scan that sweeps hot items out; LFU protects long-term favorites but can cling to items that *used* to be popular. Real caches (e.g. Redis, Caffeine) offer both, and some use hybrids (like "LRU-K" or Caffeine's frequency-aware **TinyLFU** 💡 — a compact, sketch-based frequency filter that admits a new entry only if it's likely *more* useful than the one it would evict, getting LFU's smarts at near-LRU memory cost).

At massive scale, maintaining an exact recency list (a pointer rewire on **every single access**) costs memory and CPU, which is why production systems often settle for "approximate LRU" instead of a perfect list. Redis does **sampling**: when it needs to evict, it picks a handful of random keys and evicts the oldest **among that sample** — not the true global LRU, but very close, for a fraction of the bookkeeping. It trades a little accuracy for a lot of speed and memory savings.

### Database & storage choices

This structure **is** the in-memory layer — that's the whole design, not a persistence afterthought. An LRU cache is never the source of truth, so it needs no durability, no transactions, no schema: losing it (restart, eviction) just means a **cold cache**, never lost data. It sits in front of a slower backing store in the **cache-aside** pattern — on a miss, read the DB and `put()` the result; on a write, update the DB and invalidate/refresh the cache entry.

#### Q: What actually happens on a `get` that misses — where does the value come from?

The cache doesn't fetch anything itself — the **caller** does, in the classic **cache-aside** (a.k.a. lazy-loading) read path:

```
value = cache.get(key)
if value == MISS:
    value = db.read(key)      # 1) go to the slow source of truth
    cache.put(key, value)     # 2) populate the cache so next time is a HIT
return value                  # 3) serve the caller
```

So the *first* read of a key is a miss → DB hit → then `put()` warms the cache; every subsequent read is an O(1) hit until the entry is evicted or expires. This is why a cold (just-started) cache is slow at first and speeds up as it "warms." A **read-through** cache is the same flow with step 1–2 hidden *inside* the cache library, so the caller only ever calls `get`. Either way, on a write you must **update the DB and invalidate/refresh the cache** so it never serves stale data.

At scale, the single process's HashMap+DLL becomes a **distributed cache** (Redis/Memcached) shared across app servers — same recency-eviction idea, just sharded by key across nodes (see the "Distributed" row above) instead of living in one process's heap. (For how this cache layer relates to the durable stores behind it, see [Databases — Deep Dive](../concepts/databases-deep-dive.md).)

---

## 5. Interview Cheat Sheet

> **"Design an LRU cache with O(1) get/put."**
> "**HashMap + doubly linked list.** The map gives O(1) key lookup to a node; the DLL maintains recency order — most-recently-used at the head, least at the tail. `get`/`put` move the node to the head; when full, evict the tail node. Doubly linked so any node splices out in O(1); sentinel head/tail avoid edge cases."

> **"Why a doubly linked list, not an array or singly linked?"**
> "You must remove/move an arbitrary node in O(1) given its pointer — a singly linked list can't fix the predecessor's `next` in O(1), and an array shift is O(n)."

> **"How would you scale it / make it approximate?"**
> "At large scale, maintaining exact LRU order is costly — sample K entries and evict the oldest (Redis-style approximate LRU). Distributed = shard keys via consistent hashing across many cache nodes. Add TTL via per-entry expiry + lazy/background eviction."

> **"Thread safety?"**
> "Guard with a lock (simplest) or a concurrent/striped-lock design; `LinkedHashMap(accessOrder=true)` gives a quick single-threaded implementation."

### Tricky scenarios (rapid-fire)

| Scenario | What happens / what to do |
| --- | --- |
| **A thread evicts while another `get`s the same node** | Both mutate the DLL → must hold the same lock. `get` is a **write** in LRU; don't let it run lock-free alongside eviction. |
| **`capacity == 0`** | Every `put` should be a no-op (or immediately evict what it just added). Guard the constructor/`put` so you never index into an empty list. |
| **`put` an existing key while at capacity** | It's an **update, not an insert** → change the value + move to front, **no eviction** (size didn't grow). A common off-by-one bug is evicting here anyway. |
| **`get` a missing key** | Return miss sentinel (e.g. `-1`); do **not** touch the list or create a node. |
| **`put` the same key twice in a row** | Second call finds the node, updates value, re-moves to front — size unchanged. |
| **Value is `null` / huge object** | Decide policy up front: reject nulls, or size **by bytes** and evict until under the byte limit, not the count limit. |

---

## 6. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Strategy** | Eviction policy (LRU/LFU/FIFO/approx) behind an interface | Swap policies without changing the cache API |
| **Decorator** | Add TTL, metrics, thread-safety as wrappers around a base cache | Compose features |
| **Facade / Adapter** | Uniform `Cache` interface over the map+DLL internals | Clean API |
| **Observer** | Notify on eviction (write-back, metrics) | Decouple side-effects |
| **Template Method** | Common get/put skeleton, policy-specific eviction step | Reuse flow |
| **Proxy** | Caching proxy in front of a data source (cache-aside) | Transparent caching |

---

## 7. Final Takeaways

- **HashMap (O(1) lookup) + Doubly Linked List (O(1) recency move/evict)** = O(1) `get`/`put`.
- MRU at head, LRU at tail; evict the tail; sentinels simplify edges.
- **Approximate LRU (sampling)** at scale (Redis); **shard + consistent hashing** for distributed.
- Eviction policy is a **Strategy**; TTL/metrics/locking via **Decorator**.

### Related notes

- [Caching Strategies](../concepts/caching-strategies.md) — eviction policies, invalidation
- [Distributed Cache](distributed-cache-system-design.md) — scaling this across nodes
