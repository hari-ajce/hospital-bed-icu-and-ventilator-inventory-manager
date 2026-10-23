import java.util.*;

/**
 * LRU cache = HashMap (O(1) lookup) + doubly linked list (O(1) move-to-front and eviction).
 * Head side = most recently used. Used to track recently freed / assigned ICU beds.
 */
public class LRUCache {

    public static class Node {
        public String key, event;     // key = bed id, event = FREED or ASSIGNED
        public long time;             // when the event happened (ms)
        Node prev, next;
    }

    private final int capacity;
    private final Map<String, Node> map = new HashMap<>();
    private final Node head = new Node(), tail = new Node();   // dummy sentinels

    public LRUCache(int capacity) {
        this.capacity = capacity;
        head.next = tail;
        tail.prev = head;
    }

    private void unlink(Node n) { n.prev.next = n.next; n.next.prev = n.prev; }

    private void addFront(Node n) {
        n.next = head.next; n.prev = head;
        head.next.prev = n; head.next = n;
    }

    /** O(1): record an event for a bed and make it the most recent; evict the oldest when full. */
    public void put(String key, String event) {
        Node n = map.get(key);
        if (n == null) { n = new Node(); n.key = key; map.put(key, n); } else unlink(n);
        n.event = event;
        n.time = System.currentTimeMillis();
        addFront(n);
        if (map.size() > capacity) {
            Node lru = tail.prev;
            unlink(lru);
            map.remove(lru.key);
        }
    }

    /** O(1): look a bed up and mark it as most recently used. */
    public Node get(String key) {
        Node n = map.get(key);
        if (n != null) { unlink(n); addFront(n); }
        return n;
    }

    /** Entries from most to least recently used. */
    public List<Node> toList() {
        List<Node> out = new ArrayList<>();
        for (Node n = head.next; n != tail; n = n.next) out.add(n);
        return out;
    }
}
