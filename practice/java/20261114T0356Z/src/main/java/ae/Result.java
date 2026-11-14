package ae;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/** Either a success value ({@link Ok}) or an error ({@link Err}). Null values are rejected. */
public sealed interface Result<T, E> permits Result.Ok, Result.Err {

    /** Successful result. */
    record Ok<T, E>(T value) implements Result<T, E> {
        public Ok { Objects.requireNonNull(value, "value"); }
    }

    /** Failed result. */
    record Err<T, E>(E error) implements Result<T, E> {
        public Err { Objects.requireNonNull(error, "error"); }
    }

    default boolean isOk() { return this instanceof Ok; }

    default <U> Result<U, E> map(Function<? super T, ? extends U> f) {
        Objects.requireNonNull(f, "f");
        return switch (this) {
            case Ok<T, E> ok -> new Ok<>(f.apply(ok.value()));
            case Err<T, E> err -> new Err<>(err.error());
        };
    }

    default <U> Result<U, E> flatMap(Function<? super T, ? extends Result<U, E>> f) {
        Objects.requireNonNull(f, "f");
        return switch (this) {
            case Ok<T, E> ok -> Objects.requireNonNull(f.apply(ok.value()), "flatMap result");
            case Err<T, E> err -> new Err<>(err.error());
        };
    }

    default <F> Result<T, F> mapError(Function<? super E, ? extends F> f) {
        Objects.requireNonNull(f, "f");
        return switch (this) {
            case Ok<T, E> ok -> new Ok<>(ok.value());
            case Err<T, E> err -> new Err<>(f.apply(err.error()));
        };
    }

    default T orElse(T fallback) {
        return switch (this) {
            case Ok<T, E> ok -> ok.value();
            case Err<T, E> err -> fallback;
        };
    }

    default T getOrElseGet(Function<? super E, ? extends T> f) {
        return switch (this) {
            case Ok<T, E> ok -> ok.value();
            case Err<T, E> err -> f.apply(err.error());
        };
    }

    default Optional<T> toOptional() {
        return switch (this) {
            case Ok<T, E> ok -> Optional.of(ok.value());
            case Err<T, E> err -> Optional.empty();
        };
    }

    /** Runs {@code s}, capturing any RuntimeException as an {@link Err}. */
    static <T> Result<T, RuntimeException> of(Supplier<? extends T> s) {
        Objects.requireNonNull(s, "s");
        try {
            return new Ok<>(s.get());
        } catch (RuntimeException e) {
            return new Err<>(e);
        }
    }

    /** Collects all values, or returns the first error. The returned list is immutable. */
    static <T, E> Result<List<T>, E> sequence(List<? extends Result<T, E>> results) {
        List<T> out = new ArrayList<>(results.size());
        for (Result<T, E> r : results) {
            switch (r) {
                case Ok<T, E> ok -> out.add(ok.value());
                case Err<T, E> err -> { return new Err<>(err.error()); }
            }
        }
        return new Ok<>(Collections.unmodifiableList(out));
    }
}
