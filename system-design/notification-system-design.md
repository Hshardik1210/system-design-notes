# Notification System — System Design

> **Core challenge:** Reliably deliver the **right message** to the **right user** on the **right channel** at the **right time** — without duplicates, without spam, and without blocking the source system. Used by Amazon (order updates), Swiggy (delivery tracking), BookMyShow (show reminders), LinkedIn (activity alerts), WhatsApp (messages).

> **The tension in one line:** to never *lose* a notification you must retry (**at-least-once**), but every retry risks sending the *same* thing twice — so the entire design is "try hard enough that nothing is dropped, dedup hard enough that nothing lands twice, and never spam." Almost every decision below serves this.

> **How to read this doc:** each section has the dense interview summary first, then a **deep dive** (annotated Java and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand.

---

## Contents

- [1. What Is a Notification System?](#1-what-is-a-notification-system)
- [2. Requirements](#2-requirements)
- [3. Capacity Estimation (back-of-envelope)](#3-capacity-estimation-back-of-envelope)
- [4. API Design](#4-api-design)
- [5. High-Level Architecture](#5-high-level-architecture)
- [6. Core Components](#6-core-components)
- [7. End-to-End Flow — Order Confirmed](#7-end-to-end-flow--order-confirmed)
- [8. Data Model & Schema](#8-data-model--schema)
- [9. In-App Notifications](#9-in-app-notifications)
- [10. Push Notification Flow](#10-push-notification-flow)
- [11. Fan-Out & Bulk Notifications](#11-fan-out--bulk-notifications)
- [12. Retry, Backoff & Dead Letter Queue](#12-retry-backoff--dead-letter-queue)
- [13. Idempotency & Deduplication](#13-idempotency--deduplication)
- [14. Rate Limiting & User Preferences](#14-rate-limiting--user-preferences)
- [15. Template System](#15-template-system)
- [16. Priority Queues](#16-priority-queues)
- [17. Scheduled Notifications](#17-scheduled-notifications)
- [18. Real-Time Notifications (WebSocket / SSE)](#18-real-time-notifications-websocket--sse)
- [19. Scaling the System](#19-scaling-the-system)
- [20. Worker Deployment & Service Ownership](#20-worker-deployment--service-ownership)
- [21. Kafka Lag](#21-kafka-lag)
- [22. DB Bottlenecks & Mitigations](#22-db-bottlenecks--mitigations)
- [23. Failure Scenarios & Mitigations](#23-failure-scenarios--mitigations)
- [24. Observability](#24-observability)
- [25. Security, PII & Compliance](#25-security-pii--compliance)
- [26. Read Models, Counters & Digests](#26-read-models-counters--digests)
- [27. Final Architecture](#27-final-architecture)
- [28. How to Drive the Interview](#28-how-to-drive-the-interview)
- [29. Interview Cheat Sheet](#29-interview-cheat-sheet)
- [30. Consistency & CAP Tradeoffs](#30-consistency--cap-tradeoffs)
- [31. Design Patterns (that can be used)](#31-design-patterns-that-can-be-used)
- [32. Final Takeaways](#32-final-takeaways)

---

## 1. What Is a Notification System?

A notification system sends messages to users through one or more **delivery channels**:

| Channel | Example use case |
| --- | --- |
| **Push** | "Your order has been shipped" |
| **Email** | Payment receipt, password reset |
| **SMS** | OTP, payment failed |
| **In-app** | Bell icon with unread count |
| **WhatsApp** | Order updates (where supported) |
| **WebSocket / SSE** | Live chat, real-time feed updates |

### Channel cost, speed & reach (beginner matrix)

The channels behave nothing alike — this difference drives *which* channel a `type` uses and *how many workers* each needs (§3, §6.3):

| Channel | Cost per message | Speed to user | Reach / constraint |
| --- | --- | --- | --- |
| **In-app** | ~free (a DB write) | **Instant** — but only seen when the app is open | Any logged-in user; no external provider |
| **Push** | Very cheap | **Fast** (sub-second via FCM/APNS) | Needs the app installed + a valid device token |
| **Email** | Cheap | **Slow** (seconds to deliver; may sit unread in an inbox) | Almost universal; great for rich/long content + receipts |
| **SMS** | **Expensive** (real ₹/$ per message) | Medium (seconds; provider-rate-limited) | Near-universal reach, no app needed — reserve for OTP/critical |

> 💡 **Rule of thumb:** push/in-app for volume, email for rich content, **SMS only when it must arrive and the other channels can't reach them** (OTP, "payment failed"). Cost + rate limits are exactly why SMS gets few, carefully-throttled workers.

### Mental model

```
event → preference check → template render → queue → worker → provider → user
```

| Layer | Role |
| --- | --- |
| **Queue** | Scale + decouple + absorb spikes |
| **Workers** | Channel-specific delivery |
| **DB** | History, audit, debugging |
| **Idempotency** | Prevent duplicates |
| **DLQ** | Handle permanent failures |

> Notifications are almost always **async**. The Order Service should not wait for FCM or Twilio to respond before confirming an order.

### What problem are we even solving?

On a service like Swiggy, things happen all day that a user should hear about: an order gets confirmed, a delivery agent is 5 minutes away, a payment fails, a flash sale starts. Each of those is an **event**. The notification system's whole job is: *"something happened → tell the right people, on the channel they prefer, once, and don't fall over when 10 million events land at dinner time."*

The notification system sits between the source systems and the delivery providers: source services (Order, Payment, Chat) don't call FCM/Twilio/SendGrid directly — they hand the event to the notification system, which decides *who* to notify, *which channel* to reach them on, and *retries* on failure. The Order Service is a caller; the notification system routes and delivers; FCM/Twilio/SendGrid are the providers that do the actual sending.

### Why not just call FCM directly from the Order Service?

First instinct: when an order is confirmed, the Order Service just calls FCM (Google's push service) itself. Why big systems don't:

| Problem | What goes wrong |
| --- | --- |
| **FCM is slow / sometimes down** | If Order Service waits for FCM's response before saying "order confirmed," a slow FCM makes *ordering* slow. A down FCM makes ordering *fail*. Notifications should never break the core business flow. |
| **Spikes crush you** | A flash sale fires 1M events in seconds. If each caller sends synchronously, you get 1M simultaneous outbound calls — nothing absorbs the burst. |
| **Every service reinvents retries** | Order, Payment, Chat would each re-implement templates, retries, dedup, preferences. Wasteful and inconsistent. |

**Key insight that drives the entire design:**

> **Accept the request instantly, write it down, and deliver it later on a background worker.** The source system fires an event and moves on; a separate pipeline does the slow, failure-prone delivery work.

That "accept fast → deliver later" split is exactly a **queue** (Kafka/SQS) sitting between the API and the workers — the backbone of everything below.

```java
// BAD — Order Service blocks on the slow, flaky provider
void confirmOrder(Order o) {
    saveOrder(o);
    fcm.send(o.userId, "Order confirmed!");   // if FCM hangs, ordering hangs 😱
}

// GOOD — Order Service just announces the event and returns immediately
void confirmOrder(Order o) {
    saveOrder(o);
    events.publish(new OrderConfirmed(o.userId, o.id, o.amount)); // fire-and-forget
}
```

#### Q: Is "in-app notification" the same as "push notification"?

No — beginners mix these up constantly.

- **Push** = a message the OS shows even when your app is closed (FCM on Android, APNS on iOS). It's *delivered to the device*.
- **In-app** = a row in *your* database (the bell icon 🔔 with an unread count). The app *fetches* it when opened; nothing is delivered by Apple/Google. See [§9](#9-in-app-notifications).

One business event ("order confirmed") often produces **several** of these at once (a push *and* an email *and* an in-app row).

---

## 2. Requirements

> 💡 **Always start the interview here.** Clarify scope before designing.

### Functional

| # | Requirement |
| --- | --- |
| 1 | Send notification to **one user** |
| 2 | Send notification to **many users** (fan-out / campaigns) |
| 3 | Support multiple channels: push, email, SMS, in-app |
| 4 | Respect **user preferences** (opt-in/opt-out per type + channel) |
| 5 | **Retry** failed notifications |
| 6 | Store **notification history** (audit + support) |
| 7 | Support **scheduled** notifications (reminders, campaigns) |

### Non-Functional (NFRs)

| NFR | Target / Note |
| --- | --- |
| **Scalable** | Handle millions of notifications/day; bulk campaigns to millions of users |
| **Reliable** | At-least-once delivery with dedup; no silent drops |
| **Low latency** | OTP / payment alerts: seconds, not minutes |
| **No duplicates** | Same event consumed twice → one notification |
| **Fault tolerant** | Provider down → retry, fallback, DLQ |
| **Observable** | Metrics, logs, traces per channel and provider |

### Out of scope (state assumptions)

- Full marketing automation / A/B testing (mention, defer)
- Notification content moderation
- Multi-region active-active (mention as follow-up)

### Reading the requirements table

The two columns above (Functional vs Non-Functional) answer two different questions:

- **Functional = "what can it do?"** — the features a user or product manager would list: *send to one person, send to a million, respect opt-outs, retry, schedule.*
- **Non-Functional (NFR) = "how well must it do it?"** — the qualities that don't show up as buttons: *fast, reliable, no duplicates, survives a provider outage.*

The NFRs are usually what makes the system *hard* — anyone can send one push; sending 250M/day with no duplicates and OTPs in under a second is the real engineering.

### The "hardest" requirement, and why

> **"No duplicates" + "at-least-once delivery"** — because those two pull in opposite directions.

To never *lose* a notification you must be willing to **retry** (send again if unsure). But retrying risks sending the *same* thing twice. Squaring that circle ("try hard enough that nothing is lost, but dedup so nothing lands twice") is why [§13 Idempotency](#13-idempotency--deduplication) exists. Keep this tension in mind — it reappears everywhere.

---

## 3. Capacity Estimation (back-of-envelope)

> Numbers are illustrative — show the **method**, not exact figures.

```
Assume:
  DAU                         ~ 50M users
  Notifications per user/day  ~ 5 (mix of transactional + promo)
  Total notifications/day     ~ 250M

Average write QPS:
  250M / 86,400               ~ 2,900 notifications/sec

Peak (3–5x average)           ~ 10,000–15,000 notifications/sec
  (flash sale, festival campaigns can spike much higher)

Channel split (illustrative):
  Push     60%  → ~ 6,000/sec peak
  Email    25%  → ~ 2,500/sec peak
  SMS       5%  → ~   500/sec peak (expensive, rate-limited)
  In-app  100%  → stored for every notification that has in-app channel

Storage (notifications table):
  ~500 bytes/row
  250M/day * 365 * 500B       ~ 45 TB/year (raw; partition + archive old data)

Bulk campaign example:
  10M users, push only
  → 10M messages over ~30 min = ~5,500/sec sustained
  → Must batch + parallel workers, not one API call
```

**Takeaways that drive design:**

- **Async queue** is mandatory — API cannot send synchronously.
- **Separate workers per channel** — push volume ≫ SMS volume.
- **Fan-out is a different problem** than single-user notify — batch + campaign pipeline.
- **Partition Kafka by `user_id`** — per-user ordering + even load.

### Where these numbers come from

You're not expected to *know* the traffic — you **estimate** it out loud so the interviewer sees your reasoning. Here it's *users × notifications-per-user*.

```
50,000,000 users  ×  5 notifications each per day  =  250,000,000 per day
```

Then turn "per day" into "per second" (because servers are sized by per-second load), and multiply by a **peak factor** because traffic is bursty (dinner time, festival sales), not smooth:

```java
long perDay      = 50_000_000L * 5;        // 250,000,000
double avgPerSec = perDay / 86_400.0;      // 86,400 seconds in a day ≈ 2,900/sec
double peakPerSec = avgPerSec * 5;         // bursts are ~3–5× the average ≈ 14,500/sec
```

### Why split traffic by channel (push 60%, SMS 5%)

The channels behave nothing alike, and that difference **shapes the whole design**:

- **Push** is cheap and high-volume → you'll run *lots* of push workers.
- **SMS** costs real money per message and providers rate-limit you → few workers, careful throttling.

Sizing them separately is why we later run **separate worker pools per channel** ([§6.3](#63-channel-workers)) instead of one pool. The estimate isn't trivia; it's the justification for that decision.

### A campaign is 10M messages — why not just loop and send them

The last block shows the trap: 10M users pushed in one go = a wall of traffic that dwarfs normal load. That's why fan-out ([§11](#11-fan-out--bulk-notifications)) is a **spread-it-out batch pipeline** (5,500/sec for 30 min), not a `for` loop. Estimation is what reveals that a campaign is a *different* problem from a single notify.

---

## 4. API Design

### Send notification (from internal services)

```
POST /v1/notifications
```

```json
{
  "userId": 123,
  "type": "ORDER_CONFIRMED",
  "channels": ["PUSH", "EMAIL"],
  "idempotencyKey": "123:ORDER_CONFIRMED:order_789",
  "data": {
    "orderId": 789,
    "amount": 1500
  },
  "priority": "NORMAL",
  "scheduledAt": null
}
```

| Field | Purpose |
| --- | --- |
| `type` | Maps to template + preference rules |
| `channels` | Requested channels (intersected with user prefs) |
| `idempotencyKey` | Dedup key — see [§13](#13-idempotency--deduplication) |
| `priority` | Routes to high/normal/low queue |
| `scheduledAt` | Future delivery time |

**Response:** `202 Accepted` with `{ notificationId, status: "PENDING" }` — fire-and-forget.

> The API **validates, persists, enqueues** — it does **not** call FCM/SendGrid directly.

### In-app (user-facing)

```
GET  /v1/users/{userId}/notifications?cursor=&limit=20
PATCH /v1/notifications/{id}/read
PATCH /v1/users/{userId}/notifications/read-all
```

### Device registration (push)

```
POST   /v1/users/{userId}/devices
DELETE /v1/users/{userId}/devices/{deviceId}
```

```json
{
  "deviceId": "uuid",
  "platform": "IOS",
  "deviceToken": "apns_token_here"
}
```

### Preferences

```
GET  /v1/users/{userId}/notification-preferences
PUT  /v1/users/{userId}/notification-preferences
```

### What the API actually promises

The important line is **"return `202 Accepted`, fire-and-forget."** The API accepts the request, records it, and returns immediately — it does **not** wait for the message to actually reach the user. `202` literally means *"I've taken responsibility for this; I'll deliver it in the background."* (Contrast `200 OK` = "done" and `201 Created` = "made a thing.")

```java
@PostMapping("/v1/notifications")
public ResponseEntity<Ack> send(@RequestBody NotificationRequest req) {
    validate(req);                         // 1. is this well-formed & allowed?
    long id = repo.insertPending(req);     // 2. write it down (status = PENDING)
    queue.publish(req.channelTopic(), id); // 3. drop it on the queue for a worker
    // NOTE: we did NOT call FCM/Twilio here — that's the worker's job.
    return ResponseEntity
        .accepted()                        // HTTP 202
        .body(new Ack(id, "PENDING"));
}
```

### What the `idempotencyKey` is, and why the caller sends it

`idempotencyKey` = a **fingerprint of the business event**, e.g. `123:ORDER_CONFIRMED:order_789`. The caller builds it from things that are stable for that event (user + type + the specific order). If the Order Service (or Kafka) accidentally fires the *same* event twice, both requests carry the **same** key → the notification system stores only one → the user gets one push, not two. The caller provides it because only the caller knows what makes *this* event unique. Full mechanics in [§13](#13-idempotency--deduplication).

### Why the send payload doesn't include the actual message text

Notice the request has `type` and `data`, but no "title"/"body" string. That's deliberate: the **template system** ([§15](#15-template-system)) turns `type + data` into text at send time. The caller says *"an order was confirmed, here are the facts"*; the notification service decides the exact wording (and language, and per-channel length). This keeps message copy in one place instead of scattered across every calling service.

---

## 5. High-Level Architecture

```
                  ┌──────────────┐
                  │ Source System│
                  │ Order/Payment│
                  │ User/Campaign│
                  └──────┬───────┘
                         │ event or HTTP
                         ▼
                  ┌──────────────┐
                  │ Notification │
                  │ API Service  │
                  └──────┬───────┘
                         │
          ┌──────────────┼──────────────┐
          ▼              ▼              ▼
   ┌────────────┐ ┌────────────┐ ┌────────────┐
   │ Preference │ │  Template  │ │ Notification│
   │  Service   │ │  Service   │ │     DB      │
   └────────────┘ └────────────┘ └────────────┘
                         │
                         ▼
                  ┌──────────────┐
                  │ Kafka / SQS  │
                  │ Message Queue│
                  └──────┬───────┘
                         │
        ┌────────────────┼────────────────┬──────────────┐
        ▼                ▼                ▼              ▼
 ┌─────────────┐  ┌─────────────┐  ┌─────────────┐ ┌─────────────┐
 │ Push Worker │  │ Email Worker│  │ SMS Worker  │ │ In-app Wkr  │
 └──────┬──────┘  └──────┬──────┘  └──────┬──────┘ └──────┬──────┘
        │                │                │              │
        ▼                ▼                ▼              ▼
 ┌─────────────┐  ┌─────────────┐  ┌─────────────┐ ┌─────────────┐
 │ FCM / APNS  │  │ SES/SendGrid│  │ Twilio/etc  │ │ DB + WS Svc │
 └─────────────┘  └─────────────┘  └─────────────┘ └─────────────┘
```

### Why a message queue?

| Benefit | Explanation |
| --- | --- |
| **Decoupling** | Order Service doesn't depend on Twilio uptime |
| **Spike absorption** | Queue buffers flash-sale traffic |
| **Retries** | Re-consume failed messages with backoff |
| **Fast API** | Return 202 immediately; delivery is async |

**Queue choices:** Kafka (high throughput, replay, partitioning), SQS (managed, DLQ built-in), RabbitMQ (routing flexibility). Kafka is the common interview answer at scale.

### Reading the box diagram top to bottom

Follow one order-confirmation through the picture top to bottom:

1. **Source system** (Order Service) — emits a "please notify" event.
2. **Notification API** — checks preferences, renders the template, logs the notification to the DB, and publishes to the right topic.
3. **Kafka (the queue)** — buffers messages, which wait here safely even if the workers are busy. This is what absorbs a flash-sale surge.
4. **Channel workers** — one pool per channel; each consumes from its topic and delivers.
5. **Providers (FCM/SES/Twilio)** — the last mile that actually reaches the device, inbox, or phone.

The single most important idea in the whole diagram is that **the queue sits in the middle**, so the left half (accepting work) and the right half (delivering work) can run, fail, and scale **independently**.

### Why not skip the queue and let the API call the workers directly

If it did, the API's speed would be chained to the *slowest provider*. The queue **decouples** them: the API dumps a message and returns in milliseconds; workers drain the queue at their own pace. If Twilio has a bad 10 minutes, messages simply **wait** in Kafka instead of being lost or blocking anyone.

```java
// The API side — never touches a provider, just enqueues:
queue.publish("notification-push-normal", new QueueMsg(notificationId, "PUSH"));

// The worker side — a totally separate process, reads at its own speed:
@KafkaListener(topics = "notification-push-normal")
public void onMessage(QueueMsg msg) {
    var n = repo.load(msg.notificationId());
    fcm.send(n);                    // the slow part happens HERE, off the request path
}
```

### Kafka vs SQS vs RabbitMQ — choosing without memorizing

One-line mental model:

| Queue | Pick it when |
| --- | --- |
| **Kafka** | Huge throughput, want to **replay** old messages, want partitioning by `user_id`. Default answer at scale. |
| **SQS** | You're on AWS and want zero ops — managed, DLQ built in, good enough for most. |
| **RabbitMQ** | You need fancy **routing** rules (this message to these queues by pattern). |

For an interview, say "Kafka" and give the reason (throughput + replay + partitioning); the reasoning matters more than the brand.

---

## 6. Core Components

### 6.1 Notification API Service

- Receives requests from other services (HTTP) or consumes domain events (Kafka).
- Validates user, notification type, payload.
- Resolves channels via **preferences**.
- Renders message via **templates**.
- Creates DB record (`PENDING`).
- Publishes to **channel-specific Kafka topics**.

> Single responsibility: **orchestrate**, not **deliver**.

> ⚠️ **Dual-write hazard:** "insert DB row" + "publish to Kafka" are **two separate systems**. If the process crashes between them, the row is stuck in `PENDING` and never delivered. Fix with an **outbox** (write the row + an outbox record in one transaction; a relay publishes to Kafka) or a **reconciliation sweeper** — see [§23](#23-failure-scenarios--mitigations).

### 6.2 Message Queue

Topics (channel × priority example):

```
notification-push-high
notification-push-normal
notification-push-low
notification-email-normal
notification-sms-high
notification-inapp
```

**Partition key:** `user_id` — all notifications for user 123 land on the same partition → **ordering per user**.

### 6.3 Channel Workers

Each worker:

1. Consumes from its topic
2. Calls external provider (FCM, SES, Twilio)
3. Records attempt in `notification_attempts`
4. Updates status: `SENT` / `FAILED` / `RETRYING`
5. On permanent failure → **DLQ**

**Why separate workers?**

| Channel | Characteristic |
| --- | --- |
| Push | High volume, relatively fast |
| Email | Slower, batch-friendly |
| SMS | Expensive, strict provider rate limits |
| In-app | DB write + optional WebSocket push |

Each scales **independently** — 100 push workers, 20 email workers, 10 SMS workers.

### 6.4 Preference Service

Answers: *"For user 123 and type ORDER_CONFIRMED, which channels are allowed?"*

```
Requested channels ∩ User prefs ∩ Legal/quiet hours = Final channels
```

### 6.5 Template Service

Maps `(type, channel, language)` → title/body templates with variable substitution.

### Each component is one job

Each component has exactly one job:

| Component | One-sentence job |
| --- | --- |
| **API Service** | Accept, validate, orchestrate — but deliver nothing |
| **Preference Service** | Decide *which channels are allowed* |
| **Template Service** | Turn `type + data` into actual words |
| **Message Queue** | Buffer + decouple + absorb spikes |
| **Channel Workers** | Actually deliver via the provider |

The golden rule for the API: **"orchestrate, not deliver."** It coordinates who does what, but it never calls a provider (FCM/Twilio) itself.

```java
// The API's whole job is coordination — notice it delegates every real task:
class NotificationApiService {
    void handle(NotificationRequest req) {
        var channels = preferenceService.allowedChannels(req.userId(), req.type()); // §6.4
        if (channels.isEmpty()) return;                    // user opted out of everything
        var rendered = templateService.render(req.type(), req.data(), channels);   // §6.5
        long id = repo.insertPending(req, rendered);       // write history row
        for (Channel c : channels) {
            queue.publish(topicFor(c, req.priority()), new QueueMsg(id, c));        // §6.2
        }
        // returns; the cooks (workers) take it from here
    }
}
```

### What the "dual-write hazard" the warning box mentions

The API does two things that live in **two different systems**: (1) insert a DB row, (2) publish to Kafka. There's no single "do both or neither" button across a database *and* a message broker. So if the process dies *after* the insert but *before* the publish, you get a row stuck at `PENDING` forever — a notification that exists on paper but never gets delivered.

The fix (**outbox** or a **reconciliation sweeper**) is covered in [§23](#23-failure-scenarios--mitigations) — for now just internalize *"two systems, one crash = trouble."*

### Why the Preference Service is separate from the workers

So the decision *"is this user allowed to get a promo SMS at 11 PM?"* is made **once, up front**, in one place — before anything is queued. If each worker re-checked preferences you'd duplicate that logic four times and risk them disagreeing. One gatekeeper, one answer.

---

## 7. End-to-End Flow — Order Confirmed

**Scenario:** User places order → send push + email.

```
Step 1: Order Service emits event
─────────────────────────────────
{
  "eventType": "ORDER_CONFIRMED",
  "userId": 123,
  "orderId": 789,
  "amount": 1500
}

Step 2: Notification Service receives event
────────────────────────────────────────────
  ✓ User exists?
  ✓ Notification type valid?
  ✓ Idempotency key: 123:ORDER_CONFIRMED:order_789

Step 3: Check user preferences
────────────────────────────────
  User 123, ORDER_CONFIRMED:
    push:  enabled
    email: enabled
    sms:   disabled
  → Final channels: PUSH + EMAIL

Step 4: Render templates
────────────────────────
  Push:  "Order confirmed! #789 for ₹1500"
  Email: HTML receipt template

Step 5: Create notification record
──────────────────────────────────
  notif_1 | user_123 | ORDER_CONFIRMED | PENDING | created_at

Step 6: Enqueue to Kafka
────────────────────────
  → notification-push-normal   { notificationId: 1, channel: "PUSH" }
  → notification-email-normal  { notificationId: 1, channel: "EMAIL" }
  (minimal payload — worker loads title/body from DB; see §13)

Step 7: Workers deliver
───────────────────────
  Push worker  → FCM → user's devices
  Email worker → SendGrid → user@email.com

Step 8: Update status
─────────────────────
  PENDING → SENT (with sent_at timestamp)

  On failure:
  PENDING → RETRYING → SENT
                    └→ FAILED → DLQ (after max retries)

  Multi-channel: push SENT + email FAILED → PARTIALLY_SENT
```

### The whole journey, step by step

Follow user 123's order confirmation step by step:

1. **Event fires** — "Order 789 confirmed for ₹1500." (Order Service emits this event.)
2. **Notification service catches it** and asks three sanity questions: *Does this user exist? Is this a real notification type? Have I already handled this exact event?* (that last one = the idempotency key).
3. **Preferences** — "User 123 wants push + email for orders, but not SMS." → final channels = push, email.
4. **Render** — plug the facts into the template: *"Order confirmed! #789 for ₹1500."*
5. **Write it down** — one `notifications` row, status `PENDING`. This is the audit trail.
6. **Enqueue** — drop a tiny message on the push topic and the email topic. Note the payload is just `{ notificationId, channel }` — not the full text (see [§13](#13-idempotency--deduplication) on why small payloads).
7. **Workers deliver** — push worker → FCM, email worker → SendGrid.
8. **Update status** — `PENDING → SENT`. If push works but email fails, the overall row becomes `PARTIALLY_SENT`.

```java
// A compressed version of the whole flow, so you can see it end to end:
void onOrderConfirmed(OrderConfirmed e) {
    String key = e.userId() + ":ORDER_CONFIRMED:order_" + e.orderId();  // idempotency fingerprint
    if (repo.existsByKey(key)) return;                                  // step 2: already handled

    var channels = prefs.allowed(e.userId(), "ORDER_CONFIRMED");        // step 3 → [PUSH, EMAIL]
    var text = templates.render("ORDER_CONFIRMED", e.facts(), channels);// step 4
    long id = repo.insert(key, e.userId(), text, "PENDING");            // step 5

    for (Channel c : channels)                                          // step 6
        queue.publish(topicFor(c, "NORMAL"), new QueueMsg(id, c));
    // steps 7–8 happen later, inside the workers
}
```

### What `PARTIALLY_SENT` means, and why keep it

One business event fans out to several channels, and they can succeed **independently**. Push might land while email bounces. `PARTIALLY_SENT` records that mixed outcome honestly instead of lying with a flat `SENT` or `FAILED`. It also tells a retry job *"only the email needs another attempt"* — the push is already done. Per-channel truth lives in the `notification_attempts` table ([§8](#8-data-model--schema)).

### Why check idempotency *before* doing all this work

The whole point is **"one business event → one notification."** If the same `ORDER_CONFIRMED` arrives twice (Kafka is at-least-once), the second pass should find the key already exists and stop — otherwise the user gets two "order confirmed" pings. Checking early also avoids wasting template rendering and DB writes on a duplicate.

---

## 8. Data Model & Schema

### Database & storage choices (which DB, and why at scale)

No single store fits every job here, so we use **polyglot persistence** — pick the store that matches each data type's access pattern. The deciding question for the master record is *"do we need a UNIQUE constraint to enforce idempotency?"* — the answer is yes (§13), which points straight at a relational store rather than an eventually-consistent one.

| Data | Store | Why this one | Why not the alternative |
| --- | --- | --- | --- |
| Notifications (master record), attempts, preferences, templates — **source of truth** | **RDBMS** (PostgreSQL/MySQL) | Idempotency and "exactly one active template" are enforced as **UNIQUE constraints** — `notification_key` UNIQUE, `(notification_id, channel)` UNIQUE on attempts, a partial unique index for one active template per `(type, channel, language)`. These are database guarantees, not app-level bookkeeping. | An eventually-consistent NoSQL store gives you none of these constraints for free — you'd have to hand-roll dedup and "which version is live" checks in application code, and still race under concurrent writers. |
| Event backbone — every "send this" command, from API to channel workers | **Kafka**, partitioned by `user_id` | Durable log **decouples** the API from slow providers (Twilio/FCM can have a bad 10 minutes without losing anything, §5); partitioning by `user_id` gives **per-user ordering** (push and email for the same event don't arrive out of order) while spreading load across partitions. | Calling FCM/Twilio/SES synchronously from the API chains its latency to the *slowest* provider and loses messages on a crash mid-call. SQS/RabbitMQ lack Kafka's cheap replay for reprocessing after a bug fix. |
| Rate-limit counters, dedup markers, in-app unread badge, WebSocket connection routing, hot cache (preferences/templates/device tokens) | **Redis** | All are checked on nearly **every** request (thousands/sec) — an atomic `INCR`/`SET NX EX` is O(1), auto-expires, and needs no durability beyond a TTL. It's a scoreboard, not a ledger. | Counting `notifications` rows for a rate limit, or `COUNT(*) WHERE is_read=false` for the unread badge, re-runs the same expensive query millions of times a day against the RDBMS. |
| Massive append-only delivery logs (optional, only past RDBMS scale) | **Cassandra** | If attempt volume genuinely outgrows one RDBMS's indexes (huge fleets, long retention), a wide-column store absorbs unbounded appends — attempts are already scoped to one `notification_id`, so there's no cross-row transaction to give up. | Not worth the operational cost by default — you lose the `UNIQUE (notification_id, channel)` constraint and must dedupe attempts in application code instead of at the DB layer. |
| Rich HTML email templates, large attachments | **Blob store (S3) + CDN** | Big, mostly-static bytes are cheap to store and serve from the edge; the DB keeps only a pointer. | Inlining HTML blobs into `notification_templates` bloats a hot table and kills cache locality for every other template read. |

**Why RDBMS wins for the master record, and Kafka (not sync calls) for delivery:** at ~15k inserts/sec (§3) a single RDBMS primary handles the write volume comfortably — the hard requirement isn't throughput, it's the **UNIQUE constraints** that make idempotency (§13) a database guarantee instead of app-level bookkeeping. Kafka sits between the API and the channel workers precisely so the API never blocks on a provider call; partitioning by **`user_id`** keeps one user's notifications ordered while spreading users evenly across partitions, and a genuinely hot `user_id` (a broadcast/system account) is handled the same way as any hot-key problem — extra consumer capacity or a synthetic sub-key, not a redesign. Preferences, templates, and device tokens are read constantly but change rarely, so a Redis cache sits in front of the RDBMS rather than hitting Postgres on every send. (For the full engine trade-off matrix, see [Databases — Deep Dive](../concepts/databases-deep-dive.md).)

### Users (minimal — usually owned by User Service)

```sql
CREATE TABLE users (
    user_id     BIGINT PRIMARY KEY,
    email       VARCHAR(255),
    phone       VARCHAR(20),
    name        VARCHAR(255)
);
```

### Notification preferences

Granularity: **per user, per notification type, per channel**.

```sql
CREATE TABLE notification_preferences (
    user_id           BIGINT NOT NULL,
    notification_type VARCHAR(100) NOT NULL,
    push_enabled      BOOLEAN DEFAULT TRUE,
    email_enabled     BOOLEAN DEFAULT TRUE,
    sms_enabled       BOOLEAN DEFAULT FALSE,
    in_app_enabled    BOOLEAN DEFAULT TRUE,
    PRIMARY KEY (user_id, notification_type)
);
```

Example:

| user_id | type | push | email | sms |
| --- | --- | --- | --- | --- |
| 123 | ORDER_CONFIRMED | true | true | false |
| 123 | PROMOTION | false | true | false |

### Notifications (master record)

```sql
CREATE TABLE notifications (
    notification_id   BIGINT PRIMARY KEY,      -- Snowflake / distributed ID, not a single DB sequence (see note)
    notification_key  VARCHAR(255) NOT NULL,  -- idempotency key
    user_id           BIGINT NOT NULL,
    type              VARCHAR(100) NOT NULL,
    template_type     VARCHAR(100),            -- e.g. ORDER_CONFIRMED
    template_version  INT,                     -- snapshot: which version was rendered
    title             TEXT,                    -- rendered snapshot (audit)
    body              TEXT,
    status            VARCHAR(50) NOT NULL,  -- PENDING, PROCESSING, SENT, PARTIALLY_SENT, FAILED, RETRYING, CANCELLED, RATE_LIMITED
    priority          VARCHAR(20) DEFAULT 'NORMAL',
    next_retry_at     TIMESTAMP,               -- optional; prefer Kafka retry topics at scale
    created_at        TIMESTAMP NOT NULL DEFAULT now(),
    sent_at           TIMESTAMP,
    scheduled_at      TIMESTAMP,

    UNIQUE (notification_key)
);

CREATE INDEX idx_notifications_user_created
    ON notifications (user_id, created_at DESC);

CREATE INDEX idx_notifications_retry
    ON notifications (status, next_retry_at)
    WHERE status IN ('RETRYING', 'FAILED');
```

### Notification attempts (per channel — debugging & retries)

```sql
CREATE TABLE notification_attempts (
    attempt_id        BIGINT PRIMARY KEY,
    notification_id   BIGINT NOT NULL REFERENCES notifications(notification_id),
    channel           VARCHAR(50) NOT NULL,   -- PUSH, EMAIL, SMS, IN_APP
    status            VARCHAR(50) NOT NULL,   -- PENDING, SENT, FAILED, RETRYING
    provider          VARCHAR(50),            -- FCM, APNS, SES, TWILIO
    provider_response TEXT,
    attempt_count     INT DEFAULT 0,
    next_retry_at     TIMESTAMP,
    created_at        TIMESTAMP NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (notification_id, channel)         -- one attempt row per channel per notification
);

CREATE INDEX idx_attempts_notification
    ON notification_attempts (notification_id);

CREATE INDEX idx_attempts_retry
    ON notification_attempts (next_retry_at)
    WHERE status IN ('FAILED', 'RETRYING');
```

> **Why two tables?**
>
> | Table | Role |
> | --- | --- |
> | **`notifications`** | **Intent to notify** — created once per business event (dedup via `notification_key`) |
> | **`notification_attempts`** | **Delivery attempts** — one row per channel; tracks every send/retry/failure |
>
> Support asks: *"Why didn't SMS arrive?"* → check `notification_attempts`. Retries update the **same** `notification_id` — no re-insert needed.

> **ID generation:** at ~15k inserts/sec a single auto-increment sequence becomes a bottleneck and couples you to one DB (bad for sharding). Use **Snowflake-style IDs** (time-ordered 64-bit: timestamp + worker id + sequence) or UUIDv7 — generated app-side, roughly time-sortable, and shard-friendly.

### Device tokens (push)

```sql
CREATE TABLE user_devices (
    device_id     VARCHAR(100) PRIMARY KEY,
    user_id       BIGINT NOT NULL,
    platform      VARCHAR(20) NOT NULL,   -- IOS, ANDROID, WEB
    device_token  TEXT NOT NULL,
    is_active     BOOLEAN DEFAULT TRUE,
    last_seen_at  TIMESTAMP,
    created_at    TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_devices_user_active
    ON user_devices (user_id) WHERE is_active = TRUE;
```

One user → many devices (iPhone, Android tablet). Push worker **fans out to all active device tokens**.

### Scheduled notifications

```sql
CREATE TABLE scheduled_notifications (
    id              BIGINT PRIMARY KEY,
    notification_id BIGINT REFERENCES notifications(notification_id),
    user_id         BIGINT NOT NULL,
    type            VARCHAR(100) NOT NULL,
    scheduled_at    TIMESTAMP NOT NULL,
    status          VARCHAR(50) DEFAULT 'PENDING',  -- PENDING, DISPATCHED, CANCELLED
    payload         JSONB
);

CREATE INDEX idx_scheduled_due
    ON scheduled_notifications (scheduled_at)
    WHERE status = 'PENDING';
```

### Templates (versioned — never edit in place)

```sql
CREATE TABLE notification_templates (
    template_id     BIGINT PRIMARY KEY,        -- surrogate key (BIGINT or UUID — see §15)
    type            VARCHAR(100) NOT NULL,     -- ORDER_CONFIRMED (the "why", stays stable)
    channel         VARCHAR(50) NOT NULL,
    language        VARCHAR(20) DEFAULT 'en',
    version         INT NOT NULL DEFAULT 1,
    title_template  TEXT,
    body_template   TEXT NOT NULL,
    is_active       BOOLEAN DEFAULT TRUE,      -- latest active version for new sends
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (type, channel, language, version)
);

-- Enforce EXACTLY ONE active template per (type, channel, language).
-- Postgres partial unique index — prevents "which one is live?" ambiguity.
CREATE UNIQUE INDEX uniq_active_template
    ON notification_templates (type, channel, language)
    WHERE is_active = TRUE;
```

When copy changes, **insert a new version** — do not overwrite v1 (worked example + the "pick the active row" query are in [§15](#15-template-system)).

The `notifications` row stores `template_type` + `template_version` (or a direct `template_id` FK — see [§15](#15-template-system)) **plus** the rendered `title`/`body` snapshot — so you always know exactly what was sent, even after v2 goes live.

### Why so many tables?

Each table answers a different question:

| Table | The question it answers |
| --- | --- |
| `users` | Who is this person? (email, phone) |
| `notification_preferences` | What are they willing to receive, and where? |
| `notifications` | What did we *intend* to tell them, once per event? |
| `notification_attempts` | For each channel, what actually happened on each try? |
| `user_devices` | Which phones/tablets should a push go to? |
| `notification_templates` | What's the reusable wording, and which version? |

The **one distinction that matters most**: `notifications` vs `notification_attempts`.

> `notifications` = the **intent** ("we mean to tell user 123 their order is confirmed") — created **once** per event.
> `notification_attempts` = the **delivery log** ("push try #1 to FCM failed, try #2 succeeded; email try #1 succeeded") — **many** rows, one per channel, updated on every retry.

You never re-create the notification row because a delivery failed — you just log another attempt against it.

```java
// One "notifications" row = one intent, created once:
class Notification {
    long   notificationId;      // Snowflake / UUIDv7 — see note below on why not auto-increment
    String notificationKey;     // "123:ORDER_CONFIRMED:order_789" — UNIQUE → dedup
    long   userId;
    String type;                // ORDER_CONFIRMED
    String title, body;         // rendered snapshot (what was actually sent — audit)
    Status status;              // PENDING → SENT / PARTIALLY_SENT / FAILED ...
}

// Many "attempts" rows = per-channel delivery log, one per (notificationId, channel):
class NotificationAttempt {
    long   attemptId;
    long   notificationId;      // points back to the intent above
    String channel;             // PUSH / EMAIL / SMS
    Status status;              // SENT / FAILED / RETRYING
    String provider;            // FCM / SES / TWILIO — which one we used
    int    attemptCount;        // how many times we've tried this channel
}
```

### Why not just use an auto-increment ID (1, 2, 3, ...)

Because a single auto-increment counter lives in **one** database. At ~15k inserts/sec that counter becomes a traffic jam, and worse — the moment you want to **shard** (split the table across many DBs), two shards would both hand out "id = 1000." **Snowflake / UUIDv7** IDs are generated by the app itself (no central counter), are still roughly time-ordered, and never collide across shards.

### Why store the rendered `title`/`body` on the row if we have templates

Templates change. If a support agent asks *"what exact text did we send user 123 last March?"*, re-rendering today's template might give **different** words (the template was edited since). Snapshotting the final text on the `notifications` row freezes history: you keep exactly what was sent, not just a pointer to a template that may have changed since. Compliance and debugging both depend on this.

### Why the `UNIQUE (notification_id, channel)` on attempts

This guarantees **exactly one attempt row per channel** per notification, which you *update* on each retry — instead of inserting a new row every try and drowning in duplicates. "Push to notification 5" is one row whose `attempt_count` ticks up; it's not five rows.

---

## 9. In-App Notifications

In-app notifications are **stored**, not pushed through FCM/email. The app fetches them or receives them via WebSocket.

```sql
CREATE TABLE in_app_notifications (
    id          BIGINT PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    title       TEXT NOT NULL,
    message     TEXT NOT NULL,
    is_read     BOOLEAN DEFAULT FALSE,
    metadata    JSONB,           -- deep link, entity id, etc.
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_inapp_user_unread
    ON in_app_notifications (user_id, created_at DESC)
    WHERE is_read = FALSE;
```

**Flow:**

1. In-app worker writes row to `in_app_notifications`
2. Optionally publishes to WebSocket service → instant bell update
3. App polls `GET /users/{id}/notifications` or listens on WebSocket
4. User taps → `PATCH /notifications/{id}/read`

> In-app is cheap (DB write) and always available — good fallback when push token is invalid.

> The unread badge count should not be a `COUNT(*)` on every open — use a **Redis counter** (see [§26](#26-read-models-counters--digests)).

### In-app notifications: stored, not delivered

A **push** is delivered to the device by the OS whether or not your app is open. An **in-app notification** is just a **stored row**: we write a DB row for user 123; nothing is pushed. When the user opens the app, it fetches them (`GET /notifications`). The bell icon 🔔 shows the count of unread rows.

That's why in-app is the **most reliable** channel: there's no Apple/Google in the middle who might reject a stale token. Writing a database row basically always works, so in-app is the natural **fallback** when a push token is dead.

```java
@KafkaListener(topics = "notification-inapp")
public void deliverInApp(QueueMsg msg) {
    var n = repo.load(msg.notificationId());

    // 1. Write the row for the user (this is the real delivery)
    inAppRepo.insert(new InAppRow(n.userId(), n.title(), n.body(), /*isRead*/ false));

    // 2. Bump the unread badge counter (O(1), see §26) — no COUNT(*) scan
    redis.incr("unread:" + n.userId());

    // 3. OPTIONAL nicety: if the user is online, poke their WebSocket so the
    //    bell updates instantly instead of on next app open (see §18)
    webSocket.tryPush(n.userId(), n);
}
```

### If in-app is just a DB row, what the WebSocket part is for

Pure in-app works even with no live connection — the app sees the new row *next time it opens or polls*. The WebSocket ([§18](#18-real-time-notifications-websocket--sse)) is an optional live nudge so the bell icon updates the *instant* the row is written, without the user reopening the app. Delivery = the DB row; the WebSocket is just freshness.

### Why not `SELECT COUNT(*) WHERE is_read = false` for the badge

The bell is checked on **every app open** by **every user** — that's a firehose of counting queries over a growing table. Instead keep a tiny Redis counter per user (`unread:123 = 7`): `INCR` when a row is added, `DECR` on read, `SET 0` on "mark all read." An O(n) scan becomes an O(1) lookup. Full pattern in [§26](#26-read-models-counters--digests).

---

## 10. Push Notification Flow

```
┌─────────┐    register token     ┌─────────────┐
│ Mobile  │ ────────────────────► │   Backend   │
│   App   │                       │ user_devices│
└────┬────┘                       └──────┬──────┘
     │                                   │
     │         Push Worker               │
     │    ◄──────────────────────────────┘
     │         FCM (Android) / APNS (iOS)
     ▼
  Notification on device
```

| Platform | Provider |
| --- | --- |
| Android | **FCM** (Firebase Cloud Messaging) |
| iOS | **APNS** (Apple Push Notification Service) |
| Web | FCM Web Push |

**Device token lifecycle:**

- App login → send token to backend
- Token refresh (iOS/Android do this) → update backend
- Push fails with `InvalidRegistration` / `Unregistered` → mark `is_active = false`
- Logout → delete or deactivate token

### What a "device token" actually is

You can't push to "user 123" — Apple and Google don't know who that is. You can only push to a **specific installed app on a specific device**, identified by a long random string called a **device token** (issued by FCM/APNS when the app first registers). So the flow is:

> user_id → look up all their **active device tokens** → send the push to *each* token via FCM/APNS.

One user typically has several tokens (iPhone, iPad, work Android), and to reach them you send to **every** active token, because you don't know which device they're currently using.

```java
@KafkaListener(topics = "notification-push-normal")
public void deliverPush(QueueMsg msg) {
    var n = repo.load(msg.notificationId());

    // one user → many devices → fan out to every ACTIVE token
    List<Device> devices = deviceRepo.activeTokens(n.userId());   // iPhone + iPad + ...

    for (Device d : devices) {
        try {
            fcm.send(d.token(), n.title(), n.body());
        } catch (InvalidTokenException e) {
            // the app was uninstalled / token expired → this token is dead
            deviceRepo.deactivate(d.deviceId());   // is_active = false, stop trying it
        }
    }
}
```

### Why tokens "expire," and why deactivate instead of delete

The OS reissues tokens periodically (app reinstall, OS update, restore-from-backup). When you push to an old token, FCM replies `Unregistered`/`InvalidRegistration`. You mark that row `is_active = false` so you stop wasting calls on a dead box. Soft-deactivating (vs hard delete) keeps a paper trail for debugging *"why did this user stop getting pushes?"* — the row is still there, just flagged inactive.

### FCM vs APNS — do you need to care which

Slightly. Android → **FCM** (Google), iOS → **APNS** (Apple), Web → FCM Web Push. The push worker just needs to route each token to the right provider based on the device's `platform` column. A good design hides this behind one interface so the rest of the code says `push.send(token, msg)` and the adapter picks FCM vs APNS underneath (the **Ports & Adapters** pattern — see [§31](#31-design-patterns-that-can-be-used)).

---

## 11. Fan-Out & Bulk Notifications

**Fan-out:** one event → many users → many notifications.

Example: Flash sale starts → notify 1M users.

### Bad approach ❌

```
POST /notifications with 1M userIds in one request
→ API timeout, memory blow-up, single point of failure
```

### Good approach ✅

```
Campaign Service
      ↓
User Segment Service   (SQL/ES: "users in BLR who bought electronics")
      ↓
Batch Producer         (chunks of 1,000–10,000 user IDs)
      ↓
Kafka                  (notification-push-low × N partitions)
      ↓
Push Workers           (scale horizontally, process in parallel)
```

| Step | Detail |
| --- | --- |
| Create campaign | `{ campaignId, type, segment, channels, scheduledAt }` |
| Resolve segment | Stream user IDs from segment service (cursor-based) |
| Batch enqueue | One Kafka message per batch, not per user (or per user — depends on size) |
| Rate limit | Cap promotional sends per user/day |
| Track progress | `campaigns` table: total, sent, failed counts |

> **Key interview point:** Fan-out for 1M users is a **batch pipeline**, not a single API call. Same workers, different producer.

### Fan-out: one event, many notifications

**Fan-out** just means *one event turns into many notifications*. "Flash sale starts" (one event) → notify 1,000,000 users (a million notifications). One input spreads out into many outputs.

The trap beginners fall into is trying to do it in **one giant request**, which times out and blows up memory. Instead, stream user IDs a chunk at a time, publish each chunk to Kafka, and let many workers process batches in parallel.

```java
// BAD — build a list of 1,000,000 in memory and loop → OOM, timeout, no retries
void badFanOut(Campaign c) {
    List<Long> everyone = userRepo.findAll(c.segment());   // 1M in RAM 😱
    for (long userId : everyone) fcm.send(userId, c.text());
}

// GOOD — stream the segment in chunks; enqueue batches; workers do the sending
void goodFanOut(Campaign c) {
    try (var cursor = segmentService.stream(c.segment())) {  // cursor, not full list
        List<Long> batch = new ArrayList<>(1000);
        while (cursor.hasNext()) {
            batch.add(cursor.next());
            if (batch.size() == 1000) {                      // chunk of 1,000 users
                queue.publish("notification-push-low", new BatchMsg(c.id(), List.copyOf(batch)));
                batch.clear();                               // free memory, keep going
            }
        }
        if (!batch.isEmpty()) queue.publish("notification-push-low", new BatchMsg(c.id(), batch));
    }
}
```

### Why put campaigns on the `-low` priority topic

This way a 10M-user marketing blast can't clog the pipe that carries **OTPs and payment alerts**. A promo landing 3 minutes late is fine; an OTP landing 3 minutes late is useless. Separate topics + separate worker pools mean the campaign flood drains on its own lane while critical notifications sail past ([§16](#16-priority-queues), [§21](#21-kafka-lag)).

### Is this the "celebrity / hot key" problem too?

Related but different. Campaign fan-out is *known and plannable* (you decide to blast 1M users, so you pace it). The celebrity problem is when **one entity** (a superstar's post) triggers fan-out to millions of *followers* unpredictably — same "spread the work + rate limit" toolkit, just triggered by user behavior instead of a scheduled campaign. See the linked [Fan-Out / Fan-In](../concepts/fan-out-fan-in.md) note.

---

## 12. Retry, Backoff & Dead Letter Queue

Delivery fails often — provider down, timeout, invalid token, rate limit.

### Exponential backoff

| Attempt | Delay |
| --- | --- |
| 1 | Immediate |
| 2 | 1 minute |
| 3 | 5 minutes |
| 4 | 30 minutes |
| 5+ | → **DLQ** |

> **Add jitter.** Fixed delays cause a **thundering herd**: when a provider recovers from an outage, every failed message retries at the same instant and knocks it over again. Randomize each delay, e.g. `delay = base ± rand(0, base * 0.5)` (full/decorrelated jitter).

Implementation options:

| Approach | When to use |
| --- | --- |
| **Kafka retry topics** (preferred at scale) | Worker fails → republish to `notification-push-retry-1m` / `5m` / `30m` → DLQ. Kafka drives timing; DB stores status only. |
| **SQS** | Visibility timeout + redrive policy to DLQ |
| **DB polling** (simple, small scale) | Scheduler: `SELECT * FROM notification_attempts WHERE status='RETRYING' AND next_retry_at <= now()` |

> At scale, **avoid constant DB scans for retries**. Use Kafka retry topics; DB is the audit trail, not the retry scheduler.

### Dead Letter Queue (DLQ)

Messages that exceed max retries go to DLQ for:

- Manual inspection
- Reprocessing after provider fix
- Alerting on-call

```
notification-sms-normal → (fail 5x) → notification-sms-dlq
```

> **Don't retry forever** — OTP from 30 minutes ago is useless. High-priority types may have shorter TTL.

### Provider fallback

For SMS/email, configure primary + secondary provider:

```
Twilio fails → try AWS SNS
SendGrid fails → try AWS SES
```

Log which provider succeeded in `notification_attempts`.

### Provider rate limits & backoff ceilings

Each provider caps how fast you may call it. Exceed it and you get `429 / throttled` — so worker concurrency and backoff must respect these, not just retry blindly:

| Provider (channel) | Rough throughput cap | On hit (`429`/quota) |
| --- | --- | --- |
| **FCM / APNS** (push) | Very high (100k+/sec with batch sends) | Honor `Retry-After`; exponential backoff; batch device tokens per call |
| **Twilio** (SMS) | Low per sender — ~1 msg/sec on a long code; higher on short code / A2P 10DLC | Backoff + queue; scale by adding sender numbers, not just workers |
| **SES / SendGrid** (email) | Per-account send rate + daily quota (ramps up over time) | Throttle producers to the account rate; `454` throttling → backoff |

> ⚠️ The backoff ceiling isn't just politeness — a genuinely down provider should stop being hammered. Cap retries at the DLQ threshold (§12) and let the **circuit breaker** (§31) open so healthy channels aren't starved of workers.

**Regulatory ceilings (promotional vs transactional):** law caps sends independently of provider limits.

- **Promotional** SMS/email must honor **one-click unsubscribe** (CAN-SPAM / CASL / GDPR) and prior opt-in; India's **TRAI DND** blocks promo SMS to registered numbers and bans marketing outside ~9 AM–9 PM.
- **Transactional** (OTP, receipts, security alerts) are **exempt** from unsubscribe/DND — they may send anytime. This is exactly why `type` maps to preferences (§14) and why the two paths never share a rate-limit bucket. (Full compliance in §25.)

### Inbound provider webhooks (delivery receipts & bounces)

Handing a message to a provider (`SENT`) is **not** proof the user got it. Providers call *back* with the real outcome — wire these inbound webhooks to close the loop:

| Provider signal | Meaning | Action |
| --- | --- | --- |
| **Delivery receipt** (Twilio status callback, FCM/APNS) | Reached the device/handset | `SENT → DELIVERED` on the attempt |
| **Hard bounce** (SES/SendGrid) | Email address invalid | Mark address bad; **suppress** future sends to it |
| **Complaint / spam report** | User flagged as spam | Flip the promo preference off; stop sending |
| **Invalid token** (FCM `Unregistered`) | App uninstalled / token dead | `is_active = false` on the device (§10) |

> These callbacks are **at-least-once too** — dedup them (provider event id) and treat the webhook handler like any other idempotent consumer.

### Why wait longer each time (backoff)?

When a delivery fails, you retry — but **not instantly, and not at a fixed rhythm**. You wait a little, then more, then a lot: 1 min, 5 min, 30 min. This is **exponential backoff**.

The reason: hammering a down provider with instant retries just piles more load on it while it's already struggling, and wastes your own resources. Backing off with growing delays gives it room to recover before you try again.

```java
long backoffMillis(int attempt) {
    long base = (long) (Math.pow(2, attempt) * 1000);   // 2s, 4s, 8s, 16s ...
    // JITTER: add randomness so 10,000 failed messages don't all retry at the SAME instant
    long jitter = ThreadLocalRandom.current().nextLong(base / 2);
    return base + jitter;
}

void handleFailure(Notification n, Channel ch, int attempt) {
    if (attempt >= MAX_RETRIES) {
        deadLetterQueue.send(n, ch);            // give up → DLQ for humans to inspect
        repo.mark(n.id(), ch, Status.FAILED);
        return;
    }
    long delay = backoffMillis(attempt);
    retryTopic.publishAfter(delay, new QueueMsg(n.id(), ch));   // try again later
    repo.mark(n.id(), ch, Status.RETRYING);
}
```

### What "jitter" is, and why fixed backoff causes a "thundering herd"

If a provider goes down, thousands of messages fail at once. With a *fixed* 1-minute retry, all of them retry at **exactly** the same second the minute later — a synchronized stampede that knocks the just-recovering provider straight back down. **Jitter** = adding a random wobble to each delay so retries **spread out** over time instead of firing in one spike.

### What a Dead Letter Queue (DLQ) really is

It's a separate queue for messages that couldn't be delivered. After N failed attempts, you stop retrying and park the message there, where a human (or an alert) can look: *bad payload? provider permanently rejecting? bug?* You do **not** retry forever — an OTP from 30 minutes ago is worthless, so old messages should die, not loop. DLQ = "we couldn't deliver this; someone please look," not "we'll keep trying to infinity."

### Provider fallback vs retry

- **Retry** = try the **same** provider again later (maybe it was a blip).
- **Fallback** = try a **different** provider (Twilio down → send via AWS SNS). 

Use fallback when one vendor is having a bad day but the message still matters *right now* (OTP). Log which provider finally worked in `notification_attempts` so you can see who's flaky.

---

## 13. Idempotency & Deduplication

**Problem:** Kafka delivers **at-least-once**. Same event consumed twice → duplicate "Order confirmed" push.

### Two stages — don't confuse them

| Stage | What it does | Protected by |
| --- | --- | --- |
| **Stage 1: Create notification** | Insert one row per business event | `notification_key` + `UNIQUE` constraint |
| **Stage 2: Deliver notification** | Worker sends via FCM/email/SMS; may retry | `notification_id` + `notification_attempts` |

> ⚠️ These are **two different guards** — don't collapse them. The `UNIQUE` index does **not** stop double-*sending* (retries reuse the same row), and the attempt-status check does **not** stop duplicate *rows* (that's the unique key's job). You need **both**.

```
Unique index  =  create once
Retry logic   =  send many times on the same notification_id
```

The unique index prevents **duplicate notification records**. It does **not** block delivery retries — those happen on the existing row.

### Idempotency key

```
notification_key = user_id + event_type + event_id
Example:           123:ORDER_CONFIRMED:order_789
```

```sql
CREATE UNIQUE INDEX uniq_notification_key
    ON notifications (notification_key);
```

### Happy path

```
1. Event received → INSERT notifications (status=PENDING) → succeeds
2. Enqueue { notificationId: 1, channel: "PUSH" } to Kafka
3. Worker delivers → UPDATE attempt SENT, notification SENT
```

### Delivery fails — how retry works (no re-insert)

```
1. INSERT already succeeded:
   notification_id=1, notification_key='123:ORDER_CONFIRMED:order_789', status=PENDING

2. Push worker tries FCM → timeout

3. Worker updates SAME row (no new insert):
   notifications.status = RETRYING, next_retry_at = now() + 1min
   notification_attempts: attempt_count=1, status=FAILED

4. Retry via Kafka retry topic OR scheduler picks next_retry_at

5. Worker retries notification_id=1 → success → status=SENT
```

> Retries are keyed on **`notification_id`**, not on creating a new notification row.

### Duplicate event arrives again

Same `ORDER_CONFIRMED` for `order_789` is replayed from Kafka:

```
INSERT ... notification_key='123:ORDER_CONFIRMED:order_789'
→ duplicate key error
```

Handle gracefully — fetch existing row and check status:

| Existing status | Action |
| --- | --- |
| `SENT` | Do nothing — already delivered |
| `PENDING` | Do nothing — already queued (or ensure job exists) |
| `RETRYING` | Do nothing — retry worker will handle it |
| `FAILED` | Optional manual replay or alert; do not auto re-insert |
| `PROCESSING` | Do nothing — worker in flight |

### Queue payload — keep it small

Prefer referencing the DB row, not duplicating full message body:

```json
{
  "notificationId": 1,
  "channel": "PUSH"
}
```

Worker fetches notification + attempt from DB, then calls provider.

> See also: [Idempotency](../concepts/idempotency.md) for key storage, TTL, and concurrency patterns.

**Worker-level dedup:** Before calling FCM, check if `notification_attempts.status == SENT` for this `(notification_id, channel)` — handles duplicate Kafka messages.

### "Idempotent" = doing it twice is harmless

**Idempotent** means: *an operation you can repeat and the result is the same as doing it once.* Sending a push is **not** naturally idempotent (send it twice → user sees it twice), so we *make* it idempotent by remembering "already handled this event."

The remembering is done by the **idempotency key** = a fingerprint of the event:

```
notification_key = user_id + type + event_id     →  "123:ORDER_CONFIRMED:order_789"
```

Because that key has a `UNIQUE` constraint in the DB, the **second** attempt to insert it simply fails — and that failure is *good news*: it means "already exists, don't send again."

```java
void onEvent(OrderConfirmed e) {
    String key = e.userId() + ":ORDER_CONFIRMED:order_" + e.orderId();
    try {
        repo.insert(key, /* status */ "PENDING");   // UNIQUE(notification_key) guards this
        enqueue(key);                                // first time → proceed normally
    } catch (DuplicateKeyException dup) {
        // second delivery of the SAME event → we've been here before
        var existing = repo.findByKey(key);
        // decide based on where it already is (SENT? PENDING? RETRYING?) — usually do nothing
        log.info("duplicate event {}, existing status={}", key, existing.status());
    }
}
```

#### Q: There are "two stages" — why isn't one dedup enough?

Two *different* duplicate problems exist, and each needs its own guard:

| Stage | The duplicate risk | The guard |
| --- | --- | --- |
| **1. Creating the notification** | Same **event** consumed twice (Kafka is at-least-once) → two "order confirmed" rows | `notification_key` UNIQUE constraint → create **once** |
| **2. Delivering the notification** | Same **queue message** processed twice, or a legit retry | Check `attempt.status == SENT` for this `(notification_id, channel)` → send **once** |

Put simply: stage 1 prevents **duplicate notification records** for one event; stage 2 prevents **double-sending** even while retrying the one record. The unique index guards **creation**; it does **not** block delivery retries — those happen on the existing row.

### Why the queue message carries only `{notificationId, channel}` and not the full text

Three reasons: (1) **small messages** = faster queue, cheaper storage; (2) the worker re-reads the **current** DB row, so if something was corrected, it sends the fixed version; (3) it keeps the body (which may contain PII) out of the queue. The worker "hydrates" the full notification from the DB just before sending.

### Isn't "at-least-once + dedup" just a clumsy "exactly-once"?

Practically, yes — and it's the sane choice. True exactly-once across a queue + DB + third-party provider is very hard and usually overkill. "At-least-once delivery + idempotent handling" gives you the same *observable* result (user sees it once) with far less complexity. That's why re-enqueuing a stuck row ([§23](#23-failure-scenarios--mitigations)) is safe: worst case the worker sees `SENT` and skips.

---

## 14. Rate Limiting & User Preferences

### Rate limiting (anti-spam)

Rules examples:

| Rule | Limit |
| --- | --- |
| Promotional push | Max 5 per user per day |
| SMS per order event | Max 1 |
| Marketing messages | No sends 10 PM – 8 AM (quiet hours) |

**Redis counters:**

```
Key:   notif_count:user_123:PROMOTION:2026-07-07
Value: 3
TTL:   end of day
```

Before enqueue:

```
if count >= limit → skip (log as RATE_LIMITED, don't send)
```

> See also: [Rate Limiting](../concepts/rate-limiting.md).

### Preferences vs rate limits

| Mechanism | Purpose |
| --- | --- |
| **Preferences** | User opted out of marketing email |
| **Rate limits** | User opted in but system caps volume |
| **Quiet hours** | Legal/compliance + UX |

### Preferences vs rate limits — two different "no"s

These sound similar but say different things:

- **Preference** = *"I don't want this at all."* (User turned off marketing email.) A hard, user-chosen **opt-out**.
- **Rate limit** = *"I'm fine with this, but not 50 times a day."* The system **caps volume** even for things you *do* want, so it doesn't become spam.
- **Quiet hours** = *"not while I'm asleep."* A time-based cap, often required by **law** for marketing.

All three are independent gates: preferences can reject a type/channel outright, rate limits can reject once a per-period cap is hit, and quiet hours can reject based on the time of day. Any one of them can say "no."

```java
boolean shouldSend(long userId, String type, Channel ch, Instant now) {
    // 1. PREFERENCE gate — did the user opt out of this type on this channel?
    if (!prefs.isEnabled(userId, type, ch)) return false;

    // 2. QUIET HOURS gate — is it night for this user, and is this promotional?
    if (isPromotional(type) && quietHours.contains(userId, now)) return false;

    // 3. RATE LIMIT gate — Redis counter with a per-day TTL
    String key = "notif_count:" + userId + ":" + type + ":" + today(now);
    long count = redis.incr(key);                 // atomically bump + read
    if (count == 1) redis.expireAtEndOfDay(key);  // first hit today → set TTL
    if (count > limitFor(type)) {
        repo.mark(userId, type, Status.RATE_LIMITED);  // record why we skipped
        return false;
    }
    return true;   // passed all three gates → safe to send
}
```

### Why use a Redis counter instead of counting rows in the DB

This check runs **before every single send** at peak thousands/sec. Counting `notifications` rows each time would hammer the DB. Redis `INCR` is a single in-memory atomic operation (O(1)), and the key auto-expires at end of day so you don't accumulate junk. It's a scoreboard, not a ledger.

### Do OTPs and receipts respect these limits?

**No** — and this is critical. **Transactional** messages (OTP, payment failed, password reset) *bypass* marketing rate limits and quiet hours, because the user needs them regardless of the hour or the daily cap. Only **promotional** messages are gated. This is exactly why the `type → preference` mapping exists: it lets the system treat "your OTP" and "50% off shoes!" completely differently. (Legal note in [§25](#25-security-pii--compliance).)

---

## 15. Template System

**Never hardcode message text** in application code.

Template:

```
Hi {{name}}, your order {{orderId}} has been confirmed for ₹{{amount}}.
```

Data:

```json
{ "name": "Hardik", "orderId": 789, "amount": 1500 }
```

Rendered:

```
Hi Hardik, your order 789 has been confirmed for ₹1500.
```

| Concern | Approach |
| --- | --- |
| Multi-language | `(type, channel, language)` lookup |
| Rich email | HTML templates stored in S3 or DB |
| Push char limits | Truncate body in template or worker |
| **Versioning** | New copy → **new version row**; never edit v1 in place |
| **Audit trail** | Store `template_version` + rendered `title`/`body` on `notifications` row |

### Template versioning (production pattern)

When message copy changes, **insert a new version** — do not overwrite the old template:

```
ORDER_CONFIRMED + PUSH + en + version 1  →  "Order {{orderId}} confirmed"
ORDER_CONFIRMED + PUSH + en + version 2  →  "Hi {{name}}, order {{orderId}} confirmed!"
```

At send time:

1. Load latest **active** version (`is_active = true`) for new notifications
2. Render and store snapshot on the notification row:

```
notifications.template_type    = ORDER_CONFIRMED
notifications.template_version = 1
notifications.title            = "Order 789 confirmed"   ← rendered
notifications.body             = ...
```

Old notifications still reference `template_version = 1` — support and compliance can always answer *"what exact text was sent?"*

Campaigns can **pin** a specific version so a running campaign doesn't pick up mid-flight copy changes.

### Template identity — `type` vs `template_id`

Keep two ideas separate:

| Concept | Meaning | Changes over time? |
| --- | --- | --- |
| `type` (a.k.a. `event_type`) | **Why** the notification is sent — `ORDER_CONFIRMED` | **No** — stable forever |
| `template_id` | The **exact template row** that produced the text | **Yes** — new copy → new row |
| `is_active` | Whether this row is used for **new** sends | Flips old → false, new → true |

```
type       = ORDER_CONFIRMED     (stable — the business event)
template_id = uuid-1  is_active=false   ← old copy, kept for audit
template_id = uuid-2  is_active=true    ← current copy for new sends
```

### Surrogate key: `BIGINT` vs `UUID`

`template_id` can be a `BIGINT` (auto-increment) or a `UUID`. UUID is a clean choice because it is globally unique and lets templates be created/imported across environments without id collisions. Avoid **encoding meaning into the key** (e.g. `ORDER_CONFIRMED_PUSH_002`) — put the meaning in real columns (`type`, `channel`, `version`) instead:

```sql
CREATE TABLE notification_templates (
    template_id     UUID PRIMARY KEY,          -- opaque, no meaning encoded
    type            VARCHAR(100) NOT NULL,     -- ORDER_CONFIRMED
    channel         VARCHAR(50)  NOT NULL,     -- PUSH / EMAIL / SMS
    language        VARCHAR(20)  DEFAULT 'en',
    version         INT NOT NULL DEFAULT 1,
    title_template  TEXT,
    body_template   TEXT NOT NULL,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);
```

### Referencing the template from a notification: version vs id

Two equally valid ways to record *"what was sent"* on the `notifications` row:

| Approach | Store on notification | Trade-off |
| --- | --- | --- |
| **Version snapshot** | `template_type` + `template_version` | Human-readable; needs `(type, channel, language, version)` to locate the row |
| **Direct FK** | `template_id` (UUID) | One-hop lookup to the exact row; add a foreign key if same DB |

```sql
ALTER TABLE notifications
    ADD CONSTRAINT fk_notifications_template
    FOREIGN KEY (template_id) REFERENCES notification_templates(template_id);
```

Either way, **also store the rendered `title`/`body`** on the notification. The FK/version tells you *which* template; the snapshot tells you the *exact text* even if the template row is later edited or deleted.

### Enforce a single active template

There must be exactly **one** active template per `(type, channel, language)` — otherwise the render step can't decide which copy to use. Enforce it in the DB so it's impossible to get two live rows:

```sql
CREATE UNIQUE INDEX uniq_active_template
    ON notification_templates (type, channel, language)
    WHERE is_active = TRUE;
```

Publishing a new version becomes a small transaction: flip the old row to `is_active = false`, insert the new row with `is_active = true`.

### Turning a template + data into the final text

Rendering, mechanically, is just find-and-replace on the `{{placeholders}}` shown at the top of this section. The whole point: **never hardcode message text in code.** If the marketing team wants to reword "Order confirmed!" to "Woohoo, your order's in!", that should be a data change (a new template row), not a code deploy.

```java
String render(String template, Map<String, Object> data) {
    String out = template;
    for (var e : data.entrySet()) {
        out = out.replace("{{" + e.getKey() + "}}", String.valueOf(e.getValue()));
    }
    return out;   // "Hi {{name}}..." + {name:"Hardik"} → "Hi Hardik..."
}

// picking WHICH template: type + channel + language, latest active version
Template pick(String type, Channel ch, String lang) {
    return templateRepo.findActive(type, ch, lang);   // is_active = TRUE row
}
```

> ⚠️ **Never send a blank or failed-render body.** If the template is missing, or `data` leaves a placeholder unfilled (user sees literal `Hi {{name}}` or an empty push), **fail fast** — don't deliver garbage. Validate that all placeholders resolved and the body is non-empty *before* enqueue; on failure, mark the notification `FAILED`, alert, and DLQ it (§23) rather than pushing an empty/broken message the user can't un-see.

### Why version templates instead of just editing the text

Old notifications must still show what they *actually said*. If you overwrite v1's text and a customer disputes *"you told me ₹1500!"*, you've lost the evidence. So: **new copy = new version row**; the old one stays with `is_active = false` for audit.

### What "exactly one active template" prevents

If two rows for `(ORDER_CONFIRMED, PUSH, en)` were both `is_active = true`, the render step wouldn't know which wording to use — a coin flip. The partial unique index makes that state **impossible** in the database. Publishing a new version is then a tiny transaction: flip old → inactive, insert new → active. No ambiguous "which one is live?" moment.

### `type` vs `template_id` — the difference

- `type` (`ORDER_CONFIRMED`) = **why** the notification is sent. Stable forever — it's the business event.
- `template_id` = **which exact wording row** produced the text. Changes every time copy changes.

`type` is the *reason* the notification exists ("order confirmation") and never changes; `template_id` is the *specific wording row* used to render it, which changes each time the copy is updated.

---

## 16. Priority Queues

Not all notifications are equal.

| Priority | Examples | SLA |
| --- | --- | --- |
| **High** | OTP, payment failed, security alert | Seconds |
| **Normal** | Order confirmed, delivery update | Minutes |
| **Low** | Marketing, recommendations | Best effort |

```
notification-push-high     ← dedicated workers, more replicas
notification-push-normal
notification-push-low      ← throttled, deprioritized under load
```

Under backlog, **high-priority queue drains first**. Low-priority can be dropped or delayed (with product approval).

### An OTP and a coupon are not equals

Some notifications are **urgent** (OTP — useless in 2 minutes), some can wait (a "recommended for you" nudge). If they share one queue, a giant marketing blast can make your OTPs arrive late — a disaster. So we separate messages into **priority lanes**: high-priority messages get their own topics and worker pools so they aren't stuck behind a flood of low-priority ones.

Concretely: **separate topics per priority**, and give the high-priority topic **more workers**, so it always drains first.

```java
String topicFor(Channel ch, Priority p) {
    return "notification-" + ch.name().toLowerCase() + "-" + p.name().toLowerCase();
    // OTP  → "notification-sms-high"   (many dedicated workers, drains first)
    // promo→ "notification-push-low"   (throttled, can wait under load)
}

// High-priority pool gets more replicas and consumes ONLY the high topic:
@KafkaListener(topics = "notification-sms-high", concurrency = "50")
public void deliverCritical(QueueMsg m) { /* OTPs, payment alerts */ }

@KafkaListener(topics = "notification-push-low", concurrency = "5")
public void deliverMarketing(QueueMsg m) { /* deprioritized under load */ }
```

### Why not just one queue with a "priority" field and sort it

A single queue (especially Kafka) is basically **first-in-first-out per partition** — it doesn't re-sort by importance. If 10M low-priority messages went in *before* your OTP, the OTP waits behind all of them. Physically separate topics + separate worker pools are how you guarantee the fast lane is actually fast. The priority field in the API request is what *routes* the message to the right topic.

### Can low-priority ever be dropped?

Yes — **with product approval**. Under severe overload, delaying or even dropping "recommended for you" pings is acceptable; dropping OTPs never is. Making priority explicit is what lets you make that trade-off safely instead of dropping messages at random.

---

## 17. Scheduled Notifications

Example: BookMyShow — "Your movie starts in 1 hour."

```
1. Create scheduled_notifications row (scheduled_at = show_time - 1hr)
2. Scheduler job runs every minute:
     SELECT * FROM scheduled_notifications
     WHERE status = 'PENDING' AND scheduled_at <= now()
3. For each row → create notification + enqueue to Kafka
4. Mark status = DISPATCHED
```

**Scheduler options:**

| Option | Trade-off |
| --- | --- |
| Cron + DB poll | Simple; DB load at scale |
| Kafka scheduled messages / delayed queues | Built-in delay |
| Dedicated scheduler (Temporal, Quartz) | Reliable, visible |

**Cancellation:** User cancels booking → update `scheduled_notifications.status = CANCELLED`.

### Scheduled notifications

A **scheduled notification** is just one you want sent **later**, not now: *"remind me 1 hour before the movie."* You can't hold it in memory for hours (the server might restart), so you **write it down with a due time** and have a job that wakes up regularly and asks *"anything due yet?"*

```java
// The saved intent: "send this at scheduled_at"
// scheduled_notifications: { id, userId, type, scheduled_at, status=PENDING, payload }

@Scheduled(fixedRate = 60_000)   // wake up once a minute
public void dispatchDue() {
    // "anything due AND not yet handled?"
    List<Scheduled> due = repo.findDue(Instant.now());   // WHERE status='PENDING' AND scheduled_at <= now()

    for (Scheduled s : due) {
        createNotificationAndEnqueue(s);   // now it enters the NORMAL pipeline
        repo.mark(s.id(), Status.DISPATCHED);   // so the next tick won't send it again
    }
}
```

### Why a `DISPATCHED` status — isn't that extra bookkeeping?

It stops **double-sending**. The poller runs every minute; without marking rows `DISPATCHED`, the same due row would be picked up again next minute and the user gets the reminder twice. Flipping to `DISPATCHED` is the "I've handled this one" checkmark — the same idea as idempotency, applied to time.

### DB polling vs a "delayed queue" — which is better

- **Cron + DB poll** (query due rows every minute) — dead simple, great for modest scale; the cost is a repeated query on a big table.
- **Delayed queue / scheduler** (Kafka delayed messages, Temporal, Quartz) — you hand the message a "deliver after X" and the infra holds it; more precise and less DB load, but more moving parts.

Rule of thumb: start with the poll (with an index on `(scheduled_at) WHERE status='PENDING'`); graduate to a dedicated scheduler when the poll gets heavy or you need second-level precision.

### What happens if the user cancels (movie booking cancelled)

Flip `scheduled_notifications.status = CANCELLED`. When the poller runs, that row no longer matches "PENDING and due," so it's silently skipped — no reminder for an event that isn't happening. Because the reminder was *written down* rather than already sent, cancelling is trivial.

---

## 18. Real-Time Notifications (WebSocket / SSE)

For live in-app updates (chat, live scores, delivery map):

```
Message Service → Kafka → WebSocket Service → Connected Clients
```

**Connection mapping (Redis):**

```
Key:   ws:user:123
Value: { connectionId: "abc", serverId: "ws-node-2" }
TTL:   refreshed on heartbeat
```

When in-app worker creates notification:

1. Write to DB
2. Publish `{ userId, notification }` to internal topic
3. WebSocket service looks up connection → push to client

| Protocol | When to use |
| --- | --- |
| **WebSocket** | Bidirectional (chat) |
| **SSE** | Server → client only, simpler |
| **Push** | App in background |

> WebSocket service is **stateful** — sticky sessions or shared Redis for routing. Scale by user affinity.

### WebSocket vs one-shot push/email

Normal push/email is one-shot: you send and forget. A **WebSocket** keeps a connection open between the app and the server: as long as the user is on the app, the server can push instantly ("new message!") and the app receives it with zero delay. **SSE** is the same but one-way (server → client only) — simpler, good for live scores or a delivery map.

The catch: to push over that connection, the server must **know which node holds the user's connection**. With thousands of WebSocket servers, user 123's live connection lives on exactly one of them. So we keep a mapping in Redis: *"user 123 → connected to server `ws-node-2`."*

```java
// Connection mapping, refreshed on heartbeat so dead connections expire:
//   Redis:  ws:user:123  →  { connectionId: "abc", serverId: "ws-node-2" }

void onInAppCreated(long userId, Notification n) {
    var loc = redis.get("ws:user:" + userId);      // where is this user connected?
    if (loc == null) return;                        // offline → they'll see it on next fetch
    // route the push to the RIGHT ws server, which owns that live socket
    internalBus.sendTo(loc.serverId(), new WsPush(loc.connectionId(), n));
}
```

### Why the WebSocket service is "stateful," and why that matters

It *holds live connections in memory* — user 123's socket physically lives on one machine. That's different from stateless API servers where any server can handle any request. Consequences: you need **sticky sessions** (route a user back to the same server) or a **shared Redis phonebook** to find them, and you scale by **user affinity** (spread users across servers), not by a plain load balancer. A restart of that server drops those sockets (clients reconnect).

### WebSocket vs push — when to use which

- **App open / foreground** → WebSocket or SSE for instant, in-app freshness (the bell updates live).
- **App closed / background** → **push** (FCM/APNS), because there's no open line to the app; only the OS can wake it.

They complement each other: WebSocket for "you're here right now," push for "you're away." In-app delivery itself is still the DB row ([§9](#9-in-app-notifications)) — WebSocket is just the instant nudge on top.

---

## 19. Scaling the System

### Scale API layer

- Stateless Notification API → horizontal scaling behind load balancer
- Read preferences/templates from cache (Redis)

### Scale workers independently

```
Push workers:   100 instances  (high volume)
Email workers:   20 instances  (provider rate limits)
SMS workers:     10 instances  (cost + limits)
In-app workers:  30 instances  (DB writes)
```

### Kafka partitioning

- Partition key = `user_id`
- More partitions → more parallel consumers
- Rule of thumb: partitions ≥ max consumer count per group

### Database

- **Write path:** notifications insert-heavy → partition by `created_at` (monthly tables) or shard by `user_id`
- **Read path:** in-app feed → index `(user_id, created_at DESC)`
- **Archive:** move notifications older than 90 days to cold storage (S3 / archive DB)

### Caching

| Data | Cache |
| --- | --- |
| User preferences | Redis, TTL 5–15 min, invalidate on update |
| Templates | Redis or in-memory (rarely change) |
| Device tokens | Redis for hot users, DB as source of truth |

### Scale each part where it actually hurts

"Scaling" isn't one knob — it's finding **which specific part is the bottleneck** and widening *that* part. If the API layer is saturated, add API servers; if parallelism is capped, add Kafka partitions; if delivery is the bottleneck, add workers. Widening the wrong part won't help.

The three independent dials:

```java
// 1. API layer — stateless → just run more copies behind a load balancer.
//    (Nothing is stored in the API; any server handles any request.)

// 2. Workers — scale EACH channel to its own need, because volumes differ wildly:
//    push  = 100 pods (huge volume)
//    email =  20 pods (slower providers)
//    sms   =  10 pods (expensive + provider rate limits)

// 3. Kafka — parallelism is capped by PARTITION COUNT:
int maxParallelWorkers = topic.partitionCount();   // 100 partitions → at most 100 useful workers
// adding a 101st worker to a 100-partition topic → it just sits idle
```

> ⚠️ **Partition count is a hard ceiling on parallelism, and painful to change later** — reducing partitions is effectively impossible, and increasing them re-shuffles which `user_id` lands where (breaking per-user ordering for in-flight keys). **Size partitions for peak up front**, not for today's load.

#### Q: Why can't I just add unlimited workers to go faster?

Because **a Kafka partition is read by only one consumer in a group at a time.** If a topic has 100 partitions, the 101st worker has no partition to own — it idles. So the *ceiling* on parallelism is the partition count. Plan partitions ahead (they're painful to reduce later). This is the single most common "why isn't adding workers helping?" gotcha ([§21](#21-kafka-lag)).

### Why cache preferences and templates instead of hitting the DB

They're read **constantly** but change **rarely**. Every notification needs "what are user 123's prefs?" and "what's the ORDER_CONFIRMED template?" — reading those from the DB millions of times/day is wasteful. Cache them in Redis (prefs with a short TTL + invalidate on update; templates practically forever).

### Stateless vs stateful — why it matters for scaling

**Stateless** things (the API) hold no per-user memory, so you can clone them freely and any copy serves any request — trivial to scale. **Stateful** things (the WebSocket service, [§18](#18-real-time-notifications-websocket--sse)) remember something per user (a live connection), so you must route each user consistently. The rule: **push state out to shared stores (DB/Redis/Kafka) so your compute stays stateless and cloneable.**

### Multi-region (deferred — but know the shape)

Multi-region active-active is out of scope ([§2](#2-requirements)), but mention the shape so it's clearly a *deferral*, not a blind spot: you'd run a **full pipeline per region** (regional Kafka + workers + DB), route each event to the user's **home region** (for latency and data-residency laws like GDPR), and **replicate the read-mostly data** (preferences, templates) across regions while keeping the write-heavy `notifications`/`attempts` regional. In-app read models (unread counters) stay regional. The genuinely hard parts — cross-region preference/token consistency and clean failover without double-sending — are why it's deferred: the dedup guarantee ([§13](#13-idempotency--deduplication)) must still hold across regions, which usually means pinning a user (and their `notification_key` space) to one region at a time.

---

## 20. Worker Deployment & Service Ownership

### Worker deployment models

Workers are usually **separate processes** from the API — not inside the same request path.

| Model | Layout | Good for | Problems |
| --- | --- | --- | --- |
| **Same instance** | API + worker on one EC2 | Small projects, prototypes | API load slows workers; can't scale independently |
| **Same codebase, separate process** | `node api.js` vs `node worker.js` on different EC2 | Most startups | Shared repo, independent deploy/scale |
| **Separate deployments** (best) | API deployment + push-worker + email-worker + sms-worker | Production at scale | More ops overhead; best isolation |

```
Production (Kubernetes example):

notification-api-deployment     → 3 pods
push-worker-deployment          → 100 pods
email-worker-deployment         → 20 pods
sms-worker-deployment           → 5 pods
```

**Mental model:** API = accepts work. Workers = performs async work. Kafka = buffer between them.

### EC2 boundary ≠ service boundary

Workers on **different EC2 instances** can still belong to the **same logical service** and share the same DB:

```
Notification Service (one ownership domain)
  ├── API process      → EC2-A
  ├── Push worker      → EC2-B
  ├── Email worker     → EC2-C
  └── notification_db  ← owned by Notification Service
```

Push worker fails → updates `notification_db`:

```sql
UPDATE notifications SET status = 'RETRYING', next_retry_at = now() + interval '5 minutes'
WHERE notification_id = 123;
```

That is **correct** — worker and DB are same service.

### Service ownership — who can touch which DB?

| Service | Owns | Can update |
| --- | --- | --- |
| **Order Service** | `order_db` | order rows only |
| **Notification Service** | `notification_db` | notification status, attempts |
| **Payment Service** | `payment_db` | payment rows only |

```
Notification Worker → notification_db  ✅
Notification Worker → order_db         ❌  (violates microservice boundaries)
```

If Order Service needs delivery status, prefer:

- **Option A:** Notification Service API — `GET /notifications/{id}`
- **Option B:** Publish event — `NOTIFICATION_DELIVERY_FAILED` to Kafka; Order consumes if needed
- **Option C (usual best):** Order Service doesn't track per-channel delivery — Notification Service owns that entirely

Order Service says *"please notify user"*. Notification Service owns `PENDING → SENT → FAILED → RETRYING`.

### "Worker" is a role, not a place

Beginners picture the API and workers as one program. They're not — a **worker** is a **separate long-running process** whose only job is to pull messages off a queue and deliver them. The API accepts requests and enqueues them; the workers consume the queue and deliver. Same service, different processes (often on different machines).

```
Same service (Notification Service), different processes:
  node api.js      → accepts requests (HTTP), enqueues    → EC2-A / api pods
  node pushWorker  → drains push topic, calls FCM         → EC2-B / push pods
  node emailWorker → drains email topic, calls SendGrid   → EC2-C / email pods
  ↑ all share the SAME notification_db (one ownership domain)
```

### "EC2 boundary ≠ service boundary" — what this means

Running on **different machines** does *not* make them different services. The push worker on EC2-B and the API on EC2-A are both the **Notification Service** — they share one DB and one ownership domain. A "service" is about *who owns the data and logic*, not *how many boxes it runs on*. So the push worker updating `notification_db` is completely correct; it's the same service touching its own data.

### Can a worker write to another service's database?

**No.** The push worker must never `UPDATE order_db` directly — that violates microservice boundaries and creates hidden coupling (change Order's schema, break Notifications). If the Order Service wants delivery status, it should **ask** (call Notification's API) or **listen** (consume a `NOTIFICATION_DELIVERY_FAILED` event) — never reach into the other's tables.

```java
// ✅ each service owns and writes ONLY its own DB
notificationWorker.update(notificationDb, ...);   // fine — same service

// ❌ crossing the boundary — forbidden
notificationWorker.update(orderDb, ...);          // Notification touching Order's tables

// ✅ if Order needs to know: it consumes an event Notification publishes
orderService.onEvent(NotificationDeliveryFailed e) { ... }
```

### Same codebase or separate deployments — which is right

A spectrum: (1) API + worker in **one process** (prototypes only — API load starves workers); (2) **same codebase, separate processes** (`api.js` vs `worker.js`) — most startups, shared repo but independent scaling; (3) **separate deployments** per channel — best isolation, most ops. Pick based on scale; the key property to preserve is that workers can **scale and fail independently** of the API.

---

## 21. Kafka Lag

**Kafka lag** = messages produced faster than consumers process them.

```
Latest offset in partition  = 100,000
Consumer group offset       =  95,000
Lag                         =   5,000 messages waiting
```

Users experience this as **delayed notifications** — fine for marketing, dangerous for OTP.

### Why lag happens

- Too few workers
- Slow providers (SMS 2s/message → ~30/min per worker)
- Slow DB updates in worker hot path
- Too few Kafka partitions (max parallel consumers = partition count)
- Hot partition (one `user_id` key getting all traffic — rare)
- Poison messages retrying forever
- Campaign spike (10M users instantly)

### Is lag always bad?

| Lag type | OK? |
| --- | --- |
| Temporary spike, catches up in 2 min | Yes |
| Continuously growing lag | No — need more capacity |
| High-priority (OTP) lag | Never acceptable |
| Low-priority (marketing) lag | Often tolerable |

### How to handle lag

| Mitigation | Detail |
| --- | --- |
| **Scale consumers** | More worker pods — but capped by partition count |
| **Increase partitions** | e.g. 10 → 100 on `notification-push`; plan ahead (hard to reduce) |
| **Separate topics by priority** | OTP on `notification-critical`; marketing on `notification-low` — campaign lag won't block OTP |
| **Separate worker pools** | Critical workers only consume critical topic |
| **Rate-limit producers** | Campaign sends 50k/min, not 10M instantly |
| **Batch processing** | Consume 100 messages; batch DB updates |
| **Optimize slow path** | Provider latency, DB writes, template rendering |
| **DLQ poison messages** | Invalid payload retrying forever blocks progress |
| **Monitor oldest message age** | 10k lag with 30s age ≠ 10k lag with 30min age |

```
Campaign Service  →  notification-low   →  Marketing workers  (lag OK-ish)
Order Service     →  notification-high  →  OTP/Push workers   (must stay low)
```

### Interview one-liner

> "I'd monitor consumer lag and oldest-message age, scale consumers up to partition count, separate critical and marketing topics with dedicated worker pools, throttle campaign producers, batch worker DB writes, and DLQ poison messages."

### Lag = the queue growing faster than you drain it

**Kafka lag** is simply *how many messages are waiting that you haven't processed yet.* Producers are adding messages faster than consumers remove them, so the backlog grows.

Two numbers describe the pain: **how many messages are waiting** (lag count) and **how long the oldest waiting message has sat** (oldest-message age). The second one is often what actually matters.

```java
long lag = latestOffset - consumerOffset;   // 100,000 - 95,000 = 5,000 waiting
// but ALSO watch: how old is the oldest un-processed message?
//   5,000 lag that clears in 30s  = fine
//   5,000 lag sitting for 30 min  = an OTP disaster
```

The "Is lag always bad?" table above is exactly why OTPs and marketing get **separate topics** ([§16](#16-priority-queues)) — a campaign flood can lag on its own lane without delaying a login code.

#### Q: What's a "poison message" and why can it block everything?

A **poison message** is one that always fails (malformed payload, a bug in handling it). If the worker keeps retrying it forever, it can jam the partition — everyone behind it waits on a message that will *never* succeed. Fix: after N failures, shove it to the **DLQ** and move on. Don't let one bad apple stall the whole line.

### Added workers but lag didn't drop — why

Almost always: **you hit the partition ceiling** (workers > partitions → extras idle, [§19](#19-scaling-the-system)), or the **bottleneck is downstream** (slow provider, slow DB writes) so more workers just pile up waiting on the same slow thing. The fix then isn't more workers — it's more partitions, or speeding up the slow step (batch DB writes, faster provider path).

---

## 22. DB Bottlenecks & Mitigations

Fixing Kafka lag by adding workers can **shift the bottleneck to the DB**.

```
Kafka lag fixed  →  100 workers writing to DB  →  DB overloaded
```

### Where DB pressure comes from

Per notification you may do:

1. `INSERT` into `notifications`
2. `INSERT` into `notification_attempts` (per channel)
3. `UPDATE` status on success/failure
4. Retry polling queries
5. User history reads (`GET /users/{id}/notifications`)

At 1M notifications/min with 3 channels → **millions of writes/minute**.

### Common bottlenecks

| Bottleneck | Cause |
| --- | --- |
| **Write volume** | Too many inserts/updates per notification |
| **Index maintenance** | Every insert updates `uniq_notification_key` + other indexes |
| **Hot rows** | Many workers updating same `notification_id` on retry |
| **Retry polling** | `SELECT ... WHERE status='RETRYING'` on huge table without index |
| **Table size** | 300M+ rows → slower queries, heavier backups |
| **Connection overload** | 100 workers × 10 connections = 1000 DB connections |
| **Analytics on primary** | Heavy reports competing with worker writes |

### Mitigations (use several together)

| # | Mitigation | How |
| --- | --- | --- |
| 1 | **Reduce write amplification** | Skip intermediate `PROCESSING` if not needed; `PENDING → SENT` is enough |
| 2 | **Batch DB writes** | `UPDATE ... WHERE notification_id IN (...)` ; bulk insert attempts |
| 3 | **Right indexes only** | `uniq_notification_key`, `idx_notifications_retry`, `idx_notifications_user_created`, `idx_attempts_notification` — don't over-index |
| 4 | **Partition by time** | `notifications_2026_07`, `notifications_2026_08` — hot recent data |
| 5 | **Archive old data** | Last 30 days in primary DB; older → S3 / warehouse |
| 6 | **Kafka retry topics** | Not constant DB polling for retries |
| 7 | **Control worker concurrency** | Max 50 push workers if DB can't take 100; tune connection pools |
| 8 | **Cache reads** | Templates, preferences, device tokens in Redis |
| 9 | **Read replicas** | User history reads off replica; primary for writes |
| 10 | **Separate analytics** | Metrics to warehouse / summary table — not heavy scans on primary |
| 11 | **Shard by `user_id`** | Only when single DB still insufficient after above |

### Dangerous trade-off

```
More workers  →  fixes Kafka lag  →  may overload DB
```

Balance: **worker count ≤ what DB can sustain**. Kafka absorbs spikes; workers are the throttle.

### Interview one-liner

> "DB bottlenecks come from high write volume on notifications and attempts. I'd batch updates, use only necessary indexes, partition by time, archive old rows, drive retries via Kafka topics not DB polling, cache templates and prefs, use read replicas for history, and cap worker concurrency to match DB capacity."

### Fixing one bottleneck can create the next

Here's the twist beginners miss: you add workers to clear Kafka lag ([§21](#21-kafka-lag)) — and now **100 workers all hammer the database at once**. You moved the bottleneck from the queue to the DB.

The DB is now the shared bottleneck: if every worker issues its own tiny write, the DB is overwhelmed. The fixes are all variations of *"send fewer, bigger writes, and keep the working set small."*

```java
// BAD — one DB round-trip per message → millions of tiny writes
for (Result r : results) db.update("UPDATE notifications SET status=? WHERE id=?", r.status, r.id);

// GOOD — batch: one statement for many rows
db.batchUpdate("UPDATE notifications SET status='SENT' WHERE notification_id IN (?)", batchOfIds);

// Also: don't SCAN for retries — let Kafka retry topics carry the timing (§12)
// Also: read user history from a REPLICA, keep the primary for writes
var history = replicaDb.query("... WHERE user_id=? ORDER BY created_at DESC LIMIT 20", userId);
```

The main levers, in plain terms:

- **Batch writes** — group many status updates into one statement (fewer round-trips to the DB).
- **Only the indexes you need** — every extra index is extra work on *every* insert. More isn't better.
- **Partition by time** — keep "this month" small and hot; old months live in their own tables. Queries and backups touch less data.
- **Archive** — move data older than 90 days to cheap storage (S3). The live table stays lean.
- **Read replicas** — serve "show me my notifications" from a copy so it doesn't fight worker writes on the primary.
- **Cap worker concurrency** — deliberately run *fewer* workers than the DB can survive. Kafka happily holds the backlog; workers are the throttle.

#### Q: Why is "more workers" a *dangerous* trade-off, not a free win?

Because throughput is limited by the **slowest** shared resource. More workers fix the *queue* but can drown the *DB* — and an overloaded DB fails *everything* (writes, reads, retries) at once. The healthy target is **worker count ≤ what the DB can sustain**; let Kafka absorb the surge instead of forcing it through the database.

### What "write amplification" means here

One logical notification can trigger *several* DB writes: insert `notifications`, insert an `attempts` row per channel, update status on success/failure, plus retry bookkeeping. Three channels → easily 5+ writes per notification. At 1M/min that's millions of writes/min. "Reduce write amplification" = cut needless steps (e.g. skip a `PROCESSING` status you don't actually use; `PENDING → SENT` is enough).

---

## 23. Failure Scenarios & Mitigations

| Failure | Mitigation |
| --- | --- |
| **SMS provider down** | Retry with backoff → fallback provider → DLQ |
| **Duplicate event** | Idempotency key + unique index |
| **User disabled notifications** | Preference check before enqueue |
| **Expired device token** | Mark inactive on FCM/APNS error; skip silently |
| **Queue backlog** | Autoscale workers; prioritize high queue; throttle low |
| **DB unavailable** | API returns 503; producer retries; Kafka retains messages |
| **Template missing** | Fail fast + alert; don't send blank notification |
| **Campaign sends too fast** | Rate limit per user + provider throughput cap |

### Event publishing reliability (the dual-write problem)

Two boundaries in this system are both **dual-writes**: two things must both happen, but they live in **two systems** with no shared "all-or-nothing" switch, so a DB write and a Kafka publish are **not atomic**:

| Boundary | Failure if naïve | Fix |
| --- | --- | --- |
| **Source → Notification** (Order emits event) | Order committed but event lost → user never notified | **Transactional Outbox** in source: write order + outbox row in one TX; a relay publishes outbox → Kafka |
| **Notification API → Kafka** (insert row, then enqueue) | Row inserted, process crashes before enqueue → stuck in `PENDING`, never delivered | **Outbox** in notification DB *or* a **reconciliation sweeper** (below) |

The second boundary is the nastiest in practice — if the process dies **between** the insert and the publish, the row says `PENDING` but no queue message ever went out, so the notification is silently lost:

```java
// The trap — two systems, one crash gap:
repo.insert(notification);          // ✅ committed to the DB
// 💥 crash here → row stuck PENDING forever, never enqueued
queue.publish(topic, msg);          // ❌ never runs
```

Two standard fixes:

- **Transactional Outbox** — write the notification row **and** an "outbox" row in the **same DB transaction** (so they're atomic). A separate relay process reads the outbox and publishes to Kafka, marking rows done. Now the DB commit *guarantees* the intent to publish survives a crash.
- **Reconciliation sweeper** — a safety-net job that periodically finds rows stuck at `PENDING` for more than a few minutes and re-enqueues them:

```sql
-- Runs every 1–5 min; re-enqueues rows that were persisted but never delivered
SELECT notification_id, ... FROM notifications
WHERE status = 'PENDING' AND created_at < now() - interval '5 minutes'
LIMIT 1000;
-- → re-publish to Kafka (idempotent: worker skips if attempt already SENT)
```

> See: [Outbox & Saga](../concepts/outbox-and-saga.md).

### Why re-enqueuing a maybe-already-sent notification is safe

The whole pipeline is **at-least-once + idempotent** ([§13](#13-idempotency--deduplication)). If the sweeper re-sends a row that actually *did* go out, the worker checks `attempt.status == SENT` for that `(notification_id, channel)` and skips it. Worst case = a redundant no-op, never a duplicate to the user. That safety is exactly what *lets* us use a blunt "just re-enqueue stuck rows" sweeper.

### Outbox vs sweeper — do you need both

The **outbox** *prevents* lost publishes (correct-by-construction, but more plumbing). The **sweeper** *recovers* from them after the fact (simpler, but there's a delay before it catches the stuck row). Many systems start with the sweeper for its simplicity and add an outbox at the source (Order → Notification boundary) where losing the event entirely would be worst.

---

## 24. Observability

| Signal | What to track |
| --- | --- |
| **Metrics** | Send rate by channel, success/failure rate, latency p50/p99, DLQ depth, retry count |
| **Logs** | Structured: `notification_id`, `user_id`, `channel`, `provider`, `error_code` |
| **Traces** | API → enqueue → worker → provider call (OpenTelemetry) |
| **Alerts** | DLQ growth, success rate < 95%, provider error spike, scheduler lag, **Kafka consumer lag**, **oldest message age** |

**Dashboards per channel** — SMS failure looks different from push failure.

### You can't fix what you can't see

**Observability** = being able to answer *"is the system healthy, and if not, where's it broken?"* without SSH-ing into servers and guessing — the metrics, logs, and traces tell you at a glance.

Three kinds of signal, each answering a different question:

| Signal | Answers | Notification example |
| --- | --- | --- |
| **Metrics** (numbers over time) | "How's it doing overall?" | send rate, success %, p99 latency, DLQ depth, Kafka lag |
| **Logs** (events with detail) | "What happened to *this* one?" | `notification_id=5 channel=SMS provider=TWILIO error=timeout` |
| **Traces** (the path of one request) | "Where did the time go?" | API → enqueue → worker → provider, with timing per hop |

```java
// A worker emits all three, cheaply:
Timer.Sample s = Timer.start();
try {
    provider.send(n);
    metrics.counter("notif.sent", "channel", ch).increment();     // METRIC
    log.info("sent id={} channel={} provider={}", n.id(), ch, provider.name()); // LOG (no body! §25)
} catch (Exception e) {
    metrics.counter("notif.failed", "channel", ch).increment();
    log.warn("failed id={} channel={} err={}", n.id(), ch, e.getMessage());
} finally {
    s.stop(metrics.timer("notif.latency", "channel", ch));        // for p50/p99
}
```

### Why alert on "oldest message age," not just lag count

A big number isn't always an emergency, but *staleness* usually is. 10,000 lag that clears in 30 seconds is fine; 10,000 lag where the front message has waited 30 minutes means OTPs are rotting. Age captures "how late is the *worst* affected user," which maps directly to user pain. (Same idea as [§21](#21-kafka-lag).)

### Why per-channel dashboards instead of one big one

Failures look totally different per channel: SMS failing spikes *cost* and hits provider rate limits; push failing often means *dead tokens*; email failing might be a *bounce/spam* issue. One blended "success rate" hides which channel is actually on fire. Splitting by channel (and provider) makes the root cause obvious.

---

## 25. Security, PII & Compliance

Notifications touch sensitive data (email, phone, device tokens) and legally-regulated channels (SMS, email). Interviewers love this follow-up because most candidates forget it.

### Authentication & authorization

| Boundary | Control |
| --- | --- |
| Internal service → Notification API | **mTLS** or signed **service tokens (JWT/OAuth client-creds)** — not open to the internet |
| User → in-app / preferences API | User auth token; a user can only read/modify **their own** notifications |
| Worker → provider (FCM/SES/Twilio) | Secrets in a **vault** (not env files/code); rotate keys |

### PII handling

| Data | Risk | Mitigation |
| --- | --- | --- |
| `email`, `phone`, `device_token` | PII / hijackable | **Encrypt at rest** (column-level or KMS); TLS in transit |
| Rendered `title`/`body` snapshot | May contain PII (name, amount, address) | Encrypt sensitive fields; restrict who can read history; mask in logs |
| Logs / traces | Accidental PII leak | Log `notification_id`/`user_id` only — **never** the message body or raw token |

### Compliance

- **Consent & unsubscribe** — every promotional email needs a one-click unsubscribe (CAN-SPAM / GDPR / CASL); SMS marketing needs prior opt-in. Unsubscribe flips the relevant `notification_preferences` flag.
- **Quiet hours & regional law** — some jurisdictions ban marketing SMS at night; enforce per-user timezone.
- **Right to be forgotten (GDPR)** — a "delete my data" request must purge/anonymize PII in `notifications`, `in_app_notifications`, `user_devices`. Time-partitioning makes bulk deletion of old data cheap.
- **Data retention** — keep detailed history 30–90 days hot; anonymize or archive beyond that.
- **Transactional vs promotional** — legally distinct. Transactional (OTP, receipts) bypass marketing opt-outs; promotional must honor them. This is *why* the type→preference mapping matters.

> **One-liner:** "Service-to-service auth via mTLS/signed tokens, encrypt PII (tokens, phone, email) at rest, never log message bodies, honor unsubscribe + quiet hours for promotional sends, and support GDPR delete via partitioned data + purge job."

### Notifications are full of sensitive data

Think about what flows through this system: **phone numbers, email addresses, device tokens, and message bodies** that may contain names, amounts, addresses. That's a goldmine for attackers — this is **PII** (Personally Identifiable Information). Three separate concerns:

- **Auth** — *who is allowed to ask us to send?* Only internal services (via mTLS/signed tokens), not the public internet. A user can only read *their own* notifications.
- **PII protection** — *if someone steals the data, is it useless to them?* Encrypt tokens/phone/email at rest; use TLS in transit; **never log the message body or raw token.**
- **Compliance** — *are we obeying the law?* Unsubscribe links, quiet hours, GDPR "delete my data."

```java
// Logging done RIGHT — identifiers only, never the sensitive content:
log.info("sent notification id={} user={} channel=SMS", n.id(), n.userId());  // ✅
log.info("sent SMS to {}: {}", user.phone(), n.body());                       // ❌ leaks PII!

// The single most important compliance branch — transactional vs promotional:
boolean allowed(User u, Notification n) {
    if (isTransactional(n.type())) return true;      // OTP/receipt → always send
    return u.marketingOptIn() && !quietHours(u);     // promo → needs consent + not at night
}
```

#### Q: Why can OTP ignore an opt-out but a coupon can't?

Because they're **legally different categories**. **Transactional** messages (OTP, receipts, security alerts) are things the user *needs* to complete an action they started — laws like CAN-SPAM/GDPR let those through even if the user unsubscribed from marketing. **Promotional** messages must honor opt-outs, one-click unsubscribe, and quiet hours. This legal split is the deep reason the whole system tracks `type` and maps it to preferences.

### What "right to be forgotten" means for our tables

Under GDPR a user can demand you erase their data. That means purging/anonymizing their PII across `notifications`, `in_app_notifications`, and `user_devices`. This is *far* easier if data is **time-partitioned** (drop old partitions wholesale) and PII is isolated in specific columns you can null out — another reason partitioning ([§22](#22-db-bottlenecks--mitigations)) pays off. "Delete my data" becomes a targeted purge job, not a frantic table scan.

### Why "never log the body" if it makes debugging harder

Logs get shipped to many places (log aggregators, dashboards, backups) and are read by many people — a message body in a log is PII leaking far and wide. Log the **`notification_id`** instead; if someone needs the actual text, they look it up in the (access-controlled, encrypted) DB. Debuggability without a data breach.

---

## 26. Read Models, Counters & Digests

### Unread-count read model

The bell icon needs a fast unread count. `SELECT COUNT(*) ... WHERE is_read = FALSE` on every app open is expensive at scale.

```
Redis:  unread:{userId} = 7
  create in-app notification  → INCR unread:{userId}
  user reads one             → DECR unread:{userId}
  "mark all read"            → SET unread:{userId} = 0
```

DB remains source of truth; Redis is the hot counter (rebuild by counting on cache miss). This turns an O(n) scan into an O(1) read.

### Aggregation / digest notifications

Users hate 20 separate pings. Coalesce many events into one:

| Strategy | Example |
| --- | --- |
| **Count rollup** | "You have **5** new messages" instead of 5 pushes |
| **Time-window batching** | Buffer non-urgent events for N minutes, then send one summary |
| **Scheduled digest** | Daily/weekly email: "Here's what happened this week" |

Implementation: a buffer keyed by `(user_id, type)` in Redis or a staging table; a job flushes the window → renders a **digest template** → single notification. Transactional/high-priority events (OTP, payment) **bypass** batching and send immediately.

> **Trade-off:** batching reduces spam and cost but adds latency — only apply it to low-priority, high-frequency types.

### Pre-computing the count, and merging pings

Two ideas here, both about *respecting the reader.*

**1. The unread counter (a "read model").** The bell icon needs a number *fast*, on every app open. Running `COUNT(*) WHERE is_read=false` on every open is an expensive scan over a growing table. Instead keep a running tally in Redis and just adjust it: +1 on a new notification, −1 on read, 0 on "mark all read." O(n) scan → O(1) lookup.

```java
void onNewInApp(long userId)      { redis.incr("unread:" + userId); }          // +1
void onRead(long userId)          { redis.decr("unread:" + userId); }          // −1
void onMarkAllRead(long userId)   { redis.set("unread:" + userId, 0); }        // reset
long badge(long userId) {
    Long c = redis.get("unread:" + userId);
    return c != null ? c : rebuildFromDb(userId);   // cache miss → recount once, re-cache
}
```

**2. Digests (aggregation).** Nobody wants 20 separate "you have a new message" pings. **Coalesce** many events into one: *"You have 5 new messages"* instead of five separate pushes.

```java
// Buffer low-priority events per (user, type); a timer flushes them into ONE digest
void onLowPriorityEvent(long userId, String type, Event e) {
    redis.rpush("digest:" + userId + ":" + type, e);   // pile up, don't send yet
}
@Scheduled(fixedRate = 900_000)   // every 15 min
public void flushDigests() {
    for (var bucket : redis.scan("digest:*")) {
        var events = redis.drain(bucket);
        if (events.size() == 1) send(events.get(0));          // just one → send as-is
        else send(renderDigest(events));                      // many → "You have N new ..."
    }
}
```

### If Redis is just a counter, what's the source of truth

The **database** is always the source of truth (the actual `in_app_notifications` rows). Redis is a fast *derived* number — a "read model." If Redis is wiped or drifts, you **rebuild** it by counting the DB once and re-caching. Never treat the counter as authoritative; treat it as a cache you can always regenerate.

### Doesn't batching make notifications late?

Yes — deliberately. That's the trade-off: batching **reduces spam and cost** but **adds latency**. So you only apply it to **low-priority, high-frequency** types (social pings, activity feeds). Anything urgent — OTP, payment, delivery-arriving — **bypasses** batching and sends immediately. You never make a login code wait for the 15-minute digest flush.

---

## 27. Final Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     Event Producers                          │
│         Order · Payment · User · Campaign · Chat             │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
              ┌────────────────────────┐
              │  Notification API /     │
              │  Event Consumer         │
              └────────────┬───────────┘
                           │
         ┌─────────────────┼─────────────────┐
         ▼                 ▼                 ▼
  ┌─────────────┐   ┌─────────────┐   ┌─────────────┐
  │ Preferences │   │  Templates  │   │ Notification│
  │   (cache)   │   │   (cache)   │   │     DB      │
  └─────────────┘   └─────────────┘   └─────────────┘
                           │
                           ▼
              ┌────────────────────────┐
              │   Kafka (by channel    │
              │   + priority)          │
              └────────────┬───────────┘
                           │
      ┌──────────┬─────────┼─────────┬──────────┐
      ▼          ▼         ▼         ▼          ▼
   Push Wkr   Email Wkr  SMS Wkr  In-app Wkr  Scheduler
      │          │         │         │          │
      ▼          ▼         ▼         ▼          ▼
    FCM/APNS   SES/SG   Twilio    DB/Redis   (due jobs)
      │          │         │         │
      └──────────┴─────────┴─────────┴──► WebSocket Service → Clients
                           │
                           ▼
                    DLQ (per channel)
```

---

## 28. How to Drive the Interview

Use this **30-minute framework**:

| Phase | Time | What to say |
| --- | --- | --- |
| **1. Clarify** | 5 min | Single vs bulk? Which channels? Latency for OTP? Idempotency? |
| **2. Estimation** | 5 min | Notifications/day → QPS → channel split → storage |
| **3. API + Data model** | 5 min | POST /notifications, preferences, attempts table |
| **4. High-level design** | 10 min | Async queue, separate workers, DB for history |
| **5. Deep dives** | 10 min | Interviewer picks: fan-out, retry, idempotency, push tokens, scheduling |
| **6. Wrap-up** | 5 min | Observability, failure modes, trade-offs |

**Strong opening sentence:**

> "I'd design this as an **asynchronous, queue-based** system. Source services publish events; the notification service validates preferences, renders templates, persists records, and enqueues channel-specific messages. Separate workers handle delivery with retries, idempotency keys prevent duplicates, and DLQs capture permanent failures."

**Trade-offs to mention proactively:**

| Choice | Trade-off |
| --- | --- |
| At-least-once + idempotency | vs exactly-once (harder, often overkill) |
| Separate topics per channel | vs single topic (simpler but can't scale channels independently) |
| Sync in-app + async push | In-app is cheap; push/email/SMS always async |
| DB scheduler vs delayed queue | Simplicity vs precision at scale |
| Scale workers vs DB capacity | More workers fix Kafka lag but can overload DB |

---

## 29. Interview Cheat Sheet

> **"Walk me through sending an order confirmation notification."**
>
> "Order Service emits `ORDER_CONFIRMED`. Notification Service checks idempotency key, loads user preferences (push + email, no SMS), renders templates, inserts a `PENDING` row, publishes to `notification-push-normal` and `notification-email-normal`. Workers consume, call FCM and SendGrid, record attempts, update status to `SENT`."

> **"How do you prevent duplicate notifications?"**
>
> "Unique `notification_key` on the **notifications** table — create once per business event. Duplicate event hits unique constraint → fetch existing row, check status, don't re-enqueue if already SENT. Delivery retries use the **same** `notification_id` via `notification_attempts` — no re-insert needed."

> **"Unique index blocks insert — how do retries work?"**
>
> "The unique index protects **creation**, not **delivery**. Stage 1: insert notification once. Stage 2: worker fails → update status to RETRYING, log attempt, republish `notificationId` to Kafka retry topic. Retry sends against the existing row — never inserts again."

> **"How do you handle retries?"**
>
> "Exponential backoff — 1m, 5m, 30m. Track `attempt_count` in `notification_attempts`. Prefer Kafka retry topics over DB polling at scale. After max retries → DLQ."

> **"Where do workers run?"**
>
> "Separate processes/deployments from the API — often same codebase, different entrypoint. Push/email/SMS workers scale independently. They all belong to Notification Service and update `notification_db` — not Order Service's DB."

> **"What if Kafka lag builds up?"**
>
> "Monitor lag and oldest-message age. Scale consumers up to partition limit, separate critical vs marketing topics, throttle campaign producers, batch processing, DLQ poison messages. OTP must never share a queue with flash-sale campaigns."

> **"DB becoming the bottleneck?"**
>
> "Batch writes, partition notification table by time, archive old data, cache templates/prefs, read replicas for history, Kafka retry topics instead of DB polling, cap worker concurrency to match DB capacity."

> **"How do template changes work?"**
>
> "Versioned templates — new copy gets version 2, v1 stays for audit. `type` (the event) is stable; `template_id` points to the exact row. A partial unique index enforces exactly one `is_active` template per `(type, channel, language)`, so render is never ambiguous. The notification row stores `template_version`/`template_id` plus a rendered title/body snapshot, so you always know what was sent — even after the template is edited."

> **"How do you notify millions of users in a campaign?"**
>
> "Campaign pipeline: segment service streams user IDs, batch producer enqueues batches to Kafka, push workers scale horizontally. Rate limit per user. Never one synchronous API call for 1M users."

> **"Why separate workers per channel?"**
>
> "Different volume, latency, and failure profiles. Push is high-volume; SMS is expensive and rate-limited; email is slow. Independent scaling and failure isolation."

> **"What happens when FCM returns invalid token?"**
>
> "Mark device token inactive in DB. Don't retry that token. Other devices for the same user still get the push."

> **"How do you send to 1 million users for a flash sale?"**
>
> "Separate high/normal/low Kafka topics. High-priority workers get more resources. Under load, low-priority can be delayed. OTP goes to `notification-sms-high`."

> **"How do scheduled reminders work?"**
>
> "`scheduled_notifications` table with `scheduled_at`. Scheduler polls due rows every minute, creates notification, enqueues to Kafka, marks dispatched. Cancelled bookings update status to CANCELLED."

> **"In-app vs push?"**
>
> "In-app = DB row the app fetches (bell icon). Push = FCM/APNS to device. In-app worker writes DB + optionally notifies WebSocket service for instant UI update."

> **"You insert the row, then publish to Kafka — what if you crash in between?"**
>
> "That's a dual-write. The row is stuck in `PENDING`. I'd use an outbox (row + outbox record in one transaction, a relay publishes) or a reconciliation sweeper that re-enqueues `PENDING` rows older than a few minutes. Safe because the pipeline is at-least-once + idempotent — the worker skips if the attempt is already SENT."

> **"How do you handle PII and compliance?"**
>
> "mTLS/signed tokens between services, encrypt device tokens/phone/email at rest, never log message bodies, one-click unsubscribe + quiet hours for promotional sends, and GDPR delete via partitioned data + a purge job. Transactional messages bypass marketing opt-outs; promotional ones honor them."

> **"How does the unread bell-icon count stay fast?"**
>
> "Redis counter per user — INCR on create, DECR on read, SET 0 on mark-all-read. DB is source of truth; the counter turns an O(n) scan into O(1). Avoid duplicate pings with digest/aggregation for low-priority types; OTP and payment bypass batching."

---

## 30. Consistency & CAP Tradeoffs

> Interviewers love: "Where do you choose consistency vs availability?" Notifications aren't one answer — each path picks differently.

| Path | Choice | Why |
| --- | --- | --- |
| **Create notification (master row + dedup)** | **CP** (strong consistency) | The `UNIQUE(notification_key)` insert must be strongly consistent — a duplicate event has to be *rejected*, so we favor consistency (fail/err the write) over availability on that one row. Dedup is worthless if two replicas both accept the "first" insert. |
| **Delivery status / attempts / read state** | **Eventual** | `SENT → DELIVERED`, retries, and provider webhooks converge over time. A row briefly showing `RETRYING` while a receipt is in flight is fine. |
| **OTP / transactional path** | **Consistency + priority > availability of promo workers** | A login code must go out **correctly and fast** even if the promo pipeline is degraded. We keep the critical lane isolated (its own topics/workers, §16) and would rather shed *marketing* availability than delay an OTP. |
| **In-app feed / unread badge** | **AP** (eventual) | The Redis counter (§26) can lag or be rebuilt from the DB — a stale badge for a few seconds is acceptable. |
| **Preferences / templates (cached)** | **Eventual** (read-mostly) | Served from Redis with a TTL (§19); a preference or template change may take seconds to propagate. Correctness at *send time* still comes from the DB row. |

- The system is **eventually consistent across services** (outbox/queue, §23), but **strongly consistent at the dedup row** — the one place a duplicate must be impossible.
- When forced to choose under partition, the rule is **priority-aware**: keep the transactional lane alive; let promotional availability degrade first.

> One-liner: **"Strong consistency on the dedup write, eventual everywhere else — and when something has to give, drop promo availability before transactional."**

---

## 31. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Producer-Consumer** | API enqueues → channel workers consume (Kafka) | Decouple + scale delivery |
| **Strategy** | Per-channel delivery (push/email/SMS), retry/backoff, template rendering | Swap behavior per channel |
| **Ports & Adapters (Hexagonal)** | Provider ports (FCM/SES/Twilio), queue, repo | Swap providers without touching core |
| **Idempotency Key** | `notification_key` unique constraint | Dedup duplicate events |
| **Outbox** | Reliable event → Kafka publish | No dual-write loss |
| **Observer / Pub-Sub** | Domain events → notification pipeline; WebSocket fan-out | Decouple |
| **State** | Notification + attempt lifecycle | Guard transitions |
| **Factory** | Channel-worker / provider creation | Extensible channels |
| **Template Method** | Common worker skeleton, channel-specific `deliver()` | Reuse flow |
| **Circuit Breaker + Retry** | Provider calls (with fallback provider) | Fail fast + resilience |
| **Priority Queue** | High/normal/low topics | Critical (OTP) drains first |
| **Decorator / Chain** | Preference → quiet-hours → rate-limit checks before send | Composable gating |

---

## 32. Final Takeaways

- **Async queue-based architecture** — API enqueues, workers deliver; never block source systems.
- **Two stages** — unique index = **create once** (`notifications`); retries = **send many times** (`notification_attempts` on same `notification_id`).
- **Separate workers per channel** — independent scale and failure domains; usually **separate deployments**, same service ownership.
- **Two-level storage** — `notifications` (intent) + `notification_attempts` (per-channel delivery).
- **Idempotency key** — `userId:type:entityId` + unique constraint; duplicate event → fetch existing, don't re-insert.
- **Template versioning** — new version row, never edit in place; snapshot `template_version` + rendered body on notification.
- **Fan-out ≠ single notify** — campaigns need batch pipeline + segment service.
- **Retries + DLQ + provider fallback** — prefer Kafka retry topics over DB polling at scale.
- **Kafka lag** — separate critical vs low-priority topics; scale workers ≤ partition count; watch oldest message age.
- **DB bottleneck** — batch writes, partition/archive, cache reads, cap worker concurrency; fixing lag with more workers can overload DB.
- **Service ownership** — workers update `notification_db` only; EC2 boundary ≠ service boundary.
- **Preferences + rate limits + quiet hours** — respect users and compliance.
- **Partition Kafka by `user_id`** — ordering and even load.
- **Push = multi-device fan-out** — one user, many tokens; invalidate stale tokens.
- **Transactional outbox** at source — don't lose events before they reach the notification system.
- **Dual-write inside the service too** — insert-then-publish can strand rows in `PENDING`; use an outbox or a reconciliation sweeper.
- **Retry with jitter** — fixed backoff causes a thundering herd when a provider recovers.
- **Distributed IDs** — Snowflake/UUIDv7, not a single auto-increment sequence, so you can shard.
- **Security & PII** — mTLS/signed tokens, encrypt tokens/phone/email, never log bodies, honor unsubscribe + GDPR delete.
- **Fast read models** — Redis unread counter (O(1)); digest/aggregation to cut spam for low-priority types.

### Related notes

- [Notification System — HLD & LLD](notification-system-hld-lld.md) — architecture diagram, full DDL, API contracts, class design, state machines, sequences
- [Fan-Out / Fan-In & Celebrity Problem](../concepts/fan-out-fan-in.md) — event fan-out to many users + batch pipeline
- [Idempotency](../concepts/idempotency.md)
- [Rate Limiting](../concepts/rate-limiting.md)
- [Outbox & Saga](../concepts/outbox-and-saga.md)
- [Scaling Architecture](../concepts/scaling-architecture.md)
