package ae.gc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A simulated word-addressed heap with a coalescing free-list allocator, a mark-sweep collector and
 * a sliding compactor. Objects are addressed through stable integer handles; see TASK.md for the
 * layout and rules.
 */
public final class Heap {

    /** Occupancy summary; {@code usedWords + freeWords == capacity}. */
    public record Stats(int capacity, int usedWords, int freeWords, int freeExtents, int largestFree, int liveObjects) {}

    /** Result of one collection. */
    public record GcResult(int freedObjects, int freedWords) {}

    private static final class Obj {
        int addr;
        final int dataWords;
        final int refSlots;

        Obj(int addr, int dataWords, int refSlots) {
            this.addr = addr;
            this.dataWords = dataWords;
            this.refSlots = refSlots;
        }

        int size() {
            return 1 + dataWords + refSlots;
        }
    }

    private final int capacity;
    private final FitPolicy policy;
    private final boolean autoCollect;
    private final int[] mem;
    private final TreeMap<Integer, Integer> free = new TreeMap<>(); // address -> length
    private final TreeMap<Integer, Obj> objects = new TreeMap<>(); // handle -> object
    private final Set<Integer> roots = new TreeSet<>();
    private final List<FreeListener> listeners = new ArrayList<>();
    private int nextHandle = 1;

    public Heap(int capacity, FitPolicy policy, boolean autoCollect) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive: " + capacity);
        this.policy = Objects.requireNonNull(policy, "policy");
        this.capacity = capacity;
        this.autoCollect = autoCollect;
        this.mem = new int[capacity];
        free.put(0, capacity);
    }

    // ---- allocation ----

    /** Allocates an object with zeroed data words and null refs and returns its handle. */
    public int alloc(int dataWords, int refSlots) {
        if (dataWords < 0 || refSlots < 0) {
            throw new IllegalArgumentException("negative size: " + dataWords + "," + refSlots);
        }
        long need = 1L + dataWords + refSlots;
        if (need > capacity) throw new HeapFullException("block of " + need + " words exceeds capacity " + capacity);
        int size = (int) need;
        int addr = findExtent(size);
        if (addr < 0 && autoCollect) {
            gc();
            addr = findExtent(size);
            if (addr < 0) {
                compact();
                addr = findExtent(size);
            }
        }
        if (addr < 0) throw new HeapFullException("no extent of " + size + " words");
        carve(addr, size);
        int handle = nextHandle++;
        objects.put(handle, new Obj(addr, dataWords, refSlots));
        java.util.Arrays.fill(mem, addr + 1, addr + size, 0);
        mem[addr] = handle;
        return handle;
    }

    private int findExtent(int size) {
        int bestAddr = -1;
        int bestLen = Integer.MAX_VALUE;
        for (Map.Entry<Integer, Integer> e : free.entrySet()) {
            int len = e.getValue();
            if (len < size) continue;
            if (policy == FitPolicy.FIRST_FIT) return e.getKey();
            if (len < bestLen) { // strict: ties keep the lower address
                bestLen = len;
                bestAddr = e.getKey();
            }
        }
        return bestAddr;
    }

    private void carve(int addr, int size) {
        int len = free.remove(addr);
        if (len > size) free.put(addr + size, len - size);
    }

    private void release(int addr, int size) {
        int start = addr;
        int len = size;
        Map.Entry<Integer, Integer> prev = free.lowerEntry(addr);
        if (prev != null && prev.getKey() + prev.getValue() == addr) {
            start = prev.getKey();
            len += prev.getValue();
            free.remove(start);
        }
        Integer nextLen = free.get(addr + size);
        if (nextLen != null) {
            free.remove(addr + size);
            len += nextLen;
        }
        free.put(start, len);
    }

    // ---- roots and access ----

    public void addRoot(int handle) {
        live(handle);
        roots.add(handle);
    }

    public void removeRoot(int handle) {
        roots.remove(handle);
    }

    public void addFreeListener(FreeListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public boolean isLive(int handle) {
        return objects.containsKey(handle);
    }

    public List<Integer> liveHandles() {
        return new ArrayList<>(objects.keySet());
    }

    public int addressOf(int handle) {
        return live(handle).addr;
    }

    public int sizeOf(int handle) {
        return live(handle).size();
    }

    public int getData(int handle, int index) {
        Obj o = live(handle);
        checkIndex(index, o.dataWords);
        return mem[o.addr + 1 + index];
    }

    public void setData(int handle, int index, int value) {
        Obj o = live(handle);
        checkIndex(index, o.dataWords);
        mem[o.addr + 1 + index] = value;
    }

    public int getRef(int handle, int index) {
        Obj o = live(handle);
        checkIndex(index, o.refSlots);
        return mem[o.addr + 1 + o.dataWords + index];
    }

    public void setRef(int handle, int index, int target) {
        Obj o = live(handle);
        checkIndex(index, o.refSlots);
        if (target != 0) live(target);
        mem[o.addr + 1 + o.dataWords + index] = target;
    }

    private Obj live(int handle) {
        Obj o = objects.get(handle);
        if (o == null) throw new IllegalStateException("not a live handle: " + handle);
        return o;
    }

    private static void checkIndex(int index, int limit) {
        if (index < 0 || index >= limit) throw new IndexOutOfBoundsException("index " + index + " of " + limit);
    }

    // ---- collection ----

    /** Mark from the roots, then sweep everything unmarked. */
    public GcResult gc() {
        Set<Integer> marked = new HashSet<>();
        Deque<Integer> work = new ArrayDeque<>(roots);
        while (!work.isEmpty()) {
            int h = work.pop();
            if (!marked.add(h)) continue;
            Obj o = objects.get(h);
            int base = o.addr + 1 + o.dataWords;
            for (int i = 0; i < o.refSlots; i++) {
                int t = mem[base + i];
                if (t != 0 && !marked.contains(t)) work.push(t);
            }
        }
        List<Integer> dead = new ArrayList<>();
        for (int h : objects.keySet()) if (!marked.contains(h)) dead.add(h); // ascending
        int words = 0;
        for (int h : dead) {
            Obj o = objects.remove(h);
            release(o.addr, o.size());
            words += o.size();
        }
        if (!dead.isEmpty()) {
            List<Integer> view = List.copyOf(dead);
            for (FreeListener l : new ArrayList<>(listeners)) l.onFreed(view);
        }
        return new GcResult(dead.size(), words);
    }

    /** Slides live blocks down, preserving address order. Returns how many objects moved. */
    public int compact() {
        TreeMap<Integer, Integer> byAddr = new TreeMap<>();
        for (Map.Entry<Integer, Obj> e : objects.entrySet()) byAddr.put(e.getValue().addr, e.getKey());
        int cursor = 0;
        int moved = 0;
        for (int handle : byAddr.values()) {
            Obj o = objects.get(handle);
            if (o.addr != cursor) {
                System.arraycopy(mem, o.addr, mem, cursor, o.size());
                o.addr = cursor;
                moved++;
            }
            cursor += o.size();
        }
        free.clear();
        if (cursor < capacity) free.put(cursor, capacity - cursor);
        return moved;
    }

    public Stats stats() {
        int freeWords = 0;
        int largest = 0;
        for (int len : free.values()) {
            freeWords += len;
            largest = Math.max(largest, len);
        }
        return new Stats(capacity, capacity - freeWords, freeWords, free.size(), largest, objects.size());
    }

    /** Verifies structural invariants; throws {@link AssertionError} when one is broken. */
    void checkInvariants() {
        TreeMap<Integer, Integer> tiles = new TreeMap<>(); // address -> length of every block or extent
        for (Map.Entry<Integer, Obj> e : objects.entrySet()) {
            Obj o = e.getValue();
            if (tiles.put(o.addr, o.size()) != null) throw new AssertionError("two blocks at " + o.addr);
            if (mem[o.addr] != e.getKey()) throw new AssertionError("bad header at " + o.addr);
            for (int i = 0; i < o.refSlots; i++) {
                int t = mem[o.addr + 1 + o.dataWords + i];
                if (t != 0 && !objects.containsKey(t)) throw new AssertionError("dangling ref " + t);
            }
        }
        int prevEnd = -1;
        for (Map.Entry<Integer, Integer> e : free.entrySet()) {
            if (e.getValue() <= 0) throw new AssertionError("empty extent at " + e.getKey());
            if (e.getKey() == prevEnd) throw new AssertionError("adjacent extents at " + prevEnd);
            prevEnd = e.getKey() + e.getValue();
            if (tiles.put(e.getKey(), e.getValue()) != null) throw new AssertionError("overlap at " + e.getKey());
        }
        int expected = 0;
        for (Map.Entry<Integer, Integer> t : tiles.entrySet()) {
            if (t.getKey() != expected) throw new AssertionError("gap or overlap at " + expected);
            expected += t.getValue();
        }
        if (expected != capacity) throw new AssertionError("tiles cover " + expected + " of " + capacity);
        for (int r : roots) if (!objects.containsKey(r)) throw new AssertionError("dead root " + r);
    }
}
