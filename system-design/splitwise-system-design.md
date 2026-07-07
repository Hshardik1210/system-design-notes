# Splitwise — System Design (Expense Sharing)

> **Core challenge:** track **who owes whom** across group expenses, keep a correct **balance ledger**, and **minimize the number of settle-up transactions**. It's part **money/ledger correctness** and part a neat **graph/greedy algorithm** (debt simplification). A common OOD + algorithm hybrid.

---

## Contents

- [1. Mental Model](#1-mental-model)
- [2. Requirements](#2-requirements)
- [3. Splitting an Expense](#3-splitting-an-expense)
- [4. Balances (who owes whom)](#4-balances-who-owes-whom)
- [5. Debt Simplification (minimize transactions)](#5-debt-simplification-minimize-transactions)
- [6. Data Model (all tables)](#6-data-model-all-tables)
- [7. API Design](#7-api-design)
- [8. Design Patterns (that can be used)](#8-design-patterns-that-can-be-used)
- [9. Interview Cheat Sheet](#9-interview-cheat-sheet)
- [10. Final Takeaways](#10-final-takeaways)

---

## 1. Mental Model

```
Someone pays an expense → split among participants → update pairwise balances
Balance(A,B) = net amount A owes B    →  settle up reduces it to 0
```

Correctness (balances always net to zero) + a **greedy debt-minimization** algorithm are the interesting parts.

---

## 2. Requirements

**Functional**
- Add an **expense** paid by someone, **split** among members (equal / exact / percentage / shares).
- Track **balances** between users and within **groups**.
- **Settle up** (record a payment) → adjust balances.
- Show "you owe X / you are owed Y"; simplify group debts. Multi-currency (optional).

**Non-functional**
- **Correctness** (balances consistent, sum to zero); handle concurrent expense adds; scalable but not extreme volume.

---

## 3. Splitting an Expense

```
Expense: Alice pays 900 for dinner, split equally among Alice, Bob, Carol (300 each)
  → Bob owes Alice 300
  → Carol owes Alice 300
  (Alice's own 300 is her share, not a debt)
```

| Split type | How |
| --- | --- |
| **Equal** | amount / n participants |
| **Exact** | explicit amount per person (must sum to total) |
| **Percentage** | % per person (sum 100%) |
| **Shares** | weighted (e.g. 2:1:1) |

- **Strategy pattern** for split types.
- Validate splits **sum to the total** (money conservation) before recording.

---

## 4. Balances (who owes whom)

Store **pairwise net balances** (or derive from expense_splits).

```
balance(user_a, user_b) = net amount a owes b (negative = b owes a)
Adding an expense updates each debtor↔payer pair atomically.
Settle up (Bob pays Alice 300) → balance(Bob, Alice) -= 300
```

- Keep a **normalized directed balance** per user pair; every expense/settlement is a balanced update (like a **double-entry ledger** — the sum across all users is always 0).
- Group balance = aggregate of member pair balances.

---

## 5. Debt Simplification (minimize transactions)

In a group, many pairwise debts can be **collapsed** into fewer payments.

```
Bob owes Alice 300, Carol owes Bob 300
  → simplify: Carol owes Alice 300 (Bob drops out)   → 1 payment instead of 2
```

**Algorithm (greedy):**
```
1. Compute each person's NET balance = total_paid_for_others − total_owed
   (creditors: net > 0, debtors: net < 0; sum = 0)
2. Repeatedly match the biggest creditor with the biggest debtor,
   settle min(|debtor|, creditor), reduce both, drop whoever hits 0.
3. Continue until all nets are 0.
```

- Produces a **minimal-ish set of transactions** (optimal min-cash-flow is NP-hard; greedy is the practical answer).
- Use a **max-heap** for creditors and debtors.

---

## 6. Data Model (all tables)

```sql
CREATE TABLE users ( user_id BIGINT PRIMARY KEY, name TEXT, email VARCHAR(255) UNIQUE );

CREATE TABLE groups ( group_id BIGINT PRIMARY KEY, name TEXT, created_by BIGINT );
CREATE TABLE group_members ( group_id BIGINT, user_id BIGINT, PRIMARY KEY(group_id, user_id) );

CREATE TABLE expenses (
    expense_id BIGINT PRIMARY KEY, group_id BIGINT,
    description TEXT, amount NUMERIC(12,2), currency CHAR(3),
    paid_by BIGINT NOT NULL, split_type VARCHAR(12),   -- EQUAL/EXACT/PERCENT/SHARES
    created_by BIGINT, created_at TIMESTAMP
);
CREATE TABLE expense_splits (            -- how the expense is divided (must sum to amount)
    expense_id BIGINT, user_id BIGINT, share_amount NUMERIC(12,2),
    PRIMARY KEY (expense_id, user_id)
);

-- Pairwise net balances (denormalized for fast reads; or derive from splits)
CREATE TABLE balances (
    user_a BIGINT, user_b BIGINT, group_id BIGINT,
    amount NUMERIC(12,2),                -- a owes b (net); or store normalized a<b
    PRIMARY KEY (user_a, user_b, group_id)
);

CREATE TABLE settlements (               -- recorded payments
    settlement_id BIGINT PRIMARY KEY, group_id BIGINT,
    from_user BIGINT, to_user BIGINT, amount NUMERIC(12,2), created_at TIMESTAMP
);

CREATE TABLE activity_log ( id BIGINT PRIMARY KEY, group_id BIGINT, type VARCHAR(20), data JSONB, at TIMESTAMP );
```

> **Tables to consider:** users, groups, group_members, expenses, expense_splits, balances, settlements, activity_log. (Balances can be derived from splits+settlements or denormalized for read speed.)

---

## 7. API Design

```
POST /v1/groups { name, memberIds }
POST /v1/expenses { groupId, amount, paidBy, splitType, splits:[{userId, share}] }
GET  /v1/users/{id}/balances            → net you owe / are owed
GET  /v1/groups/{id}/balances           → group balances
GET  /v1/groups/{id}/simplify           → minimized settle-up transactions
POST /v1/settlements { groupId, fromUser, toUser, amount }
GET  /v1/groups/{id}/activity
```

---

## 8. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **Strategy** | Split types (equal/exact/percent/shares) | Swap split logic cleanly |
| **Double-Entry / Ledger** | Balanced balance updates (sum = 0) | Money correctness |
| **Command** | Expense/settlement as recorded operations | Audit, undo, activity log |
| **Observer / Pub-Sub** | Expense added → notify members, update balances | Decouple |
| **Repository** | Data access | Testable |
| **Memento / Event log** | Activity log for history/undo | Auditability |
| **Factory** | Create split strategy from type | Extensible |
| **Facade** | Expense service over split + balance + notify | Simple API |

---

## 9. Interview Cheat Sheet

> **"How do you track who owes whom?"**
> "Each expense records who paid and per-person shares (Strategy for split type, validated to sum to the total). Update **pairwise net balances** as balanced entries — like a double-entry ledger where all balances net to zero. A user's total = sum of their pair balances."

> **"How do you minimize settle-up payments?"**
> "Compute each person's net balance (paid − owed; creditors positive, debtors negative, sum zero). Greedily match the biggest creditor with the biggest debtor, settle the min of the two, and repeat with heaps until all are zero. Optimal min-cash-flow is NP-hard, so greedy is the practical answer."

> **"How do you keep balances correct under concurrency?"**
> "Balance updates are atomic/transactional; adding an expense updates all affected pairs in one transaction. Validate splits sum to the total; the invariant is that all balances net to zero."

---

## 10. Final Takeaways

- Expense → **split (Strategy: equal/exact/percent/shares, must sum to total)** → update **pairwise net balances** (double-entry, sum = 0).
- **Debt simplification** = greedy max-creditor ↔ max-debtor matching with heaps (min-cash-flow is NP-hard).
- Balances denormalized for fast reads (or derived from splits + settlements); every update atomic.
- Patterns: Strategy (splits), Ledger/Double-Entry, Command + activity log, Observer, Factory.

### Related notes

- [Payment System](payment-system-system-design.md) — double-entry ledger overlap
- [Parking Lot (OOD)](parking-lot-system-design.md) · [Idempotency](../concepts/idempotency.md)
