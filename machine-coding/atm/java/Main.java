import java.util.*;

/**
 * ATM MACHINE — State pattern.
 *
 * WHAT THIS PROGRAM DOES:
 *   Simulates an ATM as a small state machine. A user inserts a card, enters a
 *   PIN, and withdraws cash. The ATM only allows operations that make sense in
 *   its current state (e.g. you cannot withdraw before entering a PIN).
 *
 * STATES: IDLE -> CARD_INSERTED -> AUTHENTICATED -> (withdraw/balance) -> IDLE
 *   Each state permits only certain operations; invalid ops are rejected.
 *
 * KEY CLASSES:
 *   - Account         : a customer's card, PIN and balance.
 *   - CashDispenser   : the machine's physical cash and note-selection logic.
 *   - ATMState        : the State-pattern interface (one method per operation).
 *   - IdleState / CardInsertedState / AuthenticatedState : the 3 concrete states.
 *   - ATM             : the "context" that holds the current state and delegates to it.
 *
 * DESIGN PATTERNS:
 *   - State pattern: each state is its own class, so the rules for what is
 *     allowed live in that state instead of in one giant if/else block.
 *   - Strategy (implicit): the greedy note selection could be swapped for a
 *     smarter algorithm without changing the rest of the code.
 *
 * Cash dispensing uses a greedy note-dispenser and checks both the account
 * balance and the machine's cash inventory.
 */
public class Main {

    // A bank account tied to one card: its card number, PIN, and current balance.
    static class Account {
        final String cardNo; final int pin; double balance;
        Account(String cardNo, int pin, double balance) { this.cardNo = cardNo; this.pin = pin; this.balance = balance; }
    }

    // The machine's physical cash (note -> count).
    // Notes are stored largest-first so the greedy algorithm hands out big notes first.
    static class CashDispenser {
        private final TreeMap<Integer, Integer> notes = new TreeMap<>(Collections.reverseOrder());
        // Add 'count' notes of value 'note' to the machine's inventory.
        void load(int note, int count) { notes.merge(note, count, Integer::sum); }
        // Total cash currently held = sum of (note value * how many of that note).
        int total() { int t = 0; for (var e : notes.entrySet()) t += e.getKey() * e.getValue(); return t; }

        // Greedily choose notes for 'amount'; returns null if not dispensable.
        // "Greedy" = use as many of the largest note as possible, then the next, etc.
        Map<Integer, Integer> dispense(int amount) {
            Map<Integer, Integer> plan = new LinkedHashMap<>();
            int remaining = amount;
            // Walk notes largest-first, taking as many of each as fit into 'remaining'.
            for (var e : notes.entrySet()) {
                int note = e.getKey(), avail = e.getValue();
                // Take the smaller of "how many fit" and "how many we actually have".
                int need = Math.min(remaining / note, avail);
                if (need > 0) { plan.put(note, need); remaining -= need * note; }
            }
            if (remaining != 0) return null; // leftover means we can't make the exact amount (e.g. 50 with only 500/100 notes)
            // Only now (once we know it works) actually remove the chosen notes from inventory.
            for (var e : plan.entrySet()) notes.merge(e.getKey(), -e.getValue(), Integer::sum);
            return plan;
        }
    }

    // ---------- State pattern ----------
    // The common contract every ATM state must support. Each concrete state
    // decides which of these operations are valid and which are rejected.
    interface ATMState {
        void insertCard(ATM atm, String cardNo);
        void enterPin(ATM atm, int pin);
        void withdraw(ATM atm, int amount);
        void ejectCard(ATM atm);
    }

    // State when no card is present. Only "insert card" does anything useful.
    static class IdleState implements ATMState {
        public void insertCard(ATM atm, String cardNo) {
            Account acc = atm.bank.get(cardNo);
            if (acc == null) { System.out.println("  ! unknown card"); return; }
            atm.currentAccount = acc;
            atm.setState(atm.cardInserted); // move IDLE -> CARD_INSERTED
            System.out.println("  card accepted; enter PIN");
        }
        // In IDLE these operations make no sense, so they are all rejected.
        public void enterPin(ATM a, int p) { System.out.println("  ! insert card first"); }
        public void withdraw(ATM a, int amt) { System.out.println("  ! insert card first"); }
        public void ejectCard(ATM a) { System.out.println("  ! no card"); }
    }

    // State after a valid card is inserted but before the PIN is verified.
    static class CardInsertedState implements ATMState {
        public void insertCard(ATM a, String c) { System.out.println("  ! card already inserted"); }
        public void enterPin(ATM atm, int pin) {
            // Wrong PIN ends the session immediately (card is ejected via reset()).
            if (atm.currentAccount.pin != pin) { System.out.println("  ! wrong PIN; ejecting"); atm.reset(); return; }
            atm.setState(atm.authenticated); // correct PIN: CARD_INSERTED -> AUTHENTICATED
            System.out.println("  PIN ok; authenticated");
        }
        public void withdraw(ATM a, int amt) { System.out.println("  ! enter PIN first"); }
        public void ejectCard(ATM atm) { System.out.println("  card ejected"); atm.reset(); }
    }

    // State after a successful PIN. Only here is withdrawing money allowed.
    static class AuthenticatedState implements ATMState {
        public void insertCard(ATM a, String c) { System.out.println("  ! already in session"); }
        public void enterPin(ATM a, int p) { System.out.println("  ! already authenticated"); }
        // Withdraw must pass two checks: enough money in the account AND enough
        // cash in the machine that can be formed into exact notes.
        public void withdraw(ATM atm, int amount) {
            Account acc = atm.currentAccount;
            if (amount > acc.balance) { System.out.println("  ! insufficient account balance"); return; } // check 1: account balance
            if (amount > atm.dispenser.total()) { System.out.println("  ! ATM out of cash"); return; }     // check 2a: machine has enough cash
            Map<Integer, Integer> plan = atm.dispenser.dispense(amount);
            if (plan == null) { System.out.println("  ! cannot dispense exact notes for " + amount); return; } // check 2b: exact notes possible
            acc.balance -= amount; // only deduct after cash was successfully reserved
            System.out.println("  dispensed " + plan + "; new balance " + acc.balance);
        }
        public void ejectCard(ATM atm) { System.out.println("  card ejected"); atm.reset(); }
    }

    // ---------- Context ----------
    // The ATM itself. It holds the state objects and forwards every user action
    // to whichever state is currently active (that is the heart of the State pattern).
    static class ATM {
        final Map<String, Account> bank = new HashMap<>();      // known accounts, looked up by card number
        final CashDispenser dispenser = new CashDispenser();     // the machine's cash
        // The three state objects are created once and reused (they hold no per-user data).
        final ATMState idle = new IdleState();
        final ATMState cardInserted = new CardInsertedState();
        final ATMState authenticated = new AuthenticatedState();

        private ATMState state = idle;   // the ATM always starts idle (no card)
        Account currentAccount;          // the account of the card currently in use, if any

        void setState(ATMState s) { state = s; }
        // Return to the starting point: no active state, no card.
        void reset() { state = idle; currentAccount = null; }

        // Public actions simply delegate to the current state, which decides what to do.
        void insertCard(String c) { state.insertCard(this, c); }
        void enterPin(int p)      { state.enterPin(this, p); }
        void withdraw(int amt)    { state.withdraw(this, amt); }
        void ejectCard()          { state.ejectCard(this); }
    }

    // Demo driver: sets up one account and some cash, then runs a few scenarios.
    public static void main(String[] args) {
        ATM atm = new ATM();
        atm.bank.put("CARD-1", new Account("CARD-1", 1234, 5000));
        atm.dispenser.load(500, 5);   // 5 notes of 500
        atm.dispenser.load(100, 10);  // 10 notes of 100

        System.out.println("--- Wrong PIN ---");
        atm.insertCard("CARD-1");
        atm.enterPin(0000);  // wrong -> ejects

        System.out.println("--- Correct flow ---");
        atm.insertCard("CARD-1");
        atm.enterPin(1234);
        atm.withdraw(1300);  // 2x500 + 3x100
        atm.withdraw(50);    // can't make with 500/100 notes
        atm.ejectCard();

        System.out.println("--- Op without card ---");
        atm.withdraw(100);   // rejected: no card inserted (IDLE state)
    }
}
