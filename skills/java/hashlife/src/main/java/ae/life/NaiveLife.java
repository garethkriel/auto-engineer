package ae.life;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reference implementation: sparse set of live cells, rule B3/S23, one generation at a time. Used as oracle. */
public final class NaiveLife {
    private Set<Cell> live;
    private long generation;

    public NaiveLife(Collection<Cell> cells) {
        live = new HashSet<>(cells);
    }

    /** Advances {@code n} generations. */
    public void step(long n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be >= 0");
        }
        for (long i = 0; i < n; i++) {
            Map<Cell, Integer> counts = new HashMap<>();
            for (Cell c : live) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx != 0 || dy != 0) {
                            counts.merge(new Cell(c.x() + dx, c.y() + dy), 1, Integer::sum);
                        }
                    }
                }
            }
            Set<Cell> next = new HashSet<>();
            for (Map.Entry<Cell, Integer> e : counts.entrySet()) {
                int k = e.getValue();
                if (k == 3 || (k == 2 && live.contains(e.getKey()))) {
                    next.add(e.getKey());
                }
            }
            live = next;
            generation++;
        }
    }

    public List<Cell> cells() {
        List<Cell> out = new ArrayList<>(live);
        out.sort(null);
        return out;
    }

    public long population() {
        return live.size();
    }

    public long generation() {
        return generation;
    }
}
