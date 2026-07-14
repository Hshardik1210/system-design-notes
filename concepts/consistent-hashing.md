# Consistent Hashing

**What it is:** a way to map keys → nodes so that **adding/removing a node moves only ~1/N of keys** (not almost all). **Why it matters:** it makes caches and sharded databases *elastic* — you can scale up/down without a mass reshuffle.

> **Problem it solves:** with plain `hash(key) % N`, adding/removing a node remaps **almost all keys** → cache misses / massive data movement. Consistent hashing remaps only **~1/N of keys**.

> **How to read this doc:** each section has the dense summary first, then a **deep dive** (annotated pseudo-Java, and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand.

---

## Contents

- [1. The Problem with `hash(key) % N`](#1-the-problem-with-hashkey--n)
- [2. The Core Idea — The Hash Ring](#2-the-core-idea--the-hash-ring)
- [3. Virtual Nodes (vnodes) — fixing imbalance](#3-virtual-nodes-vnodes--fixing-imbalance)
- [4. Walkthrough — Adding a Node](#4-walkthrough--adding-a-node)
- [Worked Example — 8 keys, add & remove a node](#worked-example--8-keys-add--remove-a-node)
- [5. Where It's Used](#5-where-its-used)
- [When NOT to use](#when-not-to-use)
- [6. Trade-offs](#6-trade-offs)
- [Common Mistakes](#common-mistakes)
- [7. Interview Cheat Sheet](#7-interview-cheat-sheet)
- [8. Final Takeaways](#8-final-takeaways)

---

## 1. The Problem with `hash(key) % N`

Suppose 4 cache servers, `server = hash(key) % 4`.

```
Add a 5th server → now hash(key) % 5
→ almost every key maps to a different server
→ cache cluster effectively wiped → DB meltdown
```

> **Modulo sharding is brittle:** changing `N` reshuffles nearly all keys. Bad for elastic clusters and caches.

### Why `hash % N` breaks

With 4 servers, the rule for where each key goes is `server = hash(key) % 4`. A key with hash 10 → server 2, hash 11 → server 3, and so on. Simple and fast.

Now a 5th server is added and the rule becomes `% 5`. The key with hash 10 that used to map to server 2 now maps to server 0; hash 11 moves from server 3 to server 1. **Almost every key now maps to a different server**, because you changed the divisor, which changes the result for nearly every key.

For a cache, a key mapping to a different server means the data isn't where you look for it → a **cache miss** → the request falls through to the database. Do that for *every* key at once and the database is hit by millions of misses the instant you add one server.

```
4 servers:  server = hash(key) % 4
  hash(key)=100 → 100 % 4 = 0   (server 0)
  hash(key)=101 → 101 % 4 = 1   (server 1)

add 1 server → hash(key) % 5
  hash(key)=100 → 100 % 5 = 0   (still 0 — lucky)
  hash(key)=101 → 101 % 5 = 1   (still 1 — lucky)
  hash(key)=102 → 102 % 4 = 2  BUT  102 % 5 = 2 ... and most keys DO change
```

The point isn't that *no* key stays put — it's that on average only ~1/N keys keep their old home, so ~80% move when going 4→5. That's the disaster.

### The rebalancing math, side by side

How many keys move when the cluster grows by one node? With modulo, changing the divisor changes the result for almost everyone; with consistent hashing, only the arc "in front of" the newcomer changes hands (~1/new_N of keys).

| Change | `hash % N` (keys remapped) | Consistent hashing (keys moved) |
| --- | --- | --- |
| 4 → 5 nodes | ~80% (only ~1/5 keep their slot) | ~20% (≈ 1/5) |
| 5 → 6 nodes | ~83% | ~17% (≈ 1/6) |
| 10 → 11 nodes | ~90% | ~9% (≈ 1/11) |
| N → N+1 nodes | ~(1 − 1/N) → approaches 100% | ~1/(N+1) |

> 💡 **The takeaway in one line:** modulo moves *almost everything* on every resize; consistent hashing moves *one node's share*. The bigger the cluster, the starker the gap.

#### Q: Why not just pick a bigger N up front so I never resize?

Because load changes over time — you add servers when traffic grows and remove them when it shrinks (or when one dies). Every one of those events reshuffles nearly everything with modulo. You want a scheme where **adding/removing one server disturbs roughly one server's worth of keys**, not all of them. That's exactly what the hash ring gives you (next section).

#### Q: Is the problem the hashing, or the `% N`?

It's the **`% N`**. Hashing keys to numbers is fine and stays. The brittleness comes entirely from dividing by the *count of servers* — because that count is what changes. Consistent hashing keeps hashing but **removes `N` from the formula**, so the mapping doesn't depend on how many servers currently exist.

---

## 2. The Core Idea — The Hash Ring

Map **both servers and keys** onto the same circular hash space `[0, 2³²)`.

```
        0 / 2³²
          ●  S1
     K3 ◌     ◌ K1
   S3 ●         ● S2
        ◌ K2
```

- A **key** is placed at `hash(key)` on the ring.
- It belongs to the **first server clockwise** from that point.

```
lookup(key):
    h = hash(key)
    return first server clockwise from h on the ring
```

### Why this helps

- **Add a server** → it only steals keys from the **next server clockwise**. Everyone else is untouched.
- **Remove a server** → its keys go to the **next server clockwise**. Others untouched.

> Only **~K/N keys** move when a node joins/leaves (vs ~all with modulo).

### The ring is a clock face of servers

Picture a clock face. Instead of numbers 1–12, the positions run from 0 up to a huge number (2³², i.e. ~4.3 billion) and then wrap back to 0 — so it's a **circle, not a line**. Both **servers** and **keys** get a position on this clock:

- To place a **server**, hash its name → you get a number → that's where it stands on the clock.
- To place a **key**, hash the key → you get a number → that's where the key sits.

The ownership rule is one sentence: **a key belongs to the first server you hit walking clockwise** from the key's spot.

```
        12 o'clock (0 / 2³²)
              ● S1
        K3            K1
     S3 ●                ● S2
              K2

K1 walks clockwise → hits S2 first → S2 owns K1
K2 walks clockwise → hits S3 first → S3 owns K2
K3 walks clockwise → hits S1 first → S1 owns K3
```

Why is this better? **Because the ring positions don't depend on how many servers there are.** Adding a server just drops one new person onto the clock; it doesn't renumber everyone else. Only the keys sitting *just behind* the newcomer (counter-clockwise, up to the previous server) change owners. Everybody else keeps walking clockwise to the exact same server as before.

#### Annotated code — the ring and a lookup

The ring is just a **sorted map of `position → server`**. "First server clockwise" = "smallest position ≥ the key's position", which a sorted map does for you.

```java
class HashRing {
    // the clock face: position on ring -> server sitting there.
    // TreeMap keeps entries sorted by position, so we can ask "next one clockwise".
    private final TreeMap<Long, String> ring = new TreeMap<>();

    void addServer(String server) {
        ring.put(hash(server), server);   // stand the server at hash(name) on the clock
    }

    String lookup(String key) {
        long h = hash(key);                       // where the key sits on the clock

        // ceilingEntry = smallest position >= h = the FIRST server clockwise.
        var entry = ring.ceilingEntry(h);

        // if there's nothing clockwise before the top (12 o'clock), WRAP AROUND
        // to the very first server — because it's a circle, not a line.
        if (entry == null) entry = ring.firstEntry();

        return entry.getValue();
    }
}
```

- `ceilingEntry(h)` is the whole trick: it finds the next server clockwise in one step.
- The `null` case is the **wrap-around**: a key sitting past the last server on the clock loops back to the first one — that's what makes it a ring.

#### Q: Why does this only move ~1/N of keys instead of all of them?

Because a server's position on the clock is fixed by `hash(its name)` — it **doesn't move when other servers come or go**. Adding a server only changes ownership for the small arc of keys between it and the previous server; every other key still walks clockwise to the same place. With `% N`, the *formula itself* changed for everyone; here, only one neighborhood of the clock is affected.

#### Q: What exactly is the "hash space [0, 2³²)"?

It's just the range of numbers your hash function can output — here, 0 up to about 4.3 billion. You imagine those numbers bent into a circle so that the largest wraps back to 0. Nothing physical; it's a mental coordinate system so "clockwise" and "wrap around" have meaning.

---

## 3. Virtual Nodes (vnodes) — fixing imbalance

> **Problem:** with few servers, keys distribute unevenly (one server gets a big arc). Removing a node dumps **all** its load on a single neighbor.

**Fix:** each physical server is placed at **many points** on the ring (e.g. 100–200 virtual nodes).

```
S1 → S1#1, S1#2, ... S1#150   (scattered around the ring)
```

Benefits:
- **Smoother distribution** (law of large numbers).
- On removal, a server's load spreads across **many** neighbors, not one.
- Lets you weight heterogeneous servers (a bigger box gets more vnodes).

> ⚠️ **Pitfall — vnodes do NOT fix a single hot key.** Virtual nodes only smooth out *uneven arcs* (some server owning too much of the ring by luck). A single **hot key** — one product everyone hammers on Black Friday — still hashes to **one** point and lands on **one** server no matter how many vnodes you add. For that you need **bounded loads** (§5), or caching/replicating that specific key.

### Why one server needs many spots on the clock

**The problem, concretely.** With only 3 or 4 servers placed *once* each, the clock is lumpy. Random hashing rarely spaces them evenly — one server might end up owning a huge arc (say half the clock) just by luck, while another owns a sliver. So one machine gets swamped and another idles. Worse, if the machine owning the big arc dies, **its entire load dumps onto the single next server clockwise**, which may then also fall over (a cascade).

**The fix — virtual nodes (vnodes).** Instead of placing each physical server at one spot, place it at **many** spots (typically ~100–200), by hashing `serverName#1`, `serverName#2`, ... Each spot is a "virtual node" but they all point back to the same real machine.

Placing each physical server at 150 spots scatters its positions across the clock. If that server leaves, its 150 spots vanish from 150 *different* places around the clock, so the keys they owned are redistributed to **150 different neighbors** — a little to each — instead of all landing on one server.

```
Physical S1 → S1#1, S1#2, ... S1#150   (150 points scattered around the ring)
Physical S2 → S2#1, S2#2, ... S2#150
Physical S3 → S3#1, S3#2, ... S3#150

Clock now looks like:  S2#7  S1#42  S3#3  S1#99  S2#1  S3#88  S1#12 ...
                       (interleaved evenly → smooth load, no giant arcs)
```

#### Annotated code — adding vnodes

```java
class HashRing {
    private final TreeMap<Long, String> ring = new TreeMap<>();
    private static final int VNODES = 150;   // spots per physical server

    void addServer(String server) {
        for (int i = 0; i < VNODES; i++) {
            // hash a DIFFERENT string per vnode so the 150 spots scatter
            // to 150 different positions on the clock...
            long position = hash(server + "#" + i);
            // ...but every spot maps back to the SAME real server.
            ring.put(position, server);
        }
    }

    // lookup() is UNCHANGED — it still returns the first entry clockwise.
    // The caller just gets a physical server name (e.g. "S1"), not the vnode label.
    String lookup(String key) {
        var entry = ring.ceilingEntry(hash(key));
        if (entry == null) entry = ring.firstEntry();
        return entry.getValue();   // the physical server behind the vnode
    }
}
```

- The only change is the loop: 150 positions per server instead of 1.
- Lookup doesn't change at all — it still just walks clockwise; it simply lands on a vnode that names its physical owner.

#### Q: Do virtual nodes store any real data? Are they extra machines?

No. A vnode is **just an extra entry in the sorted map** — a position label pointing at a real server. There are no extra machines and no data is duplicated. It only makes the *ownership map* finer-grained so load spreads evenly.

#### Q: How does this help with hot spots / skew?

Two different "unevenness" problems:

- **Uneven arcs** (some server owns too much of the clock by luck) → vnodes fix this directly: 150 random spots average out, so each server owns ~1/N of the clock (law of large numbers).
- **A single hot key** (one key everybody wants) → vnodes **don't** fix this, because a hot key still hashes to one spot → one server. For that you need **bounded loads** (below) or caching/replicating that specific key.

#### Q: How many vnodes should I use?

More vnodes → smoother balance but more memory and slightly slower ring operations (the map is bigger). Too few → lumpy. **~100–200 per physical server** is the usual sweet spot. It also gives a natural way to **weight** servers: give a beefier machine 300 vnodes and a small one 100, and the big one naturally owns ~3× the keys.

---

## 4. Walkthrough — Adding a Node

```
Before:  ... S1 ──[keys here go to S2]── S2 ...
Add S4 between S1 and S2:
After:   ... S1 ──[some keys now go to S4]── S4 ──[rest go to S2]── S2 ...
```

👉 Only the keys in the arc **between S1 and S4** move (from S2 → S4). All other keys stay put.

### Who gains and who loses keys

Each key belongs to the *next* server clockwise. A new server (S4) is added between S1 and S2. Only the keys that were between S1 and S2 — which used to belong to S2 — now belong to S4, because S4 is now the first server clockwise for them. Keys owned by S1, S3, or anything already past S2 are completely unaffected. S2 loses exactly the arc now covered by S4; nothing else changes.

```
Before:   S1 ─────────── keys in this whole arc go to S2 ───────────► S2
Add S4:   S1 ──[arc A]──► S4 ──[arc B]──► S2
              (arc A now belongs to S4; arc B still belongs to S2)
```

- **Only S2 gives up keys** (the ones in arc A). S1 and S3 are untouched.
- With **vnodes**, S4's 150 spots land all over the clock, so S4 actually steals small arcs from *many* servers — meaning no single server takes the whole hit of feeding the newcomer.

#### Q: What happens when a node is REMOVED (or dies)?

The mirror image: when S4 leaves, its keys flow to **the next server clockwise** (S2 gets arc A back). Only S4's former keys move; everyone else is untouched. With vnodes, S4's many arcs were scattered, so its load spreads across many neighbors instead of crushing one.

```
Remove S4:  S1 ──[arc A now goes to S2 again]──► S2   (S3 untouched)
```

#### Q: "Move keys" — does data physically transfer, or just the routing?

Both, depending on what's on the ring:

- **For a cache** (e.g. Memcached): usually nothing is copied. The new server simply starts **cold** for its arc — the first request for each of those keys is a miss and gets refilled from the DB. Only ~1/N of keys go cold, not all of them, so the DB blip is small and brief.
- **For a database** (Cassandra/Dynamo): the arc's data is **actually streamed** to the new node so it can serve reads. Consistent hashing minimizes *how much* data has to move (~1/N), which is the whole point for a stateful store.

#### Q: How does a key "know" it moved without recomputing everything?

It doesn't need to. Every lookup is stateless: hash the key, walk clockwise, done. After S4 joins, a key in arc A hashes to the same position as always, but now the first server clockwise *is* S4. Nothing about the key changed — only the set of servers on the clock did, and only for that one arc.

---

## Worked Example — 8 keys, add & remove a node

Let's make "only the arc changes owner" concrete with real numbers. Use a tiny ring `[0, 100)` (instead of `[0, 2³²)`) so positions are easy to eyeball. Four nodes and eight keys land at these positions:

```
Nodes:  A=10   B=35   C=60   D=85
Keys:   K1=5   K2=20  K3=33  K4=47  K5=55  K6=70  K7=88  K8=95
```

Each key belongs to the **first node clockwise** (wrap past 100 → back to A):

| Key | pos | first node clockwise | owner |
| --- | --- | --- | --- |
| K1 | 5 | A(10) | **A** |
| K2 | 20 | B(35) | **B** |
| K3 | 33 | B(35) | **B** |
| K4 | 47 | C(60) | **C** |
| K5 | 55 | C(60) | **C** |
| K6 | 70 | D(85) | **D** |
| K7 | 88 | wrap → A(10) | **A** |
| K8 | 95 | wrap → A(10) | **A** |

Owners now: `A={K1,K7,K8}  B={K2,K3}  C={K4,K5}  D={K6}`.

### Add a 5th node `E` at position 50

`E` lands between `B(35)` and `C(60)`, so it takes over the arc `(35, 50]` — the keys that used to walk past 50 to reach `C`.

```
before:  B(35) ─────────── keys 36..60 go to C ───────────► C(60)
add E:   B(35) ──(36..50)──► E(50) ──(51..60)──► C(60)
```

- **K4 (pos 47)** was `C`, now the first node clockwise is `E(50)` → **K4 moves C → E**.
- **K5 (pos 55)** is still `> 50`, so it keeps walking to `C(60)` → **unchanged**.
- Everything else is untouched.

**Result: exactly 1 of 8 keys moved** (K4). That's ~12%, in line with "≈ 1/newN = 1/5". With `hash % N` (4 → 5), ~6–7 of the 8 keys would have moved.

### Remove node `C` (back on the original 4-node ring)

Removing a node is the mirror image: its keys flow to the **next node clockwise**. `C(60)` owned `{K4, K5}`; the next node clockwise is `D(85)`.

```
before:  B(35) ──► C(60) ──► D(85)
remove C: B(35) ─────────────► D(85)   (C's arc (35,60] now belongs to D)
```

- **K4 (47)** and **K5 (55)** move **C → D**. Nothing else changes.
- Note the downside: **all** of `C`'s load landed on the single neighbor `D`. That cascade risk is exactly what **virtual nodes** (§3) prevent — `C`'s many scattered vnodes would have dumped its keys across *many* neighbors instead of one.

---

## 5. Where It's Used

- **Distributed caches** — Memcached clients, Redis Cluster (uses 16384 hash slots, a related idea).
- **Databases** — Cassandra, DynamoDB, Riak (partitioning).
- **Load balancers** — sticky routing (e.g. route a user consistently to the same node).
- **CDNs** — mapping content to edge nodes.

> Redis Cluster uses **hash slots** (fixed 16384 slots mapped to nodes) — same goal (minimal movement on resize), slightly different mechanism.

### Replication placement (which N nodes hold a key)

With a replication factor **RF**, a key lives on the node it hashes to **plus the next RF−1 distinct physical nodes clockwise**.

```
key → first node clockwise = primary; next 2 distinct nodes clockwise = replicas (RF=3)
"distinct physical" → skip extra vnodes of the same physical node so replicas aren't co-located
```

- This is exactly how **Cassandra/Dynamo** place replicas on the ring → data survives node loss, and reads/writes use a quorum of those RF nodes.

### Who owns the ring? (membership)

Every client that does a lookup must agree on **the same ring** — which nodes exist and where they sit. Two common ways to distribute that membership:

- **Centralized config service** (ZooKeeper / etcd / a coordinator): one authoritative copy of the node list; clients watch it for changes. Simple and consistent, but the config service is a dependency and a potential bottleneck. Redis Cluster's slot map and many cache-client libraries lean this way.
- **Gossip, Dynamo-style** (Cassandra, DynamoDB, Riak): there is no central owner — each node periodically gossips membership with peers, so the ring converges across the cluster on its own. No single point of failure, but membership is *eventually* consistent, so two nodes can briefly disagree during a change.

> 💡 Whatever the mechanism, the ring itself is tiny (just `position → node`), so it's cheap to replicate to every client or node.

### Bounded loads (hot keys / skew)

Plain consistent hashing can still overload one node if a **key is hot** or arcs are uneven. **Consistent hashing with bounded loads** caps each node at `(1+ε)·average`; if the target node is "full", the key spills to the next node clockwise → no node exceeds the cap.

### Alternatives

| Technique | Idea | When |
| --- | --- | --- |
| **Rendezvous (HRW) hashing** | For each key, compute `hash(key, node)` for all nodes → pick the max → the winner owns the key | Simple, no ring; great for smaller node sets / weighted |
| **Jump consistent hash** | O(1), no memory, maps key → bucket in `[0, N)` with minimal movement | Fixed-ish bucket count (can't remove an arbitrary node) |
| **Hash slots (Redis)** | Fixed 16384 slots → nodes | Explicit slot ownership + easy resharding |

> **Interview add-on:** "consistent hashing places replicas on the next RF distinct nodes clockwise (Dynamo/Cassandra); bounded-load variants cap per-node load to handle skew; rendezvous/jump hash are simpler alternatives."

### Replicas, hot spots, and the alternatives

#### Replication — "keep 3 copies" using the same clock

So far each key had one owner. Real databases keep **RF copies** (RF = replication factor, often 3) so data survives a machine dying. The trick reuses the ring: the primary is the first server clockwise, and the copies are simply **the next distinct physical servers clockwise** after it.

So a key's data lives on the first server clockwise (primary) plus the next two distinct servers clockwise (replicas). If the primary fails, the data still exists on the next two.

```java
List<String> replicasFor(String key, int rf) {
    long h = hash(key);
    List<String> result = new ArrayList<>();

    // walk clockwise from the key, collecting DISTINCT physical servers
    for (var entry : ring.tailMap(h, true).entrySet()) {
        String server = entry.getValue();
        if (!result.contains(server)) result.add(server);  // skip repeat vnodes
        if (result.size() == rf) return result;            // got RF copies
    }
    // (wrap around to the start of the ring if we hit the top — omitted for brevity)
    return result;
}
```

- **"distinct physical"** is the important bit: because of vnodes, the next few clockwise spots might all belong to the *same* machine. You skip those, or all 3 "copies" would sit on one box and die together.

#### Bounded loads — the fix for a hot key

Vnodes even out *arcs*, but a **single hot key** (one product everyone hammers on Black Friday) still lands on one server and can overwhelm it. **Consistent hashing with bounded loads** caps each server at `(1+ε)·average` load; if a key's target server is already "full", the key **spills to the next server clockwise** that has room.

#### The alternatives, in one breath

- **Rendezvous (HRW) hashing:** for a key, compute `hash(key, server)` for *every* server and pick the highest score — that server owns it. No ring to maintain; great for smaller/weighted clusters.
- **Jump consistent hash:** a tiny formula that maps a key to a bucket in `[0, N)` with minimal movement and zero memory — but you can only grow/shrink at the end, not remove an arbitrary node.
- **Hash slots (Redis Cluster):** a fixed 16384 slots are handed out to nodes; a key maps to a slot, a slot maps to a node. Same "minimal movement on resize" goal, but ownership is explicit and easy to reshard.

#### Sketch — rendezvous (HRW) hashing

No ring, no vnodes: score the key against every node and pick the winner. Adding/removing a node only changes the keys whose winner *was* (or *becomes*) that node → still ~1/N movement.

```java
String pick(String key, List<String> nodes) {
    String best = null;
    long bestScore = Long.MIN_VALUE;
    for (String node : nodes) {
        long score = hash(key + ":" + node);   // combined hash of key AND node
        if (score > bestScore) { bestScore = score; best = node; }
    }
    return best;   // highest score wins
}
```

#### Sketch — jump consistent hash

A tiny O(1), zero-memory formula mapping `key → bucket in [0, numBuckets)` with minimal movement — but buckets can only be added/removed at the *end*, so you can't drop an arbitrary node.

```java
int jumpHash(long key, int numBuckets) {
    long b = -1, j = 0;
    while (j < numBuckets) {
        b = j;
        key = key * 2862933555777941757L + 1;   // LCG step
        j = (long) ((b + 1) * (double)(1L << 31) / ((key >>> 33) + 1));
    }
    return (int) b;
}
```

---

## When NOT to use

Consistent hashing is not free (a ring to maintain, vnodes to tune). Reach for something simpler when:

- **Small, fixed node count.** If `N` almost never changes (e.g. a stable 3-node cluster), plain `hash % N` is simpler, O(1), and zero-memory — the reshuffle cost you're avoiding rarely happens.
- **You need to remove an *arbitrary* node cheaply.** Jump hash and hash-slot schemes are great at *growing*, but jump hash can't drop a node from the middle. If arbitrary removal matters, use ring-based consistent hashing (or plan slot migration).
- **You need range scans / ordering.** Hashing destroys key order, so "give me all keys between X and Y" becomes a full scatter-gather. If ordered access is central, use **range partitioning** instead.
- **Tiny/weighted set where a ring is overkill.** For a handful of weighted nodes, **rendezvous (HRW) hashing** gives the same ~1/N movement with no ring to maintain.

---

## 6. Trade-offs

| Pros | Cons |
| --- | --- |
| Minimal key movement on resize (~1/N) | More complex than modulo |
| Smooth scaling up/down | Needs vnodes for good balance |
| Supports weighted nodes (vnodes) | Lookup needs the ring structure (sorted map / TreeMap) |

### Implementation sketch

```
ring = sorted map<hashValue → server>   # vnodes included
add(server):    for i in vnodeCount: ring.put(hash(server+"#"+i), server)
lookup(key):    e = ring.ceilingEntry(hash(key)); return e ?? ring.firstEntry()
```

> `ceilingEntry` = first node clockwise; wrap around to `firstEntry` if past the end.

### Reading the implementation sketch

The whole data structure is a **sorted map** (`TreeMap` in Java, `SortedDict`/balanced BST elsewhere) whose keys are positions on the clock and whose values are server names. That's it — the "ring" is not a fancy structure, just a sorted list of `(position, server)` you can binary-search.

```java
// ring = sorted map<position -> server>, with all vnodes already inserted

void add(String server) {
    for (int i = 0; i < vnodeCount; i++)
        ring.put(hash(server + "#" + i), server);   // scatter vnodes onto the clock
}

String lookup(String key) {
    var e = ring.ceilingEntry(hash(key));   // first server clockwise (binary search)
    return (e != null ? e : ring.firstEntry()).getValue();   // wrap around if past the top
}
```

- **`add`** is the only place vnodes appear — one loop.
- **`lookup`** is O(log n) because the map is sorted: `ceilingEntry` binary-searches for "next clockwise."
- The `firstEntry` fallback is the wrap-around that turns the sorted list into a *circle*.

#### Q: Why a sorted map / TreeMap instead of a plain hash map?

Because the core operation is "**find the next position clockwise**", which is a *range* question ("smallest position ≥ h"), not an exact-match lookup. A plain hash map can only answer "is exactly this key present?" A sorted map can jump to the nearest larger key in log time — which is precisely what walking clockwise needs.

#### Q: What's the cost compared to `hash % N`?

`hash % N` is O(1) with zero memory but reshuffles everything on resize. Consistent hashing costs a little memory (the ring: N × vnodes entries) and O(log n) lookups, in exchange for moving only ~1/N of keys on resize. For any elastic or stateful cluster, that trade is overwhelmingly worth it.

---

## Common Mistakes

- **Skipping vnodes.** One point per physical node makes the ring lumpy — some node owns a huge arc, and losing it cascades onto a single neighbor. Always use ~100–200 vnodes.
- **Expecting vnodes to fix a hot key.** They balance *arcs*, not demand for one key. A hot key needs bounded loads, caching, or per-key replication.
- **Forgetting the wrap-around.** A key past the last node must loop back to the first (`ceilingEntry == null` → `firstEntry`). Miss this and top-of-ring keys have no owner.
- **Not skipping same-physical replicas.** When collecting RF copies clockwise, you must skip extra vnodes of the *same* machine — otherwise all "replicas" sit on one box and die together.
- **Clients disagreeing on the ring.** If nodes/clients have different membership views, the same key routes to different nodes. Keep the ring in sync (config service or gossip).
- **Hashing the wrong identity.** Hash a *stable* node id (not its IP/hostname if that changes on restart), or the node re-enters the ring at a new position and needlessly moves keys.

---

## 7. Interview Cheat Sheet

> **"What problem does consistent hashing solve?"**
>
> "With `hash % N`, changing the number of nodes remaps almost all keys, wiping caches. Consistent hashing maps nodes and keys onto a ring so adding/removing a node only moves ~1/N of keys."

> **"How does it work?"**
>
> "Hash nodes and keys onto a circular space; each key belongs to the first node clockwise. Adding a node only steals keys from its clockwise neighbor."

> **"What are virtual nodes?"**
>
> "Each physical node is placed at many points on the ring to even out distribution and spread load when a node leaves; also lets you weight bigger nodes."

> **"Who uses it?"**
>
> "Cassandra, DynamoDB, Memcached clients, Redis Cluster (via hash slots), CDNs, and sticky load balancers."

---

## 8. Final Takeaways

- `hash % N` → **brittle** (resize remaps everything). Consistent hashing → **elastic** (~1/N moves).
- **Ring:** key → first node clockwise.
- **Virtual nodes** are essential for balance + graceful node removal.
- Backbone of **distributed caches, sharded DBs, and sticky routing**.
