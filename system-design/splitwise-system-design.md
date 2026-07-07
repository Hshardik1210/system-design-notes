# Splitwise — System Design (Expense Sharing)

> **Core challenge:** track **who owes whom** across group expenses, keep a correct **balance ledger** (always nets to zero), and **minimize the number of settle-up transactions**. Part **money/ledger correctness**, part a neat **graph/greedy algorithm** (debt simplification) — a common OOD + algorithm hybrid.

---

## Contents

- [1. Mental Model](#1-mental-model)
- [2. Requirements](#2-requirements)
- [3. Capacity Estimation](#3-capacity-estimation)
- [4. Architecture](#4-architecture)
- [5. Splitting an Expense](#5-splitting-an-expense)
- [6. Balances (who owes whom)](#6-balances-who-owes-whom)
- [7. Debt Simplification (minimize transactions)](#7-debt-simplification-minimize-transactions)
- [8. Concurrency & Correctness](#8-concurrency--correctness)
- [9. Data Model (all tables)](#9-data-model-all-tables)
- [10. API Design](#10-api-design)
- [11. Sequences](#11-sequences)
- [12. Edge Cases](#12-edge-cases)
- [13. Design Patterns (that can be used)](#13-design-patterns-that-can-be-used)
- [14. Interview Cheat Sheet](#14-interview-cheat-sheet)
- [15. Final Takeaways](#15-final-takeaways)

---

## 1. Mental Model

```
Someone pays an expense → split among participants → update pairwise balances
Balance(A,B) = net amount A owes B    →  settle up reduces it to 0
Invariant: the sum of everyone's net balance is always 0 (money is conserved)
```

Correctness (balances always net to zero) + a **greedy debt-minimization** algorithm are the interesting parts.

---

## 2. Requirements

**Functional**
- Add an **expense** paid by someone, **split** among members (equal / exact / percentage / shares).
- Track **balances** between users and within **groups**.
- **Settle up** (record a payment) → adjust balances.
- Show "you owe X / you are owed Y"; **simplify group debts**; edit/delete expense; multi-currency (optional).

**Non-functional**
- **Correctness** (balances consistent, sum to zero, no rounding loss); handle **concurrent** expense adds; scalable but not extreme volume; auditable.

---

## 3. Capacity Estimation

```
Users ~ 100M's · groups ~ few per user · expenses ~ modest write rate (not a firehose)
Reads (view balances) > writes; balances denormalized for O(1) reads
Storage: expenses + splits + settlements grow over time → partition/archive old groups
```

> Not a high-throughput problem — the challenge is **money correctness** (balanced updates, no rounding drift) and the **debt-simplification algorithm**, not raw scale.

---

## 4. Architecture

```
Client → API Gateway
  ├── Expense Service   → validate split, compute per-person shares, update balances (RDBMS, ACID)
  ├── Balance Service   → pairwise net balances (denormalized) + "you owe / owed"
  ├── Settlement Service→ record payments, adjust balances
  ├── Simplify Service  → greedy min-cash-flow over group net balances
  └── Notification / Activity log
```

- **RDBMS with ACID** for expenses/splits/balances (money → strong consistency). Not much to shard.

---

## 5. Splitting an Expense

```
Expense: Alice pays 900 for dinner, split equally among Alice, Bob, Carol (300 each)
  → Bob owes Alice 300 ; Carol owes Alice 300   (Alice's own 300 is her share, not a debt)
```

| Split type | How |
| --- | --- |
| **Equal** | amount / n participants |
| **Exact** | explicit amount per person (must sum to total) |
| **Percentage** | % per person (sum = 100%) |
| **Shares** | weighted (e.g. 2:1:1) |

- **Strategy pattern** for split types.
- **Validate splits sum to the total** (money conservation) before recording.
- **Rounding:** equal split of 100 among 3 = 33.33 each = 99.99 → assign the leftover cent to one payer so the sum is exact (never lose/create money).

---

## 6. Balances (who owes whom)

Store **pairwise net balances** (denormalized for fast reads) — or derive from `expense_splits`.

```
balance(A, B) = net amount A owes B   (negative = B owes A)
Adding an expense → for each participant p (≠ payer): balance(p, payer) += p's share
Settle up (Bob pays Alice 300) → balance(Bob, Alice) -= 300
```

- Store a **normalized directed balance per user pair** (e.g. always key by `min(a,b), max(a,b)` with a sign) so there's one row per pair.
- Every expense/settlement is a **balanced update** — like a **double-entry ledger**; the sum across all users is always 0.
- **A user's total** = sum of their pair balances; **group balance** = aggregate of member pair balances.
- Keep the **`expenses`/`expense_splits` history** as the audit source; balances are a fast-read projection.

---

## 7. Debt Simplification (minimize transactions)

In a group, many pairwise debts collapse into fewer payments.

```
Bob owes Alice 300, Carol owes Bob 300
  → simplify: Carol owes Alice 300 (Bob drops out) → 1 payment instead of 2
```

**Algorithm (greedy min-cash-flow):**
```
1. Compute each person's NET balance = total_paid_for_others − total_they_owe
   (creditors: net > 0, debtors: net < 0;  Σ = 0)
2. Push creditors into a MAX-heap, debtors into a MAX-heap (by abs amount)
3. Repeat:
     take biggest creditor C and biggest debtor D
     amt = min(C.net, |D.net|)
     record "D pays C amt"; C.net -= amt; D.net += amt
     drop whoever hit 0; re-push the other
   until both heaps empty
```

**Worked example:**
```
Nets: Alice +300, Carol −300, Bob 0  → Carol pays Alice 300 (1 txn). Done.
```

- Produces a **minimal-ish** set of transactions; **optimal min-cash-flow is NP-hard** → greedy is the standard practical answer (≤ n−1 transactions).

---

## 8. Concurrency & Correctness

| Concern | Handling |
| --- | --- |
| **Concurrent expense adds** | Update all affected pair balances in **one ACID transaction** |
| **Deadlocks on pair rows** | Lock/update pairs in a **consistent order** (sorted by pair key) |
| **Duplicate expense (retry)** | **Idempotency key** on expense creation |
| **Money conservation** | Validate splits sum to total; balanced updates → Σ balances = 0 (invariant check) |
| **Rounding** | Distribute leftover cents deterministically so totals are exact |
| **Edit/delete expense** | Reverse the original split's balance effect (compensating entries), then apply the new one — never mutate history silently |

---

## 9. Data Model (all tables)

```sql
CREATE TABLE users ( user_id BIGINT PRIMARY KEY, name TEXT, email VARCHAR(255) UNIQUE );
CREATE TABLE groups ( group_id BIGINT PRIMARY KEY, name TEXT, created_by BIGINT );
CREATE TABLE group_members ( group_id BIGINT, user_id BIGINT, PRIMARY KEY(group_id, user_id) );

CREATE TABLE expenses (
    expense_id BIGINT PRIMARY KEY, idempotency_key VARCHAR(255) UNIQUE,
    group_id BIGINT, description TEXT, amount NUMERIC(12,2), currency CHAR(3),
    paid_by BIGINT NOT NULL, split_type VARCHAR(12),   -- EQUAL/EXACT/PERCENT/SHARES
    created_by BIGINT, created_at TIMESTAMP, deleted BOOLEAN DEFAULT FALSE
);
CREATE TABLE expense_splits (            -- how the expense is divided (must sum to amount) — audit source
    expense_id BIGINT, user_id BIGINT, share_amount NUMERIC(12,2),
    PRIMARY KEY (expense_id, user_id)
);

-- Pairwise net balances (denormalized for fast reads; derivable from splits+settlements)
CREATE TABLE balances (
    user_low BIGINT, user_high BIGINT, group_id BIGINT,   -- normalized pair (low<high)
    amount NUMERIC(12,2),                                 -- + = user_low owes user_high; − = reverse
    PRIMARY KEY (user_low, user_high, group_id)
);

CREATE TABLE settlements (               -- recorded payments
    settlement_id BIGINT PRIMARY KEY, group_id BIGINT,
    from_user BIGINT, to_user BIGINT, amount NUMERIC(12,2), created_at TIMESTAMP
);
CREATE TABLE activity_log ( id BIGINT PRIMARY KEY, group_id BIGINT, type VARCHAR(20), data JSONB, at TIMESTAMP );
```

> **Tables to consider:** users, groups, group_members, expenses, expense_splits, balances (denormalized pairs), settlements, activity_log. Balances derivable from splits+settlements or denormalized for read speed.

---

## 10. API Design

```
POST /v1/groups { name, memberIds }
POST /v1/expenses (Idempotency-Key) { groupId, amount, paidBy, splitType, splits:[{userId, share}] }
PUT/DELETE /v1/expenses/{id}            # edit/delete → reverse + reapply
GET  /v1/users/{id}/balances            → net you owe / are owed (across groups)
GET  /v1/groups/{id}/balances           → group pair balances
GET  /v1/groups/{id}/simplify           → minimized settle-up transactions
POST /v1/settlements { groupId, fromUser, toUser, amount }
GET  /v1/groups/{id}/activity
```

---

## 11. Sequences

### Add expense

```
User → ExpenseSvc (Idempotency-Key):
  validate split sums to amount (Strategy per split type; fix rounding)
  BEGIN TX
    insert expense + expense_splits (audit)
    for each participant p ≠ payer (in sorted pair order):
        balances[pair(p, payer)] += p.share    (balanced update)
  COMMIT
  → activity log + notify members
```

### Simplify

```
GET /simplify → compute net balances → greedy max-creditor/max-debtor matching (heaps) → list of payments
```

---

## 12. Edge Cases

| Case | Handling |
| --- | --- |
| Split doesn't sum to total | Reject (validate before recording) |
| Rounding (33.33×3) | Deterministically assign leftover cents; totals exact |
| Duplicate expense tap | Idempotency key → one expense |
| Edit/delete expense | Reverse original balance effect (compensating), then apply new |
| Multi-currency | Store per-expense currency; convert on display (FX rate at time); balances per currency or a base currency |
| Concurrent adds | ACID txn; consistent lock order on pair rows |
| Non-group (1:1) expense | Same pair-balance model without a group |

---

## 13. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Strategy** | Split types (equal/exact/percent/shares) | Swap split logic cleanly |
| **Double-Entry / Ledger** | Balanced balance updates (Σ = 0) | Money correctness |
| **Command** | Expense/settlement as recorded ops (with reverse) | Audit, edit/delete via compensation |
| **Observer / Pub-Sub** | Expense added → notify members, update balances | Decouple |
| **Repository** | Data access | Testable |
| **Memento / Event log** | Activity log for history | Auditability |
| **Factory** | Create split strategy from type | Extensible |
| **Facade** | Expense service over split + balance + notify | Simple API |
| **Greedy algorithm (heaps)** | Debt simplification | Minimize transactions |

---

## 14. Interview Cheat Sheet

> **"How do you track who owes whom?"**
> "Each expense records who paid and per-person shares (**Strategy** for the split type, validated to sum to the total, with deterministic rounding). Update **pairwise net balances** as balanced entries — a **double-entry ledger** where all balances net to zero. A user's total = sum of their pair balances."

> **"How do you minimize settle-up payments?"**
> "Compute each person's net balance (paid − owed; creditors +, debtors −, Σ = 0). **Greedily** match the biggest creditor with the biggest debtor (max-heaps), settle the min of the two, repeat until all are zero — ≤ n−1 transactions. Optimal min-cash-flow is NP-hard, so greedy is the practical answer."

> **"Correctness under concurrency?"**
> "Update all affected pair balances in **one ACID transaction**, lock pairs in a consistent order to avoid deadlocks, use an **idempotency key** on expense creation, and validate splits sum to the total (invariant: Σ balances = 0). Edits/deletes reverse then reapply."

---

## 15. Final Takeaways

- Expense → **split (Strategy; must sum to total, deterministic rounding)** → update **pairwise net balances** (double-entry, Σ = 0).
- **Debt simplification** = greedy max-creditor ↔ max-debtor matching with heaps (min-cash-flow is NP-hard) → ≤ n−1 payments.
- Balances **denormalized for fast reads** (derivable from splits+settlements); every update **atomic** with consistent lock order + idempotency.
- **Edit/delete** = reverse (compensate) + reapply; keep the expense/split history as audit.
- Patterns: Strategy (splits), Ledger/Double-Entry, Command (+ reverse), Observer, Factory, Greedy.

### Related notes

- [Payment System](payment-system-system-design.md) — double-entry ledger overlap
- [Parking Lot (OOD)](parking-lot-system-design.md) · [Idempotency](../concepts/idempotency.md)
