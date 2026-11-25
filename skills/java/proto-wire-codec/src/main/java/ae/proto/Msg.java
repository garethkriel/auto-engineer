package ae.proto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.TreeMap;

/** An immutable dynamic message: known field values plus preserved unknown fields. */
public final class Msg {
    private final MessageType type;
    private final Map<Integer, List<Object>> values; // field number -> values (singular: one element)
    private final List<Bytes> unknown; // raw tag+payload of each unknown field, in read order

    private Msg(MessageType type, Map<Integer, List<Object>> values, List<Bytes> unknown) {
        this.type = type;
        this.values = values;
        this.unknown = unknown;
    }

    public static Msg of(MessageType type) {
        return new Msg(Objects.requireNonNull(type, "type"), Map.of(), List.of());
    }

    public MessageType type() {
        return type;
    }

    private Field known(String name) {
        Field f = type.field(name);
        if (f == null) throw new IllegalArgumentException(type.name() + " has no field " + name);
        return f;
    }

    private void check(Field f, Object v) {
        Objects.requireNonNull(v, "value");
        f.kind().validate(v, f.name());
        if (f.kind() == Kind.MESSAGE && ((Msg) v).type != f.message()) {
            throw new IllegalArgumentException("field " + f.name() + " needs " + f.message().name()
                    + " but got " + ((Msg) v).type.name());
        }
    }

    /** Returns a copy with the singular field set (replacing any earlier value). */
    public Msg set(String name, Object v) {
        Field f = known(name);
        if (f.repeated()) throw new IllegalArgumentException(name + " is repeated; use add");
        check(f, v);
        return with(f.number(), List.of(v));
    }

    /** Returns a copy with {@code v} appended to the repeated field. */
    public Msg add(String name, Object v) {
        Field f = known(name);
        if (!f.repeated()) throw new IllegalArgumentException(name + " is singular; use set");
        check(f, v);
        List<Object> next = new ArrayList<>(values.getOrDefault(f.number(), List.of()));
        next.add(v);
        return with(f.number(), Collections.unmodifiableList(next));
    }

    private Msg with(int number, List<Object> vs) {
        Map<Integer, List<Object>> m = new TreeMap<>(values);
        m.put(number, vs);
        return new Msg(type, Collections.unmodifiableMap(m), unknown);
    }

    Msg withUnknown(List<Bytes> u) {
        return new Msg(type, values, List.copyOf(u));
    }

    /** Package-private bulk constructor used by the decoder. */
    static Msg build(MessageType type, Map<Integer, List<Object>> values, List<Bytes> unknown) {
        Map<Integer, List<Object>> m = new TreeMap<>();
        for (Map.Entry<Integer, List<Object>> e : values.entrySet()) {
            if (!e.getValue().isEmpty()) m.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return new Msg(type, Collections.unmodifiableMap(m), List.copyOf(unknown));
    }

    public boolean has(String name) {
        return !values.getOrDefault(known(name).number(), List.of()).isEmpty();
    }

    /** The value of a singular field, or null when absent. */
    public Object get(String name) {
        Field f = known(name);
        if (f.repeated()) throw new IllegalArgumentException(name + " is repeated; use list");
        List<Object> vs = values.get(f.number());
        return vs == null || vs.isEmpty() ? null : vs.get(0);
    }

    /** The (unmodifiable) values of a repeated field. */
    public List<Object> list(String name) {
        Field f = known(name);
        if (!f.repeated()) throw new IllegalArgumentException(name + " is singular; use get");
        return values.getOrDefault(f.number(), List.of());
    }

    public int unknownCount() {
        return unknown.size();
    }

    Map<Integer, List<Object>> valueMap() {
        return values;
    }

    List<Bytes> unknownFields() {
        return unknown;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Msg m && type == m.type && values.equals(m.values) && unknown.equals(m.unknown);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type.name(), values, unknown);
    }

    @Override
    public String toString() {
        StringJoiner j = new StringJoiner(", ", type.name() + "{", "}");
        for (Field f : type.fields()) {
            List<Object> vs = values.get(f.number());
            if (vs == null || vs.isEmpty()) continue;
            if (f.repeated()) {
                StringJoiner l = new StringJoiner(", ", "[", "]");
                for (Object v : vs) l.add(show(v));
                j.add(f.name() + "=" + l);
            } else {
                j.add(f.name() + "=" + show(vs.get(0)));
            }
        }
        if (!unknown.isEmpty()) j.add("unknown=" + unknown.size());
        return j.toString();
    }

    private static String show(Object v) {
        return v instanceof String s ? "\"" + s + "\"" : String.valueOf(v);
    }
}
