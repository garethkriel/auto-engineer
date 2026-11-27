package ae.xsort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * K-way merge of sorted iterators with a tournament (loser) tree: each output costs about log2(k)
 * comparisons. Ties between sources go to the lower source index, so merging runs in creation order
 * is stable. Leaf of source {@code i} is node {@code k + i}; internal nodes {@code 1..k-1} store the
 * loser of their match and {@code winner} is the overall winner.
 */
final class LoserTree<T> implements Iterator<T> {
    private final int k;
    private final Comparator<? super T> cmp;
    private final List<Iterator<T>> sources;
    private final Object[] heads;
    private final boolean[] alive;
    private final int[] loser;
    private int winner;

    LoserTree(List<? extends Iterator<T>> sources, Comparator<? super T> cmp) {
        if (sources.isEmpty()) throw new IllegalArgumentException("need at least one source");
        this.k = sources.size();
        this.cmp = cmp;
        this.sources = new ArrayList<>(sources);
        this.heads = new Object[k];
        this.alive = new boolean[k];
        this.loser = new int[k];
        for (int i = 0; i < k; i++) advance(i);
        int[] win = new int[2 * k];
        for (int i = 0; i < k; i++) win[k + i] = i;
        for (int n = k - 1; n >= 1; n--) {
            int l = win[2 * n];
            int r = win[2 * n + 1];
            if (beats(l, r)) {
                win[n] = l;
                loser[n] = r;
            } else {
                win[n] = r;
                loser[n] = l;
            }
        }
        winner = k == 1 ? 0 : win[1];
    }

    @SuppressWarnings("unchecked")
    private T head(int i) {
        return (T) heads[i];
    }

    private void advance(int i) {
        if (sources.get(i).hasNext()) {
            heads[i] = sources.get(i).next();
            alive[i] = true;
        } else {
            heads[i] = null;
            alive[i] = false;
        }
    }

    /** True if source a must be output before source b (exhausted sources lose, ties go to the lower index). */
    private boolean beats(int a, int b) {
        if (!alive[a]) return !alive[b] && a < b;
        if (!alive[b]) return true;
        int c = cmp.compare(head(a), head(b));
        return c < 0 || (c == 0 && a < b);
    }

    @Override
    public boolean hasNext() {
        return alive[winner];
    }

    @Override
    public T next() {
        if (!hasNext()) throw new NoSuchElementException();
        T v = head(winner);
        advance(winner);
        int cur = winner;
        for (int node = (winner + k) / 2; node >= 1; node /= 2) {
            if (beats(loser[node], cur)) {
                int t = loser[node];
                loser[node] = cur;
                cur = t;
            }
        }
        winner = cur;
        return v;
    }
}
