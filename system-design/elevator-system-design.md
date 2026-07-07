# Elevator System — Low-Level Design (OOD)

> A classic **OOD/LLD** problem: model a bank of elevators serving floor requests efficiently. Interviewers grade your **class model, the scheduling/dispatch strategy, and state handling**.

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

---

## 3. Class Design

```java
enum Direction { UP, DOWN, IDLE }
enum ElevatorState { MOVING, STOPPED, DOORS_OPEN, MAINTENANCE }

class Request {                 // unify hall + car calls
    int floor;                  // source (hall) or target (car)
    Direction direction;        // for hall calls
    boolean internal;
}

class Elevator {
    int id;
    int currentFloor;
    Direction direction = IDLE;
    ElevatorState state = STOPPED;
    TreeSet<Integer> upStops;   // sorted stops going up
    TreeSet<Integer> downStops; // sorted stops going down

    void addStop(int floor) { /* into up/down set based on position/direction */ }
    void step() {               // one tick: move toward next stop, open doors if arrived
        // SCAN: keep going in `direction` serving stops, then reverse
    }
}

interface DispatchStrategy {
    Elevator selectElevator(List<Elevator> elevators, Request hallCall);
}
class NearestCarStrategy implements DispatchStrategy { /* min cost by distance+direction */ }

class ElevatorSystem {
    List<Elevator> elevators;
    DispatchStrategy dispatcher;

    void requestElevator(int floor, Direction dir) {          // external
        Elevator e = dispatcher.selectElevator(elevators, new Request(floor, dir));
        e.addStop(floor);
    }
    void selectFloor(int elevatorId, int target) {            // internal
        elevators.get(elevatorId).addStop(target);
    }
    void step() { elevators.forEach(Elevator::step); }         // simulation tick
}
```

---

## 4. Scheduling / Dispatch

The interesting algorithmic part.

- **Per-elevator movement: SCAN / LOOK ("elevator algorithm")** — keep moving in one direction serving all stops, then reverse. Efficient + starvation-free.
- **Hall-call dispatch (which elevator):** score each elevator by a cost function:

```
cost(elevator, hallCall) = f(distance to the call,
                             whether it's already heading that way,
                             current load / number of pending stops)
pick the minimum-cost elevator   # NearestCar / "collective control"
```

- Swap strategies (nearest-car, energy-optimized, zoned/express) via **Strategy**.

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
