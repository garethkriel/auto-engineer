package ae.proto;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/** A message definition: name plus fields addressable by number and name. */
public final class MessageType {
    private final String name;
    private final Map<Integer, Field> byNumber = new TreeMap<>();
    private final Map<String, Field> byName = new HashMap<>();

    MessageType(String name) {
        this.name = name;
    }

    void add(Field f) {
        byNumber.put(f.number(), f);
        byName.put(f.name(), f);
    }

    public String name() {
        return name;
    }

    /** Fields in ascending field-number order. */
    public Collection<Field> fields() {
        return Collections.unmodifiableCollection(byNumber.values());
    }

    public Field field(int number) {
        return byNumber.get(number);
    }

    public Field field(String fieldName) {
        return byName.get(fieldName);
    }

    @Override
    public String toString() {
        return "MessageType " + name;
    }
}
