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
 *
 * WHAT THIS PROGRAM DOES (beginner overview):
 *   - Opens a few accounts with an opening balance.
 *   - Moves money between them using transfer().
 *   - Records every movement in a ledger so we can audit and verify it.
 *
 * KEY CLASSES:
 *   - EntryType  : whether a ledger line takes money out (DEBIT) or puts it in (CREDIT).
 *   - LedgerEntry: one immutable line in the ledger (the audit trail).
 *   - Account    : holds an id and a balance (stored in paise, the smallest unit).
 *   - Wallet     : owns all accounts + the ledger, and performs transfers.
 *
 * DESIGN PATTERNS:
 *   - Ledger / double-entry accounting: each transfer = one DEBIT + one CREDIT that
 *     cancel out, so total money is always conserved (auditable and self-checking).
 *   - Ordered locking: locks the two accounts in a fixed order (by id) to avoid deadlock.
 *
 * NOTE ON MONEY: amounts are stored as whole paise (long), never as floats, so we
 * never lose precision on money.
 */
public class Main {

    // Direction of a ledger line: DEBIT removes money from an account, CREDIT adds it.
    enum EntryType { DEBIT, CREDIT }

    // One immutable line in the ledger — the permanent audit record of a money movement.
    static class LedgerEntry {
        // txnId links the two halves (DEBIT + CREDIT) of the same transfer together.
        final String txnId; final String accountId; final EntryType type; final long amount;
        LedgerEntry(String txnId, String accountId, EntryType type, long amount) {
            this.txnId = txnId; this.accountId = accountId; this.type = type; this.amount = amount;
        }
        // Human-readable form used when printing the ledger at the end.
        public String toString() { return txnId + " " + type + " " + amount + " @" + accountId; }
    }

    // A single account: an id plus its current balance (kept private so only apply() can change it).
    static class Account {
        final String id; private long balance; // in paise
        Account(String id, long opening) { this.id = id; this.balance = opening; }
        long balance() { return balance; }
        // Update the balance: a CREDIT adds money, a DEBIT subtracts it.
        void apply(EntryType t, long amt) { balance += (t == EntryType.CREDIT ? amt : -amt); }
    }

    // Central bookkeeper: holds every account, the shared ledger, and runs transfers.
    static class Wallet {
        private final Map<String, Account> accounts = new HashMap<>();
        private final List<LedgerEntry> ledger = new ArrayList<>();
        // Generates unique, ever-increasing transaction ids (thread-safe counter).
        private final AtomicLong txnSeq = new AtomicLong(1);

        // Create a new account with a starting balance and register it in the wallet.
        Account open(String id, long opening) {
            Account a = new Account(id, opening);
            accounts.put(id, a);
            return a;
        }

        // Move `amount` from one account to another as a single all-or-nothing operation.
        // Either both ledger entries and both balance changes happen, or nothing does.
        boolean transfer(String from, String to, long amount) {
            if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
            Account a = accounts.get(from), b = accounts.get(to);
            if (a == null || b == null) throw new NoSuchElementException("unknown account");

            // Lock in a fixed order (by id) to prevent deadlock.
            // If every thread always locks the lower id first, two threads can never
            // hold one lock each and wait forever for the other.
            Account first = from.compareTo(to) < 0 ? a : b;
            Account second = first == a ? b : a;
            synchronized (first) {
                synchronized (second) {
                    // Double-spend / overdraft guard: reject before changing anything.
                    if (a.balance() < amount) { System.out.println("  ! insufficient funds in " + from); return false; }
                    String txn = "TXN" + txnSeq.getAndIncrement();
                    // Two balanced entries.
                    // Build both halves of the transfer under the same txn id.
                    LedgerEntry debit = new LedgerEntry(txn, from, EntryType.DEBIT, amount);
                    LedgerEntry credit = new LedgerEntry(txn, to, EntryType.CREDIT, amount);
                    // Apply both balance changes together so money is neither created nor lost.
                    a.apply(EntryType.DEBIT, amount);
                    b.apply(EntryType.CREDIT, amount);
                    // Record both entries in the ledger (the audit trail).
                    ledger.add(debit); ledger.add(credit);
                    System.out.printf("  %s: %d %s -> %s%n", txn, amount, from, to);
                    return true;
                }
            }
        }

        // Invariant check: DEBITs and CREDITs cancel out to zero.
        // Because every transfer adds equal DEBIT and CREDIT amounts, a correct
        // ledger must always sum to 0 — if it doesn't, money was created or lost.
        long ledgerNet() {
            long net = 0;
            for (LedgerEntry e : ledger) net += (e.type == EntryType.CREDIT ? e.amount : -e.amount);
            return net;
        }
        List<LedgerEntry> ledger() { return ledger; }
    }

    // Demo: open accounts, run a few transfers (including one that fails), then
    // print balances and verify the ledger nets to zero.
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
