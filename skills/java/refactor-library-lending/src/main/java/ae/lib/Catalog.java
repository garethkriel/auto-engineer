package ae.lib;

import java.util.HashMap;
import java.util.Map;

/** Books, their copy counts and hold queues. Keys are trimmed ids. */
final class Catalog {
    private static final class Entry {
        int copies;
        final HoldQueue queue = new HoldQueue();
        Entry(int copies) { this.copies = copies; }
    }

    private final Map<String, Entry> entries = new HashMap<>();

    static String key(String id) { return id == null ? null : id.trim(); }

    boolean contains(String id) { return id != null && entries.containsKey(key(id)); }

    /** @return true if the book is new */
    boolean add(String id, int copies) {
        Entry e = entries.get(id);
        if (e == null) {
            entries.put(id, new Entry(copies));
            return true;
        }
        e.copies += copies;
        return false;
    }

    int copies(String id) { return entries.get(id).copies; }

    HoldQueue queue(String id) { return entries.get(id).queue; }
}
