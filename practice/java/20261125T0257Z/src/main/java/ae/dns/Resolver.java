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
 * Iterative resolver with a TTL cache. It starts at the root servers, follows referrals, chases CNAME chains and
 * trusts only what it can tie to the question: records on the CNAME chain, glue for the delegated name servers
 * below the delegated name, and SOA records of the answering zone.
 */
public final class Resolver {
    /** Final answer: {@code answers} holds the CNAME chain followed by the data (TTLs are remaining TTLs when cached). */
    public record Result(Rcode rcode, List<Rr> answers) {
        public Result {
            answers = List.copyOf(answers);
        }
    }

    static final int MAX_QUERIES = 48;
    static final int MAX_CNAME = 8;
    static final int MAX_DEPTH = 3;
    static final int MAX_REFERRALS = 16;

    private final Transport transport;
    private final List<Integer> roots;
    private final Clock clock;
    private final Cache cache;
    private int nextId = 1;
    private int sent;

    public Resolver(Transport transport, List<Integer> rootServers, Clock clock) {
        this.transport = Objects.requireNonNull(transport);
        this.roots = List.copyOf(rootServers);
        this.clock = Objects.requireNonNull(clock);
        this.cache = new Cache(clock);
        if (roots.isEmpty()) {
            throw new IllegalArgumentException("no root servers");
        }
    }

    /** Total queries handed to the transport so far. */
    public int queriesSent() {
        return sent;
    }

    public Result resolve(Name name, RType type) {
        return resolveChain(name, type, new Budget(), 0);
    }

    private static final class Budget {
        int left = MAX_QUERIES;
    }

    /** One step's outcome: {@code next != null} means the chain continues at that name. */
    private record Outcome(Rcode rcode, List<Rr> records, Name next) {}

    private static Outcome servfail() {
        return new Outcome(Rcode.SERVFAIL, List.of(), null);
    }

    private Result resolveChain(Name qname, RType qtype, Budget budget, int depth) {
        List<Rr> chain = new ArrayList<>();
        Set<Name> seen = new HashSet<>();
        seen.add(qname);
        Name cur = qname;
        for (int hop = 0; hop <= MAX_CNAME; hop++) {
            Outcome o = lookupOne(cur, qtype, budget, depth);
            chain.addAll(o.records());
            if (o.rcode() != Rcode.NOERROR) {
                return new Result(o.rcode(), chain);
            }
            if (o.next() == null) {
                return new Result(Rcode.NOERROR, chain);
            }
            cur = o.next();
            if (!seen.add(cur)) {
                return new Result(Rcode.SERVFAIL, chain); // CNAME loop
            }
        }
        return new Result(Rcode.SERVFAIL, chain); // chain too long
    }

    private Outcome lookupOne(Name cur, RType qtype, Budget budget, int depth) {
        List<Rr> hit = cache.get(cur, qtype);
        if (hit != null) {
            return new Outcome(Rcode.NOERROR, hit, null);
        }
        if (qtype != RType.CNAME) {
            List<Rr> cn = cache.get(cur, RType.CNAME);
            if (cn != null) {
                return new Outcome(Rcode.NOERROR, cn, ((RData.Cname) cn.get(0).data()).target());
            }
        }
        if (cache.isNxdomain(cur)) {
            return new Outcome(Rcode.NXDOMAIN, List.of(), null);
        }
        if (cache.isNodata(cur, qtype)) {
            return new Outcome(Rcode.NOERROR, List.of(), null);
        }
        return ask(cur, qtype, budget, depth);
    }

    private Outcome ask(Name cur, RType qtype, Budget budget, int depth) {
        Name cut = Name.ROOT;
        List<Name> hosts = null; // null: use the root servers
        for (int k = cur.labelCount(); k >= 1; k--) {
            Name a = cur.ancestor(k);
            List<Rr> ns = cache.get(a, RType.NS);
            if (ns != null) {
                cut = a;
                hosts = hostsOf(ns);
                break;
            }
        }
        for (int referral = 0; referral < MAX_REFERRALS; referral++) {
            List<Integer> servers = hosts == null ? roots : addressesOf(hosts, budget, depth);
            Reply reply = null;
            for (int server : servers) {
                if (budget.left <= 0) {
                    return servfail();
                }
                budget.left--;
                int id = nextId++ & 0xFFFF;
                sent++;
                Message resp = transport.send(server, Message.query(id, cur, qtype));
                reply = classify(resp, id, cur, qtype, cut);
                if (reply != null) {
                    break;
                }
            }
            if (reply == null) {
                return servfail();
            }
            if (reply.outcome() != null) {
                return reply.outcome();
            }
            cut = reply.cut();
            hosts = reply.hosts();
        }
        return servfail();
    }

    private static List<Name> hostsOf(List<Rr> nsSet) {
        List<Name> hosts = new ArrayList<>();
        for (Rr r : nsSet) {
            hosts.add(((RData.Ns) r.data()).host());
        }
        return hosts;
    }

    /** IPv4 addresses of the given name servers: cached ones, else resolve them (glue-less delegation). */
    private List<Integer> addressesOf(List<Name> hosts, Budget budget, int depth) {
        List<Integer> addrs = new ArrayList<>();
        for (Name h : hosts) {
            collect(cache.get(h, RType.A), addrs);
        }
        if (!addrs.isEmpty() || depth >= MAX_DEPTH) {
            return addrs;
        }
        for (Name h : hosts) {
            Result r = resolveChain(h, RType.A, budget, depth + 1);
            if (r.rcode() == Rcode.NOERROR) {
                for (Rr rr : r.answers()) {
                    if (rr.name().equals(h)) {
                        collect(List.of(rr), addrs);
                    }
                }
            }
            if (!addrs.isEmpty()) {
                break;
            }
        }
        return addrs;
    }

    private static void collect(List<Rr> rrs, List<Integer> into) {
        if (rrs == null) {
            return;
        }
        for (Rr r : rrs) {
            if (r.data() instanceof RData.A a) {
                into.add(a.address());
            }
        }
    }

    /** Either a final outcome or a referral to continue with. */
    private record Reply(Outcome outcome, Name cut, List<Name> hosts) {}

    /** Validates and digests one reply; null means "unusable, try the next server". */
    private Reply classify(Message resp, int id, Name cur, RType qtype, Name cut) {
        if (resp == null || resp.id() != id || !resp.response() || !resp.qname().equals(cur) || resp.qtype() != qtype) {
            return null;
        }
        if (resp.rcode() != Rcode.NOERROR && resp.rcode() != Rcode.NXDOMAIN) {
            return null;
        }
        // Walk the CNAME chain inside the reply; keep only what hangs off the question.
        List<Rr> out = new ArrayList<>();
        Set<Name> seen = new HashSet<>();
        seen.add(cur);
        Name n = cur;
        Name next = null;
        boolean complete = false;
        while (true) {
            List<Rr> data = select(resp.answers(), n, qtype);
            if (!data.isEmpty()) {
                out.addAll(data);
                cache.put(n, qtype, data);
                complete = true;
                break;
            }
            if (qtype == RType.CNAME) {
                break;
            }
            List<Rr> cn = select(resp.answers(), n, RType.CNAME);
            if (cn.isEmpty()) {
                break;
            }
            Rr first = cn.get(0);
            out.add(first);
            cache.put(n, RType.CNAME, List.of(first));
            Name target = ((RData.Cname) first.data()).target();
            if (!target.isSubdomainOf(cut) || !seen.add(target)) {
                next = target; // outside this zone's bailiwick, or a loop: the caller decides
                break;
            }
            n = target;
        }
        if (resp.rcode() == Rcode.NXDOMAIN) {
            if (!resp.aa() || complete || next != null) {
                return null;
            }
            cache.putNxdomain(n, negativeTtl(resp, n, cut));
            return new Reply(new Outcome(Rcode.NXDOMAIN, out, null), null, null);
        }
        if (complete) {
            return new Reply(new Outcome(Rcode.NOERROR, out, null), null, null);
        }
        if (next != null) {
            return new Reply(new Outcome(Rcode.NOERROR, out, next), null, null);
        }
        long negTtl = negativeTtl(resp, n, cut);
        if (resp.aa() && negTtl >= 0) {
            cache.putNodata(n, qtype, negTtl);
            return new Reply(new Outcome(Rcode.NOERROR, out, null), null, null);
        }
        if (!out.isEmpty()) {
            return new Reply(new Outcome(Rcode.NOERROR, out, n), null, null); // chain ends in a delegation
        }
        return referral(resp, cur, cut);
    }

    private Reply referral(Message resp, Name cur, Name cut) {
        Name owner = null;
        List<Rr> ns = new ArrayList<>();
        for (Rr r : resp.authority()) {
            if (r.type() != RType.NS || !cur.isSubdomainOf(r.name()) || !r.name().isSubdomainOf(cut)
                    || r.name().labelCount() <= cut.labelCount()) {
                continue; // a referral must be strictly deeper than the current cut and cover the question
            }
            if (owner == null) {
                owner = r.name();
            }
            if (r.name().equals(owner)) {
                ns.add(r);
            }
        }
        if (owner == null) {
            return null;
        }
        cache.put(owner, RType.NS, ns);
        List<Name> hosts = hostsOf(ns);
        Map<Name, List<Rr>> glue = new LinkedHashMap<>();
        for (Rr r : resp.additional()) {
            if (r.type() == RType.A && hosts.contains(r.name()) && r.name().isSubdomainOf(owner)) {
                glue.computeIfAbsent(r.name(), k -> new ArrayList<>()).add(r);
            }
        }
        for (Map.Entry<Name, List<Rr>> e : glue.entrySet()) {
            cache.put(e.getKey(), RType.A, e.getValue());
        }
        return new Reply(null, owner, hosts);
    }

    /** min(SOA ttl, SOA minimum) of an SOA that covers {@code n} and lies within {@code cut}; -1 if there is none. */
    private static long negativeTtl(Message resp, Name n, Name cut) {
        for (Rr r : resp.authority()) {
            if (r.data() instanceof RData.Soa soa && n.isSubdomainOf(r.name()) && r.name().isSubdomainOf(cut)) {
                return Math.min(r.ttl(), soa.minimum());
            }
        }
        return -1;
    }

    private static List<Rr> select(List<Rr> rrs, Name owner, RType type) {
        List<Rr> out = new ArrayList<>();
        for (Rr r : rrs) {
            if (r.name().equals(owner) && r.type() == type) {
                out.add(r);
            }
        }
        return out;
    }

    // ---- cache -----------------------------------------------------------------

    private record Key(Name name, RType type) {}

    private record Entry(long expiry, List<Rr> rrs) {}

    private static final class Cache {
        private final Clock clock;
        private final Map<Key, Entry> positive = new HashMap<>();
        private final Map<Name, Long> nxdomain = new HashMap<>();
        private final Map<Key, Long> nodata = new HashMap<>();

        Cache(Clock clock) {
            this.clock = clock;
        }

        void put(Name name, RType type, List<Rr> rrs) {
            long ttl = Long.MAX_VALUE;
            for (Rr r : rrs) {
                ttl = Math.min(ttl, r.ttl());
            }
            if (rrs.isEmpty() || ttl <= 0) {
                return;
            }
            positive.put(new Key(name, type), new Entry(clock.nowSeconds() + ttl, List.copyOf(rrs)));
        }

        /** The cached RRset with remaining TTLs, or null if absent or expired. */
        List<Rr> get(Name name, RType type) {
            Key k = new Key(name, type);
            Entry e = positive.get(k);
            if (e == null) {
                return null;
            }
            long remaining = e.expiry() - clock.nowSeconds();
            if (remaining <= 0) {
                positive.remove(k);
                return null;
            }
            List<Rr> out = new ArrayList<>(e.rrs().size());
            for (Rr r : e.rrs()) {
                out.add(r.withTtl(remaining));
            }
            return out;
        }

        void putNxdomain(Name name, long ttl) {
            if (ttl > 0) {
                nxdomain.put(name, clock.nowSeconds() + ttl);
            }
        }

        void putNodata(Name name, RType type, long ttl) {
            if (ttl > 0) {
                nodata.put(new Key(name, type), clock.nowSeconds() + ttl);
            }
        }

        boolean isNxdomain(Name name) {
            Long exp = nxdomain.get(name);
            return exp != null && exp > clock.nowSeconds();
        }

        boolean isNodata(Name name, RType type) {
            Long exp = nodata.get(new Key(name, type));
            return exp != null && exp > clock.nowSeconds();
        }
    }
}
