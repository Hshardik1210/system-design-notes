import java.util.*;

/**
 * COFFEE VENDING MACHINE — recipe + ingredient management (concurrency-aware).
 *
 * Difference from the snacks vending machine: a drink is made from shared
 * INGREDIENTS (water, milk, coffee, sugar). Serving a drink is only possible if
 * every ingredient has enough quantity; the machine atomically deducts them.
 *
 * Multiple outlets could brew in parallel, so ingredient updates are synchronized.
 *
 * Pattern: Recipe as data + Factory-ish selection; thread-safe inventory.
 */
public class Main {

    // Shared ingredient inventory (name -> quantity in ml/g).
    static class Inventory {
        private final Map<String, Integer> stock = new HashMap<>();
        synchronized void refill(String item, int qty) { stock.merge(item, qty, Integer::sum); }
        synchronized boolean has(Map<String, Integer> recipe) {
            for (Map.Entry<String, Integer> e : recipe.entrySet())
                if (stock.getOrDefault(e.getKey(), 0) < e.getValue()) return false;
            return true;
        }
        // Atomically deduct a recipe; returns the missing ingredient if any.
        synchronized String consume(Map<String, Integer> recipe) {
            for (Map.Entry<String, Integer> e : recipe.entrySet())
                if (stock.getOrDefault(e.getKey(), 0) < e.getValue()) return e.getKey();
            for (Map.Entry<String, Integer> e : recipe.entrySet())
                stock.merge(e.getKey(), -e.getValue(), Integer::sum);
            return null;
        }
        synchronized String snapshot() { return stock.toString(); }
    }

    static class Beverage {
        final String name;
        final Map<String, Integer> recipe; // ingredient -> amount
        Beverage(String name, Map<String, Integer> recipe) { this.name = name; this.recipe = recipe; }
    }

    static class CoffeeMachine {
        private final Inventory inventory;
        private final Map<String, Beverage> menu = new LinkedHashMap<>();
        CoffeeMachine(Inventory inv) { this.inventory = inv; }
        void addBeverage(Beverage b) { menu.put(b.name, b); }

        // Brew a drink if ingredients suffice; report the missing one otherwise.
        boolean brew(String name) {
            Beverage b = menu.get(name);
            if (b == null) { System.out.println("  ! no such beverage: " + name); return false; }
            String missing = inventory.consume(b.recipe);
            if (missing != null) {
                System.out.println("  ! cannot make " + name + " (not enough " + missing + ")");
                return false;
            }
            System.out.println("  >> served " + name);
            return true;
        }
    }

    public static void main(String[] args) {
        Inventory inv = new Inventory();
        inv.refill("water", 500);
        inv.refill("milk", 300);
        inv.refill("coffee", 60);
        inv.refill("sugar", 100);

        CoffeeMachine m = new CoffeeMachine(inv);
        m.addBeverage(new Beverage("Espresso", Map.of("water", 50, "coffee", 18)));
        m.addBeverage(new Beverage("Latte", Map.of("water", 50, "milk", 150, "coffee", 18, "sugar", 10)));

        System.out.println("Inventory: " + inv.snapshot());
        m.brew("Espresso");     // ok
        m.brew("Latte");        // ok
        m.brew("Latte");        // maybe milk runs low
        System.out.println("Inventory: " + inv.snapshot());
        m.brew("Cappuccino");   // not on menu
        inv.refill("milk", 200);
        m.brew("Latte");        // ok again after refill
    }
}
