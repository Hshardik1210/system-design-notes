# BookMyShow — HLD & LLD

> Companion to **BookMyShow — System Design**. This doc is split into **Part A: High-Level Design (HLD)** — the big-picture architecture — and **Part B: Low-Level Design (LLD)** — concrete schema, contracts, classes, state machines, and algorithms.

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
- [B1. Database Schema (DDL)](#b1-database-schema-ddl)
- [B2. API Contracts](#b2-api-contracts)
- [B3. Booking Service — Class Design (detailed)](#b3-booking-service--class-design-detailed)
- [B4. State Machines](#b4-state-machines)
- [B5. Core Algorithms](#b5-core-algorithms)
- [B6. Sequence — Happy Path (async payment via Saga)](#b6-sequence--happy-path-async-payment-via-saga)
- [B7. Sequence — Payment Failure (compensation)](#b7-sequence--payment-failure-compensation)
- [B8. Concurrency & Correctness Summary](#b8-concurrency--correctness-summary)
- [B9. Caching Design](#b9-caching-design)
- [B10. Error Handling & Edge Cases](#b10-error-handling--edge-cases)
- [B11. Cheat-Sheet Mapping (HLD ↔ LLD)](#b11-cheat-sheet-mapping-hld-↔-lld)
- [B12. Design Patterns (that can be used)](#b12-design-patterns-that-can-be-used)

---

## A1. Scope & Core Goal

- **Functional:** browse movies/shows → view seat map → lock seats → pay → confirm ticket.
- **Core goal:** **never double-book a seat**, stay highly available for browsing, and scale for blockbuster spikes.
- **Style:** strong consistency on the **seat write**, eventual consistency everywhere else.

> Full requirements + estimation live in the main **BookMyShow — System Design** note (§2, §3).

---

## A2. Architecture Overview

```
                          ┌─────────────┐
                          │     CDN     │  posters, static seat-map layout
                          └─────────────┘
                                 ▲
   ┌──────────┐            ┌─────┴───────┐
   │  Client  │ ─────────► │ API Gateway │  auth · rate limit · routing
   │ (App/Web)│            └─────┬───────┘
   └──────────┘                  │
        ┌───────────────┬────────┼────────────┬──────────────┐
        ▼               ▼        ▼             ▼              ▼
 ┌────────────┐  ┌────────────┐ ┌───────────┐ ┌───────────┐ ┌──────────────┐
 │  Search    │  │  Catalog   │ │  Booking  │ │  Payment  │ │ Notification │
 │  Service   │  │  Service   │ │  Service  │ │  Service  │ │   Service    │
 └─────┬──────┘  └─────┬──────┘ └─────┬─────┘ └─────┬─────┘ └──────┬───────┘
       ▼               ▼              │             │              ▲
 ┌───────────┐   ┌───────────┐  ┌────┴────┐  ┌─────┴─────┐        │
 │Elasticsrch│   │ Catalog DB│  │Booking DB│  │Payment DB │        │
 └───────────┘   └───────────┘  │  + Redis │  └───────────┘        │
                                 └────┬─────┘                       │
                                      │ outbox                      │
                                      ▼                             │
                                 ┌─────────┐   booking events       │
                                 │  Kafka  │ ───────────────────────┘
                                 └────┬────┘
                                      ├──► Analytics Service
                                      └──► Invoice Service
```

---

## A3. Services & Responsibilities

| Service | Responsibility | Data store |
| --- | --- | --- |
| **API Gateway** | Auth, rate limiting, routing, request validation | — |
| **Search Service** | Movie/event discovery (city, language, genre, text) | Elasticsearch |
| **Catalog Service** | Movies, theatres, screens, shows, seat inventory (master data) | Catalog DB (SQL) + read replicas |
| **Booking Service** | Seat lock/confirm, booking lifecycle — **the core** | Booking DB (SQL) + Redis |
| **Payment Service** | Gateway integration, webhooks, reconciliation, refunds | Payment DB (SQL) |
| **Notification Service** | SMS/email/push (ticket + QR) | consumes Kafka |
| **Analytics Service** | Funnels, occupancy, revenue | consumes Kafka |
| **Invoice Service** | Invoice/receipt generation | consumes Kafka |

> Booking and Payment are split so payment's external/unreliable nature is isolated from the seat-locking core.

---

## A4. Communication — Sync vs Async

| Interaction | Style | Why |
| --- | --- | --- |
| Client → Gateway → services | **Sync (HTTPS/REST)** | user is waiting for a response |
| Booking ↔ Payment | **Async (Saga over Kafka)** or sync call | payment is slow/unreliable → prefer async |
| Booking → downstream (notify/analytics/invoice) | **Async (Outbox → Kafka)** | fire-and-forget, must not block booking |
| Catalog → Booking (seat inventory) | seeded per show; Booking owns live seat state | avoid cross-service chatter on hot path |

> **Rule:** synchronous on the user-facing read/lock path; asynchronous for anything downstream of a confirmed state change.

---

## A5. Storage Strategy

| Need | Choice | Reason |
| --- | --- | --- |
| Seat/booking source of truth | **RDBMS (Postgres/MySQL)** | ACID, row-level atomic conditional updates |
| Fast seat lock / dedup | **Redis** | in-memory, atomic `SET NX`, TTL |
| Search/browse | **Elasticsearch** | full-text + faceted search |
| Static assets | **CDN / object store** | offload posters, layouts |
| Events | **Kafka** | durable, partitioned, replayable |

**Sharding:** Booking DB sharded by `show_id` (natural isolation). **Replicas:** read replicas for browse/seat-map reads.

---

## A6. Scalability & Availability

- **Read-heavy browse** → CDN + Redis cache + ES + read replicas.
- **Write contention on hot seats** → atomic conditional update (+ Redis gate); shard by `show_id`.
- **Blockbuster spike** → **virtual waiting room**, gateway rate limiting, queue-based admission.
- **No SPOF** → multi-AZ DB (primary + replicas + failover), Redis cluster, N stateless service instances behind LB.
- **Graceful degradation** → if Search down, allow direct booking; if Redis down, fall back to DB-only locking.

> Details in main note §18, §21–§24.

---

## A7. Tech Stack (one option)

| Layer | Choice |
| --- | --- |
| API | Java/Spring Boot or Go |
| DB | PostgreSQL (sharded) |
| Cache/Lock | Redis (cluster) |
| Search | Elasticsearch |
| Messaging | Kafka |
| Background jobs | Scheduler (expiry/reconciliation) + Kafka consumers |
| Infra | Kubernetes + LB + CDN |

---

# PART B — Low-Level Design (LLD)

---

## B1. Database Schema (DDL)

> Identify a seat by its **unique `seat_id` (PK)** or by **`show_id` + `seat_number`** — never `seat_number` alone.

```sql
-- ---------- Catalog (master data) ----------
CREATE TABLE movie (
    movie_id     BIGINT PRIMARY KEY,
    title        VARCHAR(255) NOT NULL,
    language     VARCHAR(50),
    genre        VARCHAR(50),
    duration_min INT
);

CREATE TABLE city (
    city_id BIGINT PRIMARY KEY,
    name    VARCHAR(100) NOT NULL
);

CREATE TABLE cinema (
    cinema_id BIGINT PRIMARY KEY,
    city_id   BIGINT NOT NULL REFERENCES city(city_id),
    name      VARCHAR(255) NOT NULL,
    location  VARCHAR(255)
);

CREATE TABLE screen (
    screen_id BIGINT PRIMARY KEY,
    cinema_id BIGINT NOT NULL REFERENCES cinema(cinema_id),
    name      VARCHAR(50),
    rows      INT,
    cols      INT
);

CREATE TABLE show (
    show_id    BIGINT PRIMARY KEY,
    movie_id   BIGINT NOT NULL REFERENCES movie(movie_id),
    screen_id  BIGINT NOT NULL REFERENCES screen(screen_id),
    city_id    BIGINT NOT NULL REFERENCES city(city_id),   -- for city-scoped listing
    start_time TIMESTAMP NOT NULL,
    base_price NUMERIC(10,2) NOT NULL,
    language   VARCHAR(50), format VARCHAR(20)              -- 2D/3D/IMAX
);
CREATE INDEX idx_show_lookup ON show (movie_id, city_id, start_time);

-- ---------- Users ----------
CREATE TABLE app_user (
    user_id     BIGINT PRIMARY KEY,
    name        VARCHAR(255),
    email       VARCHAR(255) UNIQUE,
    phone       VARCHAR(20) UNIQUE,
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

-- ---------- Seat pricing tiers (Regular / Premium / Recliner) ----------
CREATE TABLE seat_category (
    category_id BIGINT PRIMARY KEY,
    screen_id   BIGINT NOT NULL REFERENCES screen(screen_id),
    name        VARCHAR(30) NOT NULL,           -- REGULAR, PREMIUM, RECLINER
    price_multiplier NUMERIC(4,2) NOT NULL DEFAULT 1.0   -- final price = show.base_price * multiplier
);

-- ---------- Booking domain ----------
CREATE TABLE seat (
    seat_id     BIGINT PRIMARY KEY,
    show_id     BIGINT NOT NULL REFERENCES show(show_id),
    category_id BIGINT REFERENCES seat_category(category_id),  -- pricing tier
    seat_number VARCHAR(10) NOT NULL,           -- e.g. 'A1'
    price       NUMERIC(10,2),                   -- resolved price for this seat/show
    status      VARCHAR(12) NOT NULL DEFAULT 'AVAILABLE', -- AVAILABLE|LOCKED|BOOKED
    locked_by   BIGINT,                          -- user_id holding the lock
    lock_expiry TIMESTAMP,
    version     INT NOT NULL DEFAULT 0,          -- optimistic locking
    UNIQUE (show_id, seat_number)
);
CREATE INDEX idx_seat_show_status ON seat (show_id, status);
CREATE INDEX idx_seat_expiry      ON seat (lock_expiry) WHERE status = 'LOCKED';

CREATE TABLE booking (
    booking_id      BIGINT PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES app_user(user_id),
    show_id         BIGINT NOT NULL REFERENCES show(show_id),
    status          VARCHAR(12) NOT NULL,        -- PENDING|CONFIRMED|FAILED|CANCELLED
    subtotal        NUMERIC(10,2) NOT NULL,
    discount        NUMERIC(10,2) DEFAULT 0,
    amount          NUMERIC(10,2) NOT NULL,      -- final payable
    coupon_code     VARCHAR(50),
    idempotency_key VARCHAR(64) NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (idempotency_key)
);
CREATE INDEX idx_booking_user ON booking (user_id, created_at DESC);

-- Join table (preferred over seat_ids[] — clean FKs + queries)
CREATE TABLE booking_seat (
    booking_id BIGINT NOT NULL REFERENCES booking(booking_id),
    seat_id    BIGINT NOT NULL REFERENCES seat(seat_id),
    price      NUMERIC(10,2) NOT NULL,           -- snapshot at booking time
    PRIMARY KEY (booking_id, seat_id)
);

CREATE TABLE payment (
    payment_id      BIGINT PRIMARY KEY,
    booking_id      BIGINT NOT NULL REFERENCES booking(booking_id),
    amount          NUMERIC(10,2) NOT NULL,
    status          VARCHAR(12) NOT NULL,        -- INITIATED|SUCCESS|FAILED|REFUNDED|UNKNOWN
    method          VARCHAR(20),                 -- UPI|CARD|WALLET|NETBANKING
    gateway         VARCHAR(30),
    gateway_ref     VARCHAR(128),
    idempotency_key VARCHAR(64) UNIQUE,          -- never double-charge
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE payment_webhook_events (            -- dedup at-least-once gateway callbacks
    event_id     VARCHAR(128) PRIMARY KEY,       -- gateway event id
    payment_id   BIGINT, gateway_ref VARCHAR(128),
    status       VARCHAR(20), processed BOOLEAN DEFAULT FALSE,
    received_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE refund (
    refund_id   BIGINT PRIMARY KEY,
    booking_id  BIGINT NOT NULL REFERENCES booking(booking_id),
    payment_id  BIGINT NOT NULL REFERENCES payment(payment_id),
    amount      NUMERIC(10,2) NOT NULL,
    reason      VARCHAR(100),
    status      VARCHAR(20) NOT NULL,            -- INITIATED|DONE|FAILED
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

-- Issued ticket (QR / entry pass) after CONFIRMED
CREATE TABLE ticket (
    ticket_id   BIGINT PRIMARY KEY,
    booking_id  BIGINT NOT NULL REFERENCES booking(booking_id),
    qr_code     VARCHAR(255) UNIQUE,
    issued_at   TIMESTAMP NOT NULL DEFAULT now(),
    checked_in  BOOLEAN DEFAULT FALSE
);

-- ---------- Coupons / offers ----------
CREATE TABLE coupon (
    code         VARCHAR(50) PRIMARY KEY,
    type         VARCHAR(10) NOT NULL,           -- FLAT|PERCENT
    value        NUMERIC(10,2) NOT NULL,
    min_amount   NUMERIC(10,2) DEFAULT 0,
    max_discount NUMERIC(10,2),
    valid_from   TIMESTAMP, valid_to TIMESTAMP,
    usage_limit  INT, per_user_limit INT DEFAULT 1,
    is_active    BOOLEAN DEFAULT TRUE
);
CREATE TABLE coupon_redemption (
    code VARCHAR(50), user_id BIGINT, booking_id BIGINT, redeemed_at TIMESTAMP,
    PRIMARY KEY (code, booking_id)
);

-- ---------- Movie ratings/reviews (optional) ----------
CREATE TABLE review (
    review_id BIGINT PRIMARY KEY, movie_id BIGINT, user_id BIGINT,
    rating SMALLINT, comment TEXT, created_at TIMESTAMP,
    UNIQUE (movie_id, user_id)
);

-- ---------- Outbox (in Booking DB) ----------
CREATE TABLE outbox (
    event_id   BIGINT PRIMARY KEY,
    event_type VARCHAR(50) NOT NULL,             -- BOOKING_CONFIRMED|BOOKING_FAILED
    payload    JSONB NOT NULL,
    status     VARCHAR(10) NOT NULL DEFAULT 'PENDING', -- PENDING|SENT
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_outbox_pending ON outbox (status, created_at);
```

> **Tables to consider (checklist):** movie, city, cinema, screen, show, seat_category, seat, app_user, booking, booking_seat, payment, payment_webhook_events, refund, ticket, coupon, coupon_redemption, review, outbox. (Search index for movies/shows lives in Elasticsearch; virtual-waiting-room queue lives in Redis.)

---

## B2. API Contracts

### Get seat map

```
GET /v1/shows/{showId}/seats
200 OK
{
  "showId": 42,
  "seats": [
    { "seatId": 99817, "seatNumber": "A1", "status": "AVAILABLE" },
    { "seatId": 99818, "seatNumber": "A2", "status": "BOOKED" }
  ]
}
```

### Lock seats (hold)

```
POST /v1/shows/{showId}/lock
Idempotency-Key: 550e8400-...
{ "seatNumbers": ["A1","A2"], "userId": 123 }

200 OK   { "lockId": "lk_88", "seatNumbers": ["A1","A2"], "expiresAt": "2026-06-21T10:05:00Z" }
409 CONFLICT { "error": "SEAT_UNAVAILABLE", "unavailable": ["A1"] }
```

### Create booking + start payment

```
POST /v1/bookings
Idempotency-Key: 550e8400-...
{ "lockId": "lk_88", "paymentMethod": "UPI" }

201 CREATED { "bookingId": 700, "status": "PENDING", "amount": 500.0 }
```

### Payment webhook (gateway → us)

```
POST /v1/payments/webhook
{ "gatewayRef": "pay_xyz", "bookingId": 700, "status": "SUCCESS" }
200 OK
```

### Poll booking

```
GET /v1/bookings/{bookingId}
200 OK { "bookingId": 700, "status": "CONFIRMED", "seatNumbers": ["A1","A2"], "ticket": { "qr": "..." } }
```

### Browse / discovery (Search + Catalog services)

```
GET /v1/cities                                   → list cities
GET /v1/movies?cityId=1&date=2026-06-21&lang=en  → now-showing movies (Search/ES)
GET /v1/movies/{movieId}/shows?cityId=1&date=    → shows (cinema, screen, time, price)
GET /v1/shows/{showId}                            → show detail + seat categories/pricing
```

### Release a held lock (user backs out)

```
POST /v1/shows/{showId}/release
{ "lockId": "lk_88", "userId": 123 }
200 OK   # frees LOCKED seats immediately (else expiry sweep would)
```

### Apply / validate coupon (pre-booking)

```
POST /v1/coupons/validate
{ "code": "WELCOME50", "userId": 123, "amount": 500.0 }
200 OK { "valid": true, "discount": 50.0, "payable": 450.0 }
409 { "error": "COUPON_INVALID" }
```

### Cancel booking + refund

```
POST /v1/bookings/{bookingId}/cancel
Idempotency-Key: 550e8400-...
200 OK { "bookingId": 700, "status": "CANCELLED", "refund": { "amount": 450.0, "status": "INITIATED" } }
# releases seats (→ AVAILABLE) + initiates refund per policy
```

### User booking history

```
GET /v1/users/{userId}/bookings?cursor=&limit=20
200 OK { "items": [ { "bookingId": 700, "status": "CONFIRMED", "showId": 42, ... } ], "nextCursor": ... }
```

### Internal / events (Kafka)

```
BOOKING_CONFIRMED · BOOKING_FAILED · BOOKING_CANCELLED
PAYMENT_SUCCESS · PAYMENT_FAILED · REFUND_INITIATED
```

---

## B3. Booking Service — Class Design (detailed)

### B3.0 Layered overview

```
            HTTP
             │
┌────────────▼─────────────┐
│     BookingController     │  HTTP I/O, DTO mapping, idempotency header
└────────────┬─────────────┘
             ▼
┌────────────────────────────────────────────────┐
│              BookingService                      │  orchestration + business rules
│  lockSeats · createBooking · onPaymentResult     │
│  confirmBooking · releaseSeats · getBooking      │
└──┬───────┬────────┬─────────┬─────────┬─────────┘
   │       │        │         │         │
   ▼       ▼        ▼         ▼         ▼
┌────────┐┌────────┐┌────────┐┌────────┐┌──────────────┐
│SeatLock││Idempot.││ Seat   ││Booking ││ OutboxRepo / │
│Manager ││ Store  ││ Repo   ││ Repo   ││ PaymentPort  │
└───┬────┘└───┬────┘└───┬────┘└───┬────┘└──────┬───────┘
    ▼         ▼         ▼         ▼            ▼
  Redis     Redis/DB    DB        DB        DB/Kafka
```

> **Design rule:** the **Controller** speaks HTTP, the **Service** owns business logic + transactions, and **everything external** (DB, Redis, payment, messaging) sits behind an **interface (port)**. This keeps the core testable and swappable (hexagonal / ports-and-adapters).

### B3.1 Domain models, enums, DTOs

```java
// ---------- Enums ----------
enum SeatStatus    { AVAILABLE, LOCKED, BOOKED }
enum BookingStatus { PENDING, CONFIRMED, FAILED, CANCELLED }
enum PaymentStatus { INITIATED, SUCCESS, FAILED, UNKNOWN, REFUNDED }

// ---------- Entities (map to DB rows) ----------
class Seat {
    long       seatId;
    long       showId;
    String     seatNumber;     // "A1"
    SeatStatus status;
    Long       lockedBy;       // userId or null
    Instant    lockExpiry;
    int        version;        // optimistic-lock counter
}

class Booking {
    long          bookingId;
    long          userId;
    long          showId;
    List<Long>    seatIds;
    BookingStatus status;
    BigDecimal    amount;
    String        idempotencyKey;
    Instant       createdAt;
}

class Payment {
    long          paymentId;
    long          bookingId;
    BigDecimal    amount;
    PaymentStatus status;
    String        gatewayRef;
}

class OutboxEvent {
    long    eventId;
    String  eventType;         // "BOOKING_CONFIRMED"
    String  payload;           // JSON
    String  status;            // PENDING | SENT
}

// ---------- DTOs (API request/response) ----------
class LockRequest    { List<String> seatNumbers; long userId; }
class LockResponse   { String lockId; List<String> seatNumbers; Instant expiresAt; }
class BookingRequest { String lockId; String paymentMethod; }
class BookingResponse{ long bookingId; BookingStatus status; BigDecimal amount; }
```

### B3.2 Controller

```java
@RestController
class BookingController {
    private final BookingService bookingService;

    @PostMapping("/v1/shows/{showId}/lock")
    LockResponse lock(@PathVariable long showId,
                      @RequestHeader("Idempotency-Key") String idemKey,
                      @RequestBody LockRequest req) {
        return bookingService.lockSeats(showId, req.seatNumbers, req.userId, idemKey);
    }

    @PostMapping("/v1/bookings")
    BookingResponse create(@RequestHeader("Idempotency-Key") String idemKey,
                           @RequestBody BookingRequest req) {
        return bookingService.createBooking(req, idemKey);
    }

    @GetMapping("/v1/bookings/{id}")
    BookingResponse get(@PathVariable long id) { return bookingService.getBooking(id); }
}
```

> **Significance:** the controller is a thin **adapter** — it validates input, reads the `Idempotency-Key` header, maps DTOs, and translates exceptions to HTTP codes (`409` for `SeatUnavailableException`, etc.). **No business logic here.**

### B3.3 BookingService (the orchestrator)

```java
@Service
class BookingService {
    private final SeatLockManager   lockManager;
    private final IdempotencyStore  idempotency;
    private final SeatRepository    seatRepo;
    private final BookingRepository bookingRepo;
    private final OutboxRepository  outboxRepo;
    private final PaymentPort       payment;
    private final TransactionTemplate tx;   // for atomic DB blocks

    LockResponse lockSeats(long showId, List<String> seatNos, long userId, String idemKey) {
        return idempotency.execute(idemKey, () -> {
            List<String> acquired = new ArrayList<>();
            for (String s : sorted(seatNos)) {                       // sorted → no deadlock
                if (!lockManager.tryLock(showId, s, userId, 300)) {
                    lockManager.releaseAll(showId, acquired, userId); // rollback Redis
                    throw new SeatUnavailableException(s);
                }
                acquired.add(s);
            }
            int rows = seatRepo.lockSeats(showId, seatNos, userId, now().plus(5, MIN));
            if (rows != seatNos.size()) {                            // partial → undo all
                seatRepo.releaseSeats(showId, seatNos, userId);
                lockManager.releaseAll(showId, seatNos, userId);
                throw new SeatUnavailableException(seatNos);
            }
            return new LockResponse(newLockId(), seatNos, now().plus(5, MIN));
        });
    }

    BookingResponse createBooking(BookingRequest req, String idemKey) {
        return idempotency.execute(idemKey, () -> {
            Booking b = bookingRepo.insertPending(buildBooking(req, idemKey)); // UNIQUE key
            payment.requestPayment(b.bookingId, b.amount, req.paymentMethod);  // async (saga)
            return new BookingResponse(b.bookingId, PENDING, b.amount);
        });
    }

    // Called by the payment webhook/consumer
    void onPaymentResult(long bookingId, PaymentStatus result) {
        Booking b = bookingRepo.findById(bookingId);
        if (result == SUCCESS) confirmBooking(b);
        else                   releaseSeats(b);     // compensation
    }

    private void confirmBooking(Booking b) {
        tx.execute(() -> {                                            // ONE transaction
            seatRepo.markBooked(b.showId, b.seatNumbers(), b.userId);
            bookingRepo.updateStatus(b.bookingId, CONFIRMED);
            outboxRepo.append(event("BOOKING_CONFIRMED", b));         // same TX as state
        });
        lockManager.releaseAll(b.showId, b.seatNumbers(), b.userId);  // best-effort
    }

    private void releaseSeats(Booking b) {
        tx.execute(() -> {
            seatRepo.releaseSeats(b.showId, b.seatNumbers(), b.userId); // WHERE locked_by=user
            bookingRepo.updateStatus(b.bookingId, FAILED);
            outboxRepo.append(event("BOOKING_FAILED", b));
        });
        lockManager.releaseAll(b.showId, b.seatNumbers(), b.userId);
    }
}
```

> **Significance:** the service is the **only place that knows the rules** — lock order, all-or-nothing locking, what goes in one transaction (state change **+** outbox), and which path is a compensation. It depends only on **interfaces**, never on Redis/JDBC/Kafka directly.

### B3.4 Interfaces (ports) — and why each exists

```java
interface SeatLockManager {
    boolean tryLock(long showId, String seatNo, long userId, int ttlSec);
    void release(long showId, String seatNo, long userId);
    void releaseAll(long showId, List<String> seatNos, long userId);
}

interface IdempotencyStore {
    <T> T execute(String key, Supplier<T> action);   // run-once-or-replay wrapper
}

interface SeatRepository {
    int lockSeats(long showId, List<String> seatNos, long userId, Instant expiry); // returns rows affected
    int markBooked(long showId, List<String> seatNos, long userId);
    int releaseSeats(long showId, List<String> seatNos, long userId);
    List<Seat> findExpiredLocks(Instant now, int limit);
}

interface BookingRepository {
    Booking insertPending(Booking b);              // relies on UNIQUE(idempotency_key)
    Booking findById(long bookingId);
    Booking findByIdempotencyKey(String key);
    int     updateStatus(long bookingId, BookingStatus s);
}

interface OutboxRepository {
    void append(OutboxEvent e);                     // MUST run in caller's transaction
}

interface PaymentPort {
    void requestPayment(long bookingId, BigDecimal amount, String method); // async
    PaymentStatus queryStatus(String gatewayRef);                          // for reconciliation
}
```

| Interface | Significance / why it exists |
| --- | --- |
| **`SeatLockManager`** | Abstracts the **fast distributed lock** (Redis `SET NX`). Lets us swap Redis ↔ another store, or disable the gate, without touching business logic. Encapsulates **safe release** (delete only if still owner). |
| **`IdempotencyStore`** | Wraps any write so a **retried request replays the stored result** instead of re-executing. Centralizes the dedup concern so every endpoint gets it uniformly (the `execute(key, action)` shape). |
| **`SeatRepository`** | The **correctness boundary** — its `lockSeats` returns *rows affected*, which is how the service detects the single winner. Hides the atomic conditional `UPDATE` and the expiry sweep query. |
| **`BookingRepository`** | Owns the **booking lifecycle persistence** and leans on `UNIQUE(idempotency_key)` as the DB-level dedup guarantee (defense in depth behind `IdempotencyStore`). |
| **`OutboxRepository`** | Guarantees the **event is written in the same DB transaction** as the state change → no lost events. Existence as a separate port makes the "atomic state + event" contract explicit. |
| **`PaymentPort`** | Isolates the **unreliable external dependency**. `requestPayment` (async/saga) keeps payment off the user path; `queryStatus` enables **reconciliation** of UNKNOWN payments. Swappable per gateway. |

### B3.5 Adapters (implementations)

```java
class RedisSeatLockManager implements SeatLockManager { /* SET NX EX, Lua safe-del */ }
class JdbcSeatRepository    implements SeatRepository  { /* atomic UPDATE ... WHERE status='AVAILABLE' */ }
class JdbcBookingRepository implements BookingRepository{ /* INSERT w/ UNIQUE(idempotency_key) */ }
class JdbcOutboxRepository  implements OutboxRepository { /* INSERT into outbox (same TX) */ }
class KafkaPaymentAdapter   implements PaymentPort      { /* emit PaymentRequested; consume result */ }
class RedisIdempotencyStore implements IdempotencyStore { /* SET NX IN_PROGRESS → SUCCESS replay */ }
```

> **Background workers (not in the request path):**
> - `OutboxPublisher` — polls `outbox` (`FOR UPDATE SKIP LOCKED`) → Kafka → marks `SENT`.
> - `LockExpiryJob` — scheduled sweep calling `SeatRepository.findExpiredLocks` / release.
> - `PaymentReconciliationJob` — settles `UNKNOWN`/`PENDING` payments via `PaymentPort.queryStatus`.

---

## B4. State Machines

### Seat

```
        lockSeats()              confirm (paid)
AVAILABLE ─────────► LOCKED ───────────────► BOOKED
    ▲                  │
    │  release()/expiry│  (payment fail / timeout / cancel)
    └──────────────────┘
```

### Booking

```
            create            payment SUCCESS
   (none) ─────────► PENDING ──────────────► CONFIRMED
                       │                         │ cancel
                       │ payment FAILED          ▼
                       └──────────► FAILED    CANCELLED
```

### Payment

```
INITIATED ──► SUCCESS
   │  ├────► FAILED
   │  └────► UNKNOWN ──(reconcile)──► SUCCESS | FAILED
SUCCESS ──(cancel)──► REFUNDED
```

---

## B5. Core Algorithms

### Lock seats (atomic, all-or-nothing)

```
lockSeats(showId, seatNos[], userId):
    # 1. (optional) Redis fast gate per seat
    for s in sorted(seatNos):
        if not redis.set("seat_lock:{showId}:{s}", userId, NX, EX=300):
            rollback any redis locks acquired
            return CONFLICT(unavailable=s)

    # 2. DB authoritative lock — ONE statement, all-or-nothing
    rows = UPDATE seat
           SET status='LOCKED', locked_by=userId,
               lock_expiry=now()+5min, version=version+1
           WHERE show_id=showId AND seat_number IN (seatNos)
             AND status='AVAILABLE'

    if rows != len(seatNos):
        # partial → undo
        UPDATE seat SET status='AVAILABLE', locked_by=NULL
          WHERE show_id=showId AND seat_number IN (seatNos) AND locked_by=userId
        release redis locks
        return CONFLICT

    return OK(expiresAt)
```

### Confirm (on payment success)

```
confirmBooking(bookingId):
    BEGIN TX
        markBooked(showId, seatNos, userId)           # status BOOKED
        updateStatus(bookingId, CONFIRMED)
        outbox.append(BOOKING_CONFIRMED, payload)     # same TX
    COMMIT
    release redis locks
```

### Release (on failure) + expiry sweep

```
releaseSeats(showId, seatNos, userId):
    UPDATE seat SET status='AVAILABLE', locked_by=NULL
      WHERE show_id=showId AND seat_number IN (seatNos)
        AND locked_by=userId            # ⚠️ condition prevents stealing another's lock
    redis.del(...)                       # only if still owner

# Scheduled every ~1 min
expirySweep():
    UPDATE seat SET status='AVAILABLE', locked_by=NULL
      WHERE status='LOCKED' AND lock_expiry < now()
```

---

## B6. Sequence — Happy Path (async payment via Saga)

```
Client    Gateway   Booking      Redis     DB        Payment    Kafka    Notify
  │  lock   │          │           │        │           │         │         │
  ├────────►├─────────►│ SET NX───►│        │           │         │         │
  │         │          ├─ UPDATE seats LOCKED ─────────►│         │         │
  │◄────────┤◄─────────┤ 200 lockId│        │           │         │         │
  │ /bookings          │           │        │           │         │         │
  ├────────►├─────────►│ insert booking PENDING ───────►│         │         │
  │         │          ├─ emit PaymentRequested ───────────────────►│       │
  │◄────────┤◄─────────┤ 201 PENDING        │           │         │         │
  │         │          │           │        │  process  │◄────────┤         │
  │         │          │◄── PaymentSuccess ─────────────┤         │         │
  │         │          ├─ TX: seats BOOKED + booking CONFIRMED + outbox ►DB │
  │         │          ├─ outbox → BOOKING_CONFIRMED ──────────────►│──────►│ SMS/QR
  │ poll    │          │           │        │           │         │         │
  ├────────►├─────────►│ 200 CONFIRMED      │           │         │         │
```

---

## B7. Sequence — Payment Failure (compensation)

```
Booking: booking=PENDING, seats=LOCKED
Payment: PaymentFailed ──► Booking consumes
Booking TX:
    releaseSeats()  → seats AVAILABLE   (WHERE locked_by = user)
    updateStatus(booking, FAILED)
    outbox.append(BOOKING_FAILED)
Redis: del seat_lock:*
(If server crashed before release → expiry sweep frees seats)
```

---

## B8. Concurrency & Correctness Summary

| Concern | Mechanism |
| --- | --- |
| Double booking | Atomic `UPDATE ... WHERE status='AVAILABLE'` (single winner) |
| Multi-seat atomicity | One `IN (...)` statement; check `rows == count` |
| Deadlocks | Single statement (or sorted-order acquisition) |
| Hot-seat contention | Redis `SET NX` gate before DB |
| Duplicate requests | `Idempotency-Key` + `UNIQUE(idempotency_key)` |
| Abandoned locks | `lock_expiry` + scheduled sweep |
| Lost events | Outbox in same TX as booking update |
| Duplicate events | Consumers idempotent (dedup on `event_id`) |
| Payment ambiguity | webhook + reconciliation job; treat timeout as UNKNOWN |

---

## B9. Caching Design

| Key | Value | TTL | Notes |
| --- | --- | --- | --- |
| `seat_lock:{showId}:{seatNo}` | userId | 300s | atomic `SET NX`; per-show scoped |
| `seatmap:{showId}` | seat statuses snapshot | short (1–5s) | browse path; invalidated on booking |
| `idem:{userId}:{key}` | booking result | 24–72h | dedup retries |
| `nowshowing:{cityId}` | listings | minutes | browse, eventually consistent |

---

## B10. Error Handling & Edge Cases

| Case | Handling |
| --- | --- |
| Partial seat lock | Roll back all; return `409` with unavailable seats |
| Redis up, DB says booked | Release Redis, return "already booked" (DB wins) |
| Redis lock expires mid-pay | DB conditional update still prevents double booking |
| Pay timeout (UNKNOWN) | Don't fail; reconcile via webhook/gateway query |
| Crash before release | Expiry sweep frees seats |
| Duplicate webhook | Idempotent payment update (dedup on `gatewayRef`) |
| Booking confirmed, event unsent | Outbox retries until SENT |

---

## B11. Cheat-Sheet Mapping (HLD ↔ LLD)

| Interview ask | HLD answer | LLD answer |
| --- | --- | --- |
| "Draw the system" | A2 architecture, A3 services | — |
| "Prevent double booking" | Booking owns seat state (A3) | B5 atomic lock, B8 |
| "Handle payment" | Async Saga (A4), Payment Service | B4 state machines, B7 |
| "Scale for a blockbuster" | A6 waiting room/sharding | B9 caching, B1 sharding |
| "Schema?" | A5 storage | B1 DDL + indexes |

---

## B12. Design Patterns (that can be used)

| Pattern | Where in this design | Why |
| --- | --- | --- |
| **Optimistic Locking / CAS** | Seat lock: `UPDATE ... WHERE status='AVAILABLE'` + `version` | Single winner without heavy locks — the core anti-double-book |
| **Idempotency Key** | Lock, booking, payment, cancel (unique constraints) | Safe retries; no duplicate holds/bookings/charges |
| **Saga / Orchestration** | Lock → pay → confirm, with compensation (release seats / refund) | Distributed transaction across Booking+Payment without 2PC |
| **Outbox** | `outbox` written in the same TX as the state change | No lost events between DB commit and Kafka |
| **State** | Seat, Booking, Payment state machines (B4) | Enforce legal transitions |
| **Ports & Adapters (Hexagonal)** | `SeatLockManager`, `SeatRepository`, `PaymentPort`, `OutboxRepository` | Swap Redis/JDBC/gateway; testable core |
| **Repository** | `SeatRepository`, `BookingRepository` | Abstract persistence |
| **Strategy** | Locking strategy (pessimistic/optimistic/Redis-hybrid), pricing (category multiplier), refund policy | Swap algorithms/policies |
| **Token Bucket / Leaky Bucket** | Virtual waiting room + gateway rate limiting | Shape blockbuster admission |
| **Circuit Breaker + Retry** | `PaymentPort` calls to the gateway | Fail fast + resilience on a flaky dependency |
| **Cache-Aside** | `seatmap:{showId}`, now-showing listings | Read scale on the browse path |
| **CQRS** | Search/browse read model (Elasticsearch) vs booking write model (SQL) | Optimized reads independent of the write path |
| **Producer-Consumer** | `OutboxPublisher`, `LockExpiryJob`, `PaymentReconciliationJob` | Async background work off the request path |
| **Observer / Pub-Sub** | Booking events → Notification / Analytics / Invoice | Decouple downstream consumers |
| **Template Method** | Common worker skeleton, job-specific step | Reuse background-job flow |
| **Factory** | Payment-provider / notification-channel creation | Extensible providers |

> **How to say it:** "The correctness core is **optimistic locking + idempotency**; the failure handling is **saga + compensation**; reliable eventing is **outbox**; the architecture is **hexagonal (ports & adapters)** so Redis/DB/gateway are swappable; and spikes are handled with **token-bucket rate limiting + a virtual waiting room**."

---

> See **BookMyShow — System Design** for deeper rationale, **Idempotency** and **Outbox & Saga** notes for the supporting patterns.
