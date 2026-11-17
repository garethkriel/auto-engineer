package ae.mw;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Immutable request; method is upper-cased and header names lower-cased. */
public record Request(String method, String path, Map<String, String> headers) {
    public Request {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(headers, "headers");
        method = method.toUpperCase(Locale.ROOT);
        Map<String, String> copy = new LinkedHashMap<>();
        headers.forEach((k, v) -> copy.put(k.toLowerCase(Locale.ROOT), Objects.requireNonNull(v, "header value")));
        headers = Map.copyOf(copy);
    }

    /** Returns a copy with the header set (replacing any existing value). */
    public Request withHeader(String name, String value) {
        Map<String, String> copy = new LinkedHashMap<>(headers);
        copy.put(name.toLowerCase(Locale.ROOT), Objects.requireNonNull(value, "value"));
        return new Request(method, path, copy);
    }
}
