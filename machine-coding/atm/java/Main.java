import java.util.*;

/**
 * ATM MACHINE — State pattern.
 *
 * States: IDLE -> CARD_INSERTED -> AUTHENTICATED -> (withdraw/balance) -> IDLE
 * Each state permits only certain operations; invalid ops are rejected.
 *
 * Cash dispensing uses a greedy note-dispenser and checks both the account
 * balance and the machine's cash inventory.
 */
public class Main {

    static class Account {
        final String cardNo; final int pin; double balance;
        Account(String cardNo, int pin, double balance) { this.cardNo = cardNo; this.pin = pin; this.balance = balance; }
    }

    // The machine's physical cash (note -> count).
    static class CashDispenser {
        private final TreeMap<Integer, Integer> notes = new TreeMap<>(Collections.reverseOrder());
        void load(int note, int count) { notes.merge(note, count, Integer::sum); }
        int total() { int t = 0; for (var e : notes.entrySet()) t += e.getKey() * e.getValue(); return t; }

        // Greedily choose notes for 'amount'; returns null if not dispensable.
        Map<Integer, Integer> dispense(int amount) {
            Map<Integer, Integer> plan = new LinkedHashMap<>();
            int remaining = amount;
            for (var e : notes.entrySet()) {
                int note = e.getKey(), avail = e.getValue();
                int need = Math.min(remaining / note, avail);
                if (need > 0) { plan.put(note, need); remaining -= need * note; }
            }
            if (remaining != 0) return null; // can't make exact amount
            for (var e : plan.entrySet()) notes.merge(e.getKey(), -e.getValue(), Integer::sum);
            return plan;
        }
    }

    // ---------- State pattern ----------
    interface ATMState {
        void insertCard(ATM atm, String cardNo);
        void enterPin(ATM atm, int pin);
        void withdraw(ATM atm, int amount);
        void ejectCard(ATM atm);
    }

    static class IdleState implements ATMState {
        public void insertCard(ATM atm, String cardNo) {
            Account acc = atm.bank.get(cardNo);
            if (acc == null) { System.out.println("  ! unknown card"); return; }
            atm.currentAccount = acc;
            atm.setState(atm.cardInserted);
            System.out.println("  card accepted; enter PIN");
        }
        public void enterPin(ATM a, int p) { System.out.println("  ! insert card first"); }
        public void withdraw(ATM a, int amt) { System.out.println("  ! insert card first"); }
        public void ejectCard(ATM a) { System.out.println("  ! no card"); }
    }

    static class CardInsertedState implements ATMState {
        public void insertCard(ATM a, String c) { System.out.println("  ! card already inserted"); }
        public void enterPin(ATM atm, int pin) {
            if (atm.currentAccount.pin != pin) { System.out.println("  ! wrong PIN; ejecting"); atm.reset(); return; }
            atm.setState(atm.authenticated);
            System.out.println("  PIN ok; authenticated");
        }
        public void withdraw(ATM a, int amt) { System.out.println("  ! enter PIN first"); }
        public void ejectCard(ATM atm) { System.out.println("  card ejected"); atm.reset(); }
    }

    static class AuthenticatedState implements ATMState {
        public void insertCard(ATM a, String c) { System.out.println("  ! already in session"); }
        public void enterPin(ATM a, int p) { System.out.println("  ! already authenticated"); }
        public void withdraw(ATM atm, int amount) {
            Account acc = atm.currentAccount;
            if (amount > acc.balance) { System.out.println("  ! insufficient account balance"); return; }
            if (amount > atm.dispenser.total()) { System.out.println("  ! ATM out of cash"); return; }
            Map<Integer, Integer> plan = atm.dispenser.dispense(amount);
            if (plan == null) { System.out.println("  ! cannot dispense exact notes for " + amount); return; }
            acc.balance -= amount;
            System.out.println("  dispensed " + plan + "; new balance " + acc.balance);
        }
        public void ejectCard(ATM atm) { System.out.println("  card ejected"); atm.reset(); }
    }

    // ---------- Context ----------
    static class ATM {
        final Map<String, Account> bank = new HashMap<>();
        final CashDispenser dispenser = new CashDispenser();
        final ATMState idle = new IdleState();
        final ATMState cardInserted = new CardInsertedState();
        final ATMState authenticated = new AuthenticatedState();

        private ATMState state = idle;
        Account currentAccount;

        void setState(ATMState s) { state = s; }
        void reset() { state = idle; currentAccount = null; }

        void insertCard(String c) { state.insertCard(this, c); }
        void enterPin(int p)      { state.enterPin(this, p); }
        void withdraw(int amt)    { state.withdraw(this, amt); }
        void ejectCard()          { state.ejectCard(this); }
    }

    public static void main(String[] args) {
        ATM atm = new ATM();
        atm.bank.put("CARD-1", new Account("CARD-1", 1234, 5000));
        atm.dispenser.load(500, 5);
        atm.dispenser.load(100, 10);

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
        atm.withdraw(100);
    }
}
