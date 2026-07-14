# Elevator System — Low-Level Design (OOD)

> **Core challenge:** **Send the right car, then move it efficiently without starving anyone.** Two decisions drive everything below: *which* car answers a hall call (**dispatch**), and in *what order* one car serves its stops (**SCAN/LOOK**) — all while keeping each car's state transitions legal and thread-safe.

> A classic **OOD/LLD** problem: model a bank of elevators serving floor requests efficiently. Interviewers grade your **class model, the scheduling/dispatch strategy, and state handling**.

> **How to read this doc:** each section has the dense interview summary first, then a **deep dive** (annotated Java and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand.

---

## Contents

- [1. Problem Statement](#1-problem-statement)
- [2. Requirements](#2-requirements)
- [3. Core Entities](#3-core-entities)
- [4. Class Design](#4-class-design)
- [5. Scheduling / Dispatch](#5-scheduling--dispatch)
- [6. Extensibility](#6-extensibility)
- [7. Concurrency / Thread-safety](#7-concurrency--thread-safety)
- [8. Design Patterns (that can be used)](#8-design-patterns-that-can-be-used)
- [9. Interview Cheat Sheet](#9-interview-cheat-sheet)
- [10. Final Takeaways](#10-final-takeaways)

---

## 1. Problem Statement

Model the control system for a **bank of elevators** in a building. People press buttons; the software decides which car goes where and drives each car up and down safely.

Concretely, the system must:

- Accept **hall calls** (someone on a floor presses ▲/▼) and **car calls** (someone inside presses a destination).
- **Dispatch** the best car to each hall call.
- **Move** each car so it serves all its stops with minimal wasted travel — and **no rider waits forever**.
- Open/close doors and keep every car in a **legal state** (e.g. never move with the doors open).

> ⚠️ **The requirement that shapes everything:** riders must not **starve**. A naive "serve the nearest request first" scheduler can leave the top floor waiting indefinitely during a busy morning — avoiding that is the whole reason for SCAN/LOOK (§5).

---

## 2. Requirements

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

> 💡 **Jargon to nail early:** **hall call** = wall button (external, must be *dispatched* to a car); **car call** = in-car button (internal, the car is already chosen). Keeping these two straight is half the problem.

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

- **Dispatch** = *choosing the car* (a fleet-level decision, one per hall call). → §5 cost function.
- **SCAN/LOOK** = *how one chosen car orders its own stops* (a single-car decision). → §5 movement.

A hall call first goes through dispatch ("car #2, you take it"), then car #2 folds that floor into its own SCAN route.

---

## 3. Core Entities

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

## 4. Class Design

The full class model is spelled out in the annotated walkthrough below.

### The classes, annotated

Here is the design **spelled out** so you can see exactly what each field does.

First the small value types — an `enum` is just a fixed set of named constants:

```java
// Which way a car is travelling. IDLE = parked, no pending stops.
enum Direction { UP, DOWN, IDLE }

// The lifecycle status of one car (its "state machine" — see §8 State pattern).
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

    // How many stops I still owe (both directions). Used by the dispatch cost fn (§5).
    int pendingStops() { return upStops.size() + downStops.size(); }

    void step() { /* one simulation tick — see the SCAN version in §5 */ }
}
```

The dispatch **strategy** is an interface so the car-picking brain can be swapped (§8 Strategy pattern):

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
    // (cost function detailed in §5)
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

## 5. Scheduling / Dispatch

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

Here's `step()` implementing LOOK, using the two sorted sets from §4:

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

### A full `step()` walkthrough — from IDLE, tick by tick

That was one stop each way. Here's the whole loop from a **parked (IDLE)** car, showing the LOOK **reversal** at the top of the sweep. Car starts parked at **floor 5**; three requests arrive: **9**, **7**, then **2**.

```
addStop(9) → 9 > 5 → upStops {9},   direction IDLE→UP
addStop(7) → 7 > 5 → upStops {7,9}  (auto-sorted ascending)
addStop(2) → 2 < 5 → downStops {2}
```

Now each `step()` tick (LOOK = keep going up, serve stops, reverse only when nothing remains ahead):

```
tick  floor  dir    what happens                                upStops  downStops
 0      5    UP     start of sweep                               {7,9}    {2}
 1      6    UP     move up (not a stop)                         {7,9}    {2}
 2      7    UP     arrive 7 → doors open, remove 7              {9}      {2}
 3      7    UP     doors close (finish the stop)                {9}      {2}
 4      8    UP     move up                                      {9}      {2}
 5      9    UP     arrive 9 → doors open, remove 9              {}       {2}
 6      9   UP→DOWN nothing above (up{}), work below → REVERSE   {}       {2}
 7      8    DOWN   move down                                    {}       {2}
 …      …    DOWN   …                                            {}       {2}
11      2    DOWN   arrive 2 → doors open, remove 2              {}       {}
12      2    DOWN→IDLE nothing left either way → park            {}       {}
```

Key moments: **tick 6** is the LOOK reversal (ran out of up-stops, so flip to DOWN because `downStops` isn't empty); **tick 12** is going IDLE once both sets are empty. Notice 7 was served before 9 even though 9 was requested first — the sorted set + directional sweep handle ordering for free.

#### Q: Why not just always serve the nearest request (SSTF)?

**SSTF (shortest-seek-time-first)** always jumps to the closest pending stop. It sounds efficient, but it **starves** far-away requests: while the car lingers around floor 5, a steady trickle of nearby calls (4, 6, 5…) keeps being "nearest," so a lone call at floor 20 can wait forever. **SCAN/LOOK** avoids this by committing to a *direction* and serving everything on the way before reversing — so every floor is guaranteed service within one full sweep (a bounded worst-case wait). SSTF optimizes the *next move*; SCAN optimizes *fairness across the whole trip*. That's exactly why real elevators (and OS disk-arm schedulers) use SCAN, not SSTF.

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
- **State** is a separate machine (§8): `STOPPED → MOVING` when a stop is added, `MOVING → DOORS_OPEN` on arrival, `DOORS_OPEN → STOPPED/MOVING` after the door timer. `MAINTENANCE` is a manual override that takes the car out of dispatch entirely (the dispatcher skips cars not in service).

### Database & storage choices

The controller's live state — car positions, directions, the `upStops`/`downStops` sets from §4 — **must** stay in-memory. Dispatch and the SCAN loop run every tick; a real-time control loop that had to wait on a DB round-trip to decide "which floor next" would make every ride feel laggy, and a network blip has no business stalling a moving car. Persistence only enters for what happens *after* the fact:

- **Audit/analytics** (which car served which hall call, wait times, door-open durations, maintenance events) → an **append-only log** or a **time-series store**, written asynchronously off to the side, never read back into the control loop.

The controller keeps mutating its in-memory state on every tick; a background writer drains events to whatever store analytics needs. See [Databases — Deep Dive](../concepts/databases-deep-dive.md).

---

## 6. Extensibility

The dispatch brain is a `DispatchStrategy` (§4), so new fleet behavior is a **new class** — the elevators and the per-car SCAN loop never change. Three strategies cover the common asks:

| Strategy | Optimizes for | When to use |
| --- | --- | --- |
| **NearestCarStrategy** | shortest wait | default / rush hour — send the closest suitable car |
| **ZonedStrategy** | throughput in tall buildings | split floors into zones (1–10, 11–20); each car owns a zone so cars don't all pile onto the same floors |
| **EnergySavingStrategy** | least movement / power | off-peak / overnight — favor already-moving cars, keep idle cars parked |

`NearestCarStrategy` is the cost-function version from §5. The other two are drop-in siblings:

```java
// Assign by zone: e.g. floors 0–9 → zone 0, 10–19 → zone 1. Each car owns a home zone.
class ZonedStrategy implements DispatchStrategy {
    private final int zoneSize;
    ZonedStrategy(int zoneSize) { this.zoneSize = zoneSize; }

    public Elevator selectElevator(List<Elevator> cars, Request hallCall) {
        int zone = hallCall.floor / zoneSize;                  // which zone the call falls in
        return cars.stream()
            .filter(e -> e.homeZone == zone)                   // only cars that own this zone
            .min(Comparator.comparingInt(e -> Math.abs(e.currentFloor - hallCall.floor)))
            .orElseGet(() -> cars.get(0));                     // fallback if the zone's car is busy
    }
}
```

```java
// Overnight: don't wake a parked car if a moving one can absorb the call cheaply.
class EnergySavingStrategy implements DispatchStrategy {
    public Elevator selectElevator(List<Elevator> cars, Request hallCall) {
        return cars.stream()
            .min(Comparator
                .comparingInt((Elevator e) -> e.direction == Direction.IDLE ? 1 : 0)  // prefer already-moving
                .thenComparingInt(e -> Math.abs(e.currentFloor - hallCall.floor)))     // then closest
            .orElseThrow();
    }
}
```

Swap the brain at runtime — one line, no elevator code touched:

```java
system.dispatcher = new NearestCarStrategy();     // 9am rush: minimize wait
system.dispatcher = new ZonedStrategy(10);        // busy tower: split the load
system.dispatcher = new EnergySavingStrategy();   // 2am: minimize movement
```

> 💡 **dispatch** is the fleet-level "which car" decision — the *only* thing these strategies change. The per-car SCAN/LOOK movement (§5) is untouched, which is why swapping strategies is safe and cheap.

#### Q: What are "destination-dispatch" elevators — the ones where you pick your floor in the lobby?

A modern variant: instead of ▲/▼ buttons, you enter your **destination on a keypad in the lobby** and it tells you which car to board (often the in-car panel has no floor buttons at all). Because the system knows your destination *before* you board, it can **group riders heading to nearby floors into the same car**, cutting the number of stops each trip makes. In our model this means the hall call already carries a *target floor*, so dispatch can batch calls by destination rather than just by up/down direction. It shines in high-rises at peak; the trade-offs are a less familiar UX and the need to route people to the right car up front.

---

## 7. Concurrency / Thread-safety

Fifty people on twenty floors mash buttons at the same instant. Each press becomes a `Request` on some thread, but a car's `upStops`/`downStops` are **shared mutable state** — two threads adding stops at once can corrupt a `TreeSet` or drop a stop. Two mechanisms keep it safe:

**1. Guard each car's stop mutation.** `addStop` and the `step()` that mutates the same sets run under the car's **own lock**, so a car serializes its own updates without blocking the other cars:

```java
class Elevator {
    private final Object lock = new Object();

    void addStop(int floor) {
        synchronized (lock) { /* enqueue into upStops/downStops */ }   // one writer at a time, per car
    }
    void step() {
        synchronized (lock) { /* advance one tick; mutate the same sets */ }  // can't race with addStop
    }
}
```

**2. Funnel requests through one queue.** Rather than let arbitrary threads reach into cars, `ElevatorSystem` accepts requests into a **thread-safe queue** and a single control loop drains it — turning a storm of concurrent presses into an ordered stream:

```java
class ElevatorSystem {
    private final BlockingQueue<Request> inbox = new LinkedBlockingQueue<>();

    void submit(Request r) { inbox.add(r); }          // any thread may enqueue (non-blocking)

    void controlLoop() {                               // ONE thread owns dispatch + ticks
        while (true) {
            Request r = inbox.poll();
            if (r != null) {
                if (!r.internal)                        // hall call → dispatcher picks the car
                    dispatcher.selectElevator(elevators, r).addStop(r.floor);
                else                                    // car call → the car is already known
                    elevators.get(r.carId).addStop(r.floor);   // (car call carries its carId)
            }
            elevators.forEach(Elevator::step);          // advance the whole world one tick
        }
    }
}
```

> ⚠️ **Why a hall-call assignment is committed, not re-evaluated every tick:** once the dispatcher picks car B for floor 7, that stop is folded into B's route and **stays there**. If we re-ran dispatch every tick, a call could **flip-flop** between cars — the floor-7 light would flicker and no car would reliably arrive. Committing the assignment guarantees exactly one car owns each call. (Fancier systems do *bounded* re-optimization; see the reassignment note in §5.)

> 💡 **Contention is naturally low.** Unlike seat-booking, elevators rarely have true write conflicts on the *same* car — presses spread across floors and cars. A per-car lock plus one control loop is plenty; you don't need a global lock or a database in the hot path.

---

## 8. Design Patterns (that can be used)

Patterns sound abstract; here's each one as a thing in the tower, and the *smell* that tells you to reach for it:

| Pattern | Where / in the elevator | Why — the "smell" it fixes |
| --- | --- | --- |
| **Strategy** | The swappable dispatch brain (`DispatchStrategy`: nearest-car/energy/zoned), movement policy | Several *interchangeable algorithms* — swap scheduling without `if/else` sprawl |
| **State** | `ElevatorState` transitions (MOVING↔STOPPED↔DOORS_OPEN) | An object *behaves differently* depending on its status, and some transitions are illegal — guard legal ones |
| **Command** | Each button press as a `Request` object queued to elevators | You want to *queue, log, or replay* actions uniformly |
| **Observer** | Floor displays / chimes reacting to a car's state changes | Several UI things must *react to a change* without the car knowing about them |
| **Singleton** | The one `ElevatorSystem` controller | There must be *exactly one* coordinator |
| **Factory** | Building elevator/request types | Object *creation* varies (express car vs normal) and you want it in one place |
| **Mediator** | `ElevatorSystem` sitting between cars and requests | Many objects would otherwise talk *directly* to each other; route through one hub |

> **Interview lead:** Strategy (dispatch + SCAN movement), State (elevator lifecycle), Command (requests), Observer (displays), Singleton (system).

**Strategy** is the star — it's literally why §4 made dispatch an interface. Swapping the brain is a one-liner:

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

#### The State pattern done properly (not just an enum + guard)

The enum-plus-guard above works, but the **full State pattern** promotes each status into its **own class** — exactly like the vending machine's `IdleState`/`HasMoneyState`/`DispensingState`. The payoff: the "what's legal right now, and what happens next" logic lives in **one place per state**, so `Elevator` holds *no* `if (state == ...)` branching at all. Every state implements the same interface:

```java
interface ElevatorState {
    void addStop(Elevator e, int floor);   // fold a new stop into this car
    void step(Elevator e);                 // advance one clock tick
}
```

```java
// PARKED: no pending stops. A new stop wakes the car up and picks a direction.
class IdleState implements ElevatorState {
    public void addStop(Elevator e, int floor) {
        if (floor == e.currentFloor) return;                 // already here → ignore
        e.enqueue(floor);                                    // into upStops or downStops
        e.direction = (floor > e.currentFloor) ? Direction.UP : Direction.DOWN;
        e.setState(e.moving);                                // IDLE → MOVING
    }
    public void step(Elevator e) { /* parked: nothing to do until a request arrives */ }
}
```

```java
// MOVING: sweeping in one direction (SCAN/LOOK). Arriving at a stop opens the doors.
class MovingState implements ElevatorState {
    public void addStop(Elevator e, int floor) { e.enqueue(floor); }   // just fold into the route

    public void step(Elevator e) {
        Integer next = e.nextStopInDirection();              // ceiling/floor of the sorted set
        if (next == null) {                                  // LOOK: nothing ahead
            if (e.hasWorkBehind()) e.reverse();              // flip direction and keep serving
            else                   e.setState(e.idle);       // MOVING → IDLE (all done)
            return;
        }
        e.currentFloor += (e.direction == Direction.UP) ? 1 : -1;      // step one floor
        if (e.currentFloor == next) {                        // arrived at a stop
            e.removeStop(next);
            e.setState(e.doorsOpen);                         // MOVING → DOORS_OPEN
        }
    }
}
```

```java
// DOORS_OPEN: busy letting people in/out. Ignores movement; a stop for THIS floor is a no-op.
class DoorsOpenState implements ElevatorState {
    public void addStop(Elevator e, int floor) {
        if (floor != e.currentFloor) e.enqueue(floor);       // stops for other floors just queue
    }
    public void step(Elevator e) {                           // one tick later the door timer elapses
        if (e.hasAnyStops()) e.setState(e.moving);           // DOORS_OPEN → MOVING
        else                 e.setState(e.idle);             // DOORS_OPEN → IDLE
    }
}
```

The `Elevator` becomes a thin **context** that just delegates — no `switch` on state anywhere:

```java
class Elevator {
    final ElevatorState idle      = new IdleState();
    final ElevatorState moving    = new MovingState();
    final ElevatorState doorsOpen = new DoorsOpenState();
    private ElevatorState state = idle;                      // START parked

    int currentFloor = 0;
    Direction direction = Direction.IDLE;
    TreeSet<Integer> upStops   = new TreeSet<>();
    TreeSet<Integer> downStops = new TreeSet<>(Comparator.reverseOrder());

    // public actions delegate to the current state:
    void addStop(int floor) { state.addStop(this, floor); }
    void step()             { state.step(this); }

    // helpers the states call back into:
    void setState(ElevatorState s) { this.state = s; }
    void enqueue(int floor)        { if (floor > currentFloor) upStops.add(floor); else downStops.add(floor); }
    Integer nextStopInDirection()  { return direction == Direction.UP ? upStops.ceiling(currentFloor)
                                                                       : downStops.floor(currentFloor); }
    boolean hasWorkBehind()        { return direction == Direction.UP ? !downStops.isEmpty() : !upStops.isEmpty(); }
    boolean hasAnyStops()          { return !upStops.isEmpty() || !downStops.isEmpty(); }
    void reverse()                 { direction = (direction == Direction.UP) ? Direction.DOWN : Direction.UP; }
    void removeStop(int f)         { upStops.remove(f); downStops.remove(f); }
}
```

A narrated run — car idle at floor 5, calls for 7 then 2 arrive:

```
state=IDLE, floor=5
 addStop(7)   IdleState  → enqueue 7 (up), dir=UP, state=MOVING          up{7}  down{}
 addStop(2)   MovingState→ enqueue 2 (down)                              up{7}  down{2}
 step ×2      MovingState→ floor 6, then arrive 7 → removeStop, DOORS_OPEN
 step         DoorsOpen  → still work (2 pending) → state=MOVING
 step         MovingState→ nothing above (up{}), work behind → reverse, dir=DOWN
 step ×5      floor 6→…→2, arrive 2 → removeStop, state=DOORS_OPEN
 step         DoorsOpen  → no stops left → state=IDLE
```

> 💡 Same shape as the [vending machine](vending-machine-system-design.md): **data lives on the context, behavior lives in the state classes, and each state owns its own transitions.** The MAINTENANCE case slots in as one more state that removes the car from dispatch.

**Observer** decouples the displays: the car just announces "I'm at floor 7" and anything subscribed reacts.

```java
elevator.onFloorChanged(floor -> hallDisplay.show(floor));   // display reacts
elevator.onFloorChanged(floor -> chime.ding());              // chime reacts too
// The elevator doesn't know or care who's listening.
```

#### Q: Isn't SCAN "movement" also a Strategy?

Yes — you can make the per-car movement policy pluggable too (SCAN today, a different LOOK variant tomorrow) using the same Strategy idea. In practice the dispatch choice is the one interviewers care about most; mention that movement *could* be a second strategy to show you see the parallel.

---

## 9. Interview Cheat Sheet

> **"How does an elevator decide where to go?"**
> "Per car, use **SCAN/LOOK**: keep moving in the current direction serving all stops, then reverse — efficient and starvation-free. Maintain sorted up/down stop sets."

> **"With multiple elevators, which one answers a hall call?"**
> "A **Dispatcher (Strategy)** scores each elevator by distance + whether it's already heading that way + current load, and picks the minimum-cost car (nearest-car/collective control). Swap the strategy for energy-optimized or zoned dispatch."

> **"How do you model state?"**
> "A **State** machine per elevator (MOVING → STOPPED → DOORS_OPEN, plus MAINTENANCE) with a direction (UP/DOWN/IDLE); requests are **Command** objects; displays are **Observers**."

### Tricky scenarios (rapid-fire)

| Scenario | What to do |
| --- | --- |
| **Express elevator** (skips 2–9, serves 10–20) | Model as a car with a *restricted valid-stop set*; dispatch never assigns it hall calls outside its range — a special case of **ZonedStrategy** (§6). |
| **Fire / emergency mode** | A global override: cancel all pending stops, send every car to the ground floor (or a refuge floor), open doors, and drop out of dispatch. A top-priority state transition that trumps normal scheduling. |
| **Two cars tie on cost** | Break the tie **deterministically** — lowest `id`, or fewer `pendingStops()` — so a call never flip-flops between cars every tick (§7). |
| **Hall call vs car call asymmetry** | Hall calls go through the **dispatcher** (a car must be chosen); car calls skip it (you're already in a car). Same `Request` class, different entry path (§4). |
| **Capacity / weight limit** | A full car is treated as **unavailable** for new hall calls (dispatch skips it) and won't stop for more pickups until someone exits — add a `load`/`weight` term to the cost function. |

---

## 10. Final Takeaways

- **Two request types**: external hall calls (floor+direction) and internal car calls (target).
- **SCAN/LOOK** for per-car movement (serve in one direction, then reverse) — no starvation.
- **Dispatcher (Strategy)** scores elevators (distance + direction + load) for hall calls.
- **State** machine per elevator; **Command** requests; **Observer** displays; **Singleton** controller.

### Related notes

- [Parking Lot (OOD)](parking-lot-system-design.md) · [Vending Machine (OOD)](vending-machine-system-design.md) · [Snake & Ladder (OOD)](snake-and-ladder-system-design.md)
