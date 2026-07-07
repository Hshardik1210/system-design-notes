# Ad Click Aggregation — System Design (Stream Analytics)

> **Core challenge:** ingest a **firehose of click events** (millions/sec), aggregate them into **near-real-time counts** (clicks per ad per minute) for dashboards + billing, while handling **duplicates, out-of-order/late events, hot keys, and huge scale** — the canonical **stream-processing** problem. Billing needs **accuracy**; dashboards need **freshness**.

> **How to read this doc:** each section has the dense interview summary first, then a **Plain-English** deep dive (analogies, annotated Java, and the exact confusions that come up while learning). Skim the summaries for revision; read the plain-English parts to actually understand.

---

## Contents

- [1. Mental Model](#1-mental-model)
- [2. Requirements](#2-requirements)
- [3. Capacity Estimation](#3-capacity-estimation)
- [4. Pipeline Architecture](#4-pipeline-architecture)
- [5. Ingestion](#5-ingestion)
- [6. Aggregation (windowing)](#6-aggregation-windowing)
- [7. Exactly-Once, Dedup & Late Events](#7-exactly-once-dedup--late-events)
- [8. Hot Keys & Skew](#8-hot-keys--skew)
- [9. Lambda vs Kappa (accuracy vs freshness)](#9-lambda-vs-kappa-accuracy-vs-freshness)
- [10. Data Model](#10-data-model)
- [11. Sequences](#11-sequences)
- [12. Consistency & Failure](#12-consistency--failure)
- [13. Design Patterns (that can be used)](#13-design-patterns-that-can-be-used)
- [14. Interview Cheat Sheet](#14-interview-cheat-sheet)
- [15. Final Takeaways](#15-final-takeaways)

---

## 1. Mental Model

```
click event → ingest (Kafka) → stream aggregator (windowed counts) → store rollups → query (dashboards/billing)
```

You almost never store-and-recount raw clicks per query — you **pre-aggregate as a stream** into time buckets and serve those. (Same family as YouTube view counting.)

### Plain-English: what problem are we even solving?

Imagine you run Google Ads / Facebook Ads. Advertisers pay you **every time someone clicks their ad** (e.g. Nike pays ₹5 per click). Reality:

- **Millions of ads** running at once.
- People click them **all over the world, constantly** — **millions of clicks per second**.
- You must **count** these clicks because:
  1. **Billing** — charge Nike the exact right amount. Over-count = overcharge (lawsuit). Under-count = lost money.
  2. **Dashboards** — Nike's team wants to see "my ad got 50,000 clicks in the last 10 minutes" *right now* to know if the campaign works.

So the whole system is a giant, super-fast **click counter**. Everything else is just "how do we count clicks correctly when there are billions of them."

### Plain-English: why not just use a normal database?

First instinct: make a table, add a row per click, `SELECT COUNT(*)` to count. Why it explodes:

- **Too many writes** — 10B clicks/day ≈ 115k/sec avg, peaks 3–5×. MySQL/Postgres can do a few thousand row-updates/sec, not hundreds of thousands. It melts.
- **Counting is too slow** — counting billions of rows per query takes minutes; thousands of advertisers query at once. Dead.

**Key insight that drives the entire design:**

> **Don't store every click and count later. Count as clicks arrive, and store only the running totals (pre-aggregation).**

Instead of 50,000 rows for 50,000 clicks, store **one** row: "Ad #123, at 2:05 PM, got 50,000 clicks."

#### The tempting shortcut: `ad_id, count` table with `count++`

```sql
-- table
ad_id | count
123   | 50000

-- on every click:
UPDATE clicks SET count = count + 1 WHERE ad_id = 123;
```

Clean, and fine for a small website. Why big systems can't do it:

| Wall | Problem |
| --- | --- |
| **1. Write throughput** | Every `UPDATE` = find row, lock, change, write to disk, unlock. A single DB handles a few thousand/sec; we need millions/sec. Melts. |
| **2. Hot row (the killer)** | 50,000 people clicking the **same ad** all update the **same row**. A DB lets only one update a row at a time (a *lock*), so everyone queues for ONE row. This is the **hot key** problem (see §8). |
| **3. Race conditions** | Two clicks read count=100, both write 101 → one click vanishes. `count=count+1` avoids this specific bug, but caching/multiple servers reintroduce it. Wrong count = wrong money. |
| **4. Lose all breakdowns** | One `count` can't answer "last 10 min?", "per hour/day?", "India vs US?". You need counts broken down by time (and region), not one lifetime total. |

**So instead:** (1) write the click down somewhere cheap/fast (a log) — no math yet; (2) separately, a counting machine reads big **batches** and does the math in memory, writing **one** summary row. That "log cheaply → count in batches" split is exactly Kafka (the log) + the stream processor (the counting machine) — detailed in §4.

---

## 2. Requirements

**Functional**
- Record ad **click events** (ad_id, user, ts, ...); aggregate counts per ad per time window (minute/hour/day).
- Query aggregates (dashboards) + feed **billing** (accurate totals); drill-down by region/campaign.
- Filter **fraud/bot** clicks.

**Non-functional**
- **Massive write throughput** (millions/sec); **near-real-time** dashboards (seconds); **accurate** billing (reconcile); handle **duplicates + late/out-of-order** events; scalable, fault-tolerant.

---

## 3. Capacity Estimation

```
Clicks ~ 10B/day → ~115k/sec avg, peaks 2–5×
Raw event ~ 100–200 bytes → 10B × 150 B ≈ ~1.5 TB/day raw → cheap storage (S3/lake)
Aggregates: ad_id × minute → far smaller (few rows per ad per minute) → OLAP store, query-friendly
Dedup state: seen click_ids within a window → bounded by window size (TTL)
```

> Two cost centers: the **ingest firehose** (Kafka + parallel processors) and **exact billing** (dedup + reconciliation). Aggregates are tiny vs raw.

---

## 4. Pipeline Architecture

```
Ad clients ─► Ingestion API ─► Kafka (partitioned by ad_id) ─► Stream processor (Flink/Spark/Kafka Streams)
                                                                    │  windowed aggregation (event time)
                                                                    ▼
                                              Aggregate store (OLAP: Druid/ClickHouse/Cassandra)
                                                                    │
                                                          Dashboards · Billing · Alerts
                              raw events also archived → S3 / data lake (audit + reprocess)
                              fraud filter stage before counting
```

- **Kafka** buffers the firehose + decouples ingest from processing; **partition by `ad_id`** for parallel aggregation + per-ad ordering.
- **Stream processor** (Flink is the standard) does windowed aggregation with **state + checkpoints** (exactly-once).
- **OLAP store** serves fast aggregate queries; **raw events archived** for reprocessing/audit.

### Plain-English: "write it down cheaply, then count in batches"

**Analogy used throughout: a busy restaurant kitchen with an order-ticket spike.**

#### Part A — The notepad: Kafka

When a click happens, don't count. Just **append it to a giant shared notepad** as fast as possible and move on. That notepad is **Kafka**.

> **Kafka is an append-only list.** Messages come in, Kafka tacks each onto the end, in order. Never edits the middle. Write, write, write, forever.

Why better than the DB `UPDATE` from §1:
- **Appending to the end is stupidly fast** — no find/lock/math/unlock. Millions/sec on one setup.
- **No fighting over one row** — everyone just adds to the end.

```
User clicks ad  →  small API server  →  append "{ad_id:123, time:2:05:03, ...}" to Kafka  →  done
```

Kafka = the **order-ticket spike**: waiters slam tickets on it (fast, no waiting); cooking (counting) happens later.

#### Part B — Splitting the notepad into lanes: partitions

One notepad = one machine = a limit. So Kafka splits it into **lanes** (**partitions**), each its own list on its own machine. Which lane does a click go to? **Split by `ad_id`** — all clicks for ad 123 → lane A, ad 456 → lane B.

```
click for ad 123 ─┐
click for ad 123 ─┼─►  Lane A:  [123][123][123][123]...
click for ad 123 ─┘

click for ad 456 ──►  Lane B:  [456][456]...
```

Why: (1) **Parallelism** — many lanes read/written at once by many machines. (2) **All clicks for one ad end up together in one lane** → the machine counting that ad sees *all* of its clicks without coordinating with others. (This same "one ad → one lane" is exactly why a viral ad becomes a hot key — see §8.)

#### Part C — The counting machine: the "stream processor"

Separately, machines read clicks off the lanes and count them = the **stream processor** (famous tool: **Flink**). "**Stream**" = a never-ending flow; clicks never stop, so this runs **forever**, updating counts as data arrives.

```
loop forever:
  1. Read next batch of clicks from its Kafka lane
  2. Add them up in memory:  "ad 123 → 50,000 so far this minute"
  3. Every so often, write the summary to the results DB
  4. Repeat
```

Huge win: read 50,000 clicks, do **one** write. Kitchen: the cook grabs a stack of tickets and notes "50 burgers this batch" once, instead of walking to the manager 50 times.

```
                                    ┌─ Lane A (ad 123) ─► Counter 1 ─┐
Clicks ─► API ─► Kafka (notepad) ───┼─ Lane B (ad 456) ─► Counter 2 ─┼─► Results DB ─► Dashboards / Billing
                                    └─ Lane C (ad 789) ─► Counter 3 ─┘
```

#### Q: Are counting machines just Kafka consumers in a loop? What triggers the write?

- **Yes** — a counting machine = a Kafka consumer running in a loop.
- **The write is triggered by TIME, not by hitting a count.** The consumer groups clicks by *which minute they belong to*. When the clock rolls past 2:05 (minute "done"), it writes "ad 123, 2:05, 50,000" — whether that's 50,000 or 3 or 2M. Count = whatever it happened to be; **time** triggers the write. (This "group by minute" = **windowing**, see §6.)

#### Q: Won't different instances each write their own row for the same ad at 2:05?

No — and this is the whole reason we partition by `ad_id`:

> **Each lane (partition) is read by exactly ONE consumer instance** (Kafka guarantees this).

Chain: all clicks for ad 123 → one lane → one instance. So **ad 123 is owned start-to-finish by a single instance.** No other instance sees its clicks → you can never get two instances writing "ad 123, 2:05." One row per `(ad, minute)`.

```
ad 123 clicks ─► Lane A ─► Instance 1  ─┐
ad 456 clicks ─► Lane B ─► Instance 1  ─┤   (Instance 1 owns lanes A & B)
ad 789 clicks ─► Lane C ─► Instance 2  ─┤   (Instance 2 owns lane C)
ad 999 clicks ─► Lane D ─► Instance 2  ─┘
```

- An instance can own **many** lanes/ads, but each ad belongs to **exactly one** instance.
- **More lanes than instances** (normal): each instance owns several lanes, keeps separate running totals per ad.
- **Instance dies:** Kafka **reassigns** its lanes to a survivor, which continues (how it continues without losing the count = **checkpoints**, see §7).
- **Subtlety — upsert:** sometimes a consumer emits a **partial** early ("123, 2:05 → 30,000") then more 2:05 clicks arrive. It doesn't make a new row; it **overwrites/updates** the same `(ad_id, window_start)` row to 50,000. That's **upsert** = update-or-insert. Still one row per `(ad, minute)`.

---

## 5. Ingestion

- **Ingestion API** validates + enriches (geo from IP, campaign lookup) and writes to Kafka.
- **Client batching**: SDKs batch clicks to reduce request overhead; each event carries a **client-generated `click_id`** (for dedup) + **event timestamp**.
- **Backpressure**: if the pipeline lags, Kafka buffers (its whole point); shed/queue at the edge only if necessary.
- **Fraud filter** stage (rate per user/IP, ML scoring) drops bots **before** counting so billing isn't inflated.

### Plain-English: what "enrich" means (and where "country" comes from)

The raw click carries an **IP address**, not "India." So early — at the **ingestion API, before Kafka** — we do IP → country (geo-IP lookup) and stamp country/campaign/device onto the event. **"Enrich"** = add useful fields before saving, so the counter downstream can group by them (see §6).

```
raw click {ad_123, ip: 49.36.x.x}
   → ingestion API enriches → {ad_123, 2:05, country: India, campaign: 77}
   → Kafka → counter adds 1 to the (ad_123, 2:05, India) bucket
```

---

## 6. Aggregation (windowing)

Aggregate counts over **time windows** keyed by `ad_id`.

| Window | Meaning |
| --- | --- |
| **Tumbling** | Fixed, non-overlapping (per-minute buckets) — most common for counts |
| **Sliding** | Overlapping (last 5 min, updated each min) |
| **Session** | Grouped by activity gaps |

```
key = (ad_id, minute_bucket)
aggregate: count(clicks), unique_users (HyperLogLog), sum(revenue)
→ emit rollup per window → upsert into OLAP store
```

- **Event time vs processing time:** aggregate by **event time** (when the click happened, from the event's timestamp), not arrival time — else late events land in the wrong bucket.
- **Watermarks:** track "we've probably seen all events up to time T" → know when to **close/emit** a window.
- **HyperLogLog** for cheap approximate unique counts (distinct users) without storing every id.
- **Roll up** minute → hour → day for cheaper long-range queries.

### Plain-English: counters, not event lists

#### Q: Are the 50k (or millions of) events held in memory as an ArrayList?

**No — the most important optimization.** We do **not** keep events. We keep only a **running count**.

Analogy: counting people entering a room — you don't write every name in a list; you keep a tally in your head (1, 2, 3...) and throw away each person after +1.

```
click for ad 123 arrives → counter[123] = counter[123] + 1 → THROW AWAY the click
```

In memory is a tiny **map (dictionary)**, not an ArrayList of events:

```
{
  (ad_123, minute_2:05): 50000,
  (ad_456, minute_2:05): 12,
  (ad_789, minute_2:05): 3400
}
```

Each entry = **one integer**. 50,000 clicks or 50 million clicks for one ad = still one integer. **Memory scales with the number of ads/combos, NOT the number of clicks.** When the minute closes, write out the handful of counters and clear them.

#### Q: If we only keep a counter, how do we know which click is from which country?

The country becomes **part of the key**. We don't keep one counter per ad — we keep one per **combination** we care about:

```
key = (ad_id, minute, country)
```

```
{
  (ad_123, 2:05, India):  30000,
  (ad_123, 2:05, US):     15000,
  (ad_123, 2:05, UK):      5000
}
```

A click bumps the **matching** counter. "Clicks from India?" → read the India bucket. "Total?" → sum India+US+UK = 50,000. Still no individual events stored, just finer-grained counters. (The country/campaign fields were added at ingestion — see §5 "enrich".)

#### Q: So `(ad_id, minute, country, campaign, ...)` creates a row per combo?

**Yes — one row per unique combination.**

```
ad_id | minute | country | campaign | clicks
123   | 2:05   | India   | 77       | 20000
123   | 2:05   | India   | 88       | 10000
123   | 2:05   | US      | 77       | 12000
123   | 2:05   | US      | 88       |  3000
123   | 2:05   | UK      | 77       |  5000
```

Why it stays manageable:
- **Only combos that actually got a click create rows.** 3 countries clicked → 3 rows, not 200.
- **Still a massive win.** 50,000 clicks → ~5 summary rows (vs 50,000 raw rows). Billions/day of raw (~1.5 TB) collapses to a tiny table.

**The real dial = cardinality** (number of *distinct combinations*), which is multiplicative:

```
rows per minute ≈ (active ads) × (countries) × (campaigns) × (other dimensions)
```

- Low-variety field like `device` (~3 values) → rows ×3. Fine.
- **High-variety** field like `user_id` (billions) → rows explode to ~one-per-click → **defeats the whole design.** **Never** put `user_id` (or exact timestamp, IP) in the aggregation key.
- Rule: **only low-cardinality dimensions in the key.** Also **roll up** minute → hour → day so long-range queries read few rows.

### Plain-English: windowing with code

A "window" = a time bucket (per-minute). The event/key model and the counting machine:

```java
// A click event (after the ingestion API enriched it with country/campaign)
class ClickEvent {
    String clickId;      // unique id per click (for dedup later)
    String adId;
    long   eventTimeMs;  // WHEN the click actually happened
    String country;
    String campaignId;
}

// The "combo" we count by = the map key.
// (ad_id, minute, country, campaign) — all the low-cardinality dimensions.
record AggKey(String adId, long minuteBucket, String country, String campaignId) {}
```

The counting machine (consumer loop) — keeps a **map of counters**, never a list of events:

```java
class ClickCounter {

    // THE in-memory state: key -> running count. Tiny. One number per combo.
    Map<AggKey, Long> counters = new HashMap<>();

    void run() {
        while (true) {                                  // loop forever = "streaming"
            List<ClickEvent> batch = kafka.poll();      // read next batch from our lane(s)

            for (ClickEvent c : batch) {
                long minute = c.eventTimeMs / 60_000;   // which minute bucket this click belongs to
                AggKey key = new AggKey(c.adId, minute, c.country, c.campaignId);

                // add 1 to the matching counter, then FORGET the event
                counters.merge(key, 1L, Long::sum);
            }

            flushClosedMinutes();                        // write out finished minutes
        }
    }
}
```

Windowing = writing out a minute when it "closes":

```java
    void flushClosedMinutes() {
        long currentMinute = System.currentTimeMillis() / 60_000;

        var it = counters.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            AggKey key = entry.getKey();
            long count = entry.getValue();

            // if this bucket's minute is over, write it out and remove from memory
            if (key.minuteBucket() < currentMinute) {
                db.upsert(key, count);   // "ad 123, 2:05, India, 77 -> 20000"
                it.remove();             // free the memory; minute is done
            }
        }
    }
```

- `db.upsert(...)` = insert or overwrite the same row → never a duplicate row.
- Closed minutes removed from the map → memory only holds the currently-open minute(s), doesn't grow forever.
- This fixed, non-overlapping minute block = a **tumbling window** (most common; see table above).

#### Event-time vs arrival-time (why `eventTimeMs`, not "now")

We bucket by **when the click happened** (`eventTimeMs`), NOT when it was read. If a 2:05 click arrives at 2:08 (phone offline):

```java
// GOOD — bucket by event time → counted in 2:05 (where it truly belongs)
long minute = c.eventTimeMs / 60_000;

// BAD — bucket by arrival time → counted in 2:08 (wrong! inflates 2:08, robs 2:05)
long minute = System.currentTimeMillis() / 60_000;
```

Event time keeps history accurate — and creates the puzzle: *if a 2:05 click can arrive at 2:08, when is it safe to declare 2:05 done?* → **watermarks** (§7).

---

## 7. Exactly-Once, Dedup & Late Events

Billing can't over/under-count.

| Problem | Handling |
| --- | --- |
| **Duplicate clicks** (retries, at-least-once Kafka) | Each event carries a unique **`click_id`**; **dedup** in the processor's keyed state store (with TTL per window) |
| **Late events** (mobile offline, network) | **Watermarks + allowed lateness**; update the window if within grace, else route very-late events to a **correction/batch** job |
| **Exactly-once counts** | Flink **checkpoints** + **transactional (two-phase-commit) sinks** (Kafka EOS / transactional OLAP write) → exactly-once aggregation |
| **Fraud/bots** | Filtering stage (rate limits, ML) **before** counting |
| **Reprocessing** | Raw events in S3 → **replay** to rebuild aggregates if logic changes / a bug is found |

### Plain-English: watermarks & late events

Dilemma: bucket by event time (§6), but a 2:05 click may arrive at 2:08. **When is it safe to finalize 2:05?**
- Flush too **early** → miss late clicks → undercount → underbill.
- Flush too **late** → stale dashboards, memory fills.

Compromise: **wait a fixed grace period** (e.g. 2 min) after a minute ends. The "how far behind we might be" marker = the **watermark**.

```java
@Component
public class ClickAggregator {

    private final Map<AggKey, Long> counters = new ConcurrentHashMap<>();

    // the watermark: "we believe we've seen all events up to this event-time"
    private volatile long watermarkMs = 0;

    private static final long ALLOWED_LATENESS_MS = 2 * 60_000; // grace: 2 minutes

    // @KafkaListener = "run this method for every batch of clicks from the topic"
    // This is the "consumer running in a loop" — Spring does the looping for you.
    @KafkaListener(topics = "clicks", groupId = "click-aggregator")
    public void onBatch(List<ClickEvent> batch) {
        long maxEventTime = watermarkMs;

        for (ClickEvent c : batch) {
            long minute = c.eventTimeMs / 60_000;
            AggKey key = new AggKey(c.adId, minute, c.country, c.campaignId);
            counters.merge(key, 1L, Long::sum);     // count + discard event

            maxEventTime = Math.max(maxEventTime, c.eventTimeMs);
        }

        // advance the watermark: newest event time seen, minus the grace period.
        // meaning: "everything older than this, we assume, has already arrived."
        watermarkMs = maxEventTime - ALLOWED_LATENESS_MS;
    }
}
```

> **Watermark = newest event time seen − grace period.** If newest click seen is 2:10 and grace is 2 min → watermark = 2:08 → "confident everything up to 2:08 has landed."

Flush on a timer, using the watermark to decide "done":

```java
    // @Scheduled = run automatically every 30s (Spring's timer). No manual loop.
    @Scheduled(fixedRate = 30_000)
    public void flushFinalizedWindows() {
        long wm = this.watermarkMs;

        counters.entrySet().removeIf(entry -> {
            AggKey key = entry.getKey();
            long windowEndMs = (key.minuteBucket() + 1) * 60_000;

            // only finalize a minute once the watermark has passed its end
            // (i.e. even late-arrivers have had their 2-minute grace to show up)
            if (windowEndMs <= wm) {
                db.upsert(key, entry.getValue());   // write final count for this minute
                return true;                         // remove from memory — done
            }
            return false;                            // still open, keep waiting
        });
    }
```

The event that shows up **too** late (after finalize + billing):

```java
    private void handlePossiblyLateEvent(ClickEvent c) {
        long windowEndMs = ((c.eventTimeMs / 60_000) + 1) * 60_000;

        if (windowEndMs > watermarkMs) {
            // still within grace → normal path, just count it
            AggKey key = new AggKey(c.adId, c.eventTimeMs / 60_000, c.country, c.campaignId);
            counters.merge(key, 1L, Long::sum);
        } else {
            // TOO late — window already finalized & billed.
            // Don't corrupt the live number; send it to a correction/batch job instead.
            lateEventTopic.send(c);   // fixed later by the nightly reconciliation
        }
    }
```

Timeline:

```
2:05 ──── clicks for minute 2:05 arrive ────► counted into (…,2:05,…)
2:06  minute 2:05 ENDS, but we keep it OPEN (grace period running)
2:07  a straggler 2:05 click arrives late → still counted (within grace) ✅
2:08  watermark passes 2:06 → minute 2:05 FINALIZED → written to DB, removed from memory
2:15  a 2:05 click crawls in now → TOO LATE → sent to correction job, not the live count
```

We **don't** retroactively edit a finalized/billed count; stragglers go to a **correction/batch job** and are fixed in nightly reconciliation (**Lambda**, see §9). Real tools (Flink) give watermarks + allowed lateness built-in; here we hand-rolled them to see the mechanics.

### Plain-English: deduplication & exactly-once

Two different "counted twice" problems (people mix them up):

| Problem | Cause | Fix |
| --- | --- | --- |
| **Duplicate clicks** | Same click enters twice (network **retries**; Kafka is at-least-once) | **Dedup by `click_id`** |
| **Reprocessing after a crash** | Consumer dies, re-reads clicks it already counted / loses memory | **Exactly-once** (checkpoints + offsets + idempotent sink) |

#### Problem 1 — Duplicate clicks → dedup by `click_id`

Kafka's normal guarantee is **at-least-once** (rather deliver twice than lose one), so duplicates are *expected*. Every click carries a **unique `click_id`** generated once at the source (both copies share it). The consumer remembers seen ids and skips repeats.

```java
@Component
public class DedupingAggregator {

    private final Map<AggKey, Long> counters = new ConcurrentHashMap<>();

    // remembers click_ids already counted, so retries don't double-count.
    // bounded by TTL — we only need recent ones (see note below).
    private final Set<String> seenClickIds = /* TTL-backed set, e.g. Caffeine cache */;

    @KafkaListener(topics = "clicks", groupId = "aggregator")
    public void onBatch(List<ClickEvent> batch) {
        for (ClickEvent c : batch) {

            // THE dedup check: if we've seen this click_id, skip it entirely
            if (!seenClickIds.add(c.clickId)) {
                continue;   // duplicate → ignore, don't count
            }

            long minute = c.eventTimeMs / 60_000;
            AggKey key = new AggKey(c.adId, minute, c.country, c.campaignId);
            counters.merge(key, 1L, Long::sum);
        }
    }
}
```

`seenClickIds.add(...)` returns `false` if already present → "already counted, skip."

**Why the set doesn't grow forever (TTL):** you can't remember every id ever (billions/day = infinite memory). But **duplicates arrive close together** (a retry comes seconds later, not days). So remember ids only for a bounded window (e.g. last few minutes), then forget. A Caffeine cache with `expireAfterWrite(5, MINUTES)` keeps memory bounded. = "dedup state with TTL per window."

#### Problem 2 — Crashes → exactly-once (checkpoints + offsets)

Danger:

```
Consumer reads clicks 1–1000 → counts in memory (counter=1000) → writes nothing yet → 💥 CRASH
```

On restart: in-memory 1000 is gone (RAM wiped). Kafka tracks how far each consumer read via an **offset**. If offset already advanced past 1000 → restart at 1001 → those 1000 never recounted (**undercount**). If offset not advanced → re-read 1–1000 → **overcount**. Either way billing is wrong. We need **exactly-once**.

**Mechanism — checkpoints bundling state + offset atomically:**

```java
// conceptual — real systems (Flink) do this automatically & atomically
class Checkpoint {
    Map<AggKey, Long> counters;   // the counts so far
    long kafkaOffset;             // exactly how far we'd read when we saved them
}
```

Rule: **counts and offset are saved together, or not at all** — they can never disagree. On crash + restart:

```
1. Load last checkpoint  → counters restored
2. Rewind Kafka to the SAVED offset (not wherever it happened to be)
3. Resume from exactly there
```

Because restored counters and rewind point were captured at the **same instant**, every click is counted **exactly once**. Crash becomes invisible.

**Last mile — the DB write must also be exactly-once** (what if we write the row then crash before recording it?):

```java
// Fix A — transactional / two-phase-commit sink:
//   DB write and offset commit succeed together or roll back together.

// Fix B — idempotent write: overwrite the ABSOLUTE value for a window, not += .
//   upsert "(ad_123, 2:05) = 50000"  (setting, not adding)
//   → writing it twice yields the same 50000. Safe to retry.
db.upsertAbsolute(key, finalCount);   // idempotent: repeat = same result
```

Idempotent write = "**set** the window's total to X" (repeatable), vs "**add** X" (not repeatable). This whole bundle = "Flink checkpoints + transactional (2PC) sinks → exactly-once." Both dedup (stops *input* duplicates) and exactly-once (stops *processing* duplicates) must hold for trustworthy billing. Real engines (Flink) give checkpoints/exactly-once out of the box — you configure it, not hand-code it.

---

## 8. Hot Keys & Skew

A viral ad = one **hot `ad_id`** → its Kafka partition + aggregation task is overloaded.

```
Mitigation: SALT the key → (ad_id, salt 0..K) spreads one hot ad across K partitions/tasks
  → aggregate per (ad_id, salt) → then SUM the K partials into the final ad_id count
```

- Trade-off: a second aggregation step to combine partials, but it removes the single-task bottleneck.
- Also: pre-aggregate on the **client/edge** (batch counts per ad before sending) to cut event volume.

### Plain-English: the problem restated

Because we partition by `ad_id` (§4), **one ad = one lane = one consumer**. So if ad 123 goes viral (Super Bowl ad), **all** its clicks pile into Lane A → Instance 1 drowns while Instances 2, 3, 4 sit idle. The thing that made counting clean now bites us.

### Plain-English: pre-counting before Kafka (client/edge pre-aggregation)

Instead of one Kafka message per click, the **ad server / edge service** (many of these sit in front) batches and counts locally for ~1 second, then sends **one message with a count**.

Without pre-counting — 500 clicks = 500 messages:

```java
void onClick(ClickEvent c) {
    kafka.send("clicks", c.adId, c);   // 500 clicks/sec = 500 sends/sec
}
```

With pre-counting — 500 clicks = 1 message:

```java
@Component
public class EdgePreAggregator {

    // local mini-counter on THIS edge server, held for ~1 second
    private final Map<AggKey, Long> local = new ConcurrentHashMap<>();

    // called for each incoming click — just bump a local counter, send nothing yet
    public void onClick(ClickEvent c) {
        long minute = c.eventTimeMs / 60_000;
        AggKey key = new AggKey(c.adId, minute, c.country, c.campaignId);
        local.merge(key, 1L, Long::sum);
    }

    // once per second, flush the local counts as PARTIAL aggregates
    @Scheduled(fixedRate = 1000)
    public void flush() {
        local.forEach((key, count) ->
            kafka.send("clicks", key.adId(), new PartialCount(key, count)) // e.g. count=500
        );
        local.clear();
    }
}
```

Kafka now gets `{ad_123, 2:05, India, count: 500}` — one message; the downstream counter does `+= 500`. Shrinks a viral ad's firehose **at the source**. Trade-off: dashboards ~1s less fresh, edge holds a little state. Analogy: each cashier tallies their own drawer and reports a total, instead of radioing the manager on every sale.

### Plain-English: salting — and how it works with fixed partitions

> **Q: If partitions already exist and a consumer is assigned to a partition, how does salting work? Is it built into Kafka?**
> **Salting is NOT a Kafka feature — it's application-level code you write.**

**How Kafka picks a partition (the mechanism):**

```
partition = hash(key) % numberOfPartitions
```

- **Same key → same hash → same partition. Always.**
- So `key = "123"` → every click for ad 123 hashes to the **same** partition → one viral ad floods one partition.

```java
kafka.send("clicks", "123", event);   // key="123" → ALWAYS the same partition
```

**What salting does:** you deliberately **vary the key** so Kafka's own hashing scatters the messages:

```java
int salt = ThreadLocalRandom.current().nextInt(10);   // 0..9
String saltedKey = event.adId + "-" + salt;           // "123-0", "123-1", ...
kafka.send("clicks", saltedKey, event);
```

```
hash("123-0") % N → partition 4
hash("123-1") % N → partition 1     ← different partitions!
hash("123-2") % N → partition 7
```

Ten keys → ~10 partitions → up to 10 consumers. **Kafka is unchanged**; you just fed it varied keys so `hash(key) % N` spreads them. Partitions already existed; salting ensures the hot ad lands across many of them.

**The catch — recombining (second stage):** each consumer now has only a **piece** of ad 123's count, so add a step to sum the pieces:

```java
// Stage 1: each consumer counts its salted slice → writes partials
//   consumer A: (123-0, 2:05) = 5000
//   consumer B: (123-1, 2:05) = 5000  ... 10 partials

// Stage 2: strip the salt and sum the pieces
@KafkaListener(topics = "partial-counts", groupId = "final-aggregator")
public void combine(PartialCount p) {
    String realAdId = p.saltedKey().split("-")[0];   // "123-0" → "123"
    AggKey finalKey = new AggKey(realAdId, p.minute(), p.country(), p.campaign());
    finalCounts.merge(finalKey, p.count(), Long::sum); // 5000+5000+...=50000
}
```

Price of salting = that **extra combine stage**. You usually salt only **detected hot ads**, not every ad.

#### Q: Do we redeploy code after finding a hot key? Do we need a new topic?

**No per-incident deploys.** Options, naive → production:

| Option | How | Verdict |
| --- | --- | --- |
| **A. Hardcode + redeploy** | `Set.of("123","456")` in code; edit + redeploy when an ad goes viral | ❌ Too slow (spikes happen in seconds); nobody does this |
| **B. Always salt, driven by config** | Keep a "hot ad list" in Redis/config, refreshed at runtime; salting code already deployed & dormant; flip an ad on by adding it to the set (can be **auto-detected** by a throughput monitor) | ✅ Common — zero deploys |
| **C. Salt everything, always** | Every ad keyed `adId + "-" + salt`; always run the combine stage | ✅ Simplest ops; pay combine cost even for tiny ads; fine at huge scale |

Option B in code:

```java
@Component
public class SaltingKeyResolver {

    // refreshed from Redis/config every few seconds — NO redeploy to change
    private volatile Set<String> hotAds = Set.of();
    private static final int SALT_BUCKETS = 10;

    @Scheduled(fixedRate = 5000)
    void refreshHotAds() {
        this.hotAds = redis.getSet("hot_ads");   // ops or an auto-detector updates this
    }

    String kafkaKeyFor(ClickEvent c) {
        if (hotAds.contains(c.adId)) {
            int salt = ThreadLocalRandom.current().nextInt(SALT_BUCKETS);
            return c.adId + "-" + salt;   // salted
        }
        return c.adId;                    // normal ads unchanged
    }
}
```

**New topic?** Two ways to do the combine stage:
- **Way 1 — same topic, combine in memory (no new topic):** if one instance owns all salted slices for an ad, strip the salt while counting and merge in its own map. (Usually slices are spread across instances, so this is limited.)
- **Way 2 — a second topic for partials (common):** stage-1 consumers write to a `partial-counts` topic; a stage-2 combiner reads it, strips salt, sums.

```
clicks (salted) ─► stage-1 consumers ─► partial-counts topic ─► stage-2 combiner ─► DB
```

That extra topic is a **one-time, permanent pipeline component created at design time** — NOT created fresh per hot ad. Real tools (Flink/Kafka Streams) create/manage this "repartition + combine" internal topic **for you** when you write a two-stage aggregation.

---

## 9. Lambda vs Kappa (accuracy vs freshness)

Billing wants **accuracy**; dashboards want **freshness**.

| | **Lambda** | **Kappa** |
| --- | --- | --- |
| Paths | **Batch** (accurate, slow) + **Streaming** (fast, approximate) | **Streaming only** (reprocess by replaying the log) |
| Pros | Batch corrects streaming's approximations | One codebase; simpler |
| Cons | Two codebases to maintain | Relies on a replayable log + strong exactly-once stream processing |

```
Lambda: fast streaming rollups for live dashboards
      + nightly BATCH recompute from raw events (S3) for accurate billing (source of truth)
Kappa:  one streaming pipeline; reprocess by REPLAYING Kafka/S3 when needed
```

> **Common answer:** streaming for real-time dashboards + a **batch reconciliation** for billing (Lambda-style); or Kappa with replay if the stream layer is trusted for exactly-once.

### Plain-English: why two paths at all

Our streaming pipeline is **fast but approximate** (HyperLogLog for unique users, too-late events shunted aside, salting partials, tiny crash-edge gaps). Great for dashboards, not the final word on money. So we run **two paths = Lambda architecture.**

#### Foundation — archive every raw click to S3

At ingestion, write **every raw click** to cheap bulk storage (S3 / data lake), in addition to Kafka:

```java
@KafkaListener(topics = "clicks", groupId = "archiver")
public void archiveRaw(List<ClickEvent> batch) {
    // append raw events as-is to cheap storage, partitioned by date/hour
    s3.appendJsonl("s3://clicks/raw/2026/07/08/00/", batch);
}
```

S3 = the **permanent, complete, exact record** of what happened (every click, untouched). Streaming counts are a fast *interpretation*; S3 is *ground truth*. = **Event Sourcing** (keep the raw log forever; rebuild any number from it).

#### Path 1 — Streaming (fast, approximate)

```
clicks → Kafka → stream aggregator → OLAP store → dashboards (seconds fresh)
```

#### Path 2 — Batch (slow, exact): nightly reconciliation

Once a day (or hourly), a batch job reads **all** raw clicks from S3 for the period and recomputes totals **with no shortcuts** — full dedup, all late events present, exact unique counts:

```java
// Runs nightly — think Spark/Flink batch over S3, shown as pseudo-SQL for clarity
@Scheduled(cron = "0 0 2 * * *")   // 2 AM daily
public void reconcileYesterday() {
    /*
        SELECT ad_id, hour, country, campaign_id,
               COUNT(DISTINCT click_id) AS exact_clicks   -- true dedup, no HLL guess
        FROM   s3_raw_clicks
        WHERE  day = yesterday
          AND  is_fraud = false                            -- fraud fully filtered
        GROUP  BY ad_id, hour, country, campaign_id
    */
    var exact = spark.run(query);
    billingStore.overwrite(exact);   // this becomes the SOURCE OF TRUTH for billing
}
```

Differences from streaming: sees **100% of the data** (day is over, no open windows/missing stragglers); `COUNT(DISTINCT click_id)` is **exact** (not HLL); slow but that's fine (billing isn't real-time).

#### Reconciliation — compare the two, trust the batch

```java
public void reconcile(AggKey key) {
    long streamed = olapStore.get(key);      // fast, approximate (already on dashboards)
    long exact    = billingStore.get(key);   // slow, correct (from S3 recompute)

    if (streamed != exact) {
        log.warn("drift for {}: stream={} exact={}", key, streamed, exact);
        // billing uses `exact`. Optionally patch the dashboard number too.
    }
    charge(advertiserOf(key), exact);        // invoice on the EXACT number
}
```

Flow: dashboard shows streamed number now → overnight batch computes exact → billing charges exact → drift corrected. Advertisers see live-ish numbers all day; the invoice is provably correct.

#### Q: Can't Lambda also just replay? Isn't that the same as Kappa?

Yes, **replay exists in both** — it's not the distinguishing factor. The difference is **codebases**:

| | **Lambda** | **Kappa** |
| --- | --- | --- |
| Reprocessing | Yes — a **separate batch job** recomputes from S3 | Yes — **replay the log through the streaming job** |
| Codebases | **Two** (stream + batch) — can drift | **One** (stream only) |
| Needs | — | Trustworthy exactly-once stream + a replayable log |

In **Lambda** the batch layer is a **separate program** (often a different tool, e.g. Spark SQL) that reprocesses S3 — two implementations of the same logic that can drift. In **Kappa** there's **no separate batch layer**; you replay the **same** streaming job. This is literally the argument Kappa's creator made: *if your stream can replay and get the exact answer, the batch layer is redundant — delete it.* So: replay a **separate batch program** = Lambda; replay the **same streaming program** with no batch layer = Kappa. Kappa needs the streaming layer to be trustworthy enough (solid exactly-once, replayable log) that you don't need a batch safety net.

```
Kappa —  Normal:      S3/Kafka log → stream job → results
         Recompute:   S3/Kafka log → SAME stream job (replayed from the start) → corrected results
```

#### Complete picture, end to end

```
                                   ┌──────────► S3 raw events (exact, permanent) ──┐
                                   │                                               │
clicks → ingest (enrich, fraud) → Kafka → stream aggregator → OLAP → dashboards    │  (nightly)
                                                                    (fast/approx)  ▼
                                                          batch recompute from S3 → billingStore
                                                                    (slow/EXACT)   │
                                                                                   ▼
                                                                    reconcile → charge advertisers
```

- **Top path (S3):** ground truth, feeds the exact batch.
- **Middle path (Kafka→stream→OLAP):** fast dashboards.
- **Bottom (batch + reconcile):** exact billing, corrects drift.

---

## 10. Data Model

```sql
-- Aggregates (served to dashboards/billing) — OLAP store
CREATE TABLE click_aggregates (
    ad_id BIGINT, window_start TIMESTAMP, granularity VARCHAR(10),   -- MINUTE/HOUR/DAY
    clicks BIGINT, unique_users BIGINT, revenue NUMERIC(14,2),
    PRIMARY KEY (ad_id, window_start, granularity)
);
CREATE TABLE ads ( ad_id BIGINT PRIMARY KEY, campaign_id BIGINT, advertiser_id BIGINT );

-- Dedup state: seen click_ids per window (stream-processor state / KV with TTL)
-- Raw events → S3 / data lake: { click_id, ad_id, user_id, ts, ip, region, ... } (audit + reprocess)
```

> **Stores to consider:** Kafka (ingest buffer), stream-processor state (dedup/windows), **OLAP store** (Druid/ClickHouse — aggregates), **raw event lake** (S3), ads/campaign metadata (RDBMS).

### Plain-English: what is OLAP here?

**OLAP = Online Analytical Processing** — a *category* of database built to **answer aggregation/summary queries over huge data, fast**. In our design the "results / aggregate store" feeding dashboards is OLAP (**Druid**, **ClickHouse**). Contrast with the OLTP DBs you're used to:

| | **OLTP** (MySQL, Postgres) | **OLAP** (ClickHouse, Druid) |
| --- | --- | --- |
| Built for | Many small reads/writes of **individual rows** | **Summarizing** millions of rows (SUM, COUNT, GROUP BY) |
| Typical query | "Get user 123's profile" / "update this order" | "Total clicks per country per hour this month" |
| Stores data by | **Row** (all of a row's fields together) | **Column** (each field stored together) |
| Sweet spot | App backends, transactions | Analytics, dashboards, reporting |

The magic word is **columnar storage**. Our query is usually "SUM the `clicks` column, GROUP BY country." A columnar DB stores all `clicks` values physically together, so it reads just that column and blazes through the sum, ignoring `ad_id`, `campaign`, etc. A row DB (MySQL) must walk every full row. That's why OLAP aggregates billions of rows in a fraction of a second.

> One line: **OLAP here = the analytics-optimized (columnar) database storing our pre-aggregated rollups, letting dashboards run `GROUP BY`/`SUM` fast.** (The raw S3 lake is also queried analytically, but S3 is bulk file storage; the OLAP store is the fast serving layer for pre-aggregated numbers.)

---

## 11. Sequences

### Ingest → aggregate → serve

```
Client (batched, click_id+ts) → Ingestion API → fraud filter → Kafka (partition by ad_id)
Flink: keyBy ad_id → dedup by click_id (state) → tumbling window by EVENT TIME (watermarks)
     → on window close: emit (ad_id, minute, count, HLL, revenue) → transactional upsert to OLAP
Dashboard/Billing → query OLAP aggregates (fast)
Raw events → also archived to S3
```

### Reconciliation (billing)

```
Nightly batch over S3 raw events → recompute exact per-ad totals → compare with streamed aggregates
→ correct discrepancies → billing uses the reconciled numbers (source of truth)
```

---

## 12. Consistency & Failure

| Concern | Handling |
| --- | --- |
| Double-count | `click_id` dedup + exactly-once processing |
| Late/out-of-order | Event-time windows + watermarks + allowed lateness; correction job for very-late |
| Hot ad | Key salting → partial aggregates → sum |
| Processor crash | Flink checkpoints → resume exactly-once; Kafka retains offsets |
| Bad logic / bug | Reprocess from S3 raw events (Event Sourcing) |
| Dashboard vs billing mismatch | Dashboards approximate (fast); billing reconciled from raw (accurate) |
| Fraud inflation | Filter before counting; post-hoc clawback via reconciliation |

---

## 13. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Producer-Consumer** | Ingest → Kafka → stream processors | Absorb + parallelize firehose |
| **Windowed Aggregation (stream)** | Tumbling/sliding windows by event time | Core rollup |
| **Idempotency / Dedup** | `click_id` dedup in processor | No double-count |
| **CQRS + Materialized View** | Pre-aggregated rollups vs raw events | Fast queries |
| **Lambda / Kappa** | Batch+stream vs stream-only | Accuracy vs freshness |
| **Event Sourcing** | Raw event log as truth; replay to rebuild | Reprocessing/audit |
| **Sketch / Probabilistic (HLL)** | Approx unique counts | Cheap cardinality |
| **Sharding / Partitioning (+ salting)** | Kafka + store partitioned by ad_id; salt hot keys | Scale + skew |
| **Watermark** | Handle late/out-of-order events | Correct windows |

---

## 14. Interview Cheat Sheet

> **"How do you count clicks at millions/sec?"**
> "Ingest into Kafka (partitioned by ad_id), aggregate with a stream processor (Flink) into **event-time tumbling windows** (per-minute), write rollups to an **OLAP store** (Druid/ClickHouse) for dashboards, and archive raw events to S3. You never recount raw events per query — you pre-aggregate."

> **"How do you avoid double-counting for billing?"**
> "Each click has a unique `click_id`; dedup in the processor's keyed state; use **exactly-once** stream processing (Flink checkpoints + transactional sinks). For billing accuracy, run a **batch reconciliation** over the raw event lake (Lambda) as the source of truth."

> **"Late / out-of-order events?"**
> "Aggregate by **event time** with **watermarks + allowed lateness**; very-late events go to a correction/batch job. Raw events in S3 let you reprocess if logic changes."

> **"A viral ad overloads one partition — hot key?"**
> "**Salt the key** — spread `(ad_id, salt)` across K partitions/tasks, aggregate partials, then sum. Optionally pre-aggregate on the client to cut volume."

> **"Lambda vs Kappa?"**
> "Lambda = fast approximate streaming + accurate batch recompute (two codebases). Kappa = streaming only, reprocess by replaying the log (simpler, needs trusted exactly-once)."

---

## 15. Final Takeaways

- **Pre-aggregate as a stream** into event-time windows; serve rollups from an **OLAP store** — never recount raw per query.
- **Kafka (partition by ad_id) → Flink windowed aggregation → OLAP**; archive raw to **S3** (reprocess/audit).
- **Dedup by click_id + exactly-once + watermarks** for late/out-of-order; **HLL** for unique counts.
- **Hot key → salting** (partials → sum); fraud filter before counting.
- **Lambda** (stream + batch reconcile) for accurate billing, or **Kappa** (stream + replay).
- Patterns: Producer-Consumer, Windowed Aggregation, Dedup/Idempotency, CQRS/Materialized View, Event Sourcing, HLL, Watermark, Salting.

### Related notes

- [Apache Kafka](../concepts/kafka.md) — ingest backbone + exactly-once
- [Video Streaming](video-streaming-system-design.md) (view counting) · [Leaderboard](leaderboard-system-design.md) · [Databases — Deep Dive](../concepts/databases-deep-dive.md) (OLAP/column stores)
