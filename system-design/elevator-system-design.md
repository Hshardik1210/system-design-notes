# Elevator System — Low-Level Design (OOD)

> A classic **OOD/LLD** problem: model a bank of elevators serving floor requests efficiently. Interviewers grade your **class model, the scheduling/dispatch strategy, and state handling**.

> **How to read this doc:** each section has the dense interview summary first, then a **deep dive** (annotated Java and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand.

---

## Contents

- [1. Requirements](#1-requirements)
- [2. Core Entities](#2-core-entities)
- [3. Class Design](#3-class-design)
- [4. Scheduling / Dispatch](#4-scheduling--dispatch)
- [5. Design Patterns (that can be used)](#5-design-patterns-that-can-be-used)
- [6. Interview Cheat Sheet](#6-interview-cheat-sheet)
- [7. Final Takeaways](#7-final-takeaways)

---

## 1. Requirements

**Functional**
- Multiple elevators, multiple floors.
- Two request types: **external** (hall call: up/down button on a floor) and **internal** (car call: destination floor inside the car).
- Dispatch the **best elevator** to a hall call; move cars efficiently; open/close doors.

**Non-functional**
- Minimize wait + travel time; extensible scheduling; thread-safe request handling.

> **Clarify:** number of elevators/floors; optimize for wait time or energy; express/service elevators?

### What are we actually building?

Take a 20-floor building with **4 elevator cars** side by side in the lobby. There are two completely different kinds of buttons, and mixing them up is the #1 beginner confusion:

- **Hall call (external):** the **up/down buttons on the wall of each floor.** You're standing on floor 7, you want to go down, you press **▼**. You are *not* telling the system which car to send or where you're going — just "someone on floor 7 wants to go down, send me a car."
- **Car call (internal):** the **panel of floor numbers inside the car.** Once you step in, you press **"12"** — now you're telling *that specific car* your destination.

```
Floor 7 wall:   [▲] [▼]     ← hall call: "a car please, I'm going down"   (external)
Inside car #2:  [1][2]...[12]...[20]  ← car call: "take ME to 12"        (internal)
```

So the system has two jobs, and it's worth naming them separately:

| Job | Trigger | Question it answers |
| --- | --- | --- |
| **Dispatch** | Hall call (wall button) | *Which* of the 4 cars should go pick this person up? |
| **Scheduling / movement** | Car calls + assigned hall calls | Given the stops one car must make, in *what order* does it visit them? |

- **"Minimize wait + travel time"** = the person tapping ▼ shouldn't stand there for 3 minutes, and once inside shouldn't ride up 10 floors before finally going down.
- **"Extensible scheduling"** = we should be able to swap the brain that answers those two questions (nearest-car today, energy-saving at night) without rewriting the elevators.
- **"Thread-safe"** = 50 people on 20 floors mash buttons *at the same time*; two button presses must not corrupt one car's stop list.

#### Q: Is dispatch the same thing as the SCAN algorithm?

No — this trips people up constantly. They are two layers:

- **Dispatch** = *choosing the car* (a fleet-level decision, one per hall call). → §4 cost function.
- **SCAN/LOOK** = *how one chosen car orders its own stops* (a single-car decision). → §4 movement.

A hall call first goes through dispatch ("car #2, you take it"), then car #2 folds that floor into its own SCAN route.

---

## 2. Core Entities

| Entity | Role |
| --- | --- |
| `ElevatorSystem` | Owns elevators + dispatcher; entry point for requests |
| `Elevator` (car) | Position, direction, state, request queues; moves |
| `Request` | External (floor + direction) or Internal (target floor) |
| `Dispatcher` | Picks which elevator serves a hall call (Strategy) |
| `Direction` / `ElevatorState` | UP/DOWN/IDLE; MOVING/STOPPED/DOORS_OPEN |
| `Door`, `Button`, `Display` | Peripherals |

### Who's who in the building

Map each class onto a real thing in the office tower:

| Class | Real-world thing | One-line job |
| --- | --- | --- |
| `ElevatorSystem` | The **whole elevator bank** + its control computer | The single front door for every button press |
| `Elevator` (car) | **One physical car** in its shaft | Knows where it is, which way it's going, and its remaining stops |
| `Request` | **A single button press** (wall or in-car) | "Floor 7 wants down" *or* "take me to 12" |
| `Dispatcher` | The **brain** that picks a car | Given a hall call, answers "car #2, you go" |
| `Direction` / `ElevatorState` | The **status of a car** | UP/DOWN/IDLE and MOVING/STOPPED/DOORS_OPEN |
| `Door`, `Button`, `Display` | The **physical bits** you touch/see | Open/close, get pressed, show the floor number |

The mental model: **one `ElevatorSystem` owns many `Elevator`s and one `Dispatcher`.** Button presses become `Request` objects; the `Dispatcher` routes hall calls to a car; each `Elevator` carries its own `Direction`/`ElevatorState`.

```
                       ┌────────── Elevator #1 (floor, direction, state, stops)
ElevatorSystem ───────►├────────── Elevator #2
   │  (owns)           ├────────── Elevator #3
   │                   └────────── Elevator #4
   └── Dispatcher  ← picks the best car for each hall call
```

#### Q: Why unify hall calls and car calls into one `Request` class?

Because from a *car's* point of view they're the same thing once assigned: **"a floor I must stop at."** A hall call adds a `direction` (so the car knows if you'll then go up or down) and `internal = false`; a car call is just a target floor with `internal = true`. Unifying them means one stop list, one SCAN loop — not two parallel code paths.

---

## 3. Class Design

The full class model is spelled out in the annotated walkthrough below.

### The classes, annotated

Here is the design **spelled out** so you can see exactly what each field does.

First the small value types — an `enum` is just a fixed set of named constants:

```java
// Which way a car is travelling. IDLE = parked, no pending stops.
enum Direction { UP, DOWN, IDLE }

// The lifecycle status of one car (its "state machine" — see §5 State pattern).
enum ElevatorState { MOVING, STOPPED, DOORS_OPEN, MAINTENANCE }
```

A `Request` = one button press. The `internal` flag is the only thing separating the two kinds:

```java
class Request {
    int       floor;       // hall call: the floor you're standing on
                           // car call:  the floor you want to reach
    Direction direction;   // hall call only: UP or DOWN (car calls leave this null/IDLE)
    boolean   internal;    // false = wall button (hall call), true = in-car button (car call)

    Request(int floor, Direction direction) {   // hall call constructor
        this.floor = floor; this.direction = direction; this.internal = false;
    }
    Request(int target) {                        // car call constructor
        this.floor = target; this.direction = Direction.IDLE; this.internal = true;
    }
}
```

An `Elevator` is the heart of it. The key trick: **two sorted sets of stops** — one for stops it will hit going up, one going down. `TreeSet<Integer>` keeps them **automatically sorted**, which is exactly what SCAN needs (§4):

```java
class Elevator {
    int   id;
    int   currentFloor = 0;
    Direction     direction = Direction.IDLE;
    ElevatorState state     = ElevatorState.STOPPED;

    // Stops above me I'll serve while going UP, kept sorted ascending: {5, 9, 14}
    TreeSet<Integer> upStops   = new TreeSet<>();
    // Stops below me I'll serve while going DOWN, sorted descending via reverse order: {12, 7, 3}
    TreeSet<Integer> downStops = new TreeSet<>(Comparator.reverseOrder());

    // Add a floor to the correct set based on where it is relative to me.
    void addStop(int floor) {
        if (floor == currentFloor) { openDoors(); return; }   // already here
        if (floor > currentFloor) upStops.add(floor);         // it's above → serve going up
        else                      downStops.add(floor);       // it's below → serve going down
        if (direction == Direction.IDLE) {                    // was parked → start moving
            direction = (floor > currentFloor) ? Direction.UP : Direction.DOWN;
        }
    }

    void openDoors() { state = ElevatorState.DOORS_OPEN; /* ...then close after a delay */ }

    void step() { /* one simulation tick — see the SCAN version in §4 */ }
}
```

The dispatch **strategy** is an interface so the car-picking brain can be swapped (§5 Strategy pattern):

```java
interface DispatchStrategy {
    // Given all cars and one hall call, return the best car to serve it.
    Elevator selectElevator(List<Elevator> elevators, Request hallCall);
}

// Default brain: pick whichever car is "cheapest" (closest & heading the right way).
class NearestCarStrategy implements DispatchStrategy {
    public Elevator selectElevator(List<Elevator> elevators, Request hallCall) {
        return elevators.stream()
            .min(Comparator.comparingInt(e -> cost(e, hallCall)))  // smallest cost wins
            .orElseThrow();
    }
    // (cost function detailed in §4)
    int cost(Elevator e, Request hallCall) { /* distance + direction match + load */ return 0; }
}
```

And the `ElevatorSystem` is the single entry point — every button in the building calls into it:

```java
class ElevatorSystem {
    List<Elevator>  elevators;
    DispatchStrategy dispatcher;   // swappable brain

    // Someone pressed ▲/▼ on a floor (external hall call).
    void requestElevator(int floor, Direction dir) {
        Elevator chosen = dispatcher.selectElevator(elevators, new Request(floor, dir));
        chosen.addStop(floor);      // dispatch decides the car; car folds it into its route
    }

    // Someone inside car #id pressed a destination (internal car call).
    void selectFloor(int elevatorId, int target) {
        elevators.get(elevatorId).addStop(target);   // no dispatch — the car is already chosen
    }

    // Advance every car by one tick (in a real system, a timer/clock drives this).
    void step() { elevators.forEach(Elevator::step); }
}
```

- Notice the asymmetry: **hall calls go through the dispatcher** (a car must be chosen); **car calls skip it** (you're already in a specific car).
- The car's stops live in `upStops` / `downStops`, not one flat queue — that's what makes efficient in-order service possible.

---

## 4. Scheduling / Dispatch

The interesting algorithmic part.

- **Per-elevator movement: SCAN / LOOK ("elevator algorithm")** — keep moving in one direction serving all stops, then reverse. Efficient + starvation-free.
- **Hall-call dispatch (which elevator):** score each elevator by a cost function — `cost = f(distance to the call, whether it's already heading that way, current load)` — and pick the minimum-cost elevator (NearestCar / "collective control"). (Full annotated `cost()` in the deep dive below.)
- Swap strategies (nearest-car, energy-optimized, zoned/express) via **Strategy**.

### How ONE car decides its route (SCAN / LOOK)

The **SCAN / LOOK "elevator algorithm":** **keep going in the current direction, stopping at every requested floor along the way, and only reverse when there's nothing left ahead.** The car doesn't jump back and forth across floors to serve requests in arrival order — it sweeps in one direction, then the other.

Why not just serve requests in the order they were pressed (**FCFS**)? Because it's horrible:

```
Car at floor 1. Presses arrive: 10, then 2.
FCFS:  1 → 10 → 2      (ride all the way up, then all the way back down — 17 floors)
SCAN:  1 → 2 → 10      (grab 2 on the way up — 9 floors)
```

FCFS also **starves** people: if new low-floor requests keep coming, a request for the top floor might wait forever. SCAN can't starve you — the sweep *will* reach your floor on this pass or the next.

> **LOOK vs SCAN (tiny distinction):** pure **SCAN** rides to the physical top/bottom even if no one's there (like an old disk-arm). **LOOK** — what real elevators and our code do — only goes as far as the *last actual stop*, then reverses. LOOK is SCAN that "looks ahead" and doesn't waste the trip.

Here's `step()` implementing LOOK, using the two sorted sets from §3:

```java
void step() {
    if (state == ElevatorState.DOORS_OPEN) { closeDoors(); return; }   // finish the stop first

    if (direction == Direction.UP) {
        Integer next = upStops.ceiling(currentFloor);   // nearest stop AT or ABOVE me
        if (next != null) {
            currentFloor++;                              // move one floor up
            if (currentFloor == next) { upStops.remove(next); openDoors(); }  // arrived → let people out/in
        } else {
            // nothing left above → LOOK: reverse only if there's work below
            direction = downStops.isEmpty() ? Direction.IDLE : Direction.DOWN;
        }
    } else if (direction == Direction.DOWN) {
        Integer next = downStops.floor(currentFloor);    // nearest stop AT or BELOW me
        if (next != null) {
            currentFloor--;
            if (currentFloor == next) { downStops.remove(next); openDoors(); }
        } else {
            direction = upStops.isEmpty() ? Direction.IDLE : Direction.UP;
        }
    }
    // direction == IDLE → parked, nothing to do until a new request arrives
}
```

- `upStops.ceiling(currentFloor)` = "give me the smallest stop ≥ where I am" — the very next place to stop while heading up. This is why we keep stops in a **sorted** `TreeSet`.
- When both sets empty out, the car goes **IDLE** and waits.

Walkthrough — car idle at floor 1, someone presses 10, then someone else presses 5:

```
addStop(10) → upStops {10}, direction becomes UP
addStop(5)  → upStops {5, 10}   (auto-sorted!)
step, step, step, step → arrive 5 → doors open → upStops {10}
step ... → arrive 10 → doors open → upStops {}  → direction IDLE
Served 5 BEFORE 10 automatically, even though 10 was pressed first. That's SCAN/LOOK.
```

### How the BANK decides which car (dispatch cost function)

That was one car. Now the fleet: a hall call comes in on floor 7 going down — **which of the 4 cars answers?** The `Dispatcher` scores every car with a **cost function** and picks the cheapest. Lower cost = "this car can serve you soonest with least detour."

```java
int cost(Elevator e, Request hallCall) {
    int distance = Math.abs(e.currentFloor - hallCall.floor);

    // Best case: the car is already heading toward you AND in the same travel direction
    // you want to go — it can scoop you up mid-sweep for almost free.
    boolean movingTowardYou =
        (e.direction == Direction.UP   && e.currentFloor <= hallCall.floor) ||
        (e.direction == Direction.DOWN && e.currentFloor >= hallCall.floor);
    boolean sameDirection = (e.direction == hallCall.direction);

    if (e.direction == Direction.IDLE)       return distance;            // free-ish, just distance
    if (movingTowardYou && sameDirection)    return distance;            // ideal: on the way
    if (movingTowardYou)                     return distance + 2;        // passing by, wrong dir
    return distance + e.pendingStops() + 5;                              // heading away → penalty
}
```

The dispatcher just takes the minimum:

```java
Elevator chosen = elevators.stream()
    .min(Comparator.comparingInt(e -> cost(e, hallCall)))
    .orElseThrow();
```

Concrete example — hall call on **floor 7, going DOWN**:

```
Car A: at floor 6, going UP    → moving toward 7 but wrong direction → cost = 1 + 2 = 3
Car B: at floor 9, going DOWN  → above you, heading down toward you  → cost = 2       ← cheapest ✅
Car C: at floor 1, IDLE        → parked, 6 floors away               → cost = 6
Car D: at floor 3, going UP    → heading away                        → cost = 4 + stops + 5
→ Dispatcher picks Car B: it's already coming down and will pass 7 anyway.
```

This "prefer the car already sweeping your way" rule is what real buildings call **collective control**. Swap the whole `cost` function via the **Strategy** pattern to optimize for energy (park cars low overnight), or **zoning** (car A serves floors 1–10, car B serves 11–20).

#### Q: What if two cars tie on cost?

Break the tie deterministically — e.g. lowest `id`, or the one with fewer pending stops. Any consistent rule works; the point is not to flip-flop and re-assign a call every tick.

#### Q: Once a car is assigned a hall call, can it be reassigned?

In simple designs, **no** — the floor is added to that car's stop list and it's committed. Fancier systems do **continuous re-optimization** (reassign if a better-placed car frees up), but that adds a lot of complexity and is usually out of scope for an interview unless asked.

#### Q: How do direction and state transitions actually flip?

- **Direction** flips inside `step()` via the LOOK rule: run out of stops ahead → reverse if work remains behind, else go `IDLE`.
- **State** is a separate machine (§5): `STOPPED → MOVING` when a stop is added, `MOVING → DOORS_OPEN` on arrival, `DOORS_OPEN → STOPPED/MOVING` after the door timer. `MAINTENANCE` is a manual override that takes the car out of dispatch entirely (the dispatcher skips cars not in service).

---

## 5. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Strategy** | `DispatchStrategy` (nearest-car/energy/zoned), movement policy | Swap scheduling algorithms |
| **State** | `ElevatorState` transitions (MOVING↔STOPPED↔DOORS_OPEN) | Guard legal transitions |
| **Command** | Requests as command objects queued to elevators | Uniform handling, queueing |
| **Observer** | Displays/buttons react to elevator state changes | Decouple UI from logic |
| **Singleton** | `ElevatorSystem` controller | One coordinator |
| **Factory** | Create elevator/request types | Extensible |
| **Mediator** | System coordinates elevators ↔ requests | Central dispatch logic |

> **Interview lead:** Strategy (dispatch + SCAN movement), State (elevator lifecycle), Command (requests), Observer (displays), Singleton (system).

### The patterns as building parts

Patterns sound abstract; here's each one as a thing in the tower, and the *smell* that tells you to reach for it:

| Pattern | In the elevator | The "smell" it fixes |
| --- | --- | --- |
| **Strategy** | The swappable dispatch brain (`DispatchStrategy`) | You have several *interchangeable algorithms* (nearest-car vs energy vs zoned) and want to switch without `if/else` sprawl |
| **State** | `ElevatorState` transitions | An object *behaves differently* depending on its status, and some transitions are illegal |
| **Command** | Each button press as a `Request` object | You want to *queue, log, or replay* actions uniformly |
| **Observer** | Floor displays / chimes reacting to a car | Several UI things must *react to a change* without the car knowing about them |
| **Singleton** | The one `ElevatorSystem` controller | There must be *exactly one* coordinator |
| **Factory** | Building elevators/requests | Object *creation* varies (express car vs normal) and you want it in one place |
| **Mediator** | `ElevatorSystem` sitting between cars and requests | Many objects would otherwise talk *directly* to each other; route through one hub |

**Strategy** is the star — it's literally why §3 made dispatch an interface. Swapping the brain is a one-liner:

```java
ElevatorSystem system = new ElevatorSystem();
system.dispatcher = new NearestCarStrategy();     // rush hour: minimize wait
// later, overnight:
system.dispatcher = new EnergySavingStrategy();   // park cars low, minimize movement
```

**State** guards illegal moves — a car must not start moving while its doors are open:

```java
class Elevator {
    ElevatorState state = ElevatorState.STOPPED;

    void startMoving() {
        if (state == ElevatorState.DOORS_OPEN)
            throw new IllegalStateException("Can't move with doors open!");  // guard
        state = ElevatorState.MOVING;
    }
}
```

Legal transitions, as a picture:

```
STOPPED ──addStop──► MOVING ──arrive──► DOORS_OPEN ──timer──► STOPPED
   │                                                             │
   └───────────────────► MAINTENANCE ◄──────────────────────────┘   (manual, leaves dispatch)
```

**Observer** decouples the displays: the car just announces "I'm at floor 7" and anything subscribed reacts.

```java
elevator.onFloorChanged(floor -> hallDisplay.show(floor));   // display reacts
elevator.onFloorChanged(floor -> chime.ding());              // chime reacts too
// The elevator doesn't know or care who's listening.
```

#### Q: Isn't SCAN "movement" also a Strategy?

Yes — you can make the per-car movement policy pluggable too (SCAN today, a different LOOK variant tomorrow) using the same Strategy idea. In practice the dispatch choice is the one interviewers care about most; mention that movement *could* be a second strategy to show you see the parallel.

---

## 6. Interview Cheat Sheet

> **"How does an elevator decide where to go?"**
> "Per car, use **SCAN/LOOK**: keep moving in the current direction serving all stops, then reverse — efficient and starvation-free. Maintain sorted up/down stop sets."

> **"With multiple elevators, which one answers a hall call?"**
> "A **Dispatcher (Strategy)** scores each elevator by distance + whether it's already heading that way + current load, and picks the minimum-cost car (nearest-car/collective control). Swap the strategy for energy-optimized or zoned dispatch."

> **"How do you model state?"**
> "A **State** machine per elevator (MOVING → STOPPED → DOORS_OPEN, plus MAINTENANCE) with a direction (UP/DOWN/IDLE); requests are **Command** objects; displays are **Observers**."

---

## 7. Final Takeaways

- **Two request types**: external hall calls (floor+direction) and internal car calls (target).
- **SCAN/LOOK** for per-car movement (serve in one direction, then reverse) — no starvation.
- **Dispatcher (Strategy)** scores elevators (distance + direction + load) for hall calls.
- **State** machine per elevator; **Command** requests; **Observer** displays; **Singleton** controller.

### Related notes

- [Parking Lot (OOD)](parking-lot-system-design.md) · [Vending Machine (OOD)](vending-machine-system-design.md) · [Snake & Ladder (OOD)](snake-and-ladder-system-design.md)
