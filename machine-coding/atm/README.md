# 🏧 ATM Machine

Model an ATM as a **state machine** with cash dispensing.

## States
```
IDLE ──insert card──▶ CARD_INSERTED ──correct PIN──▶ AUTHENTICATED ──withdraw/eject──▶ IDLE
                            └── wrong PIN ─▶ eject ─▶ IDLE
```
Each state (`IdleState`, `CardInsertedState`, `AuthenticatedState`) allows only its valid operations; everything else is rejected. This keeps the flow logic out of giant conditionals (**State pattern**).

## Cash dispensing
`CashDispenser` holds notes (denomination → count). Withdrawal must pass **two** checks:
1. account balance ≥ amount,
2. machine has enough cash **and** can form the exact amount (greedy note selection; `50` fails when only `500`/`100` notes exist).

## Design patterns
| Pattern | Where | Why |
| --- | --- | --- |
| State | `ATMState` + concrete states | Operation validity per state |
| Strategy (implicit) | greedy note selection | Swap for optimal note mix |

## Run
```bash
cd java && javac Main.java && java Main
cd cpp && g++ -std=c++17 main.cpp -o main && ./main
```
