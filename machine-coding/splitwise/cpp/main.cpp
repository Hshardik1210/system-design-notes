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
// Strategy pattern: common interface for every way of dividing one expense.
struct SplitStrategy {
    virtual ~SplitStrategy() = default;
    // Returns participant -> owed share.
    virtual map<string, double> split(double amount, const vector<string>& p, const vector<double>& args) const = 0;
};
// Strategy 1 (EQUAL): everyone owes an equal share of the total.
struct EqualSplit : SplitStrategy {
    map<string, double> split(double amount, const vector<string>& p, const vector<double>&) const override {
        map<string, double> res;
        double share = amount / p.size(); // total divided evenly by number of people
        for (auto& u : p) res[u] = share;
        return res;
    }
};
// Strategy 2 (EXACT): caller supplies the exact amount each person owes.
struct ExactSplit : SplitStrategy {
    map<string, double> split(double amount, const vector<string>& p, const vector<double>& args) const override {
        double sum = accumulate(args.begin(), args.end(), 0.0); // add up the provided amounts
        // The parts must add up to the full bill (1e-6 tolerates float rounding).
        if (fabs(sum - amount) > 1e-6) throw invalid_argument("exact shares must sum to total");
        map<string, double> res;
        for (size_t i = 0; i < p.size(); ++i) res[p[i]] = args[i]; // person i owes args[i]
        return res;
    }
};
// Strategy 3 (PERCENT): caller supplies each person's percentage of the bill.
struct PercentSplit : SplitStrategy {
    map<string, double> split(double amount, const vector<string>& p, const vector<double>& args) const override {
        double sum = accumulate(args.begin(), args.end(), 0.0); // add up the percentages
        // The percentages must total 100, otherwise the split is invalid.
        if (fabs(sum - 100.0) > 1e-6) throw invalid_argument("percentages must sum to 100");
        map<string, double> res;
        // Convert each percentage into an actual money amount: amount * pct / 100.
        for (size_t i = 0; i < p.size(); ++i) res[p[i]] = amount * args[i] / 100.0;
        return res;
    }
};

// ---------- Ledger ----------
// Central bookkeeper: stores who owes whom and turns expenses into debts.
class ExpenseManager {
    // balances[a][b] = amount a owes b
    map<string, map<string, double>> balances;
    set<string> users; // sorted set of names for stable output

    // Record 'from' owes 'to' amount, netting against reverse debt.
    // Netting keeps only one direction of a debt (if 'to' already owed 'from', cancel first).
    void adjust(const string& from, const string& to, double amount) {
        double reverse = balances[to].count(from) ? balances[to][from] : 0.0; // does 'to' already owe 'from'?
        if (reverse >= amount) {
            balances[to][from] = reverse - amount;      // reduce what 'to' owed 'from'
        } else {
            balances[to][from] = 0.0;                   // wipe out the smaller reverse debt
            balances[from][to] += (amount - reverse);   // 'from' owes the leftover
        }
    }
public:
    // Register a person so they show up in reports.
    void addUser(const string& u) { users.insert(u); }

    // Record a new expense: run the chosen split, then turn each share into a debt to the payer.
    void addExpense(const string& paidBy, double amount, const vector<string>& participants,
                    const SplitStrategy& strategy, const vector<double>& args = {}) {
        auto shares = strategy.split(amount, participants, args); // how much each person owes
        for (auto& [user, share] : shares) {
            if (user == paidBy) continue; // payer doesn't owe themselves
            adjust(user, paidBy, share); // user owes payer
        }
    }

    // Print every outstanding one-directional debt (the raw, un-simplified view).
    void showBalances() {
        bool any = false;
        for (auto& a : users)
            for (auto& [b, amt] : balances[a])
                if (amt > 1e-6) { printf("  %s owes %s: %.2f\n", a.c_str(), b.c_str(), amt); any = true; }
        if (!any) cout << "  (all settled)\n";
    }

    // Debt simplification: reduce everyone to a single net balance, then greedily
    // match biggest debtor with biggest creditor to minimise the number of transfers.
    void simplify() {
        // Step 1: collapse the pairwise matrix into one net number per person.
        // Positive = others owe them (creditor); negative = they owe others (debtor).
        map<string, double> net;
        for (auto& u : users) net[u] = 0.0;
        for (auto& a : users)
            for (auto& [b, amt] : balances[a]) { net[a] -= amt; net[b] += amt; }

        // Step 2: two heaps let us always grab the extremes.
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
        // Step 3: greedily settle the biggest debtor against the biggest creditor.
        while (!creditors.empty() && !debtors.empty()) {
            auto cr = creditors.top(); creditors.pop(); // person owed the most
            auto db = debtors.top(); debtors.pop();     // person who owes the most
            double settle = min(cr.first, -db.first);   // pay off the smaller of the two
            printf("    %s pays %s: %.2f\n", db.second.c_str(), cr.second.c_str(), settle);
            any = true;
            double crLeft = cr.first - settle; // remaining credit after this payment
            double dbLeft = db.first + settle; // remaining debt (moves toward zero)
            // Whoever isn't fully settled goes back into their heap for another round.
            if (crLeft > 1e-6) creditors.push({crLeft, cr.second});
            if (dbLeft < -1e-6) debtors.push({dbLeft, db.second});
        }
        if (!any) cout << "    (all settled)\n";
    }
};

// Demo: set up users, add a couple of expenses, then print raw and simplified balances.
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
