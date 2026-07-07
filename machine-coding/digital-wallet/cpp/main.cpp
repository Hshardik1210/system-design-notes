// DIGITAL WALLET (Paytm/PhonePe) — double-entry ledger (C++17)
//
// Each transfer writes two balanced entries: a DEBIT on the sender and a CREDIT
// on the receiver, so the ledger always nets to zero (money conserved & auditable).
// Transfers lock the two accounts in a fixed order (by id) to avoid deadlock.

#include <iostream>
#include <string>
#include <vector>
#include <unordered_map>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <cstdio>
using namespace std;

enum class EntryType { DEBIT, CREDIT };
static const char* typeStr(EntryType t) { return t == EntryType::DEBIT ? "DEBIT" : "CREDIT"; }

struct LedgerEntry {
    string txnId, accountId; EntryType type; long long amount;
    string str() const { return txnId + " " + typeStr(type) + " " + to_string(amount) + " @" + accountId; }
};

struct Account {
    string id; long long balance; mutex mtx;
    Account(string i, long long opening) : id(move(i)), balance(opening) {}
    void apply(EntryType t, long long amt) { balance += (t == EntryType::CREDIT ? amt : -amt); }
};

class Wallet {
    unordered_map<string, shared_ptr<Account>> accounts;
    vector<LedgerEntry> ledger;
    long long txnSeq = 1;
public:
    shared_ptr<Account> open(const string& id, long long opening) {
        auto a = make_shared<Account>(id, opening);
        accounts[id] = a;
        return a;
    }

    bool transfer(const string& from, const string& to, long long amount) {
        if (amount <= 0) throw invalid_argument("amount must be positive");
        auto a = accounts.at(from), b = accounts.at(to);

        // Fixed lock order by id to prevent deadlock.
        Account* first = (from < to) ? a.get() : b.get();
        Account* second = (first == a.get()) ? b.get() : a.get();
        lock_guard<mutex> g1(first->mtx);
        lock_guard<mutex> g2(second->mtx);

        if (a->balance < amount) { cout << "  ! insufficient funds in " << from << "\n"; return false; }
        string txn = "TXN" + to_string(txnSeq++);
        a->apply(EntryType::DEBIT, amount);
        b->apply(EntryType::CREDIT, amount);
        ledger.push_back({txn, from, EntryType::DEBIT, amount});
        ledger.push_back({txn, to, EntryType::CREDIT, amount});
        printf("  %s: %lld %s -> %s\n", txn.c_str(), amount, from.c_str(), to.c_str());
        return true;
    }

    long long ledgerNet() const {
        long long net = 0;
        for (auto& e : ledger) net += (e.type == EntryType::CREDIT ? e.amount : -e.amount);
        return net;
    }
    const vector<LedgerEntry>& entries() const { return ledger; }
};

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
