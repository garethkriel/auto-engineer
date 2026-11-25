package ae.dns;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ResolverTest {
    static Name n(String s) {
        return Name.parse(s);
    }

    static String show(Resolver.Result r) {
        return r.rcode() + " " + r.answers().stream().map(a -> a.name() + " " + a.ttl() + " " + a.data()).collect(Collectors.toList());
    }

    static List<String> types(Resolver.Result r) {
        return r.answers().stream().map(a -> a.name() + " " + a.data().type()).toList();
    }

    @Test
    void freshResolutionWalksRootTldZone() {
        World w = new World();
        ManualClock clock = new ManualClock();
        Resolver res = w.resolver(clock);
        Resolver.Result r = res.resolve(n("www.example.test"), RType.A);
        assertEquals(Rcode.NOERROR, r.rcode());
        assertEquals(List.of(new Rr(n("www.example.test"), 300, RData.A.of("192.0.2.1"))), r.answers());
        assertEquals(List.of(
                "10.0.0.1 www.example.test. A",
                "10.0.1.1 www.example.test. A",
                "10.0.2.1 www.example.test. A"), w.net.queryLog());
    }

    @Test
    void secondIdenticalQueryIsServedFromCacheWithDecayingTtl() {
        World w = new World();
        ManualClock clock = new ManualClock();
        Resolver res = w.resolver(clock);
        res.resolve(n("www.example.test"), RType.A);
        assertEquals(3, w.net.queryCount());
        clock.advance(100);
        Resolver.Result r = res.resolve(n("www.example.test"), RType.A);
        assertEquals(3, w.net.queryCount(), "served from cache");
        assertEquals(200, r.answers().get(0).ttl());
        clock.advance(199); // t = 299: ttl 1 left
        assertEquals(1, res.resolve(n("www.example.test"), RType.A).answers().get(0).ttl());
        assertEquals(3, w.net.queryCount());
        clock.advance(1); // t = 300: expired exactly at its TTL
        Resolver.Result again = res.resolve(n("www.example.test"), RType.A);
        assertEquals(4, w.net.queryCount(), "one query straight to the cached zone server");
        assertEquals("10.0.2.1 www.example.test. A", w.net.queryLog().get(3));
        assertEquals(300, again.answers().get(0).ttl());
    }

    @Test
    void siblingNameReusesCachedDelegation() {
        World w = new World();
        Resolver res = w.resolver(new ManualClock());
        res.resolve(n("www.example.test"), RType.A);
        Resolver.Result r = res.resolve(n("mail.example.test"), RType.A);
        assertEquals(RData.A.of("192.0.2.25"), r.answers().get(0).data());
        assertEquals(4, w.net.queryCount());
        assertEquals("10.0.2.1 mail.example.test. A", w.net.queryLog().get(3));
    }

    @Test
    void rrsetCacheUsesMinimumTtlOfTheSet() {
        World w = new World();
        ManualClock clock = new ManualClock();
        Resolver res = w.resolver(clock);
        Resolver.Result first = res.resolve(n("multi.example.test"), RType.A);
        assertEquals(List.of(100L, 200L), first.answers().stream().map(Rr::ttl).toList());
        int q = w.net.queryCount();
        clock.advance(50);
        Resolver.Result cached = res.resolve(n("multi.example.test"), RType.A);
        assertEquals(q, w.net.queryCount());
        assertEquals(List.of(50L, 50L), cached.answers().stream().map(Rr::ttl).toList());
        clock.advance(50); // t = 100: the whole set expires with its shortest record
        res.resolve(n("multi.example.test"), RType.A);
        assertEquals(q + 1, w.net.queryCount());
    }

    @Test
    void nxdomainIsNegativelyCachedForSoaMinimumAndCoversEveryType() {
        World w = new World();
        ManualClock clock = new ManualClock();
        Resolver res = w.resolver(clock);
        Resolver.Result r = res.resolve(n("nope.example.test"), RType.A);
        assertEquals(Rcode.NXDOMAIN, r.rcode());
        assertTrue(r.answers().isEmpty());
        assertEquals(3, w.net.queryCount());
        clock.advance(299); // soa ttl 3600, minimum 300 -> negative ttl 300
        assertEquals(Rcode.NXDOMAIN, res.resolve(n("nope.example.test"), RType.MX).rcode());
        assertEquals(3, w.net.queryCount(), "NXDOMAIN applies to all types of the name");
        clock.advance(1);
        assertEquals(Rcode.NXDOMAIN, res.resolve(n("nope.example.test"), RType.A).rcode());
        assertEquals(4, w.net.queryCount());
    }

    @Test
    void nodataIsCachedPerTypeOnly() {
        World w = new World();
        ManualClock clock = new ManualClock();
        Resolver res = w.resolver(clock);
        Resolver.Result r = res.resolve(n("www.example.test"), RType.MX);
        assertEquals(Rcode.NOERROR, r.rcode());
        assertTrue(r.answers().isEmpty());
        assertEquals(3, w.net.queryCount());
        res.resolve(n("www.example.test"), RType.MX);
        assertEquals(3, w.net.queryCount(), "NODATA cached");
        Resolver.Result a = res.resolve(n("www.example.test"), RType.A);
        assertEquals(1, a.answers().size(), "the A type is unaffected by NODATA for MX");
        assertEquals(4, w.net.queryCount());
        clock.advance(300);
        res.resolve(n("www.example.test"), RType.MX);
        assertEquals(5, w.net.queryCount(), "NODATA expired");
    }

    @Test
    void failsOverToTheNextNameServerInReferralOrder() {
        World w = new World();
        w.net.setDown("10.0.2.1", true);
        Resolver res = w.resolver(new ManualClock());
        Resolver.Result r = res.resolve(n("www.example.test"), RType.A);
        assertEquals(Rcode.NOERROR, r.rcode());
        assertEquals(List.of(
                "10.0.0.1 www.example.test. A",
                "10.0.1.1 www.example.test. A",
                "10.0.2.1 www.example.test. A",
                "10.0.2.2 www.example.test. A"), w.net.queryLog());
    }

    @Test
    void allServersDownGivesServfailAndAttemptsEachOnce() {
        World w = new World();
        w.net.setDown("10.0.2.1", true);
        w.net.setDown("10.0.2.2", true);
        Resolver.Result r = w.resolver(new ManualClock()).resolve(n("www.example.test"), RType.A);
        assertEquals(Rcode.SERVFAIL, r.rcode());
        assertEquals(4, w.net.queryCount());
    }

    @Test
    void glueLessNameServerIsResolvedSeparately() {
        World w = new World();
        Resolver res = w.resolver(new ManualClock());
        Resolver.Result r = res.resolve(n("www.corp.test"), RType.A);
        assertEquals(List.of(new Rr(n("www.corp.test"), 300, RData.A.of("192.0.2.77"))), r.answers());
        // test. offers glue for ns.example.test in the corp referral, but it is not below corp.test so it is ignored
        assertEquals(List.of(
                "10.0.0.1 www.corp.test. A",
                "10.0.1.1 www.corp.test. A",
                "10.0.1.1 ns.example.test. A",
                "10.0.2.1 ns.example.test. A",
                "10.0.2.1 www.corp.test. A"), w.net.queryLog());
        int before = w.net.queryCount();
        res.resolve(n("mail.corp.test"), RType.A);
        assertEquals(before + 1, w.net.queryCount(), "corp delegation and the server address are now cached");
    }

    @Test
    void cnameInsideTheSameZoneFollowsTheChain() {
        World w = new World();
        Resolver.Result r = w.resolver(new ManualClock()).resolve(n("alias.example.test"), RType.A);
        assertEquals(Rcode.NOERROR, r.rcode());
        assertEquals(List.of(new Rr(n("alias.example.test"), 60, new RData.Cname(n("www.example.test"))),
                new Rr(n("www.example.test"), 300, RData.A.of("192.0.2.1"))), r.answers());
        assertEquals(3, w.net.queryCount(), "the server chased the in-zone CNAME itself");
    }

    @Test
    void cnameToAnotherZoneOnTheSameServerIsReResolvedFromTheTree() {
        World w = new World();
        Resolver.Result r = w.resolver(new ManualClock()).resolve(n("ext.example.test"), RType.A);
        assertEquals(List.of("ext.example.test. CNAME", "www.corp.test. A"), types(r));
        // The server also sent www.corp.test, but that is outside example.test's bailiwick: resolved again.
        assertEquals(List.of(
                "10.0.0.1 ext.example.test. A",
                "10.0.1.1 ext.example.test. A",
                "10.0.2.1 ext.example.test. A",
                "10.0.1.1 www.corp.test. A",
                "10.0.2.1 www.corp.test. A"), w.net.queryLog());
    }

    @Test
    void cnameToAnotherDelegationContinuesFromTheCachedParent() {
        World w = new World();
        Resolver.Result r = w.resolver(new ManualClock()).resolve(n("far.example.test"), RType.A);
        assertEquals(List.of("far.example.test. CNAME", "target.other.test. A"), types(r));
        assertEquals(RData.A.of("198.51.100.7"), r.answers().get(1).data());
        assertEquals(List.of(
                "10.0.0.1 far.example.test. A",
                "10.0.1.1 far.example.test. A",
                "10.0.2.1 far.example.test. A",
                "10.0.1.1 target.other.test. A",
                "10.0.3.1 target.other.test. A"), w.net.queryLog());
    }

    @Test
    void cnameThenNodataReturnsTheChainAndCachesTheEnd() {
        World w = new World();
        Resolver res = w.resolver(new ManualClock());
        Resolver.Result r = res.resolve(n("alias.example.test"), RType.MX);
        assertEquals(Rcode.NOERROR, r.rcode());
        assertEquals(List.of("alias.example.test. CNAME"), types(r));
        int q = w.net.queryCount();
        // www.example.test MX is now a cached NODATA
        Resolver.Result direct = res.resolve(n("www.example.test"), RType.MX);
        assertEquals(Rcode.NOERROR, direct.rcode());
        assertEquals(q, w.net.queryCount());
    }

    @Test
    void cnameLoopIsServfailNotAHang() {
        World w = new World();
        Resolver.Result r = w.resolver(new ManualClock()).resolve(n("l1.example.test"), RType.A);
        assertEquals(Rcode.SERVFAIL, r.rcode());
        assertEquals(3, w.net.queryCount());
    }

    @Test
    void wildcardAnswerIsReturnedUnderTheQueryName() {
        World w = new World();
        Resolver.Result r = w.resolver(new ManualClock()).resolve(n("x.wild.example.test"), RType.A);
        assertEquals(List.of(new Rr(n("x.wild.example.test"), 300, RData.A.of("192.0.2.99"))), r.answers());
    }

    @Test
    void selfReferringReferralFromALameServerIsServfail() {
        World w = new World();
        Resolver.Result r = w.resolver(new ManualClock()).resolve(n("www.lame.test"), RType.A);
        assertEquals(Rcode.SERVFAIL, r.rcode());
        assertEquals(List.of(
                "10.0.0.1 www.lame.test. A",
                "10.0.1.1 www.lame.test. A",
                "10.0.4.1 www.lame.test. A"), w.net.queryLog());
    }

    // ---- custom transports: hostile servers ---------------------------------

    static Message reply(Message q, Rcode rc, boolean aa, List<Rr> an, List<Rr> ns, List<Rr> ar) {
        return new Message(q.id(), true, aa, false, q.rd(), false, rc, q.qname(), q.qtype(), an, ns, ar);
    }

    @Test
    void unrelatedRecordsInAnAnswerAreNotCached() {
        AtomicInteger calls = new AtomicInteger();
        Transport t = (server, q) -> {
            calls.incrementAndGet();
            if (q.qname().equals(n("x.test"))) {
                return reply(q, Rcode.NOERROR, true, List.of(
                        new Rr(n("x.test"), 300, RData.A.of("1.1.1.1")),
                        new Rr(n("www.bank.test"), 300, RData.A.of("6.6.6.6"))), List.of(), List.of());
            }
            return reply(q, Rcode.NXDOMAIN, true, List.of(), List.of(), List.of());
        };
        Resolver res = new Resolver(t, List.of(1), new ManualClock());
        assertEquals(Rcode.NOERROR, res.resolve(n("x.test"), RType.A).rcode());
        Resolver.Result bank = res.resolve(n("www.bank.test"), RType.A);
        assertEquals(2, calls.get(), "poisoned record must not be served from cache");
        assertEquals(Rcode.NXDOMAIN, bank.rcode());
        assertTrue(bank.answers().isEmpty());
    }

    @Test
    void outOfBailiwickCnameTargetAndGlueAreNotTrusted() {
        List<String> log = new ArrayList<>();
        Transport t = (server, q) -> {
            log.add(new RData.A(server) + " " + q.qname());
            if (server == RData.A.parseAddress("10.0.0.1")) {
                if (q.qname().isSubdomainOf(n("example.test"))) {
                    return reply(q, Rcode.NOERROR, false, List.of(),
                            List.of(new Rr(n("example.test"), 300, new RData.Ns(n("ns.example.test")))),
                            List.of(new Rr(n("ns.example.test"), 300, RData.A.of("10.0.2.1")),
                                    new Rr(n("ns.bank.test"), 300, RData.A.of("6.6.6.6")), // not an NS of the referral
                                    new Rr(n("evil.example.test"), 300, RData.A.of("6.6.6.8")))); // below example.test but not an NS host
                }
                return reply(q, Rcode.NXDOMAIN, true, List.of(), List.of(), List.of());
            }
            return reply(q, Rcode.NOERROR, true, List.of(
                    new Rr(n("x.example.test"), 300, new RData.Cname(n("www.bank.test"))),
                    new Rr(n("www.bank.test"), 300, RData.A.of("6.6.6.6"))), List.of(), List.of());
        };
        Resolver res = new Resolver(t, List.of(RData.A.parseAddress("10.0.0.1")), new ManualClock());
        Resolver.Result r = res.resolve(n("x.example.test"), RType.A);
        assertEquals(Rcode.NXDOMAIN, r.rcode());
        assertEquals(List.of("x.example.test. CNAME"), types(r));
        assertEquals(List.of("10.0.0.1 x.example.test.", "10.0.2.1 x.example.test.", "10.0.0.1 www.bank.test."), log);
        // glue for a host that is not an NS of the referral was not cached: resolving it goes to the root
        log.clear();
        assertEquals(Rcode.NXDOMAIN, res.resolve(n("ns.bank.test"), RType.A).rcode());
        assertEquals(List.of("10.0.0.1 ns.bank.test."), log);
        log.clear();
        Resolver.Result evil = res.resolve(n("evil.example.test"), RType.A);
        assertEquals(List.of("10.0.2.1 evil.example.test."), log, "an extra additional record is not cached either");
        assertTrue(evil.answers().isEmpty(), show(evil));
    }

    @Test
    void mismatchedIdOrQuestionIsIgnoredAndNextServerTried() {
        List<Integer> tried = new ArrayList<>();
        Transport t = (server, q) -> {
            tried.add(server);
            if (server == 1) {
                return new Message((q.id() + 1) & 0xFFFF, true, true, false, true, false, Rcode.NOERROR, q.qname(), q.qtype(),
                        List.of(new Rr(q.qname(), 60, RData.A.of("6.6.6.6"))), List.of(), List.of());
            }
            if (server == 2) {
                return new Message(q.id(), true, true, false, true, false, Rcode.NOERROR, n("other.test"), q.qtype(),
                        List.of(new Rr(q.qname(), 60, RData.A.of("6.6.6.6"))), List.of(), List.of());
            }
            return reply(q, Rcode.NOERROR, true, List.of(new Rr(q.qname(), 60, RData.A.of("7.7.7.7"))), List.of(), List.of());
        };
        Resolver res = new Resolver(t, List.of(1, 2, 3), new ManualClock());
        Resolver.Result r = res.resolve(n("a.test"), RType.A);
        assertEquals(List.of(1, 2, 3), tried);
        assertEquals(RData.A.of("7.7.7.7"), r.answers().get(0).data());
    }

    @Test
    void nxdomainWithoutAuthoritativeFlagIsNotBelieved() {
        List<Integer> tried = new ArrayList<>();
        Transport t = (server, q) -> {
            tried.add(server);
            if (server == 1) {
                return reply(q, Rcode.NXDOMAIN, false, List.of(), List.of(), List.of());
            }
            return reply(q, Rcode.NOERROR, true, List.of(new Rr(q.qname(), 60, RData.A.of("7.7.7.7"))), List.of(), List.of());
        };
        Resolver res = new Resolver(t, List.of(1, 2), new ManualClock());
        Resolver.Result r = res.resolve(n("a.test"), RType.A);
        assertEquals(List.of(1, 2), tried);
        assertEquals(Rcode.NOERROR, r.rcode());
        assertEquals(RData.A.of("7.7.7.7"), r.answers().get(0).data());
    }

    @Test
    void servfailAndRefusedServersAreSkipped() {
        Transport t = (server, q) -> {
            if (server == 1) {
                return reply(q, Rcode.SERVFAIL, false, List.of(), List.of(), List.of());
            }
            if (server == 2) {
                return reply(q, Rcode.REFUSED, false, List.of(), List.of(), List.of());
            }
            if (server == 3) {
                return null;
            }
            return reply(q, Rcode.NOERROR, true, List.of(new Rr(q.qname(), 60, RData.A.of("7.7.7.7"))), List.of(), List.of());
        };
        Resolver res = new Resolver(t, List.of(1, 2, 3, 4), new ManualClock());
        assertEquals(Rcode.NOERROR, res.resolve(n("a.test"), RType.A).rcode());
        assertEquals(4, res.queriesSent());
    }

    @Test
    void mutuallyDependentGluelessNameServersTerminateWithServfail() {
        AtomicInteger calls = new AtomicInteger();
        Transport t = (server, q) -> {
            calls.incrementAndGet();
            Name qn = q.qname();
            Name cut;
            Name host;
            if (qn.isSubdomainOf(n("loop.test"))) {
                cut = n("loop.test");
                host = n("ns.loop2.test");
            } else {
                cut = n("loop2.test");
                host = n("ns.loop.test");
            }
            return reply(q, Rcode.NOERROR, false, List.of(), List.of(new Rr(cut, 300, new RData.Ns(host))), List.of());
        };
        Resolver res = new Resolver(t, List.of(1), new ManualClock());
        Resolver.Result r = res.resolve(n("www.loop.test"), RType.A);
        assertEquals(Rcode.SERVFAIL, r.rcode());
        assertTrue(calls.get() > 0 && calls.get() <= 48, "queries " + calls.get());
    }

    // ---- differential test against a direct zone-chasing oracle ---------------

    static List<String> flat(List<Rr> rrs) {
        return rrs.stream().map(r -> r.name() + " " + r.data()).toList();
    }

    /** Ignores delegation: pick the deepest zone, look up, chase CNAMEs across zones. */
    static Resolver.Result oracle(List<Zone> zones, Name qname, RType qtype) {
        List<Rr> chain = new ArrayList<>();
        List<Name> seen = new ArrayList<>(List.of(qname));
        Name cur = qname;
        for (int hop = 0; hop < 9; hop++) {
            Zone best = null;
            for (Zone z : zones) {
                if (cur.isSubdomainOf(z.origin()) && (best == null || z.origin().labelCount() > best.origin().labelCount())) {
                    best = z;
                }
            }
            assertNotNull(best, "oracle: no zone for " + cur);
            Zone.Result zr = best.lookup(cur, qtype);
            switch (zr.kind()) {
                case ANSWER -> {
                    chain.addAll(zr.records());
                    return new Resolver.Result(Rcode.NOERROR, chain);
                }
                case NODATA -> {
                    return new Resolver.Result(Rcode.NOERROR, chain);
                }
                case NXDOMAIN -> {
                    return new Resolver.Result(Rcode.NXDOMAIN, chain);
                }
                case CNAME -> {
                    chain.add(zr.records().get(0));
                    cur = ((RData.Cname) zr.records().get(0).data()).target();
                    if (seen.contains(cur)) {
                        return new Resolver.Result(Rcode.SERVFAIL, chain);
                    }
                    seen.add(cur);
                }
                default -> throw new AssertionError("deepest zone gave " + zr.kind());
            }
        }
        return new Resolver.Result(Rcode.SERVFAIL, chain);
    }

    @Test
    void resolverAgreesWithDirectOracleForFreshAndWarmCaches() {
        String[] names = {"www.example.test", "alias.example.test", "ext.example.test", "far.example.test", "l1.example.test",
            "mx.example.test", "mail.example.test", "x.wild.example.test", "a.x.wild.example.test", "wild.example.test",
            "nope.example.test", "multi.example.test", "txt.example.test", "example.test", "ns.example.test",
            "www.corp.test", "mail.corp.test", "nothing.corp.test", "corp.test", "target.other.test", "ns.other.test",
            "other.test", "test", "ns.test", "a.root", "nonexistent.test", "deeper.nonexistent.test"};
        RType[] types = {RType.A, RType.MX, RType.CNAME, RType.NS, RType.SOA, RType.TXT};
        World shared = new World();
        ManualClock clock = new ManualClock();
        Resolver warm = shared.resolver(clock);
        for (int round = 0; round < 3; round++) {
            for (String nm : names) {
                for (RType t : types) {
                    Resolver.Result want = oracle(shared.zones, n(nm), t);
                    World fresh = new World();
                    Resolver.Result gotFresh = fresh.resolver(new ManualClock()).resolve(n(nm), t);
                    String ctx = "round " + round + " " + nm + " " + t;
                    assertEquals(want.rcode(), gotFresh.rcode(), "fresh " + ctx + " " + show(gotFresh));
                    assertEquals(flat(want.answers()), flat(gotFresh.answers()), "fresh " + ctx);
                    Resolver.Result gotWarm = warm.resolve(n(nm), t);
                    assertEquals(want.rcode(), gotWarm.rcode(), "warm " + ctx + " " + show(gotWarm));
                    assertEquals(flat(want.answers()), flat(gotWarm.answers()), "warm " + ctx);
                }
            }
            clock.advance(round == 0 ? 10 : 400); // round 2 runs after most TTLs (300) have expired
        }
    }
}
