// ATM MACHINE — State pattern (C++17)
//
// WHAT THIS PROGRAM DOES:
//   Simulates an ATM as a small state machine. A user inserts a card, enters a
//   PIN, and withdraws cash. The ATM only allows operations valid in its current
//   state (e.g. you cannot withdraw before entering a PIN).
//
// STATES: IDLE -> CARD_INSERTED -> AUTHENTICATED -> (withdraw) -> IDLE.
//   Each state permits only certain operations; invalid ones are rejected.
//
// KEY TYPES:
//   - Account        : a customer's card, PIN and balance.
//   - CashDispenser  : the machine's physical cash and note-selection logic.
//   - ATMState       : the State-pattern base class (one virtual method per operation).
//   - IdleState / CardInsertedState / AuthenticatedState : the 3 concrete states.
//   - ATM            : the "context" that holds the current state and delegates to it.
//
// DESIGN PATTERNS:
//   - State pattern: each state is its own class, so the rules for what is
//     allowed live in that state instead of one big if/else block.
//   - Strategy (implicit): the greedy note selection could be swapped for a
//     smarter algorithm without changing the rest of the code.

#include <iostream>
#include <string>
#include <map>
#include <unordered_map>
#include <memory>
#include <algorithm>
using namespace std;

// A bank account tied to one card: its card number, PIN, and current balance.
struct Account { string cardNo; int pin; double balance; };

// The machine's physical cash and the logic to hand out exact notes.
class CashDispenser {
    map<int, int, greater<int>> notes; // note -> count, descending (largest note first)
public:
    // Add 'count' notes of value 'note' to the machine's inventory.
    void load(int note, int count) { notes[note] += count; }
    // Total cash held = sum of (note value * how many of that note).
    int total() const { int t = 0; for (auto& [n, c] : notes) t += n * c; return t; }

    // Greedy plan; returns empty map + ok=false if exact amount impossible.
    // "Greedy" = take as many of the largest note as possible, then the next, etc.
    bool dispense(int amount, map<int, int>& planOut) {
        map<int, int> plan;
        int remaining = amount;
        // Walk notes largest-first, taking as many of each as fit into 'remaining'.
        for (auto& [note, avail] : notes) {
            // Take the smaller of "how many fit" and "how many we actually have".
            int need = min(remaining / note, avail);
            if (need > 0) { plan[note] = need; remaining -= need * note; }
        }
        if (remaining != 0) return false; // leftover means exact amount impossible (e.g. 50 with only 500/100)
        // Only now (once we know it works) actually remove the chosen notes.
        for (auto& [note, cnt] : plan) notes[note] -= cnt;
        planOut = plan;
        return true;
    }
};

struct ATM; // fwd

// State-pattern base class: the common set of operations every state supports.
// Each concrete state overrides these to allow or reject the operation.
struct ATMState {
    virtual ~ATMState() = default;
    virtual void insertCard(ATM& atm, const string& cardNo) = 0;
    virtual void enterPin(ATM& atm, int pin) = 0;
    virtual void withdraw(ATM& atm, int amount) = 0;
    virtual void ejectCard(ATM& atm) = 0;
};

// The ATM itself ("context"). It owns the state objects and forwards every user
// action to whichever state is currently active (the heart of the State pattern).
struct ATM {
    unordered_map<string, Account> bank;   // known accounts, looked up by card number
    CashDispenser dispenser;               // the machine's cash
    shared_ptr<ATMState> idle, cardInserted, authenticated, state; // reusable state objects + the current one
    Account* currentAccount = nullptr;     // account of the card currently in use, if any

    void setState(shared_ptr<ATMState> s) { state = move(s); }
    void reset(); // return to IDLE with no card (defined below, after the states)

    // Public actions simply delegate to the current state, which decides what to do.
    void insertCard(const string& c) { state->insertCard(*this, c); }
    void enterPin(int p)             { state->enterPin(*this, p); }
    void withdraw(int amt)           { state->withdraw(*this, amt); }
    void ejectCard()                 { state->ejectCard(*this); }
};

// Helper: format a note plan like {500x2, 100x3} for printing.
static string planStr(const map<int, int>& plan) {
    string s = "{";
    bool first = true;
    for (auto& [note, cnt] : plan) { if (!first) s += ", "; s += to_string(note) + "x" + to_string(cnt); first = false; }
    return s + "}";
}

// State when no card is present. Only "insert card" does anything useful.
struct IdleState : ATMState {
    void insertCard(ATM& atm, const string& cardNo) override {
        auto it = atm.bank.find(cardNo);
        if (it == atm.bank.end()) { cout << "  ! unknown card\n"; return; }
        atm.currentAccount = &it->second;
        atm.setState(atm.cardInserted); // move IDLE -> CARD_INSERTED
        cout << "  card accepted; enter PIN\n";
    }
    // In IDLE these operations make no sense, so they are all rejected.
    void enterPin(ATM&, int) override { cout << "  ! insert card first\n"; }
    void withdraw(ATM&, int) override { cout << "  ! insert card first\n"; }
    void ejectCard(ATM&) override { cout << "  ! no card\n"; }
};

// State after a valid card is inserted but before the PIN is verified.
struct CardInsertedState : ATMState {
    void insertCard(ATM&, const string&) override { cout << "  ! card already inserted\n"; }
    void enterPin(ATM& atm, int pin) override {
        // Wrong PIN ends the session immediately (card ejected via reset()).
        if (atm.currentAccount->pin != pin) { cout << "  ! wrong PIN; ejecting\n"; atm.reset(); return; }
        atm.setState(atm.authenticated); // correct PIN: CARD_INSERTED -> AUTHENTICATED
        cout << "  PIN ok; authenticated\n";
    }
    void withdraw(ATM&, int) override { cout << "  ! enter PIN first\n"; }
    void ejectCard(ATM& atm) override { cout << "  card ejected\n"; atm.reset(); }
};

// State after a successful PIN. Only here is withdrawing money allowed.
struct AuthenticatedState : ATMState {
    void insertCard(ATM&, const string&) override { cout << "  ! already in session\n"; }
    void enterPin(ATM&, int) override { cout << "  ! already authenticated\n"; }
    // Withdraw must pass two checks: enough money in the account AND enough
    // machine cash that can be formed into exact notes.
    void withdraw(ATM& atm, int amount) override {
        Account* acc = atm.currentAccount;
        if (amount > acc->balance) { cout << "  ! insufficient account balance\n"; return; }        // check 1: account balance
        if (amount > atm.dispenser.total()) { cout << "  ! ATM out of cash\n"; return; }             // check 2a: machine has enough cash
        map<int, int> plan;
        if (!atm.dispenser.dispense(amount, plan)) { cout << "  ! cannot dispense exact notes for " << amount << "\n"; return; } // check 2b: exact notes possible
        acc->balance -= amount; // only deduct after cash was successfully reserved
        cout << "  dispensed " << planStr(plan) << "; new balance " << acc->balance << "\n";
    }
    void ejectCard(ATM& atm) override { cout << "  card ejected\n"; atm.reset(); }
};

// Return the ATM to its starting point: idle state, no card.
void ATM::reset() { state = idle; currentAccount = nullptr; }

// Demo driver: sets up one account and some cash, then runs a few scenarios.
int main() {
    ATM atm;
    // Create the three state objects once and start in IDLE.
    atm.idle = make_shared<IdleState>();
    atm.cardInserted = make_shared<CardInsertedState>();
    atm.authenticated = make_shared<AuthenticatedState>();
    atm.state = atm.idle;

    atm.bank["CARD-1"] = {"CARD-1", 1234, 5000};
    atm.dispenser.load(500, 5);   // 5 notes of 500
    atm.dispenser.load(100, 10);  // 10 notes of 100

    cout << "--- Wrong PIN ---\n";
    atm.insertCard("CARD-1");
    atm.enterPin(0); // wrong -> ejects

    cout << "--- Correct flow ---\n";
    atm.insertCard("CARD-1");
    atm.enterPin(1234);
    atm.withdraw(1300); // 2x500 + 3x100
    atm.withdraw(50);   // impossible with 500/100
    atm.ejectCard();

    cout << "--- Op without card ---\n";
    atm.withdraw(100); // rejected: no card inserted (IDLE state)
    return 0;
}
