# Distributed Job Scheduler — System Design

> **Core challenge:** run **jobs at the right time** — one-off (`run at T`) and **recurring** (cron: "every day 2am") — **reliably**, **exactly-once-ish**, and **at scale**, across a fleet of workers, surviving node failures without dropping or double-running jobs. Think: cron-as-a-service (AWS EventBridge Scheduler, Airflow triggers, Quartz cluster, Sidekiq/Celery beat).

> **How to read this doc:** each section has the dense interview summary first, then a **deep dive** (annotated Java and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand.

---

## Contents

- [1. Mental Model](#1-mental-model)
- [2. Requirements](#2-requirements)
- [3. Capacity Estimation](#3-capacity-estimation)
- [4. Architecture](#4-architecture)
- [5. Storing & Finding Due Jobs](#5-storing--finding-due-jobs)
- [6. Execution — Exactly-Once-ish & No Double-Run](#6-execution--exactly-once-ish--no-double-run)
- [7. Recurring Jobs (cron)](#7-recurring-jobs-cron)
- [8. Retries, Failures & DLQ](#8-retries-failures--dlq)
- [9. Data Model (all tables)](#9-data-model-all-tables)
- [10. API Design](#10-api-design)
- [11. Sequences](#11-sequences)
- [12. Consistency & Correctness](#12-consistency--correctness)
- [13. Design Patterns (that can be used)](#13-design-patterns-that-can-be-used)
- [14. Scaling & Failure](#14-scaling--failure)
- [15. Interview Cheat Sheet](#15-interview-cheat-sheet)
- [16. Final Takeaways](#16-final-takeaways)

---

## 1. Mental Model

```
Submit job (run at T / cron) → scheduler finds jobs whose time has come
   → hands to a worker → worker runs it → record result → (recurring? schedule next run)
```

Two hard parts: **efficiently finding due jobs at scale** and **making sure each due job runs once** even when scheduler/worker nodes fail.

### What problem are we even solving?

A distributed job scheduler runs jobs at set times. Each scheduled job is something like: "send this email at 9am", "generate this invoice on the 1st of every month", "back up this database every night at 2am." At scale it must handle:

- **Millions of jobs** scheduled at once.
- Many firing at the **same instant** (everyone schedules "midnight" and "on the hour").
- A job firing **even if the machine holding it crashes** (a node dying must not lose the schedule).
- Each job running **exactly once** — not zero times, not twice.

So the whole system is a reliable **"do this thing at this time"** machine. Everything else is just "how do we do that correctly when there are millions of them and machines keep dying."

### Why not just use one `while(true)` loop with `sleep`?

First instinct: one program that loops forever, checks the clock, and runs whatever's due:

```java
// The naive single-machine scheduler — fine for a hobby project, breaks in the real world
while (true) {
    for (Job job : allJobs) {
        if (job.runAt <= now()) {
            run(job);            // do the work right here
        }
    }
    Thread.sleep(1000);          // check again in 1 second
}
```

Why this melts at scale:

| Wall | Problem |
| --- | --- |
| **1. One machine dies = everything stops** | If this one process crashes, **every** job stops firing. No redundancy. |
| **2. Can't scan millions of jobs every second** | Looping over 100M jobs each tick to ask "are you due?" is hopelessly slow. We need to look up *only* the due ones (see §5). |
| **3. Running the job blocks the loop** | If `run(job)` takes 30s, the loop is frozen for 30s and misses other alarms. Scheduling and doing must be **separated** (see §4). |
| **4. Add a second machine → double-firing** | The obvious fix (run two copies for safety) means **both** see the same due job and run it twice. Now we need locking/claiming (see §6). |

**So instead:** (1) store jobs **durably in a database** so a crash loses nothing; (2) have a **scheduler** that only *finds* due jobs and drops them on a **queue** — it never runs them; (3) a **pool of workers** picks jobs off the queue and does the actual work, scaling out independently. That "find due → enqueue → workers run" split is the backbone of the whole design (see §4).

---

## 2. Requirements

**Functional**
- Schedule **one-time** jobs (`runAt`) and **recurring** jobs (cron / fixed interval).
- Execute at (or shortly after) the scheduled time; **at-least-once** with idempotency (aim for exactly-once-effect).
- Cancel/update jobs; view status/history; retries on failure.

**Non-functional**
- **Reliable** — never silently drop a job; **no double execution**.
- **Scalable** — millions of scheduled jobs; **spiky** fire rate (many jobs at midnight/round hours).
- **Timely** — fire within acceptable delay; **fault-tolerant** to node crashes; **multi-tenant** isolation.

---

## 3. Capacity Estimation

```
Jobs ~ 100M's scheduled · fire rate spiky: e.g. 10M jobs all at midnight → huge burst
Poll granularity: if per-second, a 1M-job second needs fast due-lookup + batch claim
Storage: jobs + job_runs (history) → partition by time; archive old runs
Downstream: firing 10M jobs at once can overwhelm targets → rate-limit + jitter the schedule
```

> The classic pain is the **midnight thundering herd** (everyone schedules "daily at 00:00"). Add **jitter** to scheduled times + rate-limit dispatch so the burst doesn't melt downstream.

---

## 4. Architecture

```
              submit / cancel / update jobs
                          │ (REST)
                          ▼
                  ┌───────────────┐
                  │  API Service  │
                  └───────┬───────┘
                          │ persist job (status=SCHEDULED, next_run=T)
                          ▼
       ┌─────────────────────────────────────┐
       │            Job Store (DB)            │ ◄── durable source of truth
       │        jobs  ·  job_runs            │
       └──▲──────────────────────────┬───────┘
          │                          │  ① poll "who's due?"
   record │                          │     SELECT … WHERE next_run<=now()
   result │                          │  ② claim the row (win the race)
 / next_run                          ▼
       ┌──┴──────────────────────────────────┐
       │        Scheduler / Poller  (HA)      │  only ONE fires each job:
       │      leader election  OR  sharding   │   • leader = single active node
       └──────────────────┬──────────────────┘   • shard  = owns hash(job_id) slice
                          │ ③ enqueue due job
                          ▼
                 ┌──────────────────────┐
                 │   Queue (Kafka/SQS)   │  decouples "decide" from "do"; absorbs bursts
                 └───────────┬──────────┘
                          │ ④ pull
                          ▼
       ┌─────────────────────────────────────┐
       │             Worker Pool             │  runs the actual job; scales independently
       └──┬───────────────────────────┬──────┘
          │ success                    │ failed after N retries
          │ • record run in job_runs   ▼
          │ • recurring → compute   ┌─────────┐
          │   next_run, re-arm      │   DLQ   │  exhausted jobs → inspect / replay
          └── back to Job Store ──► └─────────┘
```

- **Separate scheduling from execution:** the scheduler only decides *what's due* and enqueues; **workers** do the actual work + scale independently (like the Notification system).
- **Follow the numbers:** ① the scheduler polls the DB for due jobs → ② claims each one so no other scheduler double-fires it → ③ drops it on the queue → ④ a worker pulls and runs it, then writes the result back (and, if recurring, re-arms `next_run`); jobs that exhaust their retries land in the **DLQ**.

### The three roles

The system has three roles:

| Part | What it does | What it does NOT do |
| --- | --- | --- |
| **Job Store (DB)** | Stores every job durably, survives crashes ("durable truth") | Doesn't run anything |
| **Scheduler / Poller** | Wakes up, asks "who's due right now?", claims due jobs, enqueues them | Never runs the job itself |
| **Queue** | Holds enqueued jobs so workers can pick them up | Doesn't decide timing |
| **Workers** | Pull a job, do the actual work, record the result | Don't watch the clock |

Why split it up? **The scheduler must stay light and fast.** If it also *ran* the jobs, one slow job would freeze it and it would miss other due jobs. By only *finding + enqueueing*, the scheduler stays a quick "who's due?" loop, while a separate pool of workers absorbs the actual work.

```java
// The scheduler NEVER runs the job. It only finds due jobs and hands them off.
void schedulerTick() {
    List<Job> due = jobStore.findDue(now());   // "who's due?" — fast lookup (§5)
    for (Job job : due) {
        if (jobStore.claim(job)) {              // win the race so no one else takes it (§6)
            queue.enqueue(job);                 // drop the ticket on the rail — done, move on
        }
    }
}

// A worker, running on a totally separate machine, does the actual work.
void workerLoop() {
    while (true) {
        Job job = queue.take();                 // grab a ticket
        JobResult result = execute(job);        // THIS is where the real work happens
        jobStore.recordRun(job, result);        // write down what happened
        if (job.isRecurring()) scheduleNext(job); // set the next run (§7)
    }
}
```

#### Q: Why put a queue in the middle? Can't the scheduler just call the worker directly?

A queue **decouples timing from doing** and gives three things for free:

- **Bursts get absorbed.** 10M jobs fire at midnight → the scheduler dumps 10M tickets on the queue and the workers drain them at their own pace. Without a queue, that spike would hammer the workers all at once.
- **Workers scale independently.** Too much work? Add more workers reading the same queue. The scheduler doesn't change.
- **Retries survive.** If a worker crashes mid-job, the ticket can go back on the queue (or the lease expires — see §6) and another worker retries it.

#### Q: Isn't the scheduler a single point of failure?

It would be if there were only one — so in production you run several, but you make sure they don't **both fire the same job**. Two ways (both in §6): **leader election** (only one scheduler is "active" at a time; others stand by) or **sharding** (each scheduler owns a slice of jobs, e.g. by hash of `job_id`, so their work never overlaps).

---

## 5. Storing & Finding Due Jobs

The scheduler must efficiently answer: *"which jobs are due now?"*

| Approach | How | Trade-off |
| --- | --- | --- |
| **DB poll + index** | `SELECT ... WHERE status='SCHEDULED' AND next_run <= now() LIMIT N` on `(status, next_run)` | Simple; DB load at scale; poll interval = min granularity |
| **Time-bucketing / partitioning** | Bucket jobs by minute/hour; poll only the current bucket | Scales the poll; hot buckets at round times |
| **Redis sorted set (ZSET)** | score = `runAt` epoch; `ZRANGEBYSCORE 0 now` pops due jobs | Fast, in-memory; needs a durable backing (DB) |
| **Timing wheel** | Array of buckets by time slot; a cursor advances each tick; near-term timers land in slots; hierarchical wheels for far-future | Very efficient O(1) insert/expire for short horizons (Kafka/Netty use this) |
| **Delay queue** | SQS delay / Kafka delayed topics | Offloads timing to the queue |

```
Timing wheel: buckets[0..N-1]; a job at T lands in bucket (T/tick) % N; the cursor fires bucket at each tick.
  Far-future jobs → a coarser "overflow" wheel, promoted down as time nears (hierarchical).
```

> **Common answer:** **DB (durable truth) + time-bucketed index/poll**, or a **Redis ZSET** as a fast due-index backed by the DB. In-process near-term timers use a **timing wheel**. (The per-tick "find due → claim → enqueue" loop is shown as annotated code in the deep dive below.)

### The "don't scan every job" problem

You have **100 million** jobs scheduled. Every second you must answer: *"which ones are due right now?"* The naive way is to look at all 100M and check each one's time — 100M comparisons **every second**. Hopeless.

The trick: **keep the jobs sorted by time**, so the ones about to fire are all at the front and you only ever look at the front.

#### The core insight: an index sorted by fire time

If the database keeps an **index on `next_run`** (sorted by fire time), then "who's due?" becomes "give me the rows at the very front whose time has passed" — the DB jumps straight to them instead of scanning everything.

```sql
-- With an index on next_run, this touches only the handful of rows that are due,
-- NOT all 100M. The DB uses the sorted index to jump to the front.
SELECT * FROM jobs
WHERE status = 'SCHEDULED' AND next_run <= now()
ORDER BY next_run
LIMIT 1000;              -- grab a batch, not the whole world
```

The index means you read only the front of the time-sorted order and stop at the first row not yet due — never the whole table.

### The four ways to find due jobs, from simplest to fanciest

These are increasingly sophisticated versions of the same idea — find the front of the time-sorted order cheaply.

#### 1. DB poll + index (the default, start here)

The scheduler wakes up every second (a "tick") and runs the `SELECT` above. **Simple, durable, good enough for most systems.**

```java
@Scheduled(fixedRate = 1000)   // wake up once per second
public void tick() {
    List<Job> due = jobRepo.findDue(Instant.now(), 1000);   // the indexed query
    for (Job job : due) {
        if (claim(job)) queue.enqueue(job);                 // claim + hand off (§6)
    }
}
```

- **Poll interval = your minimum precision.** Poll every 1s → a job can fire up to ~1s late. That's usually fine.
- **Downside:** at huge scale, hammering the DB every second is load. That's what the next options relieve.

#### 2. Time-bucketing (group jobs by the minute they fire)

Instead of one giant sorted set, put each job into a **bucket labeled with its minute**. To find what's due, you only open **the current minute's bucket** — never the whole dataset.

```
bucket "2026-07-08 09:00" → [job A, job B, job C]
bucket "2026-07-08 09:01" → [job D]
bucket "2026-07-08 09:02" → [job E, job F]
                                 ▲
                     scheduler only reads THIS minute's bucket
```

**Downside — hot buckets:** everybody schedules "09:00:00", so that one bucket is enormous while others are empty (the *thundering herd*, see §3). Fix: spread with **jitter**.

#### 3. Redis sorted set / ZSET (a fast in-memory due-index)

A Redis **ZSET** is a set where every member has a **score**, and Redis keeps it sorted by score automatically. Use the job's fire time (epoch seconds) as the score:

```java
// When a job is scheduled: add it to the ZSET, scored by WHEN it should fire.
redis.zadd("due_jobs", job.runAtEpoch(), job.jobId());

// Every tick: pop everything whose score (fire time) is now or in the past.
Set<String> due = redis.zrangeByScore("due_jobs", 0, Instant.now().getEpochSecond());
for (String jobId : due) {
    redis.zrem("due_jobs", jobId);   // remove so we don't re-fire it
    enqueue(jobId);
}
```

- **Blazing fast** because it's in memory and already sorted — `ZRANGEBYSCORE 0 now` gives you exactly the due ones.
- **Catch: Redis can lose data on crash**, so it's a fast *cache/index*, not the source of truth. Keep the **DB as durable truth** and use the ZSET as a speed layer you can rebuild from the DB.

#### 4. Timing wheel (how Kafka/Netty do near-term timers)

A **timing wheel** is a circular array of buckets — literally like a **clock face**. A pointer (the "cursor") ticks from slot to slot. When you add a timer, you drop it in the slot the cursor will reach when it's due. Each tick, the cursor fires whatever is in the slot it lands on. Insert and fire are both **O(1)** — no scanning, no sorting.

```java
class TimingWheel {
    List<Job>[] buckets = new List[60];   // e.g. 60 slots = one slot per second, 1-minute wheel
    int cursor = 0;                        // advances one slot per tick

    // Add a job: figure out which slot it lands in.
    void add(Job job, int secondsFromNow) {
        int slot = (cursor + secondsFromNow) % buckets.length;
        buckets[slot].add(job);
    }

    // Every second: fire this slot, then move the cursor forward.
    void tick() {
        for (Job job : buckets[cursor]) enqueue(job);
        buckets[cursor].clear();
        cursor = (cursor + 1) % buckets.length;
    }
}
```

- **The problem: a wheel only covers a short horizon** (a 60-slot, 1-per-second wheel only reaches 60s ahead). For a job 3 days out, use **hierarchical wheels** — a coarse "days" wheel, then "hours", then "minutes", then "seconds" (exactly like the hour/minute/second hands of a real clock). A far-future job sits in the coarse wheel and gets **promoted down** to a finer wheel as its time approaches.
- Great for **in-process, near-term** timers (retries, timeouts). The durable long-horizon store is still the DB.

### Which one should I actually use?

| If you have... | Use |
| --- | --- |
| A normal service, millions of jobs | **DB poll + index on `(status, next_run)`** — simple, durable, done |
| Very high fire rate, need sub-second speed | **DB (truth) + Redis ZSET (fast due-index)** |
| In-process short-lived timers (retries/timeouts) | **Timing wheel** |
| Herd at round times | Any of the above **+ jitter** to flatten the spike |

> **Bottom line:** the DB is always the durable truth; the fancier structures (ZSET, timing wheel) are just faster *ways to find the front of the line* layered on top.

---

## 6. Execution — Exactly-Once-ish & No Double-Run

Multiple scheduler nodes must not fire the same job twice.

| Technique | Detail |
| --- | --- |
| **Atomic claim** | `UPDATE jobs SET status='PICKED', locked_by=?, lock_expiry=now()+ttl WHERE job_id=? AND status='SCHEDULED'` → only one node wins (rows affected = 1) |
| **`SELECT ... FOR UPDATE SKIP LOCKED`** | Concurrent pollers grab different rows without blocking each other |
| **Leader election** | One active scheduler (ZooKeeper/etcd/Redis lock) polls → avoids duplicate polling |
| **Partitioning/sharding** | Each scheduler owns a shard of jobs (by hash) → no overlap |
| **Idempotent execution** | Jobs carry an idempotency key; workers dedup → at-least-once + idempotent = **exactly-once effect** |
| **Lease/heartbeat** | The claimed job has a TTL; if the worker dies, the lease expires → the job is re-claimable (no permanent stuck) |

> **Reality:** distributed exactly-once is hard → aim **at-least-once + idempotent jobs**, with **atomic claim + lease expiry** so a crashed worker's job is safely retried without a duplicate within the lease.

### The "two schedulers, one job" problem

We run **several** schedulers for safety (so one crash doesn't stop all jobs). But now if the job "send Nike's 9am email" comes due, **all** of them see it. If they each fire it, Nike gets the email 3 times. We need a way for exactly **one** to "win" the job.

The rule: whichever scheduler **marks the job as claimed first** owns it; the others see it's taken and skip it. That "mark it to claim it" is the **atomic claim**.

#### The atomic claim — how one node "wins"

The database can only let **one** update change a row from `SCHEDULED` to `PICKED`. That's the pen.

```sql
-- Every scheduler runs this for a due job. The DB guarantees only ONE succeeds.
UPDATE jobs
SET    status = 'PICKED', locked_by = 'scheduler-7', lock_expiry = now() + interval '60 seconds'
WHERE  job_id = 42 AND status = 'SCHEDULED';   -- ← the magic: only works if still SCHEDULED
```

```java
int rowsChanged = jdbc.update(claimSql, jobId, myId);
if (rowsChanged == 1) {
    queue.enqueue(job);   // I won the race — I own this job, hand it off
} else {
    // rowsChanged == 0 → someone else already flipped it to PICKED. Skip it, no harm done.
}
```

The key is `AND status = 'SCHEDULED'`. Two schedulers race to run this same `UPDATE`. The DB processes them one at a time: the first flips it to `PICKED` and reports **"1 row changed."** The second now finds no row that is still `SCHEDULED`, so it changes **"0 rows"** — it lost, and quietly moves on. No duplicate.

#### `SELECT ... FOR UPDATE SKIP LOCKED` — many pollers, no traffic jam

If you want **many** pollers grabbing **different** jobs at the same time (not just claiming one-by-one), `SKIP LOCKED` tells the DB "give me due rows that nobody else has locked right now, and don't wait around for locked ones."

```sql
-- Poller A and Poller B run this simultaneously and get DIFFERENT rows — no blocking.
SELECT * FROM jobs
WHERE  status = 'SCHEDULED' AND next_run <= now()
ORDER  BY next_run
LIMIT  100
FOR UPDATE SKIP LOCKED;    -- "skip rows another poller already grabbed"
```

Each poller grabs the next *available* rows and skips any another poller has already locked — nobody blocks waiting.

#### The lease (TTL) — what if the winner then dies?

Winning the job isn't enough: what if scheduler-7 claims the job, hands it to a worker, and the **worker crashes mid-run**? The job is stuck in `PICKED`/`RUNNING` forever — it never finishes and never re-runs. Bad.

Fix: the claim comes with a **lease — an expiry time** (`lock_expiry`). The worker must "check in" (heartbeat) to keep it. If it dies, the lease **expires**, and a sweeper makes the job claimable again.

```java
// A background sweep: any job "claimed" but whose lease ran out is reset to SCHEDULED.
@Scheduled(fixedRate = 30_000)
void reclaimExpiredLeases() {
    jdbc.update("""
        UPDATE jobs SET status = 'SCHEDULED', locked_by = NULL
        WHERE  status IN ('PICKED','RUNNING') AND lock_expiry < now()
    """);
}
```

#### Q: If a crashed worker's job re-runs, isn't that a double-run?

It **can be** — and this is why we say **"exactly-once-ish"**, not exactly-once. The honest target is:

> **at-least-once delivery + idempotent jobs = exactly-once *effect*.**

We accept that a job might occasionally run twice (crash right after doing the work but before recording success → it looks unfinished → re-runs). We make that **safe** by writing jobs so running them twice does no extra harm — that's **idempotency**.

```java
// NOT idempotent — running twice charges the customer twice. Dangerous on retry.
void chargeCustomer(Job job) {
    payments.charge(job.customerId, job.amount);
}

// Idempotent — the idempotency key makes a repeat a no-op.
void chargeCustomer(Job job) {
    if (ledger.alreadyProcessed(job.idempotencyKey)) return;   // did this already → skip
    payments.charge(job.customerId, job.amount);
    ledger.markProcessed(job.idempotencyKey);
}
```

In other words, an idempotent job is safe to repeat — extra runs have no additional effect.

#### Q: Leader election vs sharding — what's the difference?

Both stop multiple schedulers from stepping on each other, but differently:

| | **Leader election** | **Sharding / partitioning** |
| --- | --- | --- |
| Idea | Only **one** scheduler is "active"; the rest stand by | **All** schedulers active, but each owns a **slice** of jobs |
| How | A lock in ZooKeeper/etcd/Redis; whoever holds it leads | `job_id % N` → scheduler N handles that slice |
| If it dies | Standby grabs the lock and takes over | Its slice is reassigned to survivors |
| Trade-off | Simple; single leader can bottleneck | Scales out; more moving parts |

The **atomic claim above still applies within either** — it's your last line of defense against a duplicate even if two nodes briefly overlap (e.g., during a leader handover).

---

## 7. Recurring Jobs (cron)

- Parse the cron expression (Quartz-style) → next fire time; base it on the **scheduled** time to avoid drift. (The `scheduleNext` re-arm — compute `next_run` from the scheduled time, set status `SCHEDULED` — is shown as annotated code in the deep dive below.)
- **Missed windows** (system down over a fire time) → policy: **skip**, **run-once-catchup**, or **run-all-missed** (per job).
- Timezone/DST-aware cron for wall-clock schedules.

### A recurring job re-arms itself

A one-time job runs once and is done. A **recurring** job ("every day at 2am") reschedules itself: the moment it fires, it computes *when should it next run?* and sets `next_run` to that time. There's never a "list of all future occurrences" in the DB — just **one row that keeps updating its `next_run`.**

```java
// After a recurring job runs, arm the next occurrence.
void scheduleNext(Job job) {
    if (!job.isRecurring()) return;

    // A cron like "0 2 * * *" means "2:00 every day". A parser turns it into the next fire time.
    Instant next = cronParser.next(job.cronExpr, /* from = */ job.scheduledTime);

    job.nextRun = next;
    job.status  = "SCHEDULED";     // back in the pool for §5's due-lookup to find later
    jobStore.save(job);
}
```

#### Q: Why compute the next time from the *scheduled* time, not from *now*?

Because "now" drifts. Say the job is due at 2:00:00 but the system is busy and it actually runs at 2:00:47.

```java
// GOOD — anchor to the SCHEDULED time → next run is 2:00 tomorrow, exactly. No drift.
Instant next = cronParser.next(cron, job.scheduledTime);   // scheduledTime = 2:00:00

// BAD — anchor to NOW → next run creeps to 2:00:47, then later, then later... drifts daily.
Instant next = cronParser.next(cron, Instant.now());        // now = 2:00:47
```

The next run is anchored to the intended slot (2:00), not to when the previous run happened to finish — so it never drifts.

#### Q: The server was down for 3 hours over a 2am job — now what? (missed windows)

When the scheduler comes back and sees a fire time already passed, you need a **policy** per job — there's no universally right answer:

| Policy | What it does | Good for |
| --- | --- | --- |
| **Skip** | Forget the missed run, just schedule the next one | A dashboard refresh — a stale one doesn't matter |
| **Run-once-catchup** | Run it **one** time now, then resume normal schedule | A daily report — you want today's, but not 3 copies |
| **Run-all-missed** | Fire once for **every** slot that was missed | Billing/accruals — every period must be accounted for |

```java
void handleMissed(Job job, Instant downSince, Instant backAt) {
    List<Instant> missed = cronParser.between(job.cronExpr, downSince, backAt);
    switch (job.missedPolicy) {
        case SKIP        -> {}                              // do nothing, move on
        case CATCHUP_ONE -> run(job);                       // one make-up run
        case RUN_ALL     -> missed.forEach(t -> run(job));  // one per missed slot
    }
    scheduleNext(job);   // then re-arm the future as usual
}
```

#### Q: Why does DST/timezone matter for cron?

"Every day at 2:00 AM" is a **wall-clock** promise. On the night the clocks spring forward, 2:00 AM **doesn't exist** (it jumps 1:59 → 3:00); on the night they fall back, 2:00 AM happens **twice**. A timezone/DST-aware cron parser decides whether to skip, run once, or run twice — so "2am daily" stays sane for humans. (Storing/scheduling in UTC internally avoids most of this; the conversion happens at the wall-clock boundary.)

---

## 8. Retries, Failures & DLQ

- Exponential backoff **with jitter** (avoid a thundering herd of simultaneous retries — see Kafka note). (The `attempt++` → backoff-reschedule-or-DLQ logic is shown as annotated code in the deep dive below.)
- **DLQ** for jobs that exhaust retries → manual inspection/replay.
- **Timeouts** — a job running too long → kill + retry (lease expiry handles crashed workers).

### Retrying with backoff

A job failed (network blip, downstream service down). Don't give up, but don't hammer either: wait a bit before retrying, wait longer after each failure, and after several tries conclude something's wrong and stop.

#### Exponential backoff — wait longer each time

```java
void onFailure(Job job) {
    job.attempt++;

    if (job.attempt < job.maxAttempts) {
        // wait longer after each failure: 1s, 2s, 4s, 8s, 16s ...
        long backoffMs = (long) (Math.pow(2, job.attempt) * 1000);
        job.nextRun = Instant.now().plusMillis(backoffMs + jitter());  // +jitter, see below
        job.status  = "SCHEDULED";      // re-armed → §5's poll will pick it up again
        jobStore.save(job);
    } else {
        moveToDLQ(job, "exhausted retries");   // gave up — set aside for a human (below)
    }
}
```

Waiting longer each time gives a struggling downstream service **room to recover** instead of getting pounded.

#### Q: Why add jitter? Isn't a clean "1s, 2s, 4s" better?

No — because failures often hit **many jobs at once** (the payment service went down → 10,000 jobs all fail at 2:00:00). With clean backoff, all 10,000 retry at *exactly* 2:00:01, then all at 2:00:03... you've built a **synchronized stampede** that knocks the service over again the instant it recovers.

```java
// jitter = a small random offset so retries SPREAD OUT instead of all landing together
long jitter() {
    return ThreadLocalRandom.current().nextLong(0, 1000);  // 0–1000ms of randomness
}
```

Jitter spreads the retries out over time so they don't all hit at the same instant. (Same idea as the midnight-herd jitter in §3.)

#### Q: What is a DLQ and why not just retry forever?

A **DLQ (Dead Letter Queue)** is the "**giving up** shelf." Some jobs will **never** succeed no matter how many times you retry — a bug in the job, malformed data, a permanently-deleted target (a *poison job*). Retrying forever wastes resources and hides the problem.

```java
void moveToDLQ(Job job, String reason) {
    job.status = "FAILED";
    deadLetterRepo.save(new DeadLetterJob(job.jobId, reason, Instant.now()));
    alerts.page("Job " + job.jobId + " dead-lettered: " + reason);  // tell a human
}
```

After `maxAttempts`, the job moves to the DLQ and **alerts a human**, who inspects it, fixes the root cause, and can **replay** it — instead of retrying a doomed job forever.

#### Q: What about a job that hangs forever (never fails, never finishes)?

That's a **timeout**. A failure at least tells you it failed; a *hung* job just sits in `RUNNING` silently. Two guards cover it:

- **Timeout:** if a job runs past its allowed time, kill it and treat it as a failure (→ retry/backoff).
- **Lease expiry (from §6):** if the whole *worker* dies (so it can't even report a timeout), the lease's TTL runs out and the job becomes re-claimable. Belt and suspenders.

---

## 9. Data Model (all tables)

```sql
CREATE TABLE jobs (
    job_id BIGINT PRIMARY KEY, tenant_id BIGINT,
    type VARCHAR(50),               -- ONE_TIME, RECURRING
    payload JSONB, cron_expr VARCHAR(100),
    next_run TIMESTAMP NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED', -- SCHEDULED, PICKED, RUNNING, DONE, FAILED, CANCELLED
    locked_by VARCHAR(100), lock_expiry TIMESTAMP,   -- lease
    idempotency_key VARCHAR(255),
    attempt INT DEFAULT 0, max_attempts INT DEFAULT 5,
    created_at TIMESTAMP DEFAULT now()
);
CREATE INDEX idx_jobs_due ON jobs(next_run) WHERE status = 'SCHEDULED';   -- due lookup

CREATE TABLE job_runs (             -- execution history / audit
    run_id BIGINT PRIMARY KEY, job_id BIGINT NOT NULL,
    started_at TIMESTAMP, finished_at TIMESTAMP,
    status VARCHAR(20),             -- SUCCESS, FAILED, TIMEOUT
    result JSONB, error TEXT, worker_id VARCHAR(100)
);
CREATE INDEX idx_runs_job ON job_runs(job_id, started_at DESC);

CREATE TABLE dead_letter_jobs ( job_id BIGINT PRIMARY KEY, reason TEXT, moved_at TIMESTAMP );

-- Optional: Redis ZSET due_jobs (score = next_run epoch) as the fast due-index
```

> **Tables to consider:** jobs, job_runs (history), dead_letter_jobs, tenants, plus optional Redis ZSET for the due-index.

### What each table is for

Two tables carry the whole design; the rest are support.

| Table | Holds | Why separate |
| --- | --- | --- |
| **`jobs`** | One row per job, with its *current* state and *next* fire time | The live "what should happen and when" — constantly updated |
| **`job_runs`** | One row per *actual execution* — start, end, success/fail, error | History/audit; you never edit the past, only append |
| **`dead_letter_jobs`** | Jobs that exhausted retries | Keeps poison jobs out of the live flow (§8) |

#### Q: Why is `job_runs` separate from `jobs`? Isn't that duplication?

They answer **different questions**, so they're deliberately split:

- `jobs` = *"what's the plan?"* — mutable, one row per job, always shows the **latest** state and the **next** run.
- `job_runs` = *"what actually happened?"* — append-only history, **many** rows per job (one per execution attempt).

A recurring "daily at 2am" job is **one** row in `jobs` but grows **one new row per day** in `job_runs`. Mixing them would mean either losing history (if you overwrite) or bloating the live table you poll every second (bad for §5's fast lookup).

```sql
-- "What runs next for job 42?" — one row, the live plan.
SELECT next_run, status FROM jobs WHERE job_id = 42;

-- "Show me job 42's last 10 executions" — history, many rows.
SELECT started_at, status, error FROM job_runs
WHERE job_id = 42 ORDER BY started_at DESC LIMIT 10;
```

#### Q: What is that `WHERE status = 'SCHEDULED'` doing on the index?

That's a **partial index** — the single most important performance trick in the schema:

```sql
CREATE INDEX idx_jobs_due ON jobs(next_run) WHERE status = 'SCHEDULED';
```

It builds a sorted index **only over rows that are still SCHEDULED** — the only ones the poller cares about (§5). Jobs that are `DONE`, `RUNNING`, `CANCELLED` etc. aren't in this index at all, so it stays small and fast even when the table holds 100M mostly-finished rows. Finished jobs aren't indexed, so they never slow down "what's next?"

#### Q: How does this table map to everything else in the doc?

Almost every mechanism is just a column here:

| Column | Powers | Section |
| --- | --- | --- |
| `next_run` + partial index | Fast due-lookup | §5 |
| `status`, `locked_by`, `lock_expiry` | Atomic claim + lease | §6 |
| `idempotency_key` | Exactly-once *effect* | §6 |
| `cron_expr` | Recurring re-arm | §7 |
| `attempt`, `max_attempts` | Retry then DLQ | §8 |

So the "data model" isn't a separate topic — it's the **physical home** for every idea above. Read a column, and you can point to the section that uses it.

---

## 10. API Design

```
POST   /v1/jobs        { type, payload, runAt | cronExpr, maxAttempts, idempotencyKey }  → { jobId }
GET    /v1/jobs/{id}    GET /v1/jobs/{id}/runs
DELETE /v1/jobs/{id}    # cancel
PATCH  /v1/jobs/{id}    # reschedule / update
```

---

## 11. Sequences

### Fire a due job (no double-run)

```
Scheduler tick: due = SELECT ... WHERE status='SCHEDULED' AND next_run<=now() (current bucket)
  for each: atomic claim (UPDATE ... status='PICKED' WHERE status='SCHEDULED') → only winner enqueues
Queue → Worker: RUNNING → execute (idempotent) → record job_run
  success → DONE (recurring? compute next_run from scheduled time → SCHEDULED)
  failure → attempt++ → backoff+jitter reschedule OR DLQ if exhausted
Worker crash → lease TTL expires → job re-claimable → retried
```

---

## 12. Consistency & Correctness

| Concern | Mechanism |
| --- | --- |
| Two schedulers fire same job | **Atomic claim** (`WHERE status='SCHEDULED'`) / `FOR UPDATE SKIP LOCKED` / leader / sharding |
| Worker crash mid-run | **Lease TTL** expiry → re-claimable; idempotent job avoids a duplicate effect |
| Duplicate execution effect | **At-least-once + idempotent jobs** (idempotency key) = exactly-once effect |
| Drift on recurring jobs | Compute `next_run` from **scheduled** time, not actual run time |
| Missed windows | Policy: skip / catch-up-once / run-all |
| Midnight thundering herd | Jitter scheduled times + rate-limit dispatch |
| Clock skew | DB/queue is the time authority; small delay tolerance |
| Poison job | Retry → DLQ after max attempts |

---

## 13. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Producer-Consumer** | Scheduler enqueues, workers consume | Decouple timing from execution |
| **Leader Election** | Single active scheduler (ZooKeeper/etcd/Redis lock) | Avoid duplicate polling |
| **Command** | A job = a command object (execute) | Uniform execution + queueing |
| **Strategy** | Retry/backoff policy, missed-window policy, due-finding (poll/ZSET/wheel) | Swap policies |
| **State** | Job lifecycle (SCHEDULED→PICKED→RUNNING→DONE/FAILED) | Guard transitions |
| **Observer / Pub-Sub** | Job events → notifications, history, DLQ | Decouple |
| **Template Method** | Common run skeleton (setup → execute → record → reschedule) | Reuse flow |
| **Factory** | Create job handlers per type | Extensible job types |
| **Lease / Lock** | Atomic claim + TTL lease | No double-run; crash recovery |
| **Timing Wheel** | Near-term timer management | Efficient O(1) scheduling |

---

## 14. Scaling & Failure

- **Shard/partition jobs** across scheduler nodes (by hash) or use a **leader** + workers → no overlap.
- **Atomic claim + lease TTL** → crashed worker's job re-runs after expiry (no stuck jobs, no double-run within lease).
- **Queue** absorbs bursts (midnight cron spike); workers scale independently; **rate-limit downstream**.
- **DB poll** can bottleneck → time-bucket + index, Redis ZSET, or timing wheel; **batch claims**.
- **Clock skew** across nodes → DB/queue is the time authority; allow small delay tolerance.
- **At-least-once + idempotent jobs** for correctness; **jitter** scheduled times to spread the herd.

---

## 15. Interview Cheat Sheet

> **"How do you find which jobs are due efficiently?"**
> "A durable job store with an index on `(status, next_run)`; poll the current time-bucket, or keep a **Redis ZSET** scored by fire time (`ZRANGEBYSCORE 0 now`). For near-term in-process timers, a **timing wheel** is O(1) insert/expire (hierarchical for far-future)."

> **"How do you avoid running a job twice across nodes?"**
> "**Atomic claim** — `UPDATE ... SET status='PICKED' WHERE job_id=? AND status='SCHEDULED'` (only one node wins), or `SELECT FOR UPDATE SKIP LOCKED`, plus a **lease TTL** so a crashed worker's job becomes re-claimable. Combine **at-least-once + idempotent jobs** for an exactly-once effect."

> **"Recurring jobs?"**
> "Parse the cron expression; on fire/completion compute `next_run` **from the scheduled time** (not now, to avoid drift) and reschedule. Missed windows → a policy: skip, catch-up-once, or run-all."

> **"Millions of jobs firing at midnight?"**
> "Queue absorbs the burst, workers scale horizontally, jobs sharded across schedulers, batch claims, **jitter scheduled times**, and rate-limit downstream so the fire spike doesn't overwhelm targets."

> **"Failures?"**
> "Retry with exponential backoff + jitter up to max attempts → then DLQ + alert. Lease expiry recovers crashed workers; timeouts kill runaway jobs."

---

## 16. Final Takeaways

- **Separate scheduling from execution** — scheduler finds due jobs + enqueues; workers run (scale independently).
- **Find due jobs** via indexed DB poll + time-bucketing, **Redis ZSET**, or **timing wheel**.
- **No double-run** via **atomic claim + lease TTL** (or leader/sharding); **at-least-once + idempotent = exactly-once effect**.
- **Recurring** = cron parse → `next_run` from scheduled time (avoid drift) + missed-window policy.
- **Retries** with backoff+jitter → **DLQ** on exhaustion; **jitter schedules** to beat the midnight herd.
- Patterns: Producer-Consumer, Leader Election, Command, Strategy, State, Lease/Lock, Timing Wheel.

### Related notes

- [Notification System](notification-system-design.md) — scheduled notifications + queue/worker split
- [Apache Kafka](../concepts/kafka.md) — delayed topics, DLQ, retries · [Idempotency](../concepts/idempotency.md)
