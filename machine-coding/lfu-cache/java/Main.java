import java.util.*;

/**
 * LFU CACHE — evict the Least-Frequently-Used key; break ties by least-recently-used.
 *
 * O(1) get and put using three maps:
 *   - values     : key -> value
 *   - counts     : key -> access frequency
 *   - freqLists  : frequency -> ordered set of keys with that frequency (LinkedHashSet
 *                  preserves insertion/recency order so ties evict the oldest).
 *   - minFreq    : the current smallest frequency (the eviction bucket).
 *
 * On access, a key moves from freqLists[f] to freqLists[f+1]. On insert past
 * capacity, we drop the first (oldest) key in freqLists[minFreq].
 */
public class Main {

    static class LFUCache {
        private final int capacity;
        private int minFreq = 0;
        private final Map<Integer, Integer> values = new HashMap<>();
        private final Map<Integer, Integer> counts = new HashMap<>();
        private final Map<Integer, LinkedHashSet<Integer>> freqLists = new HashMap<>();

        LFUCache(int capacity) { this.capacity = capacity; }

        int get(int key) {
            if (!values.containsKey(key)) return -1;
            touch(key);           // bump frequency
            return values.get(key);
        }

        void put(int key, int value) {
            if (capacity == 0) return;
            if (values.containsKey(key)) {   // update + bump
                values.put(key, value);
                touch(key);
                return;
            }
            if (values.size() == capacity) { // evict LFU (oldest within minFreq)
                LinkedHashSet<Integer> lowest = freqLists.get(minFreq);
                int evict = lowest.iterator().next();
                lowest.remove(evict);
                values.remove(evict);
                counts.remove(evict);
            }
            // Insert new key with frequency 1.
            values.put(key, value);
            counts.put(key, 1);
            freqLists.computeIfAbsent(1, k -> new LinkedHashSet<>()).add(key);
            minFreq = 1;                     // a fresh key is always the new min
        }

        // Move key from its current freq bucket to freq+1.
        private void touch(int key) {
            int f = counts.get(key);
            counts.put(key, f + 1);
            freqLists.get(f).remove(key);
            if (freqLists.get(f).isEmpty()) {
                freqLists.remove(f);
                if (minFreq == f) minFreq++;  // that bucket emptied; min shifts up
            }
            freqLists.computeIfAbsent(f + 1, k -> new LinkedHashSet<>()).add(key);
        }
    }

    public static void main(String[] args) {
        LFUCache c = new LFUCache(2);
        c.put(1, 10);
        c.put(2, 20);
        System.out.println("get(1) = " + c.get(1)); // 10 (freq of 1 -> 2)
        c.put(3, 30);                                // capacity full; key 2 has freq 1 -> evicted
        System.out.println("get(2) = " + c.get(2)); // -1
        System.out.println("get(3) = " + c.get(3)); // 30 (freq of 3 -> 2)
        c.put(4, 40);                                // tie freq(1)=2, freq(3)=2 -> evict LRU among them = key 1
        System.out.println("get(1) = " + c.get(1)); // -1
        System.out.println("get(3) = " + c.get(3)); // 30
        System.out.println("get(4) = " + c.get(4)); // 40
    }
}
