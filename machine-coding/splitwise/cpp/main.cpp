// SPLITWISE — expense sharing with balances + debt simplification (C++17)
//
// - Split strategies: EQUAL / EXACT / PERCENT (Strategy pattern).
// - Pairwise "who owes whom" balances, netted on add.
// - Debt simplification: greedy max-creditor vs max-debtor settling to minimise transfers.

#include <iostream>
#include <string>
#include <vector>
#include <map>
#include <set>
#include <queue>
#include <algorithm>
#include <numeric>
#include <cmath>
#include <cstdio>
#include <stdexcept>
using namespace std;

// ---------- Split strategies ----------
struct SplitStrategy {
    virtual ~SplitStrategy() = default;
    // Returns participant -> owed share.
    virtual map<string, double> split(double amount, const vector<string>& p, const vector<double>& args) const = 0;
};
struct EqualSplit : SplitStrategy {
    map<string, double> split(double amount, const vector<string>& p, const vector<double>&) const override {
        map<string, double> res;
        double share = amount / p.size();
        for (auto& u : p) res[u] = share;
        return res;
    }
};
struct ExactSplit : SplitStrategy {
    map<string, double> split(double amount, const vector<string>& p, const vector<double>& args) const override {
        double sum = accumulate(args.begin(), args.end(), 0.0);
        if (fabs(sum - amount) > 1e-6) throw invalid_argument("exact shares must sum to total");
        map<string, double> res;
        for (size_t i = 0; i < p.size(); ++i) res[p[i]] = args[i];
        return res;
    }
};
struct PercentSplit : SplitStrategy {
    map<string, double> split(double amount, const vector<string>& p, const vector<double>& args) const override {
        double sum = accumulate(args.begin(), args.end(), 0.0);
        if (fabs(sum - 100.0) > 1e-6) throw invalid_argument("percentages must sum to 100");
        map<string, double> res;
        for (size_t i = 0; i < p.size(); ++i) res[p[i]] = amount * args[i] / 100.0;
        return res;
    }
};

// ---------- Ledger ----------
class ExpenseManager {
    // balances[a][b] = amount a owes b
    map<string, map<string, double>> balances;
    set<string> users;

    // Record 'from' owes 'to' amount, netting against reverse debt.
    void adjust(const string& from, const string& to, double amount) {
        double reverse = balances[to].count(from) ? balances[to][from] : 0.0;
        if (reverse >= amount) {
            balances[to][from] = reverse - amount;
        } else {
            balances[to][from] = 0.0;
            balances[from][to] += (amount - reverse);
        }
    }
public:
    void addUser(const string& u) { users.insert(u); }

    void addExpense(const string& paidBy, double amount, const vector<string>& participants,
                    const SplitStrategy& strategy, const vector<double>& args = {}) {
        auto shares = strategy.split(amount, participants, args);
        for (auto& [user, share] : shares) {
            if (user == paidBy) continue;
            adjust(user, paidBy, share); // user owes payer
        }
    }

    void showBalances() {
        bool any = false;
        for (auto& a : users)
            for (auto& [b, amt] : balances[a])
                if (amt > 1e-6) { printf("  %s owes %s: %.2f\n", a.c_str(), b.c_str(), amt); any = true; }
        if (!any) cout << "  (all settled)\n";
    }

    void simplify() {
        map<string, double> net;
        for (auto& u : users) net[u] = 0.0;
        for (auto& a : users)
            for (auto& [b, amt] : balances[a]) { net[a] -= amt; net[b] += amt; }

        // Max-heap creditors (positive), max-heap debtors (most negative).
        using Entry = pair<double, string>;
        priority_queue<Entry> creditors;                 // largest positive on top
        priority_queue<Entry, vector<Entry>, greater<>> debtors; // smallest (most negative) on top
        for (auto& [u, v] : net) {
            if (v > 1e-6) creditors.push({v, u});
            else if (v < -1e-6) debtors.push({v, u});
        }

        cout << "  Simplified settlements:\n";
        bool any = false;
        while (!creditors.empty() && !debtors.empty()) {
            auto cr = creditors.top(); creditors.pop();
            auto db = debtors.top(); debtors.pop();
            double settle = min(cr.first, -db.first);
            printf("    %s pays %s: %.2f\n", db.second.c_str(), cr.second.c_str(), settle);
            any = true;
            double crLeft = cr.first - settle;
            double dbLeft = db.first + settle;
            if (crLeft > 1e-6) creditors.push({crLeft, cr.second});
            if (dbLeft < -1e-6) debtors.push({dbLeft, db.second});
        }
        if (!any) cout << "    (all settled)\n";
    }
};

int main() {
    ExpenseManager mgr;
    for (auto& u : {"Alice", "Bob", "Charlie"}) mgr.addUser(u);

    EqualSplit equal;
    mgr.addExpense("Alice", 900, {"Alice", "Bob", "Charlie"}, equal); // each owes 300
    mgr.addExpense("Bob", 300, {"Bob", "Charlie"}, equal);            // Charlie owes 150

    cout << "Raw pairwise balances:\n";
    mgr.showBalances();

    cout << "\nAfter debt simplification:\n";
    mgr.simplify();
    return 0;
}
