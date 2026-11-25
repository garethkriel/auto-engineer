package ae.dns;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Authoritative data for one origin and the lookup algorithm of RFC 1034 section 4.3.2 (delegation, CNAME,
 * empty non-terminals, wildcard synthesis).
 */
public final class Zone {
    public enum Kind { ANSWER, CNAME, NODATA, NXDOMAIN, REFERRAL, NOT_IN_ZONE }

    /**
     * Outcome of a lookup. ANSWER: matching records (owner is the query name); CNAME: the single CNAME record
     * (owner is the query name); REFERRAL: the NS set at {@code cut}; the others carry no records.
     */
    public record Result(Kind kind, List<Rr> records, Name cut) {
        public Result {
            records = List.copyOf(records);
        }
    }

    private final Name origin;
    private final Rr soa;
    /** owner -> records at that owner in insertion order. */
    private final Map<Name, List<Rr>> byName = new LinkedHashMap<>();
    /** every owner plus all its ancestors down to (and including) the origin: names that "exist". */
    private final Set<Name> existing = new HashSet<>();

    private Zone(Name origin, List<Rr> records) {
        this.origin = origin;
        Rr foundSoa = null;
        Map<Name, Set<RType>> types = new HashMap<>();
        for (Rr r : records) {
            if (!r.name().isSubdomainOf(origin)) {
                throw new IllegalArgumentException("record " + r.name() + " is outside the origin " + origin);
            }
            if (r.type() == RType.SOA) {
                if (foundSoa != null || !r.name().equals(origin)) {
                    throw new IllegalArgumentException("exactly one SOA, at the apex, is required");
                }
                foundSoa = r;
            }
            byName.computeIfAbsent(r.name(), k -> new ArrayList<>()).add(r);
            types.computeIfAbsent(r.name(), k -> new HashSet<>()).add(r.type());
        }
        if (foundSoa == null) {
            throw new IllegalArgumentException("zone " + origin + " has no SOA");
        }
        for (Map.Entry<Name, Set<RType>> e : types.entrySet()) {
            if (e.getValue().contains(RType.CNAME) && e.getValue().size() > 1) {
                throw new IllegalArgumentException("CNAME at " + e.getKey() + " must be the only record type");
            }
            for (Name a = e.getKey(); ; a = a.parent()) {
                existing.add(a);
                if (a.equals(origin)) {
                    break;
                }
            }
        }
        this.soa = foundSoa;
    }

    public static Zone of(Name origin, List<Rr> records) {
        Objects.requireNonNull(origin);
        return new Zone(origin, records);
    }

    public Name origin() {
        return origin;
    }

    public Rr soa() {
        return soa;
    }

    /** A and AAAA records stored at {@code host} (also glue below a delegation), in stored order. */
    public List<Rr> addresses(Name host) {
        List<Rr> out = new ArrayList<>();
        for (Rr r : byName.getOrDefault(host, List.of())) {
            if (r.type() == RType.A || r.type() == RType.AAAA) {
                out.add(r);
            }
        }
        return out;
    }

    public Result lookup(Name qname, RType qtype) {
        if (!qname.isSubdomainOf(origin)) {
            return new Result(Kind.NOT_IN_ZONE, List.of(), null);
        }
        // 2. the shallowest delegation at or above qname wins, whatever the type
        for (int k = origin.labelCount() + 1; k <= qname.labelCount(); k++) {
            Name anc = qname.ancestor(k);
            List<Rr> ns = ofType(byName.getOrDefault(anc, List.of()), RType.NS);
            if (!ns.isEmpty()) {
                return new Result(Kind.REFERRAL, ns, anc);
            }
        }
        // 3. the name exists (has data or is an empty non-terminal)
        if (existing.contains(qname)) {
            return fromRecords(byName.getOrDefault(qname, List.of()), qname, qtype);
        }
        // 4. closest encloser, then its wildcard
        Name encloser = qname.parent();
        while (!existing.contains(encloser)) {
            encloser = encloser.parent();
        }
        List<Rr> wild = byName.getOrDefault(encloser.child("*"), List.of());
        if (wild.isEmpty()) {
            return new Result(Kind.NXDOMAIN, List.of(), null);
        }
        return fromRecords(wild, qname, qtype);
    }

    private static Result fromRecords(List<Rr> at, Name owner, RType qtype) {
        List<Rr> typed = ofType(at, qtype);
        if (!typed.isEmpty()) {
            return new Result(Kind.ANSWER, rename(typed, owner), null);
        }
        List<Rr> cname = ofType(at, RType.CNAME);
        if (!cname.isEmpty()) {
            return new Result(Kind.CNAME, rename(cname, owner), null);
        }
        return new Result(Kind.NODATA, List.of(), null);
    }

    private static List<Rr> ofType(List<Rr> rrs, RType t) {
        List<Rr> out = new ArrayList<>();
        for (Rr r : rrs) {
            if (r.type() == t) {
                out.add(r);
            }
        }
        return out;
    }

    private static List<Rr> rename(List<Rr> rrs, Name owner) {
        List<Rr> out = new ArrayList<>(rrs.size());
        for (Rr r : rrs) {
            out.add(r.name().equals(owner) ? r : r.withName(owner));
        }
        return out;
    }

    // ---- master-file subset ----------------------------------------------------

    /** Parses {@code $TTL}, {@code @}, relative names, {@code ;} comments and quoted TXT strings. */
    public static Zone parse(String origin, String text) {
        Name org = Name.parse(origin);
        long defaultTtl = -1;
        List<Rr> records = new ArrayList<>();
        int lineNo = 0;
        for (String line : text.split("\n", -1)) {
            lineNo++;
            List<String> tok = tokens(line, lineNo);
            if (tok.isEmpty()) {
                continue;
            }
            try {
                if (tok.get(0).equals("$TTL")) {
                    defaultTtl = ttl(tok.get(1));
                    continue;
                }
                int i = 0;
                Name owner = absolute(tok.get(i++), org);
                long ttl = defaultTtl;
                if (isNumber(tok.get(i))) {
                    ttl = ttl(tok.get(i++));
                }
                if (tok.get(i).equals("IN")) {
                    i++;
                }
                if (ttl < 0) {
                    throw new IllegalArgumentException("no TTL and no $TTL");
                }
                RType type;
                try {
                    type = RType.valueOf(tok.get(i++));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("unknown record type " + tok.get(i - 1));
                }
                records.add(new Rr(owner, ttl, rdata(type, tok.subList(i, tok.size()), org)));
            } catch (IndexOutOfBoundsException e) {
                throw new IllegalArgumentException("line " + lineNo + ": too few fields");
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("line " + lineNo + ": " + e.getMessage(), e);
            }
        }
        return of(org, records);
    }

    private static boolean isNumber(String s) {
        return !s.isEmpty() && s.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    private static long ttl(String s) {
        if (!isNumber(s) || s.length() > 10) {
            throw new IllegalArgumentException("bad number " + s);
        }
        return Long.parseLong(s);
    }

    private static Name absolute(String s, Name origin) {
        if (s.equals("@")) {
            return origin;
        }
        if (s.endsWith(".")) {
            return Name.parse(s);
        }
        if (origin.isRoot()) {
            return Name.parse(s);
        }
        return Name.parse(s + "." + origin);
    }

    private static RData rdata(RType type, List<String> f, Name origin) {
        return switch (type) {
            case A -> RData.A.of(f.get(0));
            case AAAA -> RData.Aaaa.parse(f.get(0));
            case NS -> new RData.Ns(absolute(f.get(0), origin));
            case CNAME -> new RData.Cname(absolute(f.get(0), origin));
            case MX -> new RData.Mx((int) ttl(f.get(0)), absolute(f.get(1), origin));
            case TXT -> new RData.Txt(f);
            case SOA -> new RData.Soa(absolute(f.get(0), origin), absolute(f.get(1), origin), ttl(f.get(2)), ttl(f.get(3)),
                    ttl(f.get(4)), ttl(f.get(5)), ttl(f.get(6)));
        };
    }

    /** Splits a line into tokens; a double-quoted token keeps spaces and ';'; an unquoted ';' starts a comment. */
    private static List<String> tokens(String line, int lineNo) {
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = line.length();
        while (i < n) {
            char c = line.charAt(i);
            if (c == ';') {
                break;
            }
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '"') {
                int close = line.indexOf('"', i + 1);
                if (close < 0) {
                    throw new IllegalArgumentException("line " + lineNo + ": unterminated quote");
                }
                out.add(line.substring(i + 1, close));
                i = close + 1;
            } else {
                int j = i;
                while (j < n && !Character.isWhitespace(line.charAt(j)) && line.charAt(j) != ';') {
                    j++;
                }
                out.add(line.substring(i, j));
                i = j;
            }
        }
        return out;
    }
}
