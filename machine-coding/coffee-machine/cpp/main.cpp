// COFFEE VENDING MACHINE — recipe + ingredient management (C++17)
//
// A drink is made from shared ingredients. Serving deducts them atomically; if
// any ingredient is short, the drink is refused and the missing one reported.
// Ingredient updates are mutex-guarded (outlets could brew in parallel).

#include <iostream>
#include <string>
#include <map>
#include <unordered_map>
#include <mutex>
using namespace std;

class Inventory {
    unordered_map<string, int> stock;
    mutex mtx;
public:
    void refill(const string& item, int qty) { lock_guard<mutex> g(mtx); stock[item] += qty; }

    // Atomically deduct a recipe; returns missing ingredient name or "" on success.
    string consume(const map<string, int>& recipe) {
        lock_guard<mutex> g(mtx);
        for (auto& [item, need] : recipe)
            if (stock[item] < need) return item;
        for (auto& [item, need] : recipe) stock[item] -= need;
        return "";
    }
    string snapshot() {
        lock_guard<mutex> g(mtx);
        string s = "{";
        bool first = true;
        for (auto& [k, v] : stock) { if (!first) s += ", "; s += k + "=" + to_string(v); first = false; }
        return s + "}";
    }
};

struct Beverage {
    string name;
    map<string, int> recipe;
};

class CoffeeMachine {
    Inventory& inventory;
    map<string, Beverage> menu;
public:
    explicit CoffeeMachine(Inventory& inv) : inventory(inv) {}
    void addBeverage(const Beverage& b) { menu[b.name] = b; }

    bool brew(const string& name) {
        auto it = menu.find(name);
        if (it == menu.end()) { cout << "  ! no such beverage: " << name << "\n"; return false; }
        string missing = inventory.consume(it->second.recipe);
        if (!missing.empty()) {
            cout << "  ! cannot make " << name << " (not enough " << missing << ")\n";
            return false;
        }
        cout << "  >> served " << name << "\n";
        return true;
    }
};

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
