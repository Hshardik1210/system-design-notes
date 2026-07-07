import java.util.*;

/**
 * FOOD DELIVERY (Swiggy/Zomato core) — cart -> order -> assignment -> delivery.
 *
 * Models the 3-sided marketplace minimally:
 *   - Restaurant + Menu (items with price/availability).
 *   - Cart -> Order with an order state machine.
 *   - DeliveryPartner assignment (nearest free partner).
 *
 * How to read this file (top-to-bottom):
 *   MenuItem/Restaurant/Menu  -> what a customer can order.
 *   Cart                      -> what the customer picked + running total.
 *   Order + OrderState        -> the order's lifecycle (state machine).
 *   DeliveryPartner           -> the rider who delivers the food.
 *   FoodService               -> the "brain" that places orders, moves them
 *                                through states, and assigns a rider.
 *
 * Design pattern used:
 *   - State pattern: an Order can only move along allowed transitions
 *     (e.g. PLACED -> ACCEPTED, never DELIVERED -> PREPARING). This prevents
 *     illegal jumps in the order lifecycle.
 */
public class Main {

    // A single dish on the menu: has an id, name, price, and whether it can be ordered right now.
    static class MenuItem {
        final String id, name; final double price; boolean available = true;
        MenuItem(String id, String name, double price) { this.id = id; this.name = name; this.price = price; }
    }

    // A restaurant that owns a menu (a map of itemId -> MenuItem, kept in insertion order).
    static class Restaurant {
        final String id, name;
        final Map<String, MenuItem> menu = new LinkedHashMap<>();
        Restaurant(String id, String name) { this.id = id; this.name = name; }
        // Register a dish so customers can add it to a cart.
        void addItem(MenuItem m) { menu.put(m.id, m); }
    }

    // The customer's shopping cart for one restaurant: which items and how many of each.
    static class Cart {
        final Restaurant restaurant;
        final Map<String, Integer> items = new LinkedHashMap<>(); // itemId -> qty
        Cart(Restaurant r) { this.restaurant = r; }
        // Add qty of an item; reject items that don't exist or are unavailable.
        void add(String itemId, int qty) {
            MenuItem m = restaurant.menu.get(itemId);
            if (m == null || !m.available) throw new IllegalArgumentException("item unavailable: " + itemId);
            items.merge(itemId, qty, Integer::sum); // if already in cart, add to the existing quantity
        }
        // Sum up (item price * quantity) across every line in the cart.
        double total() {
            double sum = 0;
            for (Map.Entry<String, Integer> e : items.entrySet())
                sum += restaurant.menu.get(e.getKey()).price * e.getValue();
            return sum;
        }
    }

    // The states an order can be in; also the allowed order of the lifecycle.
    enum OrderState { PLACED, ACCEPTED, PREPARING, READY, OUT_FOR_DELIVERY, DELIVERED, CANCELLED }

    // A placed order: snapshots the amount at creation time and tracks its current state and assigned rider.
    static class Order {
        final String id; final Cart cart; final double amount;
        OrderState state = OrderState.PLACED;
        DeliveryPartner partner;
        Order(String id, Cart cart) { this.id = id; this.cart = cart; this.amount = cart.total(); }
    }

    // A delivery rider; "free" means available to take a new order.
    static class DeliveryPartner {
        final String id; boolean free = true;
        DeliveryPartner(String id) { this.id = id; }
    }

    // The core service: holds the pool of riders and drives the order lifecycle.
    static class FoodService {
        private final List<DeliveryPartner> partners = new ArrayList<>();
        private int orderSeq = 1; // used to generate unique order ids (ORD1, ORD2, ...)
        // Add a rider to the available pool.
        void addPartner(DeliveryPartner p) { partners.add(p); }

        // Create a new order from a cart (starts in PLACED) and print a confirmation.
        Order placeOrder(Cart cart) {
            Order o = new Order("ORD" + orderSeq++, cart);
            System.out.printf("%s PLACED at %s, amount Rs %.2f%n", o.id, cart.restaurant.name, o.amount);
            return o;
        }

        // Move an order to the next state, but only if the jump is legal (the State pattern guard).
        void advance(Order o, OrderState next) {
            if (!validTransition(o.state, next)) {
                System.out.println("  ! illegal transition " + o.state + " -> " + next);
                return; // reject the change; the order stays in its current state
            }
            o.state = next;
            System.out.println("  " + o.id + " -> " + next);
            if (next == OrderState.READY) assignPartner(o); // food is ready -> find a rider now
        }

        // Assign the first free rider to the order (simple strategy; swap for nearest/least-loaded).
        private void assignPartner(Order o) {
            for (DeliveryPartner p : partners) {
                if (p.free) { p.free = false; o.partner = p; System.out.println("  assigned partner " + p.id); return; }
            }
            System.out.println("  no free delivery partner yet");
        }

        // The state machine rules: given the current state, which next states are allowed?
        private boolean validTransition(OrderState from, OrderState to) {
            switch (from) {
                case PLACED:           return to == OrderState.ACCEPTED || to == OrderState.CANCELLED;
                case ACCEPTED:         return to == OrderState.PREPARING || to == OrderState.CANCELLED;
                case PREPARING:        return to == OrderState.READY;
                case READY:            return to == OrderState.OUT_FOR_DELIVERY;
                case OUT_FOR_DELIVERY: return to == OrderState.DELIVERED;
                default:               return false; // DELIVERED and CANCELLED are terminal: no way out
            }
        }
    }

    // Demo: build a restaurant + menu, place an order, and walk it through its lifecycle.
    public static void main(String[] args) {
        Restaurant r = new Restaurant("R1", "Spice Hub");
        r.addItem(new MenuItem("M1", "Paneer Tikka", 220));
        r.addItem(new MenuItem("M2", "Butter Naan", 40));
        r.addItem(new MenuItem("M3", "Dal Makhani", 180));

        FoodService svc = new FoodService();
        svc.addPartner(new DeliveryPartner("P1"));

        Cart cart = new Cart(r);
        cart.add("M1", 1);
        cart.add("M2", 3);
        cart.add("M3", 1);

        Order o = svc.placeOrder(cart);
        svc.advance(o, OrderState.ACCEPTED);
        svc.advance(o, OrderState.PREPARING);
        svc.advance(o, OrderState.READY);            // triggers partner assignment
        svc.advance(o, OrderState.OUT_FOR_DELIVERY);
        svc.advance(o, OrderState.DELIVERED);
        svc.advance(o, OrderState.PREPARING);        // illegal after delivered
    }
}
