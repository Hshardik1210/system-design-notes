// VENDING MACHINE — the canonical STATE pattern (C++17)
//
// Each state is its own class implementing VendingState. The machine (context)
// forwards user actions to the current state, and states decide the transitions.
//
// States: IDLE -> ITEM_SELECTED/HAS_MONEY -> DISPENSING -> IDLE
// Patterns: State (behaviour by state), Strategy (greedy change-making).

#include <iostream>
#include <string>
#include <vector>
#include <unordered_map>
#include <memory>
#include <stdexcept>
using namespace std;

// ---------- Inventory ----------
// A single sellable item: its code (e.g. "A1"), display name, and price in cents.
struct Product {
    string code, name;
    int price; // cents
    Product() : price(0) {}                                              // default (needed by the map)
    Product(string c, string n, int p) : code(move(c)), name(move(n)), price(p) {}
};

// Tracks which products exist and how many of each remain in stock.
struct Inventory {
    unordered_map<string, Product> products;   // code -> product
    unordered_map<string, int> quantity;       // code -> units left
    // Register a product and add to its stock count.
    void add(const Product& p, int qty) { products[p.code] = p; quantity[p.code] += qty; }
    // True only if the product exists and at least one unit is left.
    bool available(const string& code) const {
        auto it = quantity.find(code);
        return it != quantity.end() && it->second > 0;
    }
    Product get(const string& code) { return products[code]; }
    // Called after a successful sale: decrease stock by one.
    void reduce(const string& code) { quantity[code]--; }
};

// ---------- Strategy: change-making ----------
// Strategy pattern: an interchangeable algorithm for computing coin change.
struct ChangeStrategy {
    virtual ~ChangeStrategy() = default;
    // Fills 'out' with coins summing to amount; returns false if not makeable.
    virtual bool makeChange(int amount, vector<int>& out) const = 0;
};
// Greedy implementation: always take the largest coin that still fits.
struct GreedyChange : ChangeStrategy {
    bool makeChange(int amount, vector<int>& out) const override {
        static const int COINS[] = {100, 50, 25, 10, 5, 1};   // denominations, largest first
        // For each coin, take as many as possible before moving to a smaller one.
        for (int c : COINS) while (amount >= c) { out.push_back(c); amount -= c; }
        return amount == 0;   // reached 0 exactly => change was makeable
    }
};

struct VendingMachine; // fwd

// ---------- State interface ----------
// The State contract: each state handles all three actions and receives the machine
// so it can read/change data and switch to the next state.
struct VendingState {
    virtual ~VendingState() = default;
    virtual void selectProduct(VendingMachine& m, const string& code) = 0;
    virtual void insertMoney(VendingMachine& m, int amount) = 0;
    virtual void dispense(VendingMachine& m) = 0;
};

// ---------- Context ----------
// Holds shared data (inventory, balance, selection) plus the current state,
// and delegates every user action to whatever state is active.
struct VendingMachine {
    Inventory inventory;
    unique_ptr<ChangeStrategy> changeStrategy = make_unique<GreedyChange>();

    // state singletons (declared below main via pointers set in ctor)
    shared_ptr<VendingState> idle, itemSelected, dispensingState;
    shared_ptr<VendingState> state;   // the state currently in control

    string selectedCode;   // product the user picked (empty when idle)
    int balance = 0;       // money inserted so far, in cents

    // How states move the machine to the next state.
    void setState(shared_ptr<VendingState> s) { state = move(s); }
    void reset();   // return to IDLE (defined after the states are known)

    // Public API delegates to the active state (the heart of the State pattern).
    void selectProduct(const string& code) { state->selectProduct(*this, code); }
    void insertMoney(int amount)            { state->insertMoney(*this, amount); }
    void dispense()                         { state->dispense(*this); }
};

// ---------- Concrete states ----------
// IDLE state: waiting for a selection; only selecting a product is meaningful here.
struct IdleState : VendingState {
    // Pick a product: reject if out of stock, else remember it and move to the money state.
    void selectProduct(VendingMachine& m, const string& code) override {
        if (!m.inventory.available(code)) { cout << "  ! Out of stock: " << code << "\n"; return; }
        m.selectedCode = code;
        m.setState(m.itemSelected);   // transition IDLE -> ITEM_SELECTED/HAS_MONEY
        Product p = m.inventory.get(code);
        cout << "  Selected " << p.name << " (price " << p.price << "). Please insert money.\n";
    }
    // These actions make no sense while idle, so just warn (no state change).
    void insertMoney(VendingMachine&, int) override { cout << "  ! Select a product first.\n"; }
    void dispense(VendingMachine&) override { cout << "  ! Select a product first.\n"; }
};

// ITEM_SELECTED / HAS_MONEY combined: accumulate money until there is enough.
struct HasMoneyState : VendingState {
    // Can't re-select while a purchase is in progress.
    void selectProduct(VendingMachine&, const string&) override { cout << "  ! Already selecting.\n"; }
    // Add inserted coins to the running balance and note when enough is in.
    void insertMoney(VendingMachine& m, int amount) override {
        m.balance += amount;
        int price = m.inventory.get(m.selectedCode).price;
        cout << "  Inserted " << amount << "; balance " << m.balance << "/" << price << "\n";
        if (m.balance >= price) cout << "  Enough money. You can dispense.\n";
    }
    // Only dispense once enough money is in, then hand off to the dispensing state.
    void dispense(VendingMachine& m) override {
        int price = m.inventory.get(m.selectedCode).price;
        if (m.balance < price) { cout << "  ! Insufficient money.\n"; return; }
        m.setState(m.dispensingState);   // transition -> DISPENSING
        m.dispense();
    }
};

// DISPENSING state: hand out item + change, then reset back to IDLE.
struct DispensingState : VendingState {
    // While busy dispensing, ignore other actions with a warning.
    void selectProduct(VendingMachine&, const string&) override { cout << "  ! Busy dispensing.\n"; }
    void insertMoney(VendingMachine&, int) override { cout << "  ! Busy dispensing.\n"; }
    // Compute change, try to make it, dispense (or refund), then return to IDLE.
    void dispense(VendingMachine& m) override {
        Product p = m.inventory.get(m.selectedCode);
        int change = m.balance - p.price;                 // overpaid amount to return
        vector<int> coins;
        // No change needed counts as OK; otherwise the strategy tries to break it into coins.
        bool ok = (change == 0) || m.changeStrategy->makeChange(change, coins);
        if (change > 0 && !ok) {
            // Coins couldn't be made: refund everything instead of selling.
            cout << "  ! Cannot make change; refunding " << m.balance << "\n";
        } else {
            m.inventory.reduce(p.code);   // sale succeeds: drop stock by one
            cout << "  >> Dispensed " << p.name;
            if (change > 0) {
                cout << "; change " << change << " [";
                // Print coins comma-separated (no trailing comma after the last one).
                for (size_t i = 0; i < coins.size(); ++i) cout << coins[i] << (i + 1 < coins.size() ? "," : "");
                cout << "]";
            }
            cout << "\n";
        }
        m.reset();   // back to IDLE
    }
};

// Defined here (not inline) because it needs 'idle', which is only assigned in main.
void VendingMachine::reset() { state = idle; selectedCode.clear(); balance = 0; }

// ---------- Demo ----------
// Drives the machine through a few scenarios to show each state's behaviour.
int main() {
    VendingMachine m;
    // Create one instance of each state and start the machine in IDLE.
    m.idle = make_shared<IdleState>();
    m.itemSelected = make_shared<HasMoneyState>();
    m.dispensingState = make_shared<DispensingState>();
    m.state = m.idle;

    m.inventory.add(Product("A1", "Coke", 75), 2);
    m.inventory.add(Product("A2", "Water", 50), 1);

    cout << "--- Buy Coke with extra money ---\n";
    m.selectProduct("A1");
    m.insertMoney(50);
    m.insertMoney(50);
    m.dispense();

    cout << "--- Try dispense with no selection ---\n";
    m.dispense();

    cout << "--- Buy Water ---\n";
    m.selectProduct("A2");
    m.insertMoney(50);
    m.dispense();

    cout << "--- Water now out of stock ---\n";
    m.selectProduct("A2");
    return 0;
}
