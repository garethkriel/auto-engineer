package ae.lib;

import java.util.ArrayList;
import java.util.List;

/** FIFO of members waiting for a book, bounded. */
final class HoldQueue {
    static final int MAX = 3;
    private final List<String> waiting = new ArrayList<>();

    boolean isEmpty() { return waiting.isEmpty(); }
    boolean isFull() { return waiting.size() >= MAX; }
    int size() { return waiting.size(); }
    boolean contains(String member) { return waiting.contains(member); }
    boolean isHead(String member) { return !waiting.isEmpty() && waiting.get(0).equals(member); }
    void removeHead() { waiting.remove(0); }

    /** Appends and returns the 1-based position. */
    int enqueue(String member) {
        waiting.add(member);
        return waiting.size();
    }
}
