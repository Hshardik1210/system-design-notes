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
    interface SplitStrategy {
        // Given total amount and participants, return per-participant owed share.
        Map<String, Double> split(double amount, List<String> participants, double[] args);
    }
    // Everyone owes an equal share.
    static class EqualSplit implements SplitStrategy {
        public Map<String, Double> split(double amount, List<String> p, double[] args) {
            Map<String, Double> res = new LinkedHashMap<>();
            double share = amount / p.size();
            for (String u : p) res.put(u, share);
            return res;
        }
    }
    // args = exact amount per participant (must sum to total).
    static class ExactSplit implements SplitStrategy {
        public Map<String, Double> split(double amount, List<String> p, double[] args) {
            double sum = 0; for (double a : args) sum += a;
            if (Math.abs(sum - amount) > 1e-6) throw new IllegalArgumentException("exact shares must sum to total");
            Map<String, Double> res = new LinkedHashMap<>();
            for (int i = 0; i < p.size(); i++) res.put(p.get(i), args[i]);
            return res;
        }
    }
    // args = percentage per participant (must sum to 100).
    static class PercentSplit implements SplitStrategy {
        public Map<String, Double> split(double amount, List<String> p, double[] args) {
            double sum = 0; for (double a : args) sum += a;
            if (Math.abs(sum - 100.0) > 1e-6) throw new IllegalArgumentException("percentages must sum to 100");
            Map<String, Double> res = new LinkedHashMap<>();
            for (int i = 0; i < p.size(); i++) res.put(p.get(i), amount * args[i] / 100.0);
            return res;
        }
    }

    // ---------- Ledger of pairwise balances ----------
    static class ExpenseManager {
        // balances.get(a).get(b) = amount a owes b (net kept positive on one side).
        private final Map<String, Map<String, Double>> balances = new HashMap<>();
        private final Set<String> users = new TreeSet<>();

        void addUser(String u) { users.add(u); balances.putIfAbsent(u, new HashMap<>()); }

        void addExpense(String paidBy, double amount, List<String> participants,
                        SplitStrategy strategy, double[] args) {
            Map<String, Double> shares = strategy.split(amount, participants, args);
            for (Map.Entry<String, Double> e : shares.entrySet()) {
                String user = e.getKey();
                double share = e.getValue();
                if (user.equals(paidBy)) continue; // payer doesn't owe themselves
                // 'user' owes 'paidBy' their share.
                adjust(user, paidBy, share);
            }
        }

        // Record that 'from' owes 'to' 'amount', netting against the reverse debt.
        private void adjust(String from, String to, double amount) {
            balances.putIfAbsent(from, new HashMap<>());
            balances.putIfAbsent(to, new HashMap<>());
            double reverse = balances.get(to).getOrDefault(from, 0.0);
            if (reverse >= amount) {
                balances.get(to).put(from, reverse - amount); // reduce what 'to' owed 'from'
            } else {
                balances.get(to).put(from, 0.0);
                balances.get(from).merge(to, amount - reverse, Double::sum);
            }
        }

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
            Map<String, Double> net = new HashMap<>();
            for (String u : users) net.put(u, 0.0);
            for (String a : users)
                for (Map.Entry<String, Double> e : balances.getOrDefault(a, Map.of()).entrySet()) {
                    net.merge(a, -e.getValue(), Double::sum);       // a owes => negative
                    net.merge(e.getKey(), e.getValue(), Double::sum); // creditor => positive
                }

            // Max-heaps of creditors (positive) and debtors (negative).
            PriorityQueue<Map.Entry<String, Double>> creditors =
                    new PriorityQueue<>((x, y) -> Double.compare(y.getValue(), x.getValue()));
            PriorityQueue<Map.Entry<String, Double>> debtors =
                    new PriorityQueue<>((x, y) -> Double.compare(x.getValue(), y.getValue()));
            for (Map.Entry<String, Double> e : net.entrySet()) {
                if (e.getValue() > 1e-6) creditors.add(new AbstractMap.SimpleEntry<>(e));
                else if (e.getValue() < -1e-6) debtors.add(new AbstractMap.SimpleEntry<>(e));
            }

            System.out.println("  Simplified settlements:");
            boolean any = false;
            while (!creditors.isEmpty() && !debtors.isEmpty()) {
                var cr = creditors.poll();
                var db = debtors.poll();
                double settle = Math.min(cr.getValue(), -db.getValue());
                System.out.printf("    %s pays %s: %.2f%n", db.getKey(), cr.getKey(), settle);
                any = true;
                double crLeft = cr.getValue() - settle;
                double dbLeft = db.getValue() + settle;
                if (crLeft > 1e-6) creditors.add(new AbstractMap.SimpleEntry<>(cr.getKey(), crLeft));
                if (dbLeft < -1e-6) debtors.add(new AbstractMap.SimpleEntry<>(db.getKey(), dbLeft));
            }
            if (!any) System.out.println("    (all settled)");
        }
    }

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
