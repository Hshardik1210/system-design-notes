import java.util.*;

/**
 * COFFEE VENDING MACHINE — recipe + ingredient management (concurrency-aware).
 *
 * WHAT THIS PROGRAM DOES:
 * A coffee machine that brews drinks (Espresso, Latte, ...) made from shared
 * INGREDIENTS (water, milk, coffee, sugar). Each drink has a "recipe" listing how
 * much of each ingredient it needs. A drink can only be served if every ingredient
 * has enough quantity; the machine then atomically deducts all of them.
 *
 * KEY CLASSES:
 *  - Inventory:     the shared stock of ingredients (thread-safe).
 *  - Beverage:      a drink defined purely by its name + recipe (data, no subclasses).
 *  - CoffeeMachine: holds the menu and brews drinks using the inventory.
 *
 * DESIGN PATTERNS / IDEAS:
 *  - Recipe-as-data: a drink is just a name + a map of ingredient amounts, so no
 *    separate subclass per drink is needed.
 *  - Thread-safe shared inventory: multiple outlets could brew in parallel, so all
 *    ingredient reads/writes are guarded by `synchronized`. The check-and-deduct is
 *    done in ONE critical section so two brews can't both pass the check and over-draw.
 */
public class Main {

    // Inventory: the shared ingredient stock (name -> quantity in ml/g).
    // Every method is `synchronized` so parallel brews can't corrupt the counts.
    static class Inventory {
        private final Map<String, Integer> stock = new HashMap<>();

        // Add `qty` more of an ingredient (creates the entry if new).
        synchronized void refill(String item, int qty) { stock.merge(item, qty, Integer::sum); }

        // Read-only check: does the stock currently satisfy the whole recipe?
        synchronized boolean has(Map<String, Integer> recipe) {
            for (Map.Entry<String, Integer> e : recipe.entrySet())
                // treat a missing ingredient as quantity 0
                if (stock.getOrDefault(e.getKey(), 0) < e.getValue()) return false;
            return true;
        }

        // Atomically deduct a recipe; returns the missing ingredient name, or null on success.
        // The check loop and the deduct loop run in the SAME synchronized method, so no other
        // brew can slip in between and cause the stock to go negative.
        synchronized String consume(Map<String, Integer> recipe) {
            // first pass: verify every ingredient has enough (abort early if not)
            for (Map.Entry<String, Integer> e : recipe.entrySet())
                if (stock.getOrDefault(e.getKey(), 0) < e.getValue()) return e.getKey();
            // second pass: now that all are confirmed, subtract each amount
            for (Map.Entry<String, Integer> e : recipe.entrySet())
                stock.merge(e.getKey(), -e.getValue(), Integer::sum);
            return null;
        }

        // Return a human-readable snapshot of current stock (for logging).
        synchronized String snapshot() { return stock.toString(); }
    }

    // Beverage: a drink described only by its name and recipe (ingredient -> amount).
    static class Beverage {
        final String name;
        final Map<String, Integer> recipe; // ingredient -> amount
        Beverage(String name, Map<String, Integer> recipe) { this.name = name; this.recipe = recipe; }
    }

    // CoffeeMachine: knows the menu of beverages and brews them from a shared Inventory.
    static class CoffeeMachine {
        private final Inventory inventory;
        // LinkedHashMap keeps the menu in insertion order (name -> beverage).
        private final Map<String, Beverage> menu = new LinkedHashMap<>();
        CoffeeMachine(Inventory inv) { this.inventory = inv; }

        // Register a beverage on the menu so it can be brewed by name.
        void addBeverage(Beverage b) { menu.put(b.name, b); }

        // Brew a drink if ingredients suffice; report the missing one otherwise.
        boolean brew(String name) {
            Beverage b = menu.get(name);
            if (b == null) { System.out.println("  ! no such beverage: " + name); return false; }
            // consume() both checks and deducts; a non-null result is the ingredient we lack
            String missing = inventory.consume(b.recipe);
            if (missing != null) {
                System.out.println("  ! cannot make " + name + " (not enough " + missing + ")");
                return false;
            }
            System.out.println("  >> served " + name);
            return true;
        }
    }

    // Demo: set up stock, add a menu, and brew a few drinks to show the behavior.
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
