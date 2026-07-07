# Apache Kafka — From Zero to Deep

> **What it is in one line:** Kafka is a **distributed, durable, append-only log** you can publish messages to and have many independent consumers read from — built for **high throughput**, **replay**, and **decoupling** producers from consumers. Think "a giant, replicated, ordered logbook that never forgets (until you tell it to)."
>
> This note assumes **zero prior knowledge** and builds up to the hard operational questions: **how to size partitions, how to scale consumers and concurrency, and how to handle consumer lag.**

> **How to read this doc:** most sections give the dense summary first, then a **Plain-English** deep dive (a real-world analogy, annotated code/config, and the exact confusions that come up while learning). Skim the summaries for revision; read the Plain-English parts to actually understand.

---

## Contents

- [1. Why Kafka Exists (the problem it solves)](#1-why-kafka-exists-the-problem-it-solves)
- [2. The Mental Model — A Distributed Log](#2-the-mental-model--a-distributed-log)
- [3. Core Concepts & Vocabulary](#3-core-concepts--vocabulary)
- [4. Partitions — The Heart of Kafka](#4-partitions--the-heart-of-kafka)
- [5. Producers](#5-producers)
- [6. Consumers & Consumer Groups](#6-consumers--consumer-groups)
- [7. Offsets & Committing](#7-offsets--committing)
- [8. Delivery Semantics](#8-delivery-semantics)
- [9. Replication & Durability](#9-replication--durability)
- [10. How to Decide Partition Count](#10-how-to-decide-partition-count)
- [11. Scaling Consumers & Concurrency](#11-scaling-consumers--concurrency)
- [12. Consumer Lag — What It Is & How to Handle It](#12-consumer-lag--what-it-is--how-to-handle-it)
- [13. Rebalancing](#13-rebalancing)
- [14. Retention, Compaction & Cleanup](#14-retention-compaction--cleanup)
- [15. Key Configs Cheat Sheet](#15-key-configs-cheat-sheet)
- [16. Kafka vs Other Queues](#16-kafka-vs-other-queues)
- [17. Failure Scenarios & Gotchas](#17-failure-scenarios--gotchas)
- [18. Interview Cheat Sheet](#18-interview-cheat-sheet)
- [19. Final Takeaways](#19-final-takeaways)

---

## 1. Why Kafka Exists (the problem it solves)

Imagine services calling each other directly:

```
Order Service ──HTTP──► Email Service
             ──HTTP──► Analytics Service
             ──HTTP──► Inventory Service
```

Problems:
- **Tight coupling** — Order Service must know every consumer and wait for each.
- **Fragility** — if Email Service is down, does the order fail?
- **Spikes** — a flash sale overwhelms downstream services instantly.
- **No replay** — if Analytics was down, those events are lost.

Kafka sits in the middle as a **buffer + broadcast log**:

```
Order Service ──► [ Kafka topic: orders ] ──► Email consumer
                                          ──► Analytics consumer
                                          ──► Inventory consumer
```

| Benefit | How Kafka provides it |
| --- | --- |
| **Decoupling** | Producer doesn't know or wait for consumers |
| **Buffering / spike absorption** | Messages sit in the log until consumers catch up |
| **Durability** | Messages are persisted to disk and replicated |
| **Replay** | Consumers can re-read from any past offset |
| **Fan-out** | Many independent consumer groups read the same data |
| **High throughput** | Sequential disk writes + batching + zero-copy → millions msg/sec |

> Kafka is often called a "distributed commit log" or an "event streaming platform." It's **not** just a queue — the key difference is **messages aren't deleted when read**; they stay until retention expires, so many consumers (and replays) are possible.

### Plain-English: Kafka is the office bulletin board, not a phone call

**Analogy: a shared bulletin board vs. making phone calls.**

Without Kafka, the Order Service is like a person who, for every order, has to *phone* Email, then *phone* Analytics, then *phone* Inventory — and wait on hold for each to pick up. If Email doesn't answer, the whole errand stalls. If 10,000 orders come in at once, the caller is overwhelmed.

Kafka is a **bulletin board in the hallway**. The Order Service just **pins a note** ("Order #55 placed") and walks away — instantly, done. Email, Analytics, and Inventory each **stroll by whenever they're ready** and read the notes at their own pace. The note stays pinned even after someone reads it, so a team that was on vacation (down) can catch up later by reading everything they missed.

```
Old way (phone calls):   Order ──waits──► Email ──waits──► Analytics ──waits──► Inventory
Kafka way (bulletin):    Order ──pins note──► [ board ]  ◄──reads whenever──  Email / Analytics / Inventory
```

#### Q: Isn't this just a message queue like a to-do list?

Not quite. A normal queue is a **to-do list where you tear off a task once you do it** — the task is gone, and only one worker can ever see it. Kafka is a **logbook**: reading an entry doesn't erase it. That's what unlocks two superpowers a plain queue can't do:

- **Fan-out** — Email, Analytics, and Inventory can *all* read the same "Order #55" note independently. On a tear-off list, whoever grabs it first denies it to the others.
- **Replay** — if Analytics had a bug, it can go back and re-read last week's notes. On a tear-off list those tasks are long gone.

#### Q: If notes are never torn off, doesn't the board fill up forever?

No — Kafka deletes old notes on a schedule (**retention**, e.g. "keep 7 days"), *not* when they're read. So the board self-cleans by age/size, but within the retention window everyone gets as many reads and replays as they want. (More in [§14](#14-retention-compaction--cleanup).)

---

## 2. The Mental Model — A Distributed Log

The single most important idea: a Kafka partition is an **append-only log**.

```
Partition (an ordered log):
 offset:  0    1    2    3    4    5    6   →  (new messages appended at the end)
        [m0] [m1] [m2] [m3] [m4] [m5] [m6]
                             ▲                    ▲
                    consumer A reads here   producer appends here
```

- Producers **append** to the end.
- Each message gets a monotonically increasing **offset** (its position in the log).
- Consumers track **which offset they've read up to** — they can go back (replay) or skip ahead.
- Messages are **immutable** once written.
- Reading does **not** remove the message (unlike a traditional queue).

Everything else in Kafka (topics, partitions, consumer groups, replication) is machinery around this log idea.

### Plain-English: a numbered notebook you only ever add to

**Analogy: a spiral notebook where you only write on the next blank line.**

- You **never erase or edit** a line you already wrote. New stuff goes on the **next empty line**, always at the bottom.
- Every line has a **line number** that only goes up: 0, 1, 2, 3... That number is the **offset**.
- Each reader keeps their **own bookmark** ("I've read up to line 95"). A reader can move the bookmark *back* to re-read old lines (replay) or *forward* to skip ahead. Reading doesn't rub anything out.

```
line #:   0      1      2      3      4      5
        [m0]   [m1]   [m2]   [m3]   [m4]   [m5]   ← writer always adds here
                              ▲
                     reader's bookmark (offset 3): "I've read up to here"
```

The single most important mental shift: **an offset is not "how many messages are left" — it's a permanent address for a slot in the notebook.** Message at offset 3 is *always* at offset 3, forever, for everybody. Your bookmark is separate from the writing.

#### Q: If two consumers read the same partition, do they share one bookmark?

No — that's the magic. **Each consumer group keeps its own bookmark.** Email's group might be at line 95 while Analytics's group is at line 40, both reading the same notebook. Neither disturbs the other's position (see [§7 Offsets](#7-offsets--committing)).

#### Q: Why "append-only"? Why can't I just edit a line?

Because appending to the end of a file is one of the *fastest* things a computer can do (no searching, no shuffling, sequential disk write). Editing the middle would mean locking, finding the spot, and rewriting — slow, and it would break every reader's bookmark. Immutability is *why* Kafka is fast and replayable.

---

## 3. Core Concepts & Vocabulary

| Term | What it is |
| --- | --- |
| **Event / Message / Record** | A key-value pair + timestamp + headers. The unit of data. |
| **Topic** | A named category of messages (e.g. `orders`, `clicks`). Logical grouping. |
| **Partition** | A topic is split into N partitions; each is an ordered append-only log. **Unit of parallelism & ordering.** |
| **Offset** | The position of a message within a partition (0, 1, 2, …). Unique per partition. |
| **Broker** | A single Kafka server. Stores partitions, serves reads/writes. |
| **Cluster** | A group of brokers working together. |
| **Producer** | Client that publishes (appends) messages to topics. |
| **Consumer** | Client that reads messages from topics. |
| **Consumer Group** | A set of consumers that **share** the work of reading a topic's partitions. |
| **Replica** | A copy of a partition on another broker (for fault tolerance). |
| **Leader / Follower** | Each partition has one leader replica (handles reads/writes) + follower replicas (copy it). |
| **ISR** | In-Sync Replicas — followers currently caught up with the leader. |
| **Controller** | A broker that manages cluster metadata, leader elections. |
| **ZooKeeper / KRaft** | Coordination layer. Older Kafka used ZooKeeper; newer uses **KRaft** (built-in Raft, no ZooKeeper). |

Visual hierarchy:

```
Cluster
 ├── Broker 1
 │     ├── topic "orders" partition 0 (leader)
 │     └── topic "orders" partition 2 (follower)
 ├── Broker 2
 │     ├── topic "orders" partition 1 (leader)
 │     └── topic "orders" partition 0 (follower)
 └── Broker 3
       ├── topic "orders" partition 2 (leader)
       └── topic "orders" partition 1 (follower)
```

### Plain-English: the vocabulary as a library

**Analogy: a chain of libraries.** All the abstract terms map to something physical:

| Kafka term | Library analogy |
| --- | --- |
| **Message/Event** | A single note/fact you want to record |
| **Topic** | A *subject* — e.g. the "Orders" shelf vs the "Clicks" shelf |
| **Partition** | One *volume* of that subject (Orders Vol. 0, Vol. 1, Vol. 2) — a real, ordered book |
| **Offset** | The *page number* inside one volume |
| **Broker** | One *library building* that physically holds some volumes |
| **Cluster** | The whole *library chain* (all buildings together) |
| **Producer** | Someone *writing* new pages |
| **Consumer** | Someone *reading* pages |
| **Consumer Group** | A *team* of readers splitting the volumes among themselves |
| **Replica** | A *photocopy* of a volume kept in another building (in case one burns down) |
| **Leader / Follower** | The *official* copy you write to vs. the *backup* copies |
| **ISR** | The backups that are *fully up to date* right now |
| **Controller / KRaft** | The *head librarian* deciding who holds what and who's in charge |

The one thing to burn in: **"topic" is a logical label; "partition" is the real physical ordered log.** When people say "Kafka scales" or "Kafka keeps order," they always mean *per partition* — the topic is just the folder name over a set of partitions.

#### Q: What's the difference between a broker and a cluster, really?

A **broker** is a single server process (one machine). A **cluster** is several brokers that agreed to work together and split the partitions among themselves. You talk to "the cluster," but any given partition physically lives on specific brokers.

#### Q: ZooKeeper vs KRaft — do I need to care?

For understanding Kafka, no. Both are just the **coordination brain** that tracks "which broker leads which partition" and runs elections when one dies. Old Kafka bolted on a separate system called ZooKeeper for this; modern Kafka has it built in (**KRaft**). Same job, one less moving part.

---

## 4. Partitions — The Heart of Kafka

**A partition is one ordered, append-only log.** A topic is just a collection of partitions.

```
Topic "orders" (3 partitions):

Partition 0:  [o0][o3][o6][o9]  →
Partition 1:  [o1][o4][o7]      →
Partition 2:  [o2][o5][o8]      →
```

### Why partitions exist (two jobs)

1. **Parallelism** — different partitions live on different brokers and are consumed independently → this is how Kafka scales horizontally.
2. **Ordering** — Kafka guarantees order **only within a single partition**, not across partitions.

> 🔑 **The golden rule:** ordering is per-partition. If you need "all events for user 123 in order," they must all go to the **same partition**.

### How a message is assigned to a partition

When a producer sends a message:

```
if message has a key:
    partition = hash(key) % numPartitions      # same key → same partition (ordering!)
else:
    partition = round-robin / sticky            # spread evenly, no ordering guarantee
```

- **With a key** (e.g. `user_id`) → all messages with that key land on the same partition → ordered per key + even-ish distribution.
- **Without a key** → spread across partitions for max throughput, but no per-entity ordering.

### Example: why the key matters

```
Key = user_id → user 123's events always go to partition 1
  → "order placed" then "order cancelled" for user 123 are read IN ORDER
  → different users spread across partitions for parallelism
```

If you used no key, "cancelled" could be processed before "placed" (different partitions, different consumers).

### Partition properties

| Property | Detail |
| --- | --- |
| Ordering | Guaranteed **within** a partition only |
| Parallelism | Each partition consumed by **exactly one** consumer in a group at a time |
| Storage | A partition is split into **segment files** on one broker's disk |
| Leadership | One replica is leader; producers/consumers talk to the leader |
| Immutable | Messages never change; only appended |

> ⚠️ **You can increase partitions later, but not decrease them** — and increasing changes the `hash(key) % N` mapping, so a key that used to go to partition 1 may now go to partition 4 → **ordering for existing keys breaks** at the boundary. Plan partition count ahead. See [§10](#10-how-to-decide-partition-count).

### Plain-English: partitions are checkout lanes at a supermarket

**Analogy: checkout lanes.** One topic = the store. Partitions = the checkout lanes. Splitting into lanes does two things at once:

1. **Parallelism** — 4 lanes serve 4 customers simultaneously, instead of one giant queue. More lanes = more throughput.
2. **Ordering only *within* a lane** — the store guarantees the *order of people in lane 3*, but makes **no promise** about lane 3 vs lane 1. If your grandma and grandpa must be served in order, they must stand in the **same lane**.

That second point is the whole reason for **keys**. The key decides which lane you're sent to:

```java
// key = user_id → all of user 123's events go to the SAME lane → processed in order
producer.send(new ProducerRecord<>("orders", "user-123", "order placed"));
producer.send(new ProducerRecord<>("orders", "user-123", "order cancelled"));
// both land in the same partition → "placed" is guaranteed to be read before "cancelled"

// no key → round-robin across lanes → fast, but "cancelled" might be read before "placed"
producer.send(new ProducerRecord<>("orders", null, "order cancelled"));
```

How Kafka turns a key into a lane — a tiny bit of arithmetic you can do by hand:

```
partition = hash(key) % numPartitions

hash("user-123") = 887788        (some big number, always the same for this key)
887788 % 4 (lanes)  = 0          → user-123 ALWAYS goes to lane 0
```

Same key → same hash → same lane. Every single time. That determinism is what preserves per-user order.

#### Q: Why can I add partitions but never remove them?

Because the lane is chosen by `hash(key) % N`. If you change `N` (add lanes), the math changes: `hash("user-123") % 4` might be lane 0, but `% 6` might be lane 2. So a user's **new** events start landing in a different lane than their **old** events — and the old ones sitting in lane 0 are now out of order relative to the new ones. This is why you **plan partition count up front and over-provision** rather than resizing later (see [§10](#10-how-to-decide-partition-count)).

#### Q: What is a "hot partition"?

If one key is wildly more popular than the rest (one giant customer, one viral ad), *its* lane gets slammed while other lanes idle — because that key always maps to one lane. That's a **hot partition / hot key** (see [§17](#17-failure-scenarios--gotchas), and the salting fix in the [Ad Click Aggregation](../system-design/ad-click-aggregation-system-design.md) note).

---

## 5. Producers

A producer publishes messages to a topic. Key concerns:

### `acks` — durability vs latency

| `acks` | Meaning | Trade-off |
| --- | --- | --- |
| `0` | Fire-and-forget; don't wait for broker | Fastest, can lose data |
| `1` | Wait for **leader** to write | Balanced; loses data if leader dies before replication |
| `all` (`-1`) | Wait for leader **+ all in-sync replicas** | Safest, highest latency |

> For anything important, use `acks=all` **+** `min.insync.replicas=2` (see [§9](#9-replication--durability)).

### Batching & throughput

Producers batch messages for efficiency:

| Config | Effect |
| --- | --- |
| `batch.size` | Max bytes per batch per partition |
| `linger.ms` | Wait up to N ms to fill a batch before sending (small delay → bigger batches → more throughput) |
| `compression.type` | `lz4`/`snappy`/`zstd` — compress batches, big throughput win |

### Idempotent producer (avoid duplicates on retry)

```
enable.idempotence = true
```

Without it, a producer retry after a network blip can write the **same message twice**. The idempotent producer tags messages with a sequence number so the broker de-dups retries. **Turn this on** — it's default in modern Kafka and costs almost nothing.

### Producer flow

```
producer.send(topic, key, value)
  → partitioner picks partition (hash(key) % N)
  → message added to a batch for that partition
  → batch sent to the partition LEADER broker
  → leader writes to log, replicates to followers
  → ack returned based on `acks` setting
```

### Plain-English: the producer is a courier dropping off packages

**Analogy: sending a package and choosing how much delivery confirmation you want.**

`acks` is literally "how sure do you want to be that it arrived before you move on?":

- `acks=0` — **drop it on the porch and drive off.** Fastest, but if it blows away you'll never know.
- `acks=1` — **wait for the front-desk clerk (leader) to sign.** Good enough usually, but if the clerk faints before filing the copy in the back office (replicating), it's lost.
- `acks=all` — **wait until the clerk AND the backup filers (in-sync replicas) all have a copy.** Slowest, but the package survives a building fire.

```java
// for anything that matters (payments, orders): be safe
props.put("acks", "all");                 // wait for leader + all in-sync replicas
props.put("enable.idempotence", true);    // don't double-write on retry (see below)
// pair with min.insync.replicas=2 on the topic (see §9)
```

**Batching = the courier waits to fill the van.** Instead of one trip per package, the producer waits a few milliseconds (`linger.ms`) to pile up packages going to the same lane, then sends them together. A tiny wait dramatically raises throughput:

```java
props.put("batch.size", 32768);   // pack up to 32 KB per lane per trip
props.put("linger.ms", 5);        // wait up to 5ms to fill the van before leaving
props.put("compression.type", "lz4");  // shrink-wrap the load → more fits, faster
```

#### Q: What does the idempotent producer actually fix?

Imagine the courier delivers a package, but the "signed!" confirmation gets lost on the way back. The courier assumes it failed and **delivers a second copy** — now there are two. The idempotent producer stamps each package with a **sequence number**, so the receiving desk notices "I already have #7" and quietly ignores the duplicate.

```
Without idempotence:   send #7 → (ack lost) → retry #7 → broker stores #7 TWICE  ✗
With idempotence:      send #7 → (ack lost) → retry #7 → broker sees dup, keeps ONE  ✓
```

It's basically free and on by default in modern Kafka — **leave it on.**

#### Q: `linger.ms=5` adds delay — isn't waiting bad?

Counter-intuitively, a *tiny* wait usually makes the whole system **faster and cheaper**, because sending one big batch beats sending 100 tiny messages (less network overhead, better compression). You trade a few milliseconds of latency for a big throughput gain. Only latency-critical, low-volume paths set `linger.ms=0`.

---

## 6. Consumers & Consumer Groups

This is where scaling lives — read carefully.

### A single consumer

Reads messages from one or more partitions, tracks its offset, processes them.

### Consumer group — the core scaling primitive

A **consumer group** is a set of consumers that cooperate to consume a topic. Kafka divides the topic's partitions among the group's consumers.

```
Topic "orders" has 4 partitions (P0, P1, P2, P3).

Group "order-processors" with 2 consumers:
  Consumer A → P0, P1
  Consumer B → P2, P3

Scale up to 4 consumers:
  Consumer A → P0
  Consumer B → P1
  Consumer C → P2
  Consumer D → P3        ← now maximum parallelism

Add a 5th consumer:
  Consumer E → (idle!)   ← no partition left to assign
```

### 🔑 The two rules that govern everything

1. **Each partition is consumed by exactly one consumer within a group** (at a time).
2. **Therefore: max useful consumers in a group = number of partitions.** Extra consumers sit idle.

> This is *the* reason partition count matters so much: **partitions cap your consumer parallelism**. You cannot process a topic with more than `partition_count` consumers in one group.

### Multiple groups = fan-out (pub/sub)

Different consumer groups are **independent** — each gets a full copy of the stream:

```
Topic "orders"
  ├── Group "email-service"     → sends emails       (own offsets)
  ├── Group "analytics-service" → aggregates metrics (own offsets)
  └── Group "fraud-service"     → scores risk        (own offsets)
```

Each group reads all messages and tracks its own offsets. One slow group doesn't block another.

### Plain-English: a consumer group is a crew splitting the delivery routes

**Analogy: a pizza shop with delivery drivers (one crew) and multiple lanes of orders.**

A **partition** is a delivery route. A **consumer group** is the crew of drivers for *one purpose* (say, "delivery"). The rules fall right out of the analogy:

- **Each route is handled by exactly one driver in the crew** — you don't want two drivers racing to the same house. So a partition is owned by exactly one consumer *within a group*.
- **More drivers than routes = idle drivers.** If you have 4 routes and hire a 5th driver, they sit in the break room — there's no route left to give them. **Max useful drivers = number of routes = number of partitions.**

```
4 routes (partitions), 2 drivers:   each drives 2 routes
4 routes, 4 drivers:                each drives 1 route   ← max parallelism
4 routes, 5 drivers:                5th driver is IDLE    ← partitions cap you
```

**Different crews = fan-out.** The "delivery crew," the "accounting crew," and the "marketing crew" are separate groups. Each crew independently reads *every* order for its own purpose, with its own progress bookmark. The delivery crew being slow doesn't hold up accounting.

```java
// This app is part of the "email-service" crew.
// Kafka automatically hands each instance some partitions and rebalances if one dies.
props.put("group.id", "email-service");   // ← the crew you belong to

// A totally separate crew, reading the SAME topic independently:
props.put("group.id", "analytics-service");
```

#### Q: If I want to process faster, do I just keep adding consumers?

Only **up to the partition count**. Adding drivers past the number of routes does nothing (they idle). To go faster beyond that, you must **add partitions first**, or speed up each consumer / add in-consumer threads (see [§11](#11-scaling-consumers--concurrency)). This "partitions cap parallelism" rule is *the* most important operational fact about Kafka.

#### Q: Same `group.id` vs different `group.id` — what's the practical effect?

- **Same `group.id`** → those consumers *share* the work (split the partitions). Use this to **scale one job**.
- **Different `group.id`** → each gets the *full* stream, independently. Use this for **different jobs** on the same data (email vs analytics vs fraud).

---

## 7. Offsets & Committing

Each consumer group stores, **per partition**, the offset it has processed up to. This is saved in an internal Kafka topic `__consumer_offsets`.

```
Group "email-service", topic "orders":
  partition 0 → committed offset 105
  partition 1 → committed offset 98
```

If a consumer crashes and restarts, it resumes from the last committed offset.

### When you commit determines your delivery semantics

| Strategy | Behavior |
| --- | --- |
| **Auto-commit** (`enable.auto.commit=true`) | Commits periodically (`auto.commit.interval.ms`). Simple but can lose or duplicate on crash. |
| **Manual commit after processing** | Commit only once work is done → **at-least-once** (may reprocess on crash). Most common. |
| **Manual commit before processing** | → **at-most-once** (may lose on crash). Rare. |

### `auto.offset.reset` — where to start with no committed offset

| Value | Behavior |
| --- | --- |
| `latest` (default) | Only new messages from now |
| `earliest` | From the very beginning of the partition |

### Plain-English: committing an offset = saving your place in a book

**Analogy: reading a long book and using a bookmark.**

- The **offset** is the page you're on. **Committing** the offset is the act of *moving the physical bookmark* so that if you close the book (crash) and reopen it later, you resume from the bookmark — not from page 1, and not from wherever you happened to stop reading.
- The subtle part: **reading a page and moving the bookmark are two separate actions.** *When* you move the bookmark decides what happens if you get interrupted mid-page.

```java
// enable.auto.commit = false → YOU control when the bookmark moves
props.put("enable.auto.commit", false);

while (true) {
    var records = consumer.poll(Duration.ofMillis(100));  // read a batch of pages
    for (var record : records) {
        process(record);            // do the actual work first
    }
    consumer.commitSync();          // THEN move the bookmark → "at-least-once"
}
```

**The two orderings, and why they matter:**

```
Process THEN commit  (bookmark moves after work is done):
    crash after processing but before commit → you re-read those pages → work happens AGAIN
    ⇒ AT-LEAST-ONCE  (never lose a message, but may repeat → be idempotent)

Commit THEN process  (bookmark moves before work is done):
    crash after commit but before work → those pages are skipped forever
    ⇒ AT-MOST-ONCE  (never repeat, but may lose messages)
```

Almost everyone picks **process-then-commit (at-least-once)** and makes their processing idempotent, because losing a payment is worse than processing it twice.

#### Q: Where is the bookmark actually stored? In the consumer's memory?

No — if it were in memory it'd vanish on crash. Kafka saves committed offsets in a special internal topic called `__consumer_offsets`, **per group, per partition**. That's why a brand-new consumer instance can pick up exactly where the crashed one left off.

#### Q: What's `auto.offset.reset` for — isn't there always a bookmark?

Only if the group has read before. For a **brand-new group** (or one whose old bookmark expired), there's no saved page. `auto.offset.reset` decides the starting page:

- `latest` (default) → "start from now, ignore history" (typical for live processing).
- `earliest` → "start from page 1, read everything ever written" (typical for backfills/replay).

---

## 8. Delivery Semantics

| Guarantee | How | Cost |
| --- | --- | --- |
| **At-most-once** | Commit offset **before** processing | May lose messages; no dup |
| **At-least-once** ✅ default in practice | Commit **after** processing | May reprocess → **consumers must be idempotent** |
| **Exactly-once (EOS)** | Idempotent producer + **transactions** (or Kafka Streams) | Complex, some overhead; only within Kafka→Kafka flows |

> **Practical answer:** almost everyone runs **at-least-once + idempotent consumers** (dedup by a business key). True exactly-once is real in Kafka (transactions), but it only spans Kafka reads/writes — the moment you call an external DB/API, you're back to needing idempotency.

### Plain-English: the three delivery promises, via a package courier

**Analogy: how carefully a courier handles your package.**

| Guarantee | Courier behavior | Result |
| --- | --- | --- |
| **At-most-once** | Marks "delivered" *before* actually knocking, then knocks | Might mark done but drop it → **you can lose messages, never get doubles** |
| **At-least-once** | Knocks and delivers *first*, then marks "delivered" | If it crashes before marking, it redelivers → **never lost, but you can get doubles** |
| **Exactly-once** | Delivers with a tamper-proof ledger entry that can't be double-counted | **No loss, no doubles** — but only works inside Kafka's own building |

The key realization: **at-least-once will hand you duplicates, and that's fine** — as long as *your* processing is idempotent (doing it twice = same result). This is why "dedup by a business key" comes up everywhere.

```java
// At-least-once in practice: make processing safe to repeat.
// Example — dedup by a business key so a re-delivered order doesn't charge twice.
void handle(OrderEvent e) {
    if (alreadyProcessed(e.orderId())) {   // seen this orderId? skip.
        return;                            // ← idempotency: repeat = no-op
    }
    chargeCustomer(e);
    markProcessed(e.orderId());
}
```

#### Q: Kafka has "exactly-once" — why doesn't everyone just use that?

Because Kafka's exactly-once only covers **Kafka-in to Kafka-out** (read from a topic, write to a topic, in one transaction). The instant your consumer calls an **external** database, payment gateway, or email API, Kafka can't wrap that in its transaction. If you crash after emailing the customer but before committing, a restart re-emails them. So in the real world you still need idempotency at the edges — exactly-once is *not* a magic "I never have to think about duplicates" switch.

#### Q: So what should I actually build?

At-least-once delivery **+ idempotent consumers**. It's simpler, robust, and the standard answer in interviews. Reserve true exactly-once (transactions / Kafka Streams) for pure Kafka-to-Kafka stream processing.

---

## 9. Replication & Durability

Each partition is replicated across brokers for fault tolerance.

```
replication.factor = 3   (1 leader + 2 followers)

Partition 0:  Leader on Broker 1
              Follower on Broker 2
              Follower on Broker 3
```

- **Leader** handles all reads/writes for the partition.
- **Followers** copy the leader's log.
- **ISR (In-Sync Replicas)** = replicas currently caught up. If the leader dies, a new leader is elected **from the ISR**.

### The durability combo

```
acks = all                 (producer waits for all ISR)
replication.factor = 3
min.insync.replicas = 2    (at least 2 replicas must have the message to accept a write)
```

This tolerates **1 broker failure with zero data loss**. If ISR drops below `min.insync.replicas`, the partition **rejects writes** (fails safe rather than losing data).

| Setting | Too low | Too high |
| --- | --- | --- |
| `replication.factor` | Data loss risk | More disk + network |
| `min.insync.replicas` | Weak durability | Writes fail more easily during outages |

### Plain-English: keeping photocopies in different buildings

**Analogy: a critical document you can't afford to lose.** You keep the **original** in Building 1 (the **leader**) and **photocopies** in Buildings 2 and 3 (**followers**). If Building 1 burns down, you grab a photocopy and promote it to the new original — no data lost.

- **Leader** = the one everybody reads from and writes to (the working copy).
- **Followers** = backups that constantly copy the leader.
- **ISR (in-sync replicas)** = the backups that are *currently fully caught up*. A new leader can only be promoted from the ISR (you'd never promote a stale, half-copied backup).

The **durability combo** and what each knob means in plain terms:

```
replication.factor = 3     → keep 3 copies total (1 original + 2 photocopies)
acks = all                 → the courier waits until all in-sync copies exist before saying "done"
min.insync.replicas = 2    → refuse to accept writes unless at least 2 copies will exist
```

Together: **you can lose 1 whole broker and lose zero data.**

#### Q: What does `min.insync.replicas=2` actually do during an outage?

It's a **safety brake**. Suppose two of your three brokers are down, so only the leader is left. With `min.insync.replicas=2`, Kafka says: "I can't guarantee a second copy right now, so I'll **reject new writes**" rather than accept a write that exists on only one machine (which would be lost if that one dies). It **fails safe** — the producer gets an error and can retry later, instead of silently risking data loss.

```
Healthy:   leader + 2 followers in ISR (3 ≥ 2) → writes accepted ✓
1 down:    leader + 1 follower  in ISR (2 ≥ 2) → writes still accepted ✓  (survives 1 failure)
2 down:    leader only          in ISR (1 < 2) → writes REJECTED — protects your data ✗→safe
```

#### Q: Isn't `acks=all` + `min.insync.replicas` redundant?

They're two halves of one guarantee. `acks=all` is the **producer** saying "wait for all in-sync copies." `min.insync.replicas=2` is the **topic** defining "in-sync must mean *at least 2*." Set only `acks=all` with a min of 1 and "all copies" could momentarily mean "just the leader" — no real safety. You need both.

---

## 10. How to Decide Partition Count

This is one of the most-asked design questions. There's a concrete method.

### The throughput formula (Confluent's rule of thumb)

```
partitions = max( T / P , T / C )

  T = target throughput (e.g. MB/s or messages/sec) you need
  P = throughput a single partition can sustain on the PRODUCER side
  C = throughput a single CONSUMER can sustain reading one partition
```

Take the **max** because you need enough partitions to satisfy whichever side (produce or consume) needs more parallelism.

### Worked example

```
Target: process 100,000 messages/sec
One consumer instance processes ~5,000 messages/sec (limited by DB writes)

Consumer-side partitions needed = 100,000 / 5,000 = 20 partitions
→ You need AT LEAST 20 partitions so you can run 20 consumers in parallel.

If producers can each push 10,000 msg/sec and you produce 100,000/sec:
Producer-side = 100,000 / 10,000 = 10 partitions

partitions = max(20, 10) = 20   → round up for headroom, say 24–30.
```

### The consumer-parallelism angle (most practical)

> **Partition count = the maximum number of consumers you'll ever want to run in parallel for one group.** Since consumers can't exceed partitions, pick partitions ≥ your peak desired parallelism, with headroom.

If you think you'll need 50 consumers at peak, you need **≥ 50 partitions** — even if today 10 is enough.

### Other factors that push the number

| Factor | Effect on partition count |
| --- | --- |
| Higher target throughput | More partitions |
| Peak consumer parallelism needed | More partitions (hard floor) |
| Future growth | Over-provision a bit (hard to reduce later) |
| Key cardinality / ordering | Enough partitions to spread keys, but all of one key stays on one partition |

### Costs of **too many** partitions (don't just crank it to 10,000)

| Cost | Why |
| --- | --- |
| **More open file handles & memory** | Each partition = segment files + buffers per broker |
| **Longer leader election / failover** | More partitions to reassign when a broker dies → longer unavailability |
| **Higher end-to-end latency** | More partitions to replicate; more overhead |
| **Rebalance time** | More partitions → slower consumer-group rebalances |
| **Producer memory** | A buffer per partition |

### Rules of thumb

- Start with something like **2–3× your expected peak consumer count**, rounded to a sensible number.
- Keep partitions per broker in the low thousands **cluster-wide**, not tens of thousands, unless you know what you're doing.
- **Over-provision slightly** because increasing later reshuffles key→partition mapping (ordering break) and you **cannot decrease**.
- If you need strict global ordering, you're stuck with **1 partition** (no parallelism) — usually you relax to per-key ordering instead.

### Plain-English: how many checkout lanes should the store build?

**Analogy: designing a supermarket.** Partitions are checkout lanes, and you're deciding how many to build *before opening day* (because you can add lanes later but never remove them without chaos). The question is really: **"at peak, how many cashiers do I want working at once?"**

The formula, in plain words:

```
partitions = max( T / P , T / C )

T = total shoppers per hour you must handle   (target throughput)
P = shoppers one lane can handle if the WRITE side is the limit (producer)
C = shoppers one cashier can ring up per hour (consumer)
```

You take the **bigger** of the two because you must satisfy whichever side needs more lanes.

Worked example, spelled out:

```
Need to handle:            100,000 messages/sec
One consumer can process:    5,000 messages/sec   (limited by its DB writes)

→ need 100,000 / 5,000 = 20 cashiers working at once
→ so you need AT LEAST 20 lanes (partitions), else you can't run 20 cashiers
→ round up for headroom → build ~24–30 lanes
```

#### Q: Why can't I just build 10,000 lanes to be safe?

Because empty lanes still cost money and slow you down. Each partition = open files, memory buffers, and replication work on every broker. Too many lanes means **slower failover** (more to reassign when a broker dies), **slower rebalances**, and **higher latency**. It's like a store with 10,000 checkout lanes and 12 customers — mostly overhead. Right-size to *peak desired parallelism + a bit of headroom*, not infinity.

#### Q: I only need 10 cashiers today — why over-provision to, say, 30?

Because **adding lanes later reshuffles the `hash(key) % N` mapping and breaks per-key ordering** (see [§4](#4-partitions--the-heart-of-kafka)), and you can **never reduce** lanes. So you pick for your *future peak*, not today's load. If you'll ever want 50 consumers at peak, build ≥ 50 partitions now.

#### Q: What if I need *strictly global* ordering (everything in one exact sequence)?

Then you're forced to **1 partition** — one lane, one cashier, no parallelism. That's a real cost, so teams almost always relax the requirement to **per-key ordering** (order within each user/account, but not across them), which lets them use many partitions.

---

## 11. Scaling Consumers & Concurrency

Two distinct levers — know the difference.

### Lever 1 — More consumer instances (horizontal, across processes/pods)

```
partitions = 12

Start: 4 consumers → each handles 3 partitions
Scale: 12 consumers → each handles 1 partition   ← MAX parallelism
Beyond: 13th consumer → IDLE (no partition to give it)
```

- Add consumer instances **up to the partition count**.
- This is the primary way to scale — add pods until consumers = partitions.
- **You cannot go past partition count.** To scale further, you must first **add partitions**.

```
Scaling ceiling:   max_consumers = partition_count
Want more?         increase partitions first, then add consumers.
```

### Lever 2 — More concurrency *inside* one consumer (threads)

A single consumer polls a batch, then processes records. You can process them with a **thread pool** to get parallelism without more partitions:

```
consumer.poll() → 500 records
  → hand records to a worker thread pool (e.g. 20 threads)
  → process in parallel
  → commit offsets only after the batch is safely processed
```

| When in-consumer concurrency helps | Caveat |
| --- | --- |
| Processing is **I/O-bound** (DB/API calls) and you're partition-limited | **Ordering within a partition is lost** if you parallelize its records |
| You can't add partitions right now | **Offset commit gets tricky** — don't commit an offset until *all* earlier records are done, or you'll lose messages on crash |
| One consumer box has spare CPU | Poison/slow record can stall the batch commit |

> **Frameworks:** Spring Kafka `concurrency=N` runs N consumer threads (each owning partitions) within one app instance — effectively Lever 1 packaged inside a process. Libraries like **Confluent's Parallel Consumer** do Lever 2 (key-level parallelism beyond partition count while preserving per-key order).

### How to decide how much to scale — a procedure

```
1. Measure current consumer lag and per-consumer throughput.
2. Compute required parallelism:
       needed_consumers = incoming_rate / per_consumer_throughput
3. If needed_consumers <= partition_count:
       scale consumer instances up to needed_consumers.
4. If needed_consumers > partition_count:
       (a) add partitions (plan for ordering impact), THEN add consumers, OR
       (b) add in-consumer concurrency (thread pool) if ordering allows, OR
       (c) speed up per-record processing (batching, async, caching) to raise per_consumer_throughput.
5. Re-measure lag; iterate until lag is flat/declining and oldest-message age is within SLA.
```

### Example

```
Incoming: 30,000 msg/sec
Per consumer: 2,000 msg/sec
needed_consumers = 30,000 / 2,000 = 15

If topic has 20 partitions → run 15 consumers. ✅ (5 partitions double-up, fine)
If topic has 10 partitions → only 10 consumers possible → max 20,000/sec → LAG GROWS.
   → add partitions to 20+, or add thread-pool concurrency, or make processing faster.
```

### Plain-English: two different ways to add muscle

**Analogy: a warehouse unloading trucks (partitions), and you need to unload faster.** There are two totally different levers, and mixing them up causes real bugs.

**Lever 1 — hire more workers (more consumer instances).** Each worker grabs whole trucks (partitions). Simple and safe, but you can't have more workers than trucks — extras stand around.

```
12 trucks (partitions):
  4 workers  → each unloads 3 trucks
  12 workers → each unloads 1 truck   ← maxed out
  13th worker → idle (no truck for them)
```

**Lever 2 — one worker with a team of helpers (threads inside one consumer).** A single worker grabs a truck, then hands boxes to a thread pool to unpack in parallel. This gets you *past* the truck limit — but you lose the guarantee that boxes come out in order, and saving your place (committing offsets) gets tricky.

```java
// Lever 2: one consumer, fan the batch out to a thread pool
var records = consumer.poll(Duration.ofMillis(100));   // e.g. 500 records from my partitions
var futures = records.stream()
    .map(r -> threadPool.submit(() -> process(r)))     // unpack boxes in parallel
    .toList();
futures.forEach(Future::get);   // WAIT for ALL to finish...
consumer.commitSync();          // ...only THEN move the bookmark (else a crash loses in-flight work)
```

| Lever | Use when | The catch |
| --- | --- | --- |
| **1. More instances** | You still have spare partitions | Hard ceiling: can't exceed partition count |
| **2. Threads inside one consumer** | Work is I/O-bound and you're partition-limited | **Ordering within the partition is lost**; commit only after *all* records in the batch finish |

#### Q: If threads let me beat the partition limit, why bother with partitions/instances?

Because Lever 1 (more instances) keeps **per-partition ordering** and simple offset handling for free — Kafka does the coordination. Lever 2 buys extra throughput but *you* now own the hard problems (ordering, safe commit, a slow record stalling the batch). Reach for Lever 1 first; use Lever 2 only when you've hit the partition ceiling and can't add partitions right now.

#### Q: What's the actual decision procedure?

Compute `needed_consumers = incoming_rate / per_consumer_throughput`. If that's ≤ partition count, just scale instances to it. If it's more, you must **add partitions**, **add threads (if ordering allows)**, or **make each record faster** (batch DB writes, async, caching). Then re-measure lag and repeat.

---

## 12. Consumer Lag — What It Is & How to Handle It

**Consumer lag** = how far behind a consumer group is on a partition.

```
Lag (per partition) = log_end_offset − committed_consumer_offset

Example:
  Latest offset in partition   = 100,000   (producer wrote up to here)
  Consumer committed offset    =  95,000   (consumer processed up to here)
  Lag                          =   5,000   messages waiting
```

Total group lag = sum of lag across all its partitions.

> Users experience lag as **delayed processing** — fine for analytics/marketing, dangerous for OTP, payments, or real-time alerts.

### Two ways to measure lag (track both)

| Metric | Meaning |
| --- | --- |
| **Offset lag** | Number of messages behind (5,000) |
| **Time lag / oldest-message age** | How *old* the oldest unprocessed message is |

> 5,000 lag that clears in 2 seconds is fine. 5,000 lag where the oldest message is **30 minutes old** is an incident. **Alert on oldest-message age**, not just count.

### Why lag happens

| Cause | Explanation |
| --- | --- |
| **Too few consumers** | Fewer consumers than partitions → some consumers do double work |
| **Too few partitions** | Can't add more consumers → hard parallelism ceiling |
| **Slow processing** | Heavy per-message work (slow DB writes, external API calls) |
| **Slow downstream** | The DB/service the consumer writes to is the real bottleneck |
| **Producer spike** | Flash sale / batch job floods the topic faster than consumers drain |
| **Poison messages** | A record that always fails → infinite retry blocks the partition |
| **Long GC pauses / big `max.poll.records`** | Consumer stalls, misses heartbeats, triggers rebalance |
| **Frequent rebalances** | Every rebalance pauses consumption |

### Is lag always bad?

| Lag pattern | Verdict |
| --- | --- |
| Temporary spike, catches up in minutes | ✅ Normal |
| Continuously growing | ❌ Under-provisioned — add capacity |
| High-priority topic (OTP/payments) | ❌ Never acceptable |
| Low-priority topic (marketing/analytics) | ⚠️ Often tolerable |

### How to handle lag — the full toolkit

| Mitigation | Detail |
| --- | --- |
| **1. Scale consumers** | Add consumer instances **up to partition count** ([§11](#11-scaling-consumers--concurrency)) |
| **2. Increase partitions** | Raises the parallelism ceiling; plan ahead (ordering impact, can't undo) |
| **3. Add in-consumer concurrency** | Thread-pool the processing if ordering permits |
| **4. Speed up processing** | Batch DB writes, async I/O, cache lookups, drop unnecessary work in the hot path |
| **5. Batch smartly** | Tune `max.poll.records` / `fetch.min.bytes`; process & commit in batches |
| **6. Separate topics by priority** | OTP on `notifications-critical`, marketing on `notifications-low` → campaign lag never delays OTP |
| **7. Dedicated consumer pools** | Critical consumers only read critical topics |
| **8. Rate-limit / throttle producers** | Campaign pushes 50k/min instead of 10M instantly → smooths the spike |
| **9. DLQ poison messages** | After N retries, route the bad record to a dead-letter topic so it stops blocking the partition |
| **10. Tune consumer timeouts** | Raise `max.poll.interval.ms` if processing a batch legitimately takes long; avoid needless rebalances |
| **11. Backpressure downstream** | If the DB is the bottleneck, scaling consumers just moves the problem — fix the DB (batching, replicas, sharding) |

### The critical gotcha: scaling consumers can just move the bottleneck

```
Lag high → add 100 consumers → they all hammer the DB → DB overloaded → still slow
```

> Always ask **"what is the actual bottleneck?"** — the consumer CPU, the partition ceiling, or the downstream DB/API. Scaling consumers only helps if consumers are the bottleneck **and** you have spare partitions.

### Interview one-liner

> "I monitor consumer lag **and oldest-message age**. To fix lag I scale consumers up to the partition count, add partitions if I've hit the ceiling, speed up per-message processing (batching/async), separate critical from low-priority topics with dedicated consumer pools, throttle bursty producers, and DLQ poison messages. And I check whether the real bottleneck is downstream — adding consumers won't help if the DB is the limit."

### Plain-English: lag is the pile of unread mail

**Analogy: a mailbox filling up while you're on vacation.** The mail carrier (producer) keeps delivering; you (consumer) read when you can. **Lag = the stack of letters you haven't opened yet.**

```
Latest letter delivered  = #100,000   (producer wrote up to here)
Last letter you opened   =  #95,000   (you processed up to here)
Lag                      =    5,000    unopened letters
```

The crucial insight: **the size of the pile matters less than how *old* the oldest letter is.**

- 5,000 letters you'll clear in 2 seconds → totally fine.
- 5,000 letters where the oldest has been sitting **30 minutes** → that's an incident (especially for OTPs, payments, alerts).

That's why you **alert on oldest-message age**, not just the count.

```
# monitor both, from a consumer-group lag tool (e.g. kafka-consumer-groups.sh):
offset lag          = 5000              # how many behind
oldest-message age  = 30 min            # how STALE the front of the queue is  ← alert on this
```

#### Q: Is lag always bad?

No. Lag is only bad if it's **growing and not catching up**, or if it's on a **latency-critical** topic. A temporary spike during a flash sale that drains in a few minutes is normal and healthy — it's Kafka doing its job absorbing a burst. Steady, ever-climbing lag means you're **under-provisioned**.

#### Q: My lag is high — I'll just add 100 consumers, right?

This is *the* classic trap. Adding consumers only helps if **the consumers themselves are the bottleneck** *and* you have spare partitions. If the real limit is a slow downstream database, 100 consumers just means 100 clients hammering an already-overloaded DB — **you moved the bottleneck, you didn't fix it.**

```
Lag high → add 100 consumers → all 100 hammer the same DB → DB melts → still slow ✗
```

Always ask **"what is the actual bottleneck?"** — consumer CPU, the partition ceiling, or the downstream DB/API — before scaling. The fix for a DB bottleneck is batching writes, read replicas, or sharding — not more consumers.

---

## 13. Rebalancing

When consumers **join or leave** a group (deploy, crash, scale), Kafka **reassigns partitions** across the current members. This is a **rebalance**.

```
Before:  A→[P0,P1]  B→[P2,P3]
B crashes →
After:   A→[P0,P1,P2,P3]      (rebalance reassigns B's partitions to A)
```

- During a rebalance, **consumption pauses** ("stop-the-world" in classic protocol) → a burst of lag.
- Triggered by: consumer join/leave, `max.poll.interval.ms` exceeded (consumer took too long → considered dead), session timeout.

| Config | Role |
| --- | --- |
| `session.timeout.ms` | If no heartbeat within this, consumer is considered dead |
| `heartbeat.interval.ms` | How often consumer pings the group coordinator |
| `max.poll.interval.ms` | Max time between `poll()` calls before the consumer is kicked (slow processing kills you here) |

> **Modern fix:** **cooperative / incremental rebalancing** (and static group membership via `group.instance.id`) avoids reassigning everything at once and prevents needless rebalances on quick restarts.

### Plain-English: reshuffling the delivery routes when the crew changes

**Analogy: your delivery crew reassigning routes.** When a driver joins (you deployed a new instance), quits (crashed), or is presumed lost (stopped checking in), the crew stops and **re-divides all the routes** among whoever's left. That reshuffle is a **rebalance**.

The pain: in the classic protocol, it's **stop-the-world** — *everyone* drops their routes and waits while the new assignment is worked out. During those seconds, **nobody is delivering**, so unread mail (lag) spikes.

```
Before:  A → [P0, P1]    B → [P2, P3]
B crashes → REBALANCE (everyone pauses) →
After:   A → [P0, P1, P2, P3]     ← A picks up B's routes; consumption resumed
```

What triggers a rebalance — and the timeouts that govern it:

```
group.instance.id        # give each instance a stable ID → quick restart ≠ "new member" → no needless rebalance
session.timeout.ms       # no heartbeat within this → "driver is dead" → rebalance
heartbeat.interval.ms    # how often the consumer pings "I'm alive"
max.poll.interval.ms     # took too long between poll() calls → "driver stuck" → kicked → rebalance
```

#### Q: Why does slow processing cause a rebalance? I never left the group.

Because Kafka can't tell "busy" from "dead." If you don't call `poll()` again within `max.poll.interval.ms` (because one batch took too long to process), the group coordinator assumes you crashed and **kicks you** — triggering a rebalance and a lag burst. Fixes: process faster, lower `max.poll.records` so each batch is smaller, or raise `max.poll.interval.ms` if the work legitimately takes long.

#### Q: How do I avoid a rebalance storm on every deploy/restart?

Two modern features: **static membership** (`group.instance.id`) lets a quickly-restarting instance rejoin *as the same member*, so its routes aren't reshuffled; and **cooperative/incremental rebalancing** moves only the *few* partitions that must change hands instead of making everyone drop everything. Together they turn rolling deploys from "storm" into "barely noticeable."

---

## 14. Retention, Compaction & Cleanup

Kafka keeps messages even after they're read. Two cleanup policies:

### Time/size retention (default)

```
retention.ms   = 604800000   # keep 7 days
retention.bytes = ...          # or cap by size per partition
```

Old segments are deleted once past the limit — regardless of whether anyone read them.

### Log compaction (`cleanup.policy=compact`)

Keeps only the **latest value per key** (like a changelog / snapshot). Great for "current state" topics.

```
Before compaction:  (user1, A) (user2, X) (user1, B) (user1, C)
After compaction:   (user2, X) (user1, C)      ← only newest per key kept
```

| Policy | Use for |
| --- | --- |
| `delete` (time/size) | Event streams, logs — most topics |
| `compact` | Latest-state-per-key (e.g. `user → current profile`) |
| `compact,delete` | Both |

### Plain-English: two ways to keep the notebook from overflowing

**Analogy: cleaning out records you no longer need.** Kafka gives you two very different cleanup styles.

**Style 1 — `delete` (time/size retention): the newspaper recycling bin.** Keep the last 7 days of papers; anything older gets recycled, *whether or not anyone read it*. This is the default and fits almost everything (event streams, logs).

```
retention.ms    = 604800000   # keep 7 days, then auto-delete old segments
retention.bytes = 10737418240 # OR cap each partition at ~10 GB, whichever hits first
```

**Style 2 — `compact` (log compaction): a contacts app, not a chat log.** For each **key**, keep only the **most recent value** and throw away older versions. Perfect for "current state" topics like `user_id → current profile`.

```
cleanup.policy = compact

Before compaction:  (user1, "name=Al")  (user2, "X")  (user1, "name=Bo")  (user1, "name=Cy")
After  compaction:  (user2, "X")  (user1, "name=Cy")     ← only the newest per key survives
```

#### Q: When would I ever want `compact` instead of `delete`?

When the topic represents **the latest state of something**, not a history of events. Example: a topic where each message is "user 123's current profile." You don't care about their 5 old profiles — just the newest. Compaction lets a brand-new consumer replay the topic from the start and rebuild the *current* state of every user, without wading through obsolete versions. (This is exactly how Kafka Streams / KTables back their state.)

#### Q: Does retention care whether consumers have read the messages?

**No — and this surprises people.** Time/size retention deletes by *age or size*, full stop. If a consumer group is down longer than the retention window, messages can be deleted **before it ever reads them** → permanent gap. So set retention comfortably longer than your worst-case consumer downtime, and monitor oldest-message age ([§12](#12-consumer-lag--what-it-is--how-to-handle-it)).

---

## 15. Key Configs Cheat Sheet

### Producer

| Config | Purpose |
| --- | --- |
| `acks` | `all` for durability |
| `enable.idempotence` | `true` — dedup retries |
| `retries` / `delivery.timeout.ms` | Retry transient failures |
| `batch.size` / `linger.ms` | Batch for throughput |
| `compression.type` | `lz4`/`zstd` throughput win |

### Consumer

| Config | Purpose |
| --- | --- |
| `group.id` | Which consumer group |
| `enable.auto.commit` | `false` → commit manually after processing (at-least-once) |
| `auto.offset.reset` | `earliest` / `latest` when no committed offset |
| `max.poll.records` | Batch size per poll (tune for throughput vs latency) |
| `max.poll.interval.ms` | Raise if processing a batch is slow (avoid rebalance) |
| `fetch.min.bytes` / `fetch.max.wait.ms` | Batch fetches for efficiency |

### Topic

| Config | Purpose |
| --- | --- |
| `partitions` | Parallelism ceiling ([§10](#10-how-to-decide-partition-count)) |
| `replication.factor` | Fault tolerance (usually 3) |
| `min.insync.replicas` | Durability floor (usually 2) |
| `retention.ms` | How long to keep messages |
| `cleanup.policy` | `delete` or `compact` |

---

## 16. Kafka vs Other Queues

| | **Kafka** | **RabbitMQ** | **AWS SQS** |
| --- | --- | --- | --- |
| Model | Distributed **log** (pull, replayable) | Message **broker** (push, routing) | Managed queue |
| Throughput | Very high (millions/sec) | Moderate–high | High (managed) |
| Message retention | Kept until retention expires (**replay**) | Deleted on ack | Deleted on delete (up to 14 days) |
| Ordering | Per-partition | Per-queue | FIFO queues only |
| Fan-out | Many consumer groups, each full copy | Exchanges/bindings | SNS+SQS for fan-out |
| Best for | Event streaming, log pipelines, high throughput, replay | Complex routing, per-message RPC-ish workflows | Simple managed queue, zero ops |

> **When to pick Kafka:** high throughput, multiple independent consumers, event replay, stream processing, ordering per key. **When not to:** you just need a simple managed task queue with low volume → SQS/RabbitMQ is less operational overhead.

### Plain-English: log vs. to-do list vs. mailroom

**Analogy: three different tools people lump together as "queues."**

- **Kafka = a shared logbook.** Everyone reads it, entries stay put until they age out, you can re-read the past. Built for *firehose volume* and *many readers*.
- **RabbitMQ = a smart mailroom.** A clerk *routes* each letter to the right recipient based on rules, hands it over, and the letter is **gone once accepted**. Great for complex "send this to exactly the right worker" routing.
- **AWS SQS = a managed drop-box.** Dead simple, someone else runs it, a message disappears once a worker deletes it. Great when you want zero ops and just need a task queue.

The single biggest difference: **Kafka doesn't delete on read; the other two do.** That's why replay and fan-out are natural in Kafka and awkward elsewhere.

```
Kafka:     write → [ logbook keeps it 7 days ] → read, re-read, many groups read independently
RabbitMQ:  write → [ router → queue ] → worker acks → message DELETED
SQS:       write → [ managed queue ] → worker deletes → message GONE
```

#### Q: RabbitMQ can also fan out — how is Kafka different?

RabbitMQ fans out by **copying** a message into several queues at publish time; once each consumer acks, its copy is gone (no built-in replay). Kafka fans out because the **one** log stays put and each consumer group keeps its **own bookmark** — so a group added *next month* can still read everything from the start (within retention). Kafka's fan-out is "many readers of one durable log"; RabbitMQ's is "many disposable copies."

#### Q: When is Kafka the *wrong* choice?

When you have **low volume** and just need a **simple task queue** with minimal operations. Kafka is a distributed system with brokers, partitions, and retention to manage — overkill if you send a few thousand messages a day and don't need replay, ordering, or multiple independent consumers. A managed SQS/RabbitMQ is far less to run. Don't reach for Kafka out of habit; reach for it when you need **throughput, replay, ordering-per-key, or fan-out**.

---

## 17. Failure Scenarios & Gotchas

| Scenario | What happens / fix |
| --- | --- |
| **Broker dies** | Leader re-elected from ISR; consumers/producers reconnect to new leader. `replication.factor≥3` + `min.insync.replicas=2` → no data loss |
| **Consumer crashes** | Rebalance reassigns its partitions; resumes from last committed offset (may reprocess → be idempotent) |
| **Slow consumer** | Exceeds `max.poll.interval.ms` → kicked → rebalance → lag burst. Raise the timeout or process faster |
| **Poison message** | Always fails → blocks the partition forever. Retry N times → **DLQ** it |
| **Duplicate delivery** | At-least-once means dups on retry → **consumers must dedup** (idempotency key) |
| **Reordering** | Only guaranteed per partition; parallel processing within a partition reorders. Key by entity for ordering |
| **Too many partitions** | Slow failover, high latency, memory pressure. Right-size ([§10](#10-how-to-decide-partition-count)) |
| **Rebalance storms** | Frequent restarts/timeouts → constant pauses. Use static membership + cooperative rebalancing |
| **Hot partition** | One key gets all traffic (e.g. one huge tenant) → that partition's consumer is overloaded. Use a better key or sub-partition the hot key |

### Plain-English: the greatest hits of "why is production on fire?"

Every scenario above is really one of a few recurring themes. Here's the intuition behind the scary ones.

**Poison message — the letter that jams the printer.** One record always throws an exception. Naive retry loops forever on it, and because a partition is read **in order**, everything *behind* it is stuck too — one bad letter freezes the whole lane.

```java
// Fix: retry a few times, then set it aside (dead-letter queue) so the line keeps moving.
try {
    process(record);
} catch (Exception e) {
    if (record.retries() >= MAX_RETRIES) {
        deadLetterTopic.send(record);   // park the bad one → partition unblocks
    } else {
        throw e;   // let it retry
    }
}
```

**Duplicate delivery — the "did that go through?" double-tap.** At-least-once means a redelivery can happen after a crash. The record isn't corrupt; it's just *repeated*. The fix isn't in Kafka — it's making **your** handler idempotent (dedup by a business key), exactly as in [§8](#8-delivery-semantics).

**Reordering — parallel unpacking scrambles the sequence.** Kafka only guarantees order *within a partition*, and only if you read it single-threaded. The moment you thread-pool a partition's records ([§11](#11-scaling-consumers--concurrency) Lever 2), "cancelled" can finish before "placed." Key by entity for ordering, and don't parallelize a partition's records if their order matters.

#### Q: A broker died — did I lose data or messages?

If you set the durability combo (`replication.factor=3`, `acks=all`, `min.insync.replicas=2` — see [§9](#9-replication--durability)), **no**. A follower is promoted to leader from the ISR, and producers/consumers transparently reconnect to the new leader. You lose *availability* for a few seconds (failover), not *data*. Without that combo, a broker death can lose whatever lived only on it.

#### Q: Everything's "slow" and lag is climbing — where do I even start?

Ask the one diagnostic question from [§12](#12-consumer-lag--what-it-is--how-to-handle-it): **what is the actual bottleneck?** Walk it in order: (1) Am I at the partition ceiling (consumers = partitions)? (2) Is each consumer CPU-bound, or waiting on a slow DB/API downstream? (3) Are frequent rebalances pausing me? The fix is completely different for each — adding consumers only helps case (1), and actively *hurts* if the real limit is a downstream DB.

---

## 18. Interview Cheat Sheet

> **"What is Kafka in one sentence?"**
>
> "A distributed, durable, append-only commit log — producers append events to partitioned topics, and independent consumer groups read them at their own pace, with replay and high throughput."

> **"What is a partition and why does it matter?"**
>
> "A partition is one ordered append-only log; a topic is split into many. Partitions are the unit of **parallelism** (each is consumed by one consumer per group) and **ordering** (guaranteed only within a partition). They cap how many consumers can work in parallel."

> **"How do you decide partition count?"**
>
> "`partitions = max(T/P, T/C)` — target throughput over per-partition producer/consumer throughput. Practically, set partitions ≥ your peak desired consumer parallelism, with headroom, because you can't exceed partitions with consumers and you can't reduce partitions later without breaking key ordering."

> **"How do you scale consumers?"**
>
> "Add consumer instances **up to the partition count** — that's the ceiling. Beyond that, add partitions first, or add in-consumer thread-pool concurrency if ordering allows, or make per-message processing faster."

> **"How much concurrency should you add?"**
>
> "`needed_consumers = incoming_rate / per_consumer_throughput`. If that's ≤ partitions, scale instances to it. If it's more, raise partitions or add thread-level concurrency. Then re-measure lag and iterate."

> **"What is consumer lag and how do you handle it?"**
>
> "Lag = latest offset − committed offset per partition. I watch lag **and oldest-message age**. Fix by scaling consumers to partition count, adding partitions, speeding up processing (batch/async), separating critical vs low-priority topics with dedicated pools, throttling bursty producers, and DLQ-ing poison messages — after checking the real bottleneck isn't downstream."

> **"How does Kafka guarantee ordering?"**
>
> "Only within a partition. Route related events to the same partition using a key (e.g. `user_id`). No global ordering unless you use a single partition (which kills parallelism)."

> **"At-least-once vs exactly-once?"**
>
> "Default practical mode is at-least-once + idempotent consumers. Exactly-once exists via idempotent producer + transactions, but only within Kafka; once you touch an external DB you still need idempotency."

> **"How is durability guaranteed?"**
>
> "`replication.factor=3`, `acks=all`, `min.insync.replicas=2` — a write is acknowledged only after 2+ replicas have it, so one broker can fail with zero data loss."

> **"What causes rebalances and why care?"**
>
> "Consumers joining/leaving or timing out. Consumption pauses during a rebalance → lag spikes. Mitigate with static membership + cooperative rebalancing and by not exceeding `max.poll.interval.ms`."

---

## 19. Final Takeaways

- **Kafka = distributed append-only log** — durable, replayable, high-throughput; messages aren't deleted on read.
- **Partition = ordering + parallelism unit.** Order is guaranteed **only within a partition**; key your messages (e.g. `user_id`) to keep an entity's events ordered.
- **Consumer group** shares partitions; **each partition → exactly one consumer per group** → **max consumers = partition count.**
- **Deciding partitions:** `max(T/P, T/C)`, but really "≥ peak consumer parallelism, with headroom." You can increase (breaks key ordering) but **never decrease** — plan ahead.
- **Scaling consumers:** add instances up to partition count; beyond that add partitions, in-consumer thread concurrency, or faster processing.
- **Lag = latest − committed offset.** Watch **oldest-message age**, not just count. Fix via more consumers (≤ partitions), more partitions, faster processing, priority topics, producer throttling, and DLQs — after finding the true bottleneck (often the downstream DB).
- **At-least-once + idempotent consumers** is the practical default; exactly-once is Kafka-internal only.
- **Durability:** `replication.factor=3` + `acks=all` + `min.insync.replicas=2`.
- **Rebalances pause consumption** — minimize them (static membership, cooperative protocol, sane timeouts).
- **Scaling consumers can shift the bottleneck to the DB** — Kafka absorbs spikes, but downstream must keep up.

### Related notes

- [Notification System — System Design](../system-design/notification-system-design.md) — Kafka in a real async pipeline: topics per channel/priority, lag handling, DB bottlenecks
- [Idempotency](idempotency.md) — required for safe at-least-once consumers
- [Outbox & Saga Patterns](outbox-and-saga.md) — reliably getting events *into* Kafka without dual-write loss
- [Scaling Architecture](scaling-architecture.md)
