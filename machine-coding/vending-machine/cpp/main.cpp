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
struct Product {
    string code, name;
    int price; // cents
    Product() : price(0) {}
    Product(string c, string n, int p) : code(move(c)), name(move(n)), price(p) {}
};

struct Inventory {
    unordered_map<string, Product> products;
    unordered_map<string, int> quantity;
    void add(const Product& p, int qty) { products[p.code] = p; quantity[p.code] += qty; }
    bool available(const string& code) const {
        auto it = quantity.find(code);
        return it != quantity.end() && it->second > 0;
    }
    Product get(const string& code) { return products[code]; }
    void reduce(const string& code) { quantity[code]--; }
};

// ---------- Strategy: change-making ----------
struct ChangeStrategy {
    virtual ~ChangeStrategy() = default;
    // Fills 'out' with coins summing to amount; returns false if not makeable.
    virtual bool makeChange(int amount, vector<int>& out) const = 0;
};
struct GreedyChange : ChangeStrategy {
    bool makeChange(int amount, vector<int>& out) const override {
        static const int COINS[] = {100, 50, 25, 10, 5, 1};
        for (int c : COINS) while (amount >= c) { out.push_back(c); amount -= c; }
        return amount == 0;
    }
};

struct VendingMachine; // fwd

// ---------- State interface ----------
struct VendingState {
    virtual ~VendingState() = default;
    virtual void selectProduct(VendingMachine& m, const string& code) = 0;
    virtual void insertMoney(VendingMachine& m, int amount) = 0;
    virtual void dispense(VendingMachine& m) = 0;
};

// ---------- Context ----------
struct VendingMachine {
    Inventory inventory;
    unique_ptr<ChangeStrategy> changeStrategy = make_unique<GreedyChange>();

    // state singletons (declared below main via pointers set in ctor)
    shared_ptr<VendingState> idle, itemSelected, dispensingState;
    shared_ptr<VendingState> state;

    string selectedCode;
    int balance = 0;

    void setState(shared_ptr<VendingState> s) { state = move(s); }
    void reset();

    void selectProduct(const string& code) { state->selectProduct(*this, code); }
    void insertMoney(int amount)            { state->insertMoney(*this, amount); }
    void dispense()                         { state->dispense(*this); }
};

// ---------- Concrete states ----------
struct IdleState : VendingState {
    void selectProduct(VendingMachine& m, const string& code) override {
        if (!m.inventory.available(code)) { cout << "  ! Out of stock: " << code << "\n"; return; }
        m.selectedCode = code;
        m.setState(m.itemSelected);
        Product p = m.inventory.get(code);
        cout << "  Selected " << p.name << " (price " << p.price << "). Please insert money.\n";
    }
    void insertMoney(VendingMachine&, int) override { cout << "  ! Select a product first.\n"; }
    void dispense(VendingMachine&) override { cout << "  ! Select a product first.\n"; }
};

struct HasMoneyState : VendingState {
    void selectProduct(VendingMachine&, const string&) override { cout << "  ! Already selecting.\n"; }
    void insertMoney(VendingMachine& m, int amount) override {
        m.balance += amount;
        int price = m.inventory.get(m.selectedCode).price;
        cout << "  Inserted " << amount << "; balance " << m.balance << "/" << price << "\n";
        if (m.balance >= price) cout << "  Enough money. You can dispense.\n";
    }
    void dispense(VendingMachine& m) override {
        int price = m.inventory.get(m.selectedCode).price;
        if (m.balance < price) { cout << "  ! Insufficient money.\n"; return; }
        m.setState(m.dispensingState);
        m.dispense();
    }
};

struct DispensingState : VendingState {
    void selectProduct(VendingMachine&, const string&) override { cout << "  ! Busy dispensing.\n"; }
    void insertMoney(VendingMachine&, int) override { cout << "  ! Busy dispensing.\n"; }
    void dispense(VendingMachine& m) override {
        Product p = m.inventory.get(m.selectedCode);
        int change = m.balance - p.price;
        vector<int> coins;
        bool ok = (change == 0) || m.changeStrategy->makeChange(change, coins);
        if (change > 0 && !ok) {
            cout << "  ! Cannot make change; refunding " << m.balance << "\n";
        } else {
            m.inventory.reduce(p.code);
            cout << "  >> Dispensed " << p.name;
            if (change > 0) {
                cout << "; change " << change << " [";
                for (size_t i = 0; i < coins.size(); ++i) cout << coins[i] << (i + 1 < coins.size() ? "," : "");
                cout << "]";
            }
            cout << "\n";
        }
        m.reset();
    }
};

void VendingMachine::reset() { state = idle; selectedCode.clear(); balance = 0; }

// ---------- Demo ----------
int main() {
    VendingMachine m;
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
