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

class LFUCache {
    int capacity;
    int minFreq = 0;
    unordered_map<int, int> values;
    unordered_map<int, int> counts;
    unordered_map<int, list<int>> freqList;                 // freq -> keys (front = oldest)
    unordered_map<int, list<int>::iterator> keyIter;        // key -> position in its list

    void touch(int key) {
        int f = counts[key];
        counts[key] = f + 1;
        freqList[f].erase(keyIter[key]);                    // O(1) remove
        if (freqList[f].empty()) {
            freqList.erase(f);
            if (minFreq == f) minFreq++;
        }
        freqList[f + 1].push_back(key);
        keyIter[key] = prev(freqList[f + 1].end());
    }

public:
    explicit LFUCache(int cap) : capacity(cap) {}

    int get(int key) {
        if (!values.count(key)) return -1;
        touch(key);
        return values[key];
    }

    void put(int key, int value) {
        if (capacity == 0) return;
        if (values.count(key)) { values[key] = value; touch(key); return; }
        if ((int)values.size() == capacity) {               // evict oldest in minFreq bucket
            int evict = freqList[minFreq].front();
            freqList[minFreq].pop_front();
            if (freqList[minFreq].empty()) freqList.erase(minFreq);
            values.erase(evict); counts.erase(evict); keyIter.erase(evict);
        }
        values[key] = value;
        counts[key] = 1;
        freqList[1].push_back(key);
        keyIter[key] = prev(freqList[1].end());
        minFreq = 1;
    }
};

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
