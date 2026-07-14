# Parking Lot — System Design (OOD / LLD)

> **Core challenge:** **This is a class-modeling problem, not a scale problem.** Model vehicles, spots, tickets, pricing, and payment as clean, swappable objects so that "now add electric scooters / valet pricing / reservations" is a small extension, not a rewrite. The one real runtime hazard is two cars claiming one spot — solved with a single atomic FREE→OCCUPIED flip.

> A classic **object-oriented design (LLD)** problem. Core challenge: model a multi-floor parking lot that handles **different vehicle/spot types**, **assigns spots**, **issues tickets**, and **charges on exit** — with clean, extensible class design. Interviewers grade **your class model and the design patterns you apply**, not scale.

> **How to read this doc:** each section has the dense interview summary first, then a **deep dive** (annotated Java and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand.

> **The running example:** a **multi-floor parking garage** — you drive up to a **ticket gate**, a barrier arm blocks you, you press a button, a **paper ticket** prints with the time stamped on it, the arm lifts, and you drive in to find a spot. When you leave, you feed the ticket into a machine, it reads the entry time, charges you for how long you stayed, and only then lifts the exit barrier. Almost every class below maps to something physical in that garage.

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
- [10. State Machines (Ticket & Payment)](#10-state-machines-ticket--payment)
- [11. Extensibility](#11-extensibility)
- [12. How to Drive the Interview](#12-how-to-drive-the-interview)
- [13. Interview Cheat Sheet](#13-interview-cheat-sheet)
- [14. Final Takeaways](#14-final-takeaways)

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

### What are we actually building?

Walk through what happens at a parking garage, and notice that every line item above is just one of these steps:

1. A car (or bike, or truck) drives up to the **entry gate**. A camera/attendant figures out what kind of vehicle it is.
2. The system finds a **free spot that this vehicle actually fits in** (a truck can't squeeze into a motorcycle slot).
3. A **ticket** prints with the entry time. The barrier lifts.
4. The car parks. A **display board** near the entrance updates: "Level 2: 41 spots free."
5. Later the car drives to the **exit gate**, the machine reads the ticket, works out **how long** it stayed, charges the right **fee**, takes **payment**, and lifts the barrier.

That's the whole system. Everything hard about it comes from **variety** (many vehicle types, spot types, pricing rules) and **not messing up** (two cars must never be sent to the same spot).

#### Functional vs non-functional — what's the difference?

Beginners often blur these two lists. A simple way to keep them apart:

| | Question it answers | Examples here |
| --- | --- | --- |
| **Functional** | "What can the system *do*?" (features) | park a car, issue a ticket, compute a fee, show free-spot counts |
| **Non-functional** | "*How well* must it do it?" (qualities) | easy to add a new vehicle type without a rewrite; two cars never grab one spot |

> **Why "extensible" is called out as a requirement:** this is an *OOD* problem. The interviewer is really asking "when I later say *now add electric scooters* or *now add valet pricing*, does your design bend gracefully, or do you have to rewrite everything?" Good class design is the actual deliverable — see §3 and §4.

### Why bother with vehicle types at all — isn't a spot just a spot

Spots have **sizes**, and a vehicle only fits in some of them. A motorcycle fits almost anywhere; a truck needs a large spot. If you ignore types you'll happily assign a bus to a compact slot and it physically won't fit. So "types + a fit rule" is the very first modeling decision (`Vehicle.canFitIn(SpotType)`).

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

### Mapping each entity to the real garage

The trick to remembering these entities: **every one is a physical thing you can point at in the garage.**

| Entity | Point at it in the garage | One-line job |
| --- | --- | --- |
| `ParkingLot` | the whole building | knows the grand total of free spots |
| `ParkingFloor` | Level 1, Level 2, Level 3 | holds the spots on that level |
| `ParkingSpot` | one painted rectangle on the ground | is it free or taken, and what size is it |
| `Vehicle` | your car / bike / truck | how big am I, what can I fit into |
| `Ticket` | the paper slip that prints at the gate | proof of *which spot* and *what time you entered* |
| `EntryGate` / `ExitGate` | the barriers with the machines | entry prints the ticket; exit charges you |
| `FeeCalculator` | the little sign "₹40/hour" + the exit machine's math | turns "you stayed 3 hrs" into "you owe ₹120" |
| `PaymentProcessor` | the card reader / UPI QR | actually takes the money |

#### The "has-a" relationships (composition)

The arrows in the diagram above are all **"has-a"** (containment), which is the backbone of the whole design:

```
ParkingLot  HAS MANY  ParkingFloor  HAS MANY  ParkingSpot
```

A lot **has** floors; a floor **has** spots. This is called **composition** — building bigger objects out of smaller ones. Compare with `Car extends Vehicle`, which is **inheritance** ("is-a"). Beginners mix these up:

> - **"is-a" (inheritance):** a `Car` **is a** `Vehicle`. Use `extends`.
> - **"has-a" (composition):** a `ParkingLot` **has** `ParkingFloor`s. Use a field/list.

### Why `Ticket` links vehicle + spot + entry time all together

At exit you're holding **only the ticket**, and from it alone you must answer: *which spot do I free up?* and *how much do I charge?* The entry time gives you duration (→ fee); the spot reference lets you flip that spot back to FREE. The ticket is the little bundle of state that ties an entry to its future exit.

---

## 3. Class Design

```java
enum VehicleType { MOTORCYCLE, CAR, TRUCK }
enum SpotType    { COMPACT, LARGE, MOTORCYCLE, HANDICAPPED, EV }
enum SpotStatus  { FREE, OCCUPIED, OUT_OF_SERVICE }
enum TicketStatus{ ACTIVE, PAID, CLOSED, LOST }

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

### Reading the class model piece by piece

The compact code above is the whole design squished together. Here is the **same design, spelled out with comments** so you can see *why* each piece looks the way it does.

#### Step 1 — enums: the fixed vocabularies

Enums are just "a fixed list of allowed values." They stop typos and make the code read like English.

```java
// The kinds of vehicles the garage accepts. Adding "BUS" later = one word here.
enum VehicleType { MOTORCYCLE, CAR, TRUCK }

// The kinds of painted spots on the ground.
enum SpotType    { COMPACT, LARGE, MOTORCYCLE, HANDICAPPED, EV }

// A spot is either open, taken, or broken/closed for maintenance.
enum SpotStatus  { FREE, OCCUPIED, OUT_OF_SERVICE }

// A ticket's life: active while parked → paid at exit → closed once you drive out.
// LOST is the side-branch for a dropped ticket (see the state machine in §10).
enum TicketStatus{ ACTIVE, PAID, CLOSED, LOST }
```

#### Step 2 — the Vehicle hierarchy and the ONE key rule: `canFitIn`

This is the heart of the fitting logic. `Vehicle` is **abstract** (you never make a plain "Vehicle" — only a Car/Motorcycle/Truck). Each subtype answers *one* question: **which spot sizes can I physically use?**

```java
abstract class Vehicle {
    String plate;             // number plate — how we identify the car
    VehicleType type;

    // Every concrete vehicle MUST answer this. It is the fitting rule.
    // "Given a spot of this size, can I actually park in it?"
    abstract boolean canFitIn(SpotType spot);
}

// A car fits in a compact or large spot, and can use an EV charging spot too.
class Car extends Vehicle {
    boolean canFitIn(SpotType s){ return s == COMPACT || s == LARGE || s == EV; }
}

// A motorcycle is tiny — it fits in its own slot or a bigger compact one.
class Motorcycle extends Vehicle {
    boolean canFitIn(SpotType s){ return s == MOTORCYCLE || s == COMPACT; }
}

// A truck is big — only a large spot works.
class Truck extends Vehicle {
    boolean canFitIn(SpotType s){ return s == LARGE; }
}
```

> **Why put the rule *inside* the vehicle?** Because when you later add `class ElectricScooter extends Vehicle`, you write its `canFitIn` and you're done — no giant `if (type == CAR && spot == ...)` switch statement scattered across the codebase to hunt down and edit. The rule lives with the thing it describes. This is the "extensible" non-functional requirement paying off.

#### Step 3 — ParkingSpot: one rectangle that knows if it's taken

```java
class ParkingSpot {
    String id;
    SpotType type;          // COMPACT / LARGE / EV ...
    SpotStatus status;      // FREE / OCCUPIED / OUT_OF_SERVICE
    Vehicle current;        // who's parked here right now (null if free)

    // Try to claim this spot. Returns false if someone already took it.
    // The FREE→OCCUPIED flip MUST be atomic (see §9) so two cars can't both win.
    boolean assign(Vehicle v){ /* CAS FREE→OCCUPIED, set current=v */ }

    // Car left — open the spot back up.
    void free(){ status = FREE; current = null; }
}
```

#### Step 4 — ParkingFloor and ParkingLot: the containers

```java
class ParkingFloor {
    int level;
    // Spots grouped by size, so "find a free COMPACT" doesn't scan the whole floor.
    Map<SpotType, List<ParkingSpot>> spots;
    DisplayBoard board;     // the "41 spots free" sign for this level

    // Ask the current strategy to pick a spot for this vehicle on this floor.
    Optional<ParkingSpot> findFreeSpot(Vehicle v, SpotAssignmentStrategy s);
}

class ParkingLot {                     // Singleton: there is exactly ONE lot object
    List<ParkingFloor> floors;
    SpotAssignmentStrategy assignment; // HOW we pick spots (swappable — see §5)
    FeeStrategy feeStrategy;           // HOW we price (swappable — see §6)

    Ticket parkVehicle(Vehicle v);           // find a spot + print a ticket
    Receipt unpark(Ticket t, PaymentMethod m); // compute fee + take payment + free spot
    int availability(SpotType type);          // how many of this size are free
}
```

> `Optional<ParkingSpot>` is Java's polite way of saying "maybe a spot, maybe nothing." It forces the caller to handle the **lot-is-full** case instead of crashing on a `null`.

#### Step 5 — the interfaces: the swappable "how" parts

The three interfaces are the seams where the design flexes. An **interface** is a promise ("anything that implements me can do X") without saying *how*.

```java
// HOW to choose a spot: nearest? smallest that fits? EV-first? Each is a class.
interface SpotAssignmentStrategy { Optional<ParkingSpot> find(ParkingFloor f, Vehicle v); }

// HOW to price: hourly? flat day rate? progressive? Each is a class.
interface FeeStrategy { Money calculate(Ticket t, Instant exit); }

// HOW to take money: cash? card? UPI? Each is a class.
interface PaymentProcessor { boolean pay(Money amount, PaymentMethod m); }
```

This is the **Strategy pattern** (see §4): `ParkingLot` holds a `FeeStrategy` but doesn't care *which* one — you can swap `HourlyFee` for `FlatDayFee` at startup without touching `ParkingLot`'s code at all.

#### Q: Why is `Vehicle` abstract but `SpotAssignmentStrategy` an interface — what's the difference?

- **Abstract class** (`Vehicle`): shares *state and code* among subtypes (all vehicles have a `plate`). Use when subtypes are variations of one real thing.
- **Interface** (`SpotAssignmentStrategy`): shares only a *promise / capability*, no state. Use when unrelated classes all need to "be pluggable here."

Rule of thumb: **is-a-kind-of → abstract class; can-do-this-job → interface.**

### Where the actual "park a car" flow lives

Inside `ParkingLot.parkVehicle(v)`, which orchestrates the smaller objects: ask each floor (via the assignment strategy) for a fitting free spot, atomically claim it, then create and return a `Ticket`. The step-by-step pseudocode for exactly this is in §5.

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

### The patterns, each in the garage

"Design patterns" sound scary but each is just a **named, reusable solution to a recurring problem**. Here's each one as a concrete thing in our garage — with the smallest possible code.

#### Strategy — "swap the algorithm, not the machine"

**Problem:** you want to change *how* spots are picked or priced without rewriting `ParkingLot`. **Solution:** put each algorithm in its own class behind an interface, and hand one to the lot.

```java
// Two different "how to pick a spot" algorithms, same interface.
class BestFitStrategy implements SpotAssignmentStrategy { /* smallest spot that fits */ }
class NearestToGateStrategy implements SpotAssignmentStrategy { /* closest free spot */ }

// Swapping behavior = one line. ParkingLot's code never changes.
lot.assignment = new NearestToGateStrategy();
```

**Same garage, different rulebook for picking spots.**

#### Factory — "one place that builds things"

**Problem:** the entry gate only knows the *string* `"CAR"`; it needs an actual `Car` object. **Solution:** a factory turns the type into the right object, so the "which class to `new`" logic lives in exactly one spot.

```java
class VehicleFactory {
    static Vehicle create(VehicleType type, String plate) {
        return switch (type) {                 // ONE place that knows all subtypes
            case CAR        -> new Car(plate);
            case MOTORCYCLE -> new Motorcycle(plate);
            case TRUCK      -> new Truck(plate);
        };
    }
}
```

The gate attendant reads the vehicle type and hands back the right kind of object — callers don't build it themselves. Add a bus? Add one line here, not everywhere someone creates a vehicle.

#### Singleton — "there is only ONE lot"

**Problem:** if two `ParkingLot` objects existed, each would think it has its own free-spot count → chaos. **Solution:** guarantee a single shared instance.

```java
class ParkingLot {
    private static final ParkingLot INSTANCE = new ParkingLot();
    private ParkingLot() {}                       // nobody else can `new` it
    public static ParkingLot get() { return INSTANCE; }
}
```

There's **one physical garage**, so there's one source of truth for "how many spots are free."

#### State — "an object behaves differently depending on its status"

A `ParkingSpot` can only make *legal* moves: FREE → OCCUPIED → FREE. It can't go from OCCUPIED straight to OCCUPIED again. The `SpotStatus` enum encodes exactly which transitions are allowed, so a car can't "double-park" into a taken spot.

```
FREE ──assign()──► OCCUPIED ──free()──► FREE
   └────────► OUT_OF_SERVICE (maintenance) ────────┘
```

#### Observer — "the boards watch the lot and auto-update"

**Problem:** when a spot fills, every display board and gate needs the new "40 free" number. **Solution:** boards *subscribe* to the lot; the lot *notifies* them on any change. Nobody polls.

```java
// When a spot changes, the lot pushes the news to everyone watching.
void onSpotChanged() {
    for (DisplayBoard b : subscribers) b.update(availability());
}
```

The "SPACES: 40" LED sign flips the instant a car parks — because it's *listening*, not re-counting every second.

#### Decorator — stack fee rules

Pricing is rarely one rule. It's base fare **+** EV charging **+** weekend surcharge **+** tax. Decorator lets you wrap a base fee with add-ons, each independent.

```java
FeeStrategy fee = new HourlyFee();
fee = new EvChargingSurcharge(fee);   // wrap: add EV cost
fee = new WeekendSurcharge(fee);      // wrap: add weekend cost
fee = new TaxDecorator(fee);          // wrap: add tax on top
// calling fee.calculate(...) now runs all four, outer-to-inner.
```

Each add-on wraps the one beneath it, so the fee is built up layer by layer.

> **Composite** (`Lot → Floors → Spots`) just means you can ask "how many free?" at any level and it recurses down — the lot sums its floors, each floor sums its spots. **Facade** (`ParkingLotService`) is a friendly front desk that hides all these moving parts behind a simple `park()` / `unpark()`.

### Do you have to use all these patterns

No — cramming all ten in is a red flag. Lead with the five that genuinely fit (Strategy, Factory, Singleton, State, Observer) and mention the rest only if a follow-up invites them (e.g. "how would you add weekend pricing?" → Decorator). Patterns are tools, not a checklist.

---

## 5. Spot Assignment Strategy

Assignment strategies (swappable): **best-fit** (smallest compatible spot — maximizes utilization), **nearest to gate**, **by floor preference**, **EV-first for EVs**. (The `parkVehicle` flow — walk floors → `find` a compatible spot → atomic CAS claim → issue ticket, else `ParkingFullException` — is shown as annotated code in the deep dive below.)

### How a spot actually gets chosen

Picture yourself driving in. You want the *nearest* spot your car fits in, and the garage wants to *not waste* a big spot on a small car. That tension is what "assignment strategy" resolves.

#### Best-fit: why not just grab the first free spot?

If a motorcycle grabs a **large** spot just because it was free first, then a truck arrives and finds no large spot left — even though a motorcycle slot was empty. **Best-fit** = give each vehicle the *smallest* spot it still fits in, saving big spots for big vehicles.

```java
// Best-fit: try the tightest compatible size first, then bigger ones.
class BestFitStrategy implements SpotAssignmentStrategy {
    // sizes ordered smallest → largest
    private static final List<SpotType> ORDER =
        List.of(MOTORCYCLE, COMPACT, EV, LARGE, HANDICAPPED);

    public Optional<ParkingSpot> find(ParkingFloor floor, Vehicle v) {
        for (SpotType size : ORDER) {
            if (!v.canFitIn(size)) continue;          // skip sizes this vehicle can't use
            for (ParkingSpot spot : floor.spots.getOrDefault(size, List.of())) {
                if (spot.status == FREE) return Optional.of(spot);  // smallest fit wins
            }
        }
        return Optional.empty();                       // nothing on this floor
    }
}
```

#### Q: Why best-fit and not first-fit?

**First-fit** grabs the first free spot you stumble across, regardless of size. It's simpler and marginally faster, but it *wastes big spots on small vehicles*: a motorcycle lands in the first LARGE spot it sees, and later a truck (which *only* fits LARGE) is turned away even though a MOTORCYCLE slot sat empty the whole time. **Best-fit** hands each vehicle the *tightest* size it still fits in, keeping scarce large spots free for the vehicles that genuinely need them — higher overall utilization. The cost is a little extra work (walk sizes smallest→largest), but it's the same `SpotAssignmentStrategy` interface either way, so you can start with first-fit and swap in best-fit later with zero changes to `ParkingLot`. 💡 In an interview, name both and say "I'd default to best-fit for utilization, but it's a one-line Strategy swap."

#### The full park flow, annotated

The full `parkVehicle` flow, written out:

```java
Ticket parkVehicle(Vehicle vehicle) {
    // Floors are checked nearest-to-entrance first, so you park close by.
    for (ParkingFloor floor : floors) {
        Optional<ParkingSpot> maybe = assignmentStrategy.find(floor, vehicle);
        if (maybe.isEmpty()) continue;                 // this floor full for this size

        ParkingSpot spot = maybe.get();
        // CRITICAL: atomically flip FREE→OCCUPIED. If another car beat us to it,
        // assign() returns false and we keep looking. (Concurrency — see §9.)
        if (spot.assign(vehicle)) {
            return issueTicket(vehicle, spot);         // print the paper ticket
        }
        // assign() failed → someone grabbed it a millisecond ago → try next spot/floor
    }
    throw new ParkingFullException(vehicle.type);       // whole lot full for this type
}
```

> **Two `find` calls can return the same spot** to two different cars a millisecond apart. That's fine — `find` only *suggests*; `assign()` is the real, atomic claim. Only one car's `assign()` succeeds; the other loops on. This split ("suggest cheaply, claim atomically") is the same idea as optimistic locking.

### How you find the nearest spot

"Nearest" needs a notion of distance. Give each spot a distance-from-gate number (precomputed) and keep free spots per floor in a **min-heap / sorted structure** ordered by that distance; the nearest free one pops off in O(log n). Swapping best-fit for nearest-first is literally just a different `SpotAssignmentStrategy` implementation — `ParkingLot` doesn't change (that's the whole point of Strategy).

### What happens if the lot is completely full

`parkVehicle` finds no spot on any floor and throws `ParkingFullException` (or returns an empty `Optional`, depending on your API taste). The entry gate then shows "LOT FULL" and keeps the barrier down. The display boards (Observer) already show 0 free, so ideally the driver never even pulls up.

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

### Turning "time parked" into "money owed"

At exit the machine knows two things: **when you entered** (from the ticket) and **now**. Fee = a function of that gap, your vehicle type, and any extras. The clever bit is making that function *swappable and stackable*.

#### The base strategies, in code

```java
// Simple hourly: round UP to the next hour, times the per-type rate.
// Park 2h 5m → billed for 3h. (Garages almost always round up.)
class HourlyFee implements FeeStrategy {
    Map<VehicleType, Money> ratePerHour;   // e.g. CAR=₹40, TRUCK=₹80

    public Money calculate(Ticket t, Instant exit) {
        long hours = ceilHours(Duration.between(t.entryTime, exit)); // ceil = round up
        return ratePerHour.get(t.vehicle.type).times(hours);
    }
}

// Flat rate per calendar day — nice for "park all day" lots.
class FlatDayFee implements FeeStrategy {
    Money perDay;
    public Money calculate(Ticket t, Instant exit) {
        long days = Math.max(1, daysBetween(t.entryTime, exit));
        return perDay.times(days);
    }
}

// Progressive: first hour cheap to attract short stops, later hours pricier.
class ProgressiveFee implements FeeStrategy {
    Money firstHour, laterHour;
    public Money calculate(Ticket t, Instant exit) {
        long hours = ceilHours(Duration.between(t.entryTime, exit));
        if (hours <= 1) return firstHour;
        return firstHour.plus(laterHour.times(hours - 1));
    }
}
```

#### Stacking extras with Decorator

The base fee is just the start. Real bills add EV charging, weekend surcharge, tax — and you want to add/remove these without editing `HourlyFee`. That's **Decorator**: each extra *wraps* the strategy underneath it.

```java
// A decorator IS a FeeStrategy that holds another FeeStrategy and adds to it.
class WeekendSurcharge implements FeeStrategy {
    private final FeeStrategy inner;         // the fee we're wrapping
    WeekendSurcharge(FeeStrategy inner){ this.inner = inner; }

    public Money calculate(Ticket t, Instant exit) {
        Money base = inner.calculate(t, exit);           // whatever's underneath
        boolean weekend = isWeekend(t.entryTime);
        return weekend ? base.times(1.2) : base;         // +20% on weekends
    }
}
```

Building the final pricer = wrapping layers (outer runs last):

```java
FeeStrategy pricer = new TaxDecorator(          // +18% GST, applied on the very top
                        new WeekendSurcharge(   // +20% if weekend
                          new HourlyFee(rates)  // the base
                        ));
Money total = pricer.calculate(ticket, Instant.now());
```

### Hourly vs flat vs progressive — how to pick, and can it change later

You don't pick *in the code* — the lot holds a `FeeStrategy` field, and you inject whichever one the business wants (config at startup, or even per-lot). Changing from hourly to flat is swapping one object, zero changes to `ParkingLot` or `ExitGate`. That flexibility is exactly why pricing is a Strategy, not a big `if/else`.

#### Q: Why round hours *up* (`ceil`)?

Real garages bill in whole blocks — stay 61 minutes and you pay for 2 hours. `Math.ceil` models that. If you used plain integer division you'd under-bill (61 min → 1 hour). Small detail, but interviewers love catching it.

### Where "how long did it stay" actually gets computed

`Duration.between(ticket.entryTime, exitTime)`. The entry time was frozen onto the ticket at the gate; the exit time is "now." The whole fee system leans on that one stored timestamp — which is why `Ticket` carries `entryTime` (see §2).

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

### From classes to tables

If the garage runs across many gate machines (not just one program in memory), the state has to live in a **shared database** so every gate agrees on what's free. The tables are just the classes from §3, flattened into rows.

| Class (§3) | Table | Note |
| --- | --- | --- |
| `ParkingFloor` | `parking_floor` | one row per level |
| `ParkingSpot` | `parking_spot` | one row per painted rectangle |
| `Ticket` | `ticket` | one row per active/finished parking session |
| `PaymentProcessor` result | `payment` | one row per money movement |
| `ParkingRate` | `parking_rate` | the price list |

### Why the partial index `WHERE status='FREE'`

```sql
CREATE INDEX idx_spot_free ON parking_spot(floor_id, type) WHERE status='FREE';
```

The only query the entry gate runs constantly is *"give me a FREE spot of type X on floor Y."* A **partial index** indexes **only the FREE rows**, so the database jumps straight to available spots instead of scanning occupied ones too. In a full garage that's the difference between checking 3 rows and 3,000. It's the DB-level version of the in-memory `Map<SpotType, List<ParkingSpot>>` grouping from §3.

#### Q: In-memory objects OR a database — which is it?

Both are valid answers depending on scope:

- **Single machine / interview default:** keep everything as in-memory objects (the §3 classes). Simple, fast, no DB.
- **Multiple gate servers (distributed):** the in-memory picture can't be shared, so the DB becomes the **source of truth**, and the atomic FREE→OCCUPIED flip becomes a conditional SQL `UPDATE` (see §9). The classes still exist in code; they're just loaded from / saved to rows.

### Database & storage choices

The §3 objects are the runtime source of truth — a single-process interview answer needs no database at all. **If you persist** (multi-gate deployment, surviving a restart, or an audit trail):

| Data | Store | Why |
| --- | --- | --- |
| Lots/floors/spots, tickets, payments | **RDBMS** (Postgres/MySQL) | Spot assignment is the atomic conditional `UPDATE ... WHERE status='FREE'` from §9 — it needs row-level locking so two gates can never claim the same spot. Ticket + spot + payment also want to commit together (ACID), which a relational engine gives for free. |
| Real-time free-spot counts (entry gates, display boards) | **Redis** | Counts are read constantly by every gate/board and don't need durability — worst case after a crash is recomputing `COUNT(*) WHERE status='FREE'` from the DB. Redis takes that read/decrement traffic instead of hammering the RDBMS on every park/unpark. |

See [Databases — Deep Dive](../concepts/databases-deep-dive.md) for the general engine trade-offs.

---

## 8. APIs (if a service)

```
POST /v1/park        { plate, vehicleType }         → { ticketId, spotId, floor }
POST /v1/unpark      { ticketId, paymentMethod }     → { amount, receiptId }
GET  /v1/availability?type=CAR                        → { free: 42 }
GET  /v1/tickets/{id}
```

### The API is just the gates, over HTTP

If you wrap the garage as a web service, each endpoint is one physical action:

| Endpoint | Real-world action | Behind the scenes |
| --- | --- | --- |
| `POST /v1/park` | drive up, barrier lifts, ticket prints | `ParkingLot.parkVehicle()` → find spot, claim it, return ticket id |
| `POST /v1/unpark` | feed ticket at exit, pay, barrier lifts | compute fee, take payment, free the spot |
| `GET /v1/availability` | glance at the "SPACES: 42" sign | count free spots of that type |
| `GET /v1/tickets/{id}` | look at your ticket | read one ticket's details |

> Notice `park` and `unpark` are **POST** (they *change* state — claim/free a spot, move money) while `availability` and the ticket lookup are **GET** (they only *read*). That read/write split is exactly the `parkVehicle`/`unpark` vs `availability` split in the `ParkingLot` class.

---

## 9. Concurrency

- **Spot assignment must be atomic** — two cars must never get the same spot. Use a **CAS/compare-and-set** on spot status (`UPDATE parking_spot SET status='OCCUPIED' WHERE spot_id=? AND status='FREE'` → check rows affected), or an in-memory lock per spot / `AtomicReference`.
- Availability counters: atomic increment/decrement (or derive from DB).
- If distributed (multiple gate servers): Redis atomic ops or DB conditional update as the source of truth.

### The "two cars, one spot" race

This is *the* concurrency question for this problem, so let's make it vivid.

Two cars enter at the same instant through two different gates. Both gate computers run `find()` and both are told "spot 2B on Level 2 is FREE." If both simply write "OCCUPIED," **both drivers get sent to 2B** and one finds the other already parked there. Bad.

```
Gate 1: read 2B = FREE ─┐
                        ├─ both see FREE at the same moment
Gate 2: read 2B = FREE ─┘
Gate 1: write OCCUPIED  ← "2B is yours!"
Gate 2: write OCCUPIED  ← "2B is yours!"   ← DISASTER: two cars, one spot
```

The fix is **compare-and-set (CAS)**: make "check it's still FREE" and "set it to OCCUPIED" a **single, indivisible step**. Only one of the two can win; the loser is told "already taken" and looks elsewhere.

#### In-memory (single machine) — atomic flip

```java
class ParkingSpot {
    // AtomicReference gives us a hardware-level compare-and-set.
    private final AtomicReference<SpotStatus> status =
        new AtomicReference<>(SpotStatus.FREE);
    Vehicle current;

    boolean assign(Vehicle v) {
        // "IF status is still FREE, set it to OCCUPIED" — atomically, in one shot.
        // Returns true for the winner, false for everyone else.
        if (status.compareAndSet(SpotStatus.FREE, SpotStatus.OCCUPIED)) {
            this.current = v;
            return true;          // WE won the spot
        }
        return false;             // someone beat us — caller tries another spot
    }
}
```

#### Distributed (many gate servers) — the DB decides

When gates run on different machines, there's no shared `AtomicReference` in RAM. The **database** becomes the single referee via a conditional `UPDATE`:

```sql
-- Only flips the row IF it's still FREE. The DB guarantees only ONE such
-- UPDATE can succeed for a given row at a time.
UPDATE parking_spot
   SET status = 'OCCUPIED'
 WHERE spot_id = ? AND status = 'FREE';
```

```java
int rows = jdbc.update(sql, spotId);
boolean iWonTheSpot = (rows == 1);   // 1 = I flipped it; 0 = someone else already did
```

> **The whole trick, in one line:** never "read then write" as two steps. Do the check-and-change as **one atomic operation** — `compareAndSet` in memory, or a conditional `UPDATE ... WHERE status='FREE'` in the DB — and use the result (true / `rows==1`) to know if you won.

#### Q: Why not just `synchronized` / lock the whole lot?

You *could* put one big lock around all parking, but then only **one car can park at a time in the entire garage** — even cars headed to different floors. That kills throughput. Per-spot CAS lets thousands of non-conflicting parks happen in parallel and only serializes the rare case where two cars truly want the *same* spot.

### Whether the availability counter has the same problem

Yes — if two cars park at once and both do `freeCount = freeCount - 1` as read-then-write, one decrement is lost. Use an **atomic** counter (`AtomicInteger.decrementAndGet()`), or just derive the count from the DB (`COUNT(*) WHERE status='FREE'`) so there's a single source of truth.

---

## 10. State Machines (Ticket & Payment)

> A **state machine** is just "here are the only states this thing can be in, and the only *legal* moves between them." Interviewers love this because it forces you to name the illegal moves and *guard* against them — the same discipline that stops a car double-parking (§4, State pattern).

### The ticket lifecycle

A `Ticket` is born the moment the entry barrier prints it and dies when you drive out the exit. Its whole life is four states:

```
                 pay OK                 barrier lifts,
   ┌────────┐  ─────────► ┌──────┐  ─────► ┌────────┐
   │ ACTIVE │             │ PAID │         │ CLOSED │  (terminal)
   └────────┘             └──────┘         └────────┘
       │  declare lost         ▲
       ▼                       │ pay penalty
   ┌────────┐  ────────────────┘
   │  LOST  │
   └────────┘
```

- **ACTIVE** — issued at entry; the car is parked; its spot is `OCCUPIED`.
- **PAID** — fee has been paid at the exit machine, but the barrier hasn't lifted / the spot isn't freed yet.
- **CLOSED** — terminal: barrier lifted, spot flipped back to `FREE`, session done.
- **LOST** — side-branch: driver lost the paper ticket; resolved with a flat/penalty fee, then it re-joins the normal `PAID → CLOSED` path.

> 💡 **Why separate PAID from CLOSED?** They are two *different* real-world moments — money changing hands vs the barrier physically lifting and the spot reopening. Keeping them apart means a crash *between* them (paid, but barrier stuck) leaves the ticket in a clear, recoverable `PAID` state instead of an ambiguous one. (Same "resource state ≠ request state" separation as seats vs bookings in the BookMyShow note.)

### The illegal-transition guard

The point of an enum isn't the four words — it's rejecting the moves that shouldn't happen. Encode the legal edges once and refuse everything else:

```java
class Ticket {
    String ticketId; Vehicle vehicle; ParkingSpot spot;
    Instant entryTime; TicketStatus status = TicketStatus.ACTIVE;

    // The ONLY legal moves. Everything not listed here is rejected.
    private static final Map<TicketStatus, Set<TicketStatus>> LEGAL = Map.of(
        ACTIVE, Set.of(PAID, LOST),   // park → pay, or lose the ticket
        LOST,   Set.of(PAID),         // pay the penalty, then continue
        PAID,   Set.of(CLOSED),       // barrier lifts, spot freed
        CLOSED, Set.of()              // terminal — no way out
    );

    void moveTo(TicketStatus next) {
        if (!LEGAL.get(status).contains(next))
            throw new IllegalStateException(status + " ✗→ " + next); // guard
        status = next;
    }
}
```

> ⚠️ **Pitfall — the moves this blocks:** `ACTIVE → CLOSED` (driving out without paying), `PAID → PAID` (a double-charge from a retry / double-click), and anything out of `CLOSED` (reusing a spent ticket). Without the guard these become silent data corruption; with it they're a loud, testable exception.

#### Q: What if the user loses the ticket?

They can't prove *when* they entered, so you can't compute an honest duration. Model it as the `LOST` branch: the ticket (looked up by plate, or a fresh "lost ticket" record for that spot) moves `ACTIVE → LOST`, and a **lost-ticket `FeeStrategy`** charges a flat maximum-stay penalty (e.g. "one full day") instead of `Duration.between(...)`. Once that penalty is paid it flows `LOST → PAID → CLOSED` exactly like a normal exit, and the spot frees. 💡 Notice this needed **no new control flow** — just another `FeeStrategy` and one extra enum edge. That's the extensibility payoff again (§11).

### The payment lifecycle & the exit flow

Payment is its own little machine, because charging a card can *fail* — and a failed exit payment is the one flow beginners forget.

```
   ┌─────────┐  charge OK   ┌─────────┐
   │ PENDING │ ───────────► │ SUCCESS │ ──► ticket ACTIVE→PAID
   └─────────┘              └─────────┘
        │  charge declined        ▲
        ▼                         │ retry / new method
   ┌─────────┐  ──────────────────┘
   │ FAILED  │
   └─────────┘
```

Here is `unpark` walking `PaymentProcessor` through it, including the failure and refund paths:

```java
Receipt unpark(Ticket t, PaymentMethod method) {
    Money amount = feeStrategy.calculate(t, Instant.now());  // duration → money (§6)

    Payment p = new Payment(t.ticketId, amount);             // status = PENDING
    boolean charged = paymentProcessor.pay(amount, method);  // talk to card/UPI/cash

    if (!charged) {
        p.status = FAILED;
        // ⚠️ DO NOT free the spot. Barrier stays DOWN, ticket stays ACTIVE,
        // spot stays OCCUPIED. The car is still physically here — nothing owed is settled.
        throw new PaymentFailedException(t.ticketId);        // gate shows "payment failed, retry"
    }

    p.status = SUCCESS;
    t.moveTo(PAID);          // guarded: only legal from ACTIVE (or LOST)
    t.spot.free();           // NOW flip the spot FREE→ ... and lift the barrier
    t.moveTo(CLOSED);        // terminal
    return new Receipt(t, amount);
}
```

> ⚠️ **The rule that keeps money and spots consistent: the spot stays `OCCUPIED` until payment is confirmed truth.** Freeing the spot first and *then* charging would let a car leave without paying if the charge fails. Order matters: **charge → then free.**

**The refund path (charge succeeded but we couldn't finish).** If `pay()` actually debited the card but the response was lost (timeout), a naive retry would **double-charge**. Guard it the same way BookMyShow does: make the charge **idempotent** (one `Payment` row per ticket-exit attempt, keyed by `ticketId`), and run a **reconciliation** check — if the gateway says "already charged," don't charge again; if we charged but the ticket never reached `CLOSED` (e.g. barrier jammed), issue a **refund** and reset `PAID → ` back through a compensating step so the driver isn't billed for a spot they never left. The spot only leaves `OCCUPIED` once the exit truly completes.

> 💡 **Beginner takeaway:** the happy path is trivial; the grade is in the *unhappy* path. "Payment failed → spot stays occupied, barrier stays down, retry" and "charged twice → idempotent + refund" are exactly the edges interviewers push on.

---

## 11. Extensibility

> The whole reason this problem is *OOD* is the follow-up: "great — now change it." A good design absorbs each of these as a **new class / enum value / Strategy**, with `ParkingLot` untouched. Here are three classic asks and the small, surgical change each needs.

### 1. Add a new vehicle type — the electric scooter

**Ask:** "We now rent electric scooters. Support them." **Change:** one enum value + one subclass. No `if/else` hunt, because the fitting rule lives *inside* the vehicle (§3).

```java
enum VehicleType { MOTORCYCLE, CAR, TRUCK, ELECTRIC_SCOOTER }   // +1 word

class ElectricScooter extends Vehicle {
    // Tiny — fits a motorcycle slot, and prefers EV spots for charging.
    boolean canFitIn(SpotType s){ return s == MOTORCYCLE || s == EV; }
}
```

Register it in `VehicleFactory` (one `case`) and you're done — assignment, pricing, ticketing all keep working because they only ever call `canFitIn` / `type`. ✅ **Enum + subclass swap, not a rewrite.**

### 2. Add valet pricing

**Ask:** "Valet customers pay a premium on top of parking." **Change:** a new `FeeStrategy` — or, better, a **Decorator** so it stacks on any base pricing (§6).

```java
class ValetSurcharge implements FeeStrategy {
    private final FeeStrategy inner;
    ValetSurcharge(FeeStrategy inner){ this.inner = inner; }
    public Money calculate(Ticket t, Instant exit) {
        return inner.calculate(t, exit).plus(Money.of(200));  // flat valet fee on top
    }
}
// wire it: new ValetSurcharge(new HourlyFee(rates))
```

`ParkingLot` and `ExitGate` never learn the word "valet" — they just hold a `FeeStrategy`. ✅ **Strategy/Decorator swap.**

### 3. Add a reservation hold

**Ask:** "Let users reserve a spot before arriving." **Change:** one `SpotStatus` value + reuse the *same atomic CAS* as parking (§9).

```java
enum SpotStatus { FREE, OCCUPIED, OUT_OF_SERVICE, RESERVED }   // +1 word

// Reserve = the same compare-and-set, just a different target state.
boolean reserve(){ return status.compareAndSet(FREE, RESERVED); }  // FREE→RESERVED
// On arrival the ticket flips RESERVED→OCCUPIED; on no-show, an expiry job flips it back to FREE.
```

The "don't let two people grab it" guarantee is *already solved* by CAS — reservation is just a third state on the spot's own state machine, with an expiry sweep (same idea as BookMyShow's lock expiry). ✅ **Enum value + existing CAS, not a rewrite.**

> **Pattern across all three:** the change is *additive* — a new enum value, a new subclass, or a new Strategy/Decorator — and the core `ParkingLot` orchestration is never edited. That "open for extension, closed for modification" property **is** the deliverable being graded.

---

## 12. How to Drive the Interview

> An OOD interview rewards a **visible method**, not a burst of classes. Narrate these four moves in order; each builds on the last.

1. **Nail the entities first (nouns → classes).** Say the requirements out loud and pull out the nouns: lot, floor, spot, vehicle, ticket, gate, fee, payment (§2). Draw the **has-a** hierarchy (`Lot → Floor → Spot`) and the **is-a** hierarchy (`Car/Truck/Motorcycle extends Vehicle`). Land the one key rule early: `Vehicle.canFitIn(SpotType)` (§3). This shows you can model before you code.
2. **Find the axes of change → introduce Strategy.** Ask "what will they make me change later?" — *how spots are picked* and *how we price*. Pull each behind an interface (`SpotAssignmentStrategy`, `FeeStrategy`) so they're swappable (§5, §6). This is the moment you turn a static class diagram into an *extensible* one.
3. **Name the one real race → CAS.** Volunteer the "two cars, one spot" hazard before they ask, and solve it with an **atomic FREE→OCCUPIED** compare-and-set (in-memory `AtomicReference`, or a conditional `UPDATE ... WHERE status='FREE'` when distributed) — `find()` only suggests, `assign()` is the atomic claim (§9). Explicitly reject "one big lock on the lot" as a throughput killer.
4. **Tie it together with patterns + state machines.** Summarize the pattern set (Strategy, Factory, Singleton, State, Observer — §4) and show the **Ticket/Payment state machines with guards** (§10), including the unhappy path (payment fails → spot stays occupied). Close with the extensibility follow-ups (§11) to prove the design bends, not breaks.

> 🎤 **Lead with the core challenge:** "This is graded on class design and extensibility, not scale — so I'll model clean objects, make the *how* parts swappable via Strategy, and guard the one real race with an atomic spot claim." Then spend most of your time in steps 1–3.

---

## 13. Interview Cheat Sheet

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

### Tricky scenarios (rapid-fire)

| Scenario | What happens / what to do |
| --- | --- |
| **Lot fills up mid-assignment** | `find()` suggested a spot but it got taken a millisecond ago → `assign()`'s CAS returns false → **loop to the next spot/floor**; if none, throw `ParkingFullException` and show "LOT FULL". |
| **Two cars, `assign()` CAS lose** | Only one `compareAndSet(FREE, OCCUPIED)` wins; the loser gets `false` and **retries the next candidate spot** — never blocks (§9). |
| **Redis count says free, DB says taken** | The **DB is the source of truth**. Redis free-counts are a cache/optimization; on mismatch trust the conditional `UPDATE ... WHERE status='FREE'` (0 rows = taken) and let Redis re-sync from `COUNT(*)`. |
| **Lost ticket** | No entry time to bill → `ACTIVE → LOST`, charge a **flat max-stay penalty** via a lost-ticket `FeeStrategy`, then `LOST → PAID → CLOSED` (§10). |
| **Exit payment fails** | Ticket stays `ACTIVE`, spot stays `OCCUPIED`, barrier stays **down**; prompt retry / another method. **Charge → then free**, never the reverse (§10). |
| **Charged but response lost** | Idempotent `Payment` (keyed by ticket-exit) + reconciliation → don't double-charge; if charged but not `CLOSED`, **refund** and keep the spot `OCCUPIED` until resolved. |

> **Ultimate layer model:** `canFitIn` = correct assignment · CAS = single winner on a spot · state-machine guards = no illegal ticket/payment moves · Strategy/Decorator = swap behavior without a rewrite.

---

## 14. Final Takeaways

- It's an **OOD/LLD** problem — grade is on **clean, extensible classes + patterns**, not scale.
- **Strategy** for assignment & pricing; **Factory** for creation; **Singleton** for the lot; **State** for spot/ticket; **Observer** for boards; **Decorator** for fees.
- **Atomic spot assignment** (CAS) is the one real concurrency concern.
- Model `Vehicle.canFitIn(SpotType)` so new types are additive.
- Tables: lot, floor, spot, ticket, payment, rate.

### Related notes

- [BookMyShow — HLD & LLD](bookmyshow-hld-lld.md) · [Food Ordering — HLD & LLD](food-ordering-hld-lld.md) — more class-design + pattern examples
