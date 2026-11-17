package ae.mw;

/** Terminal request handler. */
@FunctionalInterface
public interface Handler {
    Response handle(Request request);
}
