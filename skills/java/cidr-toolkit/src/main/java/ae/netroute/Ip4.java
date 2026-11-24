package ae.netroute;

/** Strict IPv4 dotted-quad conversion. Addresses are {@code long} values in 0..2^32-1. */
public final class Ip4 {
    /** Largest address. */
    public static final long MAX = 0xFFFFFFFFL;

    private Ip4() { }

    /**
     * Parses exactly four decimal octets (1-3 digits, no leading zeros, at most 255).
     *
     * @throws IllegalArgumentException with the quoted text when malformed
     */
    public static long parse(String text) {
        if (text == null) throw new IllegalArgumentException("null address");
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) throw bad(text);
        long v = 0;
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3 || (p.length() > 1 && p.charAt(0) == '0')) throw bad(text);
            int n = 0;
            for (int i = 0; i < p.length(); i++) {
                char c = p.charAt(i);
                if (c < '0' || c > '9') throw bad(text);
                n = n * 10 + (c - '0');
            }
            if (n > 255) throw bad(text);
            v = (v << 8) | n;
        }
        return v;
    }

    /** Formats an address as dotted quad. */
    public static String format(long addr) {
        if (addr < 0 || addr > MAX) throw new IllegalArgumentException("address out of range: " + addr);
        return (addr >>> 24) + "." + ((addr >>> 16) & 255) + "." + ((addr >>> 8) & 255) + "." + (addr & 255);
    }

    private static IllegalArgumentException bad(String text) {
        return new IllegalArgumentException("invalid IPv4 address '" + text + "'");
    }
}
