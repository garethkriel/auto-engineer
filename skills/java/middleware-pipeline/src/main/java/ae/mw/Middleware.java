package ae.mw;

/** A link in the chain: may act before/after {@code next}, or skip it. */
@FunctionalInterface
public interface Middleware {
    Response handle(Request request, Handler next);
}
