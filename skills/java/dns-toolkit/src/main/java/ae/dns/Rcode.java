package ae.dns;

/** Response codes 0..5. */
public enum Rcode {
    NOERROR, FORMERR, SERVFAIL, NXDOMAIN, NOTIMP, REFUSED;

    public int code() {
        return ordinal();
    }

    /** The code for this wire value, or null if it is not 0..5. */
    public static Rcode fromCode(int code) {
        return code >= 0 && code < values().length ? values()[code] : null;
    }
}
