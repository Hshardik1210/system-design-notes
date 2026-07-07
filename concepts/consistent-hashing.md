# Consistent Hashing

> **Problem it solves:** with plain `hash(key) % N`, adding/removing a node remaps **almost all keys** → cache misses / massive data movement. Consistent hashing remaps only **~1/N of keys**.

---

## Contents

- [1. The Problem with `hash(key) % N`](#1-the-problem-with-hashkey--n)
- [2. The Core Idea — The Hash Ring](#2-the-core-idea--the-hash-ring)
- [3. Virtual Nodes (vnodes) — fixing imbalance](#3-virtual-nodes-vnodes--fixing-imbalance)
- [4. Walkthrough — Adding a Node](#4-walkthrough--adding-a-node)
- [5. Where It's Used](#5-where-its-used)
- [6. Trade-offs](#6-trade-offs)
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

---

## 4. Walkthrough — Adding a Node

```
Before:  ... S1 ──[keys here go to S2]── S2 ...
Add S4 between S1 and S2:
After:   ... S1 ──[some keys now go to S4]── S4 ──[rest go to S2]── S2 ...
```

👉 Only the keys in the arc **between S1 and S4** move (from S2 → S4). All other keys stay put.

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

### Bounded loads (hot keys / skew)

Plain consistent hashing can still overload one node if a **key is hot** or arcs are uneven. **Consistent hashing with bounded loads** caps each node at `(1+ε)·average`; if the target node is "full", the key spills to the next node clockwise → no node exceeds the cap.

### How many virtual nodes?

- Too few → uneven distribution; too many → more memory + slower ring ops.
- Typical: **~100–200 vnodes per physical node** balances distribution vs overhead.

### Alternatives

| Technique | Idea | When |
| --- | --- | --- |
| **Rendezvous (HRW) hashing** | For each key, compute `hash(key, node)` for all nodes → pick the max → the winner owns the key | Simple, no ring; great for smaller node sets / weighted |
| **Jump consistent hash** | O(1), no memory, maps key → bucket in `[0, N)` with minimal movement | Fixed-ish bucket count (can't remove an arbitrary node) |
| **Hash slots (Redis)** | Fixed 16384 slots → nodes | Explicit slot ownership + easy resharding |

> **Interview add-on:** "consistent hashing places replicas on the next RF distinct nodes clockwise (Dynamo/Cassandra); bounded-load variants cap per-node load to handle skew; rendezvous/jump hash are simpler alternatives."

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
