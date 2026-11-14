package ae;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Small generic helpers demonstrating bounded types and wildcards. */
public final class Generics {
    private Generics() { }

    public static <T extends Comparable<? super T>> Optional<T> max(Collection<? extends T> items) {
        T best = null;
        for (T t : items) {
            if (best == null || t.compareTo(best) > 0) best = t;
        }
        return Optional.ofNullable(best);
    }

    public static <T> void copyInto(List<? super T> dst, List<? extends T> src) {
        Objects.requireNonNull(dst, "dst");
        for (T t : src) dst.add(t);
    }

    public static double sum(Collection<? extends Number> nums) {
        double s = 0;
        for (Number n : nums) s += n.doubleValue();
        return s;
    }

    /** Key with the greatest value; first in iteration order wins ties. */
    public static <K, V extends Comparable<? super V>> Optional<K> keyOfMax(Map<K, V> map) {
        K bestK = null;
        V bestV = null;
        for (Map.Entry<K, V> e : map.entrySet()) {
            if (bestV == null || e.getValue().compareTo(bestV) > 0) {
                bestK = e.getKey();
                bestV = e.getValue();
            }
        }
        return Optional.ofNullable(bestK);
    }
}
