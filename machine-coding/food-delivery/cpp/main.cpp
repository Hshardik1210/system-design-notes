// FOOD DELIVERY (Swiggy/Zomato core) (C++17)
//
// cart -> order -> assignment -> delivery, with an order state machine and
// nearest-free delivery-partner assignment.
//
// How to read this file (top-to-bottom):
//   MenuItem/Restaurant  -> what a customer can order.
//   Cart                 -> what the customer picked + running total.
//   OrderState + Order   -> the order's lifecycle (state machine).
//   DeliveryPartner      -> the rider who delivers the food.
//   FoodService          -> the "brain": places orders, advances states, assigns a rider.
//
// Design pattern used:
//   - State pattern: an Order can only move along allowed transitions
//     (e.g. PLACED -> ACCEPTED, never DELIVERED -> PREPARING), preventing
//     illegal jumps in the order lifecycle.

#include <iostream>
#include <string>
#include <vector>
#include <map>
#include <cstdio>
using namespace std;

// A single dish on the menu: id, name, price, and whether it can be ordered now.
struct MenuItem { string id, name; double price; bool available = true; };

// A restaurant that owns a menu (itemId -> MenuItem, kept sorted by key since it's a std::map).
struct Restaurant {
    string id, name;
    map<string, MenuItem> menu; // ordered
    // Register a dish so customers can add it to a cart.
    void addItem(const MenuItem& m) { menu[m.id] = m; }
};

// The customer's shopping cart for one restaurant: which items and how many of each.
struct Cart {
    Restaurant* restaurant;
    map<string, int> items; // itemId -> qty
    explicit Cart(Restaurant* r) : restaurant(r) {}
    // Add qty of an item; reject items that don't exist or are unavailable.
    void add(const string& itemId, int qty) {
        auto it = restaurant->menu.find(itemId);
        if (it == restaurant->menu.end() || !it->second.available)
            throw invalid_argument("item unavailable: " + itemId);
        items[itemId] += qty; // if already in cart, add to the existing quantity
    }
    // Sum up (item price * quantity) across every line in the cart.
    double total() const {
        double sum = 0;
        for (auto& [id, qty] : items) sum += restaurant->menu.at(id).price * qty;
        return sum;
    }
};

// The states an order can be in; also the allowed order of the lifecycle.
enum class OrderState { PLACED, ACCEPTED, PREPARING, READY, OUT_FOR_DELIVERY, DELIVERED, CANCELLED };

// Helper to turn an OrderState enum into a human-readable string for printing.
static string stateStr(OrderState s) {
    switch (s) {
        case OrderState::PLACED: return "PLACED";
        case OrderState::ACCEPTED: return "ACCEPTED";
        case OrderState::PREPARING: return "PREPARING";
        case OrderState::READY: return "READY";
        case OrderState::OUT_FOR_DELIVERY: return "OUT_FOR_DELIVERY";
        case OrderState::DELIVERED: return "DELIVERED";
        default: return "CANCELLED";
    }
}

// A delivery rider; "free" means available to take a new order.
struct DeliveryPartner { string id; bool free = true; };

// A placed order: snapshots the amount at creation and tracks its state and assigned rider.
struct Order {
    string id; Cart cart; double amount;
    OrderState state = OrderState::PLACED;
    DeliveryPartner* partner = nullptr;
    // amount is computed once from the cart total at construction time.
    Order(string i, Cart c) : id(move(i)), cart(move(c)), amount(cart.total()) {}
};

// The core service: holds the pool of riders and drives the order lifecycle.
class FoodService {
    vector<DeliveryPartner> partners;
    int orderSeq = 1; // used to generate unique order ids (ORD1, ORD2, ...)

    // The state machine rules: given the current state, which next states are allowed?
    bool validTransition(OrderState from, OrderState to) {
        switch (from) {
            case OrderState::PLACED:           return to == OrderState::ACCEPTED || to == OrderState::CANCELLED;
            case OrderState::ACCEPTED:         return to == OrderState::PREPARING || to == OrderState::CANCELLED;
            case OrderState::PREPARING:        return to == OrderState::READY;
            case OrderState::READY:            return to == OrderState::OUT_FOR_DELIVERY;
            case OrderState::OUT_FOR_DELIVERY: return to == OrderState::DELIVERED;
            default:                           return false; // DELIVERED and CANCELLED are terminal
        }
    }
    // Assign the first free rider to the order (simple strategy; swap for nearest/least-loaded).
    void assignPartner(Order& o) {
        for (auto& p : partners)
            if (p.free) { p.free = false; o.partner = &p; cout << "  assigned partner " << p.id << "\n"; return; }
        cout << "  no free delivery partner yet\n";
    }
public:
    // Add a rider to the available pool.
    void addPartner(const DeliveryPartner& p) { partners.push_back(p); }

    // Create a new order from a cart (starts in PLACED) and print a confirmation.
    Order placeOrder(const Cart& cart) {
        Order o("ORD" + to_string(orderSeq++), cart);
        printf("%s PLACED at %s, amount Rs %.2f\n", o.id.c_str(), cart.restaurant->name.c_str(), o.amount);
        return o;
    }
    // Move an order to the next state, but only if the jump is legal (the State pattern guard).
    void advance(Order& o, OrderState next) {
        if (!validTransition(o.state, next)) {
            cout << "  ! illegal transition " << stateStr(o.state) << " -> " << stateStr(next) << "\n";
            return; // reject the change; the order stays in its current state
        }
        o.state = next;
        cout << "  " << o.id << " -> " << stateStr(next) << "\n";
        if (next == OrderState::READY) assignPartner(o); // food is ready -> find a rider now
    }
};

// Demo: build a restaurant + menu, place an order, and walk it through its lifecycle.
int main() {
    Restaurant r{"R1", "Spice Hub", {}};
    r.addItem({"M1", "Paneer Tikka", 220});
    r.addItem({"M2", "Butter Naan", 40});
    r.addItem({"M3", "Dal Makhani", 180});

    FoodService svc;
    svc.addPartner({"P1", true});

    Cart cart(&r);
    cart.add("M1", 1);
    cart.add("M2", 3);
    cart.add("M3", 1);

    Order o = svc.placeOrder(cart);
    svc.advance(o, OrderState::ACCEPTED);
    svc.advance(o, OrderState::PREPARING);
    svc.advance(o, OrderState::READY);
    svc.advance(o, OrderState::OUT_FOR_DELIVERY);
    svc.advance(o, OrderState::DELIVERED);
    svc.advance(o, OrderState::PREPARING); // illegal
    return 0;
}
