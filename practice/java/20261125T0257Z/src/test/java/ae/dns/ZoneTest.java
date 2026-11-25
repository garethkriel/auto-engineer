package ae.dns;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ZoneTest {
    static Name n(String s) {
        return Name.parse(s);
    }

    static final String TEXT = """
            $TTL 300
            @ 3600 IN SOA ns.example.test. hostmaster.example.test. 7 7200 3600 1209600 120 ; apex
            @ IN NS ns.example.test.
            ns IN A 10.0.2.1
            www IN A 192.0.2.1
            www IN A 192.0.2.2
            alias 60 IN CNAME www
            mail IN A 192.0.2.25
            @ IN MX 10 mail
            txt IN TXT "hello; world" "second"
            v6 IN AAAA 2001:db8::1
            *.wild IN A 192.0.2.99
            deep.a.b IN A 192.0.2.50
            sub IN NS ns.sub
            ns.sub IN A 10.9.9.9
            host.sub IN A 10.9.9.10
            *.cn IN CNAME www
            """;

    static Zone zone() {
        return Zone.parse("example.test", TEXT);
    }

    static String show(List<Rr> rrs) {
        return rrs.stream().map(r -> r.name() + " " + r.ttl() + " " + r.data()).collect(Collectors.joining(" | "));
    }

    @Test
    void parseReadsSoaAndRecordFields() {
        Zone z = zone();
        assertEquals(n("example.test"), z.origin());
        Rr soa = z.soa();
        assertEquals(3600, soa.ttl());
        assertEquals(new RData.Soa(n("ns.example.test"), n("hostmaster.example.test"), 7, 7200, 3600, 1209600, 120), soa.data());
        Zone.Result t = z.lookup(n("txt.example.test"), RType.TXT);
        assertEquals(Zone.Kind.ANSWER, t.kind());
        assertEquals(new RData.Txt(List.of("hello; world", "second")), t.records().get(0).data());
        assertEquals(300, t.records().get(0).ttl()); // $TTL default
        Zone.Result v6 = z.lookup(n("v6.example.test"), RType.AAAA);
        assertEquals(new RData.Aaaa(0x20010db800000000L, 1L), v6.records().get(0).data());
    }

    @Test
    void exactAnswerKeepsRecordOrder() {
        Zone.Result r = zone().lookup(n("WWW.example.test"), RType.A);
        assertEquals(Zone.Kind.ANSWER, r.kind());
        assertEquals(2, r.records().size());
        assertEquals(RData.A.of("192.0.2.1"), r.records().get(0).data());
        assertEquals(RData.A.of("192.0.2.2"), r.records().get(1).data());
    }

    @Test
    void nodataNxdomainAndApex() {
        Zone z = zone();
        assertEquals(Zone.Kind.NODATA, z.lookup(n("www.example.test"), RType.MX).kind());
        assertEquals(Zone.Kind.NXDOMAIN, z.lookup(n("ghost.example.test"), RType.A).kind());
        assertEquals(Zone.Kind.ANSWER, z.lookup(n("example.test"), RType.MX).kind());
        assertEquals(Zone.Kind.NODATA, z.lookup(n("example.test"), RType.A).kind());
        // apex NS is an ordinary answer, not a delegation
        assertEquals(Zone.Kind.ANSWER, z.lookup(n("example.test"), RType.NS).kind());
        assertEquals(Zone.Kind.ANSWER, z.lookup(n("example.test"), RType.SOA).kind());
        assertEquals(Zone.Kind.NOT_IN_ZONE, z.lookup(n("other.test"), RType.A).kind());
        assertEquals(Zone.Kind.NOT_IN_ZONE, z.lookup(n("test"), RType.A).kind());
        assertEquals(Zone.Kind.NOT_IN_ZONE, z.lookup(n("badexample.test"), RType.A).kind());
    }

    @Test
    void cnameAnswersOtherTypesButNotItself() {
        Zone z = zone();
        Zone.Result r = z.lookup(n("alias.example.test"), RType.A);
        assertEquals(Zone.Kind.CNAME, r.kind());
        assertEquals(new Rr(n("alias.example.test"), 60, new RData.Cname(n("www.example.test"))), r.records().get(0));
        assertEquals(Zone.Kind.ANSWER, z.lookup(n("alias.example.test"), RType.CNAME).kind());
        assertEquals(Zone.Kind.CNAME, z.lookup(n("alias.example.test"), RType.MX).kind());
    }

    @Test
    void emptyNonTerminalsExistButHaveNoData() {
        Zone z = zone();
        // deep.a.b exists => a.b and b exist as empty non-terminals
        assertEquals(Zone.Kind.NODATA, z.lookup(n("b.example.test"), RType.A).kind());
        assertEquals(Zone.Kind.NODATA, z.lookup(n("a.b.example.test"), RType.A).kind());
        assertEquals(Zone.Kind.ANSWER, z.lookup(n("deep.a.b.example.test"), RType.A).kind());
        // c.b does not exist and its closest encloser b has no wildcard
        assertEquals(Zone.Kind.NXDOMAIN, z.lookup(n("c.b.example.test"), RType.A).kind());
    }

    @Test
    void wildcardSynthesisUsesQueryNameAsOwner() {
        Zone z = zone();
        Zone.Result r = z.lookup(n("x.wild.example.test"), RType.A);
        assertEquals(Zone.Kind.ANSWER, r.kind());
        assertEquals(n("x.wild.example.test"), r.records().get(0).name());
        assertEquals(RData.A.of("192.0.2.99"), r.records().get(0).data());
        // two labels below the wildcard parent: closest encloser is wild.example.test
        Zone.Result deep = z.lookup(n("a.x.wild.example.test"), RType.A);
        assertEquals(Zone.Kind.ANSWER, deep.kind());
        assertEquals(n("a.x.wild.example.test"), deep.records().get(0).name());
        // wildcard exists but not for this type
        assertEquals(Zone.Kind.NODATA, z.lookup(n("x.wild.example.test"), RType.MX).kind());
        // the parent of a wildcard is an empty non-terminal, so the wildcard does not match it
        assertEquals(Zone.Kind.NODATA, z.lookup(n("wild.example.test"), RType.A).kind());
        // a wildcard CNAME is synthesised as a CNAME
        Zone.Result c = z.lookup(n("foo.cn.example.test"), RType.A);
        assertEquals(Zone.Kind.CNAME, c.kind());
        assertEquals(n("foo.cn.example.test"), c.records().get(0).name());
        assertEquals(new RData.Cname(n("www.example.test")), c.records().get(0).data());
    }

    @Test
    void existingNameBlocksTheApexWildcard() {
        Zone z = Zone.parse("z.test", """
                $TTL 60
                @ IN SOA ns.z.test. h.z.test. 1 2 3 4 5
                @ IN NS ns.z.test.
                ns IN A 1.1.1.1
                * IN A 9.9.9.9
                deep.a.b IN A 2.2.2.2
                """);
        assertEquals(Zone.Kind.ANSWER, z.lookup(n("zzz.z.test"), RType.A).kind());
        assertEquals(n("p.q.r.z.test"), z.lookup(n("p.q.r.z.test"), RType.A).records().get(0).name());
        // a.b.z.test exists as an empty non-terminal: the wildcard must NOT answer it
        assertEquals(Zone.Kind.NODATA, z.lookup(n("a.b.z.test"), RType.A).kind());
        // x.b.z.test does not exist; closest encloser is b.z.test (an ENT), whose wildcard *.b is absent
        assertEquals(Zone.Kind.NXDOMAIN, z.lookup(n("x.b.z.test"), RType.A).kind());
    }

    @Test
    void delegationWinsAndOccludesBelowTheCut() {
        Zone z = zone();
        for (String q : new String[] {"sub.example.test", "www.sub.example.test", "host.sub.example.test", "ns.sub.example.test", "a.b.c.sub.example.test"}) {
            for (RType t : new RType[] {RType.A, RType.NS, RType.MX}) {
                Zone.Result r = z.lookup(n(q), t);
                assertEquals(Zone.Kind.REFERRAL, r.kind(), q + " " + t);
                assertEquals(n("sub.example.test"), r.cut(), q);
                assertEquals(List.of(new Rr(n("sub.example.test"), 300, new RData.Ns(n("ns.sub.example.test")))), r.records());
            }
        }
    }

    @Test
    void addressesReturnsStoredAAndAaaaIncludingGlue() {
        Zone z = zone();
        assertEquals(List.of(RData.A.of("10.9.9.9")), z.addresses(n("ns.sub.example.test")).stream().map(Rr::data).toList());
        assertEquals(2, z.addresses(n("www.example.test")).size());
        assertTrue(z.addresses(n("nothing.example.test")).isEmpty());
        assertTrue(z.addresses(n("outside.test")).isEmpty());
    }

    @Test
    void invalidZonesAreRejected() {
        String soa = "@ IN SOA ns.z.test. h.z.test. 1 2 3 4 5\n";
        assertThrows(IllegalArgumentException.class, () -> Zone.parse("z.test", "$TTL 60\nwww IN A 1.2.3.4\n"), "no SOA");
        assertThrows(IllegalArgumentException.class, () -> Zone.parse("z.test", "$TTL 60\n" + soa + soa), "two SOA");
        assertThrows(IllegalArgumentException.class, () -> Zone.parse("z.test", "$TTL 60\n" + soa + "w IN CNAME x\nw IN A 1.2.3.4\n"), "cname + data");
        assertThrows(IllegalArgumentException.class, () -> Zone.parse("z.test", "$TTL 60\n" + soa + "other.test. IN A 1.2.3.4\n"), "outside origin");
        assertThrows(IllegalArgumentException.class, () -> Zone.parse("z.test", "$TTL 60\n" + soa + "w IN A 1.2.3\n"), "bad address");
        assertThrows(IllegalArgumentException.class, () -> Zone.parse("z.test", "$TTL 60\n" + soa + "w IN BOGUS x\n"), "bad type");
        assertThrows(IllegalArgumentException.class, () -> Zone.parse("z.test", soa + "w IN A 1.2.3.4\n"), "no ttl anywhere");
        // control: the same text without the defect parses
        assertNotNull(Zone.parse("z.test", "$TTL 60\n" + soa + "w IN CNAME x\nv IN A 1.2.3.4\n"));
    }

    // ---- randomized differential test against a flat-list oracle --------------

    record Q(Zone.Kind kind, List<Rr> records, Name cut) {}

    static Q oracle(List<Rr> recs, Name origin, Name q, RType t) {
        if (!q.isSubdomainOf(origin)) {
            return new Q(Zone.Kind.NOT_IN_ZONE, List.of(), null);
        }
        for (int k = origin.labelCount() + 1; k <= q.labelCount(); k++) {
            Name anc = q;
            while (anc.labelCount() > k) {
                anc = anc.parent();
            }
            Name a = anc;
            List<Rr> ns = recs.stream().filter(r -> r.name().equals(a) && r.data().type() == RType.NS).toList();
            if (!ns.isEmpty()) {
                return new Q(Zone.Kind.REFERRAL, ns, a);
            }
        }
        boolean exists = recs.stream().anyMatch(r -> r.name().isSubdomainOf(q));
        Name owner = q;
        List<Rr> at;
        if (exists) {
            at = recs.stream().filter(r -> r.name().equals(q)).toList();
        } else {
            Name e = q.parent();
            while (true) {
                Name ee = e;
                if (recs.stream().anyMatch(r -> r.name().isSubdomainOf(ee))) {
                    break;
                }
                e = e.parent();
            }
            Name w = e.child("*");
            at = recs.stream().filter(r -> r.name().equals(w)).toList();
            if (at.isEmpty()) {
                return new Q(Zone.Kind.NXDOMAIN, List.of(), null);
            }
        }
        List<Rr> typed = at.stream().filter(r -> r.data().type() == t).map(r -> new Rr(owner, r.ttl(), r.data())).toList();
        if (!typed.isEmpty()) {
            return new Q(Zone.Kind.ANSWER, typed, null);
        }
        List<Rr> cn = at.stream().filter(r -> r.data().type() == RType.CNAME).map(r -> new Rr(owner, r.ttl(), r.data())).toList();
        if (!cn.isEmpty()) {
            return new Q(Zone.Kind.CNAME, cn, null);
        }
        return new Q(Zone.Kind.NODATA, List.of(), null);
    }

    @Test
    void randomZonesAgreeWithFlatListOracle() {
        Random rnd = new Random(2024);
        Name origin = n("z.test");
        String[] labels = {"a", "b", "c", "*"};
        int kindsSeen[] = new int[Zone.Kind.values().length];
        for (int iter = 0; iter < 400; iter++) {
            List<Rr> recs = new ArrayList<>();
            recs.add(new Rr(origin, 100, new RData.Soa(n("ns.z.test"), n("h.z.test"), 1, 2, 3, 4, 5)));
            recs.add(new Rr(origin, 100, new RData.Ns(n("ns.z.test"))));
            List<Name> used = new ArrayList<>();
            int count = rnd.nextInt(8);
            for (int i = 0; i < count; i++) {
                Name nm = origin;
                int depth = 1 + rnd.nextInt(3);
                for (int d = 0; d < depth; d++) {
                    String l = labels[rnd.nextInt(labels.length)];
                    // '*' only as the leftmost label
                    nm = nm.child(l.equals("*") && d != depth - 1 ? "a" : l);
                }
                if (used.contains(nm)) {
                    continue;
                }
                used.add(nm);
                int k = rnd.nextInt(4);
                if (k == 0) {
                    recs.add(new Rr(nm, 10, new RData.Cname(n("target.z.test"))));
                } else if (k == 1 && nm.labelCount() > origin.labelCount()) {
                    recs.add(new Rr(nm, 10, new RData.Ns(n("ns.far.test"))));
                } else {
                    recs.add(new Rr(nm, 10, RData.A.of("1.1.1." + i)));
                    if (rnd.nextBoolean()) {
                        recs.add(new Rr(nm, 10, new RData.Mx(5, n("m.z.test"))));
                    }
                }
            }
            Zone z = Zone.of(origin, recs);
            for (int qi = 0; qi < 25; qi++) {
                Name q = Name.parse(rnd.nextInt(10) == 0 ? "out.test" : "z.test");
                if (q.equals(n("z.test"))) {
                    int d = rnd.nextInt(5);
                    for (int x = 0; x < d; x++) {
                        q = q.child(new String[] {"a", "b", "c"}[rnd.nextInt(3)]);
                    }
                }
                RType t = new RType[] {RType.A, RType.MX, RType.NS, RType.CNAME}[rnd.nextInt(4)];
                Q want = oracle(recs, origin, q, t);
                Zone.Result got = z.lookup(q, t);
                String ctx = "iter " + iter + " q=" + q + " " + t + " recs=" + recs;
                assertEquals(want.kind(), got.kind(), ctx);
                assertEquals(want.records(), got.records(), ctx);
                assertEquals(want.cut(), got.cut(), ctx);
                kindsSeen[got.kind().ordinal()]++;
            }
        }
        for (Zone.Kind k : Zone.Kind.values()) {
            assertTrue(kindsSeen[k.ordinal()] >= 20, "outcome mix too thin for " + k + ": " + kindsSeen[k.ordinal()]);
        }
    }
}
