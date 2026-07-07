# 🛗 Elevator System

Design a multi-elevator controller: handle hall calls (outside) and car calls (inside), and schedule the car efficiently.

## Scheduling: LOOK algorithm
A practical variant of **SCAN** (disk-scheduling): keep moving in the current direction, stopping at every requested floor, until nothing remains ahead — **then reverse**. (Unlike SCAN, LOOK doesn't run to the physical end if there are no requests there.)

Each elevator keeps two sorted sets of pending stops:
- `up` — floors above current to visit while heading UP (take the smallest).
- `down` — floors below current to visit while heading DOWN (take the largest).

## Dispatch: which car answers a hall call?
`DispatchStrategy` (**Strategy**) decouples "pick a car" from the cars themselves.
- `NearestCarDispatch` — choose the car with least distance, with a small bonus if it's already heading toward the caller.
- Swap in zoning / round-robin / load-based strategies without touching elevators.

## Design patterns
| Pattern | Where | Why |
| --- | --- | --- |
| State | `Direction` (UP/DOWN/IDLE) | Movement behaviour depends on direction |
| Strategy | `DispatchStrategy` | Pluggable car-assignment policy |

## Run
```bash
cd java && javac Main.java && java Main
cd cpp && g++ -std=c++17 main.cpp -o main && ./main
```
