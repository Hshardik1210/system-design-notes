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

    // Doubly linked list node.
    static class Node {
        int key, value;
        Node prev, next;
        Node(int k, int v) { key = k; value = v; }
    }

    static class LRUCache {
        private final int capacity;
        private final Map<Integer, Node> map = new HashMap<>();
        // Sentinels: head.next = MRU, tail.prev = LRU. Avoids null checks.
        private final Node head = new Node(0, 0);
        private final Node tail = new Node(0, 0);

        LRUCache(int capacity) {
            this.capacity = capacity;
            head.next = tail;
            tail.prev = head;
        }

        // Unlink a node from the list.
        private void remove(Node n) {
            n.prev.next = n.next;
            n.next.prev = n.prev;
        }
        // Insert a node right after head (mark as most-recently-used).
        private void addFront(Node n) {
            n.next = head.next;
            n.prev = head;
            head.next.prev = n;
            head.next = n;
        }

        int get(int key) {
            Node n = map.get(key);
            if (n == null) return -1;      // miss
            remove(n); addFront(n);        // touch -> becomes MRU
            return n.value;
        }

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
                map.remove(lru.key);
            }
            Node n = new Node(key, value);
            map.put(key, n);
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
