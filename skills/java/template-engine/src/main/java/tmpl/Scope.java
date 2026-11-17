package tmpl;

import java.util.Map;

/** A lexical scope: bindings plus an optional parent. Never writes to the user's map. */
record Scope(Map<String, ?> bindings, Scope parent) {
    Object lookup(String name) {
        for (Scope s = this; s != null; s = s.parent) {
            if (s.bindings.containsKey(name)) {
                return s.bindings.get(name);
            }
        }
        return null;
    }
}
