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
public class Main {

    // ---------- Inventory ----------
    static class Product {
        final String code; final String name; final int price; // price in cents
        Product(String code, String name, int price) { this.code = code; this.name = name; this.price = price; }
    }

    static class Inventory {
        // code -> [product, quantity]
        private final Map<String, Product> products = new HashMap<>();
        private final Map<String, Integer> quantity = new HashMap<>();

        void add(Product p, int qty) { products.put(p.code, p); quantity.merge(p.code, qty, Integer::sum); }
        boolean available(String code) { return quantity.getOrDefault(code, 0) > 0; }
        Product get(String code) { return products.get(code); }
        void reduce(String code) { quantity.merge(code, -1, Integer::sum); }
    }

    // ---------- Strategy: making change ----------
    interface ChangeStrategy {
        // Returns coin denominations summing to 'amount', or null if not makeable.
        List<Integer> makeChange(int amount);
    }
    static class GreedyChange implements ChangeStrategy {
        private static final int[] COINS = {100, 50, 25, 10, 5, 1}; // cents
        public List<Integer> makeChange(int amount) {
            List<Integer> res = new ArrayList<>();
            for (int c : COINS) {
                while (amount >= c) { res.add(c); amount -= c; }
            }
            return amount == 0 ? res : null;
        }
    }

    // ---------- State pattern ----------
    interface VendingState {
        void selectProduct(VendingMachine m, String code);
        void insertMoney(VendingMachine m, int amount);
        void dispense(VendingMachine m);
    }

    // IDLE: only selecting a product is meaningful.
    static class IdleState implements VendingState {
        public void selectProduct(VendingMachine m, String code) {
            if (!m.inventory.available(code)) { System.out.println("  ! Out of stock: " + code); return; }
            m.selectedCode = code;
            m.setState(m.itemSelected);
            System.out.println("  Selected " + m.inventory.get(code).name +
                    " (price " + m.inventory.get(code).price + "). Please insert money.");
        }
        public void insertMoney(VendingMachine m, int a) { System.out.println("  ! Select a product first."); }
        public void dispense(VendingMachine m) { System.out.println("  ! Select a product first."); }
    }

    // ITEM_SELECTED / HAS_MONEY combined: accumulate money until enough.
    static class HasMoneyState implements VendingState {
        public void selectProduct(VendingMachine m, String code) { System.out.println("  ! Already selecting; finish or cancel."); }
        public void insertMoney(VendingMachine m, int amount) {
            m.balance += amount;
            int price = m.inventory.get(m.selectedCode).price;
            System.out.println("  Inserted " + amount + "; balance " + m.balance + "/" + price);
            if (m.balance >= price) System.out.println("  Enough money. You can dispense.");
        }
        public void dispense(VendingMachine m) {
            int price = m.inventory.get(m.selectedCode).price;
            if (m.balance < price) { System.out.println("  ! Insufficient money."); return; }
            m.setState(m.dispensing);
            m.dispense(); // delegate to dispensing state
        }
    }

    // DISPENSING: hand out item + change, reset to IDLE.
    static class DispensingState implements VendingState {
        public void selectProduct(VendingMachine m, String code) { System.out.println("  ! Busy dispensing."); }
        public void insertMoney(VendingMachine m, int a) { System.out.println("  ! Busy dispensing."); }
        public void dispense(VendingMachine m) {
            Product p = m.inventory.get(m.selectedCode);
            int change = m.balance - p.price;
            List<Integer> coins = change == 0 ? Collections.emptyList() : m.changeStrategy.makeChange(change);
            if (change > 0 && coins == null) {
                System.out.println("  ! Cannot make change; refunding " + m.balance);
            } else {
                m.inventory.reduce(p.code);
                System.out.println("  >> Dispensed " + p.name + (change > 0 ? "; change " + change + " " + coins : ""));
            }
            m.reset(); // back to IDLE
        }
    }

    // ---------- The machine (Context) ----------
    static class VendingMachine {
        final Inventory inventory = new Inventory();
        final ChangeStrategy changeStrategy = new GreedyChange();
        // pre-created state singletons
        final VendingState idle = new IdleState();
        final VendingState itemSelected = new HasMoneyState();
        final VendingState dispensing = new DispensingState();

        private VendingState state = idle;
        String selectedCode;
        int balance = 0;

        void setState(VendingState s) { this.state = s; }
        void reset() { state = idle; selectedCode = null; balance = 0; }

        // Public API delegates to whatever state we're in.
        void selectProduct(String code) { state.selectProduct(this, code); }
        void insertMoney(int amount)     { state.insertMoney(this, amount); }
        void dispense()                  { state.dispense(this); }
    }

    // ---------- Demo ----------
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
