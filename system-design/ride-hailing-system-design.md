# Ride-Hailing — System Design (Uber / Ola / Lyft)

> **Core challenge:** match a **rider** to the best nearby **driver** in real time, track the trip live, price it (with **surge**), and settle payment — geospatially, at massive scale, with **seconds** of matching latency. Signature problems: **real-time geo-matching/dispatch**, a **location firehose**, **ETA/routing**, and **surge pricing**.

> **How to read this doc:** each section has the dense interview summary first, then a **deep dive** (annotated example code, and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand.

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

### What problem are we even solving?

Picture opening the Uber app. You tap "Where to?", see a few little car icons floating near you on the map, request a ride, and within seconds a driver's photo and car appear — "Ramesh is 3 minutes away." Then you watch his car crawl toward you on the map in real time. That entire experience is the system.

Break it into the jobs it has to do:

1. **Know where every driver is, right now.** Thousands of cars are moving around a city; the app must always have a fresh-ish map of them.
2. **Find nearby drivers instantly.** When you request, it must answer "which idle cars are within ~3 km of this rider?" in *milliseconds* — without checking all million drivers.
3. **Pick the best one and hand off the ride.** Offer it to a good driver, let them accept, and make sure two riders never get the same car.
4. **Track the trip live.** Stream the driver's GPS to your phone so the map animates.
5. **Price and charge.** Compute the fare (higher when demand is hot = **surge**) and settle payment at the end.

So it's really **"Google Maps + a real-time matching engine + a payment system,"** all running in real time. Everything else in this doc is a detail of one of those five jobs.

### Why is this genuinely hard?

The scary part isn't any single feature — it's the **combination of real-time + geographic + huge scale**:

- **Locations never stop changing.** A million drivers each sending their GPS every few seconds = a *firehose* of updates (§3). A normal database would melt.
- **"Nearby" is a geometry problem, not a `WHERE` clause.** `SELECT * FROM drivers WHERE distance < 3km` would have to compute distance to every driver on Earth per request. You need a **geospatial index** (§6) so "nearby" is a quick lookup.
- **Money and trips must be exactly right, but locations can be fuzzy.** If two riders grab one driver, that's a real-world disaster; if a car icon lags 2 seconds on the map, nobody cares. So the design uses **strong consistency for trips/payments** and **eventual consistency for location** — different tools for different jobs.

Keep this split in mind — it explains almost every technology choice below (Redis for location, a real SQL DB for trips).

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

### "Functional vs non-functional," and the one line that matters most

- **Functional** = *what buttons exist* — the features a rider/driver can actually do (request, accept, track, pay, rate).
- **Non-functional** = *how well it must behave* — fast, always-up, correct, huge scale. No button, but if it's missing the product fails.

The single most important line here is the last one:

> **Strong consistency for trips/payments; eventual for location/discovery.**

Plain version: **be paranoid about money and ride ownership; be relaxed about car dots.**

| Data | Consistency | Why | If it's slightly wrong |
| --- | --- | --- | --- |
| Trip state, driver assignment | **Strong** (always correct) | Two riders can't share one car; you can't charge twice | Real-world chaos, angry users, lawsuits |
| Payment / fare | **Strong** | It's money | Overcharge = refund + trust lost |
| Driver's dot on the map | **Eventual** (a bit stale is fine) | It's just a hint | Car icon lags 2s — nobody notices |
| "Nearby drivers" list | **Eventual** | An estimate anyway | You offer to a car that just moved — retry the next |

Q: **Why not just make everything strongly consistent — isn't "always correct" better?**
Because strong consistency is *expensive* (locks, coordination, slower). Applying it to 250,000 location updates/sec would be impossibly slow and pointless. You spend that budget only where being wrong actually hurts (money, ride ownership).

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

### Where do these numbers come from, and why do they matter?

Capacity estimation = **back-of-the-envelope math to find what will break first.** You're not being precise; you're finding the *monster*. Here the monster jumps out immediately.

Walk the key line slowly:

```
1,000,000 drivers online
each sends its GPS location every 4 seconds
→ 1,000,000 ÷ 4 = 250,000 location updates ARRIVING EVERY SECOND
```

Compare the traffic streams:

| Stream | Rate | Feels like |
| --- | --- | --- |
| Location pings | **~250,000 /sec** | A firehose — never stops, from every car |
| Ride requests | ~5,000 /sec | A steady stream — big but manageable |
| Trips completed | ~120 /sec (10M/day) | A trickle |

The location pings **dwarf** everything else by 50×. That one number decides the whole architecture:

> A regular SQL database (Postgres/MySQL) handles maybe a few **thousand** writes/sec. We need **250,000**. So driver locations can **never** live in the transactional DB — they go into an **in-memory store (Redis)** plus a **stream (Kafka)**. The SQL DB is reserved for the *trickle* of trips and payments, where being 100% correct matters.

```
tiny data (trips, payments)   → SQL database   (correct, slow-ish, that's fine)
firehose (driver locations)   → Redis + Kafka  (fast, in-memory, "good enough")
```

Q: **Why 4 seconds? Why not update location every second (smoother map)?**
Because rate = drivers ÷ interval. Halving the interval to 2s *doubles* the firehose to 500k/sec — more servers, more cost, more heat. 4s is a battery/bandwidth/cost compromise. (Uber actually uses an **adaptive rate**: faster pings near a pickup where precision matters, slower when idle far away — see §9.)

Q: **Why is a completed trip only ~2 KB but so important?**
It carries rider, driver, route, fare, timestamps — small. But it's *money and a legal record*, so it goes to the durable SQL DB and is kept forever, while raw location history is downsampled and dumped to a cheap data lake.

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

### Reading these endpoints as app taps

Every line above maps to something you literally do in the app:

```
POST /v1/rides/estimate   → you typed a destination; app shows "₹240, 4 min away, 1.2× surge"
POST /v1/rides            → you tapped "Confirm Uber"
GET/WS  /v1/rides/{id}/track → the live map screen watching the car approach
POST /v1/rides/{id}/cancel  → you tapped "Cancel"
POST /v1/rides/{id}/rate    → the 5-star screen after the trip
```

Two ideas in that summary trip people up. Here they are in plain terms.

#### Q: What is an "Idempotency-Key" and why does `POST /rides` need one?

You tap "Confirm." Your phone is on flaky 4G, the response is slow, so you tap again — or the app auto-retries. Without protection that's **two ride requests = two drivers dispatched = chaos.**

An **idempotency key** is a unique ID the app generates *once* for that tap and sends with every retry. The server remembers it: "seen this key already? return the *same* ride, don't make a new one."

```java
@PostMapping("/v1/rides")
public Ride requestRide(@RequestHeader("Idempotency-Key") String key,
                        @RequestBody RideRequest req) {
    // If we've already created a ride for this key, return that SAME ride.
    Ride existing = trips.findByIdempotencyKey(key);
    if (existing != null) return existing;      // retry → no new ride

    Ride ride = new Ride(req, status = SEARCHING);
    ride.setIdempotencyKey(key);                // remember the key
    trips.save(ride);                           // UNIQUE(idempotency_key) in DB is the safety net
    return ride;
}
```

Repeats carrying the same key resolve to the single original ride, never a new one.

#### Q: What's a `quoteId`, and why lock the price?

You see "₹240" on the estimate screen. You think for 20 seconds. In those 20 seconds surge might jump. If we recomputed at "Confirm," you'd be charged ₹300 — surprise, anger. So `estimate` returns a **`quoteId`** that *freezes* that fare+surge for a short time; `POST /rides` passes the `quoteId`, and we charge the frozen number.

```
estimate  → { fare: 240, surge: 1.2, quoteId: "q_abc", expiresIn: 120s }   # price frozen
...rider hesitates 20s...
POST /rides { quoteId: "q_abc" }  → charged 240, NOT whatever surge is now
```

The quote simply has a short expiry window; within it, the fare you saw is the fare you pay.

#### Q: Why WebSocket for offers and tracking instead of normal REST?

Normal REST is *pull*: the client asks, server answers, done. But a driver offer and the moving-car map are *push*: the **server** needs to reach the phone the instant something happens, without the phone constantly asking "anything yet? anything yet?". A **WebSocket** is a persistent open pipe both sides can send through — perfect for "here's a ride offer, now!" and "driver moved, redraw the dot." (Same connection-registry idea as a chat system.)

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

### Why chop it into all these services?

You *could* build one giant program that does everything. But its jobs have wildly different needs: matching must be fast and in-memory; trips must be rock-solid correct; the location firehose must absorb 250k writes/sec. Bundling them means one part's problem (a location spike) drowns the others (matching, payments). So we split into **specialists**, each scaled and tuned independently — this is **microservices**.

| Service | Its one job | Storage it likes |
| --- | --- | --- |
| **Trip Service** | Own the ride's status (SEARCHING → … → COMPLETED) | SQL (must be correct) |
| **Matching / Dispatch** | Find + assign the best driver | Redis GEO (in-memory, fast) |
| **Location Service** | Absorb the GPS firehose | Redis + Kafka |
| **Pricing Service** | Compute fare + surge | Cache of surge zones |
| **ETA / Routing** | "How long to drive there?" | Map graph + traffic |
| **Payment Service** | Charge rider, pay driver | SQL + ledger |
| **Notification / Tracking** | Push updates to phones | WebSocket connections |

#### Q: What is Kafka doing in the middle, and why?

Kafka is a **shared event log** — an append-only list of "things that happened" (`RIDE_REQUESTED`, `TRIP_ENDED`, …). When the Trip Service finishes a trip, it doesn't call the payment, analytics, and receipt services one by one (tight coupling, brittle). It just **publishes one event** onto Kafka; whoever cares subscribes.

```
Trip Service:  "TRIP_ENDED {trip 55, fare 240}"  ──►  Kafka
                                                        │
                        ┌───────────────────────────────┼───────────────────────┐
                        ▼                               ▼                         ▼
                  Payment Service               Analytics job            Notification Service
                  (charge rider)                (dashboards)             (send receipt)
```

Benefits, in plain terms: (1) **Decoupling** — the Trip Service doesn't need to know who reacts. (2) **Resilience** — if Analytics is down, the event waits in the log; nothing is lost. (3) **Fan-out** — add a new listener (say, fraud detection) later without touching the Trip Service. This is the **Pub-Sub / Observer** pattern.

#### Q: Why is Matching "the hard part" and not, say, Payments?

Payments is a well-understood, low-rate problem (a few hundred/sec) with proven tools. Matching must, *within a couple of seconds*, search a constantly-moving set of a million drivers, score them by real road-driving time, offer to the best, handle rejections, and guarantee no double-assignment — all under load. That real-time geo + optimization + concurrency mix is what makes ride-hailing distinctive (§6–§7).

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

### Why "find nearby drivers" is secretly hard

Naive idea: loop over every driver, compute distance to the rider, keep the close ones.

```java
// THE NAIVE (BROKEN-AT-SCALE) WAY — do NOT do this
List<Driver> findNearby(double lat, double lng) {
    List<Driver> near = new ArrayList<>();
    for (Driver d : ALL_ONE_MILLION_DRIVERS) {         // ← loops 1,000,000 times
        if (distance(lat, lng, d.lat, d.lng) <= 3.0) { // ← per request!
            near.add(d);
        }
    }
    return near;
}
```

At 5,000 requests/sec that's 5,000 × 1,000,000 = **5 billion distance calculations per second.** Impossible. The problem: we check *everybody* even though 99.99% are in another part of the city.

**The fix = a geospatial index.** Instead of one giant list, pre-sort drivers into **grid cells**. Then "nearby" becomes: figure out the rider's cell, look only in that cell + its neighbors. You touch a few hundred drivers, not a million — you never look at drivers in other parts of the city.

### Geohash — turn a map into a grid of labeled boxes

A **geohash** chops the world into a grid and gives every cell a short string label. The magic property: **cells that are near each other share the same prefix.**

```
Longer geohash string = smaller, more precise box:

  "tdr"      → a whole chunk of a city   (big box)
  "tdr1"     → a neighborhood            (smaller box)
  "tdr1y"    → a few streets             (smaller still)
  "tdr1y7"   → ~a block                  (tiny box)
```

```
Two drivers in the same block:   "tdr1y7"  and  "tdr1y8"   ← share "tdr1y" → they're close
A driver across the city:        "twh30k"                  ← different prefix → far away
```

So "nearby" ≈ "shares my prefix." We store each driver under their geohash cell; to search, we compute the rider's geohash and grab that bucket:

```java
// Each driver lives in a bucket named by their geohash cell
Map<String, Set<DriverId>> cellToDrivers;   // "tdr1y7" -> {driver 8, driver 12, ...}

void onDriverPing(DriverId id, double lat, double lng) {
    String cell = geohash(lat, lng, /*precision*/ 6);   // "tdr1y7"
    cellToDrivers.get(oldCellOf(id)).remove(id);        // left the old box
    cellToDrivers.get(cell).add(id);                    // entered the new box
}

List<DriverId> findNearby(double lat, double lng) {
    String riderCell = geohash(lat, lng, 6);
    List<DriverId> result = new ArrayList<>(cellToDrivers.get(riderCell));
    for (String n : neighbors8(riderCell)) {  // also the 8 surrounding boxes
        result.addAll(cellToDrivers.get(n));
    }
    return result;   // a few hundred drivers, not a million
}
```

#### Q: Why also search the 8 neighboring cells — isn't my own cell enough?

Because you might be standing at the **edge** of your box. The closest driver could be 50 m away but just over the line, in the next cell. If you only searched your own cell you'd miss them. So you always grab your cell **plus its 8 neighbors** (a 3×3 block) to cover the borders.

```
┌─────┬─────┬─────┐
│  NW │  N  │  NE │
├─────┼─────┼─────┤
│  W  │ YOU │  E  │   ← search all 9, not just "YOU"
├─────┼─────┼─────┤
│  SW │  S  │  SE │
└─────┴─────┴─────┘
```

### Quadtree — a grid that adapts to crowds

Geohash cells are all the *same size*. Problem: downtown at rush hour has 5,000 drivers in one cell (too many to scan), while a rural cell has 2 (wastefully sparse). A **quadtree** fixes this by **splitting a cell into 4 only when it gets too crowded**:

```
Start: one big square for the city.
If a square holds more than K drivers (say 100), split it into 4 smaller squares.
Keep splitting the crowded ones. Leave empty countryside as big squares.

┌───────────────┐        ┌───────┬───────┐        ┌───┬───┬───────┐
│               │  too   │       │       │  still  │ █ │ █ │       │
│   200 cars    │ ─many─►│  50   │  50   │ ─busy─► ├───┼───┤  30   │
│               │        │       │       │         │ █ │ █ │       │
└───────────────┘        ├───────┼───────┤         ├───┴───┼───────┤
                         │  40   │  60   │         │  40   │  60   │
                         └───────┴───────┘         └───────┴───────┘
   dense downtown = many tiny cells;  quiet outskirts = few big cells
```

Result: **dense areas → deep, tiny cells; sparse areas → shallow, big cells.** Every cell holds roughly the same manageable number of drivers. Trade-off: it's a tree you must rebalance as drivers move, more complex than a flat geohash grid.

### S2 (Google) and H3 (Uber) — the advanced versions

- **S2** wraps the grid around the **globe as a sphere** (geohash's flat grid distorts near the poles and at the ±180° date line) and numbers cells along a space-filling **Hilbert curve** so nearby cells get nearby numbers — great for range queries.
- **H3 (Uber's own)** uses **hexagons** instead of squares. Why hexagons? Every neighbor of a hexagon is the **same distance** away (a square has close edge-neighbors but farther corner-neighbors). That uniformity makes "supply vs demand *per cell*" — which drives **surge pricing** (§11) — clean and fair, and makes radius search tidy.

```
Square grid: neighbors are unequal distances
  ┌───┬───┬───┐     edge-neighbor: 1 unit away
  │ ↖ │ ↑ │ ↗ │     corner-neighbor: 1.41 units away  ← inconsistent
  ├───┼───┼───┤
  │ ← │ ⬛ │ → │
  └───┴───┴───┘

Hexagon grid: all 6 neighbors are equidistant  ⬡  ← why Uber picked it
```

| Technique | One-liner | Cell shape | Adapts to density? |
| --- | --- | --- | --- |
| Geohash | Prefix = proximity | Square | No (fixed size) |
| QuadTree | Split when crowded | Square | **Yes** |
| S2 | Sphere + Hilbert curve | Square-ish (on sphere) | Via cell level |
| H3 | Equal-distance neighbors | **Hexagon** | Via resolution |
| Redis GEO | Ready-made `GEOSEARCH` | (geohash under the hood) | No, but *practical* |

### Redis GEO — what you actually use in production

You rarely hand-code the above. **Redis GEO** gives you geospatial search out of the box (it uses geohashes internally). Two commands do almost everything:

```
# A driver pings their location → add/overwrite their point in the "drivers:online" set.
# GEOADD OVERWRITES the same driver id, so this is also how a moving car "updates" — no duplicates.
GEOADD drivers:online  77.5946 12.9716  driver:42        # lng, lat, member

# A rider requests → find online drivers within 3 km, nearest first.
GEOSEARCH drivers:online FROMLONLAT 77.5900 12.9700 BYRADIUS 3 km ASC
```

#### Q: The index changes on *every* ping — 250k times a second. Why is that OK?

Because it lives **in memory (RAM)**, not on disk. Redis just moves a point in an in-memory structure — microseconds. This is exactly why the geo-index is Redis and **never** the SQL trip DB: a disk-based DB doing 250k location rewrites/sec would collapse. Locations are also "eventual" data (§2) — a dot being 2 seconds stale is fine — so RAM's "fast but volatile" trade-off is perfect.

#### Q: Why "shard the geo-index by city/region"?

A rider in Bangalore never needs a driver in Delhi. So keep a **separate index per city**. Benefits: each index is smaller (faster search), and a problem in one city's index doesn't touch another's (**isolation**). It's the same locality principle one level up: don't search the whole country, only the relevant city.

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

### Matching is a 3-step funnel

Once §6 gives us "cars roughly nearby," matching narrows that to *one* driver. It's a funnel:

```
1. GENERATE candidates  →  geo-index gives ~20 nearby idle drivers   (the shortlist)
2. SCORE + rank         →  who's actually BEST? sort them             (the ranking)
3. OFFER loop + CLAIM   →  ask #1; no? ask #2; on yes, LOCK the car   (the handoff)
```

Generate the shortlist from the geo-index, rank it by score, then offer to the best candidate; if they decline or time out, offer the next; once one accepts, lock them so no other ride can grab them.

#### Step 2 in code — "best" is NOT "closest in a straight line"

The nearest car *as the crow flies* might be across a river with a 20-minute detour. So we score by **real driving time (ETA via routing)**, plus a few other factors:

```java
double score(Driver d, Location pickup) {
    // etaViaRoads = actual drive time on the road network + live traffic (§10),
    // NOT haversine straight-line distance. THIS is the dominant factor.
    double eta       = routing.etaToPickup(d.location(), pickup);  // seconds — lower is better
    double rating    = d.rating();                                 // 1..5   — higher is better
    double accept    = d.acceptanceRate();                         // 0..1
    double idleBonus = d.idleSeconds();                            // fairness: waited longest gets a nudge

    return  (eta * 1.0)          // want low ETA
          - (rating * 30)        // reward good drivers
          - (accept * 20)        // reward reliable acceptors
          - (idleBonus * 0.1);   // spread work fairly
    // lower total score = better driver → we offer to the minimum
}
```

#### Step 3 in plain code — the offer loop + the all-important atomic claim

```java
void dispatch(Ride ride) {
    List<Driver> ranked = candidates(ride.pickup()).stream()
        .sorted(Comparator.comparingDouble(d -> score(d, ride.pickup())))
        .toList();

    for (Driver d : ranked) {
        boolean accepted = offerAndWait(d, ride, /*timeout*/ Duration.ofSeconds(12));
        if (accepted && claim(d, ride)) {   // claim = the atomic lock, see below
            ride.assignTo(d);               // trip → DRIVER_ASSIGNED
            return;
        }
        // rejected / timed out / someone else grabbed them → try the next candidate
    }
    ride.markNoDriversFound();              // widen radius / raise surge / apologize
}
```

#### Q: Why must the "claim" be *atomic*? What breaks without it?

Two riders A and B both see the same idle driver as their best option and offer at nearly the same instant. Both drivers-apps... no — the *driver* taps accept for A, but B's system also thinks it got him. Without protection, **one car gets assigned to two trips.** Real-world disaster.

The fix: an **atomic lock** in Redis. `SET ... NX` means "set this key **only if it doesn't already exist**" — and Redis guarantees only *one* request can win that race:

```java
boolean claim(Driver d, Ride ride) {
    // NX = set only if absent; EX 30 = auto-expire in 30s (so a crash can't lock him forever)
    // Returns true for the FIRST caller, false for everyone after → exactly one winner.
    return redis.set("driver:" + d.id() + ":lock", ride.id(), "NX", "EX", 30);
}
```

```
Rider A: SET driver:42:lock A NX  → OK true   ✅ A gets the driver
Rider B: SET driver:42:lock B NX  → nil false ❌ B is told "taken", offers its next candidate
```

Redis guarantees only the first `SET NX` succeeds; the second is rejected, so exactly one rider wins the driver and the other moves on.

#### Q: Greedy vs batch matching — when and why?

- **Greedy (nearest-first):** handle each request the instant it arrives, give it the best driver *available right now*. Simple, low latency. But **locally** optimal — it can make a globally worse set of assignments.
- **Batch:** wait ~1–2 seconds, collect a *pool* of requests and drivers, and solve them **together** to minimize *total* wait across everyone (a min-cost assignment / Hungarian algorithm).

Why batch can win — a classic case:

```
Two riders, two drivers (greedy handles R1 first):
  Greedy:  R1 grabs D1 (its nearest)  → R2 forced onto far D2  → total wait = 2 + 9 = 11 min
  Batch:   see both at once → R1↔D2 (3), R2↔D1 (2)             → total wait = 3 + 2 = 5 min ✅
```

Trade-off: batching adds a 1–2s delay and more compute, so it's used in **dense markets** where the global gain is big; sparse areas just use greedy.

#### Q: What is UberPool doing differently?

Pooling matches **multiple riders whose routes overlap** to one car (shared fare, some detour). Now matching isn't "pick a driver" but "which riders should share, and in what pickup/dropoff order?" — a routing/optimization problem on top of matching. Riders trade a little detour time for a cheaper fare.

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

### The trip state machine

A **state machine** just means: a trip is always in exactly **one** named state, and it can only move along **allowed transitions** to the next state. A trip can't jump to "COMPLETED" without passing through the states between.

The states map to what you *see* in the app:

```
REQUESTED        → "Finding you a ride…" (spinner)
DRIVER_ASSIGNED  → "Ramesh is on the way" (car appears)
DRIVER_ARRIVED   → "Your driver is here"
IN_PROGRESS      → the trip; live map
COMPLETED        → "You've arrived" → fare + rating screen
CANCELLED / NO_DRIVERS → the ride ended early
```

#### Q: Why bother with a formal state machine — why not just a `status` column we set freely?

Because it **blocks illegal jumps** that would corrupt reality or money. Without guards you could get: charge a trip that never started, start a trip nobody accepted, or complete a cancelled ride. The state machine defines *which* transitions are legal and rejects the rest.

```java
// Allowed moves only. Anything not listed is rejected.
static final Map<State, Set<State>> ALLOWED = Map.of(
    REQUESTED,       Set.of(DRIVER_ASSIGNED, NO_DRIVERS, CANCELLED),
    DRIVER_ASSIGNED, Set.of(DRIVER_ARRIVED, CANCELLED),
    DRIVER_ARRIVED,  Set.of(IN_PROGRESS, CANCELLED),
    IN_PROGRESS,     Set.of(COMPLETED),          // once rolling, you can only finish
    COMPLETED,       Set.of()                    // terminal — no moves out
);

void transition(Trip trip, State next, String actor) {
    if (!ALLOWED.get(trip.state()).contains(next)) {
        throw new IllegalStateException(trip.state() + " ✗→ " + next);  // e.g. REQUESTED→COMPLETED blocked
    }
    trip.setState(next);
    historyRepo.append(trip.id(), next, actor, now());  // audit trail: who moved it, when
    kafka.publish(next.eventName(), trip);              // tell pricing/notify/analytics
}
```

#### Q: What stops two actors (rider taps cancel, driver taps start) racing at the same instant?

A **conditional update** in the DB — only change the row *if it's still in the state you expected*:

```sql
UPDATE trips SET status = 'IN_PROGRESS'
WHERE trip_id = 55 AND status = 'DRIVER_ARRIVED';   -- only if still ARRIVED
-- rows affected = 1 → you won;  0 → someone already moved it (e.g. rider cancelled) → you lose gracefully
```

This is **optimistic concurrency**: don't lock up front; instead let the `WHERE status = <expected>` clause decide the winner. Exactly one of the racing updates changes a row; the other sees "0 rows" and backs off.

#### Q: Why write `trip_status_history` and fire a Kafka event on *every* transition?

- **History** = a full timestamped record of the ride (`REQUESTED at 8:00, ASSIGNED at 8:01…`). Priceless for disputes ("driver started the meter before I got in"), support, and analytics.
- **Kafka event** = the announcement so *other* services react without the Trip Service calling each one: `TRIP_ENDED` → Payment charges, Notification sends a receipt, Analytics updates dashboards (the Pub-Sub fan-out from §5).

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

### What happens to one GPS ping?

Every ~4 seconds each driver's phone sends "I'm at (lat, lng)." Follow **one** ping through the system and notice it does **three cheap things and never touches the SQL DB**:

```
Driver phone: "driver 42 is at (12.9716, 77.5946)"  ──►  Location Service
      │
      ├─(1)─►  GEOADD drivers:online 77.5946 12.9716 driver:42   → refresh the MATCH index (§6)
      │
      ├─(2)─►  SET loc:driver:42 {lat,lng,heading} EX 30         → the "latest known position" key
      │
      ├─(3)─►  publish to Kafka (sampled)                        → analytics, ETA models, history
      │
      └─(4, only if driver 42 is ON a trip)─►  push over WebSocket to that rider's phone → map animates
```

Each ping just **overwrites** the driver's latest position (`GEOADD` and `SET` both replace the existing value). We don't `INSERT` a database row per ping — that would accumulate 250k throwaway rows per second for data we only need the newest value of.

#### Q: Why can these 250k pings/sec NOT go into the trips database?

We did this math in §3: a SQL DB handles a few *thousand* writes/sec; we have **250,000**. It would instantly fall over. And it'd be pointless — a location is "eventual" data (§2), disposable in seconds, so we keep only the **latest** value in fast in-memory Redis (`GEOADD` and `SET` both *overwrite*, they don't accumulate). The durable DB is saved for the trickle of trips/payments.

```java
// The whole point in one method: fast overwrites, no DB row per ping.
void onPing(long driverId, double lat, double lng, boolean onTrip, Long riderId) {
    redis.geoAdd("drivers:online", lng, lat, "driver:" + driverId);      // match index
    redis.set("loc:driver:" + driverId, pos(lat, lng), "EX", 30);        // latest position, self-expiring
    kafka.publish("driver-locations", sampled(driverId, lat, lng));      // stream (sampled, for history/ETA)

    if (onTrip) {                                                        // ONLY active trips push to a rider
        websocket.pushTo(riderId, "driver_moved", pos(lat, lng));
    }
}
```

#### Q: Why does only an *active trip* push GPS to a rider? What about the idle cars I see on the map?

Two different jobs:
- **Idle drivers** just refresh the **match index** (so they can be found). Their pings do *not* stream to anyone.
- **A driver on your trip** additionally pushes each new position to *your* WebSocket so your live map animates.

The floating cars you see before booking are usually a cheap, throttled snapshot near you — not a live per-car stream to your phone. Streaming every idle car to every nearby rider would be a needless firehose.

#### Q: What is "adaptive ping rate," and why?

Ping frequency is a battery/bandwidth vs precision trade-off. So the app **dials it up when precision matters and down when it doesn't**:

```
Driver idle, far from any pickup   → ping every ~10s   (save battery; rough position is fine)
Driver assigned, approaching you   → ping every ~1–2s  (you're staring at the map; needs to be smooth)
```

#### Q: What's the WebSocket "gateway" doing here?

Millions of phones hold an **open connection** (so the server can push instantly). A **gateway** is the fleet of servers that hold those live connections and know "rider 99's phone is on gateway box #7." When driver 42's ping arrives for rider 99's trip, the system routes the update to the right gateway, which pushes it down the open pipe. It's a stateful *connection registry* — the same idea a chat app uses to deliver messages to online users.

---

## 10. ETA & Routing

- **Routing engine** (OSRM / Google Maps / Valhalla) over the road graph with **live traffic** for ETA-to-pickup and trip duration/distance.
- **Precompute** cell-to-cell travel times for fast estimates; refine with live data.
- Real maps use **contraction hierarchies** (precomputed shortcuts) — not live Dijkstra continent-wide (see the Maps note).
- ETA is often **ML-adjusted** from historical trips (time of day, weather); treat as a black box, emphasize inputs + continuous refinement + push updates.

### "How many minutes away?" is a road problem, not a ruler problem

ETA = estimated time of arrival. The naive version measures the **straight-line** distance between two points and divides by an average speed. That's wrong for cities:

```
Straight line says driver is 500 m away → "1 min".
Reality: a river is between you; the only bridge is 3 km away → actually 12 min.
```

So we compute ETA over the **road network** — a graph where intersections are nodes and roads are edges weighted by how long they take to drive (with **live traffic** making some edges "heavier"). Finding the fastest path is a shortest-path search.

This is what Google Maps does when it says "13 min, fastest route." Ride-hailing calls the same kind of engine (OSRM / Google Maps / Valhalla) for two things: **ETA-to-pickup** (used to *score* drivers in §7) and **trip duration/distance** (used to *price* the fare in §11).

```java
// Conceptual: shortest path over the road graph, edges weighted by live drive-time.
int etaSeconds(Location from, Location to) {
    Graph roads = roadNetwork.forRegion(from);   // intersections + roads
    roads.applyLiveTraffic();                     // slow edges get heavier right now
    Path best = roads.shortestPath(from, to);     // Dijkstra/A* conceptually
    return best.travelTimeSeconds();              // NOT straight-line ÷ speed
}
```

#### Q: A million ETA queries — do we run Dijkstra across the whole country each time?

No — that would be far too slow. Real map engines **precompute shortcuts** so live queries are fast:

- **Contraction hierarchies:** precompute fast "highway-like" shortcut edges between important nodes, so a live query hops via shortcuts instead of crawling every small street. (Details in the Maps note.)
- **Cell-to-cell precomputation:** precompute typical travel times between geo-cells; use those for instant rough estimates, then refine the last leg with live data.

The precomputed highway-like shortcuts handle the long haul ("take NH-44, ~6 hours"), so a live query only does detailed search over the local streets at each end instead of every small street along the way.

#### Q: Why is ETA "ML-adjusted," and how should I talk about it in an interview?

The raw graph time is a good base, but reality has patterns a static graph misses: 6 PM Fridays are slower, rain slows everything, this particular left turn always backs up. So a **machine-learning model** nudges the estimate using history (time of day, day of week, weather, this segment's past behavior). In an interview, **don't** try to design the model — treat it as a black box and emphasize: its **inputs** (live traffic, historical trips, weather), that it **continuously refines** as the trip progresses, and that updated ETAs are **pushed** to the rider's app.

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

### Surge is just "demand ÷ supply" per neighborhood

When it's raining and everyone wants a ride but few drivers are around, prices go up. That's **surge**, and the formula is intuitive: **more people wanting rides than cars available → multiply the fare.**

We compute it **per geo-cell** (per H3 hexagon from §6), because demand is *local* — it can be surging outside a concert venue while the next neighborhood is normal.

```java
// A stream job recomputes this every few seconds, per cell.
double surgeFor(String cellId) {
    int openRequests    = demand.ridersWaitingIn(cellId);   // people wanting a ride
    int availableDrivers = supply.idleDriversIn(cellId);    // cars free to take one

    if (availableDrivers == 0) return MAX_SURGE;            // lots of demand, no cars
    double ratio = (double) openRequests / availableDrivers;

    // ratio 1.0 → normal (1×); higher ratio → higher multiplier, capped.
    return clamp(ratio, /*min*/ 1.0, /*max*/ 3.0);
}

int fare(Trip t, double surge) {
    int base = BASE_FARE
             + PER_KM  * t.distanceKm()
             + PER_MIN * t.durationMin();
    return (int) (base * surge) + tolls(t) + taxes(base) + BOOKING_FEE;
}
```

```
Concert lets out:  200 requests, 20 idle drivers  → ratio 10 → capped at 3.0× surge
Quiet suburb:      5 requests, 40 idle drivers     → ratio 0.1 → 1.0× (no surge)
```

#### Q: Why does surge exist — is it just gouging riders?

It's a **self-balancing lever** pulling both sides toward equilibrium:
- **Sheds demand:** at 2.5× some riders decide to wait or take the bus → fewer requests → the ones who really need a ride get a car.
- **Pulls supply:** drivers see a **heatmap** of surging cells and *drive toward* the money → more cars show up where they're needed → surge naturally falls back to 1×.

It's the same dynamic as airline or hotel prices rising on peak dates: a higher price nudges some to shift plans and draws more supply, smoothing the crunch.

---

## 12. Payments

- **Idempotent charge** at trip end; wallet/card/UPI/cash; auto-receipt (reuse the Payment System design).
- **Fare split:** driver payout vs platform commission → a **double-entry ledger**; payout batched.
- **Refunds/adjustments** for disputes; **outbox** so a completed trip reliably triggers charge + payout.
- **Upfront pricing** (charge the quoted fare) vs metered (compute from actual distance/time).

### One fare splits two ways

When your trip ends and you pay ₹240, that money doesn't all go to the driver. It **splits**: the driver gets a payout, the platform keeps a commission.

```
Rider pays ₹240
   ├── Driver payout   ₹192   (80%)
   └── Platform fee    ₹48    (20% commission)
```

To track this without ever "losing" or "inventing" money, payments use a **double-entry ledger** — the accountant's rule that **every rupee that leaves one account must arrive in another**; the books must always balance.

```
TRIP 55 ledger entries (each transfer is two matching lines):
  rider_wallet   -240      (money out of rider)
  platform_clearing +240   (money into platform, temporarily)

  platform_clearing -240
  driver_payable    +192   (driver's share)
  platform_revenue  +48    (commission)
                    ─────
  every column sums to 0   → the books balance, always
```

#### Q: What is the "outbox," and what disaster does it prevent?

The Trip Service must do **two** things when a trip completes: (1) save `status = COMPLETED` in its DB, and (2) tell the Payment Service to charge. If it saves the trip and then crashes before sending the charge message — **trip done, but nobody ever gets charged.** Money lost. (This is the "dual-write problem.")

The **outbox pattern** fixes it: in the *same DB transaction* that marks the trip complete, also write the "please charge" message into an `outbox` table. Since they're one transaction, either both save or neither does. A separate relay then reliably ships outbox rows to Kafka.

```java
@Transactional   // both writes commit together, or neither does
void completeTrip(Trip trip) {
    trip.setStatus(COMPLETED);
    trips.save(trip);                                  // (1) the trip

    outbox.save(new OutboxEvent("TRIP_ENDED", trip));  // (2) the "charge me" intent — SAME transaction
}                                                      // crash after this? the event is safely stored → relay sends it later
```

Because the trip write and the outbox write share one transaction, the event can't be lost even if the service crashes right after committing — a separate relay ships the stored outbox rows later.

#### Q: Why an "idempotent charge"? And upfront vs metered?

- **Idempotent charge:** same retry-safety idea as §4 — a network retry must not charge the rider twice. The charge carries a unique key; the payment provider ignores a repeat of the same key. "Charge ₹240 for trip 55, key X" run twice = charged once.
- **Upfront vs metered:** *Upfront* = you pay the quoted fare no matter the exact route (predictable; what Uber mostly shows now). *Metered* = the final fare is computed from the *actual* distance/time driven (the old taxi meter). Upfront needs the locked `quoteId` from §11; metered computes at trip end from real GPS distance.

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

### Reading the schema

Each table maps to one part of a ride. Group them by job:

| Table(s) | What it holds | Role |
| --- | --- | --- |
| `riders`, `drivers`, `vehicles` | The people and cars | Core entities |
| `trips` | The ride itself + its current `status` | The main record (state machine, §8) |
| `trip_status_history` | Every state change, timestamped | Audit trail of the ride |
| `ride_offers` | Who was offered, did they accept? | Matchmaking attempts (§7) |
| `payments`, `driver_earnings` | Money in, money out | Payment records (§12) |
| `surge_zones` | Multiplier per cell right now | Live pricing state (§11) |
| `reviews` | Ratings both ways | After-trip feedback |
| `outbox` | Pending events to publish | Reliable event delivery (§12) |

#### Q: The `trips` table has an `idempotency_key UNIQUE` — what's that doing?

It's the *database-level* safety net for the double-tap problem from §4. Even if two identical requests slip past the app check, the DB's `UNIQUE` constraint physically **refuses** to store two rows with the same key — the second insert errors out, guaranteeing one ride per tap.

```sql
idempotency_key VARCHAR(255) UNIQUE   -- DB rejects a duplicate → at most one trip per tap
```

#### Q: Why is `trip_id` a "Snowflake" ID and not just `AUTO_INCREMENT 1,2,3…`?

At Uber scale, trips are created on **many servers at once**, so a single auto-increment counter would be a bottleneck (everyone waits for the one counter) and a single point of failure. A **Snowflake ID** lets each server mint unique, time-sortable 64-bit IDs *independently* (built from timestamp + machine id + sequence) — no central coordination. (See the distributed-ID-generator note.)

#### Q: Why is there an index `idx_trips_rider ON trips(rider_id, requested_at DESC)`?

Because the app constantly asks "show me *my* recent trips, newest first" (your ride history screen). Without an index the DB scans every trip ever; with it, the DB jumps straight to that rider's rows already in newest-first order, instead of scanning the whole table.

#### Q: Why are driver locations deliberately **not** a table here?

This is the §3/§9 lesson made concrete: 250k location writes/sec would destroy a SQL table, and locations are disposable "eventual" data. So live positions live in **Redis GEO** (`drivers:online` for the match index + `loc:driver:{id}` for the latest point), and only durable, must-be-correct facts (trips, payments) get real tables.

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

### The whole ride as one connected flow

The sequence diagram looks busy, but it's just the earlier sections happening **in order**. Read it top to bottom, tapping through the app:

```
1. You tap Confirm            → Trip Service creates trip = REQUESTED, shouts RIDE_REQUESTED on Kafka   (§4, §8)
2. Matching wakes up          → GEOSEARCH nearby idle drivers (§6) → scores them (§7)
3. Offers the best driver     → over WebSocket; driver taps Accept                                       (§7)
4. Atomic claim               → SET NX locks that driver so no one else can grab him                     (§7)
5. Trip = DRIVER_ASSIGNED     → you get "Ramesh is on the way" + his details                             (§8)
6. Driver drives to you       → his GPS streams to your phone; live map animates                         (§9)
7. arrive → start → end       → each is a guarded state transition                                       (§8)
8. Trip end → fare → charge   → idempotent payment; outbox reliably fires payout + receipt               (§11, §12)
```

Notice the two rhythms interleaving: **request/response** for the discrete steps (create trip, claim, charge) and **continuous streaming** for the live GPS. And every important step drops a **Kafka event** so pricing, notifications, and analytics react without the Trip Service phoning each one.

#### Q: What happens on cancellation — why "release the driver lock"?

If you cancel after a driver was assigned, that driver is still holding your atomic claim (§7). We must **release the lock** so he's free again and can be re-offered to other waiting riders — otherwise he'd sit "locked to a cancelled trip" and effectively vanish from the pool. Depending on *when* you cancel (after assign/arrival), a **cancellation fee** may apply, since the driver already spent time/fuel coming to you.

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

### A "what could go wrong?" checklist

Every row is a **real-world thing that would break** and the guard that stops it. They're not new ideas — they're the safety mechanisms from earlier sections, gathered in one place. The mental pattern: *for each place two things happen at once or a message could be lost, name the guard.*

| Bad thing that could happen | Plain scenario | The guard (and where it came from) |
| --- | --- | --- |
| Duplicate ride request | You double-tap Confirm on bad signal | `UNIQUE(idempotency_key)` — one ride per tap (§4) |
| Two rides grab one driver | Two riders' best match is the same car | **Atomic Redis claim** `SET NX` — one winner (§7) |
| Double charge | Payment retry after a timeout | Idempotent payment key — charge once (§12) |
| Lost event after trip end | Crash between "trip done" and "charge" | **Outbox** + relay — nothing lost (§12) |
| Location firehose | 250k pings/sec | Redis + Kafka, never the SQL DB (§3, §9) |
| Surge quote drift | Price jumps while you hesitate | Lock the fare with a `quoteId` (§11) |
| Trip state races | Rider cancels while driver starts | State machine + `UPDATE … WHERE status = expected` (§8) |
| Driver on 2 trips | Assignment slips through | Claim lock + `status=ON_TRIP` guard (§7) |

#### Q: The big picture — when do we care about consistency and when don't we?

Come back to the §2 rule: **be strict about money and ride ownership, relaxed about car dots.** Trips and payments use strong guarantees (unique keys, atomic locks, conditional updates, outbox) because being wrong means real-world chaos or lost money. Driver location and the nearby-driver list are *eventual* — a little staleness is invisible to users and buys the enormous throughput the firehose needs. Matching the *strength of the guard* to the *cost of being wrong* is the core judgment this whole design is built on.

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
