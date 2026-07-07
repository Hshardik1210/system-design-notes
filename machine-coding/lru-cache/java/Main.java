import java.util.*;

/**
 * LRU CACHE — O(1) get and put.
 *
 * Idea: combine two structures.
 *   - HashMap<key, Node>        -> O(1) lookup of a node by key.
 *   - Doubly linked list        -> O(1) move-to-front / remove of a node.
 * Front of the list = most-recently-used, tail = least-recently-used (evict tail).
 *
 * We build the doubly linked list by hand (with dummy head/tail sentinels) so we
 * fully control ordering — this is what interviewers want to see.
 */
public class Main {

    // One element of the doubly linked list. It stores the cached key/value and
    // pointers to the previous and next node, so we can walk the list both ways.
    static class Node {
        int key, value;
        Node prev, next;
        Node(int k, int v) { key = k; value = v; }
    }

    // The cache itself: it wires the HashMap and the doubly linked list together
    // so both get() and put() run in O(1) time.
    static class LRUCache {
        private final int capacity;
        // Maps a key straight to its Node, so lookups are O(1) (no list scanning).
        private final Map<Integer, Node> map = new HashMap<>();
        // Sentinels: head.next = MRU, tail.prev = LRU. Avoids null checks.
        // "Dummy" nodes that always sit at the two ends of the list. They hold no
        // real data; they just guarantee every real node has a prev and a next,
        // so the relinking code never has to special-case the ends.
        private final Node head = new Node(0, 0);
        private final Node tail = new Node(0, 0);

        LRUCache(int capacity) {
            this.capacity = capacity;
            // Start with an empty list: head and tail point at each other.
            head.next = tail;
            tail.prev = head;
        }

        // Unlink a node from the list.
        // Detaches a node by making its neighbours point to each other,
        // skipping over the node. The node's own pointers are left as-is (that is
        // fine, because callers either drop it or immediately re-insert it).
        private void remove(Node n) {
            n.prev.next = n.next;   // node before n now points past n
            n.next.prev = n.prev;   // node after n now points back past n
        }
        // Insert a node right after head (mark as most-recently-used).
        // Splices n between head and the current first real node, so n becomes
        // the new front (most-recently-used).
        private void addFront(Node n) {
            n.next = head.next;     // n points to the old first node
            n.prev = head;          // n points back to head
            head.next.prev = n;     // old first node points back to n
            head.next = n;          // head now points to n as the new front
        }

        // Look up a key. Returns its value, or -1 if not present. On a hit we also
        // mark the node as most-recently-used by moving it to the front.
        int get(int key) {
            Node n = map.get(key);
            if (n == null) return -1;      // miss
            remove(n); addFront(n);        // touch -> becomes MRU
            return n.value;
        }

        // Insert or update a key. If the cache is full and the key is new, we first
        // evict the least-recently-used entry (the node just before tail).
        void put(int key, int value) {
            Node existing = map.get(key);
            if (existing != null) {        // update + promote
                existing.value = value;
                remove(existing); addFront(existing);
                return;
            }
            if (map.size() == capacity) {  // evict LRU (node before tail)
                Node lru = tail.prev;
                remove(lru);
                map.remove(lru.key);       // keep map and list in sync: drop it from both
            }
            Node n = new Node(key, value);
            map.put(key, n);               // add to map and list together
            addFront(n);
        }

        // Debug helper: print MRU -> LRU order.
        String order() {
            StringBuilder sb = new StringBuilder("[");
            for (Node c = head.next; c != tail; c = c.next)
                sb.append(c.key).append("=").append(c.value).append(c.next != tail ? ", " : "");
            return sb.append("]").toString();
        }
    }

    public static void main(String[] args) {
        LRUCache cache = new LRUCache(2);
        cache.put(1, 10);
        cache.put(2, 20);
        System.out.println("get(1)     = " + cache.get(1)); // 10, promotes key 1
        cache.put(3, 30);                                   // capacity full -> evicts key 2 (LRU)
        System.out.println("get(2)     = " + cache.get(2)); // -1 (evicted)
        System.out.println("order MRU->LRU: " + cache.order());
        cache.put(4, 40);                                   // evicts key 1
        System.out.println("get(1)     = " + cache.get(1)); // -1
        System.out.println("get(3)     = " + cache.get(3)); // 30
        System.out.println("get(4)     = " + cache.get(4)); // 40
    }
}
