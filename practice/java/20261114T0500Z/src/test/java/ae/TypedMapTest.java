package ae;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TypedMapTest {
    @Test
    void storesAndRetrievesTypedValues() {
        TypedKey<String> name = TypedKey.of("name", String.class);
        TypedKey<Integer> age = TypedKey.of("age", Integer.class);
        TypedMap m = new TypedMap().put(name, "Ann").put(age, 7);
        assertEquals(Optional.of("Ann"), m.get(name));
        assertEquals(Optional.of(7), m.get(age));
        assertEquals(2, m.size());
    }

    @Test
    void missingKeyIsEmpty() {
        assertEquals(Optional.empty(), new TypedMap().get(TypedKey.of("x", String.class)));
    }

    @Test
    void sameNameDifferentTypeAreDistinct() {
        TypedMap m = new TypedMap().put(TypedKey.of("v", String.class), "s").put(TypedKey.of("v", Integer.class), 1);
        assertEquals(2, m.size());
        assertEquals(Optional.of("s"), m.get(TypedKey.of("v", String.class)));
    }

    @Test
    void overwriteKeepsSize() {
        TypedKey<String> k = TypedKey.of("k", String.class);
        TypedMap m = new TypedMap().put(k, "a").put(k, "b");
        assertEquals(1, m.size());
        assertEquals(Optional.of("b"), m.get(k));
    }

    @Test
    void primitiveClassKeysBox() {
        TypedKey<Integer> k = TypedKey.of("n", int.class);
        TypedMap m = new TypedMap().put(k, 5);
        assertEquals(Optional.of(5), m.get(k));
    }

    @Test
    void rejectsNullsAndBlankNames() {
        TypedKey<String> k = TypedKey.of("k", String.class);
        assertThrows(NullPointerException.class, () -> new TypedMap().put(k, null));
        assertThrows(IllegalArgumentException.class, () -> TypedKey.of(" ", String.class));
        assertThrows(NullPointerException.class, () -> TypedKey.of("a", null));
        assertThrows(NullPointerException.class, () -> TypedKey.of(null, String.class));
    }

    @Test
    void subclassValueAcceptedForSuperKey() {
        TypedKey<Number> k = TypedKey.of("num", Number.class);
        TypedMap m = new TypedMap().put(k, 3.5);
        assertEquals(Optional.of(3.5), m.get(k));
    }

    @Test
    void listValuesWork() {
        @SuppressWarnings("rawtypes")
        TypedKey<List> k = TypedKey.of("l", List.class);
        assertEquals(Optional.of(List.of(1)), new TypedMap().put(k, List.of(1)).get(k).map(l -> l));
    }
}
