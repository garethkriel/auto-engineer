package ae.dns;

/** Time source in whole seconds, injected so cache expiry is testable. */
@FunctionalInterface
public interface Clock {
    long nowSeconds();
}
