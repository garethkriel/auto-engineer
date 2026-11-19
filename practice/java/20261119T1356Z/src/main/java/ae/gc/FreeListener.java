package ae.gc;

import java.util.List;

/** Notified after a sweep with the handles it reclaimed, in ascending order. */
@FunctionalInterface
public interface FreeListener {
    void onFreed(List<Integer> handles);
}
