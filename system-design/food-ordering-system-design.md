# Food Ordering & Delivery — System Design (Swiggy / Zomato / Uber Eats)

> **Core challenge:** connect **three parties** — customers, restaurants, and delivery partners — to get **hot food delivered in ~30 minutes**. The hard, distinctive problems are **hyperlocal discovery** (what can reach me *right now*), **real-time delivery-partner dispatch** (assign the best rider), **live GPS tracking**, and **ETA prediction** — all under a tight, perishable SLA. The order-management core (cart → order → payment → state machine) is largely the **same as any e-commerce app**; the fulfillment layer is what's different.

> **How to read this doc:** each section has the dense interview summary first, then a **deep dive** (annotated Java/SQL, and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand.

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
- [22. Consistency & CAP Tradeoffs](#22-consistency--cap-tradeoffs)
- [23. Surge & Dynamic Pricing](#23-surge--dynamic-pricing)
- [24. Design Patterns (that can be used)](#24-design-patterns-that-can-be-used)
- [25. Final Takeaways](#25-final-takeaways)

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

### Three parties brought together

When you open **Swiggy** and order a biryani, three different parties have to be coordinated, in the right order, within half an hour:

- **You (the customer)** — hungry, want food *now*, want to watch it come to your door.
- **The restaurant** — cooking dozens of orders at once, needs to know what to cook and when.
- **The rider (delivery partner)** — on a bike somewhere in the city, needs to be told "go pick up order #9001 from Paradise Biryani and take it to this flat."

The platform is the coordinator sitting in the middle. It never cooks food and never rides a bike — it just makes sure the right message reaches the right party at the right moment, keeping restaurant, rider, and customer in sync.

### Why "30 minutes" is such a big deal (Amazon takes 2 days and nobody complains)

**Food goes cold and soggy**. With Amazon, if a package is an hour late, you shrug. With food, an hour late means the biryani is inedible → you demand a refund → the platform *loses money on that order*. This one fact — **food is perishable** — is why almost every hard design choice (fast dispatch, live tracking, tight ETAs) exists.

### What actually happens between "I tap Order" and "food arrives"

Follow one order end to end (this exact list drives the whole doc):

```java
// The life of one order, in plain steps
placeOrder();          // you pay
notifyRestaurant();    // "new order! start cooking"
findRider();           // platform searches for a nearby free rider
riderPicksUp();        // rider reaches restaurant, grabs the food
trackToDoor();         // your app shows the bike moving on a map
markDelivered();       // rider hands it over
askForRating();        // "how was your biryani?"
```

Every section below zooms into one of these steps.

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

### "Buying a phone" vs "ordering dinner"

Both start the *same way*: you browse, add to cart, pay. That first half is basically identical — that's why the summary says "50% is done" if you've built Flipkart. The **second half** (getting the thing to you) is where they split.

Flipkart fulfillment is slow and asynchronous: the item travels through warehouses and couriers over days, with coarse shipment tracking. Food delivery fulfillment is real-time: a free rider must be found *right now*, near the restaurant, and the customer watches them approach on a live map.

```java
// The part that's the SAME (reuse it)
Cart cart = buildCart(items);
Order order = orderService.place(cart, idempotencyKey);  // Flipkart does this too
payment.charge(order);

// The part that's DIFFERENT (food-delivery-specific)
Restaurant r = restaurant.notifyAndAwaitAccept(order);   // a human starts cooking now
Rider rider = dispatch.findBestNearbyRider(r.location()); // live matchmaking
tracking.streamRiderLocationToCustomer(rider, order);     // real-time map
// ...all under a 30-minute clock
```

#### Q: If the order part is "the same," why not just copy Flipkart entirely?

Because Flipkart's fulfillment assumes **days and warehouses**. It has no concept of "find a free human on a bike within 3 km in the next 10 seconds," or "the food is ready NOW, hurry." Those real-time, location-aware, human-in-the-loop problems simply don't exist in classic e-commerce, so there's nothing to copy — you build them fresh.

### Is the restaurant like a Flipkart warehouse?

Sort of, but with two twists: (1) it **cooks on demand** (nothing is pre-packed on a shelf), and (2) its "inventory" is basically a set of **on/off switches** — "we're open," "paneer tikka is sold out" — not a count of "37 units in stock." That's simpler to model but changes in **real time** during a busy dinner rush.

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

### What the three users each need

Requirements read like a dry checklist, but they're just "what does each person want to be able to do?" Picture the three apps:

- **Customer app:** "Show me places that'll actually deliver to my flat, let me order and pay, and let me watch my food come." 
- **Restaurant tablet:** the little device on the counter that goes *ding!* — "new order, accept or reject, tell us when it's ready."
- **Rider app:** "Turn me online, ping me offers, guide me to pickup then dropoff, show my earnings."

```java
// Requirements = the buttons each person can press
interface CustomerApp { search(); addToCart(); pay(); trackOrder(); rate(); }
interface RestaurantApp { setMenuAvailable(item, false); accept(order, prepMins); reject(order); }
interface RiderApp { goOnline(); acceptOffer(offer); markPickedUp(); markDelivered(); pingLocation(); }
```

### "Strong consistency for orders, eventual for discovery/tracking" in plain words

- **Strong consistency (orders/payments):** the answer must be *exactly right, immediately*. If you paid, the order **must** exist and you **must** be charged exactly once. No "give it a second to sync." Money is involved.
- **Eventual consistency (discovery/tracking):** "close enough, corrects itself in a moment" is fine. If a restaurant just marked a dish sold-out and it takes 3 seconds to vanish from search, nobody gets hurt. If one GPS ping is lost, the next one fixes the map.

> **Rule of thumb:** ask "would a human be angry or lose money if this were slightly stale?" If yes → strong. If they'd never notice → eventual.

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

### The numbers, and why one of them dominates

Capacity estimation is just back-of-the-envelope arithmetic to find **which path carries the most traffic**. Here the surprise is not orders — it's **location pings**.

- ~5M orders/day sounds huge, but spread over a day it's only ~58 orders/sec (a few hundred at peak). A normal database handles that easily.
- The dominant one: **300,000 riders**, each phone sending its GPS location **every 4 seconds**. That's `300,000 ÷ 4 = 75,000` writes **every single second**, all day — ~1,300× more traffic than orders.

Orders are a steady, manageable trickle. Location pings are a firehose of high-frequency, disposable data. You'd never pour that into the transactional order DB; it goes to a store built for high-speed ephemeral data (**Redis + Kafka**).

```java
// The single most important takeaway from the math:
double orderWritesPerSec    = 5_000_000 / 86_400.0;   // ~58  → tiny, put in the real DB
double locationWritesPerSec = 300_000 / 4.0;          // 75,000 → NEVER put in the order DB

// So: locations live in fast, ephemeral stores (Redis), orders live in a durable RDBMS.
```

### Why compute the "browse:order ratio"

For every 1 person who orders, ~50 people are *just browsing* the home screen. So **reads massively outnumber writes** on discovery → that's the signal to lean on **caches and a search index**, not the main database. The math tells you where to spend your engineering effort.

### Do you need to memorize these exact numbers?

No. The point isn't "58.02 orders/sec" — it's the **conclusion**: location is the volume monster (→ Redis/stream), browse dominates orders (→ cache), and peaks are spiky (→ autoscale). Numbers are just the argument for those design moves.

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

### Three *styles* of API for three *kinds* of action

The APIs above look like a random list, but they split into **three communication styles**, each chosen for a reason:

| Style | Used for | Why |
| --- | --- | --- |
| **Request → wait for answer** (normal HTTP) | Placing an order | You *must* know "did it work? am I charged?" before moving on |
| **Fire-and-forget** (send, don't wait) | Rider GPS pings | 75k/sec — you can't afford to wait, and a lost ping doesn't matter |
| **Server pushes to you** (WebSocket) | Live tracking | The *server* has news (rider moved) and pushes it to your phone without you asking |

Placing an order blocks until it returns "confirmed." A rider ping is sent without waiting for any reply. Tracking is a stream the server pushes as the rider moves — the client just listens.

```java
// 1) Synchronous + idempotent — you WAIT for the result
Response r = POST("/v1/orders", body, header("Idempotency-Key", clientId));
// r.status == "PENDING_PAYMENT"  → now you know it worked

// 2) Fire-and-forget — send and immediately move on, no waiting
POST("/v1/riders/42/location", {lat, lng, ts});   // one of 75,000 this second

// 3) Server-push — you open a socket ONCE, then just listen
WebSocket ws = connect("/v1/orders/9001/track");
ws.onMessage(update -> map.moveRiderMarker(update.lat, update.lng));  // updates arrive on their own
```

### What the `Idempotency-Key` header is actually for

It's a **"this is the same request as before, don't do it twice" tag**. If your phone's network hiccups and the app re-sends "Place Order," both requests carry the *same* key. The server sees the key already used and returns the **existing** order instead of making a second one (and a second charge). One tap = one order, even if the message physically arrives twice. (More in §10.)

### Why placing an order is synchronous but tracking is a WebSocket

You *place* an order once and need an immediate yes/no — a normal request/response fits. But tracking means dozens of updates flow **from server to you** over several minutes; opening a fresh HTTP request for each would be wasteful. A **WebSocket** is one long-lived pipe the server can keep pushing through.

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

### Reading the architecture diagram

The diagram is a gateway, a set of independent services, an event log, and a push layer.

- **API Gateway = the single entry point.** Every app (customer, restaurant, rider) talks here first. It authenticates the caller, rate-limits abuse, and routes to the right service. No app talks to the internal services directly.
- **The row of services = independent specialists.** Each does *one job*: Discovery finds restaurants, Order handles orders, Dispatch finds riders, Payment takes money. They don't step on each other.
- **Each service has its own store.** Discovery uses a geo-index, Order uses a relational DB, Location uses Redis. Nobody shares a database (so one busy service can't slow another).
- **Kafka = the event backbone.** When something important happens ("order placed!"), the service *publishes it once* to Kafka, and every interested consumer (notifications, dispatch, analytics) reads it — without the publisher needing to know who's listening.
- **WebSocket Service = the real-time push layer** connected to the customer and rider phones for live updates.

```java
// A customer request's journey through the diagram
gateway.authenticate(request);              // gateway verifies the caller
Restaurants list = discoveryService.near(lat, lng);   // routed to the Discovery service
Order o = orderService.place(cart);         // routed to the Order service
kafka.publish(new OrderPlaced(o.id));       // publish the event — ONCE
// dispatch, notification, analytics each react on their own, independently
```

### Why so many services instead of one big program

The pieces have **wildly different needs**. Location handles 75k writes/sec and can lose data; orders handle money and must never lose data; discovery is read-heavy. Splitting them lets you **scale and tune each independently** — add 50 location servers during dinner without touching the payment code. It also means a crash in "ratings" can't take down "checkout."

### What "Kafka is the event backbone" really means

It means services **don't call each other directly** for after-the-fact news. Instead of Order Service phoning Dispatch, Notification, *and* Analytics one by one (and breaking if one is down), it just drops **one message** — `ORDER_PLACED` — onto Kafka. Each interested service picks it up on its own schedule. Add a new listener later (say, a loyalty-points service) and *nobody else changes*. That's decoupling.

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

### Who does what

Each service has one clear responsibility, mapped to a plain-English job: Discovery returns restaurants that can deliver to you right now; Cart holds the pre-checkout order; Order Service owns the order and its state machine and coordinates the rest; Payment charges/refunds/holds the wallet; Dispatch finds and assigns a free rider; Location ingests each rider's GPS; Notification sends "your food is on the way" updates. "Orchestrates" just means the Order Service coordinates the others — but doesn't cook or drive.

```java
// "Orchestrates" = the Order Service tells others what to do, but doesn't do their jobs
class OrderService {
    Order place(Cart cart) {
        Order o = createOrder(cart);          // its OWN job: own the order
        payment.charge(o);                    // call the Payment service
        kafka.publish(new OrderPlaced(o.id));  // publish event; dispatch & notify react
        return o;                             // it never picks a rider or sends SMS itself
    }
}
```

### Why each service "owns its own store" instead of sharing one database

If everyone wrote to one giant database, they'd fight over it and a schema change for ratings could break orders. Instead each service **privately owns its data** and others must ask via its API. This is the **service-ownership boundary**: the Location Service is the *only* one that touches location data. It keeps teams and failures isolated.

### What "Order Service owns the state machine" means here

It means only the Order Service is allowed to change an order's status (`PLACED → ACCEPTED → …`). When the restaurant hits "accept," it doesn't reach into the order database itself — it **asks** the Order Service to move the state. One owner = one source of truth = no conflicting edits. (Full state machine in §11.)

---

## 8. Data Model & Schema

### Database & storage choices (which DB, and why at scale)

§7's "Store" column already hinted at this, but it's worth pulling into one place: this system is **polyglot persistence by necessity**, because "orders" and "where's my rider right now" have almost opposite shapes — one is low-volume and must never be wrong, the other is a 75k-writes/sec firehose where a lost value is harmless. The deciding question per data type is *"does this need strong consistency, or is it a disposable high-frequency signal?"*

| Data | Store | Why this one | Why not the alternative |
| --- | --- | --- | --- |
| Orders, payments, order state machine (**source of truth**) | **RDBMS**, sharded by **region/city** (or `customer_id`) | Orders are a **transactional state machine** with money attached — `idempotency_key` UNIQUE + ACID transactions guarantee "one tap = one order, one charge" (§10), and every transition must be durably auditable (`order_status_history`). Modest volume (~58 orders/sec avg, §4) means an RDBMS handles it comfortably. | An eventually-consistent NoSQL store can't give you the "charge once, create the order once" all-or-nothing guarantee without you rebuilding transactions by hand — not worth it when the write volume doesn't demand NoSQL's scale in the first place. |
| Restaurant/menu discovery | **Elasticsearch** (geo-index), fed via CDC from the catalog RDBMS (see 💡 below) | Hyperlocal search needs geo-radius + text + facet filtering (open, veg, rating) at ~30k searches/sec (§4) — a search index is built for exactly this, and it's rebuilt async so it never touches the order DB. | Querying the transactional catalog DB directly for every browse would hammer the same store that must stay fast and available for order writes. |
| Rider proximity index ("who's near this restaurant") | **Redis GEO** | `GEOADD`/`GEOSEARCH` answers "riders within 3km" in-memory, sub-ms — dispatch runs this query continuously as part of a real-time loop (§12), which an RDBMS spatial query is too slow for at this frequency. | Storing rider positions relationally and querying with lat/lng range scans doesn't scale to a live, constantly-updating index queried every dispatch cycle. |
| Cart (pre-checkout) | **Redis**, ephemeral, no durability needed | A cart is a draft that's cheap to lose — if Redis restarts mid-session, the user just re-adds items. No transaction, no audit trail required until checkout. | Persisting carts to the order RDBMS would pollute the durable, auditable order table with throwaway drafts that outnumber real orders many times over. |
| Rider GPS pings (the volume monster — 75k writes/sec) | **Redis** (latest-value + TTL) **+ Kafka** stream; only aggregates archived to a time-series/warehouse store | Every ping only matters for a few seconds (§13) — you need the *latest* dot, not a permanent row per ping. Redis overwrite + short TTL handles this at zero durability cost, and Kafka fans it out to the WebSocket tracking layer. | Writing every ping into the orders RDBMS would be ~1,300× the order write volume (§4) hitting the one store that absolutely cannot be slowed down — it would take down the transactional path for data nobody needs to keep. |
| Kafka event backbone | **Kafka** | Decouples `ORDER_PLACED`/`ORDER_READY`/`DELIVERED` from every downstream reactor (dispatch, notifications, analytics) — add a new consumer without touching the producer. | Direct service-to-service calls on every event couple services to each other's uptime/latency and lose events on a crash mid-fan-out. |
| Menu/restaurant images | **Blob store + CDN** | Large, immutable bytes served from the edge — cheap, fast, and keeps binary data out of the relational catalog. | Storing images in the DB bloats rows and backups for no query benefit. |

> 💡 **What is CDC (Change Data Capture)?** A way to **stream every insert/update from a database to other systems** by tailing its write-ahead log (e.g. **Debezium** → Kafka). Instead of the catalog service explicitly pushing "menu changed" messages, CDC watches the catalog DB and automatically emits each change, which the search index consumes to stay fresh. It keeps the read model (Elasticsearch) in sync with the source of truth (RDBMS) **without dual-writes**.

**Why orders must be relational, and GPS pings absolutely must not be:** the split comes straight from §4's capacity math — orders are a manageable ~58/sec average that needs to be **exactly right** (money, state transitions), while location pings are a **75,000/sec firehose** that only needs to be **roughly current**. Putting both in the same store would force you to either slow down the order path to survive the location firehose, or relax correctness on money to handle the volume — neither is acceptable, so they're deliberately isolated into stores that match their actual shape. **Scaling:** shard the order RDBMS by **region/city** (§17) — a food marketplace is inherently local, so a Bangalore order never needs a Delhi row, which keeps shards small and isolates a regional incident from the rest of the country. Redis GEO and the location stream scale horizontally by simply adding nodes, since they hold no durable state that requires careful partitioning. (See [Databases — Deep Dive](../concepts/databases-deep-dive.md).)

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

### Indexes that matter

The order table is small by row-count but **queried hard from three directions** — customers, restaurants, and ops dashboards — so a few targeted indexes matter more than the row count suggests:

```sql
-- 1) Customer "my orders" screen (already above) — newest first
CREATE INDEX idx_orders_customer  ON orders (customer_id, placed_at DESC);

-- 2) Restaurant dashboard: "my live orders" filtered by status
CREATE INDEX idx_orders_restaurant_status ON orders (restaurant_id, status);

-- 3) Rider's current/past assignments
CREATE INDEX idx_orders_rider     ON orders (rider_id);

-- 4) Ops/analytics dashboards & archival: slice by time window
CREATE INDEX idx_orders_placed_at ON orders (placed_at);

-- 5) Timeout sweeper: find stuck orders to auto-cancel (see §11)
--    Partial index keeps it tiny — only rows still awaiting the restaurant.
CREATE INDEX idx_orders_status_sweep ON orders (status, placed_at)
    WHERE status IN ('PLACED','PENDING_PAYMENT');
```

| Index | Serves |
| --- | --- |
| `(customer_id, placed_at DESC)` | Customer order history, reorder |
| `(restaurant_id, status)` | Restaurant tablet: pending / active orders |
| `(rider_id)` | Rider's assigned + completed orders |
| `(placed_at)` | Ops dashboards, hourly volume, archival cutoffs |
| `(status, placed_at)` partial | **Status-sweep** job that auto-cancels silent orders |

> 💡 **Why a partial (filtered) index for the sweeper?** The auto-cancel job only ever asks "which orders are *still* `PLACED`/`PENDING_PAYMENT` and too old?" — a tiny slice. A partial index over just those statuses stays small and fast even as millions of `DELIVERED` rows pile up, so the periodic sweep never does a full-table scan.

### What each table stores

What each table holds:

- **`orders`** = the **receipt** for one order — who, which restaurant, how much, current status, which rider.
- **`order_items`** = the **line items** on that order ("2× biryani, 1× coke"). Note the prices are **snapshotted** — copied at order time — so if the restaurant raises prices tomorrow, *this* order's total doesn't change.
- **`order_status_history`** = the **audit log** — every status change stamped with who did it. This powers the timeline you see as "Order accepted 7:02, Out for delivery 7:19."
- **`restaurants` / `menu_items`** = the **catalog** — menu entries plus location and open/closed flags.
- **Rider location (Redis)** = the **live GPS position**, deliberately *not* in the database.

```java
// Why prices are snapshotted onto the order (not looked up live)
orderItem.price = menuItem.price;   // COPY the price NOW, at order time
// If the restaurant edits menuItem.price later, this order's total is frozen & correct.
```

```sql
-- Notice money is stored as INT (paise/cents), never floating point:
subtotal INT NOT NULL,   -- 48000 means ₹480.00
-- Floats round badly (0.1 + 0.2 != 0.3); with money that becomes lost rupees. Use integers.
```

### Why keep a separate `order_status_history` table instead of just one `status` column

The `status` column tells you where the order is **now** ("Out for delivery"). But you also want the **story**: *when* did the restaurant accept, *when* did the rider pick up, *who* cancelled? The history table records every transition, which powers the customer's tracking timeline, debugging ("why was this late?"), and audits. One column = current snapshot; the history table = the movie.

### Why rider location is the ONE thing not in the SQL database

It changes **75,000 times per second** across all riders and it's disposable — you only ever care about the *latest* dot, and a lost ping is harmless. A transactional SQL database is built for durable, carefully-locked writes (orders, payments); flooding it with 75k throwaway updates/sec would grind it to a halt. So live location goes to **Redis** (fast, in-memory, has built-in geo search) with a short TTL, and only *aggregated* history is archived. **Right tool for the data's shape.**

### What `GEOADD` / Redis GEO is doing

It's a special Redis feature that stores points by latitude/longitude and lets you ask **"who's within 3 km of here?"** instantly. That's exactly the question dispatch asks ("which riders are near this restaurant?") and discovery asks ("which restaurants are near this user?"). It's a map with a built-in "find nearby" button. (Used heavily in §9 and §12.)

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

### "Restaurants near me" is a maps problem, not a text-search problem

When you open Swiggy, the home screen isn't "all restaurants" — it's "restaurants that can reach **my** flat, **right now**." That's a **geography** question. The naive approach — check every restaurant in the country and compute distance — is hopeless at scale. So we chop the map into **cells** and only look at the cells near you.

You don't scan every restaurant on earth; you only look at the map cell you're in and the cells touching it. **Geohash / S2 cells** are those map squares, given short codes so "same area" = "same code prefix."

```java
// Naive & doomed: measure distance to EVERY restaurant (millions)
for (Restaurant r : ALL_RESTAURANTS)          // ❌ way too slow
    if (distance(user, r) < 5_km) results.add(r);

// Geo-index way: only look inside the map-cells near the user
String myCell = geohash(userLat, userLng, precision = 6);   // e.g. "tdr1y2"
List<Restaurant> nearby = geoIndex.restaurantsInCellsAround(myCell);  // a tiny handful
```

But "nearby" isn't enough — three more filters make it *actually orderable*:

```java
List<Restaurant> results = new ArrayList<>();
for (Restaurant r : geoSearch(userLat, userLng, radiusKm)) {
    if (!r.isOpen())                     continue;   // closed → skip
    if (distance(user, r) > r.serviceableRadius) continue;   // won't deliver this far
    if (!ridersAvailable(r.area()))      continue;   // nobody to deliver → skip (or longer ETA)
    results.add(r);
}
results.sort(byEtaThenRating());   // best/nearest/fastest on top
```

### "Nearby" vs "serviceable"

"Nearby" = physically close to you. "Serviceable" = the restaurant is **willing and able** to deliver *to your exact address*. A place 500 m away across a river with no bridge is nearby but not serviceable. Serviceability checks the restaurant's delivery radius/polygon **and** whether riders are actually free in that area right now.

### Why discovery reads from Elasticsearch instead of the main orders/restaurant database

Two reasons. (1) **Volume** — ~30k searches/sec would hammer the transactional DB. (2) **Different job** — search needs "find nearby + filter veg + sort by rating" fast, which a search index (Elasticsearch) is built for. So we keep a **separate read-optimized copy** of restaurant data, kept fresh from the real DB via CDC/Kafka. The order DB is never touched by browsing.

### A restaurant marks a dish sold-out — why it's OK if search shows it for a few more seconds

Discovery is **eventually consistent** and the truth is re-checked at checkout. Worst case, you add a sold-out item to your cart and at checkout the system says "sorry, that's unavailable." Mildly annoying, not dangerous. Compare to orders/payments, where a few seconds of staleness could double-charge you — *that's* why those stay strongly consistent. Speed where it's safe, correctness where it matters.

---

## 10. Cart & Order Placement

### Cart

Ephemeral, per-user, in **Redis**. Recompute price/coupons server-side at checkout (never trust client totals). Re-validate item availability and prices at checkout time (menu may have changed).

### Order placement (synchronous, idempotent)

- Idempotency check → validate (restaurant open? items available? price unchanged? address serviceable?) → compute total → create order (`PENDING_PAYMENT`) in one transaction → initiate payment → on success flip to `PLACED` and emit `ORDER_PLACED` → return confirmation + ETA. (Full annotated version in **The checkout, step by step** deep dive below.)

> **Idempotency** (same pattern as the Notification / e-commerce notes): unique `idempotency_key` on `orders` → a retried "Place Order" tap never creates two orders or two charges.

### Then the async fulfillment kicks off (event-driven)

```
ORDER_PLACED (Kafka) → Restaurant Service (notify restaurant to accept)
                     → Dispatch Service (start looking for a rider)
                     → Notification Service (confirm to customer)
```

### The checkout, step by step

The cart is a scratch pad — it lives in Redis, is cheap, and can be thrown away (you might never check out). The moment you hit **Place Order**, the flow becomes careful and exact: money changes hands, so it must happen exactly once and produce a durable record.

```java
Order placeOrder(PlaceOrderRequest req) {
    // 1) Have we seen this exact tap before? (network retries send it twice)
    Order existing = orders.findByIdempotencyKey(req.idempotencyKey());
    if (existing != null) return existing;          // ← same tap → same order, no double charge

    // 2) Re-check reality NOW (the menu may have changed since you added to cart)
    validate(req.restaurantOpen(), req.itemsAvailable(), req.priceUnchanged(), req.addressServiceable());

    // 3) Server computes the price — NEVER trust the amount the phone sends
    int total = subtotal + deliveryFee + surge - discount;

    // 4) Save the order in ONE transaction, status = PENDING_PAYMENT
    Order o = orders.insert(req, total, PENDING_PAYMENT);

    // 5) Take the money
    payment.charge(o);

    // 6) On success, flip to PLACED and shout it once
    o.status = PLACED;
    kafka.publish(new OrderPlaced(o.id));            // restaurant + dispatch + notify all react
    return o;   // 7) hand back confirmation + ETA
}
```

### Why re-validate at checkout if the cart already had valid items

Time passed between "add to cart" and "pay." The restaurant may have **closed**, the dish may have **sold out**, or the **price** may have changed. The cart is a hopeful draft; checkout is the moment of truth, so everything is re-checked against live data right before charging you.

#### Q: Why never trust the total the app sends?

Because anyone can tamper with what the phone sends ("pay ₹1 for a ₹500 order"). The server **recomputes** the price from the real menu, coupons, and fees. The client's number is only for display. Rule: **the server is the source of truth for money.**

### What "then async fulfillment kicks off" means

Once you're charged and the order is `PLACED`, the platform doesn't make you wait while it finds a rider and notifies the restaurant. It **fires an event** (`ORDER_PLACED`) and returns your confirmation *immediately*. Behind the scenes, dispatch, the restaurant tablet, and notifications all react in parallel. You get instant confirmation; the slow coordination happens after, driven by Kafka.

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

### The order state machine

A **state machine** is the fixed set of "stages" an order can be in, plus the **only legal moves** between them. An order can't jump from `PLACED` straight to `DELIVERED` — it must pass through each stage in order, and each move is triggered by a *specific actor*.

This is exactly the "Order Accepted → Preparing → Out for Delivery → Delivered" progress bar you watch in the app. Each filled step is a state; each new step lights up when a different actor does something.

```java
enum OrderStatus {
    PENDING_PAYMENT, PLACED, ACCEPTED, REJECTED, RIDER_ASSIGNED,
    PREPARING, RIDER_AT_RESTAURANT, OUT_FOR_DELIVERY, DELIVERED, CANCELLED, PAYMENT_FAILED
}

// The rules: from each state, only certain next states are allowed
Map<OrderStatus, Set<OrderStatus>> LEGAL = Map.of(
    PLACED,           Set.of(ACCEPTED, REJECTED, CANCELLED),
    ACCEPTED,         Set.of(RIDER_ASSIGNED, PREPARING, CANCELLED),
    OUT_FOR_DELIVERY, Set.of(DELIVERED)                       // no going back from here
);

void transition(Order o, OrderStatus next, Actor who) {
    if (!LEGAL.get(o.status).contains(next))
        throw new IllegalTransition(o.status + " ✗→ " + next);  // guard: block illegal jumps
    o.status = next;
    history.record(o.id, next, who, now());   // write the audit log (order_status_history)
    kafka.publish(new OrderStatusChanged(o.id, next));  // drives notifications + live tracking
}
```

### Why a food order's state machine is more complex than Flipkart's

**Three different actors** drive it in real time. The **restaurant** accepts and cooks, the **rider** picks up and delivers, and the **system** handles payment and timeouts — all within 30 minutes. Flipkart mostly has "placed → shipped → delivered" over days, driven by one warehouse process. More actors + tighter clock = more states and more possible transitions (and cancellations from any of them).

### "Dispatch runs in parallel with prep" — what this means

Notice `RIDER_ASSIGNED` and `PREPARING` can happen at the **same time**. You don't wait for the food to be cooked before hunting for a rider — you look for a rider *while* the kitchen cooks, so the rider shows up right as the food is ready. If you did them one after another, every order would take prep-time **plus** rider-search-time, blowing the 30-min SLA.

### Why guard transitions instead of just setting `status = whatever`

This prevents impossible or fraudulent states — e.g. marking an order `DELIVERED` when it was never picked up, or reviving a `CANCELLED` order. The guard is a bouncer: it only allows moves that make real-world sense, so the data always reflects a physically possible situation.

### Restaurant accept/reject — and the silent-restaurant timeout

`PLACED → ACCEPTED / REJECTED` is a **human-in-the-loop** transition: a person at the restaurant taps the tablet. Humans forget, get slammed during the dinner rush, or leave the tablet in the back room. Since the customer is already **charged** and a 30-minute clock is ticking, the system can never wait forever — every `PLACED` order carries an **accept deadline** (e.g. 90s), and a background job resolves anything that blows it.

```
On ORDER_PLACED:
  start accept-timer (e.g. 90s)

Restaurant taps ACCEPT (prepTimeMins) → status = ACCEPTED → cancel timer → dispatch continues
Restaurant taps REJECT (reason)       → status = REJECTED → refund → notify customer → suggest alternatives
Timer fires, still PLACED             → treat as "silent" → run the auto-cancel saga (below)
```

**Auto-cancel saga (compensation on a stuck order):**

```
1. Sweeper finds PLACED orders older than the deadline   (uses idx_orders_status_sweep, §8)
2. Guarded transition PLACED → CANCELLED (actor = SYSTEM) -- atomic, so a late human ACCEPT loses the race
3. Compensations, each idempotent + retried:
     - refund the payment            (money back — the un-do of the charge)
     - release any tentatively-assigned rider  (DEL rider lock, §12)
     - emit ORDER_CANCELLED to Kafka → notify customer, dashboards
4. If the restaurant is repeatedly silent → suppress it from discovery (below)
```

> ⚠️ **Race to guard:** the restaurant might tap **Accept** at the exact moment the timeout fires. Because the transition is a **guarded, atomic state change** (§11), only one wins — if `CANCELLED` commits first, the late `ACCEPT` fails the `LEGAL`-transitions check and the tablet shows "order expired." Never let both proceed, or you'd cook food for a refunded order.

**Discovery suppression when a restaurant is offline/unresponsive:** a restaurant that is closed, logged-out, or repeatedly ignoring orders should stop appearing in search *before* more customers order from it. The signal (`is_open = false`, or an "auto-offline after N misses" flag) is pushed into the discovery index (CDC / short-TTL cache, §9) so new searches skip it within seconds — eventual consistency is fine here, since the order path already re-validates `restaurant.is_open` at checkout (§10).

#### Q: What happens if the restaurant never responds at all?

The order does **not** hang forever. An **accept deadline** turns "no response" into a definite outcome: once the timer expires, the system itself moves the order `PLACED → CANCELLED`, **auto-refunds** the customer, releases any rider that was being lined up, and notifies the customer (usually with nearby alternatives). Persistent non-response also **auto-marks the restaurant offline** so it drops out of discovery. The rule mirrors payments: *never leave a charged customer in limbo* — resolve every order to a terminal state (delivered or refunded), even when a human simply goes quiet.

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

- On `ORDER_PLACED` (and when food is nearly ready): geo-search idle candidate riders near the restaurant (~3 km) → score each (distance, direction match, current load, rating, time-to-food-ready, fairness) → offer to the best with an accept timeout → on reject/timeout offer the next → on accept set `RIDER_ASSIGNED`. (Full annotated version in **Finding the right rider for the job** deep dive below.)

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

### Finding the right rider for the job

This is the crown-jewel problem. When your order is placed, the platform must pick **one** rider out of thousands — ideally the one who gets your food to you fastest and cheapest, without leaving other riders idle.

Dispatch is an automated loop: an order comes in, the system looks at which riders are free and close, offers to the best one, and if they decline, moves to the next — running thousands of times a minute.

```java
Rider dispatch(Order order) {
    Restaurant r = order.restaurant();

    // 1) Who's even a candidate? Free riders within ~3 km of the RESTAURANT (not the customer)
    List<Rider> candidates = redisGeo.search(r.lat(), r.lng(), radiusKm = 3)
                                     .filter(Rider::isIdle);

    // 2) Score each — lower is better. This is where the "best" rider is chosen.
    candidates.sort(comparingDouble(rider ->
          w1 * distanceToRestaurant(rider, r)
        + w2 * mismatch(rider.heading(), directionToCustomer(order))  // already heading that way?
        + w3 * rider.currentLoad()                                    // busy riders score worse
        - w4 * rider.rating()                                         // good riders score better
    ));

    // 3) Offer to the best; they have N seconds to accept
    for (Rider best : candidates) {
        if (claim(best)                       // atomic lock so no one else grabs them
            && best.offerAndAwaitAccept(order, seconds = 20)) {
            order.assign(best);               // status → RIDER_ASSIGNED
            return best;
        }
        release(best);                        // rejected/timed out → try next best
    }
    return null;   // nobody available → longer ETA / "no riders" (see §18)
}

// The atomic claim — the whole trick that stops double-assignment:
boolean claim(Rider r) {
    return redis.set("rider:" + r.id() + ":lock", orderId, "NX", "EX", 30); // NX = only if unlocked
}
```

#### Q: Why search near the *restaurant*, not near me (the customer)?

Because the rider's **first job is to pick up the food**. The bottleneck is getting a rider *to the kitchen* around the time the food is ready. Where you live matters for the second leg (and for scoring "is the rider heading my way?"), but candidate selection starts at the restaurant.

#### Q: What is that `SET ... NX EX 30` lock protecting against?

Two dispatch workers running at once might both pick rider #42 for two different orders in the same instant → rider #42 gets double-booked. `NX` means "set this lock **only if nobody else has**"; the first worker wins, the second sees the lock and moves on. `EX 30` auto-expires the lock in 30s so a crashed worker can't freeze rider #42 forever. It's a **"claim it before you use it"** guard.

### "Batching" (order pooling)

If two orders come from the *same or nearby* restaurants heading in the *same direction* around the *same time*, one rider can carry **both** — pick up two bags, drop both off on one trip. Cheaper per delivery. The catch: the second customer waits a little longer, so you only batch when it won't blow either order's SLA.

### Greedy vs batch optimization — which is "right"

- **Greedy (nearest free rider):** decide each order instantly, on its own. Simple and fast, but can make globally silly choices (grabs the only rider who was perfect for a closer order arriving 2 seconds later).
- **Batch optimization:** every few seconds, look at *all* waiting orders and *all* free riders together and solve the best overall matching. Smarter globally, but adds a little delay and lots of compute.

Real systems blend them: greedy when quiet, batch during peaks. In an interview, treat the scoring function as a pluggable (often ML) black box and emphasize the **loop: candidates → score → offer → accept/timeout → fallback**.

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

### The moving dot on your map

Once your food is out, you watch the little bike icon move toward you. That icon is powered by the rider's phone sending its GPS every few seconds, and the platform relaying it to *your* phone — fast, cheaply, and without ever touching the order database.

It's a live location relay: the rider's phone is the sender, your map is the viewer, and the platform connects "this rider" to "the customers watching this order."

```java
// On the rider's phone: fire a ping every ~4 seconds, don't wait for a reply
every(4_000, () -> POST("/v1/riders/42/location", {lat, lng, ts}));

// Location Service: store only the LATEST dot (overwrite), then broadcast it
void onPing(RiderPing p) {
    redis.set("loc:rider:" + p.riderId(), p.latLng(), "EX", 30);  // latest only; expires in 30s
    kafka.publish("rider-locations", p);                          // fan out to trackers
}

// WebSocket Service: push to exactly the customers watching THIS rider's order
void onRiderMoved(RiderPing p) {
    Order o = orderOf(p.riderId());
    for (Session s : whoIsTracking(o.id()))     // connection map in Redis: ws:order:{id}
        s.push(p.latLng());                     // the customer's map marker jumps
}
```

#### Q: Why store only the *latest* location and overwrite it?

Because for a moving dot, **only "where is the rider now?" matters** — the position from 8 seconds ago is useless. Keeping every ping would pile up 75k rows/sec of instantly-stale data. So we overwrite one value per rider (with a short TTL so it auto-cleans if pings stop). Historical breadcrumbs are downsampled and archived separately, only for analytics.

### Why a WebSocket instead of the app asking "where's my rider?" every second

If the app **polled** ("any update? any update?") every second, you'd have millions of pointless requests, most returning "no change." A **WebSocket** is one open pipe: the server **pushes** a new position only when the rider actually moves. Far less traffic, and the update feels instant.

### Only `OUT_FOR_DELIVERY` orders are tracked — why not all riders

You only care about a rider's live position when they're **carrying your food**. Broadcasting all 300k riders' positions to nobody would be a massive waste. So the WebSocket fan-out is limited to active deliveries, which is a tiny slice at any moment.

### Why it's fine for tracking to be "eventually consistent"

If one GPS ping drops or arrives late, the *next* ping (4 seconds later) corrects the map — no harm done. The client even animates smoothly between updates so you don't notice. Contrast with payments, where a single lost/duplicated message means real money is wrong. Tracking is the **opposite** of the order path: speed and cheapness over perfect accuracy.

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

### Where the "32 min" comes from

The ETA is a **sum of estimates for each leg of the journey**, refined as real events happen. When you place the order it's a rough estimate; by the time the rider's near you, it's sharp. You give a rough total up front (accept + prep + travel legs) and update it as each stage completes and traffic changes.

```java
int estimateEta(Order o) {
    return timeToAccept(o.restaurant())          // how long before the kitchen says yes
         + prepTime(o.restaurant(), o.items())   // cooking time (their estimate / historical avg)
         + travelTime(rider, o.restaurant())     // rider → restaurant (routing + live traffic)
         + waitAtRestaurant()                     // buffer for pickup
         + travelTime(o.restaurant(), o.address()); // restaurant → your door (routing + traffic)
}

// It's RECOMPUTED as each real event lands, then pushed to the customer:
onEvent(RESTAURANT_ACCEPTED, o -> pushEta(o, estimateEta(o)));   // now we know prep started
onEvent(RIDER_PICKED_UP,     o -> pushEta(o, estimateEta(o)));   // now only travel-to-you remains
```

### Why the ETA keeps changing while you watch

Each real event **removes a guess and replaces it with a fact**. Before the restaurant accepts, prep time is a guess. Once the rider picks up, "time to cook" and "rider→restaurant" are done and certain — only "restaurant→you" remains, and traffic may have shifted. So the number gets more accurate (and jumps around a bit) as the order progresses.

### Where traffic data comes from

From a **routing service** (Google Maps, OSRM) that knows real roads and current congestion — the same tech that gives you driving times in a maps app. The platform asks it "how long from A to B right now?" for each travel leg.

### ETA is "often an ML model" — do you need to understand the ML?

No. In an interview, say "**treat the ETA predictor as a black box**" and focus on what feeds it (prep time, distance, traffic, hour, weather, restaurant load) and how it's **continuously refined and pushed** to the customer. The systems-design value is in the inputs and the update loop, not the model's math.

---

## 15. Payments

Same pattern as any e-commerce/BookMyShow flow:

- Gateway integration (UPI/cards/wallet); **idempotent** payment initiation.
- Order held `PENDING_PAYMENT` until confirmed; payment webhook → `PLACED`.
- **Refunds** on restaurant reject / cancellation (partial vs full depends on stage).
- **Wallet/credits**, COD (cash on delivery) as a payment method.
- Reconciliation via **outbox** so a paid order never fails to progress (dual-write safety).

> Reuse the payment design from BookMyShow / Notification notes — this is genuinely the "same as Flipkart" part.

> 💡 **Saga & compensation, in one line:** the order is a **multi-step workflow across services** (pay → accept → dispatch → deliver) that can't be one big database transaction — the steps span services and take minutes. A **saga** runs them as separate local steps; if a later step fails or times out, it triggers a **compensating action** that undoes the earlier ones. Here the classic compensation is a **refund**: if the restaurant rejects or nobody can deliver, the "charge" step is un-done by refunding. Every compensation must be **idempotent** (safe to retry) since it may fire from a background job.

### Taking money without double-charging

Payments are the classic "money must be exactly right" problem, and food delivery solves it the same way every e-commerce app does: hold the order in a **waiting** state, charge, and only advance when the payment gateway confirms.

The flow: initiate the charge, wait for the gateway to confirm via webhook, and only *then* advance the order. If the gateway declines, nothing proceeds.

```java
Order pay(Order o) {
    o.status = PENDING_PAYMENT;                     // order exists but "on hold"
    payment.initiate(o, idempotencyKey = o.id());   // idempotent: retry can't charge twice
    return o;   // we DON'T mark PLACED yet — we wait for the gateway to confirm
}

// The gateway calls US back when the money clears:
@Webhook("/payments/callback")
void onPaymentResult(PaymentResult res) {
    Order o = orders.find(res.orderId());
    if (res.success()) { o.status = PLACED;  kafka.publish(new OrderPlaced(o.id())); }
    else               { o.status = PAYMENT_FAILED; }   // nothing charged / auto-refunded
}
```

### What a "refund depends on stage" situation looks like

If the restaurant **rejects** before cooking → **full refund** (nothing was made). If you cancel *after* the food's cooked or the rider's en route → maybe a **partial refund** (someone already spent effort/ingredients). The refund amount is a function of *how far the order got* in the state machine.

### What the "outbox" is doing here (dual-write safety)

Scary scenario: payment succeeds, but the server crashes **before** it records "order is placed." Now you're charged with no order — the worst possible bug. The **outbox pattern** writes "payment done" and "publish ORDER_PLACED" in the **same database transaction**, so they can't get out of sync. A background process then reliably emits the event. Net effect: **a paid order can never get stranded.** (Same pattern as the BookMyShow/Notification notes.)

---

## 16. Ratings & Reviews

- Rate **order, restaurant, and rider** separately after delivery.
- Writes go to a reviews store; **aggregate rating** updated async (Kafka consumer → recompute avg) — don't update the hot restaurant row synchronously on every review.
- Ratings feed back into **discovery ranking** and **dispatch scoring**.

### Don't recompute the average on the hot row

After delivery you rate the food, the restaurant, and the rider separately. The tricky bit isn't storing your stars — it's **updating the restaurant's average rating** without hammering a super-popular row.

A very popular restaurant might get 10,000 reviews a day. If every review synchronously recomputed and rewrote the restaurant's average, that single row becomes a contention bottleneck (a "hot row"). Instead, store each review and let a background worker recompute the average.

```java
// WRONG — every review locks & rewrites the hot restaurant row (contention)
void addReview(Review r) {
    restaurant.avg = recomputeAverage(r.restaurantId());  // ❌ synchronous on a hot row
}

// RIGHT — just store the review + announce it; update the average asynchronously
void addReview(Review r) {
    reviews.insert(r);                       // cheap write
    kafka.publish(new ReviewAdded(r));       // fire and forget
}

@KafkaListener("reviews")
void recompute(ReviewAdded e) {              // background worker, off the hot path
    restaurant.avg = rollingAverage(e.restaurantId());   // update when convenient
}
```

### Why a slightly-stale average rating is OK

A restaurant's rating drifting from 4.31 to 4.32 a few seconds late affects nobody. It's **eventually consistent**, like discovery. The synchronous, must-be-exact treatment is reserved for money and order state — not for a star average.

### Why rate the restaurant and rider separately

They're different jobs and both feed back into the system: restaurant ratings influence **discovery ranking** (better places surface higher), and rider ratings influence **dispatch scoring** (better riders get offered more/better orders). Blending them would hide who's actually responsible when something goes wrong.

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

### Growing without falling over

Scaling just means "handle 100× the users without falling over." Each part of the system grows in the way that fits its traffic shape — you already met these shapes in the capacity math (§4). Partition orders by city (geo-sharding), add stateless service instances only at mealtimes (autoscale), and cache hot read data instead of recomputing it.

```java
// The four scaling moves, matched to the four kinds of traffic:
discovery.scale()  = geoIndex + cachePerGeohashCell;   // reads dominate → cache hard
location.scale()   = redisGeo + kafkaStream;            // 75k writes/sec → keep OFF the SQL DB
orders.scale()     = rdbms.shardBy(city).partitionBy(placedAt);  // durable, but split by region
peaks.scale()      = autoscaleStatelessServices + kafkaBuffersBursts + surgePricing;
```

### What "geo-sharding by city" is, and why it fits food delivery so well

Sharding = splitting your data across machines so no single one holds everything. For food, the natural split is **geography**: a Bangalore order will *never* need a Delhi rider or a Delhi restaurant. So you keep each city's orders, riders, and restaurants on their own shard. Benefits: queries stay **local and fast**, and a problem in one city's shard **doesn't affect** others. A food marketplace is inherently local, so the data splits cleanly.

### How Kafka helps during the lunch/dinner rush

When 10× the normal orders hit at 8 PM, downstream services (dispatch, notifications) might not keep up instantly. Kafka acts as a **shock absorber / buffer**: events pile up safely in the queue and get processed as fast as possible, instead of overwhelming and crashing a service. Combined with **autoscaling** (spinning up more servers during peaks) and **surge pricing** (gently reducing demand), the spike is smoothed out.

### Why you can "autoscale stateless services" but not the database so easily

A **stateless** service (like discovery or the API gateway) holds no data of its own — any copy can handle any request, so you just add more copies during peaks and remove them after. Databases hold **state** (the actual orders), so scaling them means the harder work of sharding/replication. Rule: **make services stateless where possible so scaling is just "add more boxes."**

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

### Things WILL go wrong — plan the graceful recovery

Real life is messy: no riders free, the restaurant ignores the tablet, a rider bails mid-trip, the network drops your tap twice. Good design isn't "prevent all failure" — it's **"when X breaks, degrade gracefully instead of losing money or an order."** Every failure gets a pre-planned response so the core path keeps running.

```java
// Each failure has a pre-planned response, not a crash:
onNoRidersAvailable(order)  -> showLongerEta(order); queueForRetry(order); surgeToAttractRiders();
onRestaurantSilent(order)   -> after(timeout, () -> { autoCancel(order); refund(order); });
onRiderCancelsMidway(order) -> reDispatch(order); notifyCustomer(order); recomputeEta(order);
onDuplicateTap(req)         -> return existingOrderByIdempotencyKey(req);   // one order only
onRedisDown()               -> tracking.degradeToLastKnownLocation();       // orders unaffected
```

#### Q: The scariest one — "payment succeeds but order creation fails." How is it handled?

This is where you could **charge someone and give them nothing**. The fix is the **idempotency key + outbox + reconciliation** combo (§10, §15): the payment and the order-record are tied together so they can't drift apart, and a reconciliation job sweeps up any stragglers — auto-refunding if the order truly can't be recovered. The guiding rule: **never take money without either delivering an order or refunding.**

### Why a Redis/location outage does NOT break orders

Location lives in a **completely separate store** from orders (a deliberate design choice, §8). If Redis dies, live tracking degrades to "last known position + ETA" — annoying but survivable — while the order itself, safe in the SQL database, keeps progressing normally. **Isolating the risky, high-volume data protects the critical path.**

### What "load-shed non-critical" means during overload

When the system is drowning, it **temporarily switches off nice-to-haves** (personalized recommendations, fancy ranking) to save capacity for the essentials (placing orders, dispatching riders, taking payments). Better to serve a plain-but-working app than to crash trying to serve a fancy one.

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

### The dashboards that tell you if you're healthy

Observability = the metrics and alerts that let you notice trouble *before* customers complain. You don't just watch servers ("is CPU high?"); you watch the **business** ("are orders arriving late?"). The vital signs are: how long orders take, how often "no rider" happens, and how many payments fail. If one drops, an alert fires.

```java
// The one metric that matters most:
metric("order_to_delivery_time")   // promised ETA vs reality — the whole business is judged on it
    .alertIf(actual > promisedEta * 1.5);   // deliveries slipping → wake someone up

// Other vital signs, grouped:
gauge("no_rider_rate");           // can't find riders → demand/supply imbalance
gauge("payment_success_rate");    // money flowing?
gauge("kafka_consumer_lag");      // is the event backbone falling behind?
gauge("geo_index_freshness");     // is discovery showing stale restaurants?
```

### Why track *business* metrics, not just CPU/memory

Servers can look perfectly healthy while customers are miserable — e.g. CPU is fine but every delivery is 20 minutes late because riders are scarce. **Business metrics catch problems the infra metrics miss.** The headline one, **order-to-delivery time vs promised ETA**, directly measures the promise the whole product is built on.

### What "Kafka consumer lag" is, and why alert on it

Kafka carries the published events; a **consumer** (like the notification or dispatch service) reads them. "Lag" = how far *behind* the consumer is. Rising lag means events are piling up faster than they're processed → notifications and dispatch start arriving late. It's an early warning that a downstream service is overwhelmed, often before users notice.

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

### How to actually talk in the interview

The single most important move: **say the framing sentence early** — "order core = same as e-commerce; the hard, unique parts are hyperlocal discovery, real-time dispatch, and live tracking." This instantly shows you know *where the real difficulty is* and stops you wasting 15 minutes designing a cart everyone already understands. Don't spend equal time on every part: call out that the order core is standard and the fulfillment layer is what's special, then spend your time there.

```java
// A rough script for the 35 minutes:
minute(0..5)   = clarify("actors? scope? scale?");
minute(5..8)   = frame("order core = e-commerce; unique = discovery + dispatch + tracking");
minute(8..12)  = estimate("orders/sec, browse:order, the 75k location firehose");
minute(12..20) = hld("services, Kafka backbone, geo-index, Redis, WebSocket");
minute(20..32) = deepDive(interviewerPicks);   // usually dispatch, geo-search, or tracking
minute(32..35) = wrapUp("consistency split, failures, geo-sharding, trade-offs");
```

### What if the interviewer just says "design Swiggy" with no guidance

Drive it yourself using the phases above: **clarify → frame → estimate → HLD → deep-dive → wrap-up.** Offer the deep-dive options ("I can go deep on dispatch, geo-search, or tracking — any preference?") so *they* steer while *you* stay in control. Proactively mention trade-offs (strong vs eventual consistency, greedy vs batch dispatch) — that's what separates a senior answer.

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

### Tricky scenarios (rapid-fire)

| Scenario | What happens / what to do |
| --- | --- |
| **Payment succeeds but order creation fails** | Idempotency key + **outbox** (payment→order in one txn) + reconciliation sweep; auto-refund if the order truly can't be recovered. Never charge without an order or a refund (§10, §15). |
| **Dispatch assigns one rider to two orders** | Atomic **Redis claim** `SET rider:{id}:lock NX EX 30` — first worker wins, the second sees the lock and moves on (§12). |
| **Restaurant goes silent (never accepts)** | Accept-deadline timer → auto-cancel saga → refund + release rider + notify; repeated silence auto-marks the restaurant offline / suppressed from discovery (§11). |
| **Discovery index stale at checkout** (sold-out item still shown) | Fine — discovery is eventually consistent; checkout **re-validates** open/available/price against live data and rejects if changed (§9, §10). |
| **Duplicate "Place Order" tap** | Idempotency key → return the existing order, one charge only (§10). |
| **Rider cancels mid-delivery** | Re-dispatch to a new rider, recompute ETA, notify customer; order state rolls back to needs-a-rider (§18). |
| **Redis / location store down** | Tracking degrades to "last known + ETA"; the order itself (in the SQL DB) keeps progressing — risky high-volume data is isolated (§8, §18). |

> **Ultimate layer model:** Idempotency = handle retries · Saga/compensation = handle failures · Outbox = guarantee event delivery · Redis claim = prevent double-assignment · Timeout sweeper = never leave an order stuck.

---

## 22. Consistency & CAP Tradeoffs

> Interviewers love: "Where do you choose consistency vs availability, and why?" Food delivery is a great answer because it needs **both**, on different paths.

| Path | Choice | Why |
| --- | --- | --- |
| **Order placement & state machine** | **CP** (strong) | Money + inventory of *your* order — a paid order must exist exactly once and transition correctly; a wrong answer loses money or trust |
| **Payments / refunds** | **CP** (strong) | Charge/refund exactly once; ambiguity here is a real financial bug |
| **Restaurant/menu discovery** | **AP** (available + eventual) | A menu edit or sold-out flag taking a few seconds to appear is harmless — availability > freshness for browse |
| **Live location & tracking** | **AP** (available + eventual) | A dropped GPS ping self-corrects on the next one; the map staying up matters more than any single point |
| **Ratings / analytics** | **Eventual** | A star average drifting a few seconds late affects nobody; recompute off the hot path |

- The **CP core** (orders, payments) lives in the RDBMS with `idempotency_key` UNIQUE + ACID transactions — a single source of truth per order.
- The **AP fulfillment layer** (discovery, location) lives in Redis / Elasticsearch / Kafka, kept fresh **asynchronously** (CDC, streams) and re-validated at the one moment correctness matters — **checkout**.

> ⚠️ **Common trap:** trying to make discovery strongly consistent (block the search until the index is perfectly fresh). That trades away availability for freshness nobody needs, and re-validation at checkout already makes staleness safe. Spend consistency budget only where money/inventory lives.

> One-liner: **"Strong consistency where money or a specific order is involved; eventual consistency for discovery, tracking, and ratings — and re-validate the stale stuff at checkout."**

---

## 23. Surge & Dynamic Pricing

> When demand outruns the supply of riders (rain, match day, lunch peak), a **delivery-fee multiplier** rebalances the two sides and keeps the SLA intact. This is the food-delivery cousin of ride-hailing surge.

### The imbalance it solves

Discovery and dispatch both depend on **enough free riders in an area**. When open orders pile up faster than idle riders can clear them, ETAs blow out and orders start failing with "no riders" (§18). Surge is the pricing lever that (a) nudges some price-sensitive demand to wait, and (b) pulls more riders online where they're needed.

### Computed per geohash cell, in near-real-time

Surge is **local** — it's raining in one neighborhood, not the whole city — so it's computed per **geohash / S2 cell** (the same cells discovery uses, §9):

```
for each geohash cell, every ~1–2 min:
    demand = open_orders + active_carts_heading_to_checkout
    supply = idle_riders_in_cell
    ratio  = demand / max(supply, 1)
    multiplier = clamp(1.0, f(ratio), CAP)      # e.g. 1.0x .. 2.5x
    publish surge:{cell} = multiplier           # read by pricing at checkout
```

- The multiplier applies to the **delivery fee** (and sometimes a small platform fee) — typically **not** the food price, which the restaurant sets.
- It's read at **checkout** and shown transparently ("Delivery fee higher due to heavy rain"), then **snapshotted onto the order** like any other price (§8) so it can't change after you pay.

### When surge applies to food vs ride-hailing

| | Food delivery | Ride-hailing |
| --- | --- | --- |
| **What surges** | Mostly the **delivery fee** (food price is the restaurant's) | The **whole trip fare** |
| **Elasticity** | Softer — people will wait for dinner, or pick a closer restaurant | Sharper — a rider *is* the product |
| **Extra lever** | Can also re-rank discovery toward **closer/faster** restaurants and enable **batching** (§12) to stretch rider supply | Fewer substitutes — price is the main lever |

### How it ties into the rest of the system

- **Dispatch (§12):** surge raises rider **supply** (more go online) and batching raises rider **throughput** — two ways to close the same gap.
- **Peaks / scaling (§17):** surge is listed as a demand-shaping tool for lunch/dinner/rain spikes — it **sheds/spreads demand** so autoscaling and Kafka buffering aren't the only defenses.
- **Consistency (§22):** the surge multiplier is an **eventually-consistent, cached** value (fine if a cell's number is a minute stale), but the **fee charged is snapshotted onto the order** at checkout (strong) so it's fixed once you pay.

> 💡 **Why per-cell and not one city-wide number?** Demand/supply imbalance is hyperlocal — one rainy suburb can be starved of riders while downtown is fine. A single city multiplier would over-charge calm areas and under-price the hot spot, failing to move riders where they're actually needed.

> ⚠️ **Pitfall:** never let surge change **after** the customer commits. Show it before payment and freeze it on the order; a fee that jumps post-checkout is both a trust and a correctness bug.

---

## 24. Design Patterns (that can be used)

> Full detail with class design in the [HLD & LLD companion](food-ordering-hld-lld.md) §B4.

These design patterns sound academic, but each is just a **named solution to a problem you already met** in this doc:

| Pattern | Where | Why — in plain words |
| --- | --- | --- |
| **Saga / Orchestration** | Order → pay → accept → dispatch → deliver, with compensation (refund) | A multi-step process where, if a later step fails, you **undo** earlier ones (refund) |
| **State** | Order lifecycle (multi-actor) | The order can only move through legal stages (§11) — the "you can't skip to Delivered" rule |
| **Strategy** | Dispatch (greedy/batch), pricing/surge, ETA | Swappable algorithms — greedy *or* batch dispatch, normal *or* surge pricing — chosen at runtime |
| **Chain of Responsibility** | Order validation (open → available → serviceable → price) | Composable checks, each one able to reject before the next runs |
| **Observer / Pub-Sub** | Kafka events → dispatch, notification, analytics | Publish "order placed" once; every interested consumer reacts independently |
| **Ports & Adapters** | Payment, maps/routing, geo-index, push | Swap providers (Razorpay → Stripe, Google Maps → OSRM) without touching core logic |
| **Outbox** | Reliable order/payment events | The "never charge without an order" safety net (§15) — write the event in the same transaction as the data |
| **Idempotency Key** | Order + payment creation | The "one tap = one order" tag (§10) that ignores duplicate requests |
| **Decorator / Chain** | Price composition (base + surge + tax − discount) | Stack pricing rules, each layer adding to the one beneath |
| **Publish-Subscribe + WebSocket** | Live rider tracking | Fan-out location updates to every customer watching that order |
| **Circuit Breaker** | Payment/maps calls | If the payment/maps provider is down, **stop calling it** and fail fast, so one failing dependency doesn't drag everything down |
| **CQRS** | Discovery (ES read model) vs order (RDBMS write) | Keep a **separate fast read copy** apart from the **write copy** — reading and writing have different needs |

```java
// Example: Strategy — swap the dispatch algorithm without touching the rest
interface DispatchStrategy { Rider pick(Order o, List<Rider> candidates); }
class NearestIdle    implements DispatchStrategy { /* fast, simple */ }
class BatchOptimizer implements DispatchStrategy { /* smarter, runs every few seconds */ }

DispatchStrategy strategy = isPeakHour() ? new BatchOptimizer() : new NearestIdle();
Rider r = strategy.pick(order, candidates);   // caller doesn't care which one it is
```

### Do you need to name-drop every pattern in an interview?

No — patterns are a **vocabulary**, not a checklist. Use one when it genuinely clarifies ("I'd use the **Saga** pattern so a failed payment cleanly refunds"). Naming a pattern you can't justify hurts more than helps. Understanding the *problem each solves* (as above) is what matters.

---

## 25. Final Takeaways

- **Three-sided marketplace** — customer, restaurant, rider; each an actor in the order state machine.
- **Order core = e-commerce core** — cart, order service, state machine, payments, idempotency, notifications are the **same as Flipkart**.
- **Fulfillment is what's different** — hyperlocal discovery, real-time dispatch, live tracking, ETA, all under a perishable ~30-min SLA.
- **Discovery = geospatial** — geohash/S2/Redis GEO/Elasticsearch; filter by open + serviceable + riders; cache per cell; eventually consistent.
- **Dispatch = real-time matching** — geo-candidate riders, score, offer/accept loop with fallback, batching, atomic rider claim; run while food cooks.
- **Live tracking = ephemeral firehose** — Redis + Kafka + WebSocket; 75k pings/sec must stay off the transactional DB.
- **Consistency split** — CP for orders/payments, AP/eventual for discovery/tracking/ratings; re-validate stale data at checkout (§22).
- **State machine is multi-actor** — restaurant, rider, system all drive transitions; Order Service owns it; audit every transition.
- **No order stays stuck** — accept-deadline timers + auto-cancel saga resolve a silent restaurant to refund; suppress it from discovery (§11).
- **Surge is a per-cell demand lever** — a delivery-fee multiplier that rebalances riders during peaks/rain and is frozen onto the order at checkout (§23).
- **Geo-shard by city** — a food marketplace is inherently local.
- **Headline SLA metric** — order-to-delivery time vs promised ETA.

> The order core is solved by **the boring, correct e-commerce toolkit** (idempotency, transactions, outbox, saga); the food-delivery magic is **pushing hyperlocal, real-time, disposable data (geo + location) into stores shaped for it** and keeping it *off* the transactional path.

### Related notes

- [Food Ordering & Delivery — HLD & LLD](food-ordering-hld-lld.md) — **all tables (DDL), full APIs, class design, design patterns**, state machines, algorithms, sequences
- [BookMyShow — System Design](bookmyshow-system-design.md) — seat locking, payments, outbox+saga (reuse the order/payment core)
- [Notification System — System Design](notification-system-design.md) — order-update notifications, WebSocket tracking, Kafka pipeline
- [Apache Kafka](../concepts/kafka.md) — the event backbone connecting order → dispatch → tracking
- [Consistent Hashing](../concepts/consistent-hashing.md) · [Caching Strategies](../concepts/caching-strategies.md) · [Idempotency](../concepts/idempotency.md)
