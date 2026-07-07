# 🎬 BookMyShow (Movie Ticket Booking)

Book seats for a show such that **no seat is ever double-booked**, even under concurrent requests.

## The core problem: concurrency
Two users clicking the same seat at the same time must not both succeed. Solution = **two-phase booking** (what real systems do):

1. **HOLD** — temporarily lock the chosen seats for a user; the hold **expires** after a few seconds if they don't pay.
2. **CONFIRM** — after payment, convert the user's held seats to **BOOKED**.

Unconfirmed holds auto-release (checked lazily on the next hold attempt), so abandoned carts free the seats.

### Why it's safe
All seat transitions for a show run **under that show's lock**, so the "check every seat is free → set them held" is one atomic critical section. A competing thread either runs fully before or fully after — never interleaved.

> At scale this lock becomes an **atomic conditional DB update** or a **Redis lock** per seat; see `system-design/bookmyshow-system-design.md`.

## Seat state machine
```
AVAILABLE ──hold──▶ HELD ──confirm──▶ BOOKED
    ▲                 │
    └──hold expires───┘
```

## Design patterns
| Pattern | Where | Why |
| --- | --- | --- |
| State | `SeatStatus` | Seat lifecycle |
| Two-phase locking | hold + confirm | Prevent double-booking |

## Run
```bash
cd java && javac Main.java && java Main
cd cpp && g++ -std=c++17 main.cpp -o main && ./main
```
