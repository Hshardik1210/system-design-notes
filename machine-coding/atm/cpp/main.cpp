// ATM MACHINE — State pattern (C++17)
//
// States: IDLE -> CARD_INSERTED -> AUTHENTICATED -> (withdraw) -> IDLE.
// Each state permits only certain operations. Cash dispensing is greedy over
// available notes and checks both account balance and machine cash.

#include <iostream>
#include <string>
#include <map>
#include <unordered_map>
#include <memory>
#include <algorithm>
using namespace std;

struct Account { string cardNo; int pin; double balance; };

class CashDispenser {
    map<int, int, greater<int>> notes; // note -> count, descending
public:
    void load(int note, int count) { notes[note] += count; }
    int total() const { int t = 0; for (auto& [n, c] : notes) t += n * c; return t; }

    // Greedy plan; returns empty map + ok=false if exact amount impossible.
    bool dispense(int amount, map<int, int>& planOut) {
        map<int, int> plan;
        int remaining = amount;
        for (auto& [note, avail] : notes) {
            int need = min(remaining / note, avail);
            if (need > 0) { plan[note] = need; remaining -= need * note; }
        }
        if (remaining != 0) return false;
        for (auto& [note, cnt] : plan) notes[note] -= cnt;
        planOut = plan;
        return true;
    }
};

struct ATM; // fwd

struct ATMState {
    virtual ~ATMState() = default;
    virtual void insertCard(ATM& atm, const string& cardNo) = 0;
    virtual void enterPin(ATM& atm, int pin) = 0;
    virtual void withdraw(ATM& atm, int amount) = 0;
    virtual void ejectCard(ATM& atm) = 0;
};

struct ATM {
    unordered_map<string, Account> bank;
    CashDispenser dispenser;
    shared_ptr<ATMState> idle, cardInserted, authenticated, state;
    Account* currentAccount = nullptr;

    void setState(shared_ptr<ATMState> s) { state = move(s); }
    void reset();

    void insertCard(const string& c) { state->insertCard(*this, c); }
    void enterPin(int p)             { state->enterPin(*this, p); }
    void withdraw(int amt)           { state->withdraw(*this, amt); }
    void ejectCard()                 { state->ejectCard(*this); }
};

static string planStr(const map<int, int>& plan) {
    string s = "{";
    bool first = true;
    for (auto& [note, cnt] : plan) { if (!first) s += ", "; s += to_string(note) + "x" + to_string(cnt); first = false; }
    return s + "}";
}

struct IdleState : ATMState {
    void insertCard(ATM& atm, const string& cardNo) override {
        auto it = atm.bank.find(cardNo);
        if (it == atm.bank.end()) { cout << "  ! unknown card\n"; return; }
        atm.currentAccount = &it->second;
        atm.setState(atm.cardInserted);
        cout << "  card accepted; enter PIN\n";
    }
    void enterPin(ATM&, int) override { cout << "  ! insert card first\n"; }
    void withdraw(ATM&, int) override { cout << "  ! insert card first\n"; }
    void ejectCard(ATM&) override { cout << "  ! no card\n"; }
};

struct CardInsertedState : ATMState {
    void insertCard(ATM&, const string&) override { cout << "  ! card already inserted\n"; }
    void enterPin(ATM& atm, int pin) override {
        if (atm.currentAccount->pin != pin) { cout << "  ! wrong PIN; ejecting\n"; atm.reset(); return; }
        atm.setState(atm.authenticated);
        cout << "  PIN ok; authenticated\n";
    }
    void withdraw(ATM&, int) override { cout << "  ! enter PIN first\n"; }
    void ejectCard(ATM& atm) override { cout << "  card ejected\n"; atm.reset(); }
};

struct AuthenticatedState : ATMState {
    void insertCard(ATM&, const string&) override { cout << "  ! already in session\n"; }
    void enterPin(ATM&, int) override { cout << "  ! already authenticated\n"; }
    void withdraw(ATM& atm, int amount) override {
        Account* acc = atm.currentAccount;
        if (amount > acc->balance) { cout << "  ! insufficient account balance\n"; return; }
        if (amount > atm.dispenser.total()) { cout << "  ! ATM out of cash\n"; return; }
        map<int, int> plan;
        if (!atm.dispenser.dispense(amount, plan)) { cout << "  ! cannot dispense exact notes for " << amount << "\n"; return; }
        acc->balance -= amount;
        cout << "  dispensed " << planStr(plan) << "; new balance " << acc->balance << "\n";
    }
    void ejectCard(ATM& atm) override { cout << "  card ejected\n"; atm.reset(); }
};

void ATM::reset() { state = idle; currentAccount = nullptr; }

int main() {
    ATM atm;
    atm.idle = make_shared<IdleState>();
    atm.cardInserted = make_shared<CardInsertedState>();
    atm.authenticated = make_shared<AuthenticatedState>();
    atm.state = atm.idle;

    atm.bank["CARD-1"] = {"CARD-1", 1234, 5000};
    atm.dispenser.load(500, 5);
    atm.dispenser.load(100, 10);

    cout << "--- Wrong PIN ---\n";
    atm.insertCard("CARD-1");
    atm.enterPin(0);

    cout << "--- Correct flow ---\n";
    atm.insertCard("CARD-1");
    atm.enterPin(1234);
    atm.withdraw(1300); // 2x500 + 3x100
    atm.withdraw(50);   // impossible with 500/100
    atm.ejectCard();

    cout << "--- Op without card ---\n";
    atm.withdraw(100);
    return 0;
}
