# 🍔 Food Delivery (Swiggy/Zomato core)

Model the 3-sided marketplace: restaurant/menu, cart → order, and delivery-partner assignment.

## Design
- **Restaurant + Menu** — items with price/availability.
- **Cart** — line items with quantities; computes total; rejects unavailable items.
- **Order state machine** — only valid forward transitions are allowed:
```
PLACED ─▶ ACCEPTED ─▶ PREPARING ─▶ READY ─▶ OUT_FOR_DELIVERY ─▶ DELIVERED
   └────────┴──▶ CANCELLED
```
- **Partner assignment** — when the order hits `READY`, assign the first **free** delivery partner (swap for nearest/least-loaded).

## Design patterns
| Pattern | Where | Why |
| --- | --- | --- |
| State | `OrderState` + guarded transitions | Prevent illegal lifecycle jumps |

> Full design (hyperlocal geo-search, real-time dispatch, live tracking, ETA) is in `system-design/food-ordering-system-design.md`.

## Run
```bash
cd java && javac Main.java && java Main
cd cpp && g++ -std=c++17 main.cpp -o main && ./main
```
