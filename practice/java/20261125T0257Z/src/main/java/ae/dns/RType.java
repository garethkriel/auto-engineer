package ae.dns;

/** Supported record types (class IN only). */
public enum RType {
    A(1), NS(2), CNAME(5), SOA(6), MX(15), TXT(16), AAAA(28);

    private final int code;

    RType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /** The type with this wire code, or null if unsupported. */
    public static RType fromCode(int code) {
        for (RType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        return null;
    }
}
