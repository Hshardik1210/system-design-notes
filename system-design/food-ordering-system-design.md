# Food Ordering & Delivery — System Design (Swiggy / Zomato / Uber Eats)

> **Core challenge:** connect **three parties** — customers, restaurants, and delivery partners — to get **hot food delivered in ~30 minutes**. The hard, distinctive problems are **hyperlocal discovery** (what can reach me *right now*), **real-time delivery-partner dispatch** (assign the best rider), **live GPS tracking**, and **ETA prediction** — all under a tight, perishable SLA. The order-management core (cart → order → payment → state machine) is largely the **same as any e-commerce app**; the fulfillment layer is what's different.

---

## Contents

- [1. What Is a Food Delivery Platform?](#1-what-is-a-food-delivery-platform)
- [2. Swiggy vs Flipkart — Same or Different?](#2-swiggy-vs-flipkart--same-or-different)
- [3. Requirements](#3-requirements)
- [4. Capacity Estimation](#4-capacity-estimation)
- [5. API Design](#5-api-design)
- [6. High-Level Architecture](#6-high-level-architecture)
- [7. Core Services](#7-core-services)
- [8. Data Model & Schema](#8-data-model--schema)
- [9. Discovery & Hyperlocal Search](#9-discovery--hyperlocal-search)
- [10. Cart & Order Placement](#10-cart--order-placement)
- [11. Order Lifecycle — State Machine](#11-order-lifecycle--state-machine)
- [12. Delivery-Partner Matching & Dispatch](#12-delivery-partner-matching--dispatch)
- [13. Live Order Tracking](#13-live-order-tracking)
- [14. ETA Estimation](#14-eta-estimation)
- [15. Payments](#15-payments)
- [16. Ratings & Reviews](#16-ratings--reviews)
- [17. Scaling the System](#17-scaling-the-system)
- [18. Failure Scenarios & Mitigations](#18-failure-scenarios--mitigations)
- [19. Observability](#19-observability)
- [20. How to Drive the Interview](#20-how-to-drive-the-interview)
- [21. Interview Cheat Sheet](#21-interview-cheat-sheet)
- [22. Design Patterns (that can be used)](#22-design-patterns-that-can-be-used)
- [23. Final Takeaways](#23-final-takeaways)

---

## 1. What Is a Food Delivery Platform?

A **three-sided marketplace**:

```
        ┌──────────────┐        ┌──────────────┐        ┌──────────────────┐
        │   Customer   │        │  Restaurant  │        │ Delivery Partner │
        │  (orders)    │        │  (cooks)     │        │  (rider)         │
        └──────┬───────┘        └──────┬───────┘        └────────┬─────────┘
               │  browse, order         │ accept, prepare         │ pick up, deliver
               └───────────────┬────────┴────────────────────────┘
                               ▼
                     Food Delivery Platform
             (discovery · order · dispatch · tracking · payments)
```

### The lifecycle in one line

```
browse restaurants near me → add to cart → pay → restaurant accepts & cooks
   → rider assigned → rider picks up → live-tracked to my door → delivered → rate
```

| Sub-problem | Why it's hard |
| --- | --- |
| **Discovery** | Hyperlocal — only show restaurants that can deliver to *my* location *now* (geospatial + serviceability + open hours) |
| **Order** | Standard e-commerce cart/checkout/payment/idempotency |
| **Dispatch** | Real-time assignment of the best rider — a live optimization problem |
| **Tracking** | Stream rider GPS to the customer's map in real time |
| **ETA** | Prep time + travel time + traffic, updated continuously |
| **Perishability** | ~30-min SLA; a late order = cold food = refund; you can't "reship" |

---

## 2. Swiggy vs Flipkart — Same or Different?

You're right that **order management is largely the same**. The difference is **fulfillment**. Here's the precise breakdown.

### ✅ What's essentially the SAME (reuse the e-commerce core)

| Area | Both Swiggy & Flipkart |
| --- | --- |
| **Cart & checkout** | Add items, apply coupons, compute totals, place order |
| **Order service + state machine** | Create order, track status transitions, idempotent order creation |
| **Payments** | Gateway integration, payment states, refunds, wallet, retries |
| **Catalog browsing** | Menu (Swiggy) ≈ product catalog (Flipkart) — list, detail, search |
| **User accounts & addresses** | Profiles, saved addresses, auth |
| **Notifications** | Order updates via push/SMS/in-app |
| **Ratings & reviews** | Rate the item/restaurant/seller |
| **Idempotency, outbox, retries** | Same reliability patterns everywhere |

> So yes — the **Order Service, Payment Service, Catalog, Cart, Notifications** look almost identical. If you've designed Flipkart's order flow, ~50% of Swiggy is done.

### ❌ What's fundamentally DIFFERENT (the food-delivery-specific hard parts)

| Dimension | **Flipkart (e-commerce)** | **Swiggy (food delivery)** |
| --- | --- | --- |
| **Fulfillment** | Warehouse → packed → courier, over **days** | Restaurant cooks → rider → your door in **~30 min** |
| **Delivery** | 3rd-party courier, coarse shipment tracking | Own/contracted **riders**, **real-time GPS dispatch + tracking** |
| **Discovery** | Global catalog search (any product, anywhere) | **Hyperlocal** — only restaurants that deliver to *me* *now* |
| **Inventory** | SKU **stock counts** across warehouses | Restaurant **availability** — open/closed, item sold-out (binary, real-time) |
| **Matching** | None (courier picks up in bulk) | **Live rider↔order assignment** optimization (like Uber) |
| **SLA / latency** | Days; delays tolerable | Minutes; food is **perishable** — late = refund |
| **State machine** | Fewer states, slow transitions | Many **real-time, multi-actor** transitions (restaurant + rider + system) |
| **Pricing** | Mostly static | **Dynamic** delivery fee / surge during peak & rain |
| **Geospatial** | Minimal | **Core** — geo-search, serviceability polygons, rider proximity |

### The mental model

```
Flipkart  = Catalog + Order + Payment + [ Warehouse & Courier Logistics (async, days) ]
Swiggy    = Catalog + Order + Payment + [ Hyperlocal Discovery + Real-time Dispatch + Live Tracking (sync, minutes) ]
                     └── SAME CORE ──┘   └──────────── DIFFERENT FULFILLMENT ────────────┘
```

> **Interview line:** "The order-management core — cart, order service, state machine, payments, idempotency — is essentially the same as any e-commerce system like Flipkart. What makes food delivery unique is the **real-time, hyperlocal fulfillment layer**: geospatial discovery, live delivery-partner matching, GPS tracking, and ETA prediction under a perishable ~30-minute SLA. That's where I'd spend the design time."

---

## 3. Requirements

### Functional — by actor

**Customer**
- Discover restaurants/dishes deliverable to their location (search, filter, sort).
- View menu, add to cart, apply offers, checkout & pay.
- Track order live (status + rider location on map), get ETA.
- Rate & review; view order history; reorder.

**Restaurant**
- Manage menu, prices, availability (open/closed, item sold-out).
- Receive, accept/reject orders; update prep status.

**Delivery partner (rider)**
- Go online/offline; receive assignment offers; accept/reject.
- Navigate to restaurant → pickup → navigate to customer → deliver.
- Stream location; see earnings.

### Non-Functional (NFRs)

| NFR | Target / Note |
| --- | --- |
| **Low latency** | Discovery & tracking feel instant; order placement < ~1s |
| **High availability** | Outage = lost revenue + hungry users; target 99.9%+ |
| **Real-time** | Dispatch and location updates in seconds |
| **Scalable** | Millions of daily orders; huge lunch/dinner peaks |
| **Consistency** | **Strong** for orders/payments; **eventual** OK for discovery/tracking |
| **Geo-accuracy** | Correct serviceability + accurate ETAs |

### Out of scope (state assumptions)

- Deep ML for ETA/dispatch ranking (mention; treat as a scoring black box).
- Grocery/instant-mart (Instamart) — similar but dark-store inventory model.
- Fraud detection, loyalty programs — mention, defer.

---

## 4. Capacity Estimation

```
Assume:
  DAU                         ~ 20M
  Orders / day                ~ 5M  (peaks at lunch 12–2pm, dinner 7–10pm)
  Avg order QPS               5M / 86,400 ~ 58 orders/sec
  Peak order QPS (10x peak)   ~ 600 orders/sec
  Browse:order ratio          ~ 50:1 → ~30k browse/search QPS peak

Delivery partners online      ~ 300k at peak
  Location pings every 4s     300k / 4 ~ 75,000 location writes/sec  ← huge, high-frequency
  (customers tracking active orders also read these)

Active orders being tracked   ~ 500k concurrent at peak
  → WebSocket connections + frequent location pushes

Storage:
  Orders: 5M/day * 365 * ~2KB     ~ 3.6 TB/year (partition + archive)
  Location pings: ephemeral → Redis / time-series, short retention, aggregate for history
```

**Takeaways that drive design:**
- **Read/browse dominates** → cache + geo-index for discovery.
- **Location pings are the volume monster** (75k/sec) → keep them out of the primary DB; Redis + streaming.
- **Sharp peaks** (mealtimes) → autoscale, queue-based decoupling, surge handling.
- **Orders/payments need strong consistency**; discovery/tracking can be eventually consistent.

---

## 5. API Design

### Discovery

```
GET /v1/restaurants?lat=..&lng=..&cursor=&filters=veg,rating4+&sort=eta
  → restaurants deliverable to (lat,lng), each with ETA + delivery fee

GET /v1/restaurants/{id}/menu
```

### Cart & order

```
POST /v1/orders
Idempotency-Key: <clientRequestId>
{
  "restaurantId": 88,
  "items": [{ "itemId": 1, "qty": 2 }, { "itemId": 5, "qty": 1 }],
  "deliveryAddressId": 42,
  "couponCode": "WELCOME50",
  "paymentMethod": "UPI"
}
→ 201 { "orderId": 9001, "status": "PENDING_PAYMENT", "amount": 480, "eta": "32 min" }
```

### Tracking (customer)

```
GET  /v1/orders/{id}                # snapshot: status + rider location + ETA
WS   /v1/orders/{id}/track          # live push: status changes + rider GPS
```

### Restaurant

```
POST  /v1/restaurant/orders/{id}/accept        { prepTimeMins: 15 }
POST  /v1/restaurant/orders/{id}/reject        { reason }
PATCH /v1/restaurant/menu/items/{id}           { available: false }   # sold out
```

### Delivery partner (rider)

```
POST /v1/riders/{id}/status         { online: true }
POST /v1/riders/{id}/location       { lat, lng, ts }        # high-frequency ping
POST /v1/riders/{id}/offers/{offerId}/accept
POST /v1/orders/{id}/picked-up
POST /v1/orders/{id}/delivered
```

> Order placement is **synchronous + idempotent** (user waits for confirmation). Location pings are **fire-and-forget high-frequency**. Tracking uses **WebSocket/SSE** for live push.

---

## 6. High-Level Architecture

```
   Customer App        Restaurant App        Rider App
        │                    │                   │
        ▼                    ▼                   ▼
                    ┌──────────────────┐
                    │   API Gateway    │  auth · rate limit · routing
                    └────────┬─────────┘
   ┌──────────┬──────────────┼───────────────┬──────────────┬─────────────┐
   ▼          ▼              ▼               ▼              ▼             ▼
┌────────┐ ┌────────┐  ┌──────────┐   ┌───────────┐  ┌──────────┐  ┌──────────┐
│Discovery│ │ Cart / │  │  Order   │   │ Dispatch  │  │ Location │  │ Payment  │
│/Search │ │ Catalog│  │ Service  │   │ Service   │  │ Service  │  │ Service  │
└───┬────┘ └───┬────┘  └────┬─────┘   └─────┬─────┘  └────┬─────┘  └────┬─────┘
    │          │            │               │             │             │
 ┌──▼──┐   ┌───▼───┐    ┌───▼────┐    ┌──────▼─────┐  ┌────▼────┐   ┌────▼────┐
 │Geo   │   │Menu   │    │Order DB│    │Rider index │  │ Redis   │   │ Gateway │
 │Index │   │DB+cache│   │(RDBMS) │    │(Redis GEO) │  │(GEO/TS) │   │(Razorpay│
 │(S2/  │   └───────┘    └───┬────┘    └────────────┘  └────┬────┘   │ /UPI)   │
 │geohash)                   │                              │        └─────────┘
 └─────┘                     │  events (order placed, ready, delivered)
                             ▼
                    ┌──────────────────┐
                    │      Kafka        │ ──► Notification Svc ──► push/SMS/in-app
                    │  (event backbone) │ ──► Analytics / ETA / Fraud
                    └──────────────────┘
                             │
                             ▼
                    ┌──────────────────┐
                    │ WebSocket Service │ ──► live tracking to Customer + Rider apps
                    └──────────────────┘
```

---

## 7. Core Services

| Service | Responsibility | Store |
| --- | --- | --- |
| **Discovery/Search** | Restaurants deliverable to a location; rank by ETA/rating; filters | Geo-index (Elasticsearch + S2/geohash), Redis cache |
| **Catalog/Menu** | Restaurant menus, prices, availability | RDBMS + heavy cache |
| **Cart** | Build order pre-checkout; price/coupon calc | Redis (ephemeral) |
| **Order Service** | Create order, own the **state machine**, orchestrate | RDBMS (orders) |
| **Payment Service** | Charge, refund, wallet | RDBMS + gateway |
| **Dispatch Service** | Match orders → best rider; batching; offers | Redis GEO (rider index) |
| **Location Service** | Ingest rider GPS pings; serve latest location | Redis GEO + time-series |
| **Tracking (WebSocket)** | Push status + rider location to apps | Redis (conn map) |
| **Notification Service** | Order updates across channels | (see Notification note) |
| **Restaurant Service** | Order accept/reject, prep updates, menu mgmt | RDBMS |

> **Order Service orchestrates**; specialized services (dispatch, location, payment) own their domains. Kafka is the **event backbone** connecting them.

---

## 8. Data Model & Schema

### Order (the core — same shape as any e-commerce order)

```sql
CREATE TABLE orders (
    order_id        BIGINT PRIMARY KEY,        -- Snowflake / distributed ID
    idempotency_key VARCHAR(255) NOT NULL,
    customer_id     BIGINT NOT NULL,
    restaurant_id   BIGINT NOT NULL,
    status          VARCHAR(40) NOT NULL,      -- see state machine (§11)
    address_id      BIGINT NOT NULL,
    subtotal        INT NOT NULL,              -- store money in paise/cents
    delivery_fee    INT NOT NULL,
    discount        INT DEFAULT 0,
    total           INT NOT NULL,
    payment_status  VARCHAR(30) NOT NULL,      -- PENDING, PAID, REFUNDED, FAILED
    rider_id        BIGINT,                    -- assigned delivery partner
    eta_at          TIMESTAMP,
    placed_at       TIMESTAMP NOT NULL DEFAULT now(),
    delivered_at    TIMESTAMP,
    UNIQUE (idempotency_key)
);
CREATE INDEX idx_orders_customer ON orders (customer_id, placed_at DESC);
CREATE INDEX idx_orders_restaurant_active ON orders (restaurant_id) WHERE status NOT IN ('DELIVERED','CANCELLED');

CREATE TABLE order_items (
    order_id   BIGINT NOT NULL REFERENCES orders(order_id),
    item_id    BIGINT NOT NULL,
    name       TEXT NOT NULL,       -- snapshot (menu may change later)
    price      INT NOT NULL,        -- snapshot at order time
    qty        INT NOT NULL
);

CREATE TABLE order_status_history (   -- audit trail of every transition
    order_id   BIGINT NOT NULL,
    status     VARCHAR(40) NOT NULL,
    actor      VARCHAR(30),          -- SYSTEM, RESTAURANT, RIDER, CUSTOMER
    at         TIMESTAMP NOT NULL DEFAULT now()
);
```

### Restaurant & menu

```sql
CREATE TABLE restaurants (
    restaurant_id BIGINT PRIMARY KEY,
    name          TEXT NOT NULL,
    lat           DOUBLE PRECISION NOT NULL,
    lng           DOUBLE PRECISION NOT NULL,
    is_open       BOOLEAN NOT NULL DEFAULT TRUE,
    prep_time_avg INT,                        -- minutes, for ETA
    rating        NUMERIC(2,1),
    serviceable_radius_m INT                  -- or a polygon/geofence
);

CREATE TABLE menu_items (
    item_id       BIGINT PRIMARY KEY,
    restaurant_id BIGINT NOT NULL,
    name          TEXT NOT NULL,
    price         INT NOT NULL,
    is_available  BOOLEAN NOT NULL DEFAULT TRUE,   -- sold-out toggle
    is_veg        BOOLEAN
);
```

### Delivery partner & location (hot, ephemeral → Redis, not RDBMS)

```
Rider status/index (Redis GEO):
  GEOADD riders:online <lng> <lat> rider:{id}    # queryable by proximity
  HSET   rider:{id} status ONLINE  current_order NULL

Latest location (Redis, TTL):
  SET loc:rider:{id} {lat,lng,ts}  EX 30

Historical track (time-series / archived for analytics only)
```

> **Key modeling decision:** high-frequency location data does **not** go in the transactional DB. It lives in **Redis (GEO + latest value)** and optionally a time-series store — otherwise 75k writes/sec crushes the order DB.

---

## 9. Discovery & Hyperlocal Search

The customer's home screen = "restaurants that can deliver to me, right now, ranked."

### The geospatial problem

Given `(lat, lng)`, find nearby, **serviceable**, **open** restaurants fast.

| Technique | How |
| --- | --- |
| **Geohash** | Encode lat/lng into a string prefix; nearby points share prefixes → range/prefix lookups |
| **S2 cells / QuadTree** | Hierarchical spatial cells; index restaurants by cell, query cells near the user |
| **Redis GEO** (`GEOADD`/`GEOSEARCH`) | Built-in radius/box search — great for the online index |
| **Elasticsearch geo_distance** | Combine geo filter with text search, filters, and ranking |

### Serviceability

A nearby restaurant isn't enough — it must **deliver to you** and be **open** and have **riders available**:

```
candidates = geoSearch(userLat, userLng, radius)
filter:  restaurant.is_open
         AND distance <= restaurant.serviceable_radius   (or inside delivery polygon)
         AND ridersAvailable(area)                        (else "no riders" / longer ETA)
rank by: ETA, rating, sponsored, personalization
```

### Making it fast

- **Precompute & cache** the restaurant list per geohash cell (many users share a cell) → cache-aside in Redis.
- Discovery reads a **read-optimized index** (Elasticsearch), rebuilt/updated from the catalog via CDC/Kafka — never queries the transactional DB directly.
- Availability (`is_open`, sold-out) is **frequently updated** → push updates into the index / cache with short TTL.

> **Consistency:** discovery is **eventually consistent** — a menu edit taking a few seconds to appear is fine. Orders are not.

---

## 10. Cart & Order Placement

### Cart

Ephemeral, per-user, in **Redis**. Recompute price/coupons server-side at checkout (never trust client totals). Re-validate item availability and prices at checkout time (menu may have changed).

### Order placement (synchronous, idempotent)

```
POST /orders  (Idempotency-Key)
1. Idempotency check: key seen? → return existing order (no double-charge)
2. Validate: restaurant open? items available? price unchanged? address serviceable?
3. Compute total (subtotal + delivery fee + surge − discount)
4. Create order (status = PENDING_PAYMENT) in ONE transaction
5. Initiate payment (§15)
6. On payment success → status = PLACED → emit ORDER_PLACED event (Kafka)
7. Return confirmation + ETA
```

> **Idempotency** (same pattern as the Notification / e-commerce notes): unique `idempotency_key` on `orders` → a retried "Place Order" tap never creates two orders or two charges.

### Then the async fulfillment kicks off (event-driven)

```
ORDER_PLACED (Kafka) → Restaurant Service (notify restaurant to accept)
                     → Dispatch Service (start looking for a rider)
                     → Notification Service (confirm to customer)
```

---

## 11. Order Lifecycle — State Machine

Food orders have **more states and more actors** than e-commerce — the interesting part.

```
                       (payment)              (restaurant)            (dispatch)
PENDING_PAYMENT ──────► PLACED ──────► ACCEPTED / REJECTED ──────► RIDER_ASSIGNED
     │  fail                              │ reject                       │
     ▼                                    ▼                              ▼
  PAYMENT_FAILED                      CANCELLED  ◄──────────────    PREPARING
                                          ▲                              │
   customer/rider/restaurant cancel ──────┘                              ▼
                                                                   RIDER_AT_RESTAURANT
                                                                         │ picked up
                                                                         ▼
                                                                   OUT_FOR_DELIVERY
                                                                         │ delivered
                                                                         ▼
                                                                     DELIVERED
```

| State | Set by | Notes |
| --- | --- | --- |
| `PENDING_PAYMENT` | System | Awaiting payment |
| `PLACED` | System | Paid; broadcast to restaurant + dispatch |
| `ACCEPTED` / `REJECTED` | **Restaurant** | With prep-time estimate; reject → refund |
| `RIDER_ASSIGNED` | **Dispatch** | Best rider matched (can run in parallel with prep) |
| `PREPARING` | Restaurant | Cooking |
| `RIDER_AT_RESTAURANT` | Rider | Arrived for pickup |
| `OUT_FOR_DELIVERY` | Rider | Picked up; live tracking active |
| `DELIVERED` | Rider | Terminal (happy) |
| `CANCELLED` | Customer/Restaurant/System | Refund logic depends on stage |

> Every transition is written to `order_status_history` (audit) and emitted to Kafka (drives notifications + tracking). The **Order Service owns** the state machine; other services request transitions via API/events, they don't mutate the order DB directly (service-ownership boundary — see Notification note §20).

> **Dispatch runs in parallel with prep:** you assign a rider while food cooks so the rider arrives near pickup-ready time — critical for the 30-min SLA.

---

## 12. Delivery-Partner Matching & Dispatch

The signature hard problem — a real-time assignment/optimization (cousin of Uber's dispatch).

### Goal

Assign each order to the **best** available rider — minimizing total delivery time and cost while keeping riders utilized.

### Inputs

- Rider locations (Redis GEO), online/idle status, current load.
- Restaurant location + **food-ready time** (prep estimate).
- Customer location.
- Traffic / distance (routing service).

### Basic flow

```
On ORDER_PLACED (and when food is nearly ready):
1. Candidate riders = GEOSEARCH near the restaurant (e.g. within 3 km), status = idle/eligible
2. Score each candidate:
      score = f(distance_to_restaurant, direction match, current_load,
                rider_rating, time_to_food_ready, fairness/earnings)
3. Offer to the best rider → they accept/reject within N seconds
4. On reject/timeout → offer to next best
5. On accept → order.status = RIDER_ASSIGNED, rider.current_order = orderId
```

### Batching (order pooling)

One rider can carry **multiple orders** from the same/nearby restaurants going the same direction:

```
if two orders share pickup area + similar drop direction + timing window
   → batch them to one rider  (lower cost per delivery, but watch per-order SLA)
```

### Assignment strategy

| Strategy | Trade-off |
| --- | --- |
| **Greedy / nearest-idle** | Simple, fast; not globally optimal |
| **Batch optimization** (run every few seconds over a pool of orders + riders) | Better global assignment (Hungarian/min-cost matching); more compute + slight delay |

> **Interview framing:** treat scoring as a pluggable function (often ML). Emphasize: candidate generation via **geo-index**, an **offer/accept loop with timeout & fallback**, **batching**, and running dispatch as a **near-real-time loop** (event-driven per order, or a periodic batch every few seconds). Assignment must be **idempotent/locked** so two orders don't grab the same rider (atomic "claim rider" in Redis).

### Concurrency safety

```
Claim rider atomically (Redis):  SET rider:{id}:lock orderId NX EX 30
  → prevents two dispatch workers assigning the same rider to two orders
```

---

## 13. Live Order Tracking

Customer watches the rider move on a map in real time.

```
Rider App ──(GPS ping every ~4s)──► Location Service
                                        │  write latest → Redis (loc:rider:{id}, TTL)
                                        │  publish → Kafka (location stream)
                                        ▼
                               WebSocket Service
                                        │  look up who's tracking this rider's order
                                        ▼
                               Customer App map (live marker + ETA)
```

| Concern | Approach |
| --- | --- |
| **Volume** (75k pings/sec) | Never touch the order DB; Redis latest-value + stream; sample/throttle |
| **Push to customer** | WebSocket/SSE; connection map in Redis (`ws:order:{id}`) |
| **Only active orders** | Track only `OUT_FOR_DELIVERY` (and maybe assigned) orders — not all riders |
| **Efficiency** | Push deltas; drop stale pings; clients interpolate/animate between updates |
| **Battery/data** | Adaptive ping frequency (faster when close, slower when far) |

> Location is **ephemeral, eventually consistent** — a dropped ping is fine, the next one corrects it. This is the opposite of the order/payment path.

---

## 14. ETA Estimation

The "32 min" the customer sees. Continuously refined.

```
ETA ≈ time_to_accept
    + food_prep_time            (restaurant estimate / historical avg)
    + rider_to_restaurant_time  (routing + traffic)
    + wait_at_restaurant
    + rider_to_customer_time    (routing + traffic)
```

- Start with restaurant/historical averages; refine as real events land (accepted, ready, picked up).
- Use a **routing service** (Google Maps / OSRM) for travel legs with live traffic.
- Recompute and **push updates** to the customer when a stage completes or the rider deviates.
- Often an **ML model** trained on historical deliveries (features: hour, weather, restaurant load, distance). Treat as a black box in an interview; emphasize the **inputs, continuous refinement, and push updates**.

---

## 15. Payments

Same pattern as any e-commerce/BookMyShow flow:

- Gateway integration (UPI/cards/wallet); **idempotent** payment initiation.
- Order held `PENDING_PAYMENT` until confirmed; payment webhook → `PLACED`.
- **Refunds** on restaurant reject / cancellation (partial vs full depends on stage).
- **Wallet/credits**, COD (cash on delivery) as a payment method.
- Reconciliation via **outbox** so a paid order never fails to progress (dual-write safety).

> Reuse the payment design from BookMyShow / Notification notes — this is genuinely the "same as Flipkart" part.

---

## 16. Ratings & Reviews

- Rate **order, restaurant, and rider** separately after delivery.
- Writes go to a reviews store; **aggregate rating** updated async (Kafka consumer → recompute avg) — don't update the hot restaurant row synchronously on every review.
- Ratings feed back into **discovery ranking** and **dispatch scoring**.

---

## 17. Scaling the System

### Read-heavy discovery
- **Geo-index (Elasticsearch) + Redis cache** per geohash cell; rebuild from catalog via CDC/Kafka.
- Discovery never hits the transactional DB.

### Location firehose
- **Redis GEO + latest-value** for rider positions; stream to Kafka; **do not** persist every ping to RDBMS.
- Aggregate/downsample for historical analytics.

### Orders & payments
- RDBMS with **strong consistency**; shard `orders` by `customer_id` or region; partition by `placed_at`; archive old orders.
- Decouple fulfillment via **Kafka** (order placed/ready/delivered events).

### Peaks (lunch/dinner, rain)
- Autoscale stateless services; Kafka absorbs event bursts.
- **Surge pricing** shapes/sheds demand; dispatch batching improves rider throughput.

### Geo-sharding
- Partition by **city/region** — a food marketplace is inherently local; a Bangalore order never needs Delhi's riders → shard by geography for locality + isolation.

---

## 18. Failure Scenarios & Mitigations

| Failure | Mitigation |
| --- | --- |
| **No riders available** | Show longer ETA / "unavailable"; queue for assignment; surge to attract riders |
| **Restaurant doesn't accept in time** | Auto-cancel + refund; alert; suppress from discovery |
| **Rider cancels mid-delivery** | Re-dispatch to a new rider; notify customer; adjust ETA |
| **Payment succeeds, order creation fails** | Idempotency + **outbox**/reconciliation; auto-refund if unrecoverable |
| **Duplicate "Place Order" tap** | Idempotency key → one order |
| **Location pings lost / Redis down** | Tracking degrades gracefully (last known + ETA); orders unaffected |
| **Dispatch assigns same rider twice** | Atomic Redis claim lock on rider |
| **Discovery index stale** | Short TTL + CDC updates; availability re-checked at checkout |
| **Peak overload** | Autoscale, Kafka buffering, surge, load-shed non-critical (recommendations) |

---

## 19. Observability

| Signal | What to track |
| --- | --- |
| **Business** | Orders/min, order→delivery time (the SLA), cancellation rate, "no rider" rate, refund rate |
| **Dispatch** | Assignment latency, offer accept rate, unassigned-order age, batching rate |
| **Tracking** | Location ping rate, WebSocket connections, push latency |
| **Infra** | Order p99, payment success rate, Kafka consumer lag, geo-index freshness, Redis latency |
| **Alerts** | Rising delivery time, "no rider" spike, payment failures, dispatch backlog, index staleness |

> **Headline metric:** **order-to-delivery time** vs promised ETA — the whole business is judged on it.

---

## 20. How to Drive the Interview

| Phase | Time | What to say |
| --- | --- | --- |
| **1. Clarify** | 5 min | Which actors? Discovery + order + dispatch + tracking in scope? Scale? |
| **2. Frame** | 3 min | "Order core = same as e-commerce; the hard, unique parts are hyperlocal discovery, real-time dispatch, and live tracking." |
| **3. Estimation** | 4 min | Orders/sec, browse:order, **location ping firehose**, storage |
| **4. HLD** | 8 min | Services, Kafka backbone, geo-index, Redis for location, WebSocket for tracking |
| **5. Deep dives** | 12 min | Interviewer picks: dispatch/matching, geo-search, tracking, order state machine, ETA |
| **6. Wrap-up** | 3 min | Consistency split, failures, geo-sharding, trade-offs |

**Strong opening:**

> "I'll split this into the **order-management core** — cart, order service, state machine, payments, idempotency, which is essentially the same as any e-commerce system — and the **food-delivery-specific fulfillment layer**: hyperlocal geospatial discovery, real-time delivery-partner matching, live GPS tracking, and ETA prediction under a ~30-minute perishable SLA. I'll spend most time on the fulfillment layer since that's what's unique."

**Trade-offs to mention proactively:**

| Choice | Trade-off |
| --- | --- |
| Strong consistency (orders/payments) vs eventual (discovery/tracking) | Correctness where money's involved; speed elsewhere |
| Location in Redis/stream vs DB | Handles 75k pings/sec; loses durable per-ping history (aggregate instead) |
| Greedy dispatch vs batch optimization | Latency/simplicity vs global efficiency |
| Batching orders | Cheaper deliveries vs per-order SLA risk |
| Geo-sharding by city | Locality + isolation vs cross-region complexity |

---

## 21. Interview Cheat Sheet

> **"Is this the same as designing Flipkart?"**
>
> "The order-management core is — cart, order service, state machine, payments, idempotency, notifications. What's different is fulfillment: Flipkart is warehouse→courier over days with coarse tracking; Swiggy is restaurant→rider in ~30 minutes with **hyperlocal discovery, real-time dispatch, and live GPS tracking**. That fulfillment layer is where the real design is."

> **"How do you find restaurants near me?"**
>
> "Geospatial index — geohash/S2 cells or Redis GEO/Elasticsearch `geo_distance`. Query candidates in a radius, filter by open + serviceable + riders-available, rank by ETA/rating. Cache per geohash cell since users share cells; the index is rebuilt from the catalog via CDC, so discovery never hits the order DB."

> **"How do you assign a delivery partner?"**
>
> "Real-time matching: geo-search idle riders near the restaurant, score by distance/direction/load/food-ready-time, offer to the best with an accept timeout and fallback to the next. Batch nearby same-direction orders to one rider. Claim the rider with an atomic Redis lock so two orders can't grab the same one. Dispatch runs while food cooks so the rider arrives at pickup-ready time."

> **"How does live tracking work at scale?"**
>
> "Rider app pings GPS every few seconds → Location Service writes latest to Redis and streams to Kafka → WebSocket service pushes to the customer tracking that order. 75k pings/sec never touch the order DB — it's ephemeral and eventually consistent. Only active `OUT_FOR_DELIVERY` orders are tracked."

> **"How do you prevent duplicate orders / double charges?"**
>
> "Idempotency key on order creation (unique constraint) + idempotent payment initiation + outbox for the payment→order transition, so a retried tap or a crash between charge and order never double-charges or strands a paid order."

> **"Consistency model?"**
>
> "Strong for orders and payments (money). Eventual for discovery and location tracking — a slightly stale menu or a dropped GPS ping is fine and self-corrects."

> **"How do you compute ETA?"**
>
> "Prep time + rider-to-restaurant + rider-to-customer travel (routing service with traffic), refined as each stage completes and pushed to the customer. Usually an ML model over historical deliveries; I treat scoring as a black box and focus on inputs + continuous refinement."

> **"Peak load at lunch/dinner?"**
>
> "Autoscale stateless services, Kafka buffers event bursts, surge pricing shapes demand, dispatch batching raises rider throughput, and geo-sharding by city isolates load."

---

## 22. Design Patterns (that can be used)

> Full detail with class design in the [HLD & LLD companion](food-ordering-hld-lld.md) §B4.

| Pattern | Where | Why |
| --- | --- | --- |
| **Saga / Orchestration** | Order → pay → accept → dispatch → deliver, with compensation (refund) | Distributed transaction |
| **State** | Order lifecycle (multi-actor) | Guard transitions |
| **Strategy** | Dispatch (greedy/batch), pricing/surge, ETA | Swap algorithms |
| **Chain of Responsibility** | Order validation (open → available → serviceable → price) | Composable checks |
| **Observer / Pub-Sub** | Kafka events → dispatch, notification, analytics | Decouple |
| **Ports & Adapters** | Payment, maps/routing, geo-index, push | Swap providers |
| **Outbox** | Reliable order/payment events | No dual-write loss |
| **Idempotency Key** | Order + payment creation | No duplicate orders/charges |
| **Decorator / Chain** | Price composition (base + surge + tax − discount) | Stack pricing rules |
| **Publish-Subscribe + WebSocket** | Live rider tracking | Fan-out location |
| **Circuit Breaker** | Payment/maps calls | Resilience |
| **CQRS** | Discovery (ES read model) vs order (RDBMS write) | Optimized reads |

---

## 23. Final Takeaways

- **Three-sided marketplace** — customer, restaurant, rider; each an actor in the order state machine.
- **Order core = e-commerce core** — cart, order service, state machine, payments, idempotency, notifications are the **same as Flipkart**.
- **Fulfillment is what's different** — hyperlocal discovery, real-time dispatch, live tracking, ETA, all under a perishable ~30-min SLA.
- **Discovery = geospatial** — geohash/S2/Redis GEO/Elasticsearch; filter by open + serviceable + riders; cache per cell; eventually consistent.
- **Dispatch = real-time matching** — geo-candidate riders, score, offer/accept loop with fallback, batching, atomic rider claim; run while food cooks.
- **Live tracking = ephemeral firehose** — Redis + Kafka + WebSocket; 75k pings/sec must stay off the transactional DB.
- **Consistency split** — strong for orders/payments, eventual for discovery/tracking.
- **State machine is multi-actor** — restaurant, rider, system all drive transitions; Order Service owns it; audit every transition.
- **Geo-shard by city** — a food marketplace is inherently local.
- **Headline SLA metric** — order-to-delivery time vs promised ETA.

### Related notes

- [Food Ordering & Delivery — HLD & LLD](food-ordering-hld-lld.md) — **all tables (DDL), full APIs, class design, design patterns**, state machines, algorithms, sequences
- [BookMyShow — System Design](bookmyshow-system-design.md) — seat locking, payments, outbox+saga (reuse the order/payment core)
- [Notification System — System Design](notification-system-design.md) — order-update notifications, WebSocket tracking, Kafka pipeline
- [Apache Kafka](../concepts/kafka.md) — the event backbone connecting order → dispatch → tracking
- [Consistent Hashing](../concepts/consistent-hashing.md) · [Caching Strategies](../concepts/caching-strategies.md) · [Idempotency](../concepts/idempotency.md)
