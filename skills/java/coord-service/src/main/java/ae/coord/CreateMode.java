package ae.coord;

/** How a node is created. */
public enum CreateMode {
    PERSISTENT(false, false),
    EPHEMERAL(true, false),
    PERSISTENT_SEQUENTIAL(false, true),
    EPHEMERAL_SEQUENTIAL(true, true);

    private final boolean ephemeral;
    private final boolean sequential;

    CreateMode(boolean ephemeral, boolean sequential) {
        this.ephemeral = ephemeral;
        this.sequential = sequential;
    }

    public boolean ephemeral() {
        return ephemeral;
    }

    public boolean sequential() {
        return sequential;
    }
}
