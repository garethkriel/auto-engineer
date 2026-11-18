package ae.lib;

/** A library member: tier plus outstanding fines in cents. */
final class Member {
    static final int BLOCK_THRESHOLD = 500;
    final Tier tier;
    private int owed;

    Member(Tier tier) { this.tier = tier; }

    int owed() { return owed; }
    boolean isBlocked() { return owed >= BLOCK_THRESHOLD; }
    void charge(int cents) { owed = Math.addExact(owed, cents); }

    /** Pays up to {@code cents}; returns the amount actually applied. */
    int pay(int cents) {
        int applied = Math.min(cents, owed);
        owed -= applied;
        return applied;
    }
}
