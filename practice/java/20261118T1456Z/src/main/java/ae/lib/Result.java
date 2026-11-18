package ae.lib;

/** Outcome of a command; {@link #code()} is the legacy string. */
sealed interface Result {
    String code();

    /** Business-rule refusals; the enum name is the wire code. */
    enum Refusal {
        ERR_ID, ERR_COPIES, ERR_NAME, ERR_TIER, ERR_DUP, ERR_DAY, ERR_AMOUNT,
        NO_MEMBER, NO_BOOK, NO_LOAN, BLOCKED, LIMIT, DUP_LOAN, UNAVAILABLE,
        HELD_FOR_OTHERS, MAX_RENEWALS, HELD, OVERDUE, HAS_LOAN, DUP_HOLD, QUEUE_FULL
    }

    record Done(String code) implements Result { }

    record Refused(Refusal reason) implements Result {
        @Override
        public String code() { return reason.name(); }
    }

    static Result done(String code) { return new Done(code); }

    static Result refused(Refusal reason) { return new Refused(reason); }
}
