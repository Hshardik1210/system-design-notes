# 🥤 Vending Machine

The textbook **State pattern** problem. The machine reacts differently to the same action depending on its current state.

## Requirements
- Select a product, insert money incrementally, dispense product + change.
- Reject invalid actions (dispense before selecting, insert money before selecting, etc.).
- Track inventory; report out-of-stock.
- Make change (greedy coin selection).

## States
```
IDLE ──select──▶ ITEM_SELECTED/HAS_MONEY ──enough $ + dispense──▶ DISPENSING ──▶ IDLE
```
Each state is a class implementing `VendingState` with `selectProduct / insertMoney / dispense`. The **machine (context)** just forwards the call to the current state; the state performs the action and sets the next state. This removes the giant `if (state == ...)` branching.

## Design patterns
| Pattern | Where | Why |
| --- | --- | --- |
| State | `VendingState` + `IdleState`/`HasMoneyState`/`DispensingState` | Behaviour changes cleanly per state |
| Strategy | `ChangeStrategy` → `GreedyChange` | Swap change-making algorithm |

## Run
```bash
cd java && javac Main.java && java Main
cd cpp && g++ -std=c++17 main.cpp -o main && ./main
```
