# Food Ordering & Delivery — HLD & LLD

> Companion to **Food Ordering & Delivery — System Design**. **Part A: High-Level Design (HLD)** — architecture. **Part B: Low-Level Design (LLD)** — **all tables**, **full API contracts**, **class design**, **design patterns**, state machines, algorithms, sequences.

> **How to read this doc:** each section has the dense interview summary first, then a **deep dive** (annotated Java/SQL/API, and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand.

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

### What are we building, and what does "consistency" mean here?

Picture the Swiggy/Zomato app. The whole journey is: **you search for nearby restaurants → add food to a cart → place + pay → the restaurant accepts and cooks → a driver picks it up → you watch the bike move on a map → it arrives → you rate it.** That's the entire system. Everything below is just "how do we build each of those steps so it scales to millions of hungry people."

**"Consistency"** = how fresh/correct the data has to be at the exact moment you read it. Two flavors:

- **Strong consistency** = "the number MUST be right, right now, no exceptions." Used for **money and orders**. If you paid ₹500, the system must never think you paid ₹0 or ₹1000, and an order must never be cooked twice or lost. Correctness beats speed.
- **Eventual consistency** = "it's fine if it's a second or two stale, as long as it catches up." Used for **discovery, live tracking, ratings**. If a restaurant's star rating shows 4.3 instead of the brand-new 4.31, or the driver dot is 3 seconds behind, nobody gets hurt. Freshness/speed beats perfect accuracy.

#### Q: Why not make *everything* strongly consistent to be safe?

Strong consistency is expensive — it needs locks, coordination, and often a single source of truth that everyone waits on. That's fine for the ~1 order you place, but the map is updating the driver's location 5×/second and thousands of people are browsing restaurants at once. Forcing all of that to be perfectly in-sync would make the app slow and fragile. So we spend the "expensive correctness" budget only where money is involved.

#### Q: What does "order core ≈ e-commerce" mean?

The cart → checkout → pay → fulfill flow is basically the same as Amazon. What makes food delivery *special* (and hard) is the three real-time, location-based pieces bolted on: finding restaurants **near you** (hyperlocal discovery), assigning a **nearby driver** in seconds (real-time dispatch), and showing the **bike moving live** (tracking). Those are the interesting parts of this doc.

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

### One big app, split into many small specialists

Instead of one giant program doing everything, we split the work into **microservices** — small programs that each do one job well. Each service owns its own database and they communicate by sending each other messages/events rather than sharing data directly.

**What each box does:**

| Box | Responsibility | Why it's separate |
| --- | --- | --- |
| **API Gateway** | Single entry point | One entrance; checks auth, routes to the right service |
| **Discovery/Search** | "What's open near me?" | Needs fast location + text search (special tools) |
| **Catalog/Menu** | Menus, prices, availability | Read a lot, changes rarely → cache it |
| **Cart** | Pre-checkout cart | Temporary, throwaway → fast memory (Redis) |
| **Order** | Order lifecycle | The heart; owns the order's state machine |
| **Payment** | Charges/refunds | Must be exact (money) |
| **Dispatch** | Matching drivers to orders | Needs "who's nearby?" fast |
| **Location** | Driver GPS ingest | Floods of pings → memory, not disk |
| **Tracking** | Live status/location push | Pushes updates to your phone |
| **Notification** | Multi-channel updates | Texts/emails/pushes "order accepted!" |

#### Q: What is Kafka (the "event backbone") doing at the bottom?

Kafka is an **event log / message broker**. When something important happens ("ORDER_PLACED"), the Order service **publishes an event** and moves on — it does *not* call every other service one by one. Whoever cares (Dispatch, Notification, Analytics) subscribes and reacts. This way the Order service doesn't need to know or wait for everyone downstream; it just publishes the event and gets back to work. (More in §A4.)

#### Q: Why so many separate databases instead of one?

Different jobs need different tools. Money needs a strict ledger (RDBMS). "Restaurants near me" needs a geo/search engine (Elasticsearch + Redis GEO). A throwaway cart needs fast memory (Redis). Forcing all of these into one database would make each job worse. This is the "**right tool for each job**" principle (detailed in §A5).

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

### Who owns what (and why "owns" matters)

Each service **owns** a slice of the data — it's the only one allowed to directly write to that slice. Everyone else must go through its API. This keeps write authority in one place: no other service can reach into another's database and change it; it must send a request.

**Concrete walk-through of one order's journey across services:**

```
You tap "Order"        → Order service creates the order (owns its status)
Order needs payment    → asks Payment service (owns money)
Order is placed        → announces on Kafka
Dispatch hears it      → asks Location "who's nearby?" then offers to a driver (owns matching)
Driver rides           → Location service ingests GPS pings (owns positions)
You open the map       → Tracking pushes the driver's dot to your phone (owns the live feed)
Delivered              → Rating service lets you leave a review (owns reviews)
```

#### Q: Why can't the Order service just update the driver's location itself?

Because that's not its job, and mixing jobs creates a mess. The Location service handles ~75,000 GPS pings/second (every driver, several times a second). If that firehose hit the Orders database, it would drown the actual orders. Keeping "owns positions" separate from "owns orders" means the location flood can't take down your ability to place orders. **Separation of concerns = a failure in one area doesn't sink the others.**

#### Q: The same store (RDBMS) appears many times — is that one database?

Not necessarily. "RDBMS" is the *type* of store (a SQL relational database). Each service typically has its **own** RDBMS instance/schema so they stay independent. They happen to pick the same *kind* of tool because their needs (transactions, relationships) are similar.

## A4. Communication — Sync vs Async

| Interaction | Style |
| --- | --- |
| Client → Order (place order) | **Sync** (user waits, idempotent) |
| Order → Dispatch/Notification/Restaurant | **Async (Kafka events)** |
| Rider GPS → Location | **Async fire-and-forget** (high frequency) |
| Location/Order → Customer app | **WebSocket push** |
| Services → Payment/Maps gateway | **Sync HTTP** (behind circuit breaker) |

### Sync vs async communication

There are two ways for services to talk:

- **Sync (synchronous)** = the caller **blocks and waits** for the reply. You can't continue until you hear back. Use it when you genuinely need the answer *now* (did the card charge succeed?).
- **Async (asynchronous)** = the caller **sends a message and moves on** (e.g. publishing to Kafka). You don't wait; the other side handles it whenever. Use it when the caller doesn't need to block (notifying the restaurant, telling analytics).

Placing the order is sync — the app waits until payment returns "success, order placed." But notifying the kitchen, the dispatcher, and the SMS system is async — the order flow doesn't wait for any of them to acknowledge.

```java
// SYNC — you wait for the result because you NEED it to proceed
PaymentResult r = paymentGateway.charge(order);   // blocks here until bank replies
if (r.success) { /* only now can we place the order */ }

// ASYNC — fire the event and immediately move on; others react later
kafka.publish("ORDER_PLACED", order);             // returns instantly; doesn't wait
// dispatch, notification, analytics each pick this up on their own time
```

#### Q: Why not just make everything sync? It's simpler to reason about.

Because sync **chains failures and adds waiting**. If placing an order had to synchronously call Payment, then Dispatch, then Notification, then Analytics — and the Notification service was slow or down — *your order would hang or fail* even though the notification is unimportant. Async decouples them: the order succeeds the moment payment clears, and everything else happens in the background. If Notification is down, it catches up later; your order is unaffected.

#### Q: Why is rider GPS "fire-and-forget"?

A driver's phone sends its location several times a second. If each ping waited for a confirmation, we'd waste huge effort on acknowledgements for data that's obsolete in one second anyway. So we fire it and forget it — if one ping is lost, the next one (a fraction of a second later) corrects it. Losing one GPS dot doesn't matter; speed does.

#### Q: What's the "circuit breaker" next to Payment/Maps?

External services (the bank's payment gateway, Google Maps) sometimes get slow or go down. A **circuit breaker** is like a fuse: if the payment gateway starts failing repeatedly, the breaker "trips" and we stop calling it for a while (failing fast with a clear error) instead of piling up thousands of stuck phone-calls that freeze our whole system. It protects us from a partner's outage.

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

### Picking the right store for each kind of data

Different data has different "shape" and access needs, so we store each in the tool built for it — the same reason you don't force transactions, full-text search, and high-frequency geo writes into one engine.

| Data | Tool | Why |
| --- | --- | --- |
| **Orders / payments** | RDBMS (SQL) | Needs ACID transactions ("all-or-nothing"); a ledger that never loses money |
| **Menu / catalog** | RDBMS + Redis cache | Rarely changes, read constantly → keep a fast cached copy |
| **Discovery index** | Elasticsearch | "cheap veg biryani near me, open now, rated 4+" is a fuzzy multi-filter search |
| **Rider positions** | Redis GEO | Must answer "who's within 3 km?" in milliseconds, updated constantly |
| **Location history** | Time-series / warehouse | Kept for later analysis, not for live use |
| **Cart / sessions** | Redis | Temporary, fast, disposable |
| **Event backbone** | Kafka | Event log carrying messages between services |

#### Q: What is "ACID" and why do orders/payments need it?

**ACID** = the four guarantees of a serious database transaction: **A**tomic (all steps happen or none do), **C**onsistent (rules never violated), **I**solated (concurrent transactions don't corrupt each other), **D**urable (once saved, it survives crashes). For money this is non-negotiable: charging your card and creating your order must **both** happen or **neither** — you should never be charged for an order that doesn't exist. Redis/Elasticsearch don't give strong ACID guarantees, which is exactly why we don't put orders/payments there.

#### Q: What does "sharded by region/customer" mean for orders?

**Sharding** = splitting one huge table across many databases so no single machine holds everything. For example, instead of one nationwide orders table, each city's orders live on their own shard — Mumbai's on the Mumbai shard, Delhi's on the Delhi shard. This spreads the load and keeps each database small and fast. The "shard key" (region or customer) decides which shard a given order lands on.

#### Q: What's "rebuilt via CDC" for the discovery index?

**CDC = Change Data Capture.** The source of truth for restaurants/menus is the RDBMS. Elasticsearch is a *copy* optimized for search. CDC is the mechanism that **automatically streams every change** (new restaurant, price update, sold-out toggle) from the RDBMS into Elasticsearch so the search index stays fresh — instead of someone manually re-uploading everything. It's a live feed that mirrors edits from the source RDBMS into the searchable copy.

## A6. Tech Stack

Java/Spring Boot or Go · PostgreSQL (sharded) · Redis (GEO + cache) · Elasticsearch · Kafka · WebSocket service (Go/Node) · Maps/routing (Google Maps/OSRM) · Payment gateway (Razorpay/Stripe) · Kubernetes.

### What each tool is, in one line

- **Java/Spring Boot or Go** — the programming languages/frameworks the services are written in (the "workers" doing the logic).
- **PostgreSQL** — the SQL database = the strict ledger for orders/payments.
- **Redis** — an in-memory (super fast) store used for carts, caches, and the "who's nearby" geo index.
- **Elasticsearch** — the search engine for restaurant discovery.
- **Kafka** — the event log/message backbone between services.
- **WebSocket service** — keeps a live, always-open connection to your phone so the map/status can be *pushed* instantly (vs your phone repeatedly polling "any update?").
- **Maps/routing (Google Maps/OSRM)** — computes driving routes and ETAs.
- **Payment gateway (Razorpay/Stripe)** — the external provider that actually charges cards/UPI.
- **Kubernetes** — the orchestrator that runs many copies of each service, restarts crashed ones, and scales them up during dinner rush.

#### Q: What is a WebSocket, and why not just refresh the page?

Normally your phone asks the server "any news?" over and over (polling) — wasteful and laggy. A **WebSocket** is a persistent, always-open two-way connection: once opened, the server can push to your phone *the instant* something changes (order accepted, driver moved) without being asked. That's how the live tracking map and status updates feel real-time.

#### Q: Why list two options (Java **or** Go, Razorpay **or** Stripe)?

Because the *design* doesn't depend on the exact brand. Thanks to **Ports & Adapters** (see §B3), payment is called through a `PaymentPort` interface, so swapping Razorpay for Stripe is a plug change, not a rewrite. Listing alternatives signals "these are interchangeable choices, pick per region/cost."

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

### What each table stores

Each table holds one kind of thing. Mapping tables to what you see in the app:

| Table group | What you see in the app |
| --- | --- |
| `users`, `addresses` | Your profile + saved "Home/Work" addresses |
| `restaurants`, `restaurant_hours`, `menu_categories`, `menu_items`, `item_customizations` | A restaurant's page: name, open hours, menu sections, dishes, "extra cheese +₹30" add-ons |
| `delivery_partners`, `rider_shifts` | The drivers and when they were online |
| `carts`, `cart_items` | Your tray before checkout |
| `orders`, `order_items`, `order_status_history` | A placed order, its dishes (frozen at purchase time), and its timeline |
| `order_assignments` | Each "offer" sent to a driver to take the order |
| `payments`, `refunds`, `wallets` | Money in, money back, app credits |
| `coupons`, `coupon_redemptions` | Discount codes and who used them |
| `reviews` | Your star ratings + comment |
| `outbox` | An internal "outbox tray" of events waiting to be announced (see §B4) |

#### Q: Why does `order_items` copy `name` and `price` — isn't that already in `menu_items`?

This is a **snapshot** (notice the `-- snapshot` comments). When you order a "Margherita ₹200", we copy the name and price *into the order*. Why? Because tomorrow the restaurant might rename it or raise the price to ₹250. Your receipt must forever say what you actually bought and paid — ₹200. If `order_items` just *pointed* to the live menu, your old receipt would silently change when the menu changes. **Orders freeze the facts at purchase time.**

#### Q: Why is money stored as `INT` (paise/cents), not a decimal like 200.50?

Decimals/floats are imprecise on computers (0.1 + 0.2 famously ≠ 0.3). For money that's unacceptable. So we store the amount in the **smallest unit as a whole number**: ₹200.50 → `20050` paise. All math stays exact integer math; we only divide by 100 when *displaying* it.

#### Q: What is `idempotency_key` on `orders` and why `UNIQUE`?

Your phone's network is flaky. You tap "Place Order", the request is slow, you tap again — now the server might get the request **twice**. To avoid charging you and cooking two identical orders, the app generates one **idempotency key** per checkout attempt and sends it with both taps. The `UNIQUE (idempotency_key)` constraint means the database physically **rejects the second insert** — same key can exist only once. Result: duplicate taps = one order. ("Idempotent" = doing it twice has the same effect as doing it once.)

#### Q: What's the `outbox` table for — why not just send the Kafka event directly?

Danger: you save the order to the DB, then try to announce "ORDER_PLACED" to Kafka — but the app crashes in between. Now the order exists but nobody was told → it's stuck forever. The **Outbox pattern** fixes this: in the *same database transaction* that saves the order, you also insert a row into `outbox`. Since it's one transaction, either both happen or neither. A separate relay process then reads unpublished outbox rows and pushes them to Kafka, marking them published. The event can never be lost. (More in §B4.)

#### Q: Why is the live rider location NOT a table?

Drivers send GPS ~every second → tens of thousands of writes per second. A SQL database would melt under that (same "too many writes" wall as high-traffic counters). Live position lives in **Redis GEO** (fast memory, and it can answer "who's near this restaurant?" instantly). The SQL `delivery_partners` table only holds slow-changing facts (name, vehicle, status).

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

### The API surface

An **API** is the list of operations each app can invoke on the server. There are three apps (Customer, Restaurant, Rider), so three sets of endpoints, plus internal Kafka events (published between services, not invoked by a user).

**Reading an endpoint:** `POST /v1/orders` means "the Customer app sends (POST = create) a request to the `/orders` address to make a new order." `GET` = read, `POST` = create, `PATCH`/`PUT` = update, `DELETE` = remove. The `/v1/` is the version, so we can change things later without breaking old apps.

**Map the customer's taps to endpoints:**

```
Tap on the app                         → API call
──────────────────────────────────────────────────────────────
Log in with OTP                        → POST /v1/auth/otp, /verify
Home screen "restaurants near me"      → GET  /v1/restaurants?lat=&lng=
Open a restaurant                      → GET  /v1/restaurants/{id}/menu
Add a dish to cart                     → POST /v1/cart/items
Apply a promo code                     → POST /v1/cart/apply-coupon
Tap "Place Order"                      → POST /v1/orders     (Idempotency-Key!)
Watch the live map                     → WS   /v1/orders/{id}/track
Rate the order                         → POST /v1/orders/{id}/review
```

#### Q: What does `(Idempotency-Key)` on `POST /v1/orders` mean?

The app sends a special header — a unique ID for *this* checkout attempt. If the request is retried (flaky network, double tap), the server sees the same key and returns the **already-created order** instead of making a second one. It's the API-level partner of the `UNIQUE(idempotency_key)` DB constraint from §B1. Example of what the client sends:

```
POST /v1/orders
Idempotency-Key: 7f3c-order-attempt-abc123     ← same on every retry of THIS checkout
Body: { restaurantId: 88, addressId: 5, items: [...], couponCode: "SAVE50" }
```

#### Q: Why is `/track` a "WS" (WebSocket) but the others are GET/POST?

`GET /v1/orders/{id}` is a one-time "give me the current state" (a snapshot). But live tracking needs **continuous** updates as the bike moves — you don't want to hammer `GET` every second. So `WS /v1/orders/{id}/track` opens a WebSocket (the always-open phone line from §A6) and the server *pushes* new positions/status as they happen.

#### Q: Why do the same actions appear under different apps (e.g. `/orders/{id}/picked-up`)?

Because different actors trigger different parts of one order's life. The **restaurant** app presses `accept` / `ready`; the **rider** app presses `picked-up` / `delivered`; the **customer** app presses `place` / `cancel` / `review`. They're all editing the *same* order, but each is only allowed to make its legal moves — enforced by the order **state machine** (§B5).

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

### Turning the app into Java objects

Low-level design = deciding **which classes exist and how they collaborate**. The main domain classes are `Restaurant`, `MenuItem`, `Cart`, `Order`, and `DeliveryPartner`, plus a coordinator (`OrderOrchestrator`) that drives the checkout flow.

Here's the same domain as small, self-contained beginner classes (illustrative — the doc's versions are terser):

```java
// A dish on the menu
class MenuItem {
    long   itemId;
    String name;          // "Margherita Pizza"
    int    priceInPaise;  // 20000 = ₹200.00  (integer money, see B1)
    boolean available;    // false = "sold out" toggle
}

// A restaurant
class Restaurant {
    long   id;
    String name;
    double lat, lng;      // where it is (for "near me")
    boolean open;
    int    prepTimeAvgMins;   // used to estimate ETA
    List<MenuItem> menu;
}

// One line in your cart: 2× Margherita with extra cheese
class CartLine {
    MenuItem item;
    int      qty;
    List<String> customizations;   // ["Extra cheese", "Large"]

    int lineTotal() {
        return item.priceInPaise * qty;   // (add-on deltas omitted for clarity)
    }
}

// Your cart before checkout
class Cart {
    long userId;
    Long restaurantId;               // a cart belongs to ONE restaurant
    List<CartLine> lines = new ArrayList<>();

    void add(MenuItem item, int qty, List<String> custom) {
        lines.add(new CartLine(item, qty, custom));
    }

    int subtotal() {
        return lines.stream().mapToInt(CartLine::lineTotal).sum();
    }
}
```

The **orchestrator** is the manager who runs the checkout step-by-step — notice it doesn't *do* payment or dispatch itself, it *delegates* to specialists:

```java
class OrderOrchestrator {

    Order placeOrder(PlaceOrderCmd cmd) {
        // 1. Idempotency: already placed this exact attempt? return it, don't redo.
        var existing = repo.findByKey(cmd.idempotencyKey);
        if (existing.isPresent()) return existing.get();

        // 2. Validate (restaurant open? items in stock? deliverable? price still right?)
        validate(cmd);

        // 3. Compute the price (base + surge + tax − discount)
        Money total = pricing.price(cmd);

        // 4. Save the order as "awaiting payment"
        Order o = repo.insert(buildOrder(cmd, total, PENDING_PAYMENT));

        // 5. Charge the card via the Payment specialist
        PaymentResult r = payment.charge(o);

        // 6. Success → announce it; failure → mark failed
        if (r.success) {
            o.transitionTo(PLACED, SYSTEM);      // legal move, guarded by state machine
            queue.publish("ORDER_PLACED", o);    // announce; dispatch/notify react
        } else {
            o.transitionTo(PAYMENT_FAILED, SYSTEM);
        }
        return repo.save(o);
    }
}
```

#### Q: What are all these `...Port` interfaces (`PaymentPort`, `GeoIndexPort`)?

A **Port** is an interface the domain depends on: the domain code says "I need to charge a card" by calling `PaymentPort.charge(...)`, without knowing which provider implements it. Razorpay or Stripe is the **adapter** that implements the port. Swapping payment providers = swapping the adapter; the business logic never changes. This is the **Ports & Adapters (Hexagonal)** style — it keeps the core logic clean and testable (in tests you plug in a fake payment that always "succeeds").

#### Q: What does `order.transitionTo(PLACED, SYSTEM)` do — why not just `order.status = PLACED`?

Directly setting `status` would let buggy code make illegal jumps (e.g. `DELIVERED` before the food was even picked up). `transitionTo` is **guarded**: it checks the **state machine** (§B5) and throws if the move is illegal. It also records *who* did it (`SYSTEM`, `RIDER`, etc.) into `order_status_history`. It's the difference between "quietly overwrite a field" and "make a legal, audited move."

#### Q: What is a "Saga" and why is the orchestrator called one?

A single order touches multiple services (Payment, Dispatch, Restaurant) that each have their *own* database — so you can't wrap it all in one classic transaction. A **Saga** is a sequence of local steps where, if a later step fails, you run **compensating actions** to undo earlier ones. Example: payment succeeded but the restaurant rejects the order → the saga issues a **refund** to compensate. The `OrderOrchestrator` is the coordinator running that sequence.

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

### Design patterns: what they are

A **design pattern** is a named, proven solution to a common problem. You don't invent them per project; you recognize the situation and apply the known solution. Here are the key ones with tiny code.

**State** — an order can only make legal moves:

```java
// You can't deliver food that was never picked up. The State pattern enforces this.
enum OrderStatus { PENDING_PAYMENT, PLACED, ACCEPTED, PREPARING,
                   OUT_FOR_DELIVERY, DELIVERED, CANCELLED }
// transitionTo() checks a table of "from → allowed next states" and rejects illegal jumps.
```

**Strategy** — swap the dispatch algorithm without touching callers:

```java
interface DispatchStrategy { Optional<Long> pickRider(Order o, List<DeliveryPartner> nearby); }

class NearestIdleStrategy   implements DispatchStrategy { /* just grab the closest free driver */ }
class BatchOptimizeStrategy implements DispatchStrategy { /* one driver carries 2 nearby orders */ }

// Dispatch service holds ONE field; swap the recipe (even A/B test) without changing dispatch code:
dispatch.strategy = new NearestIdleStrategy();
```

"How do we assign a driver?" becomes a swappable choice — switch from "closest driver wins" to "batch two orders on one trip" by swapping the strategy object, even A/B testing them.

**Chain of Responsibility** — the validation pipeline before an order is accepted:

```java
// Each check is a link. First failure short-circuits the chain (rest is skipped).
List<OrderCheck> pipeline = List.of(
    new RestaurantOpenCheck(),      // is the restaurant open right now?
    new ItemsAvailableCheck(),      // nothing sold out?
    new ServiceableAreaCheck(),     // do they deliver to your address?
    new PriceUnchangedCheck(),      // cart price still matches the menu?
    new FraudCheck()                // does this look legit?
);
for (OrderCheck check : pipeline) check.validate(cmd);   // throws on first failure
```

Each check is an independent gate; the order stops at the first gate it fails.

**Decorator** — building the final price by stacking modifiers:

```java
// Start with the base, wrap it with each modifier. Each layer adds one thing.
Price p = new BasePrice(subtotal);
p = new SurgeFee(p);      // + busy-hour surge
p = new Tax(p);           // + GST
p = new Discount(p, coupon);  // − coupon
p = new PackagingFee(p);  // + packaging
int total = p.value();    // base → +surge → +tax → −discount → +packaging
```

**Observer / Pub-Sub** — one event, many independent reactions (this IS Kafka):

```java
kafka.publish("ORDER_PLACED", order);   // publisher doesn't know or care who listens
// Subscribers each react on their own: Dispatch (find a driver),
// Notification (text the customer), Analytics (record a sale).
```

#### Q: There are ~15 patterns listed — do I need all of them in an interview?

No. Know the **headliners for this problem** cold: **State** (order lifecycle), **Strategy** (dispatch/pricing), **Saga + Outbox** (reliable multi-service orders), **Observer/Pub-Sub** (Kafka), and **Circuit Breaker** (protecting payment/maps calls). The rest are good to *recognize*. Patterns are a vocabulary — the point is to say "I'd use the State pattern here" and explain *why*, not to cram every one in.

#### Q: What's the difference between Saga and Outbox — they both sound like "reliable orders"?

They solve different halves. **Saga** = "how do I coordinate a multi-step process across services and *undo* if a step fails" (payment → accept → dispatch, with refund-on-failure). **Outbox** = "how do I guarantee an event I promised to send actually gets sent even if I crash right after saving to the DB." Saga is about *coordination + compensation*; Outbox is about *not losing the announcement*. They're often used together.

#### Q: What is CQRS "lite" here?

**CQRS = Command Query Responsibility Segregation** — use *different* models for writing vs reading. Here: orders are **written** to the strict SQL database (accuracy), but restaurant **discovery is read** from Elasticsearch (fast search). The write model and the read model are separate and optimized differently, kept in sync via CDC (§A5). "Lite" because we're not going full event-sourced CQRS, just splitting the read path onto a search-optimized store.

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

### The order state machine

A **state machine** is the rulebook for what an order (or payment, or driver offer) can do next: from a given state you can only move to certain allowed next states. An order can't jump from "PLACED" straight to "DELIVERED" — it must pass through "ACCEPTED → PREPARING → OUT_FOR_DELIVERY" first.

**Follow one order through its states:**

```
PENDING_PAYMENT   you tapped Order, we're charging your card
   │ payment ok
PLACED            paid; waiting for the restaurant to see it
   │ restaurant taps Accept
ACCEPTED          restaurant agreed to cook it
   │
PREPARING         food is being cooked (a driver is matched in parallel)
   │ driver picks it up
OUT_FOR_DELIVERY  on the bike, headed to you
   │ driver taps Delivered
DELIVERED         done — you can now rate it
```

...and the "unhappy" exits: payment fails → `PAYMENT_FAILED`; restaurant rejects or you cancel → `CANCELLED` (which triggers a refund if you'd paid).

Here's the rulebook as code — a map of "from state → allowed next states":

```java
class OrderStateMachine {

    // The legal moves. Anything not listed here is FORBIDDEN.
    static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
        PENDING_PAYMENT, Set.of(PLACED, PAYMENT_FAILED),
        PLACED,          Set.of(ACCEPTED, REJECTED, CANCELLED),
        ACCEPTED,        Set.of(PREPARING, CANCELLED),
        PREPARING,       Set.of(RIDER_ASSIGNED, CANCELLED),
        RIDER_ASSIGNED,  Set.of(OUT_FOR_DELIVERY, CANCELLED),
        OUT_FOR_DELIVERY,Set.of(DELIVERED)
        // DELIVERED / CANCELLED / PAYMENT_FAILED are terminal — no moves out
    );

    void transitionTo(Order o, OrderStatus next, Actor by) {
        Set<OrderStatus> legal = ALLOWED.getOrDefault(o.status, Set.of());
        if (!legal.contains(next)) {
            throw new IllegalStateException(
                "Illegal move: " + o.status + " → " + next);   // e.g. PLACED → DELIVERED
        }
        o.status = next;
        history.record(o.orderId, next, by);   // audit: who moved it and when
    }
}
```

#### Q: Why bother with a state machine — can't I just check `if` conditions everywhere?

Because order logic is touched by *many* places (customer app, restaurant app, rider app, background jobs). If each writes its own ad-hoc `if`, someone will eventually allow an illegal move (mark an unpaid order as delivered) and corrupt data + money. Centralizing the rules in one guarded `transitionTo` means **every** path obeys the same rulebook, and illegal moves are impossible by construction. It also gives you a free audit trail (`order_status_history`).

#### Q: The diagram says the rider is "assigned in parallel" — how does that fit one linear machine?

Cooking and driver-matching happen *at the same time* (the restaurant preps while Dispatch finds a driver), but the *order's* status still advances through a single line. The parallelism is: the **Dispatch/Assignment** state machine (`OFFERED → ACCEPTED`) runs on its own for the driver side, while the order sits in `PREPARING`. Once both "food ready" and "driver has it" are true, the order moves to `OUT_FOR_DELIVERY`. Two small machines, loosely coordinated by events.

#### Q: What are "terminal" states?

States with no moves out — the game is over. `DELIVERED`, `CANCELLED`, and `PAYMENT_FAILED` are terminal. A delivered order can't become "out for delivery" again. Recognizing terminal states prevents bugs like re-refunding an already-cancelled order.

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

### The four core algorithms

These are the actual step-by-step logic for the hard parts. Let's walk through each.

#### Algorithm 1 — Placing an order (the careful checkout)

The order matters: **check first, charge second, announce third.** We validate everything *before* touching money, save the order + outbox event in one transaction, then charge, then announce.

```java
Order placeOrder(PlaceOrderCmd cmd) {
    // Duplicate tap / retry? Return the order we already made. (idempotency)
    if (repo.exists(cmd.idempotencyKey)) return repo.findByKey(cmd.idempotencyKey);

    validateChain(cmd);              // open? in stock? deliverable? price still valid?
    Money total = price(cmd);        // base + surge + tax − discount

    // ONE transaction: the order and the "to-be-announced" event save together or not at all
    beginTx();
      Order o = repo.insert(order(cmd, total, PENDING_PAYMENT));
      outbox.insert("ORDER_PLACED", o);   // Outbox: guarantees the event survives a crash
    commitTx();

    PaymentResult pay = payment.charge(o);   // circuit-breaker protected external call
    if (pay.success) o.transitionTo(PLACED, SYSTEM);
    else             o.transitionTo(PAYMENT_FAILED, SYSTEM);
    return repo.save(o);
}
```

#### Algorithm 2 — Dispatch (finding a driver) — the interesting one

"Find a nearby free driver, offer the order, and if they say no or don't answer, offer the next one" — all while making sure **two orders never grab the same driver**.

```java
void dispatch(Order order) {
    // Ask the geo index for free drivers within 3 km of the restaurant
    List<DeliveryPartner> candidates =
        geo.nearbyRiders(order.restaurantLat, order.restaurantLng, 3000)
           .stream().filter(r -> r.status == ONLINE).toList();

    // Rank them: closest, heading the right way, not overloaded, ready in time
    List<DeliveryPartner> ranked = strategy.rank(candidates, order);

    for (DeliveryPartner rider : ranked) {
        // Atomically "claim" this rider so no other order can grab them (Redis SET NX)
        if (claimRider(rider.id, order.orderId)) {
            offer(rider, order);                 // ping the rider's app
            if (awaitAccept(rider, Duration.ofSeconds(20))) {
                order.transitionTo(RIDER_ASSIGNED, SYSTEM);
                return;                          // done!
            }
            releaseClaim(rider.id);              // declined/timed out → free them, try next
        }
    }
    // nobody accepted → hold in queue, widen radius, maybe add surge (see B10)
}
```

The dispatcher offers the order to the nearest free driver; if they don't respond in 20 seconds, it offers the next nearest. The **claim/lock** marks that driver as reserved so two dispatch loops can't both hand them a job at once.

#### Algorithm 3 — Location ingest (the GPS firehose)

Every driver's phone pings location constantly. Keep it **only in fast memory**, never the orders DB.

```java
void onPing(long riderId, double lat, double lng) {
    geo.geoAdd("riders:online", lng, lat, "rider:" + riderId);  // update the geo index
    redis.setEx("loc:rider:" + riderId, json(lat, lng), 30);    // latest value, 30s TTL
    kafka.publishSampled("rider.location", riderId, lat, lng);  // sampled → tracking/analytics
    // NEVER write to the orders database (would melt under the write volume)
}
```

#### Algorithm 4 — Cancellation + refund (undoing safely)

If you cancel, we reverse the earlier steps — the "compensation" half of the Saga.

```java
void cancel(Order o, Actor by) {
    if (o.status == DELIVERED || o.status == OUT_FOR_DELIVERY)
        throw new CannotCancelException("too late / partial only");

    o.transitionTo(CANCELLED, by);
    if (o.paymentStatus == PAID) refund.initiate(o);   // give the money back (compensation)
    if (o.riderId != null) releaseClaim(o.riderId);    // free the driver
    notify(o.customerId, "ORDER_CANCELLED");
}
```

#### Q: In dispatch, why do we "claim/lock" the rider *before* offering, not after they accept?

Because two orders can be looking at the *same* nearby driver at the exact same moment. If we waited until acceptance, both might offer to that driver and both might think they won → the driver gets double-booked. Claiming *first* (an atomic Redis `SET NX` = "set only if not already set") means exactly one order wins the driver; the other immediately moves on to the next candidate. It's a reservation before the offer. (See §B8.)

#### Q: What does "sampled" mean for publishing location to Kafka?

The driver pings maybe 5×/second, but tracking and analytics don't need *every* dot. **Sampling** = only forward, say, 1 in every few pings (or one per second). Redis always has the freshest position for the live map; Kafka gets a lighter, thinned stream so we don't flood analytics with redundant points.

#### Q: Why can't you cancel once it's `OUT_FOR_DELIVERY`?

The food is cooked and already on the bike — the cost is already incurred. Allowing a free cancel then would mean wasted food and an unpaid driver trip. So late cancels are refused or only **partially** refunded. This is a business rule encoded right into the algorithm and the state machine.

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

### Reading the sequence diagram

A **sequence diagram** shows the *timeline* of one scenario — who sends what message to whom, top to bottom. Each vertical line is a service; each arrow is a message. Read it top to bottom as a single order's timeline.

**The happy path, in plain words:**

```
1. You tap Place Order         → Order service creates a PENDING order
2. Order asks Payment to charge → card charged OK
3. Order announces ORDER_PLACED → (via Kafka) the Restaurant is notified
4. Restaurant taps Accept       → order moves to ACCEPTED/PREPARING
5. Order announces ORDER_READY  → Dispatch starts matching a driver
6. Dispatch offers to a driver  → driver taps Accept → RIDER_ASSIGNED
7. Driver picks up + rides      → GPS flows to your app → you watch the live map
8. Driver taps Delivered        → order DELIVERED → you can rate it
```

Notice the two styles from §A4 in action: steps 1–2 are **sync** (Order *waits* for Payment), while steps 3, 5, 6 are **async** announcements over Kafka (fire-and-move-on).

#### Q: Why does the failure path (payment fails) just stop?

Because nothing downstream should happen without money. If the charge fails, the order becomes `PAYMENT_FAILED`, the customer is notified, and **no** ORDER_PLACED event is announced — so Dispatch and the Restaurant never even hear about it. There's nothing to undo because we deliberately charged *before* announcing. Contrast with a cancel *after* payment succeeded, which needs a refund (compensation).

#### Q: The diagram shows dispatch happening "in parallel" with cooking — is that a race condition?

It's *concurrency*, not a bug. The restaurant cooking and Dispatch finding a driver genuinely happen at the same time to save minutes. They're coordinated by **events**, not by one waiting on the other: the order only advances to `OUT_FOR_DELIVERY` once *both* "food ready" and "driver has it" are true. The dangerous races (two orders grabbing one driver) are handled separately by atomic locks — see §B8.

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

### What goes wrong when many things happen at once

**Concurrency** = lots of actions happening simultaneously. Bugs appear when two actions touch the same thing at the same moment. Each row above is a race condition and its fix.

**The two orders / one driver race (the classic):**

```java
// WITHOUT a lock — both orders think they got the driver:
if (rider.isFree()) { assign(rider, order); }   // order A checks: free! 
                                                 // order B checks: also free! → DOUBLE-BOOKED

// WITH an atomic Redis lock — exactly one wins:
boolean gotIt = redis.set("rider:" + riderId, orderId, "NX", "EX", 30);
//   NX = "set only if the key does NOT already exist"  → only the FIRST caller succeeds
//   EX 30 = auto-expire in 30s so a crashed order doesn't lock the driver forever
if (gotIt) offer(rider, order);   // order A wins
else       tryNextRider();        // order B is told "taken", moves on
```

`SET NX` is atomic — the store guarantees only one caller can create the key, so exactly one order wins the driver and the rest are told it's taken.

**The duplicate-tap race (idempotency):**

```java
// UNIQUE(idempotency_key) makes the DB itself reject the second insert.
try {
    repo.insert(order);                 // first tap: succeeds
} catch (DuplicateKeyException e) {
    return repo.findByKey(key);         // second tap: DB refused → return the existing order
}
```

**Order state races (two actors act at once)** use *optimistic* locking:

```sql
-- Only update if the status is STILL what we expect. If someone changed it first,
-- 0 rows update → we know we lost the race and retry/reject.
UPDATE orders SET status = 'ACCEPTED'
 WHERE order_id = 123 AND status = 'PLACED';   -- guard clause
```

#### Q: Optimistic vs pessimistic locking — what's the difference?

- **Pessimistic** = "assume conflict, lock first." You grab an exclusive lock before touching the row; others wait. Safe but slow, and can cause traffic jams. (The Redis `SET NX` driver claim is lock-style.)
- **Optimistic** = "assume no conflict, verify at write time." You don't lock; you just add `WHERE status = <what I read>` to your update. If someone changed it meanwhile, your update affects 0 rows and you retry. Fast when conflicts are rare — which they usually are for a single order.

Rule of thumb: use pessimistic/atomic locks for genuinely contended resources (a driver everyone wants); use optimistic checks for things rarely touched at the same instant (one specific order's status).

#### Q: What's a "dual-write" problem and how does Outbox fix it?

A **dual write** is writing to two systems that can't share one transaction — e.g. save the order to the DB *and* publish to Kafka. If the app crashes between them, they disagree (order exists, event lost, or vice versa). The **Outbox** avoids this: you only ever write to *one* system (the DB) in the transaction — the event goes into the `outbox` table in that *same* transaction. A separate relay later reads the outbox and publishes to Kafka, retrying until it succeeds. One atomic write, zero lost events.

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

### Keep a fast copy of what you read a lot

A **cache** is a small, fast copy of data you'd otherwise fetch the slow way every time. Instead of hitting the slow SQL DB on every read of the same data, keep the answer in fast memory and serve repeated reads from there.

Each cache entry has a **TTL (time to live)** — how long before the copy is thrown away and refetched. Short TTL = fresher but more work; long TTL = faster but can be stale.

```java
String getMenu(long restaurantId) {
    String key = "menu:" + restaurantId;
    String cached = redis.get(key);
    if (cached != null) return cached;          // fast path: served from memory

    String menu = db.loadMenu(restaurantId);    // slow path: hit the SQL DB
    redis.setEx(key, menu, Duration.ofMinutes(10));  // remember it for 10 min
    return menu;
}

// When the restaurant edits its menu, don't wait for TTL — invalidate immediately:
void onMenuEdit(long restaurantId) {
    db.saveMenu(...);
    redis.del("menu:" + restaurantId);          // next read repopulates fresh
}
```

#### Q: How do I pick the TTL for each thing?

Match it to how fast the data changes and how bad staleness is:

- **Discovery list (30–60s)** — restaurants opening/closing changes minute-to-minute; a slightly stale list is fine.
- **Menu (minutes, + invalidate on edit)** — changes rarely, so cache longer, but *actively clear* it the moment the restaurant edits, so a sold-out item disappears right away.
- **Rider location (30s)** — very fresh, but a short TTL also means "if pings stop, the stale dot auto-vanishes."
- **Cart (session)** — lives as long as you're shopping.

#### Q: What's the danger of caching, and how do we avoid serving wrong data?

The classic problem is **stale reads** — the cache says an item is available but it just sold out. Two defenses: (1) **short TTLs** so wrong data self-corrects quickly, and (2) **invalidation** — proactively delete the cache entry the instant the source changes (as in `onMenuEdit` above). Critically, we **never cache money/order state**; those are always read strongly-consistent from the SQL DB. Caching is for read-heavy, tolerant-of-slightly-stale data (menus, discovery), not for correctness-critical data.

#### Q: What is `ratelimit:{userId}` doing in a cache?

**Rate limiting** = capping how often someone can do something (e.g. max 5 OTP requests per minute) to stop abuse. Redis counts the requests in a short window and blocks once you exceed the limit. It lives in Redis because it must be checked on *every* request and needs to be blazing fast.

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

### Planning for when things go wrong

Real systems spend most of their code on the *unhappy* paths. Good design means every failure has a defined, graceful response — the app should degrade, not crash. Walk through the common ones:

- **No riders available** → don't fail the order; hold it in the dispatch queue, widen the search radius, show a longer ETA, and maybe **surge** (temporarily pay drivers more to attract them). The food still gets delivered, just slower.
- **Restaurant doesn't accept in time** → auto-cancel and refund; alert ops. Don't leave you waiting forever on a restaurant that's ignoring the tablet.
- **Rider cancels mid-delivery** → re-dispatch to a new driver, recompute the ETA, notify you. The order continues.
- **Payment succeeded but order-creation crashed** → the scariest one (you paid but have no order). Caught by **Outbox/reconciliation**: a background job reconciles payments against orders and auto-refunds anything unrecoverable. You never lose money silently.
- **Item sold out at checkout** → the validation chain (§B4) rejects the order and suggests alternatives, instead of accepting an order the kitchen can't fulfill.
- **Redis/location down** → tracking **degrades gracefully** to "last known location + ETA" while orders keep working. A tracking outage must never block ordering.

Example of a graceful degradation in code — tracking falling back when Redis is down:

```java
Location trackDriver(long orderId) {
    try {
        return redis.getLatest("loc:rider:" + riderOf(orderId));   // normal: live dot
    } catch (RedisDownException e) {
        // Don't fail the whole screen — show the last known point + an ETA estimate
        return lastKnownLocation(orderId).withNote("Live tracking temporarily unavailable");
    }
}
```

#### Q: What does "degrade gracefully" actually mean?

It means when a *non-critical* part breaks, the app loses that feature but keeps its **core** function working. If live tracking dies, you can still place and receive orders — you just don't see the moving dot. The opposite (a "hard failure") would be the whole app going down because one minor service hiccuped. Rule: isolate failures so a small problem stays small.

#### Q: "Payment ok but order crashed" — how does reconciliation actually recover it?

Because payment and order live in different services, a crash can leave money charged with no order attached. A periodic **reconciliation** job compares the Payment ledger against the Orders table: any successful payment with no matching completed order is either **completed** (if we can safely finish it) or **auto-refunded**. This is the safety net that makes "charge first, then create" acceptable — nothing falls through the cracks permanently. It's the same spirit as the batch reconciliation used for billing in stream systems: trust the durable ledger, fix drift afterward.

---

> See **Food Ordering & Delivery — System Design** for the HLD rationale, and the **Notification**, **Kafka**, **Idempotency**, and **Outbox & Saga** notes for supporting patterns.
