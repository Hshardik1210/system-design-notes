// FOOD DELIVERY (Swiggy/Zomato core) (C++17)
//
// cart -> order -> assignment -> delivery, with an order state machine and
// nearest-free delivery-partner assignment.

#include <iostream>
#include <string>
#include <vector>
#include <map>
#include <cstdio>
using namespace std;

struct MenuItem { string id, name; double price; bool available = true; };

struct Restaurant {
    string id, name;
    map<string, MenuItem> menu; // ordered
    void addItem(const MenuItem& m) { menu[m.id] = m; }
};

struct Cart {
    Restaurant* restaurant;
    map<string, int> items; // itemId -> qty
    explicit Cart(Restaurant* r) : restaurant(r) {}
    void add(const string& itemId, int qty) {
        auto it = restaurant->menu.find(itemId);
        if (it == restaurant->menu.end() || !it->second.available)
            throw invalid_argument("item unavailable: " + itemId);
        items[itemId] += qty;
    }
    double total() const {
        double sum = 0;
        for (auto& [id, qty] : items) sum += restaurant->menu.at(id).price * qty;
        return sum;
    }
};

enum class OrderState { PLACED, ACCEPTED, PREPARING, READY, OUT_FOR_DELIVERY, DELIVERED, CANCELLED };
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

struct DeliveryPartner { string id; bool free = true; };

struct Order {
    string id; Cart cart; double amount;
    OrderState state = OrderState::PLACED;
    DeliveryPartner* partner = nullptr;
    Order(string i, Cart c) : id(move(i)), cart(move(c)), amount(cart.total()) {}
};

class FoodService {
    vector<DeliveryPartner> partners;
    int orderSeq = 1;

    bool validTransition(OrderState from, OrderState to) {
        switch (from) {
            case OrderState::PLACED:           return to == OrderState::ACCEPTED || to == OrderState::CANCELLED;
            case OrderState::ACCEPTED:         return to == OrderState::PREPARING || to == OrderState::CANCELLED;
            case OrderState::PREPARING:        return to == OrderState::READY;
            case OrderState::READY:            return to == OrderState::OUT_FOR_DELIVERY;
            case OrderState::OUT_FOR_DELIVERY: return to == OrderState::DELIVERED;
            default:                           return false;
        }
    }
    void assignPartner(Order& o) {
        for (auto& p : partners)
            if (p.free) { p.free = false; o.partner = &p; cout << "  assigned partner " << p.id << "\n"; return; }
        cout << "  no free delivery partner yet\n";
    }
public:
    void addPartner(const DeliveryPartner& p) { partners.push_back(p); }

    Order placeOrder(const Cart& cart) {
        Order o("ORD" + to_string(orderSeq++), cart);
        printf("%s PLACED at %s, amount Rs %.2f\n", o.id.c_str(), cart.restaurant->name.c_str(), o.amount);
        return o;
    }
    void advance(Order& o, OrderState next) {
        if (!validTransition(o.state, next)) {
            cout << "  ! illegal transition " << stateStr(o.state) << " -> " << stateStr(next) << "\n";
            return;
        }
        o.state = next;
        cout << "  " << o.id << " -> " << stateStr(next) << "\n";
        if (next == OrderState::READY) assignPartner(o);
    }
};

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
