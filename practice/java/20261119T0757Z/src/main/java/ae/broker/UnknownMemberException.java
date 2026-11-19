package ae.broker;

/** The member is not (or no longer) part of the group, e.g. it left or its session expired. */
public final class UnknownMemberException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public UnknownMemberException(String message) { super(message); }
}
