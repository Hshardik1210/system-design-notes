# 👛 Digital Wallet (double-entry ledger)

Accounts, balances, and transfers recorded with **double-entry accounting**.

## Double-entry principle
Every money movement writes **two balanced entries**: a **DEBIT** on the sender and an equal **CREDIT** on the receiver, sharing a transaction id. Therefore:

```
Σ(credits) − Σ(debits) = 0   (money is conserved)
```

The demo asserts this invariant (`ledgerNet() == 0`). This is exactly why real payment systems use a ledger — it's auditable and self-checking.

## Correctness
- Amounts are stored in the smallest unit (**paise**) — never floats for money.
- Transfers are **atomic** and lock the two accounts in a **fixed order (by id)** so concurrent transfers on the same pair can't deadlock.
- Insufficient funds ⇒ the whole transfer is rejected (no partial entries).

## Design patterns
| Pattern | Where | Why |
| --- | --- | --- |
| Ledger / double-entry | `LedgerEntry` pairs | Auditable, conserved money |
| Ordered locking | transfer() | Deadlock avoidance |

> Idempotency keys + reconciliation + payouts are covered in `system-design/payment-system-system-design.md`.

## Run
```bash
cd java && javac Main.java && java Main
cd cpp && g++ -std=c++17 main.cpp -o main && ./main
```
