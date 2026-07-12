# Splitwise — System Design (Expense Sharing)

> **Core challenge:** track **who owes whom** across group expenses, keep a correct **balance ledger** (always nets to zero), and **minimize the number of settle-up transactions**. Part **money/ledger correctness**, part a neat **graph/greedy algorithm** (debt simplification) — a common OOD + algorithm hybrid.

> **How to read this doc:** each section has the dense interview summary first, then a **deep dive** (annotated Java, and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand.

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

### What problem are we even solving?

Take a **weekend trip with three friends — Alice, Bob, and Carol.** Over the weekend:

- Alice pays ₹900 for dinner (for all three).
- Bob pays ₹600 for the cab (for all three).
- Carol pays ₹300 for snacks (for all three).

At the end, nobody wants to remember "you paid this, I paid that." They just want the app to say **one clean sentence**: *"Bob, pay Alice ₹100"* — and everyone's square. That's the entire job of Splitwise:

1. **Record who paid and who should share** each expense.
2. **Keep a running tally** of who owes whom (the *balances*).
3. **When people want to settle up**, tell them the **fewest possible payments** that make everything zero.

So Splitwise is basically a **shared, automatic scoreboard for money between friends.** Everything else is "how do we keep that scoreboard correct and simple."

### Why "everything nets to zero" is the golden rule

Money isn't created or destroyed when friends split a bill — it just **moves between people**. So if you add up *everyone's* net position, it must equal **exactly 0**.

```
Alice is owed +200   (she paid more than her share)
Bob owes       -150  (he paid less than his share)
Carol owes     -50
                ----
sum =            0    ✅  always
```

If the numbers ever add up to anything other than 0, there's a **bug** — money leaked or got invented. This "sum must be 0" is called the **money-conservation invariant**, and it's the single most important correctness check in the whole system. It's the same principle accountants call **double-entry bookkeeping**: every debit has a matching credit.

#### Q: Why not just store "Alice paid ₹900" and figure it out later?

You *could* store only raw expenses and recompute balances on every read — and that's actually a valid (and correct) approach. But people open the app constantly to check "how much do I owe?", so we usually **also keep a pre-computed balance** that's ready to read instantly. The raw expense history stays as the **source of truth**; the balances are a fast **summary** derived from it (more in §6).

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

### Why a plain SQL database wins here

The Ad-Click design needed Kafka and stream processors because it faced *millions of events per second*. Splitwise is the **opposite** situation. Expenses trickle in — a few per person per day. There is **no firehose.** What we care about instead is **never getting the money wrong.**

That's exactly what a plain relational database (Postgres/MySQL) with **ACID transactions** is built for:

- **A**tomic — either the whole expense (rows + balance changes) is saved, or none of it. No half-updated balances.
- **C**onsistent — the "sum = 0" invariant holds after every change.
- **I**solated — two people adding expenses at the same time don't corrupt each other.
- **D**urable — once saved, it survives a crash.

> **Rule of thumb:** reach for Kafka/streams when the challenge is *volume*; reach for an ACID SQL database when the challenge is *correctness of money*. Splitwise is firmly the second kind.

#### Q: What do all those "services" actually do?

Each service has one job:

| Service | Job |
| --- | --- |
| **Expense Service** | validate the split, compute each person's share, update balances |
| **Balance Service** | answer "who owes whom right now" fast |
| **Settlement Service** | record when someone actually pays back |
| **Simplify Service** | compute the fewest payments to square everyone up |
| **Notification / Activity log** | tell members what happened, keep a history |

At small scale these can all live in **one app** — they're logical responsibilities, not necessarily separate servers.

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

### The four ways to split a bill

Picture the ₹900 dinner. There are four common ways the three friends might divide it:

| Split type | "How should we split the ₹900 dinner?" | Result |
| --- | --- | --- |
| **Equal** | "Just split it evenly." | 300 / 300 / 300 |
| **Exact** | "I only had a salad — I'll pay exactly 100." | e.g. 100 / 500 / 300 (must total 900) |
| **Percentage** | "Alice earns more, she takes 50%." | 450 / 225 / 225 (must total 100%) |
| **Shares** | "Bob ate twice as much → 2 shares vs 1 each." | 450 / 225 / 225 (ratio 2:1:1) |

The **one rule that never bends**: whatever method you pick, the per-person shares must **add back up to the exact total**. If they don't, reject the expense — money would leak.

### Modeling this in Java (the OOP core)

First, the basic objects — `User`, `Group`, `Expense`, and a `Split` (one person's portion of an expense):

```java
class User {
    long   userId;
    String name;
    String email;
}

class Group {
    long        groupId;
    String      name;
    List<User>  members;
}

// One person's slice of a specific expense (this is a row in expense_splits)
class Split {
    User          user;
    BigDecimal    shareAmount;   // how much THIS person is responsible for
}

class Expense {
    long          expenseId;
    Group         group;
    String        description;
    BigDecimal    amount;        // total of the bill
    User          paidBy;        // who fronted the cash
    SplitType     type;          // EQUAL / EXACT / PERCENT / SHARES
    List<Split>   splits;        // must sum to `amount`
}
```

> **Why `BigDecimal`, not `double`?** `double` can't represent `0.1` exactly, so money math drifts (`0.1 + 0.2 = 0.30000000000000004`). For anything financial, use `BigDecimal` (or store integer *paise/cents*). Never use `float`/`double` for money.

Now the **Strategy pattern** — one interface, one implementation per split type. This lets us add a new split type later without touching the others:

```java
enum SplitType { EQUAL, EXACT, PERCENT, SHARES }

// The Strategy: "given a total and some inputs, produce each person's share."
interface SplitStrategy {
    List<Split> computeSplits(BigDecimal total, List<User> participants, List<BigDecimal> inputs);
}
```

**Equal split** — divide evenly, then hand the leftover paise to the first person so it's exact:

```java
class EqualSplit implements SplitStrategy {
    public List<Split> computeSplits(BigDecimal total, List<User> people, List<BigDecimal> ignored) {
        int n = people.size();
        // round DOWN to 2 decimals so we never over-allocate
        BigDecimal each = total.divide(new BigDecimal(n), 2, RoundingMode.DOWN);

        List<Split> splits = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO;
        for (User u : people) {
            splits.add(new Split(u, each));
            allocated = allocated.add(each);
        }

        // leftover paise (e.g. 900/3 is clean, but 100/3 = 99.99 → 0.01 left over)
        BigDecimal remainder = total.subtract(allocated);
        if (remainder.signum() != 0) {
            splits.get(0).shareAmount = splits.get(0).shareAmount.add(remainder); // give it to one person
        }
        return splits;   // guaranteed to sum EXACTLY to total
    }
}
```

**Exact split** — the caller states each amount; we just validate they add up:

```java
class ExactSplit implements SplitStrategy {
    public List<Split> computeSplits(BigDecimal total, List<User> people, List<BigDecimal> amounts) {
        BigDecimal sum = amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(total) != 0) {
            throw new IllegalArgumentException("Exact splits must sum to the total"); // reject!
        }
        List<Split> splits = new ArrayList<>();
        for (int i = 0; i < people.size(); i++) splits.add(new Split(people.get(i), amounts.get(i)));
        return splits;
    }
}
```

**Percentage split** — percentages must total 100; convert to money, fix rounding:

```java
class PercentSplit implements SplitStrategy {
    public List<Split> computeSplits(BigDecimal total, List<User> people, List<BigDecimal> percents) {
        BigDecimal pctSum = percents.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (pctSum.compareTo(new BigDecimal("100")) != 0) {
            throw new IllegalArgumentException("Percentages must sum to 100");
        }
        List<Split> splits = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < people.size(); i++) {
            BigDecimal share = total.multiply(percents.get(i))
                                    .divide(new BigDecimal("100"), 2, RoundingMode.DOWN);
            splits.add(new Split(people.get(i), share));
            allocated = allocated.add(share);
        }
        splits.get(0).shareAmount = splits.get(0).shareAmount.add(total.subtract(allocated)); // leftover paise
        return splits;
    }
}
```

A **Factory** picks the right strategy from the enum (so callers never `new` a strategy directly):

```java
class SplitStrategyFactory {
    static SplitStrategy of(SplitType type) {
        return switch (type) {
            case EQUAL   -> new EqualSplit();
            case EXACT   -> new ExactSplit();
            case PERCENT -> new PercentSplit();
            case SHARES  -> new SharesSplit();   // weighted, same idea as percent
        };
    }
}
```

#### Q: Why the "give the leftover paise to one person" dance?

Because **some totals don't divide cleanly.** ₹100 ÷ 3 = ₹33.333... You can't pay a third of a paisa. If you round each share to 33.33, three people only cover ₹99.99 — a phantom ₹0.01 vanishes. Over millions of expenses those lost paise add up and break the "sum = 0" rule. The fix: round everyone down, then **deterministically** dump the tiny remainder onto one person. Total stays exact, and it's predictable (not random), so it's auditable.

#### Q: Is the payer's own share a debt they owe themselves?

**No.** In the ₹900 equal-split dinner, Alice paid and her own share is ₹300 — but she doesn't *owe herself*. Only the **other** participants create debts: Bob owes Alice 300, Carol owes Alice 300. When we update balances (§6), we skip the payer's own split.

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

### Balances are just IOUs between pairs

A balance is just an **IOU between two people**: *"Bob owes Alice ₹300."* The whole balance system is the collection of these IOUs.

Two things make it clean:

**1. One note per pair (not two).** We don't keep *both* "Bob owes Alice 300" *and* "Alice is owed 300 by Bob" — that's redundant and can disagree. We keep **one signed number per pair**, always ordered the same way (smaller user id first):

```
balance(Bob, Alice)  →  stored as pair (Alice, Bob) with a sign
   +300  means  "the first person (Alice) is owed 300 by the second (Bob)"
   -300  means  the reverse
```

Ordering the pair consistently (`min(id), max(id)`) means there's **exactly one row per friendship**, never two competing ones.

**2. Every change touches two sides equally (double-entry).** When Bob's debt to Alice goes up by 300, that's the *only* change — the money that left Bob's "owed" column is the same money that entered Alice's "owed to me" column. Nothing is created. This is why the grand total always stays 0.

### The balance math, step by step

```
Start: everyone at 0.

Alice pays 900, split equally (300 each):
  Bob   owes Alice += 300   → balance(Alice,Bob)   = +300
  Carol owes Alice += 300   → balance(Alice,Carol) = +300
  (Alice's own 300 share is skipped — no self-debt)

Bob pays 600, split equally (200 each):
  Alice owes Bob += 200     → balance(Alice,Bob)   = +300 - 200 = +100
  Carol owes Bob += 200     → balance(Bob,Carol)   = +200

Now: Alice is owed net +100+300 = +400? Let's total everyone:
  Alice: +100 (from Bob) +300 (from Carol) = +400
  Bob:   -100 (to Alice) +200 (from Carol) = +100
  Carol: -300 (to Alice) -200 (to Bob)     = -500
                                             ----
  sum =                                        0   ✅
```

### A `BalanceSheet` in Java

```java
// A directed pair key, always normalized so (Alice,Bob) and (Bob,Alice) map to ONE entry.
record PairKey(long low, long high) {
    static PairKey of(long a, long b) {
        return a < b ? new PairKey(a, b) : new PairKey(b, a);
    }
}

class BalanceSheet {

    // one signed amount per pair (+ = `low` is owed by `high`)
    private final Map<PairKey, BigDecimal> balances = new HashMap<>();

    // Core primitive: "debtor now owes creditor `amount` more." Double-entry in one place.
    void addDebt(long debtor, long creditor, BigDecimal amount) {
        PairKey key = PairKey.of(debtor, creditor);
        // if creditor is the "low" id, a debt from debtor(high) is POSITIVE; else NEGATIVE
        BigDecimal signed = (creditor == key.low()) ? amount : amount.negate();
        balances.merge(key, signed, BigDecimal::add);
    }

    // Apply a whole expense: each participant (except payer) owes the payer their share.
    void applyExpense(Expense e) {
        for (Split s : e.splits) {
            if (s.user.userId == e.paidBy.userId) continue;          // skip payer's own share
            addDebt(s.user.userId, e.paidBy.userId, s.shareAmount);  // participant owes payer
        }
    }

    // Settling up: a real payment REDUCES the debt.
    void settle(long fromUser, long toUser, BigDecimal amount) {
        addDebt(toUser, fromUser, amount);   // reverse direction cancels existing debt
    }

    // A user's overall position = sum of every pair they're part of.
    BigDecimal netFor(long userId) {
        BigDecimal net = BigDecimal.ZERO;
        for (var entry : balances.entrySet()) {
            PairKey k = entry.getKey();
            if (k.low()  == userId) net = net.add(entry.getValue());       // + means they're owed
            if (k.high() == userId) net = net.subtract(entry.getValue());  // flip sign for the other side
        }
        return net;   // + = they are owed money, - = they owe money
    }
}
```

#### Q: Where are balances actually *stored* — a table or computed on the fly?

**Both are valid; pick based on read speed.**

- **Derived (compute on read):** store only `expenses` + `expense_splits` + `settlements`, and recompute balances by summing them whenever someone asks. Always correct, zero risk of drift, but slower for big groups.
- **Denormalized (store the running total):** keep a `balances` table with one row per pair, updated inside the same transaction as the expense. Instant reads (`SELECT` one row), which matters because people check balances constantly.

Real systems usually **denormalize for speed** but keep the expense history as the **source of truth**, so balances can always be rebuilt if they ever drift (the same "raw log is truth" idea as Event Sourcing in the Ad-Click doc).

#### Q: Why "double-entry ledger" — isn't that overkill for a friends app?

It's not accounting jargon for its own sake — it's the **cheapest way to guarantee no money leaks.** By forcing every change to move the same amount from one side to the other (and never touch a single side alone), the invariant "everyone's balances sum to 0" holds *automatically*. If a bug ever makes the sum ≠ 0, you know instantly something's wrong. It's a built-in tripwire.

---

## 7. Debt Simplification (minimize transactions)

In a group, many pairwise debts collapse into fewer payments.

```
Bob owes Alice 300, Carol owes Bob 300
  → simplify: Carol owes Alice 300 (Bob drops out) → 1 payment instead of 2
```

**Algorithm (greedy min-cash-flow):** compute each person's net balance, then repeatedly match the biggest creditor with the biggest debtor (max-heaps) and settle the smaller amount until all are zero. (Full annotated Java in the deep dive below.)

**Worked example:**
```
Nets: Alice +300, Carol −300, Bob 0  → Carol pays Alice 300 (1 txn). Done.
```

- Produces a **minimal-ish** set of transactions; **optimal min-cash-flow is NP-hard** → greedy is the standard practical answer (≤ n−1 transactions).

### Why we bother simplifying at all

After a trip, the raw IOU notes can be a tangled mess:

```
Bob owes Alice 300
Carol owes Bob 300
Dave owes Carol 300
```

Taken literally, that's **3 separate payments**. But look closer — the ₹300 is just flowing *through* Bob and Carol to end up... it's a chain. The middle people **cancel out**. Everyone ends up square if just **Dave pays Alice 300** — **1 payment instead of 3.**

That's debt simplification: **fewest payments to make everyone's net balance zero.** Nobody wants to make five Venmo transfers when one will do.

### The trick — ignore *who owes whom*, only care about *net position*

The key insight that makes the algorithm simple: **it doesn't matter who the original debts were between.** All that matters is each person's **final net number**:

```
For each person:  net = (total others owe them) − (total they owe others)

  net > 0  →  a CREDITOR (money should flow TO them)
  net < 0  →  a DEBTOR   (money should flow FROM them)
  net = 0  →  already square, ignore them entirely
  (and all nets sum to 0)
```

Once you have the net numbers, forget the history. Just **route cash from debtors to creditors** in as few moves as possible.

### The greedy algorithm — "biggest owes biggest"

The strategy: repeatedly take the person who **owes the most** and the person who is **owed the most**, and make the biggest single payment possible between them. This kills off at least one person per step.

```java
record Payment(long from, long to, BigDecimal amount) {}

class DebtSimplifier {

    // netByUser: userId -> net balance (+ owed to them, - they owe). Sums to 0.
    List<Payment> simplify(Map<Long, BigDecimal> netByUser) {

        // Two heaps: biggest creditor on top, biggest debtor on top (by absolute amount).
        PriorityQueue<long[]> creditors = new PriorityQueue<>((a, b) -> Long.compare(b[1], a[1]));
        PriorityQueue<long[]> debtors   = new PriorityQueue<>((a, b) -> Long.compare(b[1], a[1]));

        netByUser.forEach((user, net) -> {
            long paise = net.movePointRight(2).longValueExact(); // work in integer paise = no rounding
            if (paise > 0) creditors.add(new long[]{ user,  paise });   // they are owed
            if (paise < 0) debtors.add(new long[]{ user, -paise });     // store magnitude they owe
        });

        List<Payment> payments = new ArrayList<>();
        while (!creditors.isEmpty() && !debtors.isEmpty()) {
            long[] c = creditors.poll();   // biggest creditor
            long[] d = debtors.poll();     // biggest debtor

            long amt = Math.min(c[1], d[1]);            // settle the smaller of the two
            payments.add(new Payment(d[0], c[0],
                    BigDecimal.valueOf(amt).movePointLeft(2)));   // "debtor pays creditor amt"

            long creditLeft = c[1] - amt;   // one of these two is now 0
            long debtLeft   = d[1] - amt;
            if (creditLeft > 0) creditors.add(new long[]{ c[0], creditLeft }); // re-push leftover
            if (debtLeft   > 0) debtors.add(new long[]{ d[0], debtLeft });
        }
        return payments;   // at most n-1 payments
    }
}
```

**Worked trace** on the chain above (nets: Alice +300, Dave −300, Bob 0, Carol 0):

```
creditors: [Alice +300]      debtors: [Dave 300]
step 1: biggest creditor Alice(+300), biggest debtor Dave(300)
        amt = min(300, 300) = 300  →  "Dave pays Alice 300"
        both hit 0 → both dropped
heaps empty → done.  Result: 1 payment.  ✅
```

#### Q: Why heaps (priority queues) instead of just looping a list?

Because at each step we need the **current largest** creditor and debtor, and those change after every payment. A heap gives us "give me the max" in `O(log n)` instead of re-scanning the whole list each time. It's the same reason the Ad-Click leaderboard-style problems reach for heaps.

#### Q: "Optimal is NP-hard" — so is greedy actually wrong?

Greedy isn't guaranteed to find the *theoretical minimum* number of payments in every case (that problem is NP-hard — related to the subset-sum/partition problem). But it always produces a **valid** settlement with **at most n−1 payments** for n people, which is more than good enough in practice. Interviewers just want you to (a) know it's the greedy heap approach and (b) acknowledge that true optimality is NP-hard, so greedy is the pragmatic answer.

#### Q: Does simplification lose the original "who owed whom" info?

The *simplified payments* are just a **suggestion for settling up** — a convenience view. The underlying `expenses`/`expense_splits` history is never thrown away, so you can always see the real story of who paid for what. Simplify is a projection on top, not a replacement.

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

### What can go wrong when two things happen at once

Imagine Alice and Bob **both add an expense to the same group at the exact same moment**, and both touch the `balance(Alice, Bob)` row. Without care:

```
Request 1 reads balance = 100
Request 2 reads balance = 100     (before request 1 saved)
Request 1 writes 100 + 300 = 400
Request 2 writes 100 + 200 = 300  ← overwrites! request 1's 300 vanished
```

That's a **lost update** — money silently disappears. The fix is to make each expense an **all-or-nothing transaction** that locks the rows it touches, so the second request waits for the first to finish.

### The ACID transaction that keeps money honest

```java
class ExpenseService {

    void addExpense(ExpenseRequest req) {
        // Idempotency: if we've already processed this key, do nothing (return the existing result).
        if (expenseRepo.existsByIdempotencyKey(req.idempotencyKey)) return;

        // 1. Compute shares OUTSIDE the transaction (pure math, no locks held).
        SplitStrategy strategy = SplitStrategyFactory.of(req.splitType);
        List<Split> splits = strategy.computeSplits(req.amount, req.participants, req.inputs);
        validateSumsToTotal(splits, req.amount);   // reject if money doesn't conserve

        // 2. One ACID transaction: history rows + balance updates commit together, or not at all.
        db.transaction(tx -> {
            long expenseId = tx.insertExpense(req);          // audit source of truth
            tx.insertSplits(expenseId, splits);

            for (Split s : splits) {
                if (s.user.userId == req.paidBy) continue;   // skip payer's own share

                // 3. Lock/update pair rows in a CONSISTENT ORDER to avoid deadlocks (see below)
                PairKey key = PairKey.of(s.user.userId, req.paidBy);
                tx.updateBalance(key, req.groupId, signedDelta(s, req.paidBy));
            }
        }); // COMMIT here — everything or nothing

        activityLog.record(req);
        notifier.notifyMembers(req.groupId);
    }
}
```

### The deadlock trap (and the fix)

A **deadlock** is a stand-off: two transactions each hold a lock the other needs, so both wait forever.

```
Txn 1 locks row (Alice,Bob), then wants (Bob,Carol)
Txn 2 locks row (Bob,Carol), then wants (Alice,Bob)
→ each waits for the other → frozen (deadlock)
```

The classic fix costs nothing: **always acquire locks in the same order** — e.g. sort the pair rows by their key before updating. If everyone grabs `(Alice,Bob)` before `(Bob,Carol)`, the cycle can't form. That's why the `PairKey` is normalized (`min,max`): it gives a stable, sortable order for locking.

#### Q: How does the idempotency key stop double-charging on a double-tap?

Mobile networks retry. If Bob taps "Add expense" and his phone resends the request (bad signal), you'd record the **same dinner twice** — doubling everyone's debt. The client generates **one unique `idempotency_key`** per user action and sends it with every retry. The server stores it with a `UNIQUE` constraint: the first request succeeds; any retry with the same key is recognized as "already done" and skipped. One tap = one expense, no matter how many times it's sent.

#### Q: How do you edit or delete an expense without corrupting balances?

**Never quietly rewrite history.** Instead, **reverse then reapply** (compensating entries, like the Command pattern's undo):

```
Edit an expense:
  1. Apply the INVERSE of the original splits  → balances back to as-if it never happened
  2. Apply the NEW splits                       → balances reflect the corrected expense
  (both inside one ACID transaction; original expense marked as superseded, not erased)

Delete an expense:
  1. Apply the inverse of its splits            → undo its balance effect
  2. Mark the expense `deleted = true`          → kept for audit, no longer counts
```

This keeps the full history (who changed what, when) *and* keeps balances correct. The invariant "sum = 0" holds after every reverse and every reapply.

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

### What each table is *for*

Think of the tables in two buckets — **"the truth"** (what actually happened, never edited in place) and **"the fast summary"** (derived, for quick reads):

| Table | Bucket | Plain meaning |
| --- | --- | --- |
| `users`, `groups`, `group_members` | reference | who exists, and who's in which group |
| `expenses` | **truth** | one row per bill: total, who paid, split type |
| `expense_splits` | **truth** | how that bill was divided — one row per person's share |
| `settlements` | **truth** | one row per real payback ("Bob paid Alice 300") |
| `balances` | **summary** | pre-computed "who owes whom" per pair, for instant reads |
| `activity_log` | history | human-readable feed of what happened |

If the `balances` summary ever looks wrong, you can **rebuild it from scratch** by replaying `expense_splits` + `settlements` — the truth tables are the safety net.

### Reading the `balances` table with an example

The `balances` row uses a **normalized pair** (`user_low < user_high`) and a **signed amount**, so one friendship = one row:

```sql
-- Suppose Alice=1, Bob=2. After Bob owes Alice 300:
user_low | user_high | group_id | amount
   1     |     2     |    42    | -300.00   -- amount is from low→high; -300 means user_low(Alice)
                                            --   does NOT owe; the high(Bob) owes 300 to low(Alice)
```

Convention (pick one and stay consistent): `amount` = "how much `user_low` owes `user_high`." So a **negative** number means `user_high` owes `user_low`. Reading it:

```sql
-- "How much do Alice(1) and Bob(2) owe each other?"
SELECT amount FROM balances
WHERE user_low = 1 AND user_high = 2 AND group_id = 42;
-- amount = -300 → Bob owes Alice 300

-- "What is Alice's net across a group?" (sum rows where she's low, minus rows where she's high)
SELECT
  COALESCE(SUM(CASE WHEN user_low  = 1 THEN -amount ELSE 0 END), 0) +  -- she's owed when amount<0
  COALESCE(SUM(CASE WHEN user_high = 1 THEN  amount ELSE 0 END), 0)    -- flip for the other side
  AS alice_net
FROM balances WHERE group_id = 42;
```

#### Q: Why the `NUMERIC(12,2)` type for money instead of a plain number?

`NUMERIC`/`DECIMAL` stores **exact decimal values** (12 total digits, 2 after the point) — no floating-point drift. `NUMERIC(12,2)` holds up to ₹9,999,999,999.99 with paise precision. Using `FLOAT`/`DOUBLE` here would slowly corrupt totals; for money, always use fixed-precision `NUMERIC` (or integer paise).

#### Q: Why keep `expense_splits` at all if we already store `balances`?

Because `balances` is only a **running total** — it can't tell you *why* Bob owes Alice 300, or let you edit one specific dinner. `expense_splits` is the **itemized receipt history**: it's what you reverse-and-reapply on edits, what you audit disputes against, and what you rebuild `balances` from if anything drifts. Summary answers "how much," history answers "why."

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
