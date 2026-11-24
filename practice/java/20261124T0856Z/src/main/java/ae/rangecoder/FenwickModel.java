package ae.rangecoder;

/** {@link FreqModel} backed by a Fenwick tree: O(log n) cumulative lookup, search and update. */
public final class FenwickModel implements FreqModel {
    /** Frequency added per observation. */
    public static final int INCREMENT = 32;
    /** Maximum total frequency; the model halves itself before exceeding it. */
    public static final int LIMIT = 1 << 16;

    private final int n;
    private final int[] freq;
    private final int[] tree; // 1-based
    private final int topBit;
    private int total;

    /** @param n number of symbols, {@code 1 <= n <= LIMIT/4} */
    public FenwickModel(int n) {
        if (n < 1 || n > LIMIT / 4) throw new IllegalArgumentException("symbols " + n);
        this.n = n;
        this.freq = new int[n];
        this.tree = new int[n + 1];
        this.topBit = Integer.highestOneBit(n);
        java.util.Arrays.fill(freq, 1);
        rebuild();
    }

    @Override
    public int total() {
        return total;
    }

    @Override
    public int cumFreq(int sym) {
        if (sym < 0 || sym > n) throw new IllegalArgumentException("symbol " + sym);
        int s = 0;
        for (int i = sym; i > 0; i -= i & -i) s += tree[i];
        return s;
    }

    @Override
    public int freq(int sym) {
        check(sym);
        return freq[sym];
    }

    @Override
    public int findSymbol(int target) {
        if (target < 0 || target >= total) throw new IllegalArgumentException("target " + target);
        int pos = 0;
        int rest = target;
        for (int bit = topBit; bit != 0; bit >>= 1) {
            int next = pos + bit;
            if (next <= n && tree[next] <= rest) {
                pos = next;
                rest -= tree[next];
            }
        }
        return pos;
    }

    @Override
    public void update(int sym) {
        check(sym);
        if (total + INCREMENT > LIMIT) {
            for (int i = 0; i < n; i++) freq[i] = (freq[i] + 1) >>> 1;
            rebuild();
        }
        freq[sym] += INCREMENT;
        total += INCREMENT;
        for (int i = sym + 1; i <= n; i += i & -i) tree[i] += INCREMENT;
    }

    private void check(int sym) {
        if (sym < 0 || sym >= n) throw new IllegalArgumentException("symbol " + sym);
    }

    private void rebuild() {
        java.util.Arrays.fill(tree, 0);
        total = 0;
        for (int i = 0; i < n; i++) {
            total += freq[i];
            tree[i + 1] += freq[i];
            int parent = (i + 1) + ((i + 1) & -(i + 1));
            if (parent <= n) tree[parent] += tree[i + 1];
        }
    }
}
