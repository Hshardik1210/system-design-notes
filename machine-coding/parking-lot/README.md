# 🅿️ Parking Lot

Design a parking lot that supports multiple floors, different vehicle/spot sizes, ticketing on entry, and fee calculation on exit.

## Requirements
- Floors, each with spots of size `SMALL / MEDIUM / LARGE`.
- Vehicles: `MOTORCYCLE (small) / CAR (medium) / TRUCK (large)`. A vehicle fits any spot **≥** its required size.
- Issue a **ticket** on entry; compute **fee** on exit (min 1 hour billed).
- Thread-safe allocation — two vehicles never get the same spot.

## Design
- **Vehicle** hierarchy with `requiredSize()` (polymorphism) + **`VehicleFactory`**.
- **`ParkingSpot`** knows its size and whether a vehicle `canFit`.
- **`ParkingFloor.findSpot`** does **best-fit** (smallest free spot that fits) to avoid wasting large spots.
- **`ParkingLot`** is a **Singleton**; allocation is guarded by a lock.
- **Pricing** is a **Strategy** (`PricingStrategy` → `HourlyPricing`) so you can swap flat/hourly/dynamic pricing without touching the lot.

## Design patterns
| Pattern | Where | Why |
| --- | --- | --- |
| Strategy | `PricingStrategy` | Swap pricing schemes at runtime |
| Factory | `VehicleFactory` | Centralised vehicle creation |
| Singleton | `ParkingLot` | One lot per app; single source of truth |

## Run
```bash
# Java
cd java && javac Main.java && java Main

# C++
cd cpp && g++ -std=c++17 main.cpp -o main && ./main
```
