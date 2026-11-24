package ae.netroute;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class FirewallTest {
    private static Firewall.Packet pkt(String src, String dst, int port) {
        return new Firewall.Packet(Ip4.parse(src), Ip4.parse(dst), port);
    }

    private static final String RULES = String.join("\n",
        "# web servers",
        "allow any 192.168.1.0/24 80-443",
        "",
        "deny 10.66.0.0/16 any any   # bad actors",
        "allow 10.0.0.0/8 192.168.2.0/24 22",
        "allow 10.0.0.0/8 any any");

    @Test
    void firstMatchWinsAndDefaultDenies() {
        Firewall fw = Firewall.parse(RULES);
        assertEquals(4, fw.rules().size());
        Firewall.Decision d = fw.evaluate(pkt("8.8.8.8", "192.168.1.5", 80));
        assertTrue(d.allow());
        assertEquals(0, d.ruleIndex());
        d = fw.evaluate(pkt("10.66.1.1", "192.168.1.5", 80)); // rule 0 matches before the deny
        assertTrue(d.allow());
        assertEquals(0, d.ruleIndex());
        d = fw.evaluate(pkt("10.66.1.1", "192.168.2.5", 22));
        assertFalse(d.allow());
        assertEquals(1, d.ruleIndex());
        d = fw.evaluate(pkt("10.1.1.1", "192.168.2.5", 22));
        assertTrue(d.allow());
        assertEquals(2, d.ruleIndex());
        d = fw.evaluate(pkt("10.1.1.1", "172.16.0.1", 9999));
        assertEquals(3, d.ruleIndex());
        d = fw.evaluate(pkt("11.1.1.1", "172.16.0.1", 9999));
        assertFalse(d.allow());
        assertEquals(-1, d.ruleIndex());
        assertEquals(-1, fw.evaluate(pkt("8.8.8.8", "192.168.1.5", 444)).ruleIndex()); // port just above range
        assertEquals(-1, fw.evaluate(pkt("8.8.8.8", "192.168.1.5", 79)).ruleIndex());  // port just below range
        assertEquals(0, fw.evaluate(pkt("8.8.8.8", "192.168.1.5", 443)).ruleIndex());
        assertEquals(-1, fw.evaluate(pkt("8.8.8.8", "192.168.2.0", 80)).ruleIndex());  // dst just outside /24
    }

    @Test
    void parseErrorsNameTheLine() {
        String[][] bad = {
            {"allow any any", "line 1:"},
            {"# c\n\nmaybe any any any", "line 3:"},
            {"allow any any any\nallow 10.0.0.1/24 any any", "line 2:"},
            {"allow any any 70000", "line 1:"},
            {"allow any any 90-80", "line 1:"},
            {"allow any any 80 extra", "line 1:"},
            {"allow 1.2.3 any any", "line 1:"},
            {"allow any any -1", "line 1:"},
            {"allow any any 8x", "line 1:"},
        };
        for (String[] b : bad) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Firewall.parse(b[0]), b[0]);
            assertTrue(e.getMessage().contains(b[1]), e.getMessage());
        }
        assertDoesNotThrow(() -> Firewall.parse("allow any any 0-65535"));
        assertDoesNotThrow(() -> Firewall.parse("ALLOW any any any".toLowerCase()));
        assertEquals(0, Firewall.parse("# nothing\n\n").rules().size());
    }

    @Test
    void shadowedLiteralCases() {
        // rule 1 is covered by rule 0 alone
        assertEquals(List.of(1), Firewall.parse("allow 10.0.0.0/8 any any\ndeny 10.1.0.0/16 any 80").shadowed());
        // rule 2 covered only by the union of rules 0 and 1 (two halves)
        assertEquals(List.of(2), Firewall.parse(
            "allow 10.0.0.0/25 any any\ndeny 10.0.0.128/25 any any\nallow 10.0.0.0/24 any 80").shadowed());
        // same but one port is left uncovered: not shadowed
        assertEquals(List.of(), Firewall.parse(
            "allow 10.0.0.0/25 any 0-79\ndeny 10.0.0.128/25 any any\nallow 10.0.0.0/24 any 80").shadowed());
        // a later broader rule is never shadowed by a narrower earlier one
        assertEquals(List.of(), Firewall.parse("allow 10.1.0.0/16 any any\ndeny 10.0.0.0/8 any any").shadowed());
        // dst dimension decides
        assertEquals(List.of(), Firewall.parse("allow any 1.0.0.0/8 any\nallow any 2.0.0.0/8 any").shadowed());
        // an allow-everything first rule shadows every later rule (indexes 1, 2, 3)
        assertEquals(List.of(1, 2, 3), Firewall.parse(
            "allow any any any\nallow any any 80\ndeny 1.0.0.0/8 any any\ndeny any 9.0.0.0/8 5-6").shadowed());
        // rule 3 sits in the gap left by ports 5-6 (port 7 is uncovered), so only rule 2 is shadowed
        assertEquals(List.of(2), Firewall.parse(
            "allow any 9.0.0.0/8 5-6\nallow any 1.0.0.0/8 any\ndeny any 1.2.0.0/16 80\ndeny any 9.0.0.0/8 5-7").shadowed());
        assertEquals(List.of(), Firewall.parse(RULES).shadowed());
    }

    @Test
    void allowedSourcesLiteral() {
        Firewall fw = Firewall.parse(RULES);
        AddrSet web = fw.allowedSources(Ip4.parse("192.168.1.5"), 80);
        assertEquals(1L << 32, web.size()); // rule 0 allows everyone
        AddrSet ssh = fw.allowedSources(Ip4.parse("192.168.2.5"), 22);
        // 10.0.0.0/8 minus the denied 10.66.0.0/16
        assertEquals((1L << 24) - (1L << 16), ssh.size());
        assertFalse(ssh.contains(Ip4.parse("10.66.0.1")));
        assertTrue(ssh.contains(Ip4.parse("10.65.255.255")));
        assertTrue(ssh.contains(Ip4.parse("10.67.0.0")));
        assertTrue(fw.allowedSources(Ip4.parse("172.16.0.1"), 5).contains(Ip4.parse("10.200.0.1")));
        assertTrue(fw.allowedSources(Ip4.parse("172.16.0.1"), 5).subtract(
            AddrSet.of(Cidr.parse("10.0.0.0/8")).subtract(AddrSet.of(Cidr.parse("10.66.0.0/16")))).isEmpty());
        // the deny-before-allow ordering matters: with no deny first, 10.66/16 would be allowed
        Firewall plain = Firewall.parse("allow 10.0.0.0/8 any any");
        assertEquals(1L << 24, plain.allowedSources(0, 1).size());
    }

    // ---- randomized differential tests against a coordinate-compression oracle ----------------------------

    private static final long SB = Ip4.parse("10.0.0.0");
    private static final long DB = Ip4.parse("192.168.0.0");

    private static String randomAddrSpec(Random r, long base) {
        int k = r.nextInt(6);
        if (k == 0) return "any";
        return Cidr.containing(base + r.nextInt(16), 28 + r.nextInt(5)).toString();
    }

    private static String randomPorts(Random r) {
        int k = r.nextInt(5);
        if (k == 0) return "any";
        int lo = r.nextInt(8);
        if (k == 1) return Integer.toString(lo);
        return lo + "-" + (lo + r.nextInt(8 - lo + 1));
    }

    private static Firewall randomFirewall(Random r) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0, n = 1 + r.nextInt(8); i < n; i++) {
            sb.append(r.nextBoolean() ? "allow " : "deny ").append(randomAddrSpec(r, SB)).append(' ')
                .append(randomAddrSpec(r, DB)).append(' ').append(randomPorts(r)).append('\n');
        }
        return Firewall.parse(sb.toString());
    }

    /** Sorted cell start points of one dimension: every interval start and end+1, plus 0. */
    private static long[] cells(List<long[]> intervals, long max) {
        TreeSet<Long> pts = new TreeSet<>();
        pts.add(0L);
        for (long[] iv : intervals) {
            pts.add(iv[0]);
            if (iv[1] + 1 <= max) pts.add(iv[1] + 1);
        }
        return pts.stream().mapToLong(Long::longValue).toArray();
    }

    @Test
    void shadowedAndAllowedSourcesMatchCellOracle() {
        Random r = new Random(314);
        int shadowedTotal = 0, allowedTotal = 0;
        for (int t = 0; t < 300; t++) {
            Firewall fw = randomFirewall(r);
            List<Firewall.Rule> rules = fw.rules();
            List<long[]> si = new ArrayList<>(), di = new ArrayList<>(), pi = new ArrayList<>();
            for (Firewall.Rule rule : rules) {
                si.add(new long[] {rule.srcLo(), rule.srcHi()});
                di.add(new long[] {rule.dstLo(), rule.dstHi()});
                pi.add(new long[] {rule.portLo(), rule.portHi()});
            }
            long[] sc = cells(si, 0xFFFFFFFFL), dc = cells(di, 0xFFFFFFFFL), pc = cells(pi, 65535);

            // shadowing oracle: a rule is shadowed iff every cell inside its box matches an earlier rule
            List<Integer> expected = new ArrayList<>();
            for (int i = 0; i < rules.size(); i++) {
                boolean covered = true;
                for (long s : sc) for (long d : dc) for (long p : pc) {
                    if (!inBox(rules.get(i), s, d, (int) p)) continue;
                    boolean hit = false;
                    for (int j = 0; j < i; j++) hit |= inBox(rules.get(j), s, d, (int) p);
                    if (!hit) covered = false;
                }
                if (covered) expected.add(i);
            }
            assertEquals(expected, fw.shadowed(), "shadowed for\n" + rules);
            shadowedTotal += expected.size();

            // evaluate oracle on every cell start
            for (long s : sc) for (long d : dc) for (long p : pc) {
                int first = -1;
                for (int j = 0; j < rules.size() && first < 0; j++) if (inBox(rules.get(j), s, d, (int) p)) first = j;
                Firewall.Decision dec = fw.evaluate(new Firewall.Packet(s, d, (int) p));
                assertEquals(first, dec.ruleIndex());
                assertEquals(first >= 0 && rules.get(first).allow(), dec.allow());
            }
            // allowedSources oracle: at every src cell start, and the exact allowed size from cell lengths
            for (long d : dc) for (long p : pc) {
                AddrSet got = fw.allowedSources(d, (int) p);
                long size = 0;
                for (int k = 0; k < sc.length; k++) {
                    long len = (k + 1 < sc.length ? sc[k + 1] : 1L << 32) - sc[k];
                    boolean allowed = fw.evaluate(new Firewall.Packet(sc[k], d, (int) p)).allow();
                    assertEquals(allowed, got.contains(sc[k]));
                    assertEquals(allowed, got.contains(sc[k] + len - 1));
                    if (allowed) size += len;
                }
                assertEquals(size, got.size());
                allowedTotal += size > 0 ? 1 : 0;
            }
        }
        assertTrue(shadowedTotal > 40, "random firewalls should contain shadowed rules: " + shadowedTotal);
        assertTrue(allowedTotal > 200, "random firewalls should allow some traffic: " + allowedTotal);
    }

    private static boolean inBox(Firewall.Rule r, long s, long d, int p) {
        return s >= r.srcLo() && s <= r.srcHi() && d >= r.dstLo() && d <= r.dstHi() && p >= r.portLo() && p <= r.portHi();
    }
}
