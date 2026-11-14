package ae;

import java.util.List;
import java.util.function.Predicate;

/** Predicate combinators over a defensive copy of their inputs. */
public final class Rules {
    private Rules() {}

    public static <T> Predicate<T> allOf(List<Predicate<T>> ps) {
        List<Predicate<T>> copy = List.copyOf(ps);
        return t -> copy.stream().allMatch(p -> p.test(t));
    }

    public static <T> Predicate<T> anyOf(List<Predicate<T>> ps) {
        List<Predicate<T>> copy = List.copyOf(ps);
        return t -> copy.stream().anyMatch(p -> p.test(t));
    }

    public static <T> Predicate<T> noneOf(List<Predicate<T>> ps) {
        List<Predicate<T>> copy = List.copyOf(ps);
        return t -> copy.stream().noneMatch(p -> p.test(t));
    }
}
