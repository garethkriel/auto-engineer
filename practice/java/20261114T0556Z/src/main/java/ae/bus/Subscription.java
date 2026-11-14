package ae.bus;

/** Handle for cancelling a subscription. */
public final class Subscription {
    private boolean active = true;

    Subscription() { }

    /** Stops future deliveries; calling it again is harmless. */
    public void cancel() {
        active = false;
    }

    public boolean isActive() {
        return active;
    }
}
