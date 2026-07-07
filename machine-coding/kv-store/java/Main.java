import java.util.*;

/**
 * IN-MEMORY KEY-VALUE STORE with nested transactions.
 *
 * Supports: set / get / delete, and BEGIN / COMMIT / ROLLBACK that can nest.
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
    private static final String ABSENT = null;

    static class KVStore {
        private final Map<String, String> committed = new HashMap<>();
        // Stack of undo logs; one per open transaction.
        private final Deque<Map<String, String>> txStack = new ArrayDeque<>();

        String get(String key) {
            return committed.getOrDefault(key, "NULL");
        }

        void set(String key, String value) {
            recordUndo(key);
            committed.put(key, value);
        }

        void delete(String key) {
            recordUndo(key);
            committed.remove(key);
        }

        void begin() {
            txStack.push(new HashMap<>()); // new undo log
        }

        boolean rollback() {
            if (txStack.isEmpty()) { System.out.println("  ! no transaction"); return false; }
            Map<String, String> undo = txStack.pop();
            // Restore each key to its recorded prior value.
            for (Map.Entry<String, String> e : undo.entrySet()) {
                if (e.getValue() == ABSENT) committed.remove(e.getKey()); // didn't exist before
                else committed.put(e.getKey(), e.getValue());
            }
            return true;
        }

        boolean commit() {
            if (txStack.isEmpty()) { System.out.println("  ! no transaction"); return false; }
            Map<String, String> undo = txStack.pop();
            if (!txStack.isEmpty()) {
                // Merge into parent's undo log WITHOUT overwriting values it already
                // recorded (the parent must remember the value before IT started).
                Map<String, String> parent = txStack.peek();
                for (Map.Entry<String, String> e : undo.entrySet())
                    parent.putIfAbsent(e.getKey(), e.getValue());
            }
            // If no parent, changes are already in 'committed' -> permanent.
            return true;
        }

        // Record the current value of 'key' in the innermost transaction (once).
        private void recordUndo(String key) {
            if (txStack.isEmpty()) return; // no transaction: change is directly durable
            Map<String, String> undo = txStack.peek();
            if (!undo.containsKey(key))
                undo.put(key, committed.containsKey(key) ? committed.get(key) : ABSENT);
        }
    }

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
