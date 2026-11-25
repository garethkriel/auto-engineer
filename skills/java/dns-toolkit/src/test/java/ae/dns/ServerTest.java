package ae.dns;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ServerTest {
    static Name n(String s) {
        return Name.parse(s);
    }

    static final Server SERVER = new Server(List.of(Zone.parse("example.test", World.EXAMPLE), Zone.parse("corp.test", World.CORP)));

    static Message ask(String name, RType t) {
        return SERVER.handle(Message.query(77, n(name), t));
    }

    @Test
    void answerIsAuthoritativeAndEchoesIdQuestionAndRd() {
        Message r = ask("www.example.test", RType.A);
        assertTrue(r.response());
        assertTrue(r.aa());
        assertTrue(r.rd());
        assertFalse(r.ra());
        assertEquals(77, r.id());
        assertEquals(Rcode.NOERROR, r.rcode());
        assertEquals(n("www.example.test"), r.qname());
        assertEquals(RType.A, r.qtype());
        assertEquals(1, r.answers().size());
        assertTrue(r.authority().isEmpty());
    }

    @Test
    void namesOutsideAllZonesAreRefusedWithoutAa() {
        Message r = ask("www.nowhere.test", RType.A);
        assertEquals(Rcode.REFUSED, r.rcode());
        assertFalse(r.aa());
        assertTrue(r.answers().isEmpty());
    }

    @Test
    void responsesAreFormerr() {
        Message resp = new Message(1, true, false, false, true, false, Rcode.NOERROR, n("a.test"), RType.A, List.of(), List.of(), List.of());
        assertEquals(Rcode.FORMERR, SERVER.handle(resp).rcode());
    }

    @Test
    void nodataAndNxdomainCarryTheApexSoaInAuthority() {
        Message nodata = ask("www.example.test", RType.MX);
        assertEquals(Rcode.NOERROR, nodata.rcode());
        assertTrue(nodata.aa());
        assertEquals(1, nodata.authority().size());
        assertEquals(RType.SOA, nodata.authority().get(0).type());
        assertEquals(n("example.test"), nodata.authority().get(0).name());
        Message nx = ask("ghost.example.test", RType.A);
        assertEquals(Rcode.NXDOMAIN, nx.rcode());
        assertTrue(nx.aa());
        assertEquals(RType.SOA, nx.authority().get(0).type());
        Message nxCorp = ask("ghost.corp.test", RType.A);
        assertEquals(n("corp.test"), nxCorp.authority().get(0).name(), "SOA of the zone that answered");
    }

    @Test
    void inZoneCnameChainIsFollowedAndAcrossServedZonesToo() {
        Message r = ask("ext.example.test", RType.A);
        assertEquals(List.of(RType.CNAME, RType.A), r.answers().stream().map(Rr::type).toList());
        assertEquals(n("www.corp.test"), r.answers().get(1).name());
        // a target we do not serve: only the CNAME is returned
        Message far = ask("far.example.test", RType.A);
        assertEquals(List.of(RType.CNAME), far.answers().stream().map(Rr::type).toList());
        assertEquals(Rcode.NOERROR, far.rcode());
    }

    @Test
    void cnameLoopStopsAtTheFirstRepeatedName() {
        Message r = ask("l1.example.test", RType.A);
        // l1 -> l2 -> l1 (already visited): exactly two CNAME records, no endless chain
        assertEquals(2, r.answers().size(), r.answers().toString());
        assertEquals(n("l1.example.test"), r.answers().get(0).name());
        assertEquals(n("l2.example.test"), r.answers().get(1).name());
    }

    @Test
    void mxAnswerBringsAddressesInAdditional() {
        Message r = ask("mx.example.test", RType.MX);
        assertEquals(1, r.answers().size());
        assertEquals(List.of(new Rr(n("mail.example.test"), 300, RData.A.of("192.0.2.25"))), r.additional());
    }

    @Test
    void referralIsNotAuthoritativeAndCarriesGlue() {
        Server parent = new Server(List.of(Zone.parse("test", World.TEST)));
        Message r = parent.handle(Message.query(5, n("www.example.test"), RType.A));
        assertFalse(r.aa());
        assertEquals(Rcode.NOERROR, r.rcode());
        assertTrue(r.answers().isEmpty());
        assertEquals(List.of(n("ns.example.test"), n("ns2.example.test")),
                r.authority().stream().map(x -> ((RData.Ns) x.data()).host()).toList());
        assertEquals(List.of(RData.A.of("10.0.2.1"), RData.A.of("10.0.2.2")), r.additional().stream().map(Rr::data).toList());
    }
}
