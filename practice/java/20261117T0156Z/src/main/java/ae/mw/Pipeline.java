package ae.mw;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable middleware chain; the first {@code use}d middleware is outermost. */
public final class Pipeline {
    private final Handler terminal;
    private final List<Middleware> chain;

    private Pipeline(Handler terminal, List<Middleware> chain) {
        this.terminal = terminal;
        this.chain = chain;
    }

    public static Pipeline to(Handler terminal) {
        return new Pipeline(Objects.requireNonNull(terminal, "terminal"), List.of());
    }

    public Pipeline use(Middleware middleware) {
        Objects.requireNonNull(middleware, "middleware");
        List<Middleware> next = new ArrayList<>(chain);
        next.add(middleware);
        return new Pipeline(terminal, List.copyOf(next));
    }

    public Handler build() {
        Handler h = terminal;
        for (int i = chain.size() - 1; i >= 0; i--) {
            Middleware m = chain.get(i);
            Handler inner = h;
            h = req -> m.handle(req, inner);
        }
        return h;
    }
}
