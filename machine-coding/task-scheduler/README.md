# ⏰ Task Scheduler / Cron

Run jobs at/after a scheduled time, including **recurring** jobs, without busy-waiting.

## Mechanics
- **Min-heap** (priority queue) orders jobs by `nextRun` time — earliest on top.
- A **worker thread** waits on a condition variable **until the head job is due**. If a new, earlier job is scheduled, `signal`/`notify` wakes the worker so it re-evaluates. This is efficient (no polling) and responsive.
- Jobs run **outside the lock** so a slow job doesn't block scheduling.
- A **recurring** job re-enqueues itself with `nextRun += interval` after each run; one-shot jobs simply drop out.

```
schedule ─▶ [heap by nextRun] ─▶ worker waits until due ─▶ run ─▶ (recurring? re-enqueue)
```

## Design patterns
| Pattern | Where | Why |
| --- | --- | --- |
| Producer/consumer | schedule() vs worker loop | Decouple submission from execution |
| Priority queue | heap by nextRun | O(log n) "next due job" |

> A distributed version (leases, exactly-once-ish, retries/DLQ) is in `system-design/distributed-scheduler-system-design.md`.

## Run
```bash
cd java && javac Main.java && java Main
cd cpp && g++ -std=c++17 main.cpp -o main -pthread && ./main
```
