// LRU CACHE — O(1) get/put (C++17)
//
// HashMap for O(1) lookup + hand-built doubly linked list for O(1) reordering.
// Front = most-recently-used; tail = least-recently-used (evicted first).
// Uses dummy head/tail sentinels to avoid edge-case null checks.

#include <iostream>
#include <string>
#include <unordered_map>
using namespace std;

// The cache: ties an unordered_map and a hand-built doubly linked list together
// so both get() and put() are O(1).
class LRUCache {
    // One list element: holds the cached key/value plus pointers to its neighbours.
    struct Node {
        int key, value;
        Node *prev = nullptr, *next = nullptr;
        Node(int k, int v) : key(k), value(v) {}
    };

    int capacity;
    unordered_map<int, Node*> map;  // key -> node, for O(1) lookup
    Node* head; // head->next = MRU
    Node* tail; // tail->prev = LRU

    // Detach a node: make its neighbours point to each other, skipping over it.
    void remove(Node* n) {
        n->prev->next = n->next;   // node before n now points past n
        n->next->prev = n->prev;   // node after n now points back past n
    }
    // Splice a node in right after head, making it the new most-recently-used.
    void addFront(Node* n) {
        n->next = head->next;      // n points to the old first node
        n->prev = head;            // n points back to head
        head->next->prev = n;      // old first node points back to n
        head->next = n;            // head now points to n as the new front
    }

public:
    // Build an empty list bounded by two dummy sentinel nodes (head and tail).
    // They carry no real data but ensure every real node has valid neighbours.
    explicit LRUCache(int cap) : capacity(cap) {
        head = new Node(0, 0);
        tail = new Node(0, 0);
        head->next = tail;
        tail->prev = head;
    }
    // Free every node (including the sentinels) to avoid leaking heap memory.
    ~LRUCache() {
        Node* c = head;
        while (c) { Node* nxt = c->next; delete c; c = nxt; }
    }

    // Look up a key: return its value and promote it to MRU, or -1 on a miss.
    int get(int key) {
        auto it = map.find(key);
        if (it == map.end()) return -1;       // miss
        Node* n = it->second;
        remove(n); addFront(n);               // touch -> MRU
        return n->value;
    }

    // Insert or update a key; evict the least-recently-used entry if the cache
    // is full and the key is new.
    void put(int key, int value) {
        auto it = map.find(key);
        if (it != map.end()) {                // update + promote
            it->second->value = value;
            remove(it->second); addFront(it->second);
            return;
        }
        if ((int)map.size() == capacity) {    // evict LRU
            Node* lru = tail->prev;
            remove(lru);
            map.erase(lru->key);              // keep map and list in sync
            delete lru;                       // C++: also free the node's memory
        }
        Node* n = new Node(key, value);
        map[key] = n;                         // add to map and list together
        addFront(n);
    }

    string order() { // MRU -> LRU
        string s = "[";
        for (Node* c = head->next; c != tail; c = c->next) {
            s += to_string(c->key) + "=" + to_string(c->value);
            if (c->next != tail) s += ", ";
        }
        return s + "]";
    }
};

int main() {
    LRUCache cache(2);
    cache.put(1, 10);
    cache.put(2, 20);
    cout << "get(1)     = " << cache.get(1) << "\n"; // 10, promotes key 1
    cache.put(3, 30);                                // evicts key 2
    cout << "get(2)     = " << cache.get(2) << "\n"; // -1
    cout << "order MRU->LRU: " << cache.order() << "\n";
    cache.put(4, 40);                                // evicts key 1
    cout << "get(1)     = " << cache.get(1) << "\n"; // -1
    cout << "get(3)     = " << cache.get(3) << "\n"; // 30
    cout << "get(4)     = " << cache.get(4) << "\n"; // 40
    return 0;
}
