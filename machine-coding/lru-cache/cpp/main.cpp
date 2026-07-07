// LRU CACHE — O(1) get/put (C++17)
//
// HashMap for O(1) lookup + hand-built doubly linked list for O(1) reordering.
// Front = most-recently-used; tail = least-recently-used (evicted first).
// Uses dummy head/tail sentinels to avoid edge-case null checks.

#include <iostream>
#include <string>
#include <unordered_map>
using namespace std;

class LRUCache {
    struct Node {
        int key, value;
        Node *prev = nullptr, *next = nullptr;
        Node(int k, int v) : key(k), value(v) {}
    };

    int capacity;
    unordered_map<int, Node*> map;
    Node* head; // head->next = MRU
    Node* tail; // tail->prev = LRU

    void remove(Node* n) {
        n->prev->next = n->next;
        n->next->prev = n->prev;
    }
    void addFront(Node* n) {
        n->next = head->next;
        n->prev = head;
        head->next->prev = n;
        head->next = n;
    }

public:
    explicit LRUCache(int cap) : capacity(cap) {
        head = new Node(0, 0);
        tail = new Node(0, 0);
        head->next = tail;
        tail->prev = head;
    }
    ~LRUCache() {
        Node* c = head;
        while (c) { Node* nxt = c->next; delete c; c = nxt; }
    }

    int get(int key) {
        auto it = map.find(key);
        if (it == map.end()) return -1;       // miss
        Node* n = it->second;
        remove(n); addFront(n);               // touch -> MRU
        return n->value;
    }

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
            map.erase(lru->key);
            delete lru;
        }
        Node* n = new Node(key, value);
        map[key] = n;
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
