# 📅 Meeting Room Scheduler / Calendar

Book time intervals into rooms with conflict detection, and compute the minimum rooms needed.

## Design
- **Interval** — half-open `[start, end)`; adjacent meetings (`[9,10)` then `[10,12)`) don't conflict.
- **Room** — keeps bookings in a `TreeMap<start, end>` (Java) / `map<start,end>` (C++) sorted by start. To test a new interval, only the **neighbouring** bookings (the one just before and the one at/after `start`) can overlap — `O(log n)` per check.
- **Scheduler.book** — first-fit: place in the first room with no conflict.

## Bonus: minimum rooms needed (sweep-line)
Sort all start and end times separately, then sweep: every start before the next end needs a new room; each end frees one. The peak concurrency = minimum rooms.

```
[9,10),[9,11),[10,12),[11,12)  ⇒  2 rooms
```

## Design patterns
- Interval management + ordered map for `O(log n)` conflict detection.
- Two-pointer sweep-line for capacity planning.

## Run
```bash
cd java && javac Main.java && java Main
cd cpp && g++ -std=c++17 main.cpp -o main && ./main
```
