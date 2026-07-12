# Notification System — HLD & LLD

> Companion to **Notification System — System Design**. This doc is split into **Part A: High-Level Design (HLD)** — the big-picture architecture — and **Part B: Low-Level Design (LLD)** — concrete schema, contracts, classes, state machines, and algorithms.

> **How to read this doc:** each section gives the dense interview summary first, then a **deep dive** (annotated Java and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand. Running example throughout: an app (think Amazon/Swiggy) that fires **order confirmations, OTPs, and promotions** across push, SMS, email, and in-app.

---

# PART A — High-Level Design (HLD)

---

## Contents

- [A1. Scope & Core Goal](#a1-scope--core-goal)
- [A2. Architecture Overview](#a2-architecture-overview)
- [A3. Services & Responsibilities](#a3-services--responsibilities)
- [A4. Communication — Sync vs Async](#a4-communication--sync-vs-async)
- [A5. Storage Strategy](#a5-storage-strategy)
- [A6. Scalability & Availability](#a6-scalability--availability)
- [A7. Tech Stack (one option)](#a7-tech-stack-one-option)
- [A8. Worker Deployment](#a8-worker-deployment)
- [A9. Service Ownership & DB Access](#a9-service-ownership--db-access)
- [A10. Kafka Lag](#a10-kafka-lag)
- [A11. DB Bottlenecks](#a11-db-bottlenecks)
- [B1. Database Schema (DDL)](#b1-database-schema-ddl)
- [B2. API Contracts](#b2-api-contracts)
- [B3. Notification Service — Class Design (detailed)](#b3-notification-service--class-design-detailed)
- [B4. State Machines](#b4-state-machines)
- [B5. Core Algorithms](#b5-core-algorithms)
- [B6. Sequence — Happy Path (order confirmed)](#b6-sequence--happy-path-order-confirmed)
- [B7. Sequence — Retry & DLQ](#b7-sequence--retry--dlq)
- [B8. Sequence — Bulk Campaign Fan-Out](#b8-sequence--bulk-campaign-fan-out)
- [B9. Sequence — Scheduled Reminder](#b9-sequence--scheduled-reminder)
- [B10. Concurrency & Correctness Summary](#b10-concurrency--correctness-summary)
- [B11. Caching Design](#b11-caching-design)
- [B12. Error Handling & Edge Cases](#b12-error-handling--edge-cases)
- [B13. Cheat-Sheet Mapping (HLD ↔ LLD)](#b13-cheat-sheet-mapping-hld--lld)

---

## A1. Scope & Core Goal

- **Functional:** send notifications to one or many users across push, email, SMS, and in-app; respect preferences; schedule reminders; store history.
- **Core goal:** deliver the **right message** on the **right channel** at the **right time** — **without duplicates**, **without spam**, and **without blocking** source systems.
- **Style:** async delivery (at-least-once + idempotency); strong consistency on notification record creation; eventual consistency on delivery status.

> Full requirements + estimation live in the main **Notification System — System Design** note (§2, §3).

### What are we actually building?

Think of the little messages your phone gets all day: *"Your order #789 is confirmed"*, *"Your OTP is 458213"*, *"FLASH SALE — 50% off today!"*. Some arrive as a **push** banner, some as an **SMS**, some as an **email**, some show up in the app's **bell icon** (in-app). A notification system is the shared service that every part of your company hands messages to, and it figures out *how* to actually get each one to the user.

Why build one shared service instead of letting the Order team call Twilio themselves?

- **One place for the rules** — opt-outs, "don't SMS at 2 AM", "max 5 promos a day", templates, retries. Nobody re-invents them.
- **The source system shouldn't wait.** When you pay, checkout must feel instant. It should *not* freeze while an SMS is dialed out. So the notification system takes the request, says "got it" (`202 Accepted`), and delivers in the background.

The core goal in one breath: **right message, right channel, right time — no duplicates, no spam, and never block the system that asked.**

#### Q: What do "right channel" and "right time" really mean?

- **Right channel** = respect the user's choice. If they turned off promo emails but kept push on, a sale goes only to push. An **OTP** almost always goes to SMS/push because it's time-critical.
- **Right time** = **quiet hours** and **scheduling**. A "your movie starts in 1 hour" reminder must fire at T-1hr, not now; a marketing blast shouldn't wake someone at 3 AM.

#### Q: Why "at-least-once + idempotency" instead of "exactly-once"?

Networks fail mid-call, so the safe default is **at-least-once**: if unsure, try again (better a rare duplicate attempt than a lost OTP). To stop the user actually *seeing* two copies, every notification carries an **idempotency key** (e.g. `userId:type:orderId`) so a retry maps to the same record instead of creating a new one. (Details in B5/B10.)

---

## A2. Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Event Producers                               │
│              Order · Payment · User · Campaign · Chat                │
└───────────────────────────────┬─────────────────────────────────────┘
                                │ HTTP or Kafka domain events
                                ▼
                         ┌─────────────┐
                         │ API Gateway │  auth · rate limit · routing
                         └──────┬──────┘
                                │
        ┌───────────────────────┼───────────────────────┐
        ▼                       ▼                       ▼
 ┌──────────────┐      ┌──────────────┐       ┌──────────────┐
 │ Notification │      │  Preference  │       │   Template   │
 │  API Service │◄────►│   Service    │       │   Service    │
 └──────┬───────┘      └──────┬───────┘       └──────┬───────┘
        │                     │                      │
        └─────────────────────┼──────────────────────┘
                              ▼
                    ┌──────────────────┐
                    │ Notification DB  │
                    │  + Redis cache   │
                    └────────┬─────────┘
                             │ enqueue
                             ▼
                    ┌──────────────────┐
                    │      Kafka       │
                    │ (channel × prio) │
                    └────────┬─────────┘
                             │
     ┌───────────┬───────────┼───────────┬───────────┐
     ▼           ▼           ▼           ▼           ▼
┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌──────────┐
│  Push   │ │  Email  │ │   SMS   │ │ In-app  │ │ Scheduler│
│ Worker  │ │ Worker  │ │ Worker  │ │ Worker  │ │   Job    │
└────┬────┘ └────┬────┘ └────┬────┘ └────┬────┘ └────┬─────┘
     │           │           │           │           │
     ▼           ▼           ▼           ▼           │
  FCM/APNS   SES/SG      Twilio      DB + Redis       │
     │           │           │           │           │
     └───────────┴───────────┴───────────┴───────────┘
                             │
                             ▼
                    ┌──────────────────┐
                    │ WebSocket Service│ ──► connected clients
                    └──────────────────┘
                             │
                             ▼
                    ┌──────────────────┐
                    │   DLQ (per ch.)  │
                    └──────────────────┘
```

### Reading the diagram top to bottom

Producers (Order, Payment, Campaign) send "notify this user" requests. The API Gateway + Notification API validate the request, look up the user's preferences, render the message text, persist a copy, and publish to the right Kafka topics. Separate workers each consume one topic and hand the message to a provider (FCM for push, Twilio for SMS, SES for email). If a worker can't deliver after retries, the message goes to a **DLQ** instead of being lost.

Top-to-bottom, the flow is:

```
producer  →  gateway  →  Notification API (decide + save)  →  Kafka bins  →  channel workers  →  providers  →  user
```

- **Producers** = anything that has news for the user (order placed, payment done, marketing campaign).
- **Notification API** = the brain: validate → dedup → resolve prefs/templates → save to DB → enqueue.
- **Kafka** = the buffer between "decided" and "delivered", split into bins by channel and priority.
- **Workers** = one per channel; each just delivers and records the outcome.
- **WebSocket service** = the extra pipe that pushes in-app messages to a phone that's *currently open*.

#### Q: What is "fan-out" here, and where does it happen?

**Fan-out = one input turns into many outputs.** It shows up in two places:

1. **Channel fan-out** — one "order confirmed" request → a push job **and** an email job **and** an in-app job (three attempts from one notification).
2. **Audience fan-out** — one campaign → millions of per-user notifications.

```
one request ──┬──► PUSH job   (Kafka: notification-normal)
              ├──► EMAIL job
              └──► IN_APP job     ← channel fan-out (this section)

one campaign ──► 10M user notifications   ← audience fan-out (see B8)
```

The design keeps each fanned-out piece **independent**: push succeeding and email failing are separate rows, so one bad channel never blocks the others.

#### Q: Why put Kafka in the middle at all — why not call the workers directly?

Because delivery is **slow and flaky** (provider timeouts, rate limits, spikes). Kafka lets the API finish in milliseconds and lets each channel drain at its own pace. During a flash sale, 10M messages pile up **in the queue**, not in the API's memory, and push workers can scale up without touching the SMS path.

---

## A3. Services & Responsibilities

| Service | Responsibility | Data store |
| --- | --- | --- |
| **API Gateway** | Auth, rate limiting, routing | — |
| **Notification API** | Validate, dedup, resolve prefs/templates, persist, enqueue | Notification DB |
| **Preference Service** | User opt-in/opt-out per type + channel | Notification DB + Redis cache |
| **Template Service** | Render title/body per type, channel, language | Notification DB + Redis cache |
| **Push Worker** | Deliver to FCM/APNS; manage device token lifecycle | consumes Kafka |
| **Email Worker** | Deliver via SES/SendGrid | consumes Kafka |
| **SMS Worker** | Deliver via Twilio/SNS; provider fallback | consumes Kafka |
| **In-app Worker** | Write in-app row; publish to WebSocket topic | Notification DB + Redis |
| **WebSocket Service** | Maintain connections; push real-time updates | Redis (connection map) |
| **Campaign Service** | Bulk fan-out: segment → batch → enqueue | Campaign DB |
| **Scheduler Job** | Poll due scheduled notifications; dispatch | Notification DB |

> The **Notification API** orchestrates; **workers deliver**. Never call FCM/Twilio from the API synchronously.

### Who does what

The **Notification API** takes the request, checks templates and preferences, persists a record, and publishes a job per channel. The **channel workers** each own one channel and only process jobs for that channel. The API never calls a provider itself, and a worker never accepts new requests. That split is the whole point: one clear owner for "decide", one clear owner for "deliver".

```java
// The orchestrator (API): decides, saves, enqueues — then returns immediately.
class NotificationOrchestrator {
    SendResult send(SendRequest req) {
        var channels = resolvePreferences(req);      // check preferences
        var text     = renderTemplates(req, channels);// render templates
        var saved    = db.save(req, channels, text);  // persist the record
        for (Channel ch : channels)
            queue.publish(topicFor(ch), saved.id, ch);// enqueue a job per channel
        return SendResult.accepted(saved.id);         // "202 — accepted!"
    }
}

// A worker: consumes its own jobs, delivers, records the result. Never accepts requests.
class PushWorker {
    @KafkaListener(topics = "notification-normal")
    void onJob(DeliveryJob job) {                     // only push jobs reach here
        fcm.send(job.token, job.title, job.body);     // do the actual delivery
        db.markSent(job.notificationId, Channel.PUSH);
    }
}
```

#### Q: Why must the API never call FCM/Twilio directly (synchronously)?

Because providers are **slow and unreliable**. If the API waited on Twilio for every request:

- The **caller** (checkout) is blocked → the whole app feels frozen.
- One slow provider (SMS) drags down **all** channels.
- A spike of 1M messages = 1M open connections held in the API → it falls over.

By handing off to Kafka and returning `202`, the API stays fast and each channel fails/retries on its own. This is exactly why "**API orchestrates, workers deliver**" is the golden rule.

#### Q: Preference Service, Template Service — why separate services and not just tables?

They're **hot, shared, and independently owned**. Every single notification reads preferences and a template, so they're cache-backed for speed, and other teams (a settings page, a marketing template editor) also touch them. Splitting them keeps that logic in one owner instead of copy-pasted into every producer.

---

## A4. Communication — Sync vs Async

| Interaction | Style | Why |
| --- | --- | --- |
| Source → Notification API | **Sync (HTTP 202)** or **async (Kafka event)** | source shouldn't wait for delivery |
| Notification API → Workers | **Async (Kafka)** | decouple, scale, retry |
| Workers → External providers | **Sync (HTTP to FCM/Twilio)** | provider APIs are request/response |
| In-app Worker → WebSocket Service | **Async (Kafka / Redis pub-sub)** | don't block DB write on connection lookup |
| Client → In-app API | **Sync (REST)** | user fetching notification feed |
| Client ↔ WebSocket Service | **Sync (persistent WebSocket)** | real-time in-app updates |

> **Rule:** everything between "event received" and "provider called" is async. The API returns **202 Accepted** immediately after enqueue.

**Auth per boundary:** internal service → API uses **mTLS or signed service tokens (OAuth client-creds/JWT)**; user → in-app/preferences API uses the user's auth token (can only touch their own data); worker → provider uses secrets from a **vault** (rotated), never env files.

**Dual-write caveat:** `insert notification` (DB) + `publish` (Kafka) are not atomic. Guard with an **outbox** or a **reconciliation sweeper** for `PENDING` rows (see B5, B12).

### Sync vs async — only wait when you need the reply

**Async** = fire-and-forget: send the request and move on without waiting for the result. **Sync** = wait for the reply before continuing. The rule of thumb: **use sync only when you genuinely need the reply right now; otherwise fire-and-forget.**

Mapping that to the system:

```
Checkout → Notification API      : sync-ish, but the API replies "202 Accepted" instantly
                                    (it does NOT wait for the SMS to be delivered)
Notification API → Workers       : async (Kafka)  — enqueue and move on
Worker → Twilio/FCM              : sync (HTTP)     — you must know if the provider accepted it
Client ↔ WebSocket               : sync/persistent — a live open pipe for instant in-app updates
```

```java
// The API's job ends at "accepted", not at "delivered".
@PostMapping("/v1/notifications")
ResponseEntity<?> send(@RequestBody SendRequest req) {
    var id = orchestrator.send(req);          // save + enqueue only
    return ResponseEntity.accepted().body(id); // 202 — "I've got it, I'll handle delivery"
}
```

#### Q: What is the "dual-write" problem in one sentence?

Saving the row to the DB and publishing to Kafka are **two separate writes**; if the service crashes *after* the DB insert but *before* the Kafka publish, you get a notification that's saved but never delivered — stuck at `PENDING` forever.

```
insert notification (PENDING)   ✅ committed
        💥 crash here
publish to Kafka                ❌ never happened   → orphaned PENDING row
```

Two standard fixes (both in B5/B12):

- **Outbox pattern** — write the row *and* an "to-publish" outbox record in the **same DB transaction**; a separate poller reads the outbox and publishes. Now they can't disagree.
- **Reconciliation sweeper** — a background job periodically re-enqueues any `PENDING` row older than N minutes. Safe because workers are idempotent (they skip if the attempt is already `SENT`).

---

## A5. Storage Strategy

| Need | Choice | Reason |
| --- | --- | --- |
| Notification records + history | **RDBMS (Postgres/MySQL)** | ACID, unique constraints for idempotency, audit trail |
| Preferences + templates (hot read) | **Redis cache** | read on every notification; low latency |
| Message queue | **Kafka** | high throughput, partitioning by `user_id`, replay, DLQ topics |
| Rate limit counters | **Redis** | atomic INCR + TTL per user/day/type |
| WebSocket connection map | **Redis** | fast lookup `user_id → connection_id` |
| Device tokens | **RDBMS** (source of truth) + optional Redis | multi-device fan-out; invalidate on bad token |
| Campaign progress | **RDBMS** | durable counts (sent, failed, total) |
| Unread count | **Redis counter** `unread:{userId}` | O(1) bell-icon read; DB is source of truth |
| Old notifications archive | **S3 / cold storage** | partition by month; TTL after 90 days |

**Partitioning:** Kafka topics partitioned by `user_id`. Notification DB sharded by `user_id` at very large scale.

**IDs:** `notification_id` is a **Snowflake / UUIDv7** (app-generated, time-sortable) — not a single DB auto-increment sequence, which bottlenecks writes and blocks sharding.

**PII at rest:** encrypt `device_token`, `phone`, `email`, and sensitive rendered bodies (KMS / column encryption); never log message bodies or raw tokens; support GDPR delete via time-partitioned purge.

### Right tool for each job

Each store fits a different need. **Postgres (RDBMS)** holds the permanent, must-never-lose records with ACID guarantees and unique constraints. **Redis** holds data you read constantly and can rebuild if lost — cached preferences, today's counters. **Kafka** carries work between services. **S3** stores old data you rarely query but must retain. You wouldn't keep an audited record in a cache, or a high-churn counter in the primary DB.

| Data | Home | Why |
| --- | --- | --- |
| The notification record + history | **Postgres** | must be exact, audited, never lost — use `UNIQUE` to stop dupes |
| Preferences & templates | **Redis** (cache) | read on *every* send; DB is the truth, Redis is the fast copy |
| The work to deliver | **Kafka** | a durable belt that absorbs spikes and can replay |
| "how many promos today" counter | **Redis** | atomic `INCR` + auto-expire at midnight |
| Old notifications (90+ days) | **S3** | cheap cold storage; nobody queries them live |

#### Q: Why not just keep preferences in Postgres and skip Redis?

You *could* — Postgres is the source of truth. But you read preferences on **every** notification, millions of times, and the answer rarely changes. Hitting the DB every time wastes it. So we **cache-aside**: read Redis first, fall back to DB on a miss, and **invalidate** the cache when the user updates prefs. Same story for templates.

#### Q: Why is the ID a Snowflake/UUIDv7 and not a plain `AUTO_INCREMENT`?

A single auto-increment counter is a **write bottleneck** (every insert waits on one sequence) and it **breaks sharding** (two shards can't share one counter without coordinating). Snowflake/UUIDv7 IDs are generated by the app, are **time-sortable** (so `ORDER BY id` ≈ `ORDER BY created_at`), and let any node mint IDs without asking anyone.

```java
long id = Snowflake.next();   // e.g. timestamp-bits | machine-bits | sequence-bits
// time-sortable → recent notifications sort naturally; no central counter to fight over
```

---

## A6. Scalability & Availability

- **Traffic spikes (flash sale)** → Kafka absorbs backlog; autoscale push workers independently.
- **Channel isolation** → SMS provider slow doesn't block push workers (separate topics + consumer groups).
- **Priority under load** → drain high-priority topics first; throttle low-priority.
- **No SPOF** → multi-AZ DB, Kafka cluster, N stateless API/worker instances behind LB.
- **Graceful degradation** → if email provider down, push + in-app still deliver; failed channel → retry/DLQ without blocking others.
- **Campaign fan-out** → batch pipeline, never single API call for millions of users.

> Details in main note §11, §19–§21.

### Surviving spikes and outages

Under a spike, you don't rebuild the system — you **autoscale workers** and let the Kafka queue hold the backlog calmly. If one provider dies (email down), the other channels (push, in-app) keep delivering; you don't take the whole system down.

The four ideas, in plain terms:

- **Kafka absorbs the surge** — a flash sale dumps 10M messages into the queue; workers drain it steadily instead of everything hitting providers at once.
- **Channel isolation** — push, email, SMS are **separate topics + separate worker pools**. A slow SMS provider only backs up SMS; push keeps flying.
- **Priority** — OTP/payment ("critical") topics are drained first; marketing ("low") is throttled or waits.
- **No single point of failure** — multiple copies of every stateless piece behind a load balancer, DB and Kafka replicated across availability zones.

#### Q: If a channel's provider goes down, does the user get nothing?

No — that's **graceful degradation**. Channels are independent, so if email is down, push + in-app still deliver *now*, and the email attempt simply retries (backoff → DLQ) without blocking the rest. The user still hears about their order; they just might get the email a bit later.

```
"Order confirmed"  ─┬─► PUSH   ✅ delivered now
                    ├─► IN_APP ✅ delivered now
                    └─► EMAIL  ⏳ provider down → retry later (others unaffected)
→ notification status = PARTIALLY_SENT, not FAILED
```

---

## A7. Tech Stack (one option)

| Layer | Choice |
| --- | --- |
| API / Workers | Java/Spring Boot or Go |
| DB | PostgreSQL (sharded by `user_id` at scale) |
| Cache / rate limits | Redis (cluster) |
| Messaging | Kafka (+ DLQ topics) |
| Push | FCM + APNS |
| Email | AWS SES or SendGrid |
| SMS | Twilio (+ AWS SNS fallback) |
| Real-time | WebSocket service (Node/Go) + Redis |
| Background jobs | Scheduler cron + Kafka consumers |
| Infra | Kubernetes + LB |

### Why these picks (and none are sacred)

None of these are magic — each is just "the popular, boring, reliable choice" for its job, and every one is swappable behind a port (see B3.5):

- **Spring Boot / Go** — fast to write services + Kafka consumers; either is fine.
- **Postgres** — rock-solid ACID + unique constraints (our idempotency guard).
- **Redis** — the go-to for caches, counters, and connection maps.
- **Kafka** — the default durable, replayable, partitioned queue.
- **FCM/APNS, SES/SendGrid, Twilio/SNS** — the actual delivery companies for push, email, SMS.

> The exam-day point isn't the brand names — it's the **shape**: a durable DB for records, a cache for hot reads, a queue for decoupling, and pluggable provider adapters. Swap ClickHouse for Postgres or RabbitMQ for Kafka and the design still stands.

---

## A8. Worker Deployment

| Model | Example | Production fit |
| --- | --- | --- |
| **Same EC2, API + worker** | One box runs both processes | Prototypes only |
| **Same repo, separate process** | `api-server.js` vs `push-worker.js` on different EC2 | Common for startups |
| **Separate deployments** | `notification-api-deployment`, `push-worker-deployment`, etc. | **Recommended at scale** |

```
EC2-A, EC2-B  →  Notification API (3 instances)
EC2-C … EC2-N →  Push workers (100 instances)
EC2-O, EC2-P  →  Email workers (20 instances)
EC2-Q         →  SMS workers (5 instances)
```

All may share one codebase; they run **different commands** and scale **independently**.

> API must never synchronously call FCM/Twilio — return `202 Accepted` after enqueue.

### One codebase, many deployments

All the workers can share one codebase/repo, but each runs as a **separate deployment** you scale independently. When push volume spikes, you add push-worker instances — you don't touch the SMS workers.

Concretely, "same code, different entry command" looks like:

```bash
# same jar / image — the START COMMAND decides what this box becomes
java -jar app.jar --role=api            # Notification API
java -jar app.jar --role=push-worker    # Push worker  (scale to 100)
java -jar app.jar --role=email-worker   # Email worker (scale to 20)
java -jar app.jar --role=sms-worker     # SMS worker   (scale to 5)
```

#### Q: Why scale push workers to 100 but SMS to only 5?

Because the **volume and speed differ wildly**. Push is huge and fast (FCM is quick, everyone has the app), so it needs many workers. SMS is lower volume, expensive, and Twilio rate-limits you, so 5 is plenty. Decoupled deployments let you **right-size each channel** instead of scaling everything together.

#### Q: "EC2 boundary ≠ service boundary" — what does that mean?

Where the code *physically runs* (which box/pod) is a **deployment** decision; which service *owns* the logic is a **design** decision. You might run API + worker on one box in a prototype (same machine, still two services), or on 100 separate pods in production (same two services, spread out). The ownership doesn't change — only the placement does. This matters for the next section (who's allowed to write to which DB).

---

## A9. Service Ownership & DB Access

```
EC2 boundary  ≠  service boundary
Service ownership  →  decides DB access
```

| Component | Owns DB | Can write |
| --- | --- | --- |
| Notification API + all workers | `notification_db` | notifications, attempts, devices |
| Order Service | `order_db` | orders only |
| Payment Service | `payment_db` | payments only |

```
Push Worker (EC2-C)  →  UPDATE notification_db SET status='RETRYING'  ✅
Push Worker (EC2-C)  →  UPDATE order_db                               ❌
```

Cross-service updates: **API call** or **Kafka event** — never direct DB write to another service's database.

Retry flow stays entirely inside Notification Service:

```
Worker fails  →  update notification_db  →  republish notificationId to Kafka retry topic
```

### Each service owns its own database

Each service reads/writes **only its own database**. To change another service's data, it sends an **API call or a Kafka event** — it never writes directly to another service's tables.

```java
// ✅ Allowed — Notification service writing its OWN db
notificationDb.update("UPDATE notifications SET status='RETRYING' WHERE id=?", id);

// ❌ Forbidden — reaching into another service's db
orderDb.update("UPDATE orders SET ...");        // NO — that's the Order service's DB

// ✅ The right way to affect the Order service:
orderServiceClient.notifyDelivered(orderId);    // ask them (API)
//   or
kafka.publish("order-events", new DeliveredEvent(orderId)); // tell them (event)
```

#### Q: Why is this rule such a big deal?

Because shared databases create **hidden coupling**: if two services both write the `orders` table, a schema change by one silently breaks the other, and nobody can reason about who changed what. Keeping each service the sole writer of its data means you can evolve, shard, or replace one service without a chain reaction. It's the backbone of microservices.

#### Q: A retry sounds cross-service — isn't the worker touching two systems?

No. A retry is **entirely inside** the Notification service: the worker updates `notification_db` (its own) and republishes the `notificationId` to a Kafka **retry topic** (its own queue). The failed *provider* call (FCM/Twilio) is an external HTTP call, not a database write — so the "own your DB" rule is never broken.

---

## A10. Kafka Lag

**Lag** = `latest_offset - consumer_offset` per partition.

| Symptom | Risk |
| --- | --- |
| Temporary lag, catches up | OK |
| Growing lag on `notification-high` | OTP/payment delayed — critical |
| Lag on `notification-low` | Marketing delayed — often OK |

**Mitigations:**

1. Scale consumers (max = partition count in consumer group)
2. Increase partitions before you need them
3. Separate topics: `notification-critical`, `notification-high`, `notification-normal`, `notification-low`
4. Dedicated worker pools per priority
5. Throttle campaign producers (50k/min, not 10M burst)
6. Batch consume + batch DB update
7. DLQ poison messages
8. Monitor **oldest message age**, not just lag count

### Kafka lag = the backlog of unprocessed messages

"Lag" is simply *how many messages are waiting that you haven't processed yet*. A small backlog after a busy spike is fine — you'll catch up. A backlog that **keeps growing** means messages arrive faster than you process them, and you'll never catch up without adding capacity.

```
lag = latest_offset − consumer_offset
    = "newest message put in the bin"  −  "how far this worker has read"
    = number of messages still waiting
```

- **Steady or shrinking lag** → healthy, workers keep pace.
- **Growing lag on the *critical* topic** (OTP/payment) → alarm: users are getting OTPs late.
- **Growing lag on *marketing*** → usually fine; promos can wait.

#### Q: Why watch "oldest message age" instead of just the lag count?

Because a big count on a **fast** topic may clear in seconds, while a small count on a **stuck** topic could be an hour old. **Age answers the real question — "how long is a user actually waiting?"** A 3-message backlog where the oldest is 45 minutes old is a much worse OTP experience than a 50,000-message backlog that's draining in real time.

#### Q: I added more workers but lag won't drop — why?

Two classic ceilings:

1. **Partition cap** — a topic's max parallelism is its **partition count**. 8 partitions = at most 8 useful consumers in a group; a 9th just sits idle. Fix: add partitions (do it *before* you need them).
2. **The DB became the new bottleneck** — more workers = more concurrent writes hammering Postgres. You moved the traffic jam from Kafka to the database (exactly what A11 is about).

```
6 partitions, 3 workers   → each worker owns 2 partitions, room to scale to 6
6 partitions, 10 workers  → only 6 do work; 4 idle  (partitions are the ceiling)
```

---

## A11. DB Bottlenecks

Adding workers to fix Kafka lag can **overload the DB**.

**Hot paths:** insert notification, insert attempts, update status, retry queries, user history reads.

| Mitigation | Detail |
| --- | --- |
| Batch writes | `UPDATE ... WHERE notification_id IN (...)` |
| Minimal status transitions | `PENDING → SENT` (skip `PROCESSING` if unnecessary) |
| Indexes (only needed ones) | `uniq_notification_key`, `idx_notifications_retry`, `idx_notifications_user_created` |
| Time partitioning | `notifications_2026_07`, archive > 30 days |
| Kafka retry topics | Avoid heavy `SELECT ... WHERE status='RETRYING'` polling |
| Worker concurrency cap | Match DB connection pool + write capacity |
| Cache | Templates, preferences, device tokens in Redis |
| Read replicas | User history off replica |
| Shard by `user_id` | Last resort |

> **Key trade-off:** Kafka absorbs spikes; workers throttle delivery rate; DB must not become the hidden bottleneck.

### Don't just fix the queue and forget the DB

You added 100 workers to clear the Kafka backlog — but they all write to **one database**. Now the DB is the bottleneck. Scaling workers without scaling/protecting the DB just moves the bottleneck downstream.

The two cheapest, biggest wins:

- **Batch writes** — instead of 5,000 tiny `UPDATE`s, do one `UPDATE ... WHERE id IN (...)`. One round-trip with many rows, not 5,000 single-row round-trips.
- **Fewer status transitions** — if you don't need `PROCESSING`, go straight `PENDING → SENT`. Every extra state = an extra write.

```java
// ❌ 5,000 round-trips
for (var id : sentIds) db.update("UPDATE notifications SET status='SENT' WHERE id=?", id);

// ✅ one round-trip
db.update("UPDATE notifications SET status='SENT' WHERE id IN (?)", sentIds);
```

#### Q: Why avoid `SELECT ... WHERE status='RETRYING'` polling?

Because that query scans a busy table over and over, competing with the live insert/update traffic. Instead, put retries on a **Kafka delayed/retry topic** — the message *comes back* to the worker when it's due, so the DB never gets polled for "what's ready to retry?".

#### Q: Where do indexes help vs hurt here?

Indexes make **reads** fast (find a user's history, find due retries) but **slow every write** (each insert must update the index too). So add **only the indexes a real query needs** (`uniq_notification_key`, retry index, user-history index) and resist "index everything". On a write-heavy notification table, extra indexes are pure tax.

---

# PART B — Low-Level Design (LLD)

---

## B1. Database Schema (DDL)

```sql
-- ---------- Core notification domain ----------
CREATE TABLE notifications (
    notification_id   BIGINT PRIMARY KEY,          -- Snowflake / UUIDv7 (app-generated, shard-friendly)
    notification_key  VARCHAR(255) NOT NULL,     -- idempotency: userId:type:entityId
    user_id           BIGINT NOT NULL,
    type              VARCHAR(100) NOT NULL,       -- ORDER_CONFIRMED, OTP, PROMOTION
    template_type     VARCHAR(100),
    template_version  INT,                         -- snapshot at send time
    title             TEXT,                        -- rendered snapshot
    body              TEXT,
    status            VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    priority          VARCHAR(20) NOT NULL DEFAULT 'NORMAL',  -- HIGH, NORMAL, LOW
    metadata          JSONB,                     -- deep links, entity refs
    next_retry_at     TIMESTAMP,
    scheduled_at      TIMESTAMP,
    created_at        TIMESTAMP NOT NULL DEFAULT now(),
    sent_at           TIMESTAMP,
    UNIQUE (notification_key)
);
CREATE INDEX idx_notif_user_created ON notifications (user_id, created_at DESC);
CREATE INDEX idx_notif_status       ON notifications (status) WHERE status IN ('PENDING', 'RETRYING');
CREATE INDEX idx_notif_retry        ON notifications (status, next_retry_at)
    WHERE status IN ('RETRYING', 'FAILED');

CREATE TABLE notification_attempts (
    attempt_id        BIGINT PRIMARY KEY,
    notification_id   BIGINT NOT NULL REFERENCES notifications(notification_id),
    channel           VARCHAR(50) NOT NULL,      -- PUSH, EMAIL, SMS, IN_APP
    status            VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    provider          VARCHAR(50),               -- FCM, APNS, SES, TWILIO
    provider_ref      VARCHAR(255),              -- external message id
    provider_response TEXT,
    attempt_count     INT NOT NULL DEFAULT 0,
    next_retry_at     TIMESTAMP,
    created_at        TIMESTAMP NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (notification_id, channel)            -- one attempt row per channel per notification
);
CREATE INDEX idx_attempts_retry ON notification_attempts (next_retry_at)
    WHERE status IN ('FAILED', 'RETRYING');

-- ---------- Preferences & templates ----------
CREATE TABLE notification_preferences (
    user_id           BIGINT NOT NULL,
    notification_type VARCHAR(100) NOT NULL,
    push_enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    email_enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    sms_enabled       BOOLEAN NOT NULL DEFAULT FALSE,
    in_app_enabled    BOOLEAN NOT NULL DEFAULT TRUE,
    quiet_hours_start TIME,                      -- e.g. 22:00
    quiet_hours_end   TIME,                      -- e.g. 08:00
    PRIMARY KEY (user_id, notification_type)
);

CREATE TABLE notification_templates (
    template_id       BIGINT PRIMARY KEY,        -- or UUID (opaque; never encode meaning in the key)
    type              VARCHAR(100) NOT NULL,      -- the "why"; stable across versions
    channel           VARCHAR(50) NOT NULL,
    language          VARCHAR(20) NOT NULL DEFAULT 'en',
    version           INT NOT NULL DEFAULT 1,
    title_template    TEXT,
    body_template     TEXT NOT NULL,
    is_active         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (type, channel, language, version)
);
-- New copy → INSERT version 2; never UPDATE version 1 in place
-- notifications.template_version (or FK notifications.template_id) records what was rendered

-- Exactly ONE active template per (type, channel, language) — no ambiguity at render time
CREATE UNIQUE INDEX uniq_active_template
    ON notification_templates (type, channel, language)
    WHERE is_active = TRUE;

-- ---------- Push devices ----------
CREATE TABLE user_devices (
    device_id         VARCHAR(100) PRIMARY KEY,
    user_id           BIGINT NOT NULL,
    platform          VARCHAR(20) NOT NULL,      -- IOS, ANDROID, WEB
    device_token      TEXT NOT NULL,
    is_active         BOOLEAN NOT NULL DEFAULT TRUE,
    last_seen_at      TIMESTAMP,
    created_at        TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_devices_user_active ON user_devices (user_id) WHERE is_active = TRUE;

-- ---------- In-app feed ----------
CREATE TABLE in_app_notifications (
    id                BIGINT PRIMARY KEY,
    notification_id   BIGINT REFERENCES notifications(notification_id),
    user_id           BIGINT NOT NULL,
    title             TEXT NOT NULL,
    message           TEXT NOT NULL,
    is_read           BOOLEAN NOT NULL DEFAULT FALSE,
    metadata          JSONB,
    created_at        TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_inapp_user_unread ON in_app_notifications (user_id, created_at DESC)
    WHERE is_read = FALSE;

-- ---------- Scheduled notifications ----------
CREATE TABLE scheduled_notifications (
    id                BIGINT PRIMARY KEY,
    notification_id   BIGINT REFERENCES notifications(notification_id),
    user_id           BIGINT NOT NULL,
    type              VARCHAR(100) NOT NULL,
    payload           JSONB NOT NULL,
    scheduled_at      TIMESTAMP NOT NULL,
    status            VARCHAR(50) NOT NULL DEFAULT 'PENDING',  -- PENDING, DISPATCHED, CANCELLED
    created_at        TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_scheduled_due ON scheduled_notifications (scheduled_at)
    WHERE status = 'PENDING';

-- ---------- Campaigns (bulk fan-out) ----------
CREATE TABLE campaigns (
    campaign_id       BIGINT PRIMARY KEY,
    name              VARCHAR(255) NOT NULL,
    type              VARCHAR(100) NOT NULL,
    channels          VARCHAR(50)[] NOT NULL,
    segment_query     TEXT,                      -- or segment_id FK
    status            VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    total_users       BIGINT DEFAULT 0,
    sent_count        BIGINT DEFAULT 0,
    failed_count      BIGINT DEFAULT 0,
    scheduled_at      TIMESTAMP,
    created_at        TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE campaign_batches (
    batch_id          BIGINT PRIMARY KEY,
    campaign_id       BIGINT NOT NULL REFERENCES campaigns(campaign_id),
    user_ids          BIGINT[] NOT NULL,         -- or separate campaign_batch_users table
    status            VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    created_at        TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_campaign_batch_pending ON campaign_batches (campaign_id)
    WHERE status = 'PENDING';
```

> **Alternative to `user_ids[]`:** a `campaign_batch_users (batch_id, user_id)` join table — cleaner at scale. Array shown for brevity.

### The tables, and why there are two "main" ones

`notifications` is the **logical message** ("send this to Hardik"). `notification_attempts` are the **per-channel delivery records** (one for push, one for email, and so on). One message, potentially several attempt rows — because the same message goes out over push *and* email *and* in-app, and each can succeed or fail on its own.

```
notifications (1)  ───►  notification_attempts (many)
  "order confirmed"        PUSH  → SENT
   for user 123            EMAIL → RETRYING
                           IN_APP→ SENT
```

| Table | It's the… | Beginner takeaway |
| --- | --- | --- |
| `notifications` | the message + its overall status | one row per logical message |
| `notification_attempts` | per-channel delivery record | one row per channel; where retries live |
| `notification_preferences` | the user's opt-in switches | read on every send |
| `notification_templates` | the message text with `{{blanks}}` | versioned; never edited in place |
| `user_devices` | phones to push to | one user can have many; multi-device fan-out |
| `in_app_notifications` | the bell-icon feed | what the app screen shows |
| `scheduled_notifications` | "send later" queue | a cron job drains it |
| `campaigns` / `campaign_batches` | bulk blast bookkeeping | audience fan-out in chunks |

#### Q: Why split `notifications` and `notification_attempts` at all — why not one table?

Because **one message → many channels**, and each channel has its **own** status, provider, retry count, and error. If you crammed it into one row you couldn't say "push SENT but email still RETRYING". Separate attempt rows make each channel **independent** (the whole point of A6's graceful degradation) and give retries a natural home.

#### Q: What is `notification_key` and why is it `UNIQUE`?

It's the **idempotency key** — a deterministic string like `123:ORDER_CONFIRMED:order_789`. The `UNIQUE` constraint is the database physically refusing to store the same logical message twice. If the Order service accidentally fires the event twice, the second `INSERT` fails, and we return the existing row instead of sending a duplicate.

```sql
-- second identical request can't create a duplicate — the DB blocks it
INSERT INTO notifications (notification_key, ...) VALUES ('123:ORDER_CONFIRMED:order_789', ...)
ON CONFLICT (notification_key) DO NOTHING;   -- 0 rows inserted → "already exists, reuse it"
```

#### Q: Why version templates instead of editing them?

So an **audit years later still shows exactly what the user saw**. If marketing edits the "promo" wording, we `INSERT version 2` and flip `is_active`; version 1 stays frozen. Each notification also stores the **rendered snapshot** (`title`/`body`) plus `template_version`, so even a later template change can't rewrite history. The `uniq_active_template` partial index guarantees exactly **one** active template per `(type, channel, language)` — no "which one do I use?" ambiguity at render time.

---

## B2. API Contracts

### Send notification (internal)

```
POST /v1/notifications
Idempotency-Key: 123:ORDER_CONFIRMED:order_789

{
  "userId": 123,
  "type": "ORDER_CONFIRMED",
  "channels": ["PUSH", "EMAIL", "IN_APP"],
  "priority": "NORMAL",
  "data": { "orderId": 789, "amount": 1500, "name": "Hardik" },
  "scheduledAt": null
}

202 Accepted
{
  "notificationId": 9001,
  "status": "PENDING",
  "channels": ["PUSH", "EMAIL", "IN_APP"]
}

409 Conflict  (duplicate idempotency key — return existing)
{
  "notificationId": 9001,
  "status": "SENT",
  "message": "Duplicate request"
}
```

### Register device (push)

```
POST /v1/users/{userId}/devices
{
  "deviceId": "550e8400-e29b-41d4-a716-446655440000",
  "platform": "IOS",
  "deviceToken": "apns_token_xyz"
}

201 Created { "deviceId": "550e8400-...", "isActive": true }
```

### Get in-app notifications

```
GET /v1/users/{userId}/notifications?cursor=9000&limit=20

200 OK
{
  "items": [
    {
      "id": 9001,
      "title": "Order confirmed",
      "message": "Your order #789 for ₹1500 is confirmed.",
      "isRead": false,
      "metadata": { "orderId": 789 },
      "createdAt": "2026-07-07T10:00:00Z"
    }
  ],
  "nextCursor": 8980
}
```

### Mark as read

```
PATCH /v1/notifications/{id}/read
204 No Content
```

### Update preferences

```
PUT /v1/users/{userId}/notification-preferences
{
  "preferences": [
    {
      "type": "ORDER_CONFIRMED",
      "pushEnabled": true,
      "emailEnabled": true,
      "smsEnabled": false,
      "inAppEnabled": true
    },
    {
      "type": "PROMOTION",
      "pushEnabled": false,
      "emailEnabled": true,
      "smsEnabled": false,
      "inAppEnabled": true
    }
  ]
}

200 OK
```

### Create campaign (bulk)

```
POST /v1/campaigns
{
  "name": "Flash Sale BLR",
  "type": "PROMOTION",
  "channels": ["PUSH"],
  "segmentId": "blr_electronics_buyers",
  "scheduledAt": "2026-07-07T18:00:00Z"
}

202 Accepted { "campaignId": 42, "status": "SCHEDULED" }
```

### Kafka message (worker payload — prefer minimal)

```json
{
  "notificationId": 9001,
  "channel": "PUSH",
  "attemptCount": 0
}
```

Worker loads title/body from `notifications` table (or attempt row). Avoid duplicating full message in Kafka.

### Reading the API

`POST /v1/notifications` says *"tell user 123 their order is confirmed, over push/email/in-app."* The server responds **`202 Accepted`** ("got it, we'll handle it") and returns a `notificationId` — it does **not** wait for the SMS to land.

```java
// What the caller (e.g. Order service) sends — note it says WHAT, not HOW to deliver low-level
POST /v1/notifications
Idempotency-Key: 123:ORDER_CONFIRMED:order_789   // same key twice = same notification
{
  "userId": 123,
  "type": "ORDER_CONFIRMED",       // looked up to find the template
  "channels": ["PUSH","EMAIL","IN_APP"],
  "data": { "orderId": 789, "amount": 1500, "name": "Hardik" }  // fills the {{blanks}}
}
// → 202 Accepted { notificationId, status: "PENDING" }
```

#### Q: Why `202 Accepted` and not `200 OK`?

`200 OK` implies "done." Here we are **not** done — we've only *accepted* the work; delivery happens later in the background. `202 Accepted` is the honest status code for "queued, will process async." That's the API keeping its promise never to block the caller.

#### Q: What's the `Idempotency-Key` header for, and why `409 Conflict`?

It's the caller's guarantee against duplicates. If the network hiccups and the Order service retries the exact same request, it sends the **same key**. The server sees the key already exists and returns the **existing** notification (a `409 Conflict` carrying the original `notificationId`) instead of sending a second message. Retrying is now **safe**.

```
1st POST (key=123:ORDER_CONFIRMED:order_789) → 202, creates notification 9001
2nd POST (same key, a retry)                 → 409, returns existing 9001 (no new send)
```

#### Q: Why does the Kafka message carry only `{notificationId, channel}` and not the full title/body?

To keep the queue **small and the truth single**. The worker looks up the full text from the DB when it's ready to deliver. If you copied the whole rendered message into Kafka, you'd bloat the queue and risk it going **stale** (e.g. if the row was updated). A thin pointer is cheaper and always fresh.

---

## B3. Notification Service — Class Design (detailed)

### B3.0 Layered overview

```
            HTTP / Kafka
                 │
┌────────────────▼────────────────┐
│   NotificationController         │  HTTP I/O, event consumer adapter
│   NotificationEventConsumer      │
└────────────────┬────────────────┘
                 ▼
┌────────────────────────────────────────────────────────┐
│              NotificationOrchestrator                   │
│  send · sendScheduled · processDomainEvent              │
└──┬──────────┬──────────┬──────────┬──────────┬───────┘
   │          │          │          │          │
   ▼          ▼          ▼          ▼          ▼
┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐ ┌─────────────┐
│PrefPort│ │TemplPort│ │RateLim │ │Notif   │ │ QueuePort   │
│        │ │        │ │Port    │ │Repo    │ │ (Kafka)     │
└────────┘ └────────┘ └────────┘ └────────┘ └─────────────┘

┌────────────────────────────────────────────────────────┐
│              ChannelWorker (per channel)                │
│  PushWorker · EmailWorker · SmsWorker · InAppWorker     │
└──┬──────────┬──────────┬──────────┬───────────────────┘
   ▼          ▼          ▼          ▼
┌────────┐ ┌────────┐ ┌────────┐ ┌─────────────┐
│PushPort│ │EmailPort│ │SmsPort │ │ InAppRepo   │
│FCM/APNS│ │SES/SG  │ │Twilio  │ │ + WsPort    │
└────────┘ └────────┘ └────────┘ └─────────────┘
```

> **Design rule:** the **Orchestrator** owns the send pipeline (validate → dedup → prefs → template → persist → enqueue). **Workers** own delivery (provider call → attempt update → retry/DLQ). External dependencies sit behind **ports**.

### B3.1 Domain models, enums, DTOs

```java
// ---------- Enums ----------
enum Channel            { PUSH, EMAIL, SMS, IN_APP, WHATSAPP }
enum NotificationStatus { PENDING, PROCESSING, SENT, PARTIALLY_SENT, FAILED, CANCELLED, RATE_LIMITED }
enum AttemptStatus      { PENDING, SENT, FAILED, RETRYING, SKIPPED }
enum Priority           { HIGH, NORMAL, LOW }
enum CampaignStatus     { DRAFT, SCHEDULED, RUNNING, COMPLETED, FAILED, CANCELLED }

// ---------- Entities ----------
class Notification {
    long               notificationId;
    String             notificationKey;   // userId:type:entityId
    long               userId;
    String             type;
    String             title;
    String             body;
    NotificationStatus status;
    Priority           priority;
    Map<String,Object> metadata;
    Instant            scheduledAt;
    Instant            createdAt;
    Instant            sentAt;
}

class NotificationAttempt {
    long           attemptId;
    long           notificationId;
    Channel        channel;
    AttemptStatus  status;
    String         provider;
    String         providerRef;
    String         providerResponse;
    int            attemptCount;
    Instant        nextRetryAt;
}

class UserDevice {
    String  deviceId;
    long    userId;
    String  platform;       // IOS, ANDROID, WEB
    String  deviceToken;
    boolean isActive;
}

class NotificationPreference {
    long    userId;
    String  type;
    boolean pushEnabled;
    boolean emailEnabled;
    boolean smsEnabled;
    boolean inAppEnabled;
    LocalTime quietHoursStart;
    LocalTime quietHoursEnd;
}

class NotificationTemplate {
    long   templateId;
    String type;
    Channel channel;
    String language;
    String titleTemplate;
    String bodyTemplate;
}

// ---------- DTOs ----------
class SendNotificationRequest {
    long userId;
    String type;
    List<Channel> channels;
    Priority priority;
    Map<String,Object> data;
    Instant scheduledAt;
    String idempotencyKey;
}

class SendNotificationResponse {
    long notificationId;
    NotificationStatus status;
    List<Channel> channels;
}

class DeliveryJob {
    long notificationId;
    long userId;
    Channel channel;
    Priority priority;
    String title;
    String body;
    Map<String,Object> metadata;
    int attemptCount;
}
```

### B3.2 Controller & Event Consumer

```java
@RestController
class NotificationController {
    private final NotificationOrchestrator orchestrator;

    @PostMapping("/v1/notifications")
    ResponseEntity<SendNotificationResponse> send(
            @RequestHeader("Idempotency-Key") String idemKey,
            @RequestBody SendNotificationRequest req) {
        req.idempotencyKey = idemKey;
        SendNotificationResponse resp = orchestrator.send(req);
        return ResponseEntity.accepted().body(resp);
    }
}

@Component
class NotificationEventConsumer {
    private final NotificationOrchestrator orchestrator;

    @KafkaListener(topics = "domain-events")
    void onDomainEvent(DomainEvent event) {
        // Map ORDER_CONFIRMED → SendNotificationRequest
        orchestrator.processDomainEvent(event);
    }
}
```

> **Significance:** HTTP and Kafka are **two adapters** into the same orchestrator. Business rules live in one place.

### B3.3 NotificationOrchestrator

```java
@Service
class NotificationOrchestrator {
    private final NotificationRepository notifRepo;
    private final PreferencePort         preferences;
    private final TemplatePort           templates;
    private final RateLimitPort          rateLimiter;
    private final QueuePort              queue;
    private final TransactionTemplate    tx;

    SendNotificationResponse send(SendNotificationRequest req) {
        // 1. Stage 1 — create once (idempotency)
        Optional<Notification> existing = notifRepo.findByKey(req.idempotencyKey);
        if (existing.isPresent()) return handleDuplicate(existing.get());

        // 2. Resolve channels
        NotificationPreference pref = preferences.get(req.userId, req.type);
        List<Channel> channels = intersect(req.channels, pref);
        if (channels.isEmpty()) return skippedResponse(req);

        // 3. Quiet hours + rate limit (promotional only)
        if (isPromotional(req.type)) {
            if (pref.inQuietHours(now())) return rateLimitedResponse(req);
            if (!rateLimiter.tryAcquire(req.userId, req.type)) return rateLimitedResponse(req);
        }

        // 4. Render templates; snapshot template_version + rendered title/body
        Map<Channel, RenderedMessage> rendered = templates.renderAll(req.type, channels, req.data);

        // 5. Persist + enqueue (or schedule)
        if (req.scheduledAt != null && req.scheduledAt.isAfter(now()))
            return schedule(req, channels, rendered);

        Notification n = tx.execute(status -> {
            Notification saved = notifRepo.insert(buildNotification(req, rendered));
            for (Channel ch : channels)
                notifRepo.insertAttempt(saved.notificationId, ch, PENDING);
            return saved;
        });

        for (Channel ch : channels)
            queue.publish(topicFor(ch, req.priority), toJob(n, ch));  // small payload: notificationId + channel

        return toResponse(n, channels);
    }

    SendNotificationResponse handleDuplicate(Notification n) {
        // Never re-insert; retry worker handles RETRYING via same notification_id
        return toResponse(n);
    }
}
```

> **Significance:** duplicate event → `handleDuplicate`. **Stage 2 retries** happen in `ChannelWorker` — update attempt, republish `notificationId` to Kafka retry topic; no new notification row.

### B3.4 ChannelWorker (base + push example)

```java
abstract class ChannelWorker {
    protected final NotificationRepository notifRepo;
    protected final QueuePort              queue;

    void consume(DeliveryJob job) {
        NotificationAttempt attempt = notifRepo.findAttempt(job.notificationId, job.channel);
        if (attempt.status == SENT) return;                    // idempotent skip

        try {
            DeliveryResult result = deliver(job);               // subclass
            notifRepo.updateAttempt(attempt.attemptId, SENT, result);
            notifRepo.updateNotificationStatusIfAllSent(job.notificationId);
        } catch (RetryableException e) {
            handleRetry(job, attempt, e);
        } catch (PermanentFailureException e) {
            notifRepo.updateAttempt(attempt.attemptId, FAILED, e);
            queue.publishToDlq(job.channel, job);
        }
    }

    protected abstract DeliveryResult deliver(DeliveryJob job) throws RetryableException;

    void handleRetry(DeliveryJob job, NotificationAttempt attempt, RetryableException e) {
        int next = attempt.attemptCount + 1;
        if (next >= MAX_RETRIES) {
            queue.publishToDlq(job.channel, job);
            notifRepo.updateAttempt(attempt.attemptId, FAILED, e);
            return;
        }
        Duration delay = backoff(next);                         // 1m, 5m, 30m
        notifRepo.updateAttemptRetry(attempt.attemptId, RETRYING, next, now().plus(delay));
        queue.publishDelayed(topicFor(job.channel, job.priority), job, delay);
    }
}

class PushWorker extends ChannelWorker {
    private final DeviceRepository deviceRepo;
    private final PushPort           push;                      // FCM + APNS

    DeliveryResult deliver(DeliveryJob job) {
        List<UserDevice> devices = deviceRepo.findActiveByUser(job.userId);
        if (devices.isEmpty()) throw new PermanentFailureException("NO_ACTIVE_DEVICES");

        int success = 0;
        for (UserDevice d : devices) {
            try {
                push.send(d.platform, d.deviceToken, job.title, job.body, job.metadata);
                success++;
            } catch (InvalidTokenException e) {
                deviceRepo.deactivate(d.deviceId);              // mark token bad
            }
        }
        if (success == 0) throw new RetryableException("ALL_TOKENS_FAILED");
        return DeliveryResult.ok();
    }
}
```

### B3.5 Interfaces (ports)

```java
interface PreferencePort {
    NotificationPreference get(long userId, String type);
}

interface TemplatePort {
    Map<Channel, RenderedMessage> renderAll(String type, List<Channel> channels, Map<String,Object> data);
}

interface RateLimitPort {
    boolean tryAcquire(long userId, String type);   // Redis INCR + TTL
}

interface QueuePort {
    void publish(String topic, DeliveryJob job);
    void publishDelayed(String topic, DeliveryJob job, Duration delay);
    void publishToDlq(Channel channel, DeliveryJob job);
}

interface NotificationRepository {
    Optional<Notification> findByKey(String notificationKey);
    Notification insert(Notification n);
    void insertAttempt(long notificationId, Channel ch, AttemptStatus status);
    NotificationAttempt findAttempt(long notificationId, Channel ch);
    void updateAttempt(long attemptId, AttemptStatus status, DeliveryResult result);
    void updateAttemptRetry(long attemptId, AttemptStatus status, int count, Instant nextRetryAt);
    void updateNotificationStatusIfAllSent(long notificationId);
}

interface PushPort {
    void send(String platform, String token, String title, String body, Map<String,Object> meta);
}

interface EmailPort {
    void send(String to, String subject, String htmlBody);
}

interface SmsPort {
    void send(String phone, String body);           // primary; fallback inside adapter
}

interface WebSocketPort {
    void notifyUser(long userId, InAppNotification payload);
}
```

| Interface | Significance |
| --- | --- |
| **`PreferencePort`** | Encapsulates opt-in rules; cache-backed for hot path |
| **`TemplatePort`** | Keeps message text out of code; supports i18n |
| **`RateLimitPort`** | Anti-spam; Redis counters with TTL |
| **`QueuePort`** | Abstracts Kafka; enables delayed retry topics |
| **`NotificationRepository`** | Idempotency via `UNIQUE(notification_key)`; attempt tracking per channel |
| **`PushPort` / `EmailPort` / `SmsPort`** | Swappable providers; fallback logic hidden in adapter |
| **`WebSocketPort`** | Decouples in-app DB write from real-time push |

### B3.6 Adapters

```java
class KafkaQueueAdapter       implements QueuePort       { /* produce to topic; delayed via retry topic */ }
class RedisRateLimitAdapter     implements RateLimitPort   { /* INCR notif_count:user:type:date */ }
class RedisCachedPreferenceAdapter implements PreferencePort { /* cache-aside */ }
class JdbcNotificationRepository implements NotificationRepository { /* INSERT ... ON CONFLICT */ }
class FcmApnsPushAdapter        implements PushPort        { /* platform routing */ }
class SesEmailAdapter           implements EmailPort       { /* + SendGrid fallback */ }
class TwilioSmsAdapter          implements SmsPort           { /* + SNS fallback */ }
class RedisWebSocketAdapter     implements WebSocketPort   { /* pub to ws:user:{id} */ }
```

> **Background workers:**
> - `PushWorker`, `EmailWorker`, `SmsWorker`, `InAppWorker` — Kafka consumer groups per channel.
> - `SchedulerJob` — polls `scheduled_notifications`, calls orchestrator.
> - `CampaignBatchProducer` — streams segment users → `campaign_batches` → Kafka.
> - `RetryDispatcher` — polls `notification_attempts WHERE next_retry_at <= now()`.
> - `ReconciliationSweeper` — re-enqueues `notifications WHERE status='PENDING' AND created_at < now()-N` (fixes dual-write gaps; idempotent).

### Ports & adapters

A **port** is an interface (`SmsPort`) and an **adapter** is a concrete implementation behind it (`TwilioSmsAdapter`). The rest of the code depends only on the port, not the implementation. The orchestrator calls `SmsPort` and never learns it's Twilio — so swapping Twilio for AWS SNS is a one-line wiring change, with no business logic touched.

```java
// PORT — the socket shape the rest of the code depends on
interface SmsPort { void send(String phone, String body); }

// ADAPTER — a specific provider behind the socket (swappable)
class TwilioSmsAdapter implements SmsPort {
    public void send(String phone, String body) {
        try { twilio.messages().create(phone, body); }
        catch (TwilioDown e) { sns.publish(phone, body); }  // fallback hidden HERE, not in business logic
    }
}

// The worker only knows the SOCKET — never says the word "Twilio"
class SmsWorker {
    private final SmsPort sms;               // could be Twilio, SNS, a fake for tests…
    void deliver(DeliveryJob job) { sms.send(job.phone, job.body); }
}
```

#### Q: What's the difference between the Orchestrator and a Worker again?

- **Orchestrator = the decision maker (the "send pipeline").** Runs once per request: validate → dedup → check prefs → render template → save → enqueue. It decides *whether and what* to send. It never touches a provider.
- **Worker = the deliverer.** Runs once per queued job: load the message → call the provider → record `SENT`/`RETRYING`/`FAILED`. It decides *nothing*, it just delivers what it's handed.

Keeping them apart means the fast path (accept a request) and the slow path (talk to flaky providers) scale and fail independently.

#### Q: Why bother with all these interfaces (ports) — isn't it over-engineering?

Three concrete payoffs:

1. **Swap providers** without touching logic (Twilio → SNS, SES → SendGrid).
2. **Test easily** — plug a `FakeSmsPort` that records calls, no real SMS sent.
3. **Hide messy details** — provider fallback, retry, platform routing all live *inside* the adapter, so the orchestrator/worker stays clean.

#### Q: Why so many background workers (SchedulerJob, RetryDispatcher, ReconciliationSweeper)?

Each one owns a **single "eventually" job** that shouldn't sit on the hot request path:

- **SchedulerJob** — "send this later" (reminders) → polls due rows every minute.
- **RetryDispatcher** — nudges failed attempts when their backoff timer is up.
- **ReconciliationSweeper** — the safety net for the dual-write gap: re-enqueues stuck `PENDING` rows. Idempotent, so re-enqueuing something already sent is harmless (the worker skips it).

---

## B4. State Machines

### Notification (aggregate)

```
                    all channels SENT
PENDING ──► PROCESSING ──────────────────────► SENT
   │              │
   │              ├── some SENT, some FAILED ──► PARTIALLY_SENT
   │              │
   │              └── all channels FAILED ─────► FAILED
   │
   ├── rate limited / prefs off ───────────────► RATE_LIMITED / (no record)
   │
   └── cancelled (scheduled) ──────────────────► CANCELLED
```

### NotificationAttempt (per channel)

```
              deliver OK
PENDING ─────────────────────────► SENT
   │
   ├── transient error ──► RETRYING ──► SENT
   │                         │
   │                         └── max retries ──► FAILED ──► DLQ
   │
   ├── permanent error (invalid email) ───────► FAILED
   │
   └── skipped (pref/rate limit) ─────────────► SKIPPED
```

### Scheduled notification

```
PENDING ──(scheduled_at reached)──► DISPATCHED
   │
   └── user cancelled / entity deleted ───────► CANCELLED
```

### Campaign

```
DRAFT ──► SCHEDULED ──► RUNNING ──► COMPLETED
              │            │
              └── cancel ──┴── error ──► FAILED / CANCELLED
```

### State machines

A **state machine** is just the fixed set of statuses a thing can be in, plus the **only** legal moves between them. For example a notification goes `PENDING → PROCESSING → SENT`, never backwards; the arrows enforce which transitions are valid.

Two levels here, mirroring the two tables (B1):

```java
enum NotificationStatus { PENDING, PROCESSING, SENT, PARTIALLY_SENT, FAILED, CANCELLED, RATE_LIMITED }
enum AttemptStatus      { PENDING, SENT, FAILED, RETRYING, SKIPPED }

// The overall notification's status is DERIVED from its per-channel attempts:
NotificationStatus rollUp(List<Attempt> attempts) {
    if (attempts.stream().allMatch(a -> a.status == SENT))   return SENT;
    if (attempts.stream().anyMatch(a -> a.status == SENT))   return PARTIALLY_SENT; // some ok, some not
    return FAILED;                                                                  // none got through
}
```

#### Q: What is `PARTIALLY_SENT` and why does it exist?

Because a single notification fans out to several channels (push + email + in-app), they can end **differently**: push delivered, email bounced. The message as a whole isn't fully `SENT` nor fully `FAILED` — it's **`PARTIALLY_SENT`**. This lets the email attempt keep retrying on its own **without** re-sending the push that already worked.

#### Q: Attempt vs Notification status — which one drives retries?

The **attempt** status. Retries, backoff, and DLQ all operate at the per-channel **attempt** level. The **notification** status is just a *summary* rolled up from its attempts (all SENT → `SENT`; mixed → `PARTIALLY_SENT`). So a failed email flips only that attempt to `RETRYING`; the notification simply reflects the mix.

---

## B5. Core Algorithms

### Send notification (orchestrator)

```
send(request):
    if exists(notification_key): return existing

    channels = intersect(request.channels, preferences(user, type))
    if channels empty: return SKIPPED

    if promotional(type):
        if quiet_hours(preferences): return RATE_LIMITED
        if not rate_limiter.acquire(user, type): return RATE_LIMITED

    rendered = templates.render(type, channels, data)

    if scheduled_at > now:
        insert scheduled_notifications; return SCHEDULED

    BEGIN TX
        insert notifications (status=PENDING)
        for ch in channels: insert notification_attempts (status=PENDING)
    COMMIT

    for ch in channels:
        kafka.publish(topic(ch, priority), DeliveryJob)

    return 202 PENDING
```

### Push delivery (multi-device fan-out)

```
deliverPush(job):
    devices = SELECT * FROM user_devices WHERE user_id=? AND is_active=TRUE
    if devices empty: FAIL permanent

    success = 0
    for d in devices:
        try:
            if d.platform == IOS:   apns.send(d.token, job)
            if d.platform == ANDROID: fcm.send(d.token, job)
            success++
        catch InvalidToken:
            UPDATE user_devices SET is_active=FALSE WHERE device_id=d.id

    if success == 0: RETRY
    else: SENT
```

> Retry (exponential backoff + jitter → DLQ), idempotency (API `ON CONFLICT DO NOTHING` + worker `attempt.status == SENT` skip), rate limiting (Redis `INCR` + daily TTL), and campaign fan-out (stream segment → batch → enqueue) are shown as annotated Java in the **one confusion at a time** deep dive below.

### Scheduled dispatch (cron)

```
every 1 minute:
    rows = SELECT * FROM scheduled_notifications
           WHERE status='PENDING' AND scheduled_at <= now()
           LIMIT 1000

    for row in rows:
        orchestrator.send(deserialize(row.payload))
        UPDATE scheduled_notifications SET status='DISPATCHED' WHERE id=row.id
```

### The algorithms, one confusion at a time

#### Dedup vs idempotency — the "don't count it twice" pair

Two *different* "sent twice" problems people constantly mix up:

| Problem | Where it happens | Fix |
| --- | --- | --- |
| **Same request arrives twice** (source retried, event fired twice) | at the **API** | `UNIQUE(notification_key)` → reuse existing row |
| **Same Kafka message delivered twice** (Kafka is at-least-once) | at the **worker** | check `attempt.status == SENT` → skip |

```java
// API level — the DB itself refuses the duplicate
INSERT INTO notifications (notification_key, ...) VALUES ('123:ORDER_CONFIRMED:order_789', ...)
ON CONFLICT (notification_key) DO NOTHING;   // 0 rows → "already exists" → return the old id

// Worker level — before calling the provider, make sure we didn't already deliver
var attempt = db.findAttempt(job.notificationId, job.channel);
if (attempt.status == SENT) return;          // duplicate Kafka message → skip, don't re-send
```

Both guards are needed because duplicates can appear at either door: the API guard blocks a duplicate *request*, and the worker guard blocks a duplicate *delivery*.

#### Retries with backoff + jitter — "don't all stampede back at once"

When a provider blips (HTTP 503), we don't give up and we don't hammer it. We wait, and **each retry waits longer** (exponential backoff): 1 min, 5 min, 30 min. Crucially we add **jitter** (a little randomness) so that 10,000 failed messages don't *all* retry at the exact same second and knock the provider over the moment it recovers.

```java
Duration backoff(int attempt) {
    long base = switch (attempt) { case 1 -> 60; case 2 -> 300; default -> 1800; }; // seconds
    long jitter = ThreadLocalRandom.current().nextLong(base / 2);  // 0 .. base/2 randomness
    return Duration.ofSeconds(base + jitter);   // spread the herd across a window
}
// after MAX_RETRIES → give up on auto-retry → send to DLQ for a human/alert
```

Without jitter, if every failed message retried at the *same* instant, they'd hit the recovering provider in one synchronized spike. Jitter = each retry waits a *slightly different* amount, spreading the load over a window.

#### Q: What's a DLQ and when does a message land there?

**DLQ = Dead Letter Queue** — the "we gave up auto-retrying, a human should look" bin. A message goes there after it exhausts `MAX_RETRIES` (transient failures that never recovered) or on a **poison** message (malformed, will never succeed). It's parked, not lost, and on-call gets alerted on **DLQ depth**.

#### Rate limiting — "no more than 5 promos a day"

To avoid spamming, promotional sends check a per-user daily counter in Redis. `INCR` is **atomic**, so even if two workers check at the same millisecond, the count is correct.

```java
String key = "notif_count:" + userId + ":" + type + ":" + today();  // e.g. …:PROMOTION:2026-07-08
long count = redis.incr(key);
if (count == 1) redis.expireAt(key, endOfDay());   // first hit sets the reset time
if (count > limit(type)) return false;             // over the cap → skip this promo
// OTP/transactional: no cap (they're essential), but the message itself has a short TTL
```

Redis counts each promotional send; past the daily cap, further promos are skipped — but transactional messages (OTPs) are never capped.

#### Q: Campaign fan-out — how do we notify 10M users without exploding?

Two rules: **stream, don't load**, and **batch**. You pull the audience with a cursor (never `SELECT` 10M rows into memory), chop them into batches of ~5,000, and drop each batch on Kafka. Each user in a batch then becomes a **normal `send()`** — so campaigns automatically inherit the *same* preferences, rate limits, dedup, and workers as one-off notifications.

```java
var cursor = segmentService.streamUsers(segmentId);   // lazy — one page at a time
var batch = new ArrayList<Long>();
for (long userId : cursor) {
    batch.add(userId);
    if (batch.size() >= 5000) { kafka.publish("campaign-batch", batch); batch.clear(); }
}
// consumer: for each userId in the batch → orchestrator.send(...) → reuses everything
```

The key point: you never load all 10M users into memory at once — you stream them in pages and publish batches as you go.

#### Q: The scheduler — how does "send at T-1hr" work without double-sending?

A cron job wakes every minute and grabs rows whose `scheduled_at <= now` and `status = PENDING`, sends them, and flips them to `DISPATCHED`. To stop two scheduler instances grabbing the same row, use `SELECT … FOR UPDATE SKIP LOCKED` (or update-and-check-rows-affected) so each row is claimed by exactly one worker.

---

## B6. Sequence — Happy Path (order confirmed)

```
OrderSvc   Kafka    NotifAPI    Pref/Template   DB       Kafka(push)   PushWorker   FCM    User Phone
   │         │          │            │          │            │            │         │         │
   │ event   │          │            │          │            │            │         │         │
   ├────────►│ consume  │            │          │            │            │         │         │
   │         ├─────────►│ get prefs  │          │            │            │         │         │
   │         │          ├───────────►│          │            │            │         │         │
   │         │          │ render tmpl│          │            │            │         │         │
   │         │          ├───────────────────────►│ INSERT notif + attempts       │         │
   │         │          ├───────────────────────────────────►│ publish job │         │         │
   │         │          │ 202 Accepted         │            ├───────────►│         │         │
   │         │          │            │          │            │  consume   │         │         │
   │         │          │            │          │            ├───────────►│ send    │         │
   │         │          │            │          │            │            ├────────►│ notify  │
   │         │          │            │          │◄── UPDATE attempt=SENT ───────────┤         │
   │         │          │            │          │◄── UPDATE notif=SENT ─────────────┤         │
```

### Following one order confirmation

Read the diagram left-to-right:

1. **Order service** finishes an order and emits an event ("order 789 confirmed").
2. **Notification API** picks it up, asks **Preference/Template** services ("is push on? what's the wording?"), fills in the blanks.
3. It **saves** the notification + attempt rows to the DB, then **drops a job on Kafka** and immediately replies `202` — its work is done in milliseconds.
4. Later (could be 50ms later), the **Push Worker** picks up the job, calls **FCM**, and the banner appears on the phone.
5. The worker writes back **attempt = SENT**, and once all channels are done, **notification = SENT**.

Notice the **handoff at Kafka**: everything left of it is "decide" (fast), everything right of it is "deliver" (async). The API never waits for FCM — that's the async boundary from A4 in action.

#### Q: If the whole thing is async, how does the caller know it worked?

It doesn't wait to find out — it trusts the `202` and moves on. Delivery outcome lands in the **DB** (attempt/notification status) and, for the user, as the actual push/email. If a caller *needs* delivery status, it reads it back later or subscribes to a status event — but it never blocks the original request on it.

---

## B7. Sequence — Retry & DLQ

```
PushWorker    FCM        DB         Kafka(retry)    Kafka(DLQ)
    │          │          │              │              │
    ├─ send ──►│ 503      │              │              │
    │◄─────────┤          │              │              │
    ├─ update attempt: RETRYING, count=1, next_retry=+1m ─►│
    ├─ publish delayed job ───────────────────────────────►│
    │          │          │              │              │
    ... 1 min later ...
    │          │          │              │              │
    ├◄─ consume retry job ────────────────────────────────┤
    ├─ send ──►│ 503      │              │              │
    │  (repeat until attempt 4)           │              │
    ├─ update attempt: FAILED ────────────►│              │
    ├─ publish to DLQ ────────────────────────────────────►│
    │          │          │              │              │
    │          │          │         alert on-call (DLQ depth metric)
```

### What happens when FCM says "503"

Walk it as "try, wait, try again, eventually give up":

1. Push worker calls **FCM** → gets a **503** (FCM temporarily unhappy). This is *transient*, so we don't fail permanently.
2. Worker marks the attempt **`RETRYING`, count=1, next_retry=+1m** and republishes the job to a **delayed retry topic** — the message will come *back* in 1 minute.
3. A minute later the job reappears, worker tries again. Still 503? Wait longer (5m, then 30m — backoff).
4. After the last allowed try, the attempt flips to **`FAILED`** and the job goes to the **DLQ**, which pages on-call.

```
try → 503 → RETRYING (+1m) → try → 503 → RETRYING (+5m) → … → FAILED → DLQ → alert
```

#### Q: Why push the retry to a topic instead of just `Thread.sleep(60_000)` in the worker?

Because sleeping **ties up a worker thread** doing nothing for a minute (or 30!), and if the worker crashes mid-sleep the retry is lost. Parking it on a **delayed topic** frees the worker to process other jobs, and the retry survives restarts (it's durably in Kafka). The timer lives in the queue, not in a blocked thread.

#### Q: Transient vs permanent failure — how does the worker decide whether to retry?

It's about **can trying again ever help?**

- **Transient** (503, timeout, "all tokens temporarily failed") → yes, retry with backoff.
- **Permanent** (invalid email address, hard bounce, no active devices) → no, retrying is pointless → mark `FAILED` immediately, skip straight to DLQ. Retrying a permanently-bad address just wastes provider quota.

---

## B8. Sequence — Bulk Campaign Fan-Out

```
Admin    CampaignSvc   SegmentSvc   DB(batches)   Kafka    NotifAPI   PushWorkers
  │           │             │           │          │          │           │
  ├─ POST ───►│             │           │          │          │           │
  │           ├─ stream ───►│           │          │          │           │
  │           │◄─ userIds ──┤ (cursor)  │          │          │           │
  │           ├─ batch 5k ──────────────►│          │          │           │
  │           ├─ publish batch ─────────────────────►│          │           │
  │           │             │           │          ├─ fan-out ►│           │
  │           │             │           │          │  (5k jobs)├──────────►│
  │           │             │           │          │          │  (parallel)
  │◄─ 202 ────┤             │           │          │          │           │
```

> Each user in the batch becomes a normal `send()` call — reuses the same orchestrator, prefs, rate limits, and workers.

### Blasting millions without melting

You can't send to 10M people in one shot. The **Campaign Service** streams the audience from the **Segment Service** in pages (cursor), packs them into **batches of ~5,000**, and drops each batch on Kafka. Consumers unpack a batch and call the *ordinary* `send()` for each user — so a campaign is really just "a lot of normal notifications, produced carefully."

```
segment (10M) → stream in pages → batch(5k) → Kafka → unpack → send() per user
                (never all in RAM)                              (reuses prefs, rate limits, retries)
```

#### Q: Why batches of 5,000 instead of one message per user, or one giant message?

- **One-per-user at the producer** = 10M tiny publishes = huge overhead and a slow producer.
- **One giant message** = a 10M-user blob that's impossible to retry or track partially.
- **Batches of ~5k** = the sweet spot: few enough to publish fast, small enough to retry/track a chunk, and they naturally throttle the firehose.

#### Q: A user has "max 5 promos/day" — does a campaign bypass that?

**No.** Because each campaign recipient goes through the **same `send()`** pipeline, the per-user rate limit, quiet hours, and opt-outs all still apply. If a user already hit their promo cap, the campaign send for them is simply skipped — the blast can't spam anyone the normal rules would protect.

---

## B9. Sequence — Scheduled Reminder

```
BookSvc    NotifAPI    DB(scheduled)    Scheduler(cron)    Kafka    PushWorker
   │           │              │                │              │           │
   ├─ send ───►│              │                │              │           │
   │  (sched)  ├─ INSERT scheduled_at=T-1hr ──►│              │           │
   │◄─ 202 ────┤              │                │              │           │
   │           │              │                │              │           │
   ... 1 hour later ...
   │           │              │                │              │           │
   │           │              │◄── poll due ───┤              │           │
   │           │◄─ send(now) ─────────────────┤              │           │
   │           ├─ INSERT notif + enqueue ─────────────────────►│           │
   │           │              │◄─ status=DISPATCHED ──────────┤           │
   │           │              │                │              ├──────────►│
```

### "Remind me in an hour"

When the Booking service asks for a "movie starts in 1 hour" reminder, we don't hold a thread awake for an hour. We **write it down** in `scheduled_notifications` with `scheduled_at = T-1hr` and forget about it. A **cron job** ticks every minute asking "anything due now?" When the time arrives, it fires a normal `send()` and marks the row `DISPATCHED`.

```
now:       INSERT scheduled_notifications(scheduled_at = 6:00pm, status=PENDING)
every min: SELECT ... WHERE status='PENDING' AND scheduled_at <= now()  → send() → status=DISPATCHED
```

#### Q: Why store it in a table + poll, instead of an in-memory timer?

Because in-memory timers **die when the process restarts** — a reboot would silently drop every pending reminder. A DB row is durable: it survives crashes, deploys, and scaling, and any scheduler instance can pick it up. The trade-off is a tiny delay (up to the 1-minute poll interval), which is fine for reminders.

#### Q: Two scheduler instances are running — won't they both send the same reminder?

They could, so we prevent it: claim rows with `SELECT … FOR UPDATE SKIP LOCKED` (or `UPDATE … WHERE status='PENDING'` and check rows-affected). Whichever instance claims the row processes it; the other simply skips locked rows. Plus the notification's own idempotency key is a final backstop against a double-send.

---

## B10. Concurrency & Correctness Summary

| Concern | Mechanism |
| --- | --- |
| Duplicate events | `notification_key` + `UNIQUE` constraint on `notifications` |
| Duplicate queue messages | Worker checks `attempt.status == SENT` before deliver |
| At-least-once delivery | Kafka + idempotent workers |
| Per-user ordering | Kafka partition key = `user_id` |
| Multi-channel independence | Separate attempt row per channel; one channel failing doesn't block others |
| Rate limit race | Redis `INCR` is atomic |
| Campaign memory blow-up | Stream segment with cursor; batch size cap |
| Scheduler double-dispatch | `UPDATE ... WHERE status='PENDING'` + check rows affected; or `SELECT FOR UPDATE SKIP LOCKED` |
| Lost events from source | Transactional outbox in Order/Payment service |
| Lost enqueue after DB insert | Outbox in notification DB, or reconciliation sweeper for stale `PENDING` rows |
| Stale device token | Deactivate on provider error; don't retry same token |

### Delivery guarantees

We'd love "exactly once" — the message arrives precisely one time. But over unreliable networks that's very hard and expensive. So we settle for **at-least-once** (we'd rather deliver twice than not at all — imagine losing an OTP) and then make **duplicates harmless** so the user never actually *sees* two. That combo — *at-least-once + idempotency* — is the practical stand-in for exactly-once.

The layered defenses, in plain terms:

- **Same request twice?** The DB's `UNIQUE(notification_key)` blocks the second (API guard).
- **Same Kafka message twice?** The worker checks `attempt == SENT` and skips (worker guard).
- **Two clicks, one counter (rate limit)?** Redis `INCR` is atomic — no lost updates.
- **Messages in order for a user?** Kafka is partitioned by `user_id`, so one user's events stay in sequence.

```
"exactly once" (hard)  ≈  at-least-once delivery  +  idempotent handlers  →  user sees it once
```

#### Q: What does "at-least-once + idempotent" actually guarantee the user sees?

**Exactly one delivered message, effectively.** We might *attempt* twice under the hood (a retry, a duplicate Kafka message), but the two guards above collapse those into a single real send. The user gets their OTP once — never zero times (we retry) and never twice (we dedup).

#### Q: Why partition Kafka by `user_id`?

To preserve **per-user ordering**. All of one user's notifications land on the same partition, and a partition is consumed in order — so "order placed" can't overtake "order shipped" for the same person. Different users are on different partitions, which is what gives us parallelism.

---

## B11. Caching Design

| Key | Value | TTL | Notes |
| --- | --- | --- | --- |
| `pref:{userId}:{type}` | `NotificationPreference` JSON | 5–15 min | invalidate on PUT preferences |
| `tmpl:{type}:{channel}:{lang}` | template strings | 1 hour | invalidate on template update |
| `notif_count:{userId}:{type}:{date}` | integer | end of day | rate limiting |
| `ws:user:{userId}` | `{ connectionId, serverId }` | session | refreshed on heartbeat |
| `devices:{userId}` | list of active tokens | 5 min | optional; DB is source of truth |
| `unread:{userId}` | integer | none (rebuild on miss) | bell-icon count; INCR on create, DECR on read, SET 0 on read-all |

### Cache the hot reads

Instead of hitting the DB for every "is push on for this user?" or "what's the promo template?", keep the answers you read constantly in **Redis**. Much faster — and if a cached entry is lost, rebuild it from the DB on the next miss.

The pattern is **cache-aside**: read Redis first; on a miss, read the DB and write it back to Redis. On a *change*, evict the cached entry (**invalidate**) so nobody reads stale info.

```java
NotificationPreference getPref(long userId, String type) {
    var key = "pref:" + userId + ":" + type;
    var cached = redis.get(key);
    if (cached != null) return cached;              // hit — fast path
    var fromDb = db.loadPref(userId, type);         // miss — go to the DB
    redis.set(key, fromDb, Duration.ofMinutes(10)); // write back, short TTL
    return fromDb;
}
// on PUT /preferences → redis.del("pref:"+userId+":"+type);  // invalidate, so next read is fresh
```

#### Q: What's the `unread:{userId}` counter and why isn't it just `COUNT(*)`?

The bell icon shows an unread badge on **every** app open — running `SELECT COUNT(*) ... WHERE is_read=false` each time would hammer the DB. So we keep a tiny integer in Redis: `INCR` when a notification is created, `DECR` when one is read, `SET 0` on "mark all read". It's an **O(1)** read for a screen that's viewed constantly. The DB stays the source of truth (rebuild the counter on a cache miss).

#### Q: What if the cache and DB disagree (stale preference)?

For preferences/templates a few minutes of staleness is acceptable, and the **short TTL + invalidate-on-write** keeps drift tiny. The DB is always the source of truth; the cache is a fast, disposable copy. We never let a cache be the *only* home for something we can't afford to lose.

---

## B12. Error Handling & Edge Cases

| Case | Handling |
| --- | --- |
| Duplicate API request | `UNIQUE(notification_key)` → `handleDuplicate()`; never re-insert |
| Duplicate Kafka message | Worker skips if attempt already `SENT` |
| Delivery failed after insert | Update same `notification_id` to RETRYING; republish to retry topic — **no re-insert** |
| Crash between DB insert and Kafka publish (dual-write) | Row stuck in `PENDING`; outbox or reconciliation sweeper re-enqueues rows older than N min (idempotent — worker skips if attempt already `SENT`) |
| Provider recovers after outage | Jittered backoff spreads retries; avoids thundering herd |
| User opted out | `intersect()` returns empty → no record or `SKIPPED` |
| All push tokens invalid | Deactivate tokens; attempt → `FAILED` or retry if transient |
| Email bounces (hard) | Permanent failure; no retry; mark attempt `FAILED` |
| SMS provider down | Retry with backoff → fallback provider → DLQ |
| Quiet hours | Skip promotional; transactional (OTP) bypasses quiet hours |
| Partial multi-channel success | Notification → `PARTIALLY_SENT`; failed channels retry independently |
| Campaign too fast | Throttle batch publish rate; rate limit per user still applies |
| Scheduled event cancelled | Update `scheduled_notifications.status = CANCELLED` |
| Template missing | Fail fast; alert; don't send blank message |
| Two active templates for same `(type, channel, language)` | Impossible — `uniq_active_template` partial unique index rejects the second; publishing a version flips old → false, inserts new → true in one tx |
| Template edited after send | Notification stored rendered `title`/`body` snapshot + `template_id`/`version`; audit still exact |
| Queue backlog | Autoscale workers; prioritize HIGH topic |

### The "what could go wrong?" checklist

This table is really the **paranoia list** — every way a message could be lost, doubled, or sent wrong, and the one-line defense. A few that trip up beginners:

- **Crash between DB insert and Kafka publish (dual-write)** → the row is stuck `PENDING`. The **reconciliation sweeper** re-enqueues stale `PENDING` rows; the worker's idempotency check means re-enqueuing something already sent is harmless.
- **All push tokens invalid** → deactivate the dead tokens (don't keep retrying a token FCM rejected) and fail/skip that channel.
- **Email hard-bounce** → *permanent* failure, so **no retry** — retrying a non-existent address just burns quota.
- **Quiet hours** → skip *promotional* sends, but let **OTP/transactional bypass** (an OTP at 2 AM is expected and wanted).
- **Template missing** → **fail fast and alert**; never send a blank or half-rendered message.

```java
catch (InvalidTokenException e) { deviceRepo.deactivate(d.deviceId); }  // stale token → stop using it
catch (HardBounceException e)   { markFailed(attempt); }                 // permanent → no retry
catch (ProviderDownException e) { scheduleRetryWithBackoff(job); }        // transient → try later
```

#### Q: When do we retry vs give up?

Ask "**would trying again ever help?**" Transient problems (provider down, timeout, temporary token failure) → retry with backoff. Permanent problems (invalid email, hard bounce, no devices, missing template) → give up immediately and record `FAILED`. Retrying a permanently-broken send just wastes time and provider quota — and delays the DLQ alert a human needs to see.

#### Q: Do OTPs really ignore quiet hours and rate limits?

Yes — they're **transactional and essential**. Quiet hours and the "5/day" cap exist to stop *marketing* spam. Blocking an OTP or a "your payment failed" alert at night would break the actual product. So the rules apply to `PROMOTION`-type sends, and transactional types bypass them.

---

## B13. Cheat-Sheet Mapping (HLD ↔ LLD)

| Interview ask | HLD answer | LLD answer |
| --- | --- | --- |
| "Draw the system" | A2 architecture, A3 services | — |
| "Walk through order confirmation" | A4 async flow | B6 sequence, B5 send algorithm |
| "Prevent duplicates" | Idempotency (main note §13) | B5 idempotency, B10, `UNIQUE(notification_key)` |
| "Unique index but delivery failed — retry?" | Main note §13 two-stage model | B3.3 orchestrator + B4 attempt SM + B7 sequence |
| "Where do workers run?" | A8 worker deployment | A8, A9 service ownership |
| "Kafka lag?" | Main note §21 | A10 |
| "DB bottleneck?" | Main note §22 | A11, B11 batching |
| "Template versioning?" | Main note §15 | B1 templates DDL, `template_version` on notifications |
| "Multi-device push" | Main note §10 | B5 push fan-out, B3.4 PushWorker |
| "Retry failed SMS" | Main note §12 | B4 attempt state machine, B7 sequence |
| "Notify 1M users" | Main note §11 | B5 campaign fan-out, B8 sequence |
| "Scheduled reminder" | Main note §17 | B5 scheduler algorithm, B9 sequence |
| "Schema?" | A5 storage | B1 DDL + indexes |
| "Class design?" | A3 services | B3 orchestrator + workers + ports |
| "Real-time in-app" | Main note §18 | B3 WebSocketPort, InAppWorker |
| "Crash between insert and enqueue?" | Main note §6.1, §23 dual-write | B5 idempotency, B10, B12, `ReconciliationSweeper` |
| "PII & compliance?" | Main note §25 | A4 auth, A5 PII-at-rest |
| "Fast unread count / digests?" | Main note §26 | A5 + B11 `unread:{userId}` counter |

### How to use this table in an interview

Each row is a common interview ask, with the **big-picture answer** (HLD) and the **concrete detail** (LLD) side by side. Answer in that order: sketch the shape first, then drop into specifics only if pushed.

> **One-breath summary of the whole system:** *A source drops a request → the API decides (prefs, template, dedup) and saves it → Kafka decouples decide from deliver → per-channel workers deliver via provider adapters, retrying with backoff and DLQ → idempotency everywhere makes at-least-once look like exactly-once.* If you can say that and then zoom into any one piece (retries, fan-out, scheduling, hot topics), you've got the design.

---

> See **Notification System — System Design** for deeper rationale, plus **Idempotency**, **Rate Limiting**, and **Outbox & Saga** notes for supporting patterns.
