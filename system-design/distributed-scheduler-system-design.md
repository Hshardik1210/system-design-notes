# Distributed Job Scheduler — System Design

> **Core challenge:** run **jobs at the right time** — one-off (`run at T`) and **recurring** (cron: "every day 2am") — **reliably**, **exactly-once-ish**, and **at scale**, across a fleet of workers, surviving node failures without dropping or double-running jobs. Think: cron-as-a-service (AWS EventBridge Scheduler, Airflow triggers, Quartz cluster, Sidekiq/Celery beat).

---

## Contents

- [1. Mental Model](#1-mental-model)
- [2. Requirements](#2-requirements)
- [3. Architecture](#3-architecture)
- [4. Storing & Finding Due Jobs](#4-storing--finding-due-jobs)
- [5. Execution — Exactly-Once-ish & No Double-Run](#5-execution--exactly-once-ish--no-double-run)
- [6. Recurring Jobs (cron)](#6-recurring-jobs-cron)
- [7. Retries, Failures & DLQ](#7-retries-failures--dlq)
- [8. Data Model (all tables)](#8-data-model-all-tables)
- [9. API Design](#9-api-design)
- [10. Design Patterns (that can be used)](#10-design-patterns-that-can-be-used)
- [11. Scaling & Failure](#11-scaling--failure)
- [12. Interview Cheat Sheet](#12-interview-cheat-sheet)
- [13. Final Takeaways](#13-final-takeaways)

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
- Schedule **one-time** jobs (`runAt`) and **recurring** jobs (cron expression / fixed interval).
- Execute at (or shortly after) the scheduled time; **at-least-once** with idempotency (aim exactly-once-effect).
- Cancel/update jobs; view status/history; retries on failure.

**Non-functional**
- **Reliable** — never silently drop a job; **no double execution**.
- **Scalable** — millions of scheduled jobs, high fire rate at peaks (e.g. many jobs at midnight).
- **Timely** — fire within acceptable delay; **fault-tolerant** to node crashes.
- **Multi-tenant** isolation.

---

## 3. Architecture

```
API → Job Store (DB)               ← submit/cancel/update jobs
        │
   ┌────▼───────────────┐
   │ Scheduler / Poller  │  finds DUE jobs, enqueues them   (leader or partitioned)
   └────┬───────────────┘
        ▼
     Queue (Kafka/SQS)  ── decouples scheduling from execution
        ▼
   Worker pool  → run job → record result → if recurring, compute next_run
        ▼
   Result store + DLQ (failed jobs)
```

- **Separate scheduling from execution**: the scheduler only decides *what's due* and enqueues; **workers** do the actual work (scale independently — like the Notification system).

---

## 4. Storing & Finding Due Jobs

The scheduler must efficiently answer: *"which jobs are due now?"*

| Approach | How | Trade-off |
| --- | --- | --- |
| **DB poll + index** | `SELECT ... WHERE status='SCHEDULED' AND next_run <= now() LIMIT N` on an index `(status, next_run)` | Simple; DB load at scale; poll interval = min granularity |
| **Time-bucketing / partitioning** | Bucket jobs by minute/hour; poll only current bucket | Scales the poll; hot buckets at round times |
| **Redis sorted set (ZSET)** | score = `runAt` epoch; `ZRANGEBYSCORE 0 now` pops due jobs | Fast, in-memory; needs durability backing |
| **Timing wheel / hierarchical wheel** | In-memory wheel for near-term timers | Very efficient for short horizons (Kafka/Netty use this) |
| **Delay queue** | SQS delay / Kafka delayed topics | Offloads timing to the queue |

> Common answer: **DB (durable source of truth) + time-bucketed index/poll**, or a **Redis ZSET** as a fast due-index backed by the DB. Poll every second/minute for the current bucket.

```
every tick:
  due = jobs where next_run <= now() AND status = SCHEDULED   (current bucket)
  for each due job: claim + enqueue
```

---

## 5. Execution — Exactly-Once-ish & No Double-Run

Multiple scheduler nodes must not fire the same job twice.

| Technique | Detail |
| --- | --- |
| **Atomic claim** | `UPDATE jobs SET status='PICKED', locked_by=?, lock_expiry=now()+ttl WHERE job_id=? AND status='SCHEDULED'` → only one node wins (rows affected = 1) |
| **`SELECT ... FOR UPDATE SKIP LOCKED`** | Concurrent pollers each grab different rows without blocking |
| **Leader election** | One scheduler leader (via ZooKeeper/etcd/Redis lock) decides; avoids duplicate polling |
| **Partitioning/sharding** | Each scheduler owns a shard of jobs (by hash) → no overlap |
| **Idempotent execution** | Jobs carry an idempotency key; workers dedup → true at-least-once + idempotent = exactly-once **effect** |
| **Lease/heartbeat** | Locked job has a TTL; if the worker dies, lock expires → job re-claimable (no permanent stuck) |

> **Reality:** distributed exactly-once is hard → aim **at-least-once delivery + idempotent jobs**, with atomic claim + lease expiry so a crashed worker's job is safely retried.

---

## 6. Recurring Jobs (cron)

```
On job completion (or at claim time) for a recurring job:
   next_run = cronParser.next(cron_expr, from = scheduled_time)   # not from now, to avoid drift
   INSERT/UPDATE next occurrence with status = SCHEDULED
```

- Compute the **next fire time** from a cron expression (Quartz-style parser).
- Base `next_run` on the **scheduled** time, not actual run time, to avoid drift.
- Guard against **missed windows** (system down over a fire time): policy = skip, run-once-catchup, or run-all-missed.

---

## 7. Retries, Failures & DLQ

```
job fails → attempt++
   if attempt < max: reschedule next_run = now + backoff (with jitter)
   else: status = FAILED → DLQ + alert
```

- Exponential backoff **with jitter** (avoid thundering herd — see Kafka note).
- **DLQ** for jobs that exhaust retries; manual inspection/replay.
- **Timeouts** — a job running too long → kill + retry (lease expiry handles crashed workers).

---

## 8. Data Model (all tables)

```sql
CREATE TABLE jobs (
    job_id        BIGINT PRIMARY KEY,
    tenant_id     BIGINT,
    type          VARCHAR(50),               -- ONE_TIME, RECURRING
    payload       JSONB,                     -- what to run / target
    cron_expr     VARCHAR(100),              -- for recurring
    next_run      TIMESTAMP NOT NULL,        -- when to fire next
    status        VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED', -- SCHEDULED, PICKED, RUNNING, DONE, FAILED, CANCELLED
    locked_by     VARCHAR(100),              -- scheduler/worker node id
    lock_expiry   TIMESTAMP,                 -- lease TTL
    attempt       INT DEFAULT 0, max_attempts INT DEFAULT 5,
    created_at    TIMESTAMP DEFAULT now()
);
CREATE INDEX idx_jobs_due ON jobs(next_run) WHERE status = 'SCHEDULED';

CREATE TABLE job_runs (                        -- execution history / audit
    run_id    BIGINT PRIMARY KEY,
    job_id    BIGINT NOT NULL,
    started_at TIMESTAMP, finished_at TIMESTAMP,
    status    VARCHAR(20),                     -- SUCCESS, FAILED, TIMEOUT
    result    JSONB, error TEXT, worker_id VARCHAR(100)
);
CREATE INDEX idx_runs_job ON job_runs(job_id, started_at DESC);

CREATE TABLE dead_letter_jobs ( job_id BIGINT PRIMARY KEY, reason TEXT, moved_at TIMESTAMP );

-- Optional: Redis ZSET  due_jobs (score = next_run epoch) as the fast due-index
```

> **Tables to consider:** jobs, job_runs (history), dead_letter_jobs, tenants, locks (or lock columns), plus optional Redis ZSET for the due-index.

---

## 9. API Design

```
POST   /v1/jobs        { type, payload, runAt | cronExpr, maxAttempts }  → { jobId }
GET    /v1/jobs/{id}    GET /v1/jobs/{id}/runs
DELETE /v1/jobs/{id}    # cancel
PATCH  /v1/jobs/{id}    # reschedule / update
```

---

## 10. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Producer-Consumer** | Scheduler enqueues, workers consume | Decouple timing from execution |
| **Leader Election** | Single active scheduler (ZooKeeper/etcd/Redis lock) | Avoid duplicate polling |
| **Command** | A job = a command object (execute/undo) | Uniform execution + queueing |
| **Strategy** | Retry/backoff policy, missed-window policy, due-finding (poll vs ZSET vs wheel) | Swap policies |
| **State** | Job lifecycle (SCHEDULED→PICKED→RUNNING→DONE/FAILED) | Guard transitions |
| **Observer / Pub-Sub** | Job events → notifications, history, DLQ | Decouple |
| **Template Method** | Common run skeleton (setup → execute → record → reschedule) | Reuse flow |
| **Factory** | Create job handlers per type | Extensible job types |
| **Lease / Lock** | Atomic claim + TTL lease | No double-run; crash recovery |
| **Timing Wheel** | Near-term timer management | Efficient scheduling |

---

## 11. Scaling & Failure

- **Shard/partition jobs** across scheduler nodes (by hash) or use a **leader** + workers → no overlap.
- **Atomic claim + lease TTL** → crashed worker's job re-runs after expiry (no stuck jobs, no double-run within lease).
- **Queue** absorbs bursts (midnight spike of cron jobs); workers scale independently.
- **DB poll** can bottleneck → time-bucket + index, or Redis ZSET; batch claims.
- **Clock skew** across nodes → rely on the DB/queue as the time authority; allow small delay tolerance.
- **At-least-once + idempotent jobs** for correctness.

---

## 12. Interview Cheat Sheet

> **"How do you find which jobs are due efficiently?"**
> "Durable job store with an index on `(status, next_run)`; poll the current time-bucket, or keep a Redis ZSET scored by fire time and `ZRANGEBYSCORE 0 now`. For near-term timers, a timing wheel is very efficient."

> **"How do you avoid running a job twice across nodes?"**
> "Atomic claim — `UPDATE ... SET status='PICKED' WHERE job_id=? AND status='SCHEDULED'` (only one node wins), or `SELECT FOR UPDATE SKIP LOCKED`, plus a lease TTL so a crashed worker's job becomes re-claimable. Combine at-least-once delivery with idempotent jobs for exactly-once effect."

> **"How do recurring jobs work?"**
> "Parse the cron expression; on fire (or completion) compute the next run from the scheduled time (not now, to avoid drift) and reschedule. Handle missed windows with a policy: skip, catch-up-once, or run-all."

> **"Handling failures?"**
> "Retry with exponential backoff + jitter up to max attempts; then DLQ + alert. Lease expiry recovers crashed workers; timeouts kill runaway jobs."

> **"Scale to millions of jobs firing at midnight?"**
> "Queue absorbs the burst, workers scale horizontally, jobs sharded across schedulers, batch claims, and rate-limit downstream so the fire spike doesn't overwhelm targets."

---

## 13. Final Takeaways

- **Separate scheduling from execution** — scheduler finds due jobs + enqueues; workers run (scale independently).
- **Find due jobs** via indexed DB poll + time-bucketing, Redis ZSET, or timing wheel.
- **No double-run** via **atomic claim + lease TTL** (or leader/sharding); **at-least-once + idempotent** = exactly-once effect.
- **Recurring** = cron parse → next_run from scheduled time (avoid drift) + missed-window policy.
- **Retries** with backoff+jitter → **DLQ** on exhaustion.
- Patterns: Producer-Consumer, Leader Election, Command, Strategy, State, Lease/Lock, Timing Wheel.

### Related notes

- [Notification System — System Design](notification-system-design.md) — scheduled notifications + queue/worker split
- [Apache Kafka](../concepts/kafka.md) — delayed topics, DLQ, retries · [Idempotency](../concepts/idempotency.md)
