# Distributed Job Scheduler — System Design

> **Core challenge:** run **jobs at the right time** — one-off (`run at T`) and **recurring** (cron: "every day 2am") — **reliably**, **exactly-once-ish**, and **at scale**, across a fleet of workers, surviving node failures without dropping or double-running jobs. Think: cron-as-a-service (AWS EventBridge Scheduler, Airflow triggers, Quartz cluster, Sidekiq/Celery beat).

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
API → Job Store (DB)                 ← submit/cancel/update jobs
        │
   ┌────▼────────────────┐
   │ Scheduler / Poller   │  finds DUE jobs, claims + enqueues   (leader or partitioned)
   └────┬────────────────┘
        ▼
     Queue (Kafka/SQS)     ── decouples scheduling from execution
        ▼
   Worker pool  → run job → record result → if recurring, compute + persist next_run
        ▼
   Result store (job_runs) + DLQ (exhausted jobs)
```

- **Separate scheduling from execution:** the scheduler only decides *what's due* and enqueues; **workers** do the actual work + scale independently (like the Notification system).

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

> **Common answer:** **DB (durable truth) + time-bucketed index/poll**, or a **Redis ZSET** as a fast due-index backed by the DB. In-process near-term timers use a **timing wheel**.

```
every tick:
  due = jobs where next_run <= now() AND status = SCHEDULED   (current bucket)
  for each due job: claim atomically + enqueue
```

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

---

## 7. Recurring Jobs (cron)

```
On job completion (or at claim time) for a recurring job:
   next_run = cronParser.next(cron_expr, from = scheduled_time)   # from SCHEDULED time, not now → no drift
   INSERT/UPDATE the next occurrence with status = SCHEDULED
```

- Parse the cron expression (Quartz-style) → next fire time; base it on the **scheduled** time to avoid drift.
- **Missed windows** (system down over a fire time) → policy: **skip**, **run-once-catchup**, or **run-all-missed** (per job).
- Timezone/DST-aware cron for wall-clock schedules.

---

## 8. Retries, Failures & DLQ

```
job fails → attempt++
   if attempt < max: reschedule next_run = now + backoff (WITH JITTER)
   else: status = FAILED → DLQ + alert
```

- Exponential backoff **with jitter** (avoid a thundering herd of simultaneous retries — see Kafka note).
- **DLQ** for jobs that exhaust retries → manual inspection/replay.
- **Timeouts** — a job running too long → kill + retry (lease expiry handles crashed workers).

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
