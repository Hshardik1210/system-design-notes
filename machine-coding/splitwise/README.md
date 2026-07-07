# 💸 Splitwise

Track shared expenses, compute who owes whom, and minimise the number of settle-up transactions.

## Requirements
- Add an expense: **paid by** one person, **split** among participants.
- Split types: **EQUAL**, **EXACT** (fixed amounts), **PERCENT** (must sum to 100).
- Show pairwise balances.
- **Simplify debts** — reduce total transactions.

## Design
- **`SplitStrategy`** (Strategy) → `EqualSplit`, `ExactSplit`, `PercentSplit`. Adding a new split type = new class, no core changes.
- **`ExpenseManager`** keeps `balances[a][b] = amount a owes b`. Each expense adds each participant's share as a debt to the payer, **netting** against any reverse debt.
- **Debt simplification (greedy):**
  1. Collapse pairwise balances into a single **net balance** per person (`+` creditor, `−` debtor).
  2. Repeatedly settle the **largest debtor** against the **largest creditor** (two heaps) for `min(|debt|, credit)`.
  3. This yields at most `n − 1` transfers — near-minimal in practice.

## Design patterns
| Pattern | Where | Why |
| --- | --- | --- |
| Strategy | `SplitStrategy` | Pluggable split algorithms |

## Run
```bash
cd java && javac Main.java && java Main
cd cpp && g++ -std=c++17 main.cpp -o main && ./main
```
