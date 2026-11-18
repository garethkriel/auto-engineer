package ae.outbox.port;

/** A unit of work run inside a transaction. */
@FunctionalInterface
public interface TxWork<T> {
    T run(Tx tx);
}
