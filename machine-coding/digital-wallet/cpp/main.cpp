// DIGITAL WALLET (Paytm/PhonePe) — double-entry ledger (C++17)
//
// Each transfer writes two balanced entries: a DEBIT on the sender and a CREDIT
// on the receiver, so the ledger always nets to zero (money conserved & auditable).
// Transfers lock the two accounts in a fixed order (by id) to avoid deadlock.
//
// WHAT THIS PROGRAM DOES (beginner overview):
//   - Opens a few accounts with opening balances.
//   - Moves money between them with transfer().
//   - Records each movement in a ledger so it can be audited and verified.
//
// KEY TYPES:
//   - EntryType  : whether a ledger line takes money out (DEBIT) or puts it in (CREDIT).
//   - LedgerEntry: one line in the ledger (the audit trail).
//   - Account    : an id, a balance, and its own mutex (lock) for thread safety.
//   - Wallet     : owns all accounts + the ledger, and performs transfers.
//
// DESIGN PATTERNS:
//   - Ledger / double-entry accounting: each transfer = one DEBIT + one CREDIT that
//     cancel out, so total money is always conserved (auditable and self-checking).
//   - Ordered locking: locks the two accounts in a fixed order (by id) to avoid deadlock.
//
// NOTE ON MONEY: amounts are whole units (long long, e.g. paise), never floats,
// so we never lose precision on money.

#include <iostream>
#include <string>
#include <vector>
#include <unordered_map>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <cstdio>
using namespace std;

// Direction of a ledger line: DEBIT removes money from an account, CREDIT adds it.
enum class EntryType { DEBIT, CREDIT };
// Helper to print an EntryType as text.
static const char* typeStr(EntryType t) { return t == EntryType::DEBIT ? "DEBIT" : "CREDIT"; }

// One line in the ledger — the permanent audit record of a money movement.
struct LedgerEntry {
    // txnId links the two halves (DEBIT + CREDIT) of the same transfer together.
    string txnId, accountId; EntryType type; long long amount;
    // Human-readable form used when printing the ledger at the end.
    string str() const { return txnId + " " + typeStr(type) + " " + to_string(amount) + " @" + accountId; }
};

// A single account: an id, its balance, and a mutex so transfers can lock it safely.
struct Account {
    string id; long long balance; mutex mtx;
    Account(string i, long long opening) : id(move(i)), balance(opening) {}
    // Update the balance: a CREDIT adds money, a DEBIT subtracts it.
    void apply(EntryType t, long long amt) { balance += (t == EntryType::CREDIT ? amt : -amt); }
};

// Central bookkeeper: holds every account, the shared ledger, and runs transfers.
class Wallet {
    unordered_map<string, shared_ptr<Account>> accounts;
    vector<LedgerEntry> ledger;
    long long txnSeq = 1; // next transaction number; increments on each transfer
public:
    // Create a new account with a starting balance and register it in the wallet.
    shared_ptr<Account> open(const string& id, long long opening) {
        auto a = make_shared<Account>(id, opening);
        accounts[id] = a;
        return a;
    }

    // Move `amount` from one account to another as a single all-or-nothing operation.
    // Either both ledger entries and both balance changes happen, or nothing does.
    bool transfer(const string& from, const string& to, long long amount) {
        if (amount <= 0) throw invalid_argument("amount must be positive");
        auto a = accounts.at(from), b = accounts.at(to);

        // Fixed lock order by id to prevent deadlock.
        // If every thread always locks the lower id first, two threads can never
        // hold one lock each and wait forever for the other.
        Account* first = (from < to) ? a.get() : b.get();
        Account* second = (first == a.get()) ? b.get() : a.get();
        lock_guard<mutex> g1(first->mtx);
        lock_guard<mutex> g2(second->mtx);

        // Double-spend / overdraft guard: reject before changing anything.
        if (a->balance < amount) { cout << "  ! insufficient funds in " << from << "\n"; return false; }
        string txn = "TXN" + to_string(txnSeq++);
        // Apply both balance changes together so money is neither created nor lost.
        a->apply(EntryType::DEBIT, amount);
        b->apply(EntryType::CREDIT, amount);
        // Record both halves of the transfer in the ledger under the same txn id.
        ledger.push_back({txn, from, EntryType::DEBIT, amount});
        ledger.push_back({txn, to, EntryType::CREDIT, amount});
        printf("  %s: %lld %s -> %s\n", txn.c_str(), amount, from.c_str(), to.c_str());
        return true;
    }

    // Invariant check: because every transfer adds equal DEBIT and CREDIT amounts,
    // a correct ledger must always sum to 0 — if it doesn't, money was created or lost.
    long long ledgerNet() const {
        long long net = 0;
        for (auto& e : ledger) net += (e.type == EntryType::CREDIT ? e.amount : -e.amount);
        return net;
    }
    const vector<LedgerEntry>& entries() const { return ledger; }
};

// Demo: open accounts, run a few transfers (including one that fails), then
// print balances and verify the ledger nets to zero.
int main() {
    Wallet w;
    auto alice = w.open("alice", 10000);
    auto bob   = w.open("bob", 0);
    auto carol = w.open("carol", 5000);

    w.transfer("alice", "bob", 4000);
    w.transfer("carol", "bob", 1500);
    w.transfer("bob", "carol", 1000);
    w.transfer("alice", "bob", 999999); // insufficient

    cout << "Balances: alice=" << alice->balance << " bob=" << bob->balance << " carol=" << carol->balance << "\n";
    cout << "Ledger net (must be 0): " << w.ledgerNet() << "\n";
    cout << "Ledger:\n";
    for (auto& e : w.entries()) cout << "  " << e.str() << "\n";
    return 0;
}
