package ae.netroute;

import java.util.ArrayList;
import java.util.List;

/** First-match packet filter over (source, destination, port) boxes with exact static analysis. */
public final class Firewall {
    /** A packet header. */
    public record Packet(long src, long dst, int port) { }

    /** Outcome of evaluating a packet: {@code ruleIndex} is -1 for the default deny. */
    public record Decision(boolean allow, int ruleIndex) { }

    /** One rule; all bounds are inclusive. */
    public record Rule(boolean allow, long srcLo, long srcHi, long dstLo, long dstHi, int portLo, int portHi) {
        boolean matches(Packet p) {
            return p.src() >= srcLo && p.src() <= srcHi && p.dst() >= dstLo && p.dst() <= dstHi
                && p.port() >= portLo && p.port() <= portHi;
        }
    }

    private static final int MAX_PORT = 65535;

    private final List<Rule> rules;

    private Firewall(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    public List<Rule> rules() {
        return rules;
    }

    /** Parses the rule text; errors read {@code line N: ...}. */
    public static Firewall parse(String text) {
        List<Rule> rules = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            line = line.trim();
            if (line.isEmpty()) continue;
            try {
                rules.add(parseRule(line));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("line " + (i + 1) + ": " + e.getMessage(), e);
            }
        }
        return new Firewall(rules);
    }

    private static Rule parseRule(String line) {
        String[] t = line.split("\\s+");
        if (t.length != 4) throw new IllegalArgumentException("expected 'allow|deny <src> <dst> <ports>' but got '" + line + "'");
        boolean allow;
        if (t[0].equals("allow")) allow = true;
        else if (t[0].equals("deny")) allow = false;
        else throw new IllegalArgumentException("unknown action '" + t[0] + "'");
        long[] src = addrRange(t[1]);
        long[] dst = addrRange(t[2]);
        int[] ports = portRange(t[3]);
        return new Rule(allow, src[0], src[1], dst[0], dst[1], ports[0], ports[1]);
    }

    private static long[] addrRange(String s) {
        if (s.equals("any")) return new long[] {0, Ip4.MAX};
        Cidr c = Cidr.parse(s);
        return new long[] {c.first(), c.last()};
    }

    private static int[] portRange(String s) {
        if (s.equals("any")) return new int[] {0, MAX_PORT};
        int dash = s.indexOf('-');
        int lo = port(dash < 0 ? s : s.substring(0, dash));
        int hi = dash < 0 ? lo : port(s.substring(dash + 1));
        if (lo > hi) throw new IllegalArgumentException("empty port range '" + s + "'");
        return new int[] {lo, hi};
    }

    private static int port(String s) {
        if (s.isEmpty() || s.length() > 5) throw new IllegalArgumentException("invalid port '" + s + "'");
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') throw new IllegalArgumentException("invalid port '" + s + "'");
            n = n * 10 + (c - '0');
        }
        if (n > MAX_PORT) throw new IllegalArgumentException("port above 65535: '" + s + "'");
        return n;
    }

    /** First matching rule decides; no match denies. */
    public Decision evaluate(Packet p) {
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).matches(p)) return new Decision(rules.get(i).allow(), i);
        }
        return new Decision(false, -1);
    }

    /** Sources that are allowed to reach {@code dst} on {@code port}. */
    public AddrSet allowedSources(long dst, int port) {
        AddrSet covered = AddrSet.EMPTY;
        AddrSet allowed = AddrSet.EMPTY;
        for (Rule r : rules) {
            if (dst < r.dstLo() || dst > r.dstHi() || port < r.portLo() || port > r.portHi()) continue;
            AddrSet src = AddrSet.of(r.srcLo(), r.srcHi());
            if (r.allow()) allowed = allowed.union(src.subtract(covered));
            covered = covered.union(src);
        }
        return allowed;
    }

    /** Indexes of rules that can never match because earlier rules already cover their whole box. */
    public List<Integer> shadowed() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            List<long[]> remaining = new ArrayList<>();
            Rule r = rules.get(i);
            remaining.add(new long[] {r.srcLo(), r.srcHi(), r.dstLo(), r.dstHi(), r.portLo(), r.portHi()});
            for (int j = 0; j < i && !remaining.isEmpty(); j++) {
                Rule e = rules.get(j);
                long[] cut = {e.srcLo(), e.srcHi(), e.dstLo(), e.dstHi(), e.portLo(), e.portHi()};
                List<long[]> next = new ArrayList<>();
                for (long[] box : remaining) subtract(box, cut, next);
                remaining = next;
            }
            if (remaining.isEmpty()) out.add(i);
        }
        return out;
    }

    /** Appends box minus cut (both as lo/hi pairs per dimension) as up to six disjoint boxes. */
    private static void subtract(long[] box, long[] cut, List<long[]> out) {
        for (int d = 0; d < 3; d++) {
            if (cut[2 * d] > box[2 * d + 1] || cut[2 * d + 1] < box[2 * d]) {
                out.add(box); // disjoint in some dimension: nothing removed
                return;
            }
        }
        long[] rest = box.clone();
        for (int d = 0; d < 3; d++) {
            if (rest[2 * d] < cut[2 * d]) {
                long[] piece = rest.clone();
                piece[2 * d + 1] = cut[2 * d] - 1;
                out.add(piece);
                rest[2 * d] = cut[2 * d];
            }
            if (rest[2 * d + 1] > cut[2 * d + 1]) {
                long[] piece = rest.clone();
                piece[2 * d] = cut[2 * d + 1] + 1;
                out.add(piece);
                rest[2 * d + 1] = cut[2 * d + 1];
            }
        }
    }
}
