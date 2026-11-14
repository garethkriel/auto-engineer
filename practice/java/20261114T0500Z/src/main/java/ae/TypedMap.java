package ae;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Type-safe heterogeneous container (typesafe-container pattern). */
public final class TypedMap {
    private final Map<TypedKey<?>, Object> values = new HashMap<>();

    public <T> TypedMap put(TypedKey<T> key, T value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        values.put(key, boxed(key.type()).cast(value));
        return this;
    }

    public <T> Optional<T> get(TypedKey<T> key) {
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable(values.get(key)).map(v -> {
            @SuppressWarnings("unchecked")
            T t = (T) boxed(key.type()).cast(v);
            return t;
        });
    }

    public int size() {
        return values.size();
    }

    private static Class<?> boxed(Class<?> c) {
        if (!c.isPrimitive()) return c;
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == double.class) return Double.class;
        if (c == boolean.class) return Boolean.class;
        if (c == char.class) return Character.class;
        if (c == byte.class) return Byte.class;
        if (c == short.class) return Short.class;
        if (c == float.class) return Float.class;
        throw new IllegalArgumentException("unsupported type " + c);
    }
}
