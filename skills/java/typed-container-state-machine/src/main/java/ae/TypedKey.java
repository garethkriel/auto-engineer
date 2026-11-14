package ae;

import java.util.Objects;

/** Key for a {@link TypedMap}; the type token makes lookups type-safe. */
public record TypedKey<T>(String name, Class<T> type) {
    public TypedKey {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    public static <T> TypedKey<T> of(String name, Class<T> type) {
        return new TypedKey<>(name, type);
    }
}
