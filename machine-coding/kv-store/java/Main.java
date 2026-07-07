import java.util.*;

/**
 * IN-MEMORY KEY-VALUE STORE with nested transactions.
 *
 * WHAT THIS PROGRAM DOES (beginner overview):
 *   It is a tiny database that lives only in memory (a HashMap). You can store
 *   values by key (set), read them (get), and remove them (delete). On top of
 *   that it supports transactions you can BEGIN, COMMIT, or ROLLBACK, and those
 *   transactions can be nested (a transaction inside another transaction).
 *
 * Supports: set / get / delete, and BEGIN / COMMIT / ROLLBACK that can nest.
 *
 * KEY CLASSES:
 *   - Main    : entry point plus a small demo in main().
 *   - KVStore : the actual key-value store and transaction engine.
 *
 * DESIGN PATTERN USED:
 *   - "Undo log" / Memento-style approach: instead of copying the whole store on
 *     every BEGIN, each transaction remembers only the OLD value of keys it
 *     touched. To undo, we just put those old values back. This keeps set/get at
 *     O(1) even with deep nesting.
 *   - The stack of undo logs (a LIFO/stack) mirrors the nesting of transactions:
 *     the last BEGIN is the first to COMMIT or ROLLBACK.
 *
 * How nested transactions work:
 *   - 'committed' holds the durable state.
 *   - Each open transaction pushes an "undo log" onto a stack. The undo log
 *     records the PREVIOUS value of every key the transaction changed (so it
 *     can be reverted). We only store the first prior value per key.
 *   - set/delete mutate 'committed' immediately but first record the old value
 *     in the top transaction's undo log.
 *   - ROLLBACK replays the top undo log in reverse to restore old values.
 *   - COMMIT merges the top undo log into the parent (or discards it at depth 1),
 *     making the changes permanent relative to the outer scope.
 *
 * This gives O(1) get/set and correct nested semantics.
 */
public class Main {

    // Sentinel meaning "key did not exist" in an undo record.
    // We reuse null to mean "before the change, this key was absent", so that
    // ROLLBACK knows to remove the key rather than restore some old value.
    private static final String ABSENT = null;

    // KVStore: the in-memory store plus the transaction (undo log) machinery.
    static class KVStore {
        // The live, current data. All reads/writes go through this map.
        private final Map<String, String> committed = new HashMap<>();
        // Stack of undo logs; one per open transaction.
        // Top of the stack = the innermost (most recently begun) transaction.
        private final Deque<Map<String, String>> txStack = new ArrayDeque<>();

        // Read a key; returns the string "NULL" when the key is not present.
        String get(String key) {
            return committed.getOrDefault(key, "NULL");
        }

        // Store/overwrite a value. recordUndo first saves the old value (if we
        // are inside a transaction) so the change can be undone later.
        void set(String key, String value) {
            recordUndo(key);
            committed.put(key, value);
        }

        // Remove a key. Like set, we record the old value before deleting so a
        // ROLLBACK can bring the key back.
        void delete(String key) {
            recordUndo(key);
            committed.remove(key);
        }

        // Start a new transaction by pushing a fresh, empty undo log.
        void begin() {
            txStack.push(new HashMap<>()); // new undo log
        }

        // Undo every change made since the matching BEGIN, then drop that log.
        boolean rollback() {
            if (txStack.isEmpty()) { System.out.println("  ! no transaction"); return false; }
            Map<String, String> undo = txStack.pop();
            // Restore each key to its recorded prior value.
            for (Map.Entry<String, String> e : undo.entrySet()) {
                if (e.getValue() == ABSENT) committed.remove(e.getKey()); // didn't exist before
                else committed.put(e.getKey(), e.getValue()); // put the old value back
            }
            return true;
        }

        // Accept the changes of the innermost transaction. If it is nested, its
        // undo info is handed up to the parent (so the parent could still undo it);
        // at the outermost level the changes simply stay in 'committed'.
        boolean commit() {
            if (txStack.isEmpty()) { System.out.println("  ! no transaction"); return false; }
            Map<String, String> undo = txStack.pop();
            if (!txStack.isEmpty()) {
                // Merge into parent's undo log WITHOUT overwriting values it already
                // recorded (the parent must remember the value before IT started).
                Map<String, String> parent = txStack.peek();
                for (Map.Entry<String, String> e : undo.entrySet())
                    parent.putIfAbsent(e.getKey(), e.getValue()); // keep parent's earlier record if any
            }
            // If no parent, changes are already in 'committed' -> permanent.
            return true;
        }

        // Record the current value of 'key' in the innermost transaction (once).
        // "Once" matters: the undo log must keep the value from BEFORE the
        // transaction began, so we ignore repeated writes to the same key.
        private void recordUndo(String key) {
            if (txStack.isEmpty()) return; // no transaction: change is directly durable
            Map<String, String> undo = txStack.peek();
            if (!undo.containsKey(key))
                undo.put(key, committed.containsKey(key) ? committed.get(key) : ABSENT);
        }
    }

    // Demo driver: exercises set/get plus a nested BEGIN/ROLLBACK/COMMIT flow
    // and prints the value of the keys at each step so you can trace the logic.
    public static void main(String[] args) {
        KVStore db = new KVStore();

        db.set("a", "1");
        System.out.println("get a           = " + db.get("a")); // 1

        db.begin();                 // TX1
        db.set("a", "2");
        System.out.println("get a (in TX1)  = " + db.get("a")); // 2
        db.begin();                 // TX2 (nested)
        db.set("a", "3");
        db.set("b", "9");
        System.out.println("get a (in TX2)  = " + db.get("a")); // 3
        db.rollback();              // undo TX2
        System.out.println("get a (post RB) = " + db.get("a")); // 2 (TX2 reverted)
        System.out.println("get b (post RB) = " + db.get("b")); // NULL
        db.commit();                // commit TX1 -> a=2 permanent
        System.out.println("get a (final)   = " + db.get("a")); // 2

        db.rollback();              // no transaction now
    }
}
