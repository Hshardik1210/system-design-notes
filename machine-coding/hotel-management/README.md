# 🏨 Hotel Management / Booking

Search available rooms for a date range and make non-overlapping reservations.

## Core problem: date-range availability
A room is booked for **half-open** intervals `[checkIn, checkOut)`. A new booking is allowed only if it doesn't overlap any existing one:

```
[s1, e1) and [s2, e2) overlap  ⇔  s1 < e2 && s2 < e1
```

Half-open intervals make **adjacent** stays valid: a checkout on day 13 and a new check-in on day 13 don't conflict.

## Design
- `DateRange` — validated interval with `overlaps()`.
- `Room` — type, nightly price, and its list of bookings; `isAvailable(range)` scans for overlap.
- `Hotel.search(type, range)` — free rooms of a type.
- `Hotel.book(...)` — re-checks availability before committing (guards against races); amount = nights × price.

> Dates are day-number ints to stay dependency-free; swap for `LocalDate`. At scale, overbooking is prevented with an **atomic conditional update** — see `system-design/hotel-management-system-design.md`.

## Run
```bash
cd java && javac Main.java && java Main
cd cpp && g++ -std=c++17 main.cpp -o main && ./main
```
