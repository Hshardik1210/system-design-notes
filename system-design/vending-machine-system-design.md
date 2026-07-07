# Vending Machine — Low-Level Design (OOD)

> A classic **OOD/LLD** problem, and the textbook example of the **State pattern**: a machine that takes money, lets you select a product, dispenses it, and returns change. Interviewers grade your **state machine and class model**.

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

---

## 3. Class Design

```java
interface State {
    void insertMoney(VendingMachine m, int amount);
    void selectProduct(VendingMachine m, String code);
    void dispense(VendingMachine m);
    void cancel(VendingMachine m);
}

class IdleState implements State {
    public void insertMoney(VendingMachine m, int amt) { m.addBalance(amt); m.setState(m.hasMoney); }
    public void selectProduct(VendingMachine m, String c){ throw new IllegalState("Insert money first"); }
    public void dispense(VendingMachine m)               { throw new IllegalState("No selection"); }
    public void cancel(VendingMachine m)                 { /* nothing */ }
}

class HasMoneyState implements State {
    public void insertMoney(VendingMachine m, int amt) { m.addBalance(amt); }
    public void selectProduct(VendingMachine m, String code) {
        Product p = m.getProduct(code);
        if (p == null || p.stock == 0) throw new OutOfStock(code);
        if (m.getBalance() < p.price)  throw new InsufficientFunds(p.price - m.getBalance());
        m.setSelected(p); m.setState(m.dispensing); m.dispense();   // proceed
    }
    public void dispense(VendingMachine m) { /* invalid here */ }
    public void cancel(VendingMachine m)   { m.refund(); m.setState(m.idle); }
}

class DispensingState implements State {
    public void dispense(VendingMachine m) {
        Product p = m.getSelected();
        p.stock--;
        int change = m.getBalance() - p.price;
        m.dispenseProduct(p);
        m.returnChange(change);        // ChangeStrategy (greedy coins)
        m.reset(); m.setState(m.idle);
    }
    // other actions invalid in this transient state
}

class VendingMachine {
    final State idle = new IdleState(), hasMoney = new HasMoneyState(), dispensing = new DispensingState();
    private State state = idle;
    private int balance;
    private Product selected;
    private Map<String, Product> slots;         // code → product (price, stock)
    private Inventory cashInventory;             // coins/notes for change
    private ChangeStrategy changeStrategy;

    // delegate to current state
    void insertMoney(int a){ state.insertMoney(this, a); }
    void selectProduct(String c){ state.selectProduct(this, c); }
    void dispense(){ state.dispense(this); }
    void cancel(){ state.cancel(this); }
    // ... setters/getters used by states
}

class Product { String code; String name; int price; int stock; }
interface ChangeStrategy { List<Coin> makeChange(int amount, Inventory cash); }  // greedy
```

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

---

## 5. Interview Cheat Sheet

> **"How do you model a vending machine?"**
> "As a **finite state machine** using the **State pattern**: IDLE, HAS_MONEY, DISPENSING (and OUT_OF_STOCK). The machine delegates each action to its current state object, which only permits valid actions — e.g. selecting a product in IDLE throws 'insert money first'. This removes giant if/else blocks and makes transitions explicit."

> **"How is change handled?"**
> "A `ChangeStrategy` (greedy over available coin/note inventory); if exact change can't be made, reject or ask for exact money. Cash inventory is tracked so we never dispense change we don't have."

> **"What if two things happen at once / invalid action?"**
> "Invalid actions throw in the current state (e.g. dispense with no selection). State transitions are the single source of truth; the machine holds balance, selected product, and inventories."

---

## 6. Final Takeaways

- The canonical **State pattern** problem — states (IDLE/HAS_MONEY/DISPENSING) each permit only valid actions.
- Machine **delegates** actions to the current `State` object; transitions are explicit (no if/else soup).
- **ChangeStrategy** (greedy over cash inventory) makes change; reject if impossible.
- Add **Strategy** (change/payment), **Singleton** (machine), **Observer** (display/low-stock), **Factory** (products).

### Related notes

- [Snake & Ladder (OOD)](snake-and-ladder-system-design.md) · [Parking Lot (OOD)](parking-lot-system-design.md) · [Elevator (OOD)](elevator-system-design.md)
