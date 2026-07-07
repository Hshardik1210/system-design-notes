# ✈️ Airline / Flight Booking

Search flights by route/date and book seats by **fare class**.

## Design
- **Flight** — route (`from`, `to`, `day`) + a **seat map partitioned by fare class** (`ECONOMY / BUSINESS / FIRST`), each class with its own price.
- **Search** — flights on a route/date that still have a free seat in the requested class.
- **Book** — takes the first free seat in a class; **mutex-guarded** so two bookings can't grab the same seat.

Seat numbers are prefixed by class (`F1`, `B2`, `E4`).

## Design patterns
- Inventory + filtered search.
- Guarded seat allocation (same concurrency idea as BookMyShow, simplified).

## Run
```bash
cd java && javac Main.java && java Main
cd cpp && g++ -std=c++17 main.cpp -o main && ./main
```
