# Ride-Hailing — System Design (Uber / Ola / Lyft)

> **Core challenge:** match a **rider** to the best nearby **driver** in real time, track the trip live, price it (with **surge**), and settle payment — geospatially, at massive scale, with **seconds** of matching latency. Signature problems: **real-time geo-matching/dispatch**, a **location firehose**, **ETA/routing**, and **surge pricing**.

---

## Contents

- [1. Mental Model](#1-mental-model)
- [2. Requirements](#2-requirements)
- [3. Capacity Estimation](#3-capacity-estimation)
- [4. API Design](#4-api-design)
- [5. High-Level Architecture](#5-high-level-architecture)
- [6. Geospatial Index (how "nearby" works)](#6-geospatial-index-how-nearby-works)
- [7. Driver Matching / Dispatch (in depth)](#7-driver-matching--dispatch-in-depth)
- [8. Trip Lifecycle — State Machine](#8-trip-lifecycle--state-machine)
- [9. Location Pipeline & Live Tracking](#9-location-pipeline--live-tracking)
- [10. ETA & Routing](#10-eta--routing)
- [11. Surge / Dynamic Pricing](#11-surge--dynamic-pricing)
- [12. Payments](#12-payments)
- [13. Data Model (all tables)](#13-data-model-all-tables)
- [14. Sequences](#14-sequences)
- [15. Consistency & Correctness](#15-consistency--correctness)
- [16. Design Patterns (that can be used)](#16-design-patterns-that-can-be-used)
- [17. Scaling & Failure](#17-scaling--failure)
- [18. Interview Cheat Sheet](#18-interview-cheat-sheet)
- [19. Final Takeaways](#19-final-takeaways)

---

## 1. Mental Model

```
Rider requests → Matching finds the best nearby available driver → driver accepts
   → driver navigates to pickup → trip starts → live-tracked → ends → fare + payment → both rate
```

A two-sided real-time marketplace (**riders** + **drivers**) connected by a **dispatch/matching engine** over a **geospatial index** of live driver locations. (Same family as food-delivery dispatch, minus the restaurant prep leg.)

---

## 2. Requirements

**Functional**
- Rider: set pickup/drop, see nearby cars + ETA + fare estimate, request, track driver live, pay, rate.
- Driver: go online/offline, receive requests, accept/reject, navigate, start/end trip, see earnings.
- Matching: assign the best driver; reject/timeout fallback; pooling (shared rides) optional.
- Surge pricing in high-demand areas.

**Non-functional**
- **Low matching latency** (seconds), **real-time** location, **high availability**, huge scale, **geo-accuracy**.
- **Strong consistency** for trips/payments; **eventual** for location/discovery.

---

## 3. Capacity Estimation

```
Active drivers online (peak)   ~ 1M (per large market; global = more)
Location pings every 4s        1M / 4 ≈ 250,000 writes/sec   ← the volume monster
Ride requests (peak)           ~ 5,000/sec
Concurrent trips tracked       ~ millions (each pushes driver GPS to its rider)

Location firehose:
  250k pings/sec × ~50 bytes = ~12 MB/s ingest, but the WRITE RATE is the problem
  → in-memory geo store (Redis GEO) + a stream (Kafka); NEVER the transactional DB
Persistent connections:
  drivers + active riders on WebSockets → stateful gateways (like the chat system)
Storage:
  trips 10M/day × ~2 KB ≈ 20 GB/day → partition by time + archive; location history → downsample to a lake
```

> Two cost centers: the **location firehose** (250k writes/sec) → Redis + stream, and **matching latency** → an in-memory geo index. The trip/payment DB is small but must be strongly consistent.

---

## 4. API Design

```
# Rider
POST /v1/rides/estimate      { pickup, drop, product }   → { fare, eta, surgeMultiplier, quoteId }
POST /v1/rides               (Idempotency-Key) { quoteId, pickup, drop } → { rideId, status: SEARCHING }
GET  /v1/rides/{id}          WS /v1/rides/{id}/track       # live status + driver location
POST /v1/rides/{id}/cancel
POST /v1/rides/{id}/rate     { rating, comment }

# Driver
POST /v1/drivers/{id}/status    { online: true, lat, lng }
POST /v1/drivers/{id}/location  { lat, lng, heading, ts }  # high-frequency ping
WS   /v1/drivers/{id}/offers                                # receive ride offers in real time
POST /v1/rides/{id}/accept | /reject
POST /v1/rides/{id}/arrived | /start | /end
GET  /v1/drivers/{id}/earnings
```

> `estimate` returns a **quoteId** locking the surge/fare; `rides` is **idempotent** (a retried request tap doesn't create two rides). Offers/tracking use **WebSocket** for real-time push.

---

## 5. High-Level Architecture

```
Rider/Driver apps → API Gateway / WebSocket gateways
   ├── Trip Service         (RDBMS)      owns the trip state machine
   ├── Matching/Dispatch    (Redis GEO driver index)  the hard part
   ├── Location Service     (Redis GEO + Kafka stream)  the firehose
   ├── Pricing Service      (surge + fare)
   ├── ETA/Routing Service  (OSRM/Maps + live traffic)
   ├── Payment Service      (+ outbox, ledger)
   └── Notification + Tracking (WebSocket)
                 │
              Kafka (RIDE_REQUESTED · DRIVER_ASSIGNED · TRIP_STARTED · TRIP_ENDED · location stream)
```

---

## 6. Geospatial Index (how "nearby" works)

To answer "idle drivers within 3 km of pickup" fast, index driver locations by **spatial cell** (never scan all drivers).

| Technique | Idea | Note |
| --- | --- | --- |
| **Geohash** | (lat,lng) → base32 string; shared prefix ≈ proximity | Query the cell + 8 neighbors; simple |
| **QuadTree** | Recursively split space until ≤K points/node | Adapts to density (deeper where dense) |
| **S2 (Google)** | Sphere → 64-bit cells via Hilbert curve | Great range queries |
| **H3 (Uber)** | **Hexagonal** hierarchical grid | Uniform neighbor distance → ideal for dispatch/supply-demand |
| **Redis GEO** (`GEOADD`/`GEOSEARCH`) | Built-in radius/box search | Practical online serving layer |

- **As drivers move**, their cell entry updates on each ping (`GEOADD` overwrites). The index is **constantly churning** — hence in-memory.
- **Shard the geo-index by region/city** — a Bangalore request never needs Delhi drivers → locality + isolation.
- Uber uses **H3 hexagons** because equal-distance neighbors make **supply/demand per cell** (for surge) and radius search clean.

```
candidates = GEOSEARCH drivers:online FROMLONLAT <lng> <lat> BYRADIUS 3 km ASC  filter status=idle
```

---

## 7. Driver Matching / Dispatch (in depth)

The signature problem — a real-time assignment/optimization.

### Greedy (nearest-first) — baseline

```
onRideRequest(pickup):
  candidates = nearby idle drivers (geo index)
  score(driver) = f(ETA-to-pickup via routing,   # not straight-line — real road time!
                    driver rating, acceptance rate, heading/direction, idle time/fairness)
  offer to top driver → wait for accept (timeout ~10–15s)
    accepted → atomic claim → trip = DRIVER_ASSIGNED
    reject/timeout → offer next candidate
```

- **ETA-to-pickup uses routing** (road network + traffic), not straight-line distance — the truly *nearest* car may be across a river.
- **Atomic driver claim** (`SET driver:{id}:lock rideId NX EX 30`) so two concurrent rides can't grab the same driver.

### Batch matching (min-cost bipartite) — better in dense areas

```
Every 1–2 seconds, over a POOL of open requests + available drivers:
  build a cost matrix (request × driver) using ETA/score
  solve min-cost assignment (Hungarian / auction algorithm)
  → globally better than greedy (minimizes total wait), at the cost of a 1–2s batching delay
```

| Strategy | Trade-off |
| --- | --- |
| **Greedy / nearest** | Simple, low latency; locally—not globally—optimal |
| **Batch (min-cost matching)** | Globally better assignment; small added latency; more compute |

### Ride pooling (UberPool)

- Match multiple riders whose routes overlap to one driver → detour tolerance + shared fare. Turns matching into a routing/optimization problem (which riders + what order).

> **Interview framing:** candidate generation via the **geo index** → **scoring** (ETA/rating/direction, often ML) → **offer/timeout/fallback loop** → **atomic claim**; batch-match in dense markets. Emphasize ETA-by-road, not straight-line.

---

## 8. Trip Lifecycle — State Machine

```
REQUESTED ─match→ DRIVER_ASSIGNED ─arrive→ DRIVER_ARRIVED ─start→ IN_PROGRESS ─end→ COMPLETED ─→ (fare+pay)
    │ no driver / cancel                       │ cancel (fees may apply)
    ▼                                          ▼
 NO_DRIVERS / CANCELLED  ◄──────────────────  CANCELLED (rider/driver/system)
```

| State | Set by | Notes |
| --- | --- | --- |
| REQUESTED | System | searching for a driver |
| DRIVER_ASSIGNED | Matching | atomic claim done |
| DRIVER_ARRIVED | Driver | at pickup |
| IN_PROGRESS | Driver | trip started; live tracking |
| COMPLETED | Driver | → compute fare + charge |
| CANCELLED / NO_DRIVERS | Any / System | cancellation fee logic by stage |

Trip Service **owns** the state machine; every transition writes `trip_status_history` and emits a Kafka event (tracking/pricing/notifications/analytics).

---

## 9. Location Pipeline & Live Tracking

```
Driver app ──GPS every ~4s──► Location Service
   → GEOADD drivers:online <lng> <lat> driver:{id}     # update match index
   → SET loc:driver:{id} {lat,lng,heading} EX 30        # latest position
   → publish to Kafka (sampled)                         # analytics + ETA + tracking
   → if driver is ON a trip → push to that rider's WebSocket (live map)
```

- **250k pings/sec never touch the trip DB** — Redis GEO (index) + latest-value + Kafka stream only.
- **Only active trips** push GPS to a rider; idle-driver pings just refresh the match index.
- **Adaptive ping rate** (faster near pickup, slower when far) saves battery/bandwidth.
- WebSocket gateways manage rider/driver connections (same connection-registry idea as the chat system).

---

## 10. ETA & Routing

- **Routing engine** (OSRM / Google Maps / Valhalla) over the road graph with **live traffic** for ETA-to-pickup and trip duration/distance.
- **Precompute** cell-to-cell travel times for fast estimates; refine with live data.
- Real maps use **contraction hierarchies** (precomputed shortcuts) — not live Dijkstra continent-wide (see the Maps note).
- ETA is often **ML-adjusted** from historical trips (time of day, weather); treat as a black box, emphasize inputs + continuous refinement + push updates.

---

## 11. Surge / Dynamic Pricing

```
per geo cell (H3 hexagon), over a short window:
   surge_multiplier = f(open_requests / available_drivers)     # demand ÷ supply

fare = base_fare + per_km × distance + per_min × duration
     ) × surge_multiplier
     + tolls + taxes + booking_fee
```

- Computed **per cell** from live demand vs supply; a stream job updates `surge_zones` every few seconds.
- Surge **sheds/shifts demand** (some riders wait) and **pulls drivers** toward hot cells (they see a heatmap).
- **Lock the quote** at estimate time (idempotent `quoteId`) so the price doesn't jump between estimate and request.

---

## 12. Payments

- **Idempotent charge** at trip end; wallet/card/UPI/cash; auto-receipt (reuse the Payment System design).
- **Fare split:** driver payout vs platform commission → a **double-entry ledger**; payout batched.
- **Refunds/adjustments** for disputes; **outbox** so a completed trip reliably triggers charge + payout.
- **Upfront pricing** (charge the quoted fare) vs metered (compute from actual distance/time).

---

## 13. Data Model (all tables)

```sql
CREATE TABLE riders   ( rider_id BIGINT PRIMARY KEY, name TEXT, phone VARCHAR(20), rating NUMERIC(2,1) );
CREATE TABLE drivers  ( driver_id BIGINT PRIMARY KEY, name TEXT, phone VARCHAR(20),
                        vehicle_id BIGINT, status VARCHAR(20) DEFAULT 'OFFLINE', rating NUMERIC(2,1), city_id BIGINT );
CREATE TABLE vehicles ( vehicle_id BIGINT PRIMARY KEY, driver_id BIGINT, type VARCHAR(20), plate VARCHAR(20) );

CREATE TABLE trips (
    trip_id BIGINT PRIMARY KEY,                     -- Snowflake
    idempotency_key VARCHAR(255) UNIQUE,
    rider_id BIGINT NOT NULL, driver_id BIGINT,
    status VARCHAR(30) NOT NULL,
    pickup_lat DOUBLE PRECISION, pickup_lng DOUBLE PRECISION,
    drop_lat DOUBLE PRECISION, drop_lng DOUBLE PRECISION,
    quote_id VARCHAR(64), fare INT, surge_multiplier NUMERIC(3,2) DEFAULT 1.0,
    distance_m INT, duration_s INT,
    requested_at TIMESTAMP, assigned_at TIMESTAMP, started_at TIMESTAMP, ended_at TIMESTAMP
);
CREATE INDEX idx_trips_rider ON trips(rider_id, requested_at DESC);

CREATE TABLE trip_status_history ( trip_id BIGINT, status VARCHAR(30), actor VARCHAR(20), at TIMESTAMP );
CREATE TABLE ride_offers ( offer_id BIGINT PRIMARY KEY, trip_id BIGINT, driver_id BIGINT,
                           status VARCHAR(20), offered_at TIMESTAMP, responded_at TIMESTAMP ); -- OFFERED/ACCEPTED/REJECTED/TIMEOUT
CREATE TABLE payments ( payment_id BIGINT PRIMARY KEY, trip_id BIGINT, amount INT, method VARCHAR(20),
                        status VARCHAR(20), idempotency_key VARCHAR(255) UNIQUE );
CREATE TABLE driver_earnings ( driver_id BIGINT, trip_id BIGINT, payout INT, at TIMESTAMP );
CREATE TABLE surge_zones ( cell_id VARCHAR(20) PRIMARY KEY, multiplier NUMERIC(3,2), updated_at TIMESTAMP );
CREATE TABLE reviews ( review_id BIGINT PRIMARY KEY, trip_id BIGINT, by VARCHAR(10), rating SMALLINT, comment TEXT );
CREATE TABLE outbox ( id BIGINT PRIMARY KEY, event_type VARCHAR(50), payload JSONB, published BOOLEAN DEFAULT FALSE );
```

> Live driver positions = **Redis GEO** (`drivers:online`) + `loc:driver:{id}`, **not a table**. Surge = a small hot table/cache updated by a stream job.

---

## 14. Sequences

### Request → match → trip

```
Rider  TripSvc  Matching  RedisGEO  Driver   Payment  Kafka
  │ req  │         │          │        │        │        │
  ├─────►│ trip=REQUESTED     │        │        │        │
  │      ├─ RIDE_REQUESTED ──────────────────────────────►│
  │      │         ├─ GEOSEARCH nearby idle ─►│            │
  │      │         ├─ score + offer best ────► │ (WS)      │
  │      │         │◄──────── accept ──────────┤           │
  │      │         ├─ atomic claim (SET NX) ──►│            │
  │      │◄─ DRIVER_ASSIGNED ─┤        │        │           │
  │◄─ assigned + driver info ──────────────────────────────┤
  │      │  ... driver arrives → start → live GPS to rider ...
  │      ├─ trip end → fare → charge ─────────►│           │
  │      ├─ TRIP_ENDED (outbox → Kafka) ───────────────────►│  → payout, receipt, analytics
```

### Cancellation

```
cancel(trip):  if status in [DRIVER_ASSIGNED, DRIVER_ARRIVED] → maybe cancellation fee
               trip = CANCELLED; release driver lock; re-offer driver to pool; notify both
```

---

## 15. Consistency & Correctness

| Concern | Mechanism |
| --- | --- |
| Duplicate ride request | `UNIQUE(idempotency_key)` on trips |
| Two rides grab same driver | **Atomic Redis claim** `driver:{id}:lock` (`SET NX EX`) |
| Double charge | Idempotent payment key |
| Lost event after trip end | **Outbox** + relay |
| Location firehose | Redis + stream only; never the trip DB |
| Surge quote drift | Lock the fare with a `quoteId` at estimate time |
| Trip state races (multi-actor) | State machine + `UPDATE ... WHERE status = <expected>` |
| Driver on 2 trips | Claim lock + `drivers.status=ON_TRIP` guard |

---

## 16. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Strategy** | Matching (greedy vs batch/min-cost), pricing (normal/surge), ETA model | Swap algorithms |
| **State** | Trip lifecycle | Guard legal transitions |
| **Observer / Pub-Sub** | Kafka events + WebSocket tracking | Decouple; fan-out |
| **Ports & Adapters** | Routing, payment, maps, geo-index | Swap providers |
| **Saga / Orchestration** | Request→match→trip→payment, with compensation (cancel/refund) | Distributed txn |
| **Outbox** | Reliable event emit (trip end → charge/payout) | No dual-write loss |
| **Circuit Breaker** | Maps/payment calls | Fail fast on provider issues |
| **Spatial Index** | Geohash/S2/H3/Redis GEO | Fast nearby search |
| **Decorator / Chain** | Fare composition (base + distance + time + surge + tax) | Stack fare rules |
| **Repository / Factory** | Data access; vehicle/notification creation | Testable, extensible |

---

## 17. Scaling & Failure

- **Geo-shard by city/region** — rides are local → locality + isolation; matching engine stateless + horizontally scaled per region.
- **Location firehose** → Redis GEO + Kafka; downsample history to a lake; never the DB.
- **No driver found** → widen radius, retry, raise surge to attract drivers, time out gracefully with a clear message.
- **Driver cancels / no-shows** → re-match from the pool; atomic lock prevents double assignment.
- **Payment fails** → outbox/reconciliation, retry, dispute flow.
- **Region overload (New Year's Eve)** → autoscale, batch-match, surge to balance demand/supply.
- **WebSocket gateway crash** → clients reconnect; trip state is durable in the DB so nothing is lost.

---

## 18. Interview Cheat Sheet

> **"How do you find nearby drivers fast?"**
> "A geospatial index — geohash/S2/**H3 hexagons** (Uber) or Redis GEO. Index online drivers by cell; `GEOSEARCH` a radius around pickup and rank candidates by **ETA-to-pickup via routing** (not straight-line), rating, and direction."

> **"How do you match and avoid double-assigning a driver?"**
> "Offer to the top-scored candidate with an accept timeout and fallback to the next; on accept, **claim the driver with an atomic Redis lock** (`SET NX`) so two rides can't grab the same one. In dense markets, **batch-match** every 1–2s solving a min-cost assignment for a globally better result."

> **"How does the 250k-pings/sec location load not kill the DB?"**
> "It never hits the transactional DB — pings update **Redis GEO** (match index) + a latest-value key and stream to **Kafka**; only active trips push GPS to the rider's WebSocket. History is downsampled to a lake."

> **"Surge pricing?"**
> "Per H3 cell, multiplier = demand/supply over a short window (a stream job updates it). **Lock the quote** at estimate time so the price is stable; surge sheds demand and pulls drivers to hot cells."

> **"Consistency model?"**
> "Strong for trips and payments (idempotency, atomic claim, outbox); eventual for driver location and nearby-driver discovery."

---

## 19. Final Takeaways

- **Real-time geo-matching** over a **spatial index** (H3/S2/geohash/Redis GEO) is the core; rank by **ETA-by-road**, not straight-line.
- **Atomic driver claim** + offer/timeout/fallback loop; **batch min-cost matching** in dense areas; pooling is a routing problem.
- **Location firehose → Redis + Kafka**, never the transactional DB; only active trips stream GPS to riders.
- **Surge** = demand/supply per cell; **lock the quote** at estimate time.
- **Trip state machine** owned by Trip Service; **outbox** drives charge/payout; **double-entry ledger** for payments.
- **Geo-shard by city**; strong consistency for money, eventual for location.
- Patterns: Strategy, State, Observer/Pub-Sub, Saga, Outbox, Spatial Index, Circuit Breaker, Ports&Adapters.

### Related notes

- [Food Ordering — System Design](food-ordering-system-design.md) — sibling dispatch/tracking problem
- [Google Maps / Proximity](maps-proximity-system-design.md) — geospatial indexing + routing depth
- [Payment System](payment-system-system-design.md) · [Apache Kafka](../concepts/kafka.md) · [Consistent Hashing](../concepts/consistent-hashing.md)
