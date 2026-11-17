package ae.tconf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** A parsed TOML value. Everything except {@link Table} and {@link Arr} is an immutable record. */
public sealed interface Value permits Value.Str, Value.Int, Value.Flt, Value.Bool, Value.Arr, Table {

    /** A string value. */
    record Str(String value) implements Value {
        public Str {
            Objects.requireNonNull(value);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("\"");
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\t' -> sb.append("\\t");
                    case '\r' -> sb.append("\\r");
                    default -> {
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
            return sb.append('"').toString();
        }
    }

    /** A 64-bit integer. */
    record Int(long value) implements Value {
        @Override
        public String toString() {
            return Long.toString(value);
        }
    }

    /** A double. */
    record Flt(double value) implements Value {
        @Override
        public String toString() {
            return Double.toString(value);
        }
    }

    /** A boolean. */
    record Bool(boolean value) implements Value {
        @Override
        public String toString() {
            return Boolean.toString(value);
        }
    }

    /** An array; may hold values of mixed types. Mutable only inside the parser. */
    final class Arr implements Value {
        private final List<Value> items = new ArrayList<>();

        Arr() {
        }

        void add(Value v) {
            items.add(v);
        }

        /** Read-only view of the elements. */
        public List<Value> items() {
            return Collections.unmodifiableList(items);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Arr a && a.items.equals(items);
        }

        @Override
        public int hashCode() {
            return items.hashCode();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < items.size(); i++) {
                sb.append(i == 0 ? "" : ", ").append(items.get(i));
            }
            return sb.append(']').toString();
        }
    }
}
