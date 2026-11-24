package ae.netroute;

/** An IPv4 prefix: {@code base} has no host bits set below {@code len}. */
public record Cidr(long base, int len) implements Comparable<Cidr> {
    public Cidr {
        if (len < 0 || len > 32) throw new IllegalArgumentException("prefix length out of range: " + len);
        if (base < 0 || base > Ip4.MAX) throw new IllegalArgumentException("address out of range: " + base);
        if ((base & hostMask(len)) != 0) throw new IllegalArgumentException("host bits set in " + Ip4.format(base) + "/" + len);
    }

    private static long hostMask(int len) {
        return (1L << (32 - len)) - 1;
    }

    /** Strict factory: host bits set is an error. */
    public static Cidr of(long base, int len) {
        return new Cidr(base, len);
    }

    /** The prefix of length {@code len} that contains {@code addr}. */
    public static Cidr containing(long addr, int len) {
        if (len < 0 || len > 32) throw new IllegalArgumentException("prefix length out of range: " + len);
        return new Cidr(addr & ~hostMask(len) & Ip4.MAX, len);
    }

    /** Parses {@code a.b.c.d/n}; the length is 1-2 digits without a leading zero. */
    public static Cidr parse(String text) {
        int slash = text.indexOf('/');
        if (slash < 0 || text.indexOf('/', slash + 1) >= 0) throw new IllegalArgumentException("invalid CIDR '" + text + "'");
        String l = text.substring(slash + 1);
        if (l.isEmpty() || l.length() > 2 || (l.length() > 1 && l.charAt(0) == '0')
            || !Character.isDigit(l.charAt(0)) || !Character.isDigit(l.charAt(l.length() - 1))) {
            throw new IllegalArgumentException("invalid CIDR '" + text + "'");
        }
        int len = Integer.parseInt(l);
        if (len > 32) throw new IllegalArgumentException("invalid CIDR '" + text + "': length above 32");
        return new Cidr(Ip4.parse(text.substring(0, slash)), len);
    }

    public long first() {
        return base;
    }

    public long last() {
        return base + hostMask(len);
    }

    public long size() {
        return hostMask(len) + 1;
    }

    public boolean contains(long addr) {
        return addr >= first() && addr <= last();
    }

    public boolean contains(Cidr other) {
        return other.first() >= first() && other.last() <= last();
    }

    public boolean overlaps(Cidr other) {
        return contains(other) || other.contains(this);
    }

    @Override
    public int compareTo(Cidr o) {
        int c = Long.compare(base, o.base);
        return c != 0 ? c : Integer.compare(len, o.len);
    }

    @Override
    public String toString() {
        return Ip4.format(base) + "/" + len;
    }
}
