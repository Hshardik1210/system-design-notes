# Vending Machine — Low-Level Design (OOD)

> **Core challenge:** **The same button must behave differently depending on the machine's situation** — "insert money first" when empty, drop a can when paid. Modelling that cleanly (without an `if/else` swamp) is the whole problem, and it's why this is *the* **State pattern** interview.

> A classic **OOD/LLD** problem, and the textbook example of the **State pattern**: a machine that takes money, lets you select a product, dispenses it, and returns change. Interviewers grade your **state machine and class model**.

> **How to read this doc:** each section has the dense interview summary first, then a **deep dive** (annotated Java and the exact confusions that come up while learning). Skim the summaries for revision; read the deep dives to actually understand.

---

## Contents

- [1. Requirements](#1-requirements)
- [2. The State Machine (the key)](#2-the-state-machine-the-key)
- [3. Class Design](#3-class-design)
- [4. Extensibility](#4-extensibility)
- [5. How to Drive the Interview](#5-how-to-drive-the-interview)
- [6. Design Patterns (that can be used)](#6-design-patterns-that-can-be-used)
- [7. Interview Cheat Sheet](#7-interview-cheat-sheet)
- [8. Final Takeaways](#8-final-takeaways)

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

### Why this is a "hard" interview problem — it's just a vending machine

The naive version turns into a swamp of `if/else`. Every action has to first ask "wait, what situation am I in right now — has the user paid? is something selected? am I mid-dispense?" and branch accordingly. Do that for 4 actions × 4 situations and you get a tangle that's easy to get wrong (e.g. letting someone select twice, or refunding money that was already spent). The clean solution is to make the *situation itself* an object — the **State pattern** (§2).

### "Correct money handling" — what could go wrong

Two classic bugs:

- **Bad change:** user pays ₹30 for a ₹25 item, machine owes ₹5 — but only has ₹2 coins in the box. It must **not** promise change it can't physically give. So we track a **cash inventory** and refuse (or ask for exact money) when change is impossible.
- **Losing the balance:** if the machine forgets how much you inserted (or lets a second person's coins mix in mid-transaction), it over- or under-charges. So exactly **one** transaction's balance is tracked at a time, and cancelling must refund the *exact* inserted amount.

---

## 2. The State Machine (the key)

The vending machine is a **finite state machine** — this is why it's the canonical **State pattern** example.

```
IDLE ──insertMoney──► HAS_MONEY ──selectProduct──► DISPENSING ──► (return change) ──► IDLE
  ▲                       │  cancel → refund                          │
  │                       │  select empty slot → refund               │ (jam / can't make change)
  └───────────────────────┴──────────────────► REFUNDING ◄────────────┘
                                          (hand back full balance) │
                                                                    ▼
                                                                  IDLE
```

| State | Allowed actions |
| --- | --- |
| `IDLE` | insert money (else "insert money first") |
| `HAS_MONEY` | insert more, select product, cancel (refund) |
| `DISPENSING` | dispense product + change → IDLE, or fault → REFUNDING |
| `REFUNDING` | hand back full balance → IDLE (ignores input) |
| `OUT_OF_STOCK` | reject selection (or transient: refund → IDLE) |

> Each state **only allows valid actions** — e.g. you can't select a product in `IDLE`. That guard logic living in state objects is the whole point of the pattern.

> ⚠️ **Don't stop at the happy path.** The transitions that separate a strong answer from a mediocre one are the **failure edges**: a slot that's empty *after* you've paid, and a jam *mid-dispense*. Both must land in `REFUNDING` and give the money back — never leave the machine stuck holding a balance.

### The failure branch: jam mid-dispense and empty-after-pay

Two things can go wrong once money is already in, and both funnel through a single **REFUNDING** state:

```
[HAS_MONEY] --select empty slot--> refund full balance --> [REFUNDING] --> [IDLE]
[DISPENSING] --motor jams / no change--> abort drop      --> [REFUNDING] --> [IDLE]
```

Why route both through one `REFUNDING` state instead of refunding inline? Because "give the money back, then reset to IDLE" is a **real, distinct situation** (like DISPENSING): while it's happening the machine must ignore new coins/buttons, and modelling it as a state keeps the "money owed back" guarantee in exactly one place. A jam that *silently* stays in DISPENSING — machine keeps your ₹30, no can — is the exact bug interviewers probe for.

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

### What a "finite state machine (FSM)" is

Just a fancy name for "a thing that is always in exactly **one** of a fixed list of states, and only certain moves take it from one state to another." *Finite* = the list of states is small and known ahead of time (here: a small handful — IDLE, HAS_MONEY, DISPENSING, plus the REFUNDING / OUT_OF_STOCK edges). Traffic lights (Red → Green → Yellow → Red) and microwaves are FSMs too. The vending machine is the canonical coding example.

### The "State pattern," in one sentence

**Make each state its own class**, and let the machine hand off ("delegate") every action to whichever state object it's currently holding. Instead of one giant method full of `if (state == IDLE) ... else if (state == HAS_MONEY) ...`, you have an `IdleState` object and a `HasMoneyState` object, and each one *only* knows how to behave in that situation. The machine just forwards each button press to its current state object (see the code in §3).

> 💡 **Jargon, first use — "State pattern".** Two moving parts: a **context** (`VendingMachine`, holds the data + a pointer to the current state) and a set of **state objects** (one class per situation). The machine changes its *own* current state as events happen — that self-driven flipping is what distinguishes State from Strategy (§6).

### What happens on an "invalid transition," like selecting a product in IDLE

The current state simply **refuses** it. `IdleState.selectProduct(...)` throws *"insert money first"* and the state does **not** change — you stay IDLE. That's the beauty: each state is a bouncer that only lets legal moves through, so illegal moves can't quietly corrupt the machine. No move is "handled everywhere"; each move is only truly implemented in the states where it makes sense.

### Why DISPENSING is its own state instead of just doing it inside "select"

Dispensing is a real, momentary situation where the machine should **ignore new input** (you shouldn't be able to insert more money or select again while a can is dropping). Modeling it as a distinct state makes "I'm busy, buttons do nothing right now" explicit and safe, instead of hoping no one presses anything during the split second of work.

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

#### Piece 4 — The `State` interface and the states

This is the pattern itself. Every state implements the **same four actions**, but each state only does something meaningful for the actions that are *legal* in that state; the rest throw. The three happy-path states (`IdleState`, `HasMoneyState`, `DispensingState`) come first; the failure-edge states (`RefundingState`, `OutOfStockState`) follow.

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
        m.setState(m.refunding);    // state changes: HAS_MONEY → REFUNDING
        m.dispense();               // RefundingState hands back the full balance, then → IDLE
    }
}
```

```java
// STATE 3: busy dropping the item + change. Ignores new input.
class DispensingState implements State {
    public void dispense(VendingMachine m) {
        Product p = m.getSelected();
        int change = m.getBalance() - p.price;      // what we owe back

        try {
            m.motor.drop(p);            // *clunk* — the physical drop CAN jam/fail
            m.returnChange(change);     // and change-making CAN fail (no coins)
        } catch (DispenseFailed | CannotMakeChange e) {
            // FAILURE EDGE: nothing (or a stuck item) came out.
            // Do NOT decrement stock, do NOT keep the money.
            m.setState(m.refunding);    // state changes: DISPENSING → REFUNDING
            m.refundAll();              // give the whole balance back
            return;
        }

        p.stock--;                      // success only: one fewer can in the slot
        m.reset();                      // clear balance + selection
        m.setState(m.idle);             // state changes: DISPENSING → IDLE
    }
    // insertMoney / selectProduct / cancel are intentionally no-ops here:
    // the machine is mid-transaction and shouldn't accept new input.
}
```

> ⚠️ **Order matters on the failure edge.** Decrement stock and clear the balance **only after** the drop succeeds. If you `p.stock--` first and *then* the motor jams, you've lost a can from the count and kept the user's money — the two worst outcomes at once.

```java
// STATE 4: giving money back after a cancel or a fault. Also ignores input.
class RefundingState implements State {
    public void dispense(VendingMachine m) {
        m.returnBalanceAsCoins();       // physically hand back the full balance
        m.reset();                      // balance = 0, selected = null
        m.setState(m.idle);             // state changes: REFUNDING → IDLE
    }
    // insertMoney / selectProduct / cancel: no-ops — we're busy refunding.
}
```

```java
// STATE 5: the selected slot is empty. Reject further selection here.
// Reached only if you model "sold out" as a lingering state (e.g. a
// single-product machine). A multi-slot machine usually stays in
// HAS_MONEY and just rejects the one empty slot (see below).
class OutOfStockState implements State {
    public void selectProduct(VendingMachine m, String code) {
        Product p = m.getProduct(code);
        if (p != null && p.stock > 0) {          // a restock happened
            m.setState(m.hasMoney);              // recover: OUT_OF_STOCK → HAS_MONEY
            m.selectProduct(code);
            return;
        }
        throw new OutOfStock(code);              // still empty → refuse
    }
    public void insertMoney(VendingMachine m, int amt) { m.addBalance(amt); }
    public void cancel(VendingMachine m) {       // let the user bail out + refund
        m.setState(m.refunding);
        m.dispense();
    }
    public void dispense(VendingMachine m) { /* nothing to dispense */ }
}
```

**Empty slot *after* the user has paid — the HAS_MONEY refund path.** In a multi-slot machine you don't need a lingering `OUT_OF_STOCK` state: `HasMoneyState.selectProduct` already checks `p.stock == 0`. You have two reasonable choices, and saying which (and why) scores points:

- **Stay + let them re-pick (default here):** throw `OutOfStock`, stay in `HAS_MONEY` — money is still in, the user just picks another item. Best UX for a machine with other stock.
- **Refund + reset:** if the picked item is the only thing they wanted, route to `REFUNDING` and give the balance back:

```java
// variant inside HasMoneyState.selectProduct, when the slot is empty:
if (p == null || p.stock == 0) {
    m.setState(m.refunding);   // HAS_MONEY → REFUNDING
    m.dispense();              // hand the full balance back, then → IDLE
    return;
}
```

#### Piece 5 — `VendingMachine`: the context that delegates

The machine holds the shared data (balance, selected product, inventories) and **one reference to the current state**. Every public action is a one-liner that just forwards to the current state — *the machine itself contains no `if (state == ...)` logic at all.*

```java
class VendingMachine {
    // the state objects, created once and reused (they're stateless)
    final State idle = new IdleState();
    final State hasMoney = new HasMoneyState();
    final State dispensing = new DispensingState();
    final State refunding = new RefundingState();     // cancel / fault → give money back
    final State outOfStock = new OutOfStockState();    // slot empty

    private State state = idle;              // START in IDLE
    private int balance;                     // money inserted so far, this transaction
    private Product selected;                // product chosen, this transaction
    private Map<String, Product> slots;      // "A4" → Coke(price, stock)
    private Inventory cashInventory;         // coins available to give as change
    private ChangeStrategy changeStrategy;   // HOW to make change (see Strategy)
    Motor motor;                             // physical drop; can throw DispenseFailed

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
    void returnChange(int amt)    { changeStrategy.makeChange(amt, cashInventory); }
    void refundAll()              { dispense(); }              // delegate to RefundingState
    void returnBalanceAsCoins()   { changeStrategy.makeChange(balance, cashInventory); }
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

#### A second run: cancel / refund mid-`HAS_MONEY`

The happy path is easy; the trace that shows you understand the *transitions* is the one where the user backs out after paying:

```
new VendingMachine()          state = IDLE,      balance = 0
 insertMoney(50)              IdleState → addBalance(50), state = HAS_MONEY   (balance 50)
 insertMoney(10)              HasMoneyState → addBalance(10)                  (balance 60)
 cancel()                     HasMoneyState.cancel → state = REFUNDING, dispense()
   (inside dispense)          RefundingState → returnBalanceAsCoins() = ₹60,
                                              reset(), state = IDLE
 done                          state = IDLE,      balance = 0   → nothing kept, nothing dispensed
```

And the jam trace — paid, selected, but the motor fails mid-drop:

```
 selectProduct("A4")          HasMoneyState → Coke ₹25, 60 ≥ 25 ✓,
                                              state = DISPENSING, dispense()
   (inside dispense)          DispensingState → motor.drop() throws DispenseFailed,
                                              state = REFUNDING, refundAll()
   (inside dispense)          RefundingState → return ₹60, reset(), state = IDLE
 done                          stock UNCHANGED (no can dropped), full ₹60 returned
```

### Why the state objects take `VendingMachine m` as a parameter

The states are **stateless** — they hold no data themselves; the data (balance, selected, inventory) all lives on the machine. So when `IdleState` needs to record money, it calls back into the machine: `m.addBalance(amt)`. This lets us create each state **once** and share it (they're shared, stateless objects, not per-transaction objects).

### What `ChangeStrategy` is, and why it's separate

Making change is its own little algorithm, and there's more than one way to do it (greedy: biggest coins first; or exact-only: refuse if you can't hit it exactly). Pulling it into a swappable `ChangeStrategy` (the **Strategy pattern**) means you can change *how* change is made without touching the state machine.

> 💡 **First sighting of Strategy (vs State).** `ChangeStrategy` is a **Strategy**: a plug-in algorithm you pick once at setup and leave alone. Contrast the **State** objects, which the machine flips between *itself* as events happen. Same "swap behaviour" shape, opposite intent — see the [State vs Strategy](#q-state-vs-strategy--arent-they-the-same-both-swap-behavior) callout in §6.

Greedy example:

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

#### Q: Wait — there are *two* different "inventories"? Product stock vs the cash box?

Yes, and conflating them is the most common modelling slip. They answer different questions and are touched at different moments:

```
              ┌──────────────────────────────┐
              │        VendingMachine         │
              ├───────────────┬──────────────┤
  "what can   │  slots        │  cashInventory│  "what coins can
   I sell?"   │  A4 → Coke×3  │  ₹10 × 4      │   I hand back as
              │  B2 → Water×0 │  ₹5  × 1      │   CHANGE?"
              │  (Product     │  ₹1  × 12     │  (Inventory of Coin)
              │   stock)      │               │
              └───────┬───────┴──────┬───────┘
                      │              │
   checked/decremented on a SALE     drained by ChangeStrategy
   (p.stock-- in DispensingState)    (returnChange / refund)
```

- **Product stock** = how many *cans* are in each slot. Checked in `HasMoneyState.selectProduct` (`p.stock == 0` → out of stock) and decremented in `DispensingState` **only on a successful drop**.
- **Cash inventory** = how many of each *coin* the machine holds to give back as change/refund. Drained by the `ChangeStrategy`.

They fail independently: a slot can be full while the cash box can't make ₹5 change (→ `CannotMakeChange`), and the cash box can be flush while the slot is empty (→ out of stock). Keeping them as separate fields is what lets each failure be handled cleanly.

### How inventory (stock) is handled, and when stock decrements

Two inventories, don't confuse them: **product stock** (cans per slot) and **cash inventory** (coins for change). Product stock is checked in `HasMoneyState.selectProduct` (`p.stock == 0` → `OutOfStock`) and decremented in `DispensingState.dispense` (`p.stock--`) — i.e. only when the can actually drops, so a cancelled or failed transaction never miscounts stock.

### Where the state transitions actually happen

Only inside the state methods, via `m.setState(...)`. That's deliberate — the *states* own the transition rules, so all the "what state comes next" logic lives in exactly one place per state, never scattered across the machine. That's why there's no giant `switch` in `VendingMachine`.

### Database & storage choices

The control path (state transitions, balance, dispensing) has to stay **in-memory** — it's one physical machine, and every button press needs an instant answer; a DB round-trip has no business sitting inside the state machine itself. Persistence, if any, is bolted on the side for inventory/audit, not the control loop:

- **A single machine:** barely needs a DB at all — an embedded store like **SQLite** for product inventory and a sales/restock log, so counts survive a reboot without needing a network.
- **A fleet of machines:** a central **RDBMS** for inventory + sales + cash-collection records per machine, fed by a lightweight telemetry stream (low-stock/fault events) from each machine back to head office.

Either way, the `State` objects and `VendingMachine` fields from §3 never touch a database directly — only the restock/reporting path does. See [Databases — Deep Dive](../concepts/databases-deep-dive.md).

---

## 4. Extensibility

> The interviewer's favourite follow-up: *"now add card payments / a shopping cart / exact-change-only."* The whole point of the State + Strategy split is that each of these is a **localized swap**, not a rewrite. State the seam it plugs into.

### Card payments — a `PaymentProcessor` Strategy

Cash is just one way to pay. Introduce a `PaymentProcessor` strategy so the state machine never hard-codes "coins":

```java
interface PaymentProcessor {
    int  amountTendered();          // how much has been paid so far
    void refund(int amount);        // give it back (coins, or reverse the card auth)
}

class CashProcessor implements PaymentProcessor { /* coins + cashInventory */ }
class CardProcessor implements PaymentProcessor { /* authorize on select, capture on drop */ }
```

**What changes:** `HasMoneyState` asks `m.payment.amountTendered()` instead of reading `balance` directly; refund goes through `payment.refund(...)`.
**What doesn't:** the state graph (IDLE → HAS_MONEY → DISPENSING → …) is identical. A card swipe just moves you into `HAS_MONEY` the same way a coin does.

> 💡 Card money is **authorize-then-capture**: authorize when the user has "enough", capture only in `DispensingState` on a successful drop, and *void the authorization* on the REFUNDING edge — the money analogue of "decrement stock only on success."

### Multi-item cart — one transaction, several picks

Today one transaction = one product. To support "add A4, add B2, then pay", make the transaction hold a **list** of selections and a total:

- `HasMoneyState.selectProduct` **appends** to a cart and stays in `HAS_MONEY` (no auto-dispense).
- A new `checkout()` action moves `HAS_MONEY → DISPENSING`, which drops **each** item and computes change against the cart total.
- Stock is checked per item at add-time and re-checked at dispense-time (someone could empty a slot in between).

**What changes:** `selected` (one `Product`) becomes `cart` (a `List`); add a `checkout` action. **What doesn't:** still the same states and the same "decrement/charge only on success" rule.

### Exact-change-only — just another `ChangeStrategy`

If the machine's cash box is low, flip the change algorithm — no state changes at all:

```java
class ExactChangeStrategy implements ChangeStrategy {
    public List<Coin> makeChange(int amount, Inventory cash) {
        if (amount != 0) throw new CannotMakeChange(amount);   // only accept exact payment
        return List.of();
    }
}
```

Swap `GreedyChangeStrategy` → `ExactChangeStrategy` (and light the "EXACT CHANGE ONLY" lamp). Because it's a Strategy, the states are untouched — this is precisely the payoff of pulling change-making out of the state machine.

### Admin / restock — a `MaintenanceState`

Restocking and cash collection aren't customer actions, so they get their **own** state that locks out purchases:

```java
// Entered when the operator opens the machine with a key/PIN.
class MaintenanceState implements State {
    void restock(VendingMachine m, String code, int qty) { m.getProduct(code).stock += qty; }
    void refillCash(VendingMachine m, Coin c, int qty)   { m.cash().add(c, qty); }
    int  collectCash(VendingMachine m)                   { return m.cash().sweep(); }
    // insertMoney / selectProduct / dispense all throw: not serving customers now
}
```

```
[IDLE] --operator unlocks (admin auth)--> [MAINTENANCE] --lock up--> [IDLE]
                                              │
                       restock stock · refill/collect cash · read sales log
```

> ⚠️ Only enter `MAINTENANCE` from `IDLE` (balance = 0). Letting an operator open up mid-`HAS_MONEY` would strand a customer's money — refund and reset first. This is also where the low-stock **Observer** event (§6) fires from.

---

## 5. How to Drive the Interview

> This is a **design/coding** interview, not a distributed-systems one — so drive it toward the **class model and state machine**, and treat scale as a footnote.

1. **Clarify** (30s) — exact change or make change? Card or cash? One item per transaction or a cart? These pin the scope (§1).
2. **Name the core challenge** — *"same button, different behaviour by situation → I'll model it as a finite state machine with the **State pattern**."* Say this out loud first (§2).
3. **Draw the FSM** — the three core states (IDLE / HAS_MONEY / DISPENSING) plus the **REFUNDING** failure edge. Interviewers love that you volunteer failure paths (§2).
4. **Sketch the classes** — `State` interface, the state classes, and the `VendingMachine` context that *delegates* and holds the data (§3). Emphasize: **data on the machine, behaviour in the states.**
5. **Walk one happy trace, then one failure trace** — pay → select → dispense, then cancel-refund and jam-refund (§3). Traces prove the transitions actually work.
6. **Layer in the Strategies** — `ChangeStrategy` for change, `PaymentProcessor` for card. Call out State-vs-Strategy explicitly (§6) — it's a classic "gotcha" they'll test.
7. **Take the extension** — card / cart / exact-change / restock (§4), each as a swap into a named seam.
8. **Close on money-safety invariants** — never keep money without dispensing, never dispense change you don't hold, decrement stock only on success.

> 🎤 **Lead with the state machine, sprinkle patterns after.** Draw the states + arrows before writing any class. Strategy, Singleton, Observer are bonus toppings — don't open with them.

---

## 6. Design Patterns (that can be used)

Each pattern maps to a very concrete part of the real machine:

| Pattern | Where (real machine part) | Why |
| --- | --- | --- |
| **State** ⭐ | Machine's current state (IDLE/HAS_MONEY/DISPENSING) — changes which buttons work | Kills the `if/else` swamp; each state is a self-contained rulebook that guards valid actions — the core of this problem |
| **Strategy** | `ChangeStrategy` (greedy/exact) — the change-making brain, "biggest coins first" vs "exact only" | Swap *how* change is made without touching the states |
| **Singleton** | The one physical machine in the hallway | One authoritative object owning balance/inventory — no confusing duplicates |
| **Factory** | The loading dock that builds `Product`/payment objects | Add new products or payment types without editing everywhere |
| **Observer** | The little display + a "restock me" alert to the operator | Machine announces events (low stock); screens/alerts react on their own, decoupled |
| **Command** | Each button press wrapped as a reified request | Uniform actions you can log, queue, or replay |

> **Interview lead:** this is *the* **State pattern** question. Lead with the state machine + State objects; add Strategy for change-making, Singleton for the machine.

#### Q: State vs Strategy — aren't they the same? Both swap behavior.

Common confusion; the structures look identical, but the **intent** differs:

- **State** swaps behavior based on the object's own **internal situation, and the object flips itself between states** as things happen (IDLE → HAS_MONEY → DISPENSING). The transitions are the whole point.
- **Strategy** is a behavior **you (or config) pick once and it just stays** — the machine doesn't spontaneously switch from greedy to exact-change mid-sale. No self-driven transitions.

One-liner: *State = "the machine changes its own state over time"; Strategy = "plug in an algorithm and leave it."*

### Why Singleton for the machine

There's genuinely **one** machine, holding one balance and one cash box. Two `VendingMachine` objects would be like two cash registers both thinking they own the same drawer — a recipe for money bugs. Singleton guarantees a single authoritative instance.

```java
// one machine, globally accessible
class VendingMachine {
    private static final VendingMachine INSTANCE = new VendingMachine();
    private VendingMachine() { /* private → nobody else can 'new' one */ }
    static VendingMachine getInstance() { return INSTANCE; }
}
```

### Where Observer actually helps

When stock runs low, several things need to know: the front **display** ("SOLD OUT"), an **operator alert** ("refill slot A4"), maybe an **analytics** log. Rather than `selectProduct` calling all three, the machine just publishes a `lowStock` event and any number of listeners react — you can add a new listener later without editing the machine.

---

## 7. Interview Cheat Sheet

> **"How do you model a vending machine?"**
> "As a **finite state machine** using the **State pattern**: IDLE, HAS_MONEY, DISPENSING (and OUT_OF_STOCK). The machine delegates each action to its current state object, which only permits valid actions — e.g. selecting a product in IDLE throws 'insert money first'. This removes giant if/else blocks and makes transitions explicit."

> **"How is change handled?"**
> "A `ChangeStrategy` (greedy over available coin/note inventory); if exact change can't be made, reject or ask for exact money. Cash inventory is tracked so we never dispense change we don't have."

> **"What if two things happen at once / invalid action?"**
> "Invalid actions throw in the current state (e.g. dispense with no selection). State transitions are the single source of truth; the machine holds balance, selected product, and inventories."

### Answering the follow-up questions

The cheat-sheet lines are what you *say*; here's the reasoning so you can defend them.

### How to "lead with the state machine" without rambling

Draw the four states and the arrows between them **first** (the diagram in §2), then say one sentence: *"Each state is a class implementing a common `State` interface; the machine delegates every action to its current state, which only permits legal moves."* That single move — data on the machine, behavior in the states — is what they're grading. Everything else (Strategy, Singleton) is a bonus you sprinkle after.

#### Q: What about concurrency — two people using it at once?

Physically there's one coin slot and one keypad, so a real machine serializes input anyway. In code, the clean answer is: guard the machine's public actions (e.g. `synchronized`, or one command queue) so one transaction completes before the next begins. The `DISPENSING` state already models "busy, ignore input," which is the conceptual version of this lock.

#### Q: What's the single most common mistake candidates make?

Writing one enormous `handleAction()` method with a `switch (currentState)` inside. It "works," but it's the *anti-pattern* the State pattern exists to replace — the interviewer specifically wants to see the behavior distributed into per-state classes so illegal moves are impossible by construction, not caught by a forgotten `else` branch.

### How to handle "dispense failed" or "no change available" gracefully

Treat them as first-class outcomes, not crashes: if the item jams or change can't be made, the transaction should **refund** and return to `IDLE` rather than leaving the machine stuck mid-`DISPENSING`. Mentioning this failure-recovery path shows maturity beyond the happy path.

### Tricky scenarios (rapid-fire)

| Scenario | What happens / what to do |
| --- | --- |
| **Can't make change** (owe ₹5, no coins) | `ChangeStrategy` throws `CannotMakeChange` → route to `REFUNDING`, hand back the full balance, don't drop the can. Or show "exact change only" up front (`ExactChangeStrategy`). Never dispense change you don't hold. |
| **Double-select** (press A4, then B2 while dispensing) | `DispensingState` ignores input — the second press is a no-op. Selection is only legal in `HAS_MONEY`, and you leave it the instant you select. |
| **Cancel after partial pay** (₹60 in, no selection) | `HasMoneyState.cancel` → `REFUNDING` → return the exact ₹60 → `IDLE`. Stock untouched, nothing dispensed. |
| **Jam mid-dispense** (motor fails) | `DispensingState` catches `DispenseFailed` → `REFUNDING`, refund full balance, **stock not decremented**. The failure edge, not a crash. |
| **Slot empty after paying** | Stay in `HAS_MONEY` and reject that slot (user picks another), or refund via `REFUNDING` if it's a single-product machine. |
| **Machine opened for restock mid-sale** | Refund + reset to `IDLE` first; only enter `MAINTENANCE` from a clean IDLE so no customer money is stranded. |

> **Invariant to repeat:** money and product move **together and only on success** — decrement stock, capture payment, and give change in one committed step; on any fault, refund everything and reset.

---

## 8. Final Takeaways

- The canonical **State pattern** problem — states (IDLE/HAS_MONEY/DISPENSING) each permit only valid actions.
- Machine **delegates** actions to the current `State` object; transitions are explicit (no if/else soup).
- **ChangeStrategy** (greedy over cash inventory) makes change; reject if impossible.
- Add **Strategy** (change/payment), **Singleton** (machine), **Observer** (display/low-stock), **Factory** (products).

### Related notes

- [Snake & Ladder (OOD)](snake-and-ladder-system-design.md) · [Parking Lot (OOD)](parking-lot-system-design.md) · [Elevator (OOD)](elevator-system-design.md)
