// COFFEE VENDING MACHINE — recipe + ingredient management (C++17)
//
// WHAT THIS PROGRAM DOES:
// A coffee machine that brews drinks made from shared INGREDIENTS (water, milk,
// coffee, sugar). Each drink has a "recipe" (ingredient -> amount). Serving a drink
// deducts all its ingredients atomically; if any ingredient is short, the drink is
// refused and the missing one is reported.
//
// KEY CLASSES:
//   - Inventory:     the shared stock of ingredients (mutex-guarded / thread-safe).
//   - Beverage:      a drink defined by its name + recipe (plain data, no subclasses).
//   - CoffeeMachine: holds the menu and brews drinks using the inventory.
//
// DESIGN IDEAS:
//   - Recipe-as-data: a drink is just a name + map of ingredient amounts.
//   - Thread-safe shared inventory: outlets could brew in parallel, so all stock
//     access is guarded by std::mutex. consume() checks and deducts inside ONE lock
//     so two brews can't both pass the check and over-draw stock.

#include <iostream>
#include <string>
#include <map>
#include <unordered_map>
#include <mutex>
using namespace std;

// Inventory: the shared ingredient stock (name -> quantity). A mutex protects it
// so concurrent brews cannot corrupt the counts.
class Inventory {
    unordered_map<string, int> stock;
    mutex mtx;
public:
    // Add `qty` more of an ingredient (creates the entry if new).
    void refill(const string& item, int qty) { lock_guard<mutex> g(mtx); stock[item] += qty; }

    // Atomically deduct a recipe; returns missing ingredient name or "" on success.
    // The whole method holds one lock, so the check and the deduct happen together.
    string consume(const map<string, int>& recipe) {
        lock_guard<mutex> g(mtx);
        // first pass: verify every ingredient has enough (return the short one if not)
        for (auto& [item, need] : recipe)
            if (stock[item] < need) return item;
        // second pass: all confirmed, so subtract each amount
        for (auto& [item, need] : recipe) stock[item] -= need;
        return "";
    }
    // Build a human-readable snapshot string of current stock (for logging).
    string snapshot() {
        lock_guard<mutex> g(mtx);
        string s = "{";
        bool first = true;
        for (auto& [k, v] : stock) { if (!first) s += ", "; s += k + "=" + to_string(v); first = false; }
        return s + "}";
    }
};

// Beverage: a drink described only by its name and recipe (ingredient -> amount).
struct Beverage {
    string name;
    map<string, int> recipe;
};

// CoffeeMachine: knows the menu of beverages and brews them from a shared Inventory.
class CoffeeMachine {
    Inventory& inventory;          // reference to the shared inventory (not owned here)
    map<string, Beverage> menu;    // name -> beverage
public:
    explicit CoffeeMachine(Inventory& inv) : inventory(inv) {}

    // Register a beverage on the menu so it can be brewed by name.
    void addBeverage(const Beverage& b) { menu[b.name] = b; }

    // Brew a drink if ingredients suffice; report the missing one otherwise.
    bool brew(const string& name) {
        auto it = menu.find(name);
        if (it == menu.end()) { cout << "  ! no such beverage: " << name << "\n"; return false; }
        // consume() both checks and deducts; a non-empty result is the ingredient we lack
        string missing = inventory.consume(it->second.recipe);
        if (!missing.empty()) {
            cout << "  ! cannot make " << name << " (not enough " << missing << ")\n";
            return false;
        }
        cout << "  >> served " << name << "\n";
        return true;
    }
};

// Demo: set up stock, add a menu, and brew a few drinks to show the behavior.
int main() {
    Inventory inv;
    inv.refill("water", 500);
    inv.refill("milk", 300);
    inv.refill("coffee", 60);
    inv.refill("sugar", 100);

    CoffeeMachine m(inv);
    m.addBeverage({"Espresso", {{"water", 50}, {"coffee", 18}}});
    m.addBeverage({"Latte", {{"water", 50}, {"milk", 150}, {"coffee", 18}, {"sugar", 10}}});

    cout << "Inventory: " << inv.snapshot() << "\n";
    m.brew("Espresso");
    m.brew("Latte");
    m.brew("Latte");
    cout << "Inventory: " << inv.snapshot() << "\n";
    m.brew("Cappuccino");
    inv.refill("milk", 200);
    m.brew("Latte");
    return 0;
}
