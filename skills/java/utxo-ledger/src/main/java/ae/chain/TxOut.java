package ae.chain;

import java.util.Objects;

/** An amount owned by {@code owner}. */
public record TxOut(String owner, long amount) {
    public TxOut {
        Objects.requireNonNull(owner, "owner");
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }
    }
}
