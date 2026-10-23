import java.util.*;

/** Trie (prefix tree): insert and prefix lookup cost O(k), where k = length of the word typed. */
public class Trie {

    /** A searchable stock entry: key = medicine name or equipment ID. */
    public static class Item {
        public final String key, desc, type;
        public final int qty;
        public Item(String key, String desc, String type, int qty) {
            this.key = key; this.desc = desc; this.type = type; this.qty = qty;
        }
    }

    private static class Node {
        final Map<Character, Node> kids = new TreeMap<>();   // sorted children -> alphabetical results
        Item item;                                            // set only where a word ends
    }

    private final Node root = new Node();

    /** O(k): walk/create one node per character. */
    public void insert(Item it) {
        Node cur = root;
        for (char ch : it.key.toLowerCase().toCharArray()) {
            Node next = cur.kids.get(ch);
            if (next == null) { next = new Node(); cur.kids.put(ch, next); }
            cur = next;
        }
        cur.item = it;
    }

    /** O(k) to reach the prefix node, then a DFS that stops after `limit` results. */
    public List<Item> autocomplete(String prefix, int limit) {
        Node cur = root;
        for (char ch : prefix.toLowerCase().toCharArray()) {
            cur = cur.kids.get(ch);
            if (cur == null) return new ArrayList<>();
        }
        List<Item> out = new ArrayList<>();
        collect(cur, out, limit);
        return out;
    }

    private void collect(Node n, List<Item> out, int limit) {
        if (out.size() >= limit) return;
        if (n.item != null) out.add(n.item);
        for (Node child : n.kids.values()) collect(child, out, limit);
    }
}
