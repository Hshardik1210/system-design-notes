# Ride-Hailing — System Design (Uber / Ola / Lyft)

> **Core challenge:** match a **rider** to the nearest suitable **driver** in real time, track the trip live, compute fare (with **surge**), and handle payments — all geospatially, at massive scale, with a tight matching latency. The signature problems are **real-time geo-matching/dispatch**, **live location at scale**, **ETA/routing**, and **surge pricing**.

---

## Contents

- [1. Mental Model](#1-mental-model)
- [2. Requirements](#2-requirements)
- [3. Capacity Estimation](#3-capacity-estimation)
- [4. API Design](#4-api-design)
- [5. High-Level Architecture](#5-high-level-architecture)
- [6. Geospatial Index & Driver Matching](#6-geospatial-index--driver-matching)
- [7. Trip Lifecycle — State Machine](#7-trip-lifecycle--state-machine)
- [8. Live Location & Tracking](#8-live-location--tracking)
- [9. ETA & Routing](#9-eta--routing)
- [10. Surge / Dynamic Pricing](#10-surge--dynamic-pricing)
- [11. Payments](#11-payments)
- [12. Data Model (all tables)](#12-data-model-all-tables)
- [13. Design Patterns (that can be used)](#13-design-patterns-that-can-be-used)
- [14. Scaling & Failure](#14-scaling--failure)
- [15. Interview Cheat Sheet](#15-interview-cheat-sheet)
- [16. Final Takeaways](#16-final-takeaways)

---

## 1. Mental Model

```
Rider requests → Matching finds nearest available driver → driver accepts
   → driver navigates to pickup → trip starts → live-tracked → ends → fare + payment → rate
```

Two-sided real-time marketplace: **riders** and **drivers**, connected by a **dispatch/matching engine** over a **geospatial index** of driver locations. (Very close to food-delivery dispatch, minus the restaurant leg.)

---

## 2. Requirements

**Functional**
- Rider: set pickup/drop, see nearby cars + ETA + fare estimate, request ride, track driver live, pay, rate.
- Driver: go online/offline, receive ride requests, accept/reject, navigate, start/end trip, see earnings.
- Matching: assign the best driver; handle reject/timeout fallback.
- Surge pricing in high-demand areas.

**Non-functional**
- **Low matching latency** (seconds), **real-time** location, **high availability**, huge scale, **geo-accuracy**.
- Strong consistency for trips/payments; eventual for location/discovery.

---

## 3. Capacity Estimation

```
Active drivers online (peak)   ~ 1M
Location pings every 4s        1M/4 ~ 250,000 writes/sec   ← the volume monster
Ride requests (peak)           ~ 5,000/sec
Concurrent trips tracked       ~ millions
Storage: trips 10M/day * ~2KB  ~ 20 GB/day (partition + archive)
```

> Location pings dominate → **Redis GEO + streaming**, never the transactional DB.

---

## 4. API Design

```
# Rider
POST /v1/rides/estimate      { pickup, drop }         → { fare, eta, surge }
POST /v1/rides               (Idempotency-Key)         → { rideId, status: SEARCHING }
GET  /v1/rides/{id}          WS /v1/rides/{id}/track
POST /v1/rides/{id}/cancel
POST /v1/rides/{id}/rate     { rating }

# Driver
POST /v1/drivers/{id}/status      { online: true, lat, lng }
POST /v1/drivers/{id}/location    { lat, lng, ts }     # high frequency
POST /v1/rides/{id}/accept | /reject
POST /v1/rides/{id}/start | /end
```

---

## 5. High-Level Architecture

```
Rider/Driver apps → API Gateway
   ├── Ride/Trip Service (RDBMS)        owns trip state machine
   ├── Matching/Dispatch Service        (Redis GEO driver index)
   ├── Location Service                 (Redis GEO + stream)
   ├── Pricing Service                  (surge, fare)
   ├── ETA/Routing Service              (maps/OSRM + traffic)
   ├── Payment Service
   └── Notification + Tracking (WebSocket)
                 │
              Kafka (RIDE_REQUESTED, DRIVER_ASSIGNED, TRIP_STARTED, TRIP_ENDED, location stream)
```

---

## 6. Geospatial Index & Driver Matching

### Indexing driver locations

Divide the map into cells and index drivers by cell so "nearby drivers" is a fast lookup:

| Technique | Note |
| --- | --- |
| **Geohash** | Prefix = proximity; query neighbor cells |
| **S2 cells / QuadTree** | Hierarchical cells (Uber uses **H3** hexagons) |
| **Redis GEO** (`GEOADD`/`GEOSEARCH`) | Practical online index; radius search |

### Matching flow

```
onRideRequest(pickup):
    candidates = GEOSEARCH drivers:online near pickup (e.g. 3 km), status=idle
    rank by: ETA-to-pickup (routing), driver rating, acceptance, direction
    offer to best driver → accept within N seconds
    on reject/timeout → next candidate
    on accept → atomic claim (SET driver:{id}:lock NX) → trip = DRIVER_ASSIGNED
```

- **Batch matching** every 1–2s can be used in dense areas (global min-cost assignment) instead of pure greedy.
- **Atomic driver claim** prevents two rides grabbing the same driver.

---

## 7. Trip Lifecycle — State Machine

```
REQUESTED ─match→ DRIVER_ASSIGNED ─arrive→ DRIVER_ARRIVED ─start→ IN_PROGRESS ─end→ COMPLETED
    │ no driver / cancel                                   
    ▼                                                       
 CANCELLED (rider/driver/system)  ← can cancel pre-start (fees may apply)
```

| State | Set by |
| --- | --- |
| REQUESTED | System (searching) |
| DRIVER_ASSIGNED | Matching |
| DRIVER_ARRIVED | Driver (at pickup) |
| IN_PROGRESS | Driver (trip start) |
| COMPLETED | Driver (trip end) → fare + payment |
| CANCELLED | Any party |

Trip Service owns the state machine; transitions emit Kafka events (tracking, notifications, pricing, analytics).

---

## 8. Live Location & Tracking

```
Driver app ──GPS every ~4s──► Location Service
    → GEOADD drivers:online   (update index for matching)
    → SET loc:driver:{id} {lat,lng} EX 30   (latest)
    → publish to Kafka (sampled)
    → WebSocket push to the rider tracking this trip
```

- 250k pings/sec → **Redis + stream only**; never the trip DB.
- Only **active trips** are tracked to the rider; idle-driver pings just update the match index.

---

## 9. ETA & Routing

- Use a **routing engine** (OSRM/Google Maps/Valhalla) with live traffic for ETA-to-pickup and trip duration/distance.
- Precompute travel times between cells for fast estimates; refine with live data.
- Often ML for demand/ETA; treat as a black box, emphasize inputs + continuous refinement.

---

## 10. Surge / Dynamic Pricing

```
surge_multiplier(cell) = f(demand/supply ratio in the cell over a short window)

fare = base_fare
     + per_km * distance
     + per_min * duration
   then × surge_multiplier
     + tolls/taxes
```

- Compute per **geo cell** from live demand (open requests) vs supply (idle drivers).
- Surge **sheds/shifts demand** and pulls drivers to hot areas.
- Show the multiplier upfront; **lock the quote** at request time (idempotent estimate).

---

## 11. Payments

- Idempotent charge at trip end; wallet/card/UPI/cash; auto-receipt.
- Fare split (driver payout vs platform commission).
- Refunds/adjustments for disputes; reuse the standard payment + outbox pattern.

---

## 12. Data Model (all tables)

```sql
CREATE TABLE riders   ( rider_id BIGINT PRIMARY KEY, name TEXT, phone VARCHAR(20), rating NUMERIC(2,1) );
CREATE TABLE drivers  ( driver_id BIGINT PRIMARY KEY, name TEXT, phone VARCHAR(20),
                        vehicle_id BIGINT, status VARCHAR(20) DEFAULT 'OFFLINE', rating NUMERIC(2,1), city_id BIGINT );
CREATE TABLE vehicles ( vehicle_id BIGINT PRIMARY KEY, driver_id BIGINT, type VARCHAR(20), plate VARCHAR(20) );

CREATE TABLE trips (
    trip_id       BIGINT PRIMARY KEY,          -- Snowflake
    idempotency_key VARCHAR(255) UNIQUE,
    rider_id      BIGINT NOT NULL, driver_id BIGINT,
    status        VARCHAR(30) NOT NULL,
    pickup_lat DOUBLE PRECISION, pickup_lng DOUBLE PRECISION,
    drop_lat   DOUBLE PRECISION, drop_lng   DOUBLE PRECISION,
    fare INT, surge_multiplier NUMERIC(3,2) DEFAULT 1.0, distance_m INT, duration_s INT,
    requested_at TIMESTAMP, assigned_at TIMESTAMP, started_at TIMESTAMP, ended_at TIMESTAMP
);
CREATE INDEX idx_trips_rider ON trips(rider_id, requested_at DESC);

CREATE TABLE trip_status_history ( trip_id BIGINT, status VARCHAR(30), actor VARCHAR(20), at TIMESTAMP );
CREATE TABLE ride_offers ( offer_id BIGINT PRIMARY KEY, trip_id BIGINT, driver_id BIGINT,
                           status VARCHAR(20), offered_at TIMESTAMP, responded_at TIMESTAMP );
CREATE TABLE payments ( payment_id BIGINT PRIMARY KEY, trip_id BIGINT, amount INT, method VARCHAR(20),
                        status VARCHAR(20), idempotency_key VARCHAR(255) UNIQUE );
CREATE TABLE driver_earnings ( driver_id BIGINT, trip_id BIGINT, payout INT, at TIMESTAMP );
CREATE TABLE surge_zones ( cell_id VARCHAR(20) PRIMARY KEY, multiplier NUMERIC(3,2), updated_at TIMESTAMP );
CREATE TABLE reviews ( review_id BIGINT PRIMARY KEY, trip_id BIGINT, by VARCHAR(10), rating SMALLINT, comment TEXT );
CREATE TABLE outbox ( id BIGINT PRIMARY KEY, event_type VARCHAR(50), payload JSONB, published BOOLEAN DEFAULT FALSE );
```

> Live driver positions = **Redis GEO** (`drivers:online`), not a table.

---

## 13. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Strategy** | Matching (greedy vs batch), pricing (normal vs surge), ETA | Swap algorithms |
| **State** | Trip lifecycle | Guard legal transitions |
| **Observer / Pub-Sub** | Kafka events + WebSocket tracking | Decouple; fan-out |
| **Ports & Adapters** | Routing, payment, maps, geo-index | Swap providers |
| **Saga / Orchestration** | Request→match→trip→payment with compensation (cancel/refund) | Distributed txn |
| **Outbox** | Reliable event emit | No dual-write loss |
| **Circuit Breaker** | Maps/payment calls | Fail fast on provider issues |
| **Publisher-Subscriber (Geo pub/sub)** | Location updates → subscribers | Efficient fan-out |
| **Factory** | Vehicle/notification creation | Extensible types |
| **Repository** | Data access | Testable domain |
| **Decorator/Chain** | Fare composition (base + distance + time + surge + tax) | Stack fare rules |

---

## 14. Scaling & Failure

- **Geo-shard by city/region** — a ride is local; isolate load + locality.
- **Location firehose** in Redis + Kafka; downsample for history.
- **No driver found** → widen radius, retry, surge; time out gracefully.
- **Driver cancels** → re-match; **atomic driver lock** avoids double assignment.
- **Payment fails** → outbox/reconciliation; retry; dispute flow.
- Matching engine is stateless + horizontally scaled; Redis GEO sharded by region.

---

## 15. Interview Cheat Sheet

> **"How do you find nearby drivers fast?"**
> "Geospatial index — geohash/S2/H3 or Redis GEO. Index online drivers by cell; `GEOSEARCH` a radius around pickup, rank by ETA-to-pickup and rating."

> **"How do you match and avoid double-assigning a driver?"**
> "Offer to the best candidate with an accept timeout and fallback; on accept, claim the driver with an atomic Redis lock so two rides can't grab the same one. In dense areas, batch-match every 1–2s for a globally better assignment."

> **"How does surge pricing work?"**
> "Per geo cell, multiplier = f(demand/supply). Lock the quote at request time. It sheds demand and pulls drivers to hot cells."

> **"How is live tracking done at scale?"**
> "Drivers ping GPS every few seconds → Redis GEO + Kafka; WebSocket pushes to the rider. 250k pings/sec never touch the trip DB."

> **"Consistency model?"**
> "Strong for trips/payments; eventual for location and nearby-driver discovery."

---

## 16. Final Takeaways

- **Real-time geo-matching** over a **geospatial index** (geohash/S2/H3/Redis GEO) is the core.
- **Atomic driver claim** + offer/timeout/fallback loop; batch-match in dense areas.
- **Location firehose → Redis + Kafka**, never the transactional DB.
- **Surge** = demand/supply per cell; **lock the quote** at request time.
- **Trip state machine** owned by Trip Service; events drive tracking/pricing/payments.
- **Geo-shard by city**; strong consistency for money, eventual for location.
- Patterns: Strategy, State, Observer/Pub-Sub, Saga, Outbox, Ports&Adapters, Circuit Breaker.

### Related notes

- [Food Ordering — System Design](food-ordering-system-design.md) — sibling dispatch/tracking problem
- [Apache Kafka](../concepts/kafka.md) · [Consistent Hashing](../concepts/consistent-hashing.md) · [Caching Strategies](../concepts/caching-strategies.md)
