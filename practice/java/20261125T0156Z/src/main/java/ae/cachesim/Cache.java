package ae.cachesim;

import java.util.SortedSet;
import java.util.SplittableRandom;
import java.util.TreeSet;

/** Set-associative cache simulator working on byte addresses. Not thread-safe. */
public final class Cache {
    private final CacheConfig cfg;
    private final int ways;
    private final int setMask;
    private final long[] lines;
    private final boolean[] valid;
    private final boolean[] dirty;
    private final long[] lastUse;
    private final long[] inserted;
    private final int[] plru;
    private final int levels;
    private final SplittableRandom rng;
    private long clock;
    private long reads;
    private long writes;
    private long readHits;
    private long writeHits;
    private long evictions;
    private long writebacks;
    private long memReads;
    private long memWrites;

    public Cache(CacheConfig cfg) {
        this.cfg = java.util.Objects.requireNonNull(cfg);
        this.ways = cfg.ways();
        int sets = cfg.sets();
        this.setMask = sets - 1;
        int n = sets * ways;
        lines = new long[n];
        valid = new boolean[n];
        dirty = new boolean[n];
        lastUse = new long[n];
        inserted = new long[n];
        plru = new int[sets];
        levels = Integer.numberOfTrailingZeros(ways);
        rng = new SplittableRandom(cfg.seed());
    }

    public int lineBytes() {
        return cfg.lineBytes();
    }

    public CacheConfig config() {
        return cfg;
    }

    /** Simulates one load or store of the byte at {@code addr}. */
    public AccessResult access(long addr, boolean write) {
        if (addr < 0) {
            throw new IllegalArgumentException("negative address: " + addr);
        }
        long line = addr / cfg.lineBytes();
        int set = (int) (line & setMask);
        int base = set * ways;
        if (write) writes++;
        else reads++;

        for (int w = 0; w < ways; w++) {
            if (valid[base + w] && lines[base + w] == line) {
                boolean through = false;
                if (write) {
                    writeHits++;
                    if (cfg.writeBack()) {
                        dirty[base + w] = true;
                    } else {
                        memWrites++;
                        through = true;
                    }
                } else {
                    readHits++;
                }
                touch(set, w);
                return new AccessResult(true, false, AccessResult.NONE, false, through);
            }
        }

        if (write && !cfg.writeBack()) {
            memWrites++;
            return new AccessResult(false, false, AccessResult.NONE, false, true);
        }

        int victim = chooseVictim(set);
        int idx = base + victim;
        long evicted = AccessResult.NONE;
        boolean wb = false;
        if (valid[idx]) {
            evicted = lines[idx];
            evictions++;
            if (dirty[idx]) {
                wb = true;
                writebacks++;
                memWrites++;
            }
        }
        lines[idx] = line;
        valid[idx] = true;
        dirty[idx] = write; // only reachable for write-back caches when write is true
        memReads++;
        inserted[idx] = ++clock;
        touch(set, victim);
        return new AccessResult(false, true, evicted, wb, false);
    }

    private int chooseVictim(int set) {
        int base = set * ways;
        for (int w = 0; w < ways; w++) {
            if (!valid[base + w]) return w;
        }
        switch (cfg.policy()) {
            case LRU: {
                int best = 0;
                for (int w = 1; w < ways; w++) {
                    if (lastUse[base + w] < lastUse[base + best]) best = w;
                }
                return best;
            }
            case FIFO: {
                int best = 0;
                for (int w = 1; w < ways; w++) {
                    if (inserted[base + w] < inserted[base + best]) best = w;
                }
                return best;
            }
            case PLRU: {
                int node = 1;
                for (int d = 0; d < levels; d++) {
                    node = 2 * node + ((plru[set] >> node) & 1);
                }
                return node - ways;
            }
            default:
                return rng.nextInt(ways);
        }
    }

    /** Records a use of {@code way} for LRU timestamps and PLRU tree bits (bit set = victim lies to the right). */
    private void touch(int set, int way) {
        lastUse[set * ways + way] = ++clock;
        if (cfg.policy() == Policy.PLRU) {
            int node = 1;
            int bits = plru[set];
            for (int d = levels - 1; d >= 0; d--) {
                int b = (way >> d) & 1;
                if (b == 0) bits |= 1 << node;
                else bits &= ~(1 << node);
                node = 2 * node + b;
            }
            plru[set] = bits;
        }
    }

    /** Whether the line holding {@code addr} is resident (does not change any state). */
    public boolean contains(long addr) {
        long line = addr / cfg.lineBytes();
        int base = (int) (line & setMask) * ways;
        for (int w = 0; w < ways; w++) {
            if (valid[base + w] && lines[base + w] == line) return true;
        }
        return false;
    }

    /** Sorted line numbers currently resident. */
    public SortedSet<Long> residentLines() {
        TreeSet<Long> out = new TreeSet<>();
        for (int i = 0; i < lines.length; i++) {
            if (valid[i]) out.add(lines[i]);
        }
        return out;
    }

    /** Writes back every dirty line (lines stay resident, clean) and returns how many were written. */
    public int flush() {
        int n = 0;
        for (int i = 0; i < lines.length; i++) {
            if (valid[i] && dirty[i]) {
                dirty[i] = false;
                n++;
            }
        }
        writebacks += n;
        memWrites += n;
        return n;
    }

    public CacheStats stats() {
        return new CacheStats(reads, writes, readHits, writeHits, evictions, writebacks, memReads, memWrites);
    }
}
