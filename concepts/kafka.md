# Apache Kafka — From Zero to Deep

> **What it is in one line:** Kafka is a **distributed, durable, append-only log** you can publish messages to and have many independent consumers read from — built for **high throughput**, **replay**, and **decoupling** producers from consumers. Think "a giant, replicated, ordered logbook that never forgets (until you tell it to)."
>
> This note assumes **zero prior knowledge** and builds up to the hard operational questions: **how to size partitions, how to scale consumers and concurrency, and how to handle consumer lag.**

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

---

## 8. Delivery Semantics

| Guarantee | How | Cost |
| --- | --- | --- |
| **At-most-once** | Commit offset **before** processing | May lose messages; no dup |
| **At-least-once** ✅ default in practice | Commit **after** processing | May reprocess → **consumers must be idempotent** |
| **Exactly-once (EOS)** | Idempotent producer + **transactions** (or Kafka Streams) | Complex, some overhead; only within Kafka→Kafka flows |

> **Practical answer:** almost everyone runs **at-least-once + idempotent consumers** (dedup by a business key). True exactly-once is real in Kafka (transactions), but it only spans Kafka reads/writes — the moment you call an external DB/API, you're back to needing idempotency.

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
