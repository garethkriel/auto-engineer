package fn;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Composable validation that accumulates every error instead of stopping at the first.
 *
 * @param <T> validated type
 */
@FunctionalInterface
public interface Validator<T> {

    /** @return the errors for {@code value}; empty when valid */
    List<String> validate(T value);

    /** @return true when {@link #validate} reports no errors */
    default boolean isValid(T value) {
        return validate(value).isEmpty();
    }

    /** Returns the value if valid, else throws {@link ValidationException}. */
    default T requireValid(T value) {
        List<String> errors = validate(value);
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        return value;
    }

    /** Runs both validators and concatenates their errors (this first). */
    default Validator<T> and(Validator<? super T> other) {
        Objects.requireNonNull(other, "other");
        return value -> {
            List<String> all = new ArrayList<>(validate(value));
            all.addAll(other.validate(value));
            return List.copyOf(all);
        };
    }

    /** Validator reporting {@code message} when {@code ok} rejects the value. */
    static <T> Validator<T> of(Predicate<? super T> ok, String message) {
        Objects.requireNonNull(ok, "ok");
        Objects.requireNonNull(message, "message");
        return value -> ok.test(value) ? List.of() : List.of(message);
    }

    /** Runs all validators in order and concatenates their errors. */
    @SafeVarargs
    static <T> Validator<T> allOf(Validator<? super T>... validators) {
        List<Validator<? super T>> copy = List.of(validators);
        return value -> {
            List<String> all = new ArrayList<>();
            for (Validator<? super T> v : copy) {
                all.addAll(v.validate(value));
            }
            return List.copyOf(all);
        };
    }

    /** Validates a property of the value, prefixing errors with {@code name: }. */
    static <T, V> Validator<T> field(String name, Function<? super T, ? extends V> extractor,
                                     Validator<? super V> inner) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(extractor, "extractor");
        Objects.requireNonNull(inner, "inner");
        return value -> inner.validate(extractor.apply(value)).stream()
                .map(e -> name + ": " + e)
                .toList();
    }

    /** Validates each element, prefixing errors with {@code [index]: }. */
    static <E> Validator<Iterable<? extends E>> each(Validator<? super E> inner) {
        Objects.requireNonNull(inner, "inner");
        return items -> {
            List<String> all = new ArrayList<>();
            int i = 0;
            for (E item : items) {
                for (String e : inner.validate(item)) {
                    all.add("[" + i + "]: " + e);
                }
                i++;
            }
            return List.copyOf(all);
        };
    }
}
