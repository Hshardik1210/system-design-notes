// IN-MEMORY KEY-VALUE STORE with nested transactions (C++17)
//
// WHAT THIS PROGRAM DOES (beginner overview):
//   A tiny in-memory database (an unordered_map) that supports set/get/delete
//   and transactions (BEGIN/COMMIT/ROLLBACK) which can be nested.
//
// set/get/delete + BEGIN/COMMIT/ROLLBACK (nestable).
// Each open transaction keeps an undo log: key -> previous value (optional; empty
// optional means "key was absent before"). Only the first prior value per key is
// stored. ROLLBACK restores from the log; COMMIT merges into the parent (or makes
// changes permanent at the outermost level).
//
// KEY CLASS:
//   - KVStore : the store plus the transaction/undo-log engine.
//
// DESIGN PATTERN USED:
//   - "Undo log" / Memento-style approach: rather than copying the whole store on
//     each BEGIN, a transaction records only the OLD value of the keys it changes,
//     so both set and get stay O(1). std::optional distinguishes "had an old
//     value" from "did not exist before".
//   - A stack (vector used as LIFO) of undo logs mirrors transaction nesting.

#include <iostream>
#include <string>
#include <unordered_map>
#include <vector>
#include <optional>
using namespace std;

// KVStore: the live data plus a stack of undo logs for nested transactions.
class KVStore {
    unordered_map<string, string> committed; // the live, current data
    // Stack of undo logs; optional<string> empty => key was absent before.
    // Back of the vector = the innermost (most recent) transaction.
    vector<unordered_map<string, optional<string>>> txStack;

    // Save the current value of 'key' into the innermost transaction's undo log,
    // but only the first time the key is touched (so we keep the pre-transaction
    // value). nullopt records "the key did not exist before".
    void recordUndo(const string& key) {
        if (txStack.empty()) return; // not in a transaction: nothing to undo later
        auto& undo = txStack.back();
        if (!undo.count(key)) {
            auto it = committed.find(key);
            undo[key] = (it != committed.end()) ? optional<string>(it->second) : nullopt;
        }
    }
public:
    // Read a key; returns "NULL" when the key is not present.
    string get(const string& key) {
        auto it = committed.find(key);
        return it != committed.end() ? it->second : "NULL";
    }
    // Write/overwrite: record the old value first, then update the live map.
    void set(const string& key, const string& value) { recordUndo(key); committed[key] = value; }
    // Delete: record the old value first, then erase from the live map.
    void del(const string& key) { recordUndo(key); committed.erase(key); }

    // Start a transaction by pushing a fresh, empty undo log.
    void begin() { txStack.emplace_back(); }

    // Undo all changes since the matching BEGIN, then discard that undo log.
    bool rollback() {
        if (txStack.empty()) { cout << "  ! no transaction\n"; return false; }
        auto undo = txStack.back(); txStack.pop_back();
        for (auto& [key, prev] : undo) {
            if (prev.has_value()) committed[key] = *prev; // restore old value
            else committed.erase(key);                    // key was absent before -> remove
        }
        return true;
    }

    // Accept the innermost transaction. If nested, hand its undo info to the
    // parent; otherwise the changes are already live and become permanent.
    bool commit() {
        if (txStack.empty()) { cout << "  ! no transaction\n"; return false; }
        auto undo = txStack.back(); txStack.pop_back();
        if (!txStack.empty()) {
            auto& parent = txStack.back();
            for (auto& [key, prev] : undo)
                if (!parent.count(key)) parent[key] = prev; // don't overwrite parent's earlier record
        }
        return true;
    }
};

// Demo driver: runs set/get plus a nested BEGIN/ROLLBACK/COMMIT sequence,
// printing values at each step so the transaction behavior is easy to trace.
int main() {
    KVStore db;

    db.set("a", "1");
    cout << "get a           = " << db.get("a") << "\n"; // 1

    db.begin();            // TX1
    db.set("a", "2");
    cout << "get a (in TX1)  = " << db.get("a") << "\n"; // 2
    db.begin();            // TX2 nested
    db.set("a", "3");
    db.set("b", "9");
    cout << "get a (in TX2)  = " << db.get("a") << "\n"; // 3
    db.rollback();         // undo TX2
    cout << "get a (post RB) = " << db.get("a") << "\n"; // 2
    cout << "get b (post RB) = " << db.get("b") << "\n"; // NULL
    db.commit();           // commit TX1
    cout << "get a (final)   = " << db.get("a") << "\n"; // 2

    db.rollback();         // no transaction
    return 0;
}
