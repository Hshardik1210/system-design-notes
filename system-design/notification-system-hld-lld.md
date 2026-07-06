# Notification System — HLD & LLD

> Companion to **Notification System — System Design**. This doc is split into **Part A: High-Level Design (HLD)** — the big-picture architecture — and **Part B: Low-Level Design (LLD)** — concrete schema, contracts, classes, state machines, and algorithms.

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
| Old notifications archive | **S3 / cold storage** | partition by month; TTL after 90 days |

**Partitioning:** Kafka topics partitioned by `user_id`. Notification DB sharded by `user_id` at very large scale.

---

## A6. Scalability & Availability

- **Traffic spikes (flash sale)** → Kafka absorbs backlog; autoscale push workers independently.
- **Channel isolation** → SMS provider slow doesn't block push workers (separate topics + consumer groups).
- **Priority under load** → drain high-priority topics first; throttle low-priority.
- **No SPOF** → multi-AZ DB, Kafka cluster, N stateless API/worker instances behind LB.
- **Graceful degradation** → if email provider down, push + in-app still deliver; failed channel → retry/DLQ without blocking others.
- **Campaign fan-out** → batch pipeline, never single API call for millions of users.

> Details in main note §11, §19–§21.

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

---

# PART B — Low-Level Design (LLD)

---

## B1. Database Schema (DDL)

```sql
-- ---------- Core notification domain ----------
CREATE TABLE notifications (
    notification_id   BIGINT PRIMARY KEY,
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
    template_id       BIGINT PRIMARY KEY,
    type              VARCHAR(100) NOT NULL,
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
-- notifications.template_version stores which version was rendered

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

### Retry with exponential backoff

```
handleFailure(job, attempt):
    attempt_count++
    if attempt_count >= MAX_RETRIES:
        kafka.publish(DLQ_topic, job)
        update attempt status=FAILED
        return

    delay = backoff[attempt_count]    # 0, 1m, 5m, 30m
    update attempt: status=RETRYING, next_retry_at=now+delay
    kafka.publish(delayed_retry_topic, job, delay)
```

### Idempotency (DB + worker)

```
# API level
INSERT INTO notifications (notification_key, ...)
ON CONFLICT (notification_key) DO NOTHING
→ if 0 rows inserted, return existing notification_id

# Worker level
SELECT status FROM notification_attempts
WHERE notification_id=? AND channel=?
if status == SENT: return (skip duplicate Kafka message)
```

### Rate limiting (Redis)

```
key = "notif_count:{userId}:{type}:{yyyy-MM-dd}"
count = INCR key
if count == 1: EXPIRE key end_of_day
if count > limit(type): return false
return true

limits:
  PROMOTION: 5/day
  OTP: unlimited (but short TTL on message itself)
```

### Campaign fan-out

```
runCampaign(campaignId):
    users = segmentService.streamUsers(campaign.segmentId)  // cursor, don't load all in memory
    batch = []
    for user in users:
        batch.append(user.id)
        if len(batch) >= BATCH_SIZE:          // e.g. 5000
            insert campaign_batches(batch)
            kafka.publish("campaign-batch", { campaignId, userIds: batch })
            batch = clear()
    update campaign.total_users
    campaign.status = RUNNING

// Campaign consumer (per user in batch):
for userId in batch.userIds:
    orchestrator.send(SendRequest(userId, campaign.type, campaign.channels, ...))
    increment campaign.sent_count
```

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
| Stale device token | Deactivate on provider error; don't retry same token |

---

## B11. Caching Design

| Key | Value | TTL | Notes |
| --- | --- | --- | --- |
| `pref:{userId}:{type}` | `NotificationPreference` JSON | 5–15 min | invalidate on PUT preferences |
| `tmpl:{type}:{channel}:{lang}` | template strings | 1 hour | invalidate on template update |
| `notif_count:{userId}:{type}:{date}` | integer | end of day | rate limiting |
| `ws:user:{userId}` | `{ connectionId, serverId }` | session | refreshed on heartbeat |
| `devices:{userId}` | list of active tokens | 5 min | optional; DB is source of truth |

---

## B12. Error Handling & Edge Cases

| Case | Handling |
| --- | --- |
| Duplicate API request | `UNIQUE(notification_key)` → `handleDuplicate()`; never re-insert |
| Duplicate Kafka message | Worker skips if attempt already `SENT` |
| Delivery failed after insert | Update same `notification_id` to RETRYING; republish to retry topic — **no re-insert** |
| User opted out | `intersect()` returns empty → no record or `SKIPPED` |
| All push tokens invalid | Deactivate tokens; attempt → `FAILED` or retry if transient |
| Email bounces (hard) | Permanent failure; no retry; mark attempt `FAILED` |
| SMS provider down | Retry with backoff → fallback provider → DLQ |
| Quiet hours | Skip promotional; transactional (OTP) bypasses quiet hours |
| Partial multi-channel success | Notification → `PARTIALLY_SENT`; failed channels retry independently |
| Campaign too fast | Throttle batch publish rate; rate limit per user still applies |
| Scheduled event cancelled | Update `scheduled_notifications.status = CANCELLED` |
| Template missing | Fail fast; alert; don't send blank message |
| Queue backlog | Autoscale workers; prioritize HIGH topic |

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

---

> See **Notification System — System Design** for deeper rationale, plus **Idempotency**, **Rate Limiting**, and **Outbox & Saga** notes for supporting patterns.
