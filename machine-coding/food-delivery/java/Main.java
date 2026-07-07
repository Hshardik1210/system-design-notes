import java.util.*;

/**
 * FOOD DELIVERY (Swiggy/Zomato core) — cart -> order -> assignment -> delivery.
 *
 * Models the 3-sided marketplace minimally:
 *   - Restaurant + Menu (items with price/availability).
 *   - Cart -> Order with an order state machine.
 *   - DeliveryPartner assignment (nearest free partner).
 *
 * Pattern: State (order lifecycle), simple nearest-partner assignment strategy.
 */
public class Main {

    static class MenuItem {
        final String id, name; final double price; boolean available = true;
        MenuItem(String id, String name, double price) { this.id = id; this.name = name; this.price = price; }
    }

    static class Restaurant {
        final String id, name;
        final Map<String, MenuItem> menu = new LinkedHashMap<>();
        Restaurant(String id, String name) { this.id = id; this.name = name; }
        void addItem(MenuItem m) { menu.put(m.id, m); }
    }

    static class Cart {
        final Restaurant restaurant;
        final Map<String, Integer> items = new LinkedHashMap<>(); // itemId -> qty
        Cart(Restaurant r) { this.restaurant = r; }
        void add(String itemId, int qty) {
            MenuItem m = restaurant.menu.get(itemId);
            if (m == null || !m.available) throw new IllegalArgumentException("item unavailable: " + itemId);
            items.merge(itemId, qty, Integer::sum);
        }
        double total() {
            double sum = 0;
            for (Map.Entry<String, Integer> e : items.entrySet())
                sum += restaurant.menu.get(e.getKey()).price * e.getValue();
            return sum;
        }
    }

    enum OrderState { PLACED, ACCEPTED, PREPARING, READY, OUT_FOR_DELIVERY, DELIVERED, CANCELLED }
    static class Order {
        final String id; final Cart cart; final double amount;
        OrderState state = OrderState.PLACED;
        DeliveryPartner partner;
        Order(String id, Cart cart) { this.id = id; this.cart = cart; this.amount = cart.total(); }
    }

    static class DeliveryPartner {
        final String id; boolean free = true;
        DeliveryPartner(String id) { this.id = id; }
    }

    static class FoodService {
        private final List<DeliveryPartner> partners = new ArrayList<>();
        private int orderSeq = 1;
        void addPartner(DeliveryPartner p) { partners.add(p); }

        Order placeOrder(Cart cart) {
            Order o = new Order("ORD" + orderSeq++, cart);
            System.out.printf("%s PLACED at %s, amount Rs %.2f%n", o.id, cart.restaurant.name, o.amount);
            return o;
        }

        // Only valid forward transitions are allowed.
        void advance(Order o, OrderState next) {
            if (!validTransition(o.state, next)) {
                System.out.println("  ! illegal transition " + o.state + " -> " + next);
                return;
            }
            o.state = next;
            System.out.println("  " + o.id + " -> " + next);
            if (next == OrderState.READY) assignPartner(o);
        }

        private void assignPartner(Order o) {
            for (DeliveryPartner p : partners) {
                if (p.free) { p.free = false; o.partner = p; System.out.println("  assigned partner " + p.id); return; }
            }
            System.out.println("  no free delivery partner yet");
        }

        private boolean validTransition(OrderState from, OrderState to) {
            switch (from) {
                case PLACED:           return to == OrderState.ACCEPTED || to == OrderState.CANCELLED;
                case ACCEPTED:         return to == OrderState.PREPARING || to == OrderState.CANCELLED;
                case PREPARING:        return to == OrderState.READY;
                case READY:            return to == OrderState.OUT_FOR_DELIVERY;
                case OUT_FOR_DELIVERY: return to == OrderState.DELIVERED;
                default:               return false;
            }
        }
    }

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
