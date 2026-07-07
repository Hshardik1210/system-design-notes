# ☕ Coffee Vending Machine

Like the snacks vending machine, but drinks are made from **shared ingredients** (water, milk, coffee, sugar).

## Key difference: ingredient management
Each beverage has a **recipe** (ingredient → amount). Serving a drink must:
1. verify **every** ingredient has enough stock, then
2. **atomically** deduct all of them.

If any ingredient is short, the drink is refused and the **missing ingredient** is reported (so the machine can show "add milk").

## Concurrency
Multiple outlets may brew at once, so ingredient reads/writes are mutex-guarded (Java `synchronized` / C++ `std::mutex`). `consume()` does the check-and-deduct in one critical section so two brews can't both pass the check and over-draw stock.

## Design patterns
- Recipe-as-data (no per-drink subclasses needed).
- Thread-safe shared inventory.

## Run
```bash
cd java && javac Main.java && java Main
cd cpp && g++ -std=c++17 main.cpp -o main && ./main
```
