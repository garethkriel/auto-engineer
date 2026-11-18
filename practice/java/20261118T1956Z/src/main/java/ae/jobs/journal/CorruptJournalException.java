package ae.jobs.journal;

/** The journal contains a record that fails its checksum, cannot be parsed, or is not a legal transition. */
public final class CorruptJournalException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CorruptJournalException(String message) {
        super(message);
    }
}
