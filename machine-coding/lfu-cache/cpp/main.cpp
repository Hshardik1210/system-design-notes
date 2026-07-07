// LFU CACHE — evict Least-Frequently-Used; tie-break by Least-Recently-Used (C++17)
//
// O(1) get/put using:
//   values  : key -> value
//   counts  : key -> frequency
//   freqList: freq -> list<key> in recency order (front = oldest = evict first)
//   keyIter : key -> iterator into its freq list (for O(1) removal)
//   minFreq : smallest frequency bucket (eviction target)

#include <iostream>
#include <list>
#include <unordered_map>
using namespace std;

// The LFU cache: holds up to `capacity` entries and evicts the least-frequently-used
// key when full (the oldest such key on a tie). All operations are O(1).
class LFUCache {
    int capacity;
    int minFreq = 0;                                        // smallest frequency in use = the bucket we evict from
    unordered_map<int, int> values;                         // key -> value
    unordered_map<int, int> counts;                         // key -> access frequency
    unordered_map<int, list<int>> freqList;                 // freq -> keys (front = oldest)
    unordered_map<int, list<int>::iterator> keyIter;        // key -> position in its list

    // Called on every access: move a key from frequency f to f+1.
    // Keeping the stored iterator lets us splice the key out in O(1).
    void touch(int key) {
        int f = counts[key];
        counts[key] = f + 1;                                // record one more access
        freqList[f].erase(keyIter[key]);                    // O(1) remove
        if (freqList[f].empty()) {
            freqList.erase(f);
            if (minFreq == f) minFreq++;                    // old bucket emptied; min shifts up
        }
        freqList[f + 1].push_back(key);                     // append to the higher bucket (now the most recent there)
        keyIter[key] = prev(freqList[f + 1].end());         // remember its new position for future O(1) removal
    }

public:
    explicit LFUCache(int cap) : capacity(cap) {}

    // Return the value for key and count it as an access, or -1 if the key is absent.
    int get(int key) {
        if (!values.count(key)) return -1;
        touch(key);
        return values[key];
    }

    // Insert or update a key. On a new key when full, evict the LFU key first.
    void put(int key, int value) {
        if (capacity == 0) return;                          // a zero-size cache holds nothing
        if (values.count(key)) { values[key] = value; touch(key); return; }  // existing key: update + bump
        if ((int)values.size() == capacity) {               // evict oldest in minFreq bucket
            int evict = freqList[minFreq].front();          // front = oldest key in the lowest-frequency bucket
            freqList[minFreq].pop_front();
            if (freqList[minFreq].empty()) freqList.erase(minFreq);
            values.erase(evict); counts.erase(evict); keyIter.erase(evict);
        }
        values[key] = value;
        counts[key] = 1;
        freqList[1].push_back(key);
        keyIter[key] = prev(freqList[1].end());
        minFreq = 1;                                        // a fresh key always has the smallest frequency
    }
};

// Small demo showing both eviction rules: least-frequently-used first, and
// least-recently-used as the tie-breaker.
int main() {
    LFUCache c(2);
    c.put(1, 10);
    c.put(2, 20);
    cout << "get(1) = " << c.get(1) << "\n"; // 10
    c.put(3, 30);                            // evicts key 2 (freq 1)
    cout << "get(2) = " << c.get(2) << "\n"; // -1
    cout << "get(3) = " << c.get(3) << "\n"; // 30
    c.put(4, 40);                            // tie -> evicts LRU (key 1)
    cout << "get(1) = " << c.get(1) << "\n"; // -1
    cout << "get(3) = " << c.get(3) << "\n"; // 30
    cout << "get(4) = " << c.get(4) << "\n"; // 40
    return 0;
}
