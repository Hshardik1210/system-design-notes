# Food Ordering & Delivery — HLD & LLD

> Companion to **Food Ordering & Delivery — System Design**. **Part A: High-Level Design (HLD)** — architecture. **Part B: Low-Level Design (LLD)** — **all tables**, **full API contracts**, **class design**, **design patterns**, state machines, algorithms, sequences.

---

## Contents

- [A1. Scope & Consistency Stance](#a1-scope--consistency-stance)
- [A2. Architecture Overview](#a2-architecture-overview)
- [A3. Services & Responsibilities](#a3-services--responsibilities)
- [A4. Communication — Sync vs Async](#a4-communication--sync-vs-async)
- [A5. Storage Strategy](#a5-storage-strategy)
- [A6. Tech Stack](#a6-tech-stack)
- [B1. Database Schema — ALL Tables (DDL)](#b1-database-schema--all-tables-ddl)
- [B2. API Contracts (complete)](#b2-api-contracts-complete)
- [B3. Class Design](#b3-class-design)
- [B4. Design Patterns Used / Applicable](#b4-design-patterns-used--applicable)
- [B5. State Machines](#b5-state-machines)
- [B6. Core Algorithms](#b6-core-algorithms)
- [B7. Sequences](#b7-sequences)
- [B8. Concurrency & Correctness](#b8-concurrency--correctness)
- [B9. Caching Design](#b9-caching-design)
- [B10. Error Handling & Edge Cases](#b10-error-handling--edge-cases)

---

# PART A — High-Level Design (HLD)

## A1. Scope & Consistency Stance

- **Functional:** discover → cart → order → pay → restaurant accepts → dispatch rider → live track → deliver → rate.
- **Consistency:** **strong** for orders/payments; **eventual** for discovery, tracking, ratings.
- Order core ≈ e-commerce; the unique layer is **hyperlocal discovery + real-time dispatch + live tracking**. Full rationale in the main design note.

## A2. Architecture Overview

```
Customer/Restaurant/Rider apps → API Gateway
   ├── Discovery/Search  (Elasticsearch + Redis GEO)
   ├── Catalog/Menu      (RDBMS + cache)
   ├── Cart              (Redis)
   ├── Order Service     (RDBMS)  ── owns state machine
   ├── Payment Service   (RDBMS + gateway)
   ├── Dispatch Service  (Redis GEO rider index)
   ├── Location Service  (Redis GEO + time-series)
   ├── Tracking (WebSocket)
   └── Notification Service
                 │
              Kafka (event backbone: ORDER_PLACED, ORDER_READY, RIDER_ASSIGNED, DELIVERED)
```

## A3. Services & Responsibilities

| Service | Owns | Store |
| --- | --- | --- |
| Discovery/Search | geo restaurant index, ranking | ES + Redis |
| Catalog/Menu | menus, prices, availability | RDBMS + cache |
| Cart | pre-checkout cart | Redis |
| Order | order lifecycle + state machine | RDBMS |
| Payment | charge/refund/wallet | RDBMS + gateway |
| Dispatch | rider↔order matching, offers, batching | Redis GEO |
| Location | rider GPS ingest + latest position | Redis + TS |
| Tracking | push status + location to apps | Redis (conn map) |
| Restaurant | accept/reject, prep, menu mgmt | RDBMS |
| Notification | multi-channel updates | (see Notification note) |
| Rating | reviews + aggregate | RDBMS |

## A4. Communication — Sync vs Async

| Interaction | Style |
| --- | --- |
| Client → Order (place order) | **Sync** (user waits, idempotent) |
| Order → Dispatch/Notification/Restaurant | **Async (Kafka events)** |
| Rider GPS → Location | **Async fire-and-forget** (high frequency) |
| Location/Order → Customer app | **WebSocket push** |
| Services → Payment/Maps gateway | **Sync HTTP** (behind circuit breaker) |

## A5. Storage Strategy

| Need | Choice |
| --- | --- |
| Orders/payments (ACID) | RDBMS (sharded by region/customer) |
| Menu/catalog | RDBMS + Redis cache |
| Discovery index | Elasticsearch (geo + text), rebuilt via CDC |
| Rider positions / geo queries | Redis GEO + latest-value + TTL |
| Location history | Time-series / warehouse (analytics only) |
| Cart, sessions, rate limits | Redis |
| Event backbone | Kafka |

## A6. Tech Stack

Java/Spring Boot or Go · PostgreSQL (sharded) · Redis (GEO + cache) · Elasticsearch · Kafka · WebSocket service (Go/Node) · Maps/routing (Google Maps/OSRM) · Payment gateway (Razorpay/Stripe) · Kubernetes.

---

# PART B — Low-Level Design (LLD)

## B1. Database Schema — ALL Tables (DDL)

```sql
-- ============ Users & addresses ============
CREATE TABLE users (
    user_id     BIGINT PRIMARY KEY,
    name        VARCHAR(255),
    email       VARCHAR(255) UNIQUE,
    phone       VARCHAR(20) UNIQUE,
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE addresses (
    address_id  BIGINT PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users(user_id),
    label       VARCHAR(50),            -- HOME, WORK
    line1       TEXT, line2 TEXT, city VARCHAR(100), pincode VARCHAR(10),
    lat         DOUBLE PRECISION NOT NULL,
    lng         DOUBLE PRECISION NOT NULL,
    is_default  BOOLEAN DEFAULT FALSE
);
CREATE INDEX idx_addr_user ON addresses(user_id);

-- ============ Restaurants & menu ============
CREATE TABLE restaurants (
    restaurant_id BIGINT PRIMARY KEY,
    name          TEXT NOT NULL,
    lat           DOUBLE PRECISION NOT NULL,
    lng           DOUBLE PRECISION NOT NULL,
    geohash       VARCHAR(12),           -- precomputed for geo lookups
    is_open       BOOLEAN NOT NULL DEFAULT TRUE,
    prep_time_avg INT,                   -- minutes, for ETA
    rating        NUMERIC(2,1) DEFAULT 0,
    rating_count  BIGINT DEFAULT 0,
    serviceable_radius_m INT,            -- or polygon in restaurant_zones
    city_id       BIGINT                 -- shard/partition key
);
CREATE INDEX idx_rest_geohash ON restaurants(geohash) WHERE is_open = TRUE;

CREATE TABLE restaurant_hours (
    restaurant_id BIGINT NOT NULL REFERENCES restaurants(restaurant_id),
    day_of_week   SMALLINT NOT NULL,     -- 0-6
    open_time     TIME, close_time TIME,
    PRIMARY KEY (restaurant_id, day_of_week)
);

CREATE TABLE restaurant_zones (          -- delivery serviceability polygons (optional)
    restaurant_id BIGINT NOT NULL,
    polygon       GEOGRAPHY(POLYGON)     -- PostGIS
);

CREATE TABLE menu_categories (
    category_id   BIGINT PRIMARY KEY,
    restaurant_id BIGINT NOT NULL,
    name          TEXT NOT NULL,
    sort_order    INT DEFAULT 0
);

CREATE TABLE menu_items (
    item_id       BIGINT PRIMARY KEY,
    restaurant_id BIGINT NOT NULL,
    category_id   BIGINT,
    name          TEXT NOT NULL,
    description   TEXT,
    price         INT NOT NULL,          -- money in paise/cents
    is_available  BOOLEAN NOT NULL DEFAULT TRUE,  -- sold-out toggle
    is_veg        BOOLEAN
);
CREATE INDEX idx_item_restaurant ON menu_items(restaurant_id);

CREATE TABLE item_customizations (       -- add-ons / variants (e.g. size, extra cheese)
    customization_id BIGINT PRIMARY KEY,
    item_id       BIGINT NOT NULL REFERENCES menu_items(item_id),
    name          TEXT NOT NULL,
    price_delta   INT NOT NULL DEFAULT 0,
    group_name    VARCHAR(50),           -- e.g. "Size" (radio) vs "Toppings" (multi)
    is_required   BOOLEAN DEFAULT FALSE
);

-- ============ Delivery partners (riders) ============
CREATE TABLE delivery_partners (
    rider_id      BIGINT PRIMARY KEY,
    name          VARCHAR(255),
    phone         VARCHAR(20),
    vehicle_type  VARCHAR(20),           -- BIKE, CYCLE, CAR
    status        VARCHAR(20) NOT NULL DEFAULT 'OFFLINE',  -- OFFLINE, ONLINE, ON_DELIVERY
    rating        NUMERIC(2,1) DEFAULT 0,
    city_id       BIGINT
);
-- Live position lives in Redis GEO, NOT here (75k writes/sec)

CREATE TABLE rider_shifts (              -- online sessions for payroll/analytics
    shift_id      BIGINT PRIMARY KEY,
    rider_id      BIGINT NOT NULL,
    started_at    TIMESTAMP, ended_at TIMESTAMP
);

-- ============ Cart (usually Redis; table shown for completeness) ============
CREATE TABLE carts (
    cart_id       BIGINT PRIMARY KEY,
    user_id       BIGINT NOT NULL,
    restaurant_id BIGINT,
    updated_at    TIMESTAMP NOT NULL DEFAULT now()
);
CREATE TABLE cart_items (
    cart_id BIGINT, item_id BIGINT, qty INT, customizations JSONB,
    PRIMARY KEY (cart_id, item_id)
);

-- ============ Orders ============
CREATE TABLE orders (
    order_id        BIGINT PRIMARY KEY,        -- Snowflake / distributed ID
    idempotency_key VARCHAR(255) NOT NULL,
    customer_id     BIGINT NOT NULL,
    restaurant_id   BIGINT NOT NULL,
    address_id      BIGINT NOT NULL,
    status          VARCHAR(40) NOT NULL,      -- see B5
    subtotal        INT NOT NULL,
    delivery_fee    INT NOT NULL,
    surge_fee       INT DEFAULT 0,
    tax             INT DEFAULT 0,
    discount        INT DEFAULT 0,
    total           INT NOT NULL,
    payment_status  VARCHAR(30) NOT NULL,      -- PENDING, PAID, REFUNDED, FAILED
    rider_id        BIGINT,
    eta_at          TIMESTAMP,
    placed_at       TIMESTAMP NOT NULL DEFAULT now(),
    accepted_at     TIMESTAMP, ready_at TIMESTAMP, picked_at TIMESTAMP, delivered_at TIMESTAMP,
    UNIQUE (idempotency_key)
);
CREATE INDEX idx_orders_customer ON orders(customer_id, placed_at DESC);
CREATE INDEX idx_orders_rest_active ON orders(restaurant_id)
    WHERE status NOT IN ('DELIVERED','CANCELLED');

CREATE TABLE order_items (
    order_id  BIGINT NOT NULL REFERENCES orders(order_id),
    item_id   BIGINT NOT NULL,
    name      TEXT NOT NULL,   -- snapshot
    price     INT NOT NULL,    -- snapshot
    qty       INT NOT NULL,
    customizations JSONB
);

CREATE TABLE order_status_history (
    order_id  BIGINT NOT NULL,
    status    VARCHAR(40) NOT NULL,
    actor     VARCHAR(30),     -- SYSTEM, RESTAURANT, RIDER, CUSTOMER
    at        TIMESTAMP NOT NULL DEFAULT now()
);

-- ============ Dispatch / assignment ============
CREATE TABLE order_assignments (          -- each offer made to a rider
    assignment_id BIGINT PRIMARY KEY,
    order_id      BIGINT NOT NULL,
    rider_id      BIGINT NOT NULL,
    status        VARCHAR(20) NOT NULL,   -- OFFERED, ACCEPTED, REJECTED, TIMEOUT, CANCELLED
    offered_at    TIMESTAMP NOT NULL DEFAULT now(),
    responded_at  TIMESTAMP
);
CREATE INDEX idx_assign_order ON order_assignments(order_id);

-- ============ Payments & refunds ============
CREATE TABLE payments (
    payment_id    BIGINT PRIMARY KEY,
    order_id      BIGINT NOT NULL REFERENCES orders(order_id),
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    amount        INT NOT NULL,
    method        VARCHAR(30),           -- UPI, CARD, WALLET, COD
    status        VARCHAR(30) NOT NULL,  -- INITIATED, SUCCESS, FAILED
    provider_ref  VARCHAR(255),
    created_at    TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE refunds (
    refund_id     BIGINT PRIMARY KEY,
    order_id      BIGINT NOT NULL,
    payment_id    BIGINT NOT NULL,
    amount        INT NOT NULL,
    reason        VARCHAR(100),
    status        VARCHAR(30) NOT NULL,  -- INITIATED, DONE, FAILED
    created_at    TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE wallets (
    user_id       BIGINT PRIMARY KEY,
    balance       INT NOT NULL DEFAULT 0
);

-- ============ Coupons / offers ============
CREATE TABLE coupons (
    code          VARCHAR(50) PRIMARY KEY,
    type          VARCHAR(20),           -- FLAT, PERCENT
    value         INT NOT NULL,
    min_order     INT DEFAULT 0,
    max_discount  INT,
    valid_from    TIMESTAMP, valid_to TIMESTAMP,
    usage_limit   INT, per_user_limit INT DEFAULT 1,
    is_active     BOOLEAN DEFAULT TRUE
);
CREATE TABLE coupon_redemptions (
    code     VARCHAR(50), user_id BIGINT, order_id BIGINT, redeemed_at TIMESTAMP,
    PRIMARY KEY (code, order_id)
);

-- ============ Ratings & reviews ============
CREATE TABLE reviews (
    review_id     BIGINT PRIMARY KEY,
    order_id      BIGINT NOT NULL,
    customer_id   BIGINT NOT NULL,
    restaurant_rating SMALLINT,          -- 1-5
    rider_rating  SMALLINT,
    comment       TEXT,
    created_at    TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (order_id)                    -- one review per order
);

-- ============ Outbox (reliable event publishing) ============
CREATE TABLE outbox (
    id            BIGINT PRIMARY KEY,
    aggregate     VARCHAR(50),           -- ORDER, PAYMENT
    event_type    VARCHAR(50),
    payload       JSONB,
    published     BOOLEAN DEFAULT FALSE,
    created_at    TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_outbox_unpublished ON outbox(created_at) WHERE published = FALSE;
```

> **Table checklist:** users, addresses, restaurants, restaurant_hours, restaurant_zones, menu_categories, menu_items, item_customizations, delivery_partners, rider_shifts, carts, cart_items, orders, order_items, order_status_history, order_assignments, payments, refunds, wallets, coupons, coupon_redemptions, reviews, outbox. Live rider location = **Redis GEO** (not a table).

---

## B2. API Contracts (complete)

### Customer

```
POST   /v1/auth/otp  ·  POST /v1/auth/verify
GET    /v1/addresses            POST /v1/addresses           PUT/DELETE /v1/addresses/{id}
GET    /v1/restaurants?lat=&lng=&filters=&sort=eta           # discovery
GET    /v1/restaurants/{id}/menu
POST   /v1/cart/items           PATCH /v1/cart/items/{id}    DELETE /v1/cart/items/{id}
POST   /v1/cart/apply-coupon    { code }
POST   /v1/orders               (Idempotency-Key)            # place order
GET    /v1/orders/{id}          WS /v1/orders/{id}/track     # snapshot + live
POST   /v1/orders/{id}/cancel
GET    /v1/orders?cursor=&limit=20                           # history
POST   /v1/orders/{id}/reorder
POST   /v1/orders/{id}/review   { restaurantRating, riderRating, comment }
```

### Restaurant

```
GET    /v1/restaurant/orders?status=NEW
POST   /v1/restaurant/orders/{id}/accept   { prepTimeMins }
POST   /v1/restaurant/orders/{id}/reject   { reason }
POST   /v1/restaurant/orders/{id}/ready
PATCH  /v1/restaurant/menu/items/{id}      { available: false }   # sold out
PATCH  /v1/restaurant/status               { isOpen: false }
```

### Delivery partner (rider)

```
POST   /v1/riders/{id}/status              { online: true }
POST   /v1/riders/{id}/location            { lat, lng, ts }        # high-frequency
GET    /v1/riders/{id}/offers              WS for real-time offers
POST   /v1/riders/{id}/offers/{oid}/accept · /reject
POST   /v1/orders/{id}/arrived-at-restaurant
POST   /v1/orders/{id}/picked-up
POST   /v1/orders/{id}/delivered
GET    /v1/riders/{id}/earnings
```

### Internal / events (Kafka)

```
ORDER_PLACED · ORDER_ACCEPTED · ORDER_REJECTED · ORDER_READY
RIDER_ASSIGNED · ORDER_PICKED_UP · ORDER_DELIVERED · ORDER_CANCELLED
PAYMENT_SUCCESS · PAYMENT_FAILED · REFUND_INITIATED
```

---

## B3. Class Design

Layered **Hexagonal (Ports & Adapters)** design — domain logic in the center, infra behind ports.

```
Controllers / Event Consumers  (HTTP + Kafka adapters)
        │
OrderOrchestrator  (the saga coordinator)
        │
   ┌────┴──────┬───────────┬────────────┬──────────────┐
PricingSvc  OrderRepo  PaymentPort  DispatchPort   QueuePort(Kafka)
   │                                    │
PricingStrategy chain            DispatchStrategy (Strategy)
                                        │
                                 GeoIndexPort (Redis GEO)
```

```java
// ---------- Enums ----------
enum OrderStatus { PENDING_PAYMENT, PLACED, ACCEPTED, REJECTED, PREPARING,
                   RIDER_ASSIGNED, RIDER_AT_RESTAURANT, OUT_FOR_DELIVERY,
                   DELIVERED, CANCELLED, PAYMENT_FAILED }
enum RiderStatus { OFFLINE, ONLINE, ON_DELIVERY }
enum PaymentStatus { INITIATED, SUCCESS, FAILED, REFUNDED }
enum Actor { SYSTEM, RESTAURANT, RIDER, CUSTOMER }

// ---------- Entities ----------
class Order {
    long orderId; String idempotencyKey; long customerId, restaurantId, addressId;
    OrderStatus status; Money subtotal, deliveryFee, surge, tax, discount, total;
    Long riderId; Instant etaAt, placedAt; List<OrderItem> items;
    void transitionTo(OrderStatus next, Actor by);   // guarded by state machine
}
class MenuItem { long itemId, restaurantId; String name; Money price; boolean available; }
class Restaurant { long id; double lat, lng; boolean open; int prepTimeAvg; }
class DeliveryPartner { long riderId; RiderStatus status; GeoPoint location; }

// ---------- Orchestrator (Saga) ----------
class OrderOrchestrator {
    OrderRepository repo; PricingService pricing; PaymentPort payment;
    QueuePort queue; PreferencePort prefs;

    Order placeOrder(PlaceOrderCmd cmd) {
        return repo.findByKey(cmd.idempotencyKey)         // idempotency
            .orElseGet(() -> {
                validate(cmd);                             // Chain of Responsibility
                Money total = pricing.price(cmd);          // Strategy + Decorator
                Order o = repo.insert(buildOrder(cmd, total, PENDING_PAYMENT));
                PaymentResult r = payment.charge(o);       // Port (circuit breaker)
                if (r.success) { o.transitionTo(PLACED, SYSTEM); queue.publish("ORDER_PLACED", o); }
                else           { o.transitionTo(PAYMENT_FAILED, SYSTEM); }
                return repo.save(o);
            });
    }
}

// ---------- Dispatch (Strategy + geo) ----------
interface DispatchStrategy { Optional<Long> pickRider(Order o, List<DeliveryPartner> candidates); }
class NearestIdleStrategy   implements DispatchStrategy { /* greedy */ }
class BatchOptimizeStrategy implements DispatchStrategy { /* min-cost matching */ }

class DispatchService {
    GeoIndexPort geo; DispatchStrategy strategy; AssignmentRepo assignments;
    void onOrderReadyish(Order o) {
        var candidates = geo.nearbyRiders(o.restaurantLat, o.restaurantLng, 3000);
        strategy.pickRider(o, candidates).ifPresent(riderId -> offer(o, riderId));
    }
    boolean claimRider(long riderId, long orderId) {        // atomic
        return geo.tryLock("rider:"+riderId, orderId, Duration.ofSeconds(30));
    }
}

// ---------- Ports (interfaces) ----------
interface PaymentPort   { PaymentResult charge(Order o); RefundResult refund(long orderId, Money amt); }
interface GeoIndexPort  { List<DeliveryPartner> nearbyRiders(double lat,double lng,int radiusM);
                          boolean tryLock(String key,long owner,Duration ttl); }
interface RoutingPort   { Duration eta(GeoPoint from, GeoPoint to); }
interface QueuePort     { void publish(String topic, Object event); }
interface OrderRepository { Optional<Order> findByKey(String k); Order insert(Order o); Order save(Order o); }
interface NotificationPort { void notify(long userId, String type, Map<String,Object> data); }
```

---

## B4. Design Patterns Used / Applicable

> This is the section that maps **which design patterns fit where** — the ones you *can* use, with the reason.

| Pattern | Where it applies | Why |
| --- | --- | --- |
| **State** | `Order` lifecycle (`transitionTo`) | Encapsulate legal transitions; reject illegal ones (e.g. can't deliver before pickup) |
| **Strategy** | Dispatch (`NearestIdle` vs `BatchOptimize`), pricing, ETA model | Swap algorithms without touching callers; A/B test |
| **Chain of Responsibility** | Order **validation pipeline** (restaurant open → items available → serviceable → price unchanged → fraud) | Each check is a link; add/reorder easily; short-circuit on first failure |
| **Decorator** | **Price composition** (base → + surge → + tax → − discount → + packaging) | Stack pricing modifiers cleanly |
| **Observer / Pub-Sub** | Kafka event backbone (`ORDER_PLACED` → dispatch, notification, analytics) | Decouple producers from many consumers |
| **Saga / Orchestration** | Order fulfillment (payment → accept → dispatch → deliver) with **compensations** (refund on reject) | Manage a distributed multi-step transaction without 2PC |
| **Outbox** | Reliable event publishing from Order/Payment | Avoid dual-write loss between DB commit and Kafka |
| **Repository** | `OrderRepository`, menu, rider repos | Abstract persistence; testable domain |
| **Ports & Adapters (Hexagonal)** | `PaymentPort`, `GeoIndexPort`, `RoutingPort`, `QueuePort` | Swap providers (Razorpay↔Stripe, Google Maps↔OSRM) without domain changes |
| **Factory** | Notification channel / payment provider creation | Build the right handler per type |
| **Circuit Breaker** | Calls to payment gateway, maps/routing | Fail fast when a provider degrades; protect the system |
| **Builder** | Constructing `Order` / complex DTOs | Readable, immutable object creation |
| **Command** | Rider/restaurant actions (accept, reject, pickup) as commands | Uniform handling, queueable, auditable |
| **Singleton / Object Pool** | DB/HTTP connection pools, Kafka producer | Reuse expensive resources |
| **Publish-Subscribe + WebSocket (Fan-out)** | Live tracking push | One rider location → all subscribers of that order |
| **CQRS (lite)** | Write path (orders RDBMS) vs read path (discovery on ES) | Separate optimized read model from the write model |

---

## B5. State Machines

### Order

```
PENDING_PAYMENT ─pay ok→ PLACED ─accept→ ACCEPTED ─→ PREPARING ─→ RIDER_AT_RESTAURANT
      │ fail             │ reject                         (rider assigned in parallel)
      ▼                  ▼                                        │ picked up
 PAYMENT_FAILED      CANCELLED ◄─ cancel (customer/restaurant)    ▼
                                                            OUT_FOR_DELIVERY ─→ DELIVERED
```

### Payment

```
INITIATED ─→ SUCCESS ─(reject/cancel)→ REFUNDED
      └────→ FAILED
```

### Assignment (per rider offer)

```
OFFERED ─accept→ ACCEPTED
   │ reject / timeout → REJECTED/TIMEOUT → (offer next rider)
```

---

## B6. Core Algorithms

### Place order (Saga + validation chain + idempotency)

```
placeOrder(cmd):
    if exists(cmd.idempotencyKey): return existing        # idempotent
    validateChain(cmd)                                     # open? available? serviceable? price?
    total = price(cmd)                                     # strategy + decorator
    BEGIN TX
        order = insert(status=PENDING_PAYMENT)
        insert outbox(...)                                 # for reliable events
    COMMIT
    pay = payment.charge(order)                            # circuit breaker
    if pay.success: order→PLACED; emit ORDER_PLACED
    else:           order→PAYMENT_FAILED
```

### Dispatch (candidate → score → offer loop)

```
onReadyish(order):
    candidates = geo.nearbyRiders(restaurant, 3km) filter idle
    ranked = strategy.rank(candidates, order)             # distance, direction, load, ready time
    for rider in ranked:
        if claimRider(rider, order):                       # atomic Redis lock
            offer(rider); await accept (timeout Ns)
            if accepted: order→RIDER_ASSIGNED; break
            else: release lock; continue
```

### Live location ingest

```
onPing(riderId, lat, lng):
    GEOADD riders:online lng lat rider:{id}   # update index
    SET loc:rider:{id} {lat,lng,ts} EX 30     # latest value
    publish location to Kafka (sampled)        # for tracking + analytics
    # NEVER write to orders DB
```

### Cancellation + refund (compensation)

```
cancel(order):
    if status in [DELIVERED, OUT_FOR_DELIVERY]: reject or partial
    order→CANCELLED
    if payment==PAID: refund.initiate(order)   # saga compensation
    release rider lock if held; notify parties
```

---

## B7. Sequences

### Happy path

```
Customer  OrderSvc  Payment  Kafka  Restaurant  Dispatch  Rider  Customer(map)
   │ place  │        │        │         │          │        │        │
   ├───────►│ create PENDING  │         │          │        │        │
   │        ├─charge►│ ok      │         │          │        │        │
   │        ├─PLACED─────────►│─notify──►│ accept   │        │        │
   │        │        │        │◄─────────┤          │        │        │
   │        │        │        │─ORDER_READY(ish)───►│ match  │        │
   │        │        │        │          │          ├─offer─►│ accept │
   │        │        │        │◄─RIDER_ASSIGNED──────┤        │        │
   │        │        │        │          │ pickup   │        │        │
   │        │        │        │          │          │        ├─GPS───►│ live track
   │        │        │        │          │ delivered│        │        │
```

### Payment failure → no order progresses (compensation)

```
charge → FAILED → order=PAYMENT_FAILED → notify customer → (no dispatch)
```

---

## B8. Concurrency & Correctness

| Concern | Mechanism |
| --- | --- |
| Duplicate "Place Order" | `UNIQUE(idempotency_key)` on orders |
| Double charge | Idempotent payment (`UNIQUE` payment key) |
| Two orders grab same rider | Atomic Redis lock `rider:{id}` (`SET NX EX`) |
| Lost event after commit | **Outbox** + relay |
| Stale menu at checkout | Re-validate price/availability in the validation chain |
| Order state races (multi-actor) | State machine guards + `UPDATE ... WHERE status = <expected>` (optimistic) |
| Location firehose | Redis only; never RDBMS |
| Coupon over-use | `coupon_redemptions` unique + atomic decrement of usage limit |

---

## B9. Caching Design

| Key | Value | TTL |
| --- | --- | --- |
| `discovery:{geohash}:{filters}` | restaurant list | short (30–60s) |
| `menu:{restaurantId}` | menu + availability | minutes; invalidate on edit |
| `loc:rider:{id}` | latest GPS | 30s |
| `riders:online` (GEO set) | rider positions | live |
| `cart:{userId}` | cart | session |
| `ratelimit:{userId}` | counters | window |

---

## B10. Error Handling & Edge Cases

| Case | Handling |
| --- | --- |
| No riders available | Longer ETA / hold in dispatch queue; surge to attract riders |
| Restaurant no-accept timeout | Auto-cancel + refund; alert |
| Rider cancels mid-way | Re-dispatch; adjust ETA; notify |
| Payment ok, order-create crash | Outbox/reconciliation; auto-refund if unrecoverable |
| Duplicate tap | Idempotency key |
| Item sold out at checkout | Validation chain rejects; suggest alternatives |
| Redis/location down | Tracking degrades to last-known + ETA; orders unaffected |
| Dispatch double-assign | Atomic rider lock |

---

> See **Food Ordering & Delivery — System Design** for the HLD rationale, and the **Notification**, **Kafka**, **Idempotency**, and **Outbox & Saga** notes for supporting patterns.
