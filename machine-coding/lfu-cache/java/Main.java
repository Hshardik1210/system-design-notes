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

    // The LFU cache itself: stores up to `capacity` key/value pairs and, when full,
    // throws out the key that has been used the fewest times (oldest one on a tie).
    static class LFUCache {
        private final int capacity;
        private int minFreq = 0;                                                        // smallest frequency currently in use = the bucket we evict from
        private final Map<Integer, Integer> values = new HashMap<>();                   // key -> stored value
        private final Map<Integer, Integer> counts = new HashMap<>();                   // key -> how many times it has been accessed
        private final Map<Integer, LinkedHashSet<Integer>> freqLists = new HashMap<>(); // frequency -> keys at that frequency; LinkedHashSet keeps recency order

        LFUCache(int capacity) { this.capacity = capacity; }

        // Look up a key. Returns its value and counts as an access (so its frequency goes up),
        // or returns -1 if the key is not present.
        int get(int key) {
            if (!values.containsKey(key)) return -1;
            touch(key);           // bump frequency
            return values.get(key);
        }

        // Insert or update a key. If the cache is full and this is a brand-new key,
        // first evict the least-frequently-used key to make room.
        void put(int key, int value) {
            if (capacity == 0) return;       // a zero-size cache can never hold anything
            if (values.containsKey(key)) {   // update + bump
                values.put(key, value);
                touch(key);
                return;
            }
            if (values.size() == capacity) { // evict LFU (oldest within minFreq)
                LinkedHashSet<Integer> lowest = freqLists.get(minFreq);
                int evict = lowest.iterator().next();   // first element = oldest key in the lowest-frequency bucket
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
        // Called on every access; this is what makes "frequently used" keys survive.
        private void touch(int key) {
            int f = counts.get(key);
            counts.put(key, f + 1);           // record one more access
            freqLists.get(f).remove(key);     // take key out of its old frequency bucket
            if (freqLists.get(f).isEmpty()) {
                freqLists.remove(f);
                if (minFreq == f) minFreq++;  // that bucket emptied; min shifts up
            }
            freqLists.computeIfAbsent(f + 1, k -> new LinkedHashSet<>()).add(key);   // add key to the next-higher bucket
        }
    }

    // Small demo that walks through eviction: capacity 2, then puts/gets that trigger
    // both least-frequently-used and (on a tie) least-recently-used eviction.
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
