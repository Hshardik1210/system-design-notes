# Parking Lot — System Design (OOD / LLD)

> A classic **object-oriented design (LLD)** problem. Core challenge: model a multi-floor parking lot that handles **different vehicle/spot types**, **assigns spots**, **issues tickets**, and **charges on exit** — with clean, extensible class design. Interviewers grade **your class model and the design patterns you apply**, not scale.

---

## Contents

- [1. Requirements](#1-requirements)
- [2. Core Entities](#2-core-entities)
- [3. Class Design](#3-class-design)
- [4. Design Patterns (that can be used)](#4-design-patterns-that-can-be-used)
- [5. Spot Assignment Strategy](#5-spot-assignment-strategy)
- [6. Pricing / Fee Calculation](#6-pricing--fee-calculation)
- [7. Data Model (if persisted)](#7-data-model-if-persisted)
- [8. APIs (if a service)](#8-apis-if-a-service)
- [9. Concurrency](#9-concurrency)
- [10. Interview Cheat Sheet](#10-interview-cheat-sheet)
- [11. Final Takeaways](#11-final-takeaways)

---

## 1. Requirements

### Functional
- Park and unpark vehicles of different types: **motorcycle, car, truck/bus**.
- Multiple **floors**, each with spots of different **sizes** (compact, large, handicapped, EV).
- Assign a suitable free spot on entry; **issue a ticket**.
- On exit, **compute fee** (by duration/type) and process **payment**.
- Show **real-time availability** (free spots per type/floor); full-lot handling.
- Multiple **entry/exit gates**; display boards.

### Non-functional
- Extensible: add new vehicle types, spot types, pricing rules without rewrites.
- Thread-safe spot assignment (no two cars → one spot).

> **Clarify:** flat rate vs hourly? EV charging spots? Reservations? Single vs multi-lot? State assumptions.

---

## 2. Core Entities

```
ParkingLot ──has many──► ParkingFloor ──has many──► ParkingSpot
Vehicle (Motorcycle/Car/Truck) parks in a compatible ParkingSpot
Ticket links Vehicle ↔ Spot ↔ entry time
EntryGate issues Ticket; ExitGate processes Payment
```

| Entity | Role |
| --- | --- |
| `ParkingLot` | Root; aggregates floors; global availability |
| `ParkingFloor` | Group of spots; per-floor display board |
| `ParkingSpot` | A single spot with a type/size and occupancy state |
| `Vehicle` | Abstract; subtypes carry a size requirement |
| `Ticket` | Issued on entry (vehicle, spot, entryTime) |
| `EntryGate` / `ExitGate` | Entry issues ticket; exit computes fee + payment |
| `ParkingRate` / `FeeCalculator` | Pricing rules |
| `PaymentProcessor` | Cash/card/UPI |

---

## 3. Class Design

```java
enum VehicleType { MOTORCYCLE, CAR, TRUCK }
enum SpotType    { COMPACT, LARGE, MOTORCYCLE, HANDICAPPED, EV }
enum SpotStatus  { FREE, OCCUPIED, OUT_OF_SERVICE }
enum TicketStatus{ ACTIVE, PAID, LOST }

abstract class Vehicle {
    String plate; VehicleType type;
    abstract boolean canFitIn(SpotType spot);
}
class Car extends Vehicle { boolean canFitIn(SpotType s){ return s==COMPACT||s==LARGE||s==EV; } }
class Motorcycle extends Vehicle { boolean canFitIn(SpotType s){ return s==MOTORCYCLE||s==COMPACT; } }
class Truck extends Vehicle { boolean canFitIn(SpotType s){ return s==LARGE; } }

class ParkingSpot {
    String id; SpotType type; SpotStatus status; Vehicle current;
    boolean assign(Vehicle v){ /* CAS FREE→OCCUPIED */ }
    void free(){ status = FREE; current = null; }
}

class ParkingFloor {
    int level; Map<SpotType, List<ParkingSpot>> spots; DisplayBoard board;
    Optional<ParkingSpot> findFreeSpot(Vehicle v, SpotAssignmentStrategy s);
}

class ParkingLot {                     // Singleton
    List<ParkingFloor> floors;
    SpotAssignmentStrategy assignment;
    FeeStrategy feeStrategy;
    Ticket parkVehicle(Vehicle v);      // finds spot, issues ticket
    Receipt unpark(Ticket t, PaymentMethod m);
    int availability(SpotType type);
}

class Ticket {
    String ticketId; Vehicle vehicle; ParkingSpot spot;
    Instant entryTime; TicketStatus status;
}

interface SpotAssignmentStrategy { Optional<ParkingSpot> find(ParkingFloor f, Vehicle v); }
interface FeeStrategy { Money calculate(Ticket t, Instant exit); }
interface PaymentProcessor { boolean pay(Money amount, PaymentMethod m); }
```

---

## 4. Design Patterns (that can be used)

> The heart of this problem — know these cold.

| Pattern | Where | Why |
| --- | --- | --- |
| **Strategy** | `SpotAssignmentStrategy` (nearest/random/by-floor), `FeeStrategy` (hourly/flat/day) | Swap algorithms without changing `ParkingLot` |
| **Factory / Factory Method** | `VehicleFactory` (type→Vehicle), `SpotFactory` | Centralize creation, add types easily |
| **Singleton** | `ParkingLot` (one instance manages the lot) | Single source of truth for availability |
| **State** | `ParkingSpot` status (FREE↔OCCUPIED↔OUT_OF_SERVICE), `Ticket` state | Encapsulate legal transitions |
| **Observer** | Display boards subscribe to availability changes | Auto-refresh boards/gates when a spot frees/fills |
| **Decorator / Chain** | Fee composition (base + EV charging + tax + weekend surcharge) | Stack pricing rules cleanly |
| **Command** | Gate operations (park/unpark) as commands | Queue, log, undo |
| **Template Method** | Common park/unpark skeleton, subtypes override compatibility | Reuse flow, vary details |
| **Facade** | `ParkingLotService` exposing simple `park()`/`unpark()` over subsystems | Simple client API |
| **Composite** | Lot → floors → spots hierarchy | Uniform aggregate operations (count availability) |

> **Interview tip:** lead with **Strategy** (assignment + pricing), **Factory** (vehicle/spot creation), **Singleton** (lot), **Observer** (display boards), **State** (spot/ticket). That set covers most follow-ups.

---

## 5. Spot Assignment Strategy

```
parkVehicle(vehicle):
    for floor in floors (nearest-to-entry first):
        spot = assignmentStrategy.find(floor, vehicle)   # smallest compatible free spot
        if spot and spot.assign(vehicle):                # atomic CAS FREE→OCCUPIED
            return issueTicket(vehicle, spot)
    throw ParkingFullException(vehicleType)
```

Assignment strategies (swappable): **best-fit** (smallest compatible spot — maximizes utilization), **nearest to gate**, **by floor preference**, **EV-first for EVs**.

---

## 6. Pricing / Fee Calculation

```
FeeStrategy examples:
  HourlyFee:  ceil(hours) * ratePerHour[vehicleType]
  FlatDayFee: flat per calendar day
  ProgressiveFee: first hour X, subsequent hours Y

fee(ticket, exitTime):
    duration = exitTime - ticket.entryTime
    base = strategy.calculate(duration, vehicleType)
    apply decorators: + EV charging, + weekend surcharge, + tax
    return total
```

---

## 7. Data Model (if persisted)

```sql
CREATE TABLE parking_floor ( floor_id BIGINT PRIMARY KEY, lot_id BIGINT, level INT );
CREATE TABLE parking_spot (
    spot_id   BIGINT PRIMARY KEY, floor_id BIGINT, type VARCHAR(20),
    status    VARCHAR(20) NOT NULL DEFAULT 'FREE'
);
CREATE INDEX idx_spot_free ON parking_spot(floor_id, type) WHERE status='FREE';

CREATE TABLE ticket (
    ticket_id  BIGINT PRIMARY KEY, plate VARCHAR(20), vehicle_type VARCHAR(20),
    spot_id    BIGINT, entry_time TIMESTAMP NOT NULL, exit_time TIMESTAMP,
    amount     INT, status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
);
CREATE TABLE payment ( payment_id BIGINT PRIMARY KEY, ticket_id BIGINT, amount INT, method VARCHAR(20), status VARCHAR(20) );
CREATE TABLE parking_rate ( vehicle_type VARCHAR(20) PRIMARY KEY, rate_per_hour INT );
```

> **Tables to consider:** parking_lot, parking_floor, parking_spot, ticket, payment, parking_rate, vehicle (optional registry), display_board_state (optional).

---

## 8. APIs (if a service)

```
POST /v1/park        { plate, vehicleType }         → { ticketId, spotId, floor }
POST /v1/unpark      { ticketId, paymentMethod }     → { amount, receiptId }
GET  /v1/availability?type=CAR                        → { free: 42 }
GET  /v1/tickets/{id}
```

---

## 9. Concurrency

- **Spot assignment must be atomic** — two cars must never get the same spot. Use a **CAS/compare-and-set** on spot status (`UPDATE parking_spot SET status='OCCUPIED' WHERE spot_id=? AND status='FREE'` → check rows affected), or an in-memory lock per spot / `AtomicReference`.
- Availability counters: atomic increment/decrement (or derive from DB).
- If distributed (multiple gate servers): Redis atomic ops or DB conditional update as the source of truth.

---

## 10. Interview Cheat Sheet

> **"How do you model different vehicle and spot types?"**
>
> "Abstract `Vehicle` with subtypes that declare `canFitIn(SpotType)`; spots have a type. Creation via a **Factory**. This makes adding an EV or a bus a new subclass + rule, not a rewrite."

> **"How do you assign a spot?"**
>
> "A **Strategy** (`SpotAssignmentStrategy`) — default best-fit (smallest compatible free spot). Assignment flips spot status FREE→OCCUPIED atomically (CAS) so two cars can't take one spot."

> **"How is pricing handled and made flexible?"**
>
> "A `FeeStrategy` (hourly/flat/progressive) plus **Decorator** modifiers (EV charge, weekend surcharge, tax). Swap or stack rules without touching core logic."

> **"How do display boards stay updated?"**
>
> "**Observer** — boards/gates subscribe to the lot; a spot changing state publishes an availability update."

> **"Which patterns did you use?"**
>
> "Strategy (assignment + pricing), Factory (vehicles/spots), Singleton (lot), State (spot/ticket), Observer (boards), Decorator (fees), Facade (service API), Composite (lot→floor→spot)."

---

## 11. Final Takeaways

- It's an **OOD/LLD** problem — grade is on **clean, extensible classes + patterns**, not scale.
- **Strategy** for assignment & pricing; **Factory** for creation; **Singleton** for the lot; **State** for spot/ticket; **Observer** for boards; **Decorator** for fees.
- **Atomic spot assignment** (CAS) is the one real concurrency concern.
- Model `Vehicle.canFitIn(SpotType)` so new types are additive.
- Tables: lot, floor, spot, ticket, payment, rate.

### Related notes

- [BookMyShow — HLD & LLD](bookmyshow-hld-lld.md) · [Food Ordering — HLD & LLD](food-ordering-hld-lld.md) — more class-design + pattern examples
