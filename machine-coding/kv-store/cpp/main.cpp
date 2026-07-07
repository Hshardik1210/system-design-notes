// IN-MEMORY KEY-VALUE STORE with nested transactions (C++17)
//
// set/get/delete + BEGIN/COMMIT/ROLLBACK (nestable).
// Each open transaction keeps an undo log: key -> previous value (optional; empty
// optional means "key was absent before"). Only the first prior value per key is
// stored. ROLLBACK restores from the log; COMMIT merges into the parent (or makes
// changes permanent at the outermost level).

#include <iostream>
#include <string>
#include <unordered_map>
#include <vector>
#include <optional>
using namespace std;

class KVStore {
    unordered_map<string, string> committed;
    // Stack of undo logs; optional<string> empty => key was absent before.
    vector<unordered_map<string, optional<string>>> txStack;

    void recordUndo(const string& key) {
        if (txStack.empty()) return;
        auto& undo = txStack.back();
        if (!undo.count(key)) {
            auto it = committed.find(key);
            undo[key] = (it != committed.end()) ? optional<string>(it->second) : nullopt;
        }
    }
public:
    string get(const string& key) {
        auto it = committed.find(key);
        return it != committed.end() ? it->second : "NULL";
    }
    void set(const string& key, const string& value) { recordUndo(key); committed[key] = value; }
    void del(const string& key) { recordUndo(key); committed.erase(key); }

    void begin() { txStack.emplace_back(); }

    bool rollback() {
        if (txStack.empty()) { cout << "  ! no transaction\n"; return false; }
        auto undo = txStack.back(); txStack.pop_back();
        for (auto& [key, prev] : undo) {
            if (prev.has_value()) committed[key] = *prev;
            else committed.erase(key);
        }
        return true;
    }

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
