import java.util.*;

/**
 * VENDING MACHINE — the canonical STATE pattern problem.
 *
 * The machine behaves differently depending on its current state:
 *   IDLE            -> waiting for a product selection
 *   ITEM_SELECTED   -> waiting for money
 *   HAS_MONEY       -> enough money in; can dispense
 *   DISPENSING      -> hands out item + change, returns to IDLE
 *
 * Instead of giant if/else on a "state" enum, each state is its own class
 * (State pattern). Each state decides what the next state is.
 *
 * Patterns:
 *   - State    -> VendingState + concrete states.
 *   - Strategy -> ChangeStrategy (how to make change; greedy here).
 */
// Outer class is just a container so the whole example lives in one file.
public class Main {

    // ---------- Inventory ----------
    // A single item the machine can sell: its code (e.g. "A1"), display name, and price.
    static class Product {
        final String code; final String name; final int price; // price in cents
        Product(String code, String name, int price) { this.code = code; this.name = name; this.price = price; }
    }

    // Tracks what products exist and how many of each are left in stock.
    static class Inventory {
        // Two lookups by product code: one for the product itself, one for how many remain.
        private final Map<String, Product> products = new HashMap<>();
        private final Map<String, Integer> quantity = new HashMap<>();

        // Register a product and add to its stock count (merge sums with any existing quantity).
        void add(Product p, int qty) { products.put(p.code, p); quantity.merge(p.code, qty, Integer::sum); }
        // True only if we still have at least one of this product.
        boolean available(String code) { return quantity.getOrDefault(code, 0) > 0; }
        Product get(String code) { return products.get(code); }
        // Called after a successful sale: decrease stock by one.
        void reduce(String code) { quantity.merge(code, -1, Integer::sum); }
    }

    // ---------- Strategy: making change ----------
    // Strategy pattern: an interchangeable algorithm for computing coin change.
    interface ChangeStrategy {
        // Returns coin denominations summing to 'amount', or null if not makeable.
        List<Integer> makeChange(int amount);
    }
    // Greedy implementation: always take the largest coin that still fits.
    static class GreedyChange implements ChangeStrategy {
        private static final int[] COINS = {100, 50, 25, 10, 5, 1}; // cents, largest first
        public List<Integer> makeChange(int amount) {
            List<Integer> res = new ArrayList<>();
            // For each denomination, take as many as possible before moving to a smaller coin.
            for (int c : COINS) {
                while (amount >= c) { res.add(c); amount -= c; }
            }
            // If we reduced the amount exactly to 0 the change is makeable; otherwise it isn't.
            return amount == 0 ? res : null;
        }
    }

    // ---------- State pattern ----------
    // The State contract: every state must handle all three user actions.
    // Each action receives the machine so a state can read/change it and switch to the next state.
    interface VendingState {
        void selectProduct(VendingMachine m, String code);
        void insertMoney(VendingMachine m, int amount);
        void dispense(VendingMachine m);
    }

    // IDLE state: waiting for a selection; only selecting a product is meaningful here.
    static class IdleState implements VendingState {
        // Pick a product: reject if out of stock, otherwise remember it and move to the money state.
        public void selectProduct(VendingMachine m, String code) {
            if (!m.inventory.available(code)) { System.out.println("  ! Out of stock: " + code); return; }
            m.selectedCode = code;
            m.setState(m.itemSelected); // transition IDLE -> ITEM_SELECTED/HAS_MONEY
            System.out.println("  Selected " + m.inventory.get(code).name +
                    " (price " + m.inventory.get(code).price + "). Please insert money.");
        }
        // In IDLE these actions make no sense, so we just warn the user (no state change).
        public void insertMoney(VendingMachine m, int a) { System.out.println("  ! Select a product first."); }
        public void dispense(VendingMachine m) { System.out.println("  ! Select a product first."); }
    }

    // ITEM_SELECTED / HAS_MONEY combined: accumulate money until enough.
    static class HasMoneyState implements VendingState {
        // Can't re-select while a purchase is in progress.
        public void selectProduct(VendingMachine m, String code) { System.out.println("  ! Already selecting; finish or cancel."); }
        // Add the inserted coins to the running balance and tell the user if they can now buy.
        public void insertMoney(VendingMachine m, int amount) {
            m.balance += amount;
            int price = m.inventory.get(m.selectedCode).price;
            System.out.println("  Inserted " + amount + "; balance " + m.balance + "/" + price);
            if (m.balance >= price) System.out.println("  Enough money. You can dispense.");
        }
        // Only allow dispensing once enough money is in; then hand off to the dispensing state.
        public void dispense(VendingMachine m) {
            int price = m.inventory.get(m.selectedCode).price;
            if (m.balance < price) { System.out.println("  ! Insufficient money."); return; }
            m.setState(m.dispensing);         // transition -> DISPENSING
            m.dispense(); // delegate to dispensing state
        }
    }

    // DISPENSING state: hand out item + change, then reset back to IDLE.
    static class DispensingState implements VendingState {
        // While busy dispensing, ignore other actions with a warning.
        public void selectProduct(VendingMachine m, String code) { System.out.println("  ! Busy dispensing."); }
        public void insertMoney(VendingMachine m, int a) { System.out.println("  ! Busy dispensing."); }
        // Compute change, try to make it, dispense (or refund), then return to IDLE.
        public void dispense(VendingMachine m) {
            Product p = m.inventory.get(m.selectedCode);
            int change = m.balance - p.price;                 // overpaid amount to return
            // No change needed -> empty list; otherwise ask the strategy to break it into coins.
            List<Integer> coins = change == 0 ? Collections.emptyList() : m.changeStrategy.makeChange(change);
            if (change > 0 && coins == null) {
                // Coins couldn't be made for this amount: refund everything instead of selling.
                System.out.println("  ! Cannot make change; refunding " + m.balance);
            } else {
                m.inventory.reduce(p.code); // sale succeeds: drop stock by one
                System.out.println("  >> Dispensed " + p.name + (change > 0 ? "; change " + change + " " + coins : ""));
            }
            m.reset(); // back to IDLE (clears selection and balance)
        }
    }

    // ---------- The machine (Context) ----------
    // The context holds all shared data (inventory, balance, selection) and the current state.
    // It owns one instance of each state and simply delegates actions to the active one.
    static class VendingMachine {
        final Inventory inventory = new Inventory();
        final ChangeStrategy changeStrategy = new GreedyChange();
        // pre-created state singletons (reused instead of new-ing states on every transition)
        final VendingState idle = new IdleState();
        final VendingState itemSelected = new HasMoneyState();
        final VendingState dispensing = new DispensingState();

        private VendingState state = idle; // machine starts idle
        String selectedCode;               // which product the user picked (null when idle)
        int balance = 0;                   // money inserted so far, in cents

        // How states move the machine forward.
        void setState(VendingState s) { this.state = s; }
        // Return to the starting point after a sale/refund.
        void reset() { state = idle; selectedCode = null; balance = 0; }

        // Public API delegates to whatever state we're in (the heart of the State pattern).
        void selectProduct(String code) { state.selectProduct(this, code); }
        void insertMoney(int amount)     { state.insertMoney(this, amount); }
        void dispense()                  { state.dispense(this); }
    }

    // ---------- Demo ----------
    // Drives the machine through a few scenarios to show each state's behaviour.
    public static void main(String[] args) {
        VendingMachine m = new VendingMachine();
        m.inventory.add(new Product("A1", "Coke", 75), 2);
        m.inventory.add(new Product("A2", "Water", 50), 1);

        System.out.println("--- Buy Coke with exact + extra money ---");
        m.selectProduct("A1");   // IDLE -> selected
        m.insertMoney(50);       // not enough
        m.insertMoney(50);       // now 100 >= 75
        m.dispense();            // dispenses Coke, change 25

        System.out.println("--- Try dispense with no selection ---");
        m.dispense();

        System.out.println("--- Buy Water ---");
        m.selectProduct("A2");
        m.insertMoney(50);
        m.dispense();

        System.out.println("--- Water now out of stock ---");
        m.selectProduct("A2");
    }
}
