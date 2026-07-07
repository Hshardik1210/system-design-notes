import java.util.*;

/**
 * SPLITWISE — expense sharing with balances and debt simplification.
 *
 * Core ideas:
 *  - An expense is paid by one person and split among several (EQUAL / EXACT / PERCENT).
 *    How to split is a Strategy (SplitStrategy).
 *  - We track net balances with a "who-owes-whom" matrix: balance[a][b] = how much
 *    a owes b. Adding an expense updates these pairwise balances.
 *  - Debt simplification: reduce the number of transactions using a greedy
 *    max-creditor / max-debtor settling algorithm.
 *
 * Patterns: Strategy (split algorithms).
 */
public class Main {

    // ---------- Split strategies ----------
    // Strategy pattern: the common contract for every way of splitting a bill.
    // Each concrete strategy decides how one expense is divided among people.
    interface SplitStrategy {
        // Given total amount and participants, return per-participant owed share.
        Map<String, Double> split(double amount, List<String> participants, double[] args);
    }
    // Strategy 1 (EQUAL): everyone owes an equal share of the total.
    static class EqualSplit implements SplitStrategy {
        public Map<String, Double> split(double amount, List<String> p, double[] args) {
            Map<String, Double> res = new LinkedHashMap<>();
            double share = amount / p.size(); // total divided evenly by number of people
            for (String u : p) res.put(u, share);
            return res;
        }
    }
    // Strategy 2 (EXACT): caller gives the exact amount each person owes.
    // args = exact amount per participant (must sum to total).
    static class ExactSplit implements SplitStrategy {
        public Map<String, Double> split(double amount, List<String> p, double[] args) {
            double sum = 0; for (double a : args) sum += a; // add up the provided amounts
            // Sanity check: the parts must add up to the full bill (1e-6 tolerates float rounding).
            if (Math.abs(sum - amount) > 1e-6) throw new IllegalArgumentException("exact shares must sum to total");
            Map<String, Double> res = new LinkedHashMap<>();
            for (int i = 0; i < p.size(); i++) res.put(p.get(i), args[i]); // person i owes args[i]
            return res;
        }
    }
    // Strategy 3 (PERCENT): caller gives each person's percentage of the bill.
    // args = percentage per participant (must sum to 100).
    static class PercentSplit implements SplitStrategy {
        public Map<String, Double> split(double amount, List<String> p, double[] args) {
            double sum = 0; for (double a : args) sum += a; // add up the percentages
            // The percentages must total 100, otherwise the split is invalid.
            if (Math.abs(sum - 100.0) > 1e-6) throw new IllegalArgumentException("percentages must sum to 100");
            Map<String, Double> res = new LinkedHashMap<>();
            // Convert each percentage into an actual money amount: amount * pct / 100.
            for (int i = 0; i < p.size(); i++) res.put(p.get(i), amount * args[i] / 100.0);
            return res;
        }
    }

    // ---------- Ledger of pairwise balances ----------
    // Central bookkeeper: stores who owes whom and turns expenses into debts.
    static class ExpenseManager {
        // balances.get(a).get(b) = amount a owes b (net kept positive on one side).
        private final Map<String, Map<String, Double>> balances = new HashMap<>();
        private final Set<String> users = new TreeSet<>(); // TreeSet keeps names sorted for stable output

        // Register a person so they appear in balances and reports.
        void addUser(String u) { users.add(u); balances.putIfAbsent(u, new HashMap<>()); }

        // Record a new expense: run the chosen split, then turn each share into a debt to the payer.
        void addExpense(String paidBy, double amount, List<String> participants,
                        SplitStrategy strategy, double[] args) {
            Map<String, Double> shares = strategy.split(amount, participants, args); // how much each owes
            for (Map.Entry<String, Double> e : shares.entrySet()) {
                String user = e.getKey();
                double share = e.getValue();
                if (user.equals(paidBy)) continue; // payer doesn't owe themselves
                // 'user' owes 'paidBy' their share.
                adjust(user, paidBy, share);
            }
        }

        // Record that 'from' owes 'to' 'amount', netting against the reverse debt.
        // Netting keeps only one direction of a debt (if B already owed A, we cancel first).
        private void adjust(String from, String to, double amount) {
            balances.putIfAbsent(from, new HashMap<>());
            balances.putIfAbsent(to, new HashMap<>());
            double reverse = balances.get(to).getOrDefault(from, 0.0); // does 'to' already owe 'from'?
            if (reverse >= amount) {
                balances.get(to).put(from, reverse - amount); // reduce what 'to' owed 'from'
            } else {
                balances.get(to).put(from, 0.0);                     // wipe out the smaller reverse debt
                balances.get(from).merge(to, amount - reverse, Double::sum); // 'from' owes the leftover
            }
        }

        // Print every outstanding one-directional debt (the raw, un-simplified view).
        void showBalances() {
            boolean any = false;
            for (String a : users)
                for (Map.Entry<String, Double> e : balances.getOrDefault(a, Map.of()).entrySet())
                    if (e.getValue() > 1e-6) {
                        System.out.printf("  %s owes %s: %.2f%n", a, e.getKey(), e.getValue());
                        any = true;
                    }
            if (!any) System.out.println("  (all settled)");
        }

        /**
         * Debt simplification: compute each person's NET balance, then greedily
         * settle the biggest debtor against the biggest creditor. Minimises the
         * number of transfers.
         */
        void simplify() {
            // Step 1: collapse the pairwise matrix into one net number per person.
            // Positive net = others owe them (creditor); negative = they owe others (debtor).
            Map<String, Double> net = new HashMap<>();
            for (String u : users) net.put(u, 0.0);
            for (String a : users)
                for (Map.Entry<String, Double> e : balances.getOrDefault(a, Map.of()).entrySet()) {
                    net.merge(a, -e.getValue(), Double::sum);       // a owes => negative
                    net.merge(e.getKey(), e.getValue(), Double::sum); // creditor => positive
                }

            // Step 2: put everyone into two heaps so we can always grab the extremes.
            // Max-heaps of creditors (positive) and debtors (negative).
            PriorityQueue<Map.Entry<String, Double>> creditors =
                    new PriorityQueue<>((x, y) -> Double.compare(y.getValue(), x.getValue())); // biggest credit on top
            PriorityQueue<Map.Entry<String, Double>> debtors =
                    new PriorityQueue<>((x, y) -> Double.compare(x.getValue(), y.getValue())); // most negative on top
            for (Map.Entry<String, Double> e : net.entrySet()) {
                if (e.getValue() > 1e-6) creditors.add(new AbstractMap.SimpleEntry<>(e));
                else if (e.getValue() < -1e-6) debtors.add(new AbstractMap.SimpleEntry<>(e));
            }

            System.out.println("  Simplified settlements:");
            boolean any = false;
            // Step 3: greedily match the biggest debtor with the biggest creditor.
            while (!creditors.isEmpty() && !debtors.isEmpty()) {
                var cr = creditors.poll(); // person owed the most
                var db = debtors.poll();   // person who owes the most
                double settle = Math.min(cr.getValue(), -db.getValue()); // pay off the smaller of the two
                System.out.printf("    %s pays %s: %.2f%n", db.getKey(), cr.getKey(), settle);
                any = true;
                double crLeft = cr.getValue() - settle; // remaining credit after this payment
                double dbLeft = db.getValue() + settle; // remaining debt (moves toward zero)
                // Whoever isn't fully settled goes back into their heap for another round.
                if (crLeft > 1e-6) creditors.add(new AbstractMap.SimpleEntry<>(cr.getKey(), crLeft));
                if (dbLeft < -1e-6) debtors.add(new AbstractMap.SimpleEntry<>(db.getKey(), dbLeft));
            }
            if (!any) System.out.println("    (all settled)");
        }
    }

    // Demo: set up users, add a couple of expenses, then print raw and simplified balances.
    public static void main(String[] args) {
        ExpenseManager mgr = new ExpenseManager();
        for (String u : List.of("Alice", "Bob", "Charlie")) mgr.addUser(u);

        // Alice pays 900 for dinner, split equally among 3 -> each owes 300.
        mgr.addExpense("Alice", 900, List.of("Alice", "Bob", "Charlie"), new EqualSplit(), null);
        // Bob pays 300 for cab split equally among Bob & Charlie -> Charlie owes 150.
        mgr.addExpense("Bob", 300, List.of("Bob", "Charlie"), new EqualSplit(), null);

        System.out.println("Raw pairwise balances:");
        mgr.showBalances();

        System.out.println("\nAfter debt simplification:");
        mgr.simplify();
    }
}
