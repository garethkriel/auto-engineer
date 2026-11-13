package ae.account;

import java.util.Objects;

/** Bank account demonstrating static (class-level) versus instance state. Not thread-safe. */
public final class Account {
    private static int nextId = 1;
    private static int openCount = 0;

    private final int id;
    private final String owner;
    private long balanceCents;

    private Account(int id, String owner) {
        this.id = id;
        this.owner = owner;
    }

    /** Opens a new account with the next sequential id. */
    public static Account open(String owner) {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("owner must be non-blank");
        }
        openCount++;
        return new Account(nextId++, owner);
    }

    /** Number of accounts opened since the last reset. */
    public static int openCount() {
        return openCount;
    }

    /** Resets static state; intended for tests. */
    public static void resetForTests() {
        nextId = 1;
        openCount = 0;
    }

    public int id() {
        return id;
    }

    public String owner() {
        return owner;
    }

    public long balanceCents() {
        return balanceCents;
    }

    public void deposit(long cents) {
        requirePositive(cents);
        balanceCents = Math.addExact(balanceCents, cents);
    }

    public void withdraw(long cents) {
        requirePositive(cents);
        if (cents > balanceCents) {
            throw new IllegalStateException("insufficient funds");
        }
        balanceCents -= cents;
    }

    /** Moves money atomically: validates everything before mutating either account. */
    public void transferTo(Account other, long cents) {
        if (other == null || other == this) {
            throw new IllegalArgumentException("invalid transfer target");
        }
        requirePositive(cents);
        if (cents > balanceCents) {
            throw new IllegalStateException("insufficient funds");
        }
        long newOther = Math.addExact(other.balanceCents, cents);
        balanceCents -= cents;
        other.balanceCents = newOther;
    }

    private static void requirePositive(long cents) {
        if (cents <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Account a && a.id == id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Account#%d[%s: %d.%02d]".formatted(id, owner, balanceCents / 100, balanceCents % 100);
    }
}
