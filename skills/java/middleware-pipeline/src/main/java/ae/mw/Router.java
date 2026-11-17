package ae.mw;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.LinkedHashMap;
import java.util.Locale;

/** Immutable router with literal and {name} segments. First matching route wins. */
public final class Router implements Handler {
    private record Route(String method, List<String> segments, Handler handler) {
        Map<String, String> match(List<String> path) {
            if (path.size() != segments.size()) {
                return null;
            }
            Map<String, String> params = new LinkedHashMap<>();
            for (int i = 0; i < path.size(); i++) {
                String s = segments.get(i);
                if (s.startsWith("{") && s.endsWith("}")) {
                    params.put(s.substring(1, s.length() - 1), path.get(i));
                } else if (!s.equals(path.get(i))) {
                    return null;
                }
            }
            return params;
        }
    }

    private final List<Route> routes;

    private Router(List<Route> routes) {
        this.routes = routes;
    }

    public static Router empty() {
        return new Router(List.of());
    }

    public Router route(String method, String pattern, Handler handler) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(handler, "handler");
        if (pattern.isBlank() || !pattern.startsWith("/")) {
            throw new IllegalArgumentException("pattern must start with '/': " + pattern);
        }
        List<Route> next = new ArrayList<>(routes);
        next.add(new Route(method.toUpperCase(Locale.ROOT), split(pattern), handler));
        return new Router(List.copyOf(next));
    }

    @Override
    public Response handle(Request request) {
        List<String> path = split(request.path());
        TreeSet<String> allowed = new TreeSet<>();
        for (Route r : routes) {
            Map<String, String> params = r.match(path);
            if (params == null) {
                continue;
            }
            if (r.method().equals(request.method())) {
                Request withParams = request;
                for (Map.Entry<String, String> e : params.entrySet()) {
                    withParams = withParams.withHeader("x-param-" + e.getKey(), e.getValue());
                }
                return r.handler().handle(withParams);
            }
            allowed.add(r.method());
        }
        return allowed.isEmpty() ? new Response(404, "not found") : new Response(405, String.join(",", allowed));
    }

    private static List<String> split(String path) {
        List<String> out = new ArrayList<>();
        for (String s : path.split("/")) {
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }
}
