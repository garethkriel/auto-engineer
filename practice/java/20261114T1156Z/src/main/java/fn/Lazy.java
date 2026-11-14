package fn;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Thread-safe memoising supplier. The supplier runs at most once successfully;
 * if it throws, nothing is cached and the next {@link #get()} retries.
 *
 * @param <T> value type (null results are allowed and memoised)
 */
public final class Lazy<T> implements Supplier<T> {
    private final Object lock = new Object();
    private Supplier<? extends T> supplier;
    private volatile boolean evaluated;
    private T value;

    private Lazy(Supplier<? extends T> supplier) {
        this.supplier = supplier;
    }

    /** Creates a Lazy over {@code supplier}. */
    public static <T> Lazy<T> of(Supplier<? extends T> supplier) {
        return new Lazy<>(Objects.requireNonNull(supplier, "supplier"));
    }

    @Override
    public T get() {
        if (!evaluated) {
            synchronized (lock) {
                if (!evaluated) {
                    value = supplier.get();
                    supplier = null; // release captured state
                    evaluated = true;
                }
            }
        }
        return value;
    }

    /** @return whether a value has been computed successfully */
    public boolean isEvaluated() {
        return evaluated;
    }

    /** Returns a new Lazy applying {@code fn} to this one's value on first access. */
    public <R> Lazy<R> map(Function<? super T, ? extends R> fn) {
        Objects.requireNonNull(fn, "fn");
        return new Lazy<>(() -> fn.apply(get()));
    }
}
