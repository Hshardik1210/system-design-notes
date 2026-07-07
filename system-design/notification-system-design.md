# Notification System — System Design

> **Core challenge:** Reliably deliver the **right message** to the **right user** on the **right channel** at the **right time** — without duplicates, without spam, and without blocking the source system. Used by Amazon (order updates), Swiggy (delivery tracking), BookMyShow (show reminders), LinkedIn (activity alerts), WhatsApp (messages).

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
- [30. Design Patterns (that can be used)](#30-design-patterns-that-can-be-used)
- [31. Final Takeaways](#31-final-takeaways)

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

---

## 8. Data Model & Schema

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

When copy changes, **insert a new version** — do not overwrite v1:

| type | channel | version | is_active | body_template |
| --- | --- | --- | --- | --- |
| ORDER_CONFIRMED | PUSH | 1 | false | "Order {{orderId}} confirmed" |
| ORDER_CONFIRMED | PUSH | 2 | true | "Hi {{name}}, order {{orderId}} confirmed!" |

**Selecting the template to use for a new send** — always pick the active row:

```sql
SELECT * FROM notification_templates
WHERE type = 'ORDER_CONFIRMED' AND channel = 'PUSH' AND language = 'en'
  AND is_active = TRUE;
```

The `notifications` row stores `template_type` + `template_version` (or a direct `template_id` FK — see [§15](#15-template-system)) **plus** the rendered `title`/`body` snapshot — so you always know exactly what was sent, even after v2 goes live.

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

---

## 13. Idempotency & Deduplication

**Problem:** Kafka delivers **at-least-once**. Same event consumed twice → duplicate "Order confirmed" push.

### Two stages — don't confuse them

| Stage | What it does | Protected by |
| --- | --- | --- |
| **Stage 1: Create notification** | Insert one row per business event | `notification_key` + `UNIQUE` constraint |
| **Stage 2: Deliver notification** | Worker sends via FCM/email/SMS; may retry | `notification_id` + `notification_attempts` |

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

Two boundaries in this system are both dual-writes — a DB write and a Kafka publish that are **not atomic**:

| Boundary | Failure if naïve | Fix |
| --- | --- | --- |
| **Source → Notification** (Order emits event) | Order committed but event lost → user never notified | **Transactional Outbox** in source: write order + outbox row in one TX; a relay publishes outbox → Kafka |
| **Notification API → Kafka** (insert row, then enqueue) | Row inserted, process crashes before enqueue → stuck in `PENDING`, never delivered | **Outbox** in notification DB *or* a **reconciliation sweeper** (below) |

**Reconciliation sweeper** (safety net for stuck rows):

```sql
-- Runs every 1–5 min; re-enqueues rows that were persisted but never delivered
SELECT notification_id, ... FROM notifications
WHERE status = 'PENDING' AND created_at < now() - interval '5 minutes'
LIMIT 1000;
-- → re-publish to Kafka (idempotent: worker skips if attempt already SENT)
```

Because the whole pipeline is **at-least-once + idempotent**, re-enqueuing a row that *was* actually delivered is harmless — the worker sees `attempt.status = SENT` and skips.

> See: [Outbox & Saga](../concepts/outbox-and-saga.md).

---

## 24. Observability

| Signal | What to track |
| --- | --- |
| **Metrics** | Send rate by channel, success/failure rate, latency p50/p99, DLQ depth, retry count |
| **Logs** | Structured: `notification_id`, `user_id`, `channel`, `provider`, `error_code` |
| **Traces** | API → enqueue → worker → provider call (OpenTelemetry) |
| **Alerts** | DLQ growth, success rate < 95%, provider error spike, scheduler lag, **Kafka consumer lag**, **oldest message age** |

**Dashboards per channel** — SMS failure looks different from push failure.

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

## 30. Design Patterns (that can be used)

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

## 31. Final Takeaways

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
