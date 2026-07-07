# 🐍 Snake & Ladder

Classic board game — model the board, dice, players, snakes, and ladders.

## Key design insight
A **snake** and a **ladder** are the *same thing*: a **jump** from a `start` cell to an `end` cell.
- Ladder ⇒ `end > start` (go up).
- Snake ⇒ `end < start` (go down).

Modelling both as one `Jump` abstraction (keyed by start cell) removes duplicated snake/ladder logic — the board just asks "is there a jump starting here?".

## Rules modelled
- Round-robin turns (a queue).
- Roll die, advance; if you land on a jump's start, teleport to its end.
- Must land **exactly** on the last cell to win; overshoot ⇒ stay put.

## Design
- `Jump` — unified snake/ladder.
- `Die` — pluggable (faces, seed); swap for loaded/multi-dice.
- `Board` — size + `startCell → Jump` map + `resolve()`.
- `Game` — turn queue, roll loop, winner detection.

## Run
```bash
cd java && javac Main.java && java Main
cd cpp && g++ -std=c++17 main.cpp -o main && ./main
```
