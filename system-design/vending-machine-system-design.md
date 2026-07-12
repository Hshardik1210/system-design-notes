# Vending Machine — Low-Level Design (OOD)

> A classic **OOD/LLD** problem, and the textbook example of the **State pattern**: a machine that takes money, lets you select a product, dispenses it, and returns change. Interviewers grade your **state machine and class model**.

> **How to read this doc:** each section has the dense interview summary first, then a **deep dive** (annotated Java and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand.

---

## Contents

- [1. Requirements](#1-requirements)
- [2. The State Machine (the key)](#2-the-state-machine-the-key)
- [3. Class Design](#3-class-design)
- [4. Design Patterns (that can be used)](#4-design-patterns-that-can-be-used)
- [5. Interview Cheat Sheet](#5-interview-cheat-sheet)
- [6. Final Takeaways](#6-final-takeaways)

---

## 1. Requirements

**Functional**
- Hold **products** in slots with prices + inventory counts.
- Accept **money** (coins/notes/card), track balance.
- **Select** a product; dispense if enough money + in stock; **return change**.
- **Cancel** → refund inserted money. Admin: restock, collect cash.

**Non-functional**
- Correct money handling (never over/under-dispense change), extensible, clear state transitions.

> **Clarify:** exact change only, or make change? Card payments? Multiple items per transaction?

### What are we actually building?

A snack machine: you feed in a ₹10 coin and a ₹20 note, punch **A4** for a Coke (₹25), and it drops the can plus your ₹5 change. Boring to use — surprisingly fiddly to *model correctly in code*, which is exactly why interviewers love it.

Break the machine into the **nouns** (things it has) and the **verbs** (things you do to it):

- **Nouns (data):** the *products* in each slot (name, price, how many left), the *money* you've put in so far (your balance), and the *cash box* of coins/notes it can hand back as change.
- **Verbs (actions):** *insert money*, *select a product*, *dispense*, *cancel/refund*. Admin also does *restock* and *collect cash*.

The tricky part isn't the verbs — it's that **the same verb behaves differently depending on the machine's situation.** Pressing "A4" when you've paid nothing should scold you ("insert money first"); pressing "A4" when you've paid enough should drop a can. Same button, different outcome. That "it depends on the current situation" is the heart of the design (see §2).

#### Q: Why is this a "hard" interview problem? It's just a vending machine.

Because the naive version turns into a swamp of `if/else`. Every action has to first ask "wait, what situation am I in right now — has the user paid? is something selected? am I mid-dispense?" and branch accordingly. Do that for 4 actions × 4 situations and you get a tangle that's easy to get wrong (e.g. letting someone select twice, or refunding money that was already spent). The clean solution is to make the *situation itself* an object — the **State pattern** (§2).

#### Q: "Correct money handling" — what could go wrong?

Two classic bugs:

- **Bad change:** user pays ₹30 for a ₹25 item, machine owes ₹5 — but only has ₹2 coins in the box. It must **not** promise change it can't physically give. So we track a **cash inventory** and refuse (or ask for exact money) when change is impossible.
- **Losing the balance:** if the machine forgets how much you inserted (or lets a second person's coins mix in mid-transaction), it over- or under-charges. So exactly **one** transaction's balance is tracked at a time, and cancelling must refund the *exact* inserted amount.

---

## 2. The State Machine (the key)

The vending machine is a **finite state machine** — this is why it's the canonical **State pattern** example.

```
IDLE ──insertMoney──► HAS_MONEY ──selectProduct──► DISPENSING ──► (return change) ──► IDLE
  ▲                       │  cancel → refund                          │
  └───────────────────────┴──────────────────────────────────────────┘
                    (out of stock / insufficient money → stay / error)
```

| State | Allowed actions |
| --- | --- |
| `IDLE` | insert money (else "insert money first") |
| `HAS_MONEY` | insert more, select product, cancel (refund) |
| `DISPENSING` | dispense product + change → back to IDLE |
| `OUT_OF_STOCK` | reject selection |

> Each state **only allows valid actions** — e.g. you can't select a product in `IDLE`. That guard logic living in state objects is the whole point of the pattern.

### The states and how they behave

The machine is always in exactly one of a few **states**, and its buttons behave differently depending on the state it's in right now:

- **IDLE** — waiting for money. (Pressing a product button here just flashes *"insert money first."*)
- **HAS_MONEY** — money is in. Add more, pick something, or cancel for a refund.
- **DISPENSING** — busy dropping your snack and counting your change. A brief, busy moment; it ignores buttons.
- **OUT_OF_STOCK** — that slot's empty, pick something else.

The only way to move between states is a specific action; each state defines which actions are legal:

```
[IDLE]  --insert ₹30-->  [HAS_MONEY]  --press A4 (Coke ₹25)-->  [DISPENSING]
   ^                          |                                      |
   |                          | (press Cancel → refund ₹30)          | drop can + ₹5 change
   +--------------------------+--------------------------------------+
                    back to [IDLE], ready for the next person
```

#### Q: What's a "finite state machine (FSM)"?

Just a fancy name for "a thing that is always in exactly **one** of a fixed list of states, and only certain moves take it from one state to another." *Finite* = the list of states is small and known ahead of time (here: 4). Traffic lights (Red → Green → Yellow → Red) and microwaves are FSMs too. The vending machine is the canonical coding example.

#### Q: What is the "State pattern," in one sentence?

**Make each state its own class**, and let the machine hand off ("delegate") every action to whichever state object it's currently holding. Instead of one giant method full of `if (state == IDLE) ... else if (state == HAS_MONEY) ...`, you have an `IdleState` object and a `HasMoneyState` object, and each one *only* knows how to behave in that situation. The machine just forwards each button press to its current state object (see the code in §3).

#### Q: What happens on an "invalid transition," like selecting a product in IDLE?

The current state simply **refuses** it. `IdleState.selectProduct(...)` throws *"insert money first"* and the state does **not** change — you stay IDLE. That's the beauty: each state is a bouncer that only lets legal moves through, so illegal moves can't quietly corrupt the machine. No move is "handled everywhere"; each move is only truly implemented in the states where it makes sense.

#### Q: Why is DISPENSING its own state instead of just doing it inside "select"?

Because dispensing is a real, momentary situation where the machine should **ignore new input** (you shouldn't be able to insert more money or select again while a can is dropping). Modeling it as a distinct state makes "I'm busy, buttons do nothing right now" explicit and safe, instead of hoping no one presses anything during the split second of work.

---

## 3. Class Design

The full class model is built up in the annotated walkthrough below.

### The class model, piece by piece

Here's the design spelled out with heavy annotations, building it up from the small pieces (money, product, inventory) to the big machine and its states.

#### Piece 1 — Money: `Coin` / `Note` as enums

Real machines only accept specific denominations, so model them as **enums** (a fixed list of allowed values) rather than random integers. Each carries its rupee value:

```java
// The exact coins the machine accepts. An enum = a fixed, known set of choices.
enum Coin {
    ONE(1), TWO(2), FIVE(5), TEN(10);

    final int value;               // rupee value of this coin
    Coin(int value) { this.value = value; }
}

// The exact notes the machine accepts.
enum Note {
    TEN(10), TWENTY(20), FIFTY(50), HUNDRED(100);

    final int value;
    Note(int value) { this.value = value; }
}
```

> Why enums? You can never insert a "₹7 coin" or a "₹3 note" — the type system forbids it. This is the real-world "the slot only takes these shapes" constraint, encoded in code.

#### Piece 2 — `Product`: what's in a slot

```java
class Product {
    String code;    // the button label, e.g. "A4"
    String name;    // "Coke"
    int    price;   // 25  (in rupees)
    int    stock;   // how many cans are left in this slot
}
```

#### Piece 3 — `Inventory`: the machine's cash box (for making change)

The machine can only give back change it physically holds. `Inventory` tracks **how many of each coin** are in the cash box, so we never promise change we can't produce.

```java
class Inventory {
    // how many of each coin the machine currently holds
    private final Map<Coin, Integer> coins = new EnumMap<>(Coin.class);

    void add(Coin c, int qty)   { coins.merge(c, qty, Integer::sum); }
    void remove(Coin c, int qty){ coins.merge(c, -qty, Integer::sum); }
    int  countOf(Coin c)        { return coins.getOrDefault(c, 0); }
}
```

#### Piece 4 — The `State` interface and the four states

This is the pattern itself. Every state implements the **same four actions**, but each state only does something meaningful for the actions that are *legal* in that state; the rest throw.

```java
// Every state must be able to (attempt to) handle every action.
// It's up to each state whether the action is legal or an error.
interface State {
    void insertMoney(VendingMachine m, int amount);
    void selectProduct(VendingMachine m, String code);
    void dispense(VendingMachine m);
    void cancel(VendingMachine m);
}
```

```java
// STATE 1: waiting for money. Only inserting money is legal.
class IdleState implements State {
    public void insertMoney(VendingMachine m, int amt) {
        m.addBalance(amt);          // remember how much has been put in
        m.setState(m.hasMoney);     // state changes: IDLE → HAS_MONEY
    }
    // everything else is illegal here — refuse it, state stays IDLE
    public void selectProduct(VendingMachine m, String c){ throw new IllegalStateException("Insert money first"); }
    public void dispense(VendingMachine m)               { throw new IllegalStateException("No selection"); }
    public void cancel(VendingMachine m)                 { /* nothing to refund */ }
}
```

```java
// STATE 2: money is in. You can add more, pick a product, or cancel.
class HasMoneyState implements State {
    public void insertMoney(VendingMachine m, int amt) {
        m.addBalance(amt);          // topping up — stay in HAS_MONEY
    }

    public void selectProduct(VendingMachine m, String code) {
        Product p = m.getProduct(code);
        if (p == null || p.stock == 0) throw new OutOfStock(code);                 // empty slot
        if (m.getBalance() < p.price)  throw new InsufficientFunds(p.price - m.getBalance()); // pay more

        m.setSelected(p);
        m.setState(m.dispensing);   // state changes: HAS_MONEY → DISPENSING
        m.dispense();               // kick off the actual drop
    }

    public void dispense(VendingMachine m) { /* not triggered directly here */ }

    public void cancel(VendingMachine m) {
        m.refund();                 // hand back the exact inserted balance
        m.setState(m.idle);         // state changes: HAS_MONEY → IDLE
    }
}
```

```java
// STATE 3: busy dropping the item + change. Ignores new input.
class DispensingState implements State {
    public void dispense(VendingMachine m) {
        Product p = m.getSelected();
        p.stock--;                                  // one fewer can in the slot
        int change = m.getBalance() - p.price;      // what we owe back
        m.dispenseProduct(p);                       // *clunk* — drop the can
        m.returnChange(change);                     // give change (via ChangeStrategy)
        m.reset();                                  // clear balance + selection
        m.setState(m.idle);                         // state changes: DISPENSING → IDLE
    }
    // insertMoney / selectProduct / cancel are intentionally no-ops or throw:
    // the machine is mid-transaction and shouldn't accept new input.
}
```

#### Piece 5 — `VendingMachine`: the context that delegates

The machine holds the shared data (balance, selected product, inventories) and **one reference to the current state**. Every public action is a one-liner that just forwards to the current state — *the machine itself contains no `if (state == ...)` logic at all.*

```java
class VendingMachine {
    // the four state objects, created once and reused (they're stateless)
    final State idle = new IdleState();
    final State hasMoney = new HasMoneyState();
    final State dispensing = new DispensingState();

    private State state = idle;              // START in IDLE
    private int balance;                     // money inserted so far, this transaction
    private Product selected;                // product chosen, this transaction
    private Map<String, Product> slots;      // "A4" → Coke(price, stock)
    private Inventory cashInventory;         // coins available to give as change
    private ChangeStrategy changeStrategy;   // HOW to make change (see Strategy)

    // --- public actions: each just DELEGATES to the current state ---
    void insertMoney(int a)     { state.insertMoney(this, a); }
    void selectProduct(String c){ state.selectProduct(this, c); }
    void dispense()             { state.dispense(this); }
    void cancel()               { state.cancel(this); }

    // --- helpers the states call back into ---
    void setState(State s)        { this.state = s; }
    void addBalance(int a)        { this.balance += a; }
    int  getBalance()             { return balance; }
    Product getProduct(String c)  { return slots.get(c); }
    void setSelected(Product p)   { this.selected = p; }
    Product getSelected()         { return selected; }
    void dispenseProduct(Product p){ /* release the physical item */ }
    void returnChange(int amt)    { changeStrategy.makeChange(amt, cashInventory); }
    void refund()                 { /* hand back `balance` */ }
    void reset()                  { this.balance = 0; this.selected = null; }
}
```

#### A full run, narrated

```
new VendingMachine()          state = IDLE,      balance = 0
 insertMoney(20)              IdleState → addBalance(20), state = HAS_MONEY   (balance 20)
 insertMoney(10)              HasMoneyState → addBalance(10)                  (balance 30)
 selectProduct("A4")          HasMoneyState → Coke ₹25, 30 ≥ 25 ✓,
                                              state = DISPENSING, dispense()
   (inside dispense)          DispensingState → stock--, change = 30-25 = 5,
                                              drop can, return ₹5, reset(),
                                              state = IDLE
 done                          state = IDLE,      balance = 0   → ready for next person
```

#### Q: Why do the state objects take `VendingMachine m` as a parameter?

Because the states are **stateless** — they hold no data themselves; the data (balance, selected, inventory) all lives on the machine. So when `IdleState` needs to record money, it calls back into the machine: `m.addBalance(amt)`. This lets us create each state **once** and share it (they're shared, stateless objects, not per-transaction objects).

#### Q: What is `ChangeStrategy` and why is it separate?

Making change is its own little algorithm, and there's more than one way to do it (greedy: biggest coins first; or exact-only: refuse if you can't hit it exactly). Pulling it into a swappable `ChangeStrategy` (the **Strategy pattern**) means you can change *how* change is made without touching the state machine. Greedy example:

```java
class GreedyChangeStrategy implements ChangeStrategy {
    public List<Coin> makeChange(int amount, Inventory cash) {
        List<Coin> out = new ArrayList<>();
        // try biggest coins first, but only ones we actually have
        Coin[] byValueDesc = { Coin.TEN, Coin.FIVE, Coin.TWO, Coin.ONE };
        for (Coin c : byValueDesc) {
            while (amount >= c.value && cash.countOf(c) > 0) {
                out.add(c);
                cash.remove(c, 1);   // we're physically giving this coin away
                amount -= c.value;
            }
        }
        if (amount != 0) throw new CannotMakeChange(amount);  // couldn't hit it exactly
        return out;
    }
}
```

#### Q: How is money/change handled safely? What if it can't make change?

Two safeguards: (1) `cashInventory` tracks the coins physically available, and the strategy only hands out coins it actually has; (2) if it can't reach the exact amount, it throws `CannotMakeChange` — a well-behaved machine would then either **refund** the user and cancel, or up front show *"exact change only."* Never dispense change you don't have.

#### Q: How is inventory (stock) handled? When does stock decrement?

Two inventories, don't confuse them: **product stock** (cans per slot) and **cash inventory** (coins for change). Product stock is checked in `HasMoneyState.selectProduct` (`p.stock == 0` → `OutOfStock`) and decremented in `DispensingState.dispense` (`p.stock--`) — i.e. only when the can actually drops, so a cancelled or failed transaction never miscounts stock.

#### Q: Where do the state transitions actually happen?

Only inside the state methods, via `m.setState(...)`. That's deliberate — the *states* own the transition rules, so all the "what state comes next" logic lives in exactly one place per state, never scattered across the machine. That's why there's no giant `switch` in `VendingMachine`.

---

## 4. Design Patterns (that can be used)

| Pattern | Where | Why |
| --- | --- | --- |
| **State** ⭐ | Machine states (IDLE/HAS_MONEY/DISPENSING) delegate behavior | Each state guards valid actions — the core of this problem |
| **Strategy** | `ChangeStrategy` (greedy/exact), payment method | Swap change/payment logic |
| **Singleton** | The machine instance | One authoritative machine |
| **Factory** | Create products / payment handlers | Extensible |
| **Observer** | Notify display/inventory/admin on events (low stock) | Decouple |
| **Command** | User actions as commands | Uniform, queueable |

> **Interview lead:** this is *the* **State pattern** question. Lead with the state machine + State objects; add Strategy for change-making, Singleton for the machine.

### Which pattern does what

Each pattern maps to a very concrete part of the real machine:

| Pattern | Real machine part | What it buys you |
| --- | --- | --- |
| **State** ⭐ | The machine's current state (waiting / paid / dropping) that changes which buttons work | Kills the `if/else` swamp; each state is a self-contained rulebook |
| **Strategy** | The change-making brain — "biggest coins first" vs "exact only" | Swap *how* change is made without touching the states |
| **Singleton** | There is **one** physical machine in the hallway | One authoritative object owning balance/inventory — no confusing duplicates |
| **Factory** | The loading dock that builds `Product`/payment objects | Add new products or payment types without editing everywhere |
| **Observer** | The little display + a "restock me" alert to the operator | The machine announces events; screens/alerts react on their own |
| **Command** | Each button press wrapped as a reified request | Uniform actions you can log, queue, or replay |

#### Q: State vs Strategy — aren't they the same? Both swap behavior.

Common confusion; the structures look identical, but the **intent** differs:

- **State** swaps behavior based on the object's own **internal situation, and the object flips itself between states** as things happen (IDLE → HAS_MONEY → DISPENSING). The transitions are the whole point.
- **Strategy** is a behavior **you (or config) pick once and it just stays** — the machine doesn't spontaneously switch from greedy to exact-change mid-sale. No self-driven transitions.

One-liner: *State = "the machine changes its own state over time"; Strategy = "plug in an algorithm and leave it."*

#### Q: Why Singleton for the machine?

Because there's genuinely **one** machine, holding one balance and one cash box. Two `VendingMachine` objects would be like two cash registers both thinking they own the same drawer — a recipe for money bugs. Singleton guarantees a single authoritative instance.

```java
// one machine, globally accessible
class VendingMachine {
    private static final VendingMachine INSTANCE = new VendingMachine();
    private VendingMachine() { /* private → nobody else can 'new' one */ }
    static VendingMachine getInstance() { return INSTANCE; }
}
```

#### Q: Where would Observer actually help?

When stock runs low, several things need to know: the front **display** ("SOLD OUT"), an **operator alert** ("refill slot A4"), maybe an **analytics** log. Rather than `selectProduct` calling all three, the machine just publishes a `lowStock` event and any number of listeners react — you can add a new listener later without editing the machine.

---

## 5. Interview Cheat Sheet

> **"How do you model a vending machine?"**
> "As a **finite state machine** using the **State pattern**: IDLE, HAS_MONEY, DISPENSING (and OUT_OF_STOCK). The machine delegates each action to its current state object, which only permits valid actions — e.g. selecting a product in IDLE throws 'insert money first'. This removes giant if/else blocks and makes transitions explicit."

> **"How is change handled?"**
> "A `ChangeStrategy` (greedy over available coin/note inventory); if exact change can't be made, reject or ask for exact money. Cash inventory is tracked so we never dispense change we don't have."

> **"What if two things happen at once / invalid action?"**
> "Invalid actions throw in the current state (e.g. dispense with no selection). State transitions are the single source of truth; the machine holds balance, selected product, and inventories."

### Answering the follow-up questions

The cheat-sheet lines are what you *say*; here's the reasoning so you can defend them.

#### Q: How do I "lead with the state machine" without rambling?

Draw the four states and the arrows between them **first** (the diagram in §2), then say one sentence: *"Each state is a class implementing a common `State` interface; the machine delegates every action to its current state, which only permits legal moves."* That single move — data on the machine, behavior in the states — is what they're grading. Everything else (Strategy, Singleton) is a bonus you sprinkle after.

#### Q: What about concurrency — two people using it at once?

Physically there's one coin slot and one keypad, so a real machine serializes input anyway. In code, the clean answer is: guard the machine's public actions (e.g. `synchronized`, or one command queue) so one transaction completes before the next begins. The `DISPENSING` state already models "busy, ignore input," which is the conceptual version of this lock.

#### Q: What's the single most common mistake candidates make?

Writing one enormous `handleAction()` method with a `switch (currentState)` inside. It "works," but it's the *anti-pattern* the State pattern exists to replace — the interviewer specifically wants to see the behavior distributed into per-state classes so illegal moves are impossible by construction, not caught by a forgotten `else` branch.

#### Q: How should I handle "dispense failed" or "no change available" gracefully?

Treat them as first-class outcomes, not crashes: if the item jams or change can't be made, the transaction should **refund** and return to `IDLE` rather than leaving the machine stuck mid-`DISPENSING`. Mentioning this failure-recovery path shows maturity beyond the happy path.

---

## 6. Final Takeaways

- The canonical **State pattern** problem — states (IDLE/HAS_MONEY/DISPENSING) each permit only valid actions.
- Machine **delegates** actions to the current `State` object; transitions are explicit (no if/else soup).
- **ChangeStrategy** (greedy over cash inventory) makes change; reject if impossible.
- Add **Strategy** (change/payment), **Singleton** (machine), **Observer** (display/low-stock), **Factory** (products).

### Related notes

- [Snake & Ladder (OOD)](snake-and-ladder-system-design.md) · [Parking Lot (OOD)](parking-lot-system-design.md) · [Elevator (OOD)](elevator-system-design.md)
