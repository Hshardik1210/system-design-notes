# Distributed Unique ID Generator — System Design

> **Core challenge:** generate **globally unique** IDs across many machines, **at high throughput**, ideally **roughly time-sortable**, **without a single bottleneck** and without coordination on every request. The canonical answer is **Snowflake**.

> **How to read this doc:** each section has the dense interview summary first, then a **deep dive** (annotated Java, and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand.

---

## Contents

- [1. Why Not Auto-Increment / UUIDv4?](#1-why-not-auto-increment--uuidv4)
- [2. Requirements](#2-requirements)
- [3. Approaches](#3-approaches)
- [4. Snowflake (the standard answer)](#4-snowflake-the-standard-answer)
- [5. Machine-ID Assignment](#5-machine-id-assignment)
- [6. Ticket Server / Range Allocation](#6-ticket-server--range-allocation)
- [7. UUIDv7 / ULID](#7-uuidv7--ulid)
- [8. Deployment: Library vs Service](#8-deployment-library-vs-service)
- [9. Clock Issues & Gotchas](#9-clock-issues--gotchas)
- [10. Interview Cheat Sheet](#10-interview-cheat-sheet)
- [11. Capacity Estimation (back-of-envelope)](#11-capacity-estimation-back-of-envelope)
- [12. API / Deployment](#12-api--deployment)
- [13. Consistency & CAP Tradeoffs](#13-consistency--cap-tradeoffs)
- [14. How to Drive the Interview (framework)](#14-how-to-drive-the-interview-framework)
- [15. Design Patterns (that can be used)](#15-design-patterns-that-can-be-used)
- [16. Final Takeaways](#16-final-takeaways)

---

## 1. Why Not Auto-Increment / UUIDv4?

| Option | Problem |
| --- | --- |
| **DB auto-increment** | Single point of contention; bottleneck; couples you to one DB; blocks sharding |
| **UUIDv4 (random 128-bit)** | Unique + no coordination, **but** 128-bit (large), **not sortable** → poor DB primary key (random inserts hurt B-tree locality, page splits, cache misses) |
| **UUIDv7 / ULID** | Time-ordered UUIDs — good modern no-coordination option (sortable + unique) |

We usually want: **64-bit** (compact), **unique across nodes**, **time-sortable** (good index locality + natural chronological order), **no per-ID coordination**.

### What problem are we even solving?

We need to hand out numbers that are:

- **Unique** — no two requests ever get the same number.
- **Roughly in order** — a number issued later should generally be larger than one issued earlier, so IDs sort by creation time.

With **one** counter this is trivial: it counts 1, 2, 3, 4… That single counter is a **DB auto-increment** column — one place hands out the next number every time.

Now the system gets *insanely* busy: **millions of IDs per second**, so you run **many machines generating IDs at the same time**. The whole design problem is:

> **How do many independent machines mint numbers at the same time, so that no two ever collide — without the machines constantly coordinating on "who's next"?**

Talking to a shared authority on every ID is **coordination**, and it's the thing we're desperate to avoid — it's slow and it becomes the bottleneck. Every scheme below (Snowflake, UUID, ticket server) is a different way to let many machines avoid handing out the same number *without talking on every request*.

### Why not just use DB auto-increment?

That's the **single shared counter.** It genuinely works and gives perfect 1, 2, 3… order. Why it collapses at scale:

- **One machine = one bottleneck.** Every ID request in the whole system must go to that one database row/counter. Millions/sec can't funnel through one place.
- **It blocks sharding.** The whole point of splitting your data across many databases (shards) is that they're independent. But if all of them must ask **one** central counter for the next ID, they're not independent anymore — you've re-coupled them.
- **Single point of failure.** That counter dies → *nobody* in the system can create anything.

> The fix is NOT "make the counter faster." It's "get rid of the need for one shared counter at all," so each machine can mint IDs **locally**.

### Why not just use a plain UUID (UUIDv4)?

A **UUIDv4** is 128 random bits — like every machine picking a **giant random number** (`f47ac10b-58cc-4372-a567-0e02b2c3d479`) instead of asking anyone. Two machines picking the same one is so astronomically unlikely we treat it as impossible. Zero coordination — great! So what's wrong?

```
UUIDv4  =  4a2f...  (128 random bits, NO time info inside)
```

| Problem | Why it hurts |
| --- | --- |
| **Twice as big** | 128 bits vs Snowflake's 64. Every row, every index, every foreign key carries the extra weight — adds up across billions of rows. |
| **Not sortable / not time-ordered** | It's *random*, so ID order tells you nothing about *when* a row was created. You can't say "give me the newest rows" by sorting on the ID. |
| **Terrible as a DB primary key** | This is the killer. Databases store primary keys in a sorted **B-tree**. Sequential-ish keys append neatly to the end (one hot page, cache-friendly). **Random** keys scatter inserts all over the tree → constant **page splits**, poor cache locality, index bloat. Inserts get slow. |

So UUIDv4 solves "no coordination" but *reintroduces* pain at the database. What we really want is the **best of both**: no coordination (like UUID) **and** compact + time-sortable (like auto-increment). That combination is exactly what **Snowflake** (§4) and **UUIDv7/ULID** (§7) deliver.

What does "time-sortable" (or "k-sorted") actually buy you? Three things: **sort by newest with no extra column** — the ID itself encodes creation time, so `ORDER BY id DESC` ≈ "most recent first," great for feeds, cursors, pagination; **fast time-range scans** — "IDs created this hour" live near each other in the index; and **happy B-trees** — new IDs are always a bit bigger than old ones, so they append to the "right edge" of the index instead of scattering. "**k-sorted**" (a.k.a. *roughly* sorted) is the honest caveat: across many machines with slightly different clocks, IDs are *mostly* in time order but can be off by a tiny bit near the same millisecond. That's fine for indexes and feeds — we never promised a strict global sequence.

---

## 2. Requirements

> 💡 **Start here in the interview.** State the shape of the ID up front — *64-bit, unique, roughly time-sorted, minted locally* — because every later decision (Snowflake vs UUID vs ticket server) falls out of these four constraints.

### Functional

- **Generate a unique ID on demand** — one call, one number, never a duplicate.
- **Roughly time-ordered** (k-sorted) — nice for DB keys, cursors, and sorting by creation.
- **Optional: decode an ID** back into its parts (timestamp / machine / sequence) for debugging.

### Non-Functional (NFRs)

| NFR | Target / Note |
| --- | --- |
| **Uniqueness** | **Absolute** — a collision is a correctness bug (two rows, one key). Non-negotiable. |
| **Throughput** | 100k–1M+ IDs/sec fleet-wide; a single Snowflake node does ~4M/sec (4096/ms). |
| **Latency** | Sub-microsecond, **local** generation — no network hop on the per-ID path. |
| **Availability** | Very high — ID minting must never be the thing that takes writes down. |
| **Ordering** | k-sorted (roughly by time) is enough; a strict global sequence is *not* required. |
| **Coordination** | **None per ID.** At most a one-time machine-id claim at startup. |

### Out of scope (state assumptions)

- Strictly monotonic global sequence, cryptographic unguessability, or human-friendly short codes (defer to a ticket server, §6, if asked).

---

## 3. Approaches

| Approach | Unique? | Sortable? | Coordination |
| --- | --- | --- | --- |
| DB auto-increment | ✅ | ✅ | Central bottleneck |
| Multi-DB step/offset (e.g. node k: k, k+N, k+2N) | ✅ | ~ | Config per node |
| UUIDv4 | ✅ | ❌ | None (but large + random) |
| **UUIDv7 / ULID** | ✅ | ✅ | None |
| **Snowflake** ✅ | ✅ | ✅ (time-ordered) | None per-ID (needs a machine id) |
| Ticket server / range allocation | ✅ | ✅ | Rare (per block) |

> 💡 **Interview decision tree (one screen — say this out loud):**
>
> ```
> Need short, human-friendly codes (URL shortener)?
>   └─ yes → Ticket server / range allocation (§6)
>   └─ no  → Need a compact 64-bit key + fine managing machine ids?
>              └─ yes → Snowflake (§4)                 ← default answer
>              └─ no  → Want zero ops, 128-bit is OK?
>                         └─ yes → UUIDv7 / ULID (§7)
>                         └─ no  → (never) auto-increment = bottleneck; UUIDv4 = random, bad key
> ```
>
> **Lead with Snowflake**, then name the two escape hatches: UUIDv7/ULID (drop machine-id ops) and a ticket server (short codes). Auto-increment and UUIDv4 are the "why not" baseline (§1), never the recommendation.

### The four approaches as one code file

Here's every approach as a tiny `nextId()` method, so you can see exactly *where* each one avoids talking to other machines. All four "work"; they just trade off size, sortability, and how often they coordinate.

```java
// 1) DB AUTO-INCREMENT — one shared central counter.
//    Correct + perfectly ordered, but EVERY id goes through one row → bottleneck.
long nextId() {
    return db.execute("INSERT INTO ids VALUES () RETURNING id");  // central round-trip per id
}

// 2) MULTI-DB STEP/OFFSET — several machines, pre-divided lanes.
//    Node k starts at k and jumps by N (the number of nodes), so lanes never overlap.
//    e.g. N=3 → node0: 0,3,6,9…  node1: 1,4,7…  node2: 2,5,8…
long counter = MY_NODE_ID;               // offset = which node I am (0..N-1)
long nextId() {
    long id = counter;
    counter += NUM_NODES;                // step = how many nodes exist
    return id;                            // no coordination, but adding a node means reconfiguring N
}

// 3) UUIDv4 — everyone rolls a giant random number. Zero coordination…
//    …but 128-bit and NOT time-sortable → poor DB key.
UUID nextId() {
    return UUID.randomUUID();             // 128 random bits, no time inside
}

// 4) SNOWFLAKE — everyone stamps: time + who-I-am + a within-ms counter.
//    64-bit, time-sortable, and the machineId is what keeps two machines from colliding.
long nextId() {
    return (timeMs << 22) | (machineId << 12) | sequence;   // see §4 for the bit layout
}
```

The insight tying them together: approaches 2, 3, and 4 all avoid the central counter by **carving up the number space ahead of time** — by *lane* (step/offset), by *sheer randomness* (UUID), or by *time + machine slot* (Snowflake). Snowflake wins for most systems because its carve-up also keeps IDs small and time-ordered.

### Database & storage choices (which store, and why at scale)

This system is unusual for a "database choices" section: the whole design goal is **avoiding** a shared store, so most approaches need **no store at all**. The deciding question isn't "strong consistency or not" — it's *"how much state does this approach actually need, and how often must it be touched?"* Ranking the approaches by that lets you see exactly what each one is trading away.

| Data | Store | Why this one | Why not the alternative |
| --- | --- | --- | --- |
| Machine-id slot (Snowflake) | **ZooKeeper/etcd** ephemeral sequential znode | The *only* coordination Snowflake needs, and it happens **once per machine at startup**, never per ID. Ephemeral nodes auto-free a dead machine's slot, which matters because there are only 1024 of them (§5). | A static config file assigning ids is simpler but can't reclaim slots from crashed/scaled-down instances — a redeploy or autoscale event can silently duplicate a machine id and start colliding. |
| Block/range counter (ticket server) | A single **RDBMS row or Redis `INCRBY`** counter | One atomically-incrementing value is all it takes — a node claims a whole block (e.g. 1000 ids) in one round trip, then hands them out locally with zero further coordination until the block runs out (§6). | Snowflake avoids needing this row entirely; you only reach for a ticket server when you specifically want short, dense, human-friendly ids (URL shortener codes) and are willing to trade away Snowflake's global time-ordering for it. |
| Per-ID generation state (Snowflake, in-process) | **No external store** — just process-local memory (`lastTimestamp`, `sequence`) | Zero coordination per ID is the entire point of Snowflake; reaching for any shared store on the hot per-ID path reintroduces the exact bottleneck the design exists to escape (§1). | N/A — this is the "do nothing" option, and here doing nothing is correct. |
| UUIDv7 / ULID generation | **No store at all** | Uniqueness comes from ~80 random bits plus a timestamp, not from a centrally claimed slot — genuinely zero-coordination, not even a one-time machine-id claim. | N/A — the whole appeal of UUIDv7 is skipping machine-id management; if you need that operational simplicity more than Snowflake's 64-bit compactness, this is the answer. |

**Why "which DB wins" isn't really the question here:** every approach above is ranked by *how rarely it needs to touch shared state*, not by throughput vs. correctness. Snowflake's one-time ZooKeeper claim is sized for **churn**, not volume — 1024 slots is plenty because it only has to cover *currently running* machines, and ephemeral nodes reclaim slots as instances come and go. The ticket server's counter row is sized similarly: it only needs to sustain "coordination once per ~1000 ids," not once per id, so a single RDBMS row or Redis key comfortably keeps up even at huge total ID volume. (See [Databases — Deep Dive](../concepts/databases-deep-dive.md) for why time-sortable keys matter for B-trees, and how a counter row like this scales.)

---

## 4. Snowflake (the standard answer)

A **64-bit** integer = time + machine + sequence, generated **locally** with no per-ID coordination.

```
| 1 bit  | 41 bits            | 10 bits      | 12 bits          |
| sign=0 | timestamp (ms)     | machine id   | sequence          |
          ~69 years            1024 machines   4096 ids/ms/machine
```

| Field | Role | Tunable |
| --- | --- | --- |
| **Timestamp** | Time-sortable + ~69 years from a **custom epoch** | more bits → longer lifespan |
| **Machine id** | Cross-node uniqueness (assigned once) | more bits → more nodes, fewer per-ms ids |
| **Sequence** | Uniqueness within one ms on one machine (4096/ms) | more bits → higher per-ms throughput |

- **Throughput**: 4096 ids/ms/machine × 1024 machines ≈ **~4B ids/sec** theoretical.
- **No coordination** per ID (only the one-time machine-id assignment).
- **k-sorted**: IDs increase with time → great for DB primary keys, time-range scans, and cursors.
- **Bit layout is tunable** to your scale (e.g., more machine bits if you have >1024 nodes, fewer sequence bits).
- Full annotated `nextId()` in the deep dive below.

### A Snowflake ID is one 64-bit number in 3 parts

To let **many** machines mint numbers with **zero coordination**, we give each machine a smarter numbering rule. Instead of a plain counter, each ID is **three pieces glued together into one 64-bit integer**:

```
one 64-bit number, read left → right:

|  0  | 1101011…01 (41 bits)  | 0000000101 (10 bits) |  000000000011 (12 bits) |
| sign|   TIMESTAMP (ms)      |    MACHINE ID        |      SEQUENCE           |
| =0  |  when: this millisec  |  who: this machine   | which: nth id THIS ms   |
```

1. **Timestamp** = *when* (the current millisecond). Makes IDs grow over time → time-sortable.
2. **Machine id** = *who* (a slot number unique to this one machine). Two machines with the same clock still differ here → no collision across machines.
3. **Sequence** = *which* (a tiny counter, 0..4095, for multiple IDs inside the *same* millisecond on the *same* machine).

Why this guarantees uniqueness with **no coordination**: two IDs can only be equal if all three parts match. Different millisecond → timestamps differ. Same millisecond, different machine → machine ids differ. Same millisecond, same machine → the local sequence counter differs. There's **no scenario left** where two IDs collide, and no machine had to coordinate with any other.

### Snowflake in annotated Java

```java
class SnowflakeIdGenerator {

    // --- one-time config (fixed per machine) ---
    private final long machineId;                 // 0..1023 — MY unique slot (assigned once, see §5)
    private static final long EPOCH = 1704067200000L; // custom start time (Jan 1 2024). See Q below.

    // --- how many bits each part gets (must sum to 63; bit 63 is the sign, kept 0) ---
    private static final long MACHINE_BITS  = 10L; // 2^10 = 1024 machines
    private static final long SEQUENCE_BITS = 12L; // 2^12 = 4096 ids per machine per ms

    // --- how far to left-shift each part so they don't overlap ---
    private static final long MACHINE_SHIFT   = SEQUENCE_BITS;                 // 12
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + MACHINE_BITS;  // 22
    private static final long MAX_SEQUENCE    = (1L << SEQUENCE_BITS) - 1;     // 4095 (used as a mask)

    // --- mutable state, shared across threads → must be guarded ---
    private long lastTimestamp = -1L;  // the last ms we generated in
    private long sequence      = 0L;   // the within-ms counter

    public synchronized long nextId() {          // synchronized: only one thread mints at a time
        long now = System.currentTimeMillis();

        if (now == lastTimestamp) {
            // still in the SAME millisecond → bump the sequence counter
            sequence = (sequence + 1) & MAX_SEQUENCE;   // & 4095 wraps 4096 → 0
            if (sequence == 0) {
                // we've used all 4096 slots this ms → wait for the next ms (see §9)
                now = waitNextMillis(lastTimestamp);
            }
        } else {
            // a NEW millisecond → reset the counter to 0
            sequence = 0L;
        }
        lastTimestamp = now;

        // glue the three parts together with shifts + OR:
        return ((now - EPOCH) << TIMESTAMP_SHIFT)  // timestamp in the high bits
             | (machineId      << MACHINE_SHIFT)   // machine id in the middle
             |  sequence;                          // sequence in the low bits
    }

    // spin until the wall clock advances to a new millisecond
    private long waitNextMillis(long lastTs) {
        long now = System.currentTimeMillis();
        while (now <= lastTs) now = System.currentTimeMillis();
        return now;
    }
}
```

#### Q: Why the `<< shift` and `| OR`? What's actually happening?

We're **packing three small numbers into one 64-bit slot** by giving each its own range of bit positions, so they never step on each other:

```
timestamp << 22   →  puts timestamp in bits 22..62   (shifts it left, leaving 22 empty low bits)
machineId << 12   →  puts machineId in bits 12..21
sequence          →  sits in bits 0..11              (no shift needed)

OR (|) them together → merges the three into one number without overlap:

  0000...timestamp....  0000000000  000000000000
| 0000...............  ..machineId  000000000000
| 0000...............  ..........   ....sequence
= 0000...timestamp....  ..machineId  ....sequence   ← the final 64-bit id
```

To read a part back out, you shift the other way and mask: `machineId = (id >> 12) & 1023`. (You rarely need to — the DB just treats the whole thing as one `BIGINT`.)

This comes out time-sortable because the **timestamp is in the highest bits.** When you compare two 64-bit numbers, the high bits dominate. A newer timestamp → a bigger number, *regardless* of what machine id or sequence sits below it. So "sort by id" ≈ "sort by creation time." Within a single millisecond the ordering is decided by machine id + sequence (arbitrary but consistent) — that tiny wobble is why we say "**k-sorted**" (roughly, not perfectly, ordered) rather than strictly sorted.

#### Q: How do I choose the bit split? Why 41 / 10 / 12?

It's a **budget of 63 bits** you divide based on your scale — more bits to one part means fewer for another:

| Give more bits to… | You gain | You lose |
| --- | --- | --- |
| **Timestamp** (41 → e.g. 42) | Longer lifespan before the epoch runs out | fewer machines or fewer ids/ms |
| **Machine id** (10 → e.g. 13) | More machines (8192 instead of 1024) | fewer ids per ms, or shorter lifespan |
| **Sequence** (12 → e.g. 14) | More ids per ms per machine (16384) | fewer machines, or shorter lifespan |

Twitter's original split (41/10/12) is a sane default: ~69 years of life, 1024 machines, ~4M ids/ms per machine. If you have >1024 nodes, steal bits from sequence; if you generate few ids but run huge fleets, do the same.

A "custom epoch" is worth explaining too — why not just use 1970? The 41 timestamp bits count **milliseconds since a start point (epoch)**. They only hold ~69 years' worth. If you start counting from **1970** (the Unix epoch), you've already burned ~55 of those years doing nothing — you'd exhaust the range around 2039. If you start counting from a **recent** date (e.g. Jan 1 2024), you get the *full* ~69 years ahead of you. So we subtract a custom, recent epoch: `(now - EPOCH)`.

---

## 5. Machine-ID Assignment

Each generating node needs a **unique machine id** (else collisions). Options:

| Method | How |
| --- | --- |
| **ZooKeeper/etcd** ✅ | On startup, claim an **ephemeral sequential znode** → your machine id; released on disconnect (reusable) |
| **Config/env** | Statically assign per deployment (simple, error-prone at scale) |
| **From infra** | Derive from a stable host attribute (careful with reuse) |

- The id must be **unique among *running* instances** at any time — ZooKeeper ephemeral nodes handle churn (a dead node's id can be reclaimed).
- With only 10 bits (1024), reuse of freed ids is important for large/elastic fleets.

### Who hands out the "machine slot" numbers?

Snowflake's whole no-coordination trick rests on one promise: **no two live machines share a machine id.** If two machines both think they're "machine #5," they'll happily mint the *same* IDs within the same millisecond → collision. So there must be *some* one-time coordination to hand out these slots — just once at startup, never per ID.

Each machine is assigned a slot number (0–1023) when it starts up. After that it never coordinates again — the slot number is baked into every ID it generates.

Could you just hardcode the machine id in each server's config? You can, and it's the simplest option — but it's **error-prone at scale**: copy-paste a deploy config and two servers end up as "machine #5" → silent duplicate IDs; autoscaling spins up a new instance — what id does *it* get? Someone/something has to assign a fresh, unused one. Fine for a handful of fixed servers; risky for a large, elastic fleet.

#### Q: How does ZooKeeper/etcd solve this automatically?

On startup, a new instance asks ZooKeeper for an **ephemeral sequential node**. Two magic properties:

- **Sequential** — ZooKeeper hands out an ever-increasing number (0, 1, 2, …). Two instances asking at once get *different* numbers. That number becomes the machine id.
- **Ephemeral** — the node exists only while that instance stays connected. If the instance crashes or its network dies, ZooKeeper **auto-deletes** the node, so its id is **freed for reuse** by a future instance.

```java
// pseudo-code: claim a machine id at startup
long claimMachineId() {
    // ZK creates e.g. "/snowflake/ids/node-0000000042" — the number is unique & auto-assigned
    String path = zk.create("/snowflake/ids/node-",
                            EPHEMERAL_SEQUENTIAL);      // dies with this session
    long seq = parseTrailingNumber(path);              // 42
    return seq % 1024;   // fold into the 10-bit space (0..1023)
}
// if this process dies → ZK deletes the node → id 42 becomes available again
```

This matters because you only have **1024 slots**. In an elastic fleet where machines come and go all day, you *must* reclaim the ids of dead machines — otherwise you'd run out of slots even though few are running at once. Ephemeral nodes give you that reclaim for free.

> Key point: this is the system's **only** coordination, and it happens **once per machine at startup** — not once per ID. Millions of IDs are then minted locally with no further talking.

### Slot exhaustion & the zombie-reuse trap

> ⚠️ **The dangerous failure isn't "no free slots" — it's *two live machines sharing one slot*.** Running out of the 1024 slots just fails a startup loudly; a duplicated slot silently mints colliding IDs for hours.

Two things to reason about with only 10 bits (1024 slots):

- **Exhaustion.** If *currently running* instances exceed 1024, new nodes can't claim a slot → fail startup fast (don't wrap around and reuse a live slot). Fix by **stealing bits from sequence** (§4) to widen the machine-id space, or shrinking the fleet.
- **Zombie reuse.** A node's network partitions from ZooKeeper: ZK's session times out and **frees the ephemeral slot**, a new node claims it — but the "dead" node is actually alive (a GC pause or partition, not a crash) and **keeps minting IDs under the reclaimed slot.** Now two live machines share a slot → collisions.

> 💡 **Fencing token** (the standard guard): each slot claim also carries a monotonically increasing **epoch/fencing number**. The zombie holds a *stale* token; when its writes reach a store that tracks the latest token, they're rejected. A cheaper practical guard: on reconnect, a node that lost its ZK session must **re-claim (possibly a different) slot and pause minting** until it does, rather than assuming it still owns the old one.

---

## 6. Ticket Server / Range Allocation

Alternative when you want **short, dense** numbers (e.g. URL shortener codes):

```
A central counter store holds the next id; each node claims a BLOCK (e.g. 1000 ids) atomically,
serves them locally, and refills when exhausted.
```

- Coordination only **once per block** (not per ID) → minimal contention.
- **Smaller numbers** than Snowflake → shorter base62 codes.
- **Trade-off:** a crashed node "wastes" its unused block (fine — keyspace is huge); ids aren't strictly time-ordered globally.
- HA: replicate the counter store; the block claim must be atomic (`INCRBY`).

### Claim a block of ids, don't ask per id

Snowflake needs no central counter at all. A **ticket server** *does* keep one central counter — but instead of asking it for every single number, each machine claims a whole **block of ids** at once and hands them out locally.

A machine reserves a block (say #1000–#1999) in one atomic operation, serves those ids one by one from memory, and only goes back to the central counter when the block runs out to reserve the next block.

```java
class RangeAllocator {

    private long nextId;   // next id I can hand out from my current block
    private long maxId;    // last id in my current block (exclusive)

    // hand out ids locally — NO network call in the common case
    synchronized long nextId() {
        if (nextId >= maxId) {
            refillBlock();   // block exhausted → grab a new one (rare)
        }
        return nextId++;
    }

    // the ONLY coordination: atomically bump the central counter by a block size.
    // e.g. Redis INCRBY returns the new total; we take the 1000 below it.
    private void refillBlock() {
        long end   = redis.incrBy("id_counter", 1000);  // atomic: reserves 1000 ids
        this.maxId = end;
        this.nextId = end - 1000;                        // my block = [end-1000, end)
    }
}
```

Why the atomic `INCRBY` matters: if two machines refill at the same instant, the atomic increment guarantees one gets `[1000,2000)` and the other `[2000,3000)` — **never overlapping ranges**. That single atomic op is the whole coordination, and it happens **once per ~1000 ids**, not once per id.

When would you pick this over Snowflake? When you want **short, dense, human-friendly numbers** — the classic case is a **URL shortener** (`bit.ly/4Zk`). Snowflake ids are huge 64-bit numbers → long base62 codes. A ticket server hands out small numbers (1, 2, 3…, 1001, 1002…) → short codes. The trade-off:

- A crashed machine **wastes its remaining block** (it took #1000–#1999, used 3, died → 997 numbers gone). That's fine — the keyspace is enormous, gaps don't hurt.
- IDs are **not globally time-ordered** (machine A might be on block #5000 while machine B is still on #2000), so you lose Snowflake's clean chronological sort.

---

## 7. UUIDv7 / ULID

Modern no-coordination alternatives that fix UUIDv4's unsortability:

```
UUIDv7 / ULID = [ 48-bit millisecond timestamp | random bits ]
  → time-ordered (sortable) + globally unique + NO coordination (no machine id needed)
```

- **Pros:** dead simple (no machine-id management), time-sortable, unique. Great default for many apps.
- **Cons:** 128-bit (bigger than Snowflake's 64-bit); slightly less compact as a key.
- Use when you want zero coordination and don't need 64-bit compactness.

### UUIDv4 fixed to be sortable

Remember UUIDv4's flaw (§1): it's *pure random*, so it's unsortable and murders your B-tree. UUIDv7/ULID keep the "zero coordination" superpower of a UUID but **move a timestamp into the front bits** — the same trick Snowflake uses to become time-sortable.

```
UUIDv4:  [ 128 random bits ]                         ← random → unsortable, bad key
UUIDv7:  [ 48-bit timestamp | 80 random bits ]       ← time first → sortable, good key
```

```java
// conceptual UUIDv7 construction
long tsMs      = System.currentTimeMillis();   // 48 bits: WHEN (goes in the high bits → sortable)
byte[] random  = secureRandom(10);             // 80 bits: randomness for uniqueness (no machine id!)
UUID id = combine(tsMs, random);
```

Notice what's **missing** compared to Snowflake: there's **no machine id**. Uniqueness comes from the 80 random bits — the chance two machines generate the same random tail in the same millisecond is negligible. So you get Snowflake-like time-sorting **without** ever assigning or coordinating machine ids.

#### Q: So why isn't UUIDv7 always the answer over Snowflake?

- **Size:** it's still **128-bit** — twice Snowflake's 64-bit. Across billions of rows and every index/foreign key, that extra weight adds up.
- **Snowflake is smaller and equally sortable** — but Snowflake makes you *manage machine ids* (§5).

Rule of thumb: **want dead-simple + no machine-id ops → UUIDv7/ULID.** **Want the most compact 64-bit key and don't mind machine-id assignment → Snowflake.**

### Real-world variants worth name-dropping

Mentioning these signals you've seen production systems, not just the textbook:

| Variant | What it changes | When it fits |
| --- | --- | --- |
| **Sonyflake** | Snowflake with a different split: **10ms** time units (39 bits ≈ 174 years), 16-bit machine id (more nodes), 8-bit sequence | huge fleets that mint *few* IDs per node — trades per-ms throughput for more machines + longer life |
| **Leaf-segment** (Meituan) | A **ticket server** (§6): hands out `step`-sized DB segments; double-buffers the next segment so refills never block | short-ish dense IDs, DB already present, want no ZK |
| **Leaf-snowflake** | Snowflake variant that stores machine ids in **ZooKeeper** and reports clock state to detect big backward jumps | Snowflake semantics with managed machine-id lifecycle |
| **ULID** | 128-bit like UUIDv7, but **Crockford base32** text (26 chars, lexicographically sortable, URL-safe) | when you want a sortable *string* id, not a number |

> 💡 The taxonomy collapses to two families: **stamp-based** (Snowflake, Sonyflake, Leaf-snowflake, UUIDv7, ULID — time in the high bits, no per-ID coordination) and **allocation-based** (ticket server, Leaf-segment — claim a block, coordinate per block). Pick the family from the requirements, then the flavor from ops constraints.

---

## 8. Deployment: Library vs Service

| Model | How | Trade-off |
| --- | --- | --- |
| **Embedded library** ✅ | Each app instance generates ids in-process (Snowflake) | Zero network hop, lowest latency; needs machine-id assignment per instance |
| **ID service** | A dedicated service (cluster) hands out ids via RPC | Centralized machine-id mgmt; adds a network hop + a dependency |
| **Range/ticket service** | Service hands out blocks; app serves locally | Rare coordination; short ids |

- **Embedded Snowflake** is usually best (no hop). A **service** is used when you want central control or non-JVM clients sharing one scheme.

---

## 9. Clock Issues & Gotchas

| Issue | Handling |
| --- | --- |
| **Clock skew across machines** | Machine id guarantees uniqueness even if two clocks read the same ms |
| **Clock moving backwards (NTP)** | If `now < lastTs`, **wait** until the clock catches up (or briefly reject) — never emit an id from the past (could collide with already-issued ids) |
| **Sequence overflow in 1ms** | >4096 ids in a ms → **spin-wait** to the next ms |
| **Machine id reuse** | Ephemeral ZooKeeper node → reclaim freed ids; ensure no two live instances share one |
| **Epoch exhaustion** | 41 bits ≈ 69 years from your custom epoch — pick a **recent epoch** so you get the full range |
| **Leap seconds / NTP slew** | Prefer monotonic clock for `lastTs` comparison |

### Snowflake's weak point is the clock

Snowflake's uniqueness leans on **time always moving forward.** The problem: computer clocks *don't* always move forward. **NTP** (the service that keeps clocks accurate) occasionally **nudges the clock backwards** to correct drift. If our timestamp suddenly jumps into the past, we can **re-generate an ID we already handed out** → collision.

Concretely: if the clock is wound **back** a few milliseconds, the generator will re-produce timestamps it already used a moment ago, and with the sequence reset to 0 it can emit the exact same `(timestamp, machineId, sequence)` combination twice — a duplicate ID.

Concretely, here's what happens if the clock goes backwards: say the machine minted ids at `t = 2:00:05.000`. NTP rewinds the clock to `2:00:04.998`. Now `nextId()` computes a timestamp of `...04.998` — a millisecond it **already used** — and the sequence counter resets to 0. It will produce the *exact same* `(timestamp, machineId, sequence)` combo it produced 2ms ago → **duplicate id.** This is the single most famous Snowflake failure.

#### Q: How do we handle it? (clock-backwards guard)

The generator remembers the **last timestamp it used** (`lastTimestamp`). Before minting, it checks: is the clock now *behind* that? If so, we must **never** mint from the past.

```java
public synchronized long nextId() {
    long now = System.currentTimeMillis();

    // --- THE CLOCK-BACKWARDS GUARD ---
    if (now < lastTimestamp) {
        long drift = lastTimestamp - now;
        if (drift <= 5) {
            // tiny rewind (a few ms): just WAIT for the clock to catch back up.
            // Safe because we never emit an id with a timestamp <= one we've used.
            now = waitUntil(lastTimestamp);
        } else {
            // big rewind: don't silently wait forever — fail fast & loud.
            throw new IllegalStateException(
                "Clock moved backwards by " + drift + "ms; refusing to generate id");
        }
    }

    if (now == lastTimestamp) {
        sequence = (sequence + 1) & MAX_SEQUENCE;
        if (sequence == 0) now = waitUntil(lastTimestamp + 1);  // sequence overflow → next ms
    } else {
        sequence = 0L;
    }

    lastTimestamp = now;
    return ((now - EPOCH) << 22) | (machineId << 12) | sequence;
}

private long waitUntil(long targetMs) {
    long now = System.currentTimeMillis();
    while (now < targetMs) now = System.currentTimeMillis();   // spin until time catches up
    return now;
}
```

The two rules that keep every id unique:

- **Never emit an id whose timestamp ≤ one we've already used** → so on a *small* backwards jump, we simply **wait** until the wall clock catches back up to `lastTimestamp`.
- **On a large jump, refuse** (throw / take the node out of rotation) rather than block for minutes or risk a duplicate. Better to fail a few requests than corrupt uniqueness.

This waiting is a cousin of "sequence overflow," not the same bug — both are handled by the same "spin until the clock advances" helper, just triggered differently:

| Situation | Why it happens | Fix |
| --- | --- | --- |
| **Sequence overflow** | >4096 ids in ONE ms on one machine (too fast) | wait for the **next** ms, then reset sequence to 0 |
| **Clock backwards** | NTP rewound the clock (time went *down*) | wait until the clock climbs back to `lastTimestamp` |

Clock *skew* between different machines (as opposed to one clock jumping backwards) is a *different, milder* issue. Two machines' clocks can disagree by a few ms, so their IDs might be slightly out of true time order. That's **fine** — it's exactly why we only claim "**k-sorted**," not perfectly sorted. And it can **never cause a collision**, because the **machine id** part differs between the two machines. Cross-machine skew only affects *ordering*; the clock-*backwards* problem on a *single* machine is the one that threatens *uniqueness*.

> Pro tip from the table above: compare `lastTimestamp` using a **monotonic clock** (`System.nanoTime`-style) where possible, since a monotonic clock by definition never goes backwards — sidestepping most of this. The wall clock is still needed for the actual timestamp value, though.

### Clock skew across data centers — what "k-sorted" really bounds

Across DCs, machine clocks disagree by some bounded amount — call it **ε** (with decent NTP, single-digit to low-tens of milliseconds; with something like Google's TrueTime, a few ms). That ε is exactly the **k** in "k-sorted": two IDs created within ε of each other in *wall time* may come out in the "wrong" order, but IDs created more than ε apart are always ordered correctly.

- **Ordering, not uniqueness, is what skew touches.** Even if two DCs' clocks read the *identical* millisecond, the **machine-id** bits differ → no collision. Skew can shuffle order within ε; it can never mint a duplicate.
- **Practical bound:** if you need "sorted enough for feeds/pagination," ensure ε ≪ the granularity you sort at (e.g. skew of 10ms is invisible when users perceive events at second granularity).
- **Don't confuse with clock-backwards:** cross-machine skew is *steady disagreement between clocks* (mild, ordering-only); clock-**backwards** is *one clock jumping into its own past* (dangerous, uniqueness-threatening — §9 guard above).

---

## 10. Interview Cheat Sheet

> **"How do you generate unique IDs across many servers without a bottleneck?"**
> "**Snowflake** — a 64-bit id = timestamp + machine id + per-ms sequence, generated locally with no per-ID coordination. Time-sortable, ~4B/sec across the fleet; the machine id (assigned once via ZooKeeper) ensures cross-node uniqueness."

> **"Why not UUIDv4 or auto-increment?"**
> "Auto-increment is a central bottleneck and blocks sharding. UUIDv4 is unique but 128-bit and **random** — poor as a DB key (bad index locality). Snowflake (64-bit) or **UUIDv7/ULID** give compact/time-sortable ids."

> **"How is the machine id assigned?"**
> "A ZooKeeper/etcd **ephemeral sequential znode** on startup → a unique id that's reclaimed if the node dies. With 10 bits (1024 nodes), reclaiming freed ids matters for elastic fleets."

> **"What breaks Snowflake?"**
> "Clock moving backwards → if `now < lastTs`, wait so you never re-issue a (timestamp, sequence). Sequence overflow within a ms → spin to the next ms. Duplicate machine ids → collisions (so assignment must be unique)."

> **"When a range/ticket server instead?"**
> "When you want short dense numbers (URL shortener codes) — nodes claim blocks and serve locally, coordinating only per block."

### Tricky scenarios (rapid-fire)

| Scenario | What happens / what to do |
| --- | --- |
| **NTP winds the clock back 3ms** | `now < lastTimestamp` → **wait** (spin) until the clock catches up; never mint from a used millisecond (§9). |
| **NTP jumps back 10 minutes** | Big drift → **refuse to mint** (throw / pull node from rotation). Fail a few requests, don't risk duplicates. |
| **>4096 IDs in one millisecond** | Sequence overflows → **spin-wait to the next ms**, reset sequence to 0. Sustained? steal bits from machine-id for sequence. |
| **Two servers deployed with the same machine id** | Silent duplicate IDs. Root cause is static config; fix with ZK ephemeral-sequential assignment (§5). |
| **Zombie node keeps its slot after a GC pause** | ZK freed the slot, another node took it → collision. Guard with a **fencing token**; re-claim on reconnect (§5). |
| **>1024 live nodes** | Out of slots → widen machine-id bits (§4) or fail startup; never wrap onto a live slot. |
| **Clocks differ across DCs by a few ms** | Ordering wobble within ε only (k-sorted); machine-id bits keep IDs unique — no collision. |
| **ID leaks creation time / node count** | Snowflake is *guessable* by design. If you need unguessable, use random UUIDv4/v7 or encrypt the exposed id. |

> **Ultimate layer model:** timestamp = *when* (sortable) · machine id = *who* (cross-node uniqueness) · sequence = *which* (within-ms uniqueness) · clock guard = the thing that keeps all three honest.

---

## 11. Capacity Estimation (back-of-envelope)

> Numbers are illustrative — the point is to **show the method**, then reason about which knob you'd turn.

```
Target: 1,000,000 IDs/sec across the fleet.

Per-machine budget (Snowflake, 12 sequence bits):
  4096 ids / ms  ×  1000 ms  =  ~4,096,000 ids/sec  per machine

Machines needed for 1M/sec:
  1,000,000 / 4,096,000       ≈  0.25  → ONE machine easily covers it
  (you run more machines for HA / locality, not for throughput)

When do you hit the sequence spin-wait?
  Only if a SINGLE machine must exceed 4096 ids in ONE ms
  → i.e. > ~4.1M ids/sec on one node. Below that, sequence never overflows.
  Fleet of 1M/sec spread over, say, 10 nodes = 100k/sec/node = ~100 ids/ms
  → nowhere near 4096 → no spin-wait.

Machine-id slots (ZooKeeper):
  10 bits = 1024 slots. Size for CONCURRENTLY-LIVE nodes, not total ever-launched.
  A fleet that peaks at 300 live nodes with churn fits comfortably in 1024
  (ephemeral nodes reclaim dead slots). Need >1024 live? steal bits from sequence.

Ticket server block size (if using §6 instead):
  coordination rate = (write QPS) / (block size)
  1M ids/sec, block=1000  → 1000 INCRBY/sec on the counter (easy for one Redis/row)
  1M ids/sec, block=10000 → 100  INCRBY/sec, but a crashed node wastes up to 10k ids
  → bigger block = less coordination, more waste on crash. Pick block ≈ a few seconds of one node's draw.
```

**Takeaways that drive design:** for ID generation, throughput is almost never the constraint — **one Snowflake node already does ~4M/sec.** You scale nodes for **availability and locality**, and you size the machine-id space for **live-node count + churn**, not total volume. The only real capacity question is the ticket server's **block size**, which trades coordination frequency against wasted IDs on crash.

---

## 12. API / Deployment

> Two deployment shapes; pick embedded unless a constraint forces the service. (Extends the library-vs-service trade-off in §8.)

### Shape A — embedded library (default)

```java
// in-process, zero network hop
long id = idGenerator.nextId();   // Snowflake, machine-id claimed once at startup
```

- Lowest latency (sub-µs), no extra dependency on the hot path.
- Cost: every app instance must be assigned a machine id (§5).

### Shape B — ID service (when you need central control or non-JVM clients)

```
POST /v1/ids                      → mint one id
     body: { count?: 1 }          → optionally batch (amortize the RPC)
     200 { ids: [7213554990354432], epoch: 1704067200000, machineId: 42 }

POST /v1/ids:decode               → debug helper: split an id into parts
     body: { id: 7213554990354432 }
     200 { timestampMs, machineId, sequence }

GET  /v1/healthz                  → liveness (includes clock-guard status)
```

**Error codes (the ones interviewers probe):**

| Code | Meaning | Client action |
| --- | --- | --- |
| `200` | ID(s) minted | use them |
| `429 Too Many Requests` | sequence budget saturated this ms (rare) | retry after backoff; batch next time |
| `503 CLOCK_MOVED_BACKWARDS` | clock drifted back beyond the safe threshold → node refusing to mint (§9) | retry → LB routes to a **healthy node**; the sick node self-ejects |
| `500` | unexpected | retry idempotently (minting has no side effects, so retry is safe) |

> ⚠️ **`503` on clock-moved-backwards is a feature, not a bug.** Refusing to issue during a large backward jump trades a little **availability** for **uniqueness** (see §13). The node should also flip `healthz` unhealthy so the load balancer stops sending it traffic until the clock recovers.

### Observability (what to alert on)

- **`clock_backward_events`** — counter of detected backward jumps; a spike means NTP/host-clock trouble. Alert if a node refuses (503) at all.
- **`sequence_overflow_waits`** — how often a node spin-waits for the next ms; rising = a hot node approaching its 4096/ms ceiling → add nodes or widen sequence bits.
- **`machine_id_claim_failures`** — startup couldn't get a slot (ZK down or slots exhausted) → page.
- **`live_machine_ids` / 1024** — slot-pool utilization; approaching the cap means re-plan the bit split.
- **`id_gen_latency_p99`** — should be sub-µs; a jump implies lock contention on `nextId()` or clock spin-waits.

---

## 13. Consistency & CAP Tradeoffs

> "Where do you choose consistency vs availability?" — an ID generator makes this unusually crisp because different parts sit on opposite sides.

| Path | Choice | Why |
| --- | --- | --- |
| **Per-ID minting** (Snowflake node) | **AP** | Each node mints from *local* state (clock + machine id + sequence) with **zero coordination** — it stays available even if fully partitioned from everything else. Uniqueness is structural (the bit layout), not coordinated. |
| **Machine-id assignment** (ZooKeeper/etcd) | **CP** | Slots *must* be globally unique among live nodes → needs a consistent coordinator. During a ZK partition a node **can't claim a new slot** (unavailable to start), but already-running nodes keep minting. |
| **Clock-backwards handling** | **choose C over A** | On a large backward jump the node **refuses to issue** (§9) rather than risk a duplicate → sacrifices availability of *that node* to preserve uniqueness (correctness). |
| **Ticket server** (§6) | **CP counter, AP serving** | The block-claim `INCRBY` is a consistent step; once a block is claimed, serving IDs from it is fully local/available until it drains. |

- **The elegant part:** Snowflake pushes the *hard* consistency (unique machine ids) to a **one-time CP step at startup**, leaving the **per-ID hot path fully AP**. Coordination cost is paid once per machine, never per ID.
- **The honest tradeoff:** refusing to mint on clock skew is a deliberate **CP choice on the write path** — you'd rather return `503` than hand out a colliding key.

> One-liner: **"Minting is AP (local, no coordination); the machine-id slot is CP (must be globally unique); and on bad clocks I choose correctness over availability by refusing to issue."**

---

## 14. How to Drive the Interview (framework)

> Use this order so you never freeze. Spend ~5 min on 1–4, then go deep on 5–6.

1. **Clarify requirements** — 64-bit? sortable? throughput? unguessable? — §2
2. **Rule out the naive options** — auto-increment (bottleneck) and UUIDv4 (random, bad key) — §1
3. **Land on Snowflake** and **draw the 64-bit layout** on the board — §4
4. **Estimate capacity** — one node ≈ 4M/sec; size slots for live nodes, not volume — §11
5. **Deep dive: the two hard parts** → **machine-id assignment** (§5) and **the clock** (§9)
6. **Address failures & tradeoffs** — clock-backwards, zombie slots, CAP — §9, §13

> 🎤 **Lead with the core challenge:** "many machines must mint unique, time-sortable numbers *without coordinating per ID*." Then the whole design is just: **stamp time + who-I-am + a within-ms counter**, and defend the clock.

**Two questions you should invite (they're the whole interview):**

- ❓ **"Draw the bits."** Sketch `sign | 41 timestamp | 10 machine | 12 sequence` and explain why timestamp-in-high-bits → sortable (§4).
- ❓ **"NTP goes backwards — what happens?"** Small jump → **wait**; large jump → **refuse** (`503`) so you never re-issue a used `(timestamp, machineId, sequence)` (§9). This is the single most-asked follow-up.

### Worked exercise: decode a Snowflake ID

Given the 64-bit id `65954591539372032` and epoch `1704067200000` (Jan 1 2024 UTC), with layout `41 | 10 | 12` (shifts 22 / 12 / 0), extract the three parts by shifting and masking:

```
id = 65954591539372032

sequence  = id & 0xFFF            = id & 4095                     = 0
machineId = (id >> 12) & 0x3FF    = (id >> 12) & 1023             = 42
timeDelta = id >> 22              = id / 4194304                  = 15724800000  (ms since epoch)
timestamp = timeDelta + EPOCH     = 15724800000 + 1704067200000   = 1719792000000 ms
          → 2024-07-01 00:00:00 UTC  (the wall-clock time of creation)
```

> 💡 **Why it works:** each part lives in its own bit range, so masking (`& (2^bits − 1)`) grabs the low bits and shifting (`>> shift`) drops the parts below it. Reading an ID is just the packing from §4 run in reverse — you almost never do this in prod (the DB treats the whole thing as one `BIGINT`), but being able to decode on a whiteboard proves you understand the layout.

---

## 15. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Strategy** | ID scheme (Snowflake / UUIDv7 / range) | Swap per need |
| **Factory** | ID generator per node (config: machine id, epoch, bit layout) | Encapsulate creation |
| **Singleton / Object Pool** | One generator per process (shared `lastTs`/`sequence`) | Thread-safe shared state |
| **Leader Election / Coordination** | Machine-id assignment (ZooKeeper/etcd) | Unique node ids |
| **Ticket / Range Allocation** | Block-based counters | Rare coordination, short ids |

---

## 16. Final Takeaways

- Want **64-bit, unique, time-sortable, no per-ID coordination** → **Snowflake** (timestamp | machine id | sequence); bit layout is tunable.
- **Machine id** assigned once via **ZooKeeper/etcd ephemeral node** (reclaimable); the only coordination.
- **UUIDv7/ULID** = zero-coordination alternative (128-bit, time-ordered); **range/ticket** allocation for short dense ids.
- Avoid **auto-increment** (bottleneck) and **UUIDv4** (large, unsortable) as scalable keys.
- Guard **clock-backwards** (wait), **sequence overflow** (next ms), **unique machine ids**, and pick a **recent epoch**.
- Patterns: Strategy, Factory, Singleton, Leader Election, Range Allocation.

### Related notes

- [URL Shortener](url-shortener-system-design.md) — base62 of generated ids (range allocation vs Snowflake)
- [Databases — Deep Dive](../concepts/databases-deep-dive.md) (why time-sortable keys matter for B-trees) · [Consistent Hashing](../concepts/consistent-hashing.md)
