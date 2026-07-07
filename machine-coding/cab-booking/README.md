# 🚕 Cab Booking (Uber/Ola core)

Match a rider to a nearby driver, price the trip (with surge), and run the trip state machine.

## Design
- **Matching** — pick the **nearest AVAILABLE** driver (Euclidean distance here; swap for road-distance/ETA/rating).
- **Pricing** — `PricingStrategy` = `(base + perKm·distance) × surge`. **Surge** rises when demand > supply.
- **Trip state machine:**
```
REQUESTED ─▶ ASSIGNED ─▶ IN_PROGRESS ─▶ COMPLETED
      └────────────▶ CANCELLED (no driver)
```
- **Driver status:** `AVAILABLE → ON_TRIP → AVAILABLE` (frees up on trip end, relocated to drop point).

## Design patterns
| Pattern | Where | Why |
| --- | --- | --- |
| Strategy | `PricingStrategy` | Swap pricing/surge models |
| State | `TripState`, `DriverStatus` | Lifecycle transitions |

> Real systems use a **geospatial index** (geohash/S2/H3) for nearest-driver at scale — see `system-design/ride-hailing-system-design.md`.

## Run
```bash
cd java && javac Main.java && java Main
cd cpp && g++ -std=c++17 main.cpp -o main && ./main
```
