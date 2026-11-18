package ae.outbox.port;

/** Port for transactional storage. */
public interface Database {
    /**
     * Runs {@code work} atomically: if it throws, no write it made is visible.
     * Transactions must not be nested.
     */
    <T> T inTransaction(TxWork<T> work);
}
