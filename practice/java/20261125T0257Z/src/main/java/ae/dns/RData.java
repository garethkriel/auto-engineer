package ae.dns;

import java.util.List;
import java.util.Objects;

/** Typed record data. */
public sealed interface RData {
    RType type();

    /** IPv4 address as a 32-bit int. */
    record A(int address) implements RData {
        public static A of(String dotted) {
            return new A(parseAddress(dotted));
        }

        /** Parses {@code "a.b.c.d"} into a 32-bit int. */
        public static int parseAddress(String dotted) {
            String[] p = dotted.split("\\.", -1);
            if (p.length != 4) {
                throw new IllegalArgumentException("bad IPv4 address: " + dotted);
            }
            int v = 0;
            for (String s : p) {
                if (s.isEmpty() || s.length() > 3 || !s.chars().allMatch(c -> c >= '0' && c <= '9')) {
                    throw new IllegalArgumentException("bad IPv4 address: " + dotted);
                }
                int o = Integer.parseInt(s);
                if (o > 255) {
                    throw new IllegalArgumentException("bad IPv4 address: " + dotted);
                }
                v = (v << 8) | o;
            }
            return v;
        }

        @Override
        public RType type() {
            return RType.A;
        }

        @Override
        public String toString() {
            return (address >>> 24) + "." + ((address >>> 16) & 255) + "." + ((address >>> 8) & 255) + "." + (address & 255);
        }
    }

    /** IPv6 address as two 64-bit halves. */
    record Aaaa(long hi, long lo) implements RData {
        /** Parses a textual IPv6 address with optional {@code ::} compression (no embedded IPv4). */
        public static Aaaa parse(String text) {
            int dc = text.indexOf("::");
            if (dc >= 0 && text.indexOf("::", dc + 1) >= 0) {
                throw new IllegalArgumentException("bad IPv6 address: " + text);
            }
            int[] groups = new int[8];
            if (dc < 0) {
                String[] p = text.split(":", -1);
                if (p.length != 8) {
                    throw new IllegalArgumentException("bad IPv6 address: " + text);
                }
                for (int i = 0; i < 8; i++) {
                    groups[i] = group(p[i], text);
                }
            } else {
                String left = text.substring(0, dc);
                String right = text.substring(dc + 2);
                String[] l = left.isEmpty() ? new String[0] : left.split(":", -1);
                String[] r = right.isEmpty() ? new String[0] : right.split(":", -1);
                if (l.length + r.length > 7) {
                    throw new IllegalArgumentException("bad IPv6 address: " + text);
                }
                for (int i = 0; i < l.length; i++) {
                    groups[i] = group(l[i], text);
                }
                for (int i = 0; i < r.length; i++) {
                    groups[8 - r.length + i] = group(r[i], text);
                }
            }
            long hi = 0;
            long lo = 0;
            for (int i = 0; i < 4; i++) {
                hi = (hi << 16) | groups[i];
                lo = (lo << 16) | groups[4 + i];
            }
            return new Aaaa(hi, lo);
        }

        private static int group(String s, String whole) {
            if (s.isEmpty() || s.length() > 4) {
                throw new IllegalArgumentException("bad IPv6 address: " + whole);
            }
            try {
                return Integer.parseInt(s, 16);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bad IPv6 address: " + whole, e);
            }
        }

        @Override
        public RType type() {
            return RType.AAAA;
        }
    }

    record Ns(Name host) implements RData {
        public Ns {
            Objects.requireNonNull(host);
        }

        @Override
        public RType type() {
            return RType.NS;
        }
    }

    record Cname(Name target) implements RData {
        public Cname {
            Objects.requireNonNull(target);
        }

        @Override
        public RType type() {
            return RType.CNAME;
        }
    }

    record Mx(int preference, Name host) implements RData {
        public Mx {
            Objects.requireNonNull(host);
            if (preference < 0 || preference > 0xFFFF) {
                throw new IllegalArgumentException("preference " + preference);
            }
        }

        @Override
        public RType type() {
            return RType.MX;
        }
    }

    /** One or more character strings of at most 255 ISO-8859-1 characters. */
    record Txt(List<String> chunks) implements RData {
        public Txt {
            chunks = List.copyOf(chunks);
            if (chunks.isEmpty()) {
                throw new IllegalArgumentException("TXT needs at least one chunk");
            }
            for (String c : chunks) {
                if (c.length() > 255 || c.chars().anyMatch(ch -> ch > 255)) {
                    throw new IllegalArgumentException("TXT chunk must be <= 255 ISO-8859-1 characters");
                }
            }
        }

        @Override
        public RType type() {
            return RType.TXT;
        }
    }

    record Soa(Name mname, Name rname, long serial, long refresh, long retry, long expire, long minimum) implements RData {
        public Soa {
            Objects.requireNonNull(mname);
            Objects.requireNonNull(rname);
            for (long v : new long[] {serial, refresh, retry, expire, minimum}) {
                if (v < 0 || v > 0xFFFFFFFFL) {
                    throw new IllegalArgumentException("SOA field out of 32-bit range: " + v);
                }
            }
        }

        @Override
        public RType type() {
            return RType.SOA;
        }
    }
}
