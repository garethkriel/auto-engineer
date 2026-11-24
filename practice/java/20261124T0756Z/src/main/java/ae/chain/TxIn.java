package ae.chain;

import java.util.Objects;

/** A reference to output {@code index} of transaction {@code txId}. */
public record TxIn(String txId, int index) {
    public TxIn {
        Objects.requireNonNull(txId, "txId");
        if (index < 0) {
            throw new IllegalArgumentException("index must be >= 0");
        }
    }
}
