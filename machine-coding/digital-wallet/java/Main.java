import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DIGITAL WALLET (Paytm/PhonePe) — double-entry ledger.
 *
 * Every money movement is recorded as balanced ledger entries: for a transfer of
 * X from A to B we write a DEBIT of X on A and a CREDIT of X on B. The sum of all
 * entries is always zero (money is conserved) — this is how real payment systems
 * stay auditable.
 *
 * Transfers are atomic and locked in a consistent order (by account id) to avoid
 * deadlocks when two transfers touch the same pair concurrently.
 */
public class Main {

    enum EntryType { DEBIT, CREDIT }

    static class LedgerEntry {
        final String txnId; final String accountId; final EntryType type; final long amount;
        LedgerEntry(String txnId, String accountId, EntryType type, long amount) {
            this.txnId = txnId; this.accountId = accountId; this.type = type; this.amount = amount;
        }
        public String toString() { return txnId + " " + type + " " + amount + " @" + accountId; }
    }

    static class Account {
        final String id; private long balance; // in paise
        Account(String id, long opening) { this.id = id; this.balance = opening; }
        long balance() { return balance; }
        void apply(EntryType t, long amt) { balance += (t == EntryType.CREDIT ? amt : -amt); }
    }

    static class Wallet {
        private final Map<String, Account> accounts = new HashMap<>();
        private final List<LedgerEntry> ledger = new ArrayList<>();
        private final AtomicLong txnSeq = new AtomicLong(1);

        Account open(String id, long opening) {
            Account a = new Account(id, opening);
            accounts.put(id, a);
            return a;
        }

        // Atomic transfer with double-entry recording.
        boolean transfer(String from, String to, long amount) {
            if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
            Account a = accounts.get(from), b = accounts.get(to);
            if (a == null || b == null) throw new NoSuchElementException("unknown account");

            // Lock in a fixed order (by id) to prevent deadlock.
            Account first = from.compareTo(to) < 0 ? a : b;
            Account second = first == a ? b : a;
            synchronized (first) {
                synchronized (second) {
                    if (a.balance() < amount) { System.out.println("  ! insufficient funds in " + from); return false; }
                    String txn = "TXN" + txnSeq.getAndIncrement();
                    // Two balanced entries.
                    LedgerEntry debit = new LedgerEntry(txn, from, EntryType.DEBIT, amount);
                    LedgerEntry credit = new LedgerEntry(txn, to, EntryType.CREDIT, amount);
                    a.apply(EntryType.DEBIT, amount);
                    b.apply(EntryType.CREDIT, amount);
                    ledger.add(debit); ledger.add(credit);
                    System.out.printf("  %s: %d %s -> %s%n", txn, amount, from, to);
                    return true;
                }
            }
        }

        // Invariant check: DEBITs and CREDITs cancel out to zero.
        long ledgerNet() {
            long net = 0;
            for (LedgerEntry e : ledger) net += (e.type == EntryType.CREDIT ? e.amount : -e.amount);
            return net;
        }
        List<LedgerEntry> ledger() { return ledger; }
    }

    public static void main(String[] args) {
        Wallet w = new Wallet();
        Account alice = w.open("alice", 10000); // Rs 100.00
        Account bob   = w.open("bob", 0);
        Account carol = w.open("carol", 5000);

        w.transfer("alice", "bob", 4000);   // 40.00
        w.transfer("carol", "bob", 1500);   // 15.00
        w.transfer("bob", "carol", 1000);   // 10.00
        w.transfer("alice", "bob", 999999); // insufficient

        System.out.println("Balances: alice=" + alice.balance() + " bob=" + bob.balance() + " carol=" + carol.balance());
        System.out.println("Ledger net (must be 0): " + w.ledgerNet());
        System.out.println("Ledger:");
        for (LedgerEntry e : w.ledger()) System.out.println("  " + e);
    }
}
