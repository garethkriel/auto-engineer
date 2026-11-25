package ae.dns;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class WireTest {
    static final HexFormat HEX = HexFormat.of();

    static Name n(String s) {
        return Name.parse(s);
    }

    static Message query(int id, String name, RType t) {
        return new Message(id, false, false, false, true, false, Rcode.NOERROR, n(name), t, List.of(), List.of(), List.of());
    }

    static Message response(Message q, List<Rr> an, List<Rr> ns, List<Rr> ar) {
        return new Message(q.id(), true, true, false, q.rd(), false, Rcode.NOERROR, q.qname(), q.qtype(), an, ns, ar);
    }

    @Test
    void queryHasLiteralBytes() {
        byte[] b = Wire.encode(query(0x1234, "www.example.com", RType.A));
        // id 1234, flags 0100 (RD), qd=1 an=0 ns=0 ar=0, 3www 7example 3com 0, type 1, class 1
        String expected = "1234" + "0100" + "0001" + "0000" + "0000" + "0000"
                + "03777777" + "076578616d706c65" + "03636f6d" + "00" + "0001" + "0001";
        assertEquals(expected, HEX.formatHex(b));
        assertEquals(12 + 17 + 4, b.length);
    }

    @Test
    void responseUsesCompressionPointerToQuestionName() {
        Message q = query(0x0001, "www.example.com", RType.A);
        Message r = response(q, List.of(new Rr(n("www.example.com"), 300, RData.A.of("192.0.2.1"))), List.of(), List.of());
        byte[] b = Wire.encode(r);
        // header: id 0001, flags 8500 (QR AA RD), 1 1 0 0
        String header = "0001" + "8500" + "0001" + "0001" + "0000" + "0000";
        String question = "03777777" + "076578616d706c65" + "03636f6d" + "00" + "0001" + "0001";
        // answer: pointer c00c (offset 12 = start of question name), type 1, class 1, ttl 300=0x12c, rdlen 4, 192.0.2.1
        String answer = "c00c" + "0001" + "0001" + "0000012c" + "0004" + "c0000201";
        assertEquals(header + question + answer, HEX.formatHex(b));
    }

    @Test
    void compressionReusesLongestKnownSuffixWithLabelsBeforeIt() {
        Message q = query(7, "www.example.com", RType.CNAME);
        Message r = response(q, List.of(new Rr(n("www.example.com"), 60, new RData.Cname(n("mail.example.com")))), List.of(), List.of());
        byte[] b = Wire.encode(r);
        String hex = HEX.formatHex(b);
        // rdata name: 4mail then pointer to "example.com" which starts at offset 12+4 = 16 = 0x10
        assertTrue(hex.endsWith("046d61696c" + "c010"), hex);
        assertEquals(r, Wire.decode(b));
    }

    @Test
    void compressionShrinksRepeatedSuffixes() {
        Message q = query(9, "a.example.com", RType.NS);
        List<Rr> an = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            an.add(new Rr(n("example.com"), 10, new RData.Ns(n("ns" + i + ".example.com"))));
        }
        Message r = response(q, an, List.of(), List.of());
        byte[] compressed = Wire.encode(r);
        int uncompressedNames = 17 + 5 * (13 + 17); // question + 5*(owner 13 bytes + ns name 17 bytes), crude bound
        assertTrue(compressed.length < 12 + uncompressedNames + 5 * 10, "compressed size " + compressed.length);
        assertEquals(r, Wire.decode(compressed));
    }

    @Test
    void roundTripAllTypes() {
        Name z = n("example.org");
        List<Rr> all = List.of(
                new Rr(z, 1, RData.A.of("10.1.2.3")),
                new Rr(z, 2, new RData.Aaaa(0x20010db800000000L, 0x0000000000000001L)),
                new Rr(z, 3, new RData.Ns(n("ns1.example.org"))),
                new Rr(n("w.example.org"), 4, new RData.Cname(n("example.org"))),
                new Rr(z, 5, new RData.Mx(10, n("mail.example.org"))),
                new Rr(z, 6, new RData.Txt(List.of("v=spf1 -all", "", "x".repeat(255)))),
                new Rr(z, 7, new RData.Soa(n("ns1.example.org"), n("hostmaster.example.org"), 4294967295L, 7200, 3600, 1209600, 300)),
                new Rr(Name.ROOT, 0, RData.A.of("255.255.255.255")));
        Message m = new Message(65535, true, true, true, true, true, Rcode.NXDOMAIN, n("Q.Example.org"), RType.AAAA, all, all.subList(0, 3), all.subList(3, 5));
        assertEquals(m, Wire.decode(Wire.encode(m)));
    }

    @Test
    void randomRoundTrip() {
        Random rnd = new Random(42);
        for (int i = 0; i < 300; i++) {
            Message m = randomMessage(rnd);
            byte[] b = Wire.encode(m);
            assertEquals(m, Wire.decode(b), "iteration " + i);
        }
    }

    static Name randomName(Random rnd) {
        int k = rnd.nextInt(4);
        Name n = Name.parse(rnd.nextBoolean() ? "example.com" : "test");
        for (int i = 0; i < k; i++) {
            n = n.child("l" + rnd.nextInt(3));
        }
        return n;
    }

    static RData randomData(Random rnd) {
        return switch (rnd.nextInt(7)) {
            case 0 -> new RData.A(rnd.nextInt());
            case 1 -> new RData.Aaaa(rnd.nextLong(), rnd.nextLong());
            case 2 -> new RData.Ns(randomName(rnd));
            case 3 -> new RData.Cname(randomName(rnd));
            case 4 -> new RData.Mx(rnd.nextInt(65536), randomName(rnd));
            case 5 -> new RData.Txt(List.of("t" + rnd.nextInt(100), "u"));
            default -> new RData.Soa(randomName(rnd), randomName(rnd), rnd.nextInt(Integer.MAX_VALUE), rnd.nextInt(1000), 5, 6, 7);
        };
    }

    static Message randomMessage(Random rnd) {
        List<List<Rr>> sec = new ArrayList<>();
        for (int s = 0; s < 3; s++) {
            List<Rr> l = new ArrayList<>();
            int cnt = rnd.nextInt(4);
            for (int i = 0; i < cnt; i++) {
                l.add(new Rr(randomName(rnd), rnd.nextInt(100000), randomData(rnd)));
            }
            sec.add(l);
        }
        return new Message(rnd.nextInt(65536), rnd.nextBoolean(), rnd.nextBoolean(), rnd.nextBoolean(), rnd.nextBoolean(),
                rnd.nextBoolean(), Rcode.values()[rnd.nextInt(Rcode.values().length)], randomName(rnd),
                RType.values()[rnd.nextInt(RType.values().length)], sec.get(0), sec.get(1), sec.get(2));
    }

    // ---- strict decoding -------------------------------------------------

    static final String HEADER_1Q = "abcd" + "0100" + "0001" + "0000" + "0000" + "0000";

    static byte[] hex(String s) {
        return HEX.parseHex(s);
    }

    @Test
    void controlQueryDecodes() {
        Message m = Wire.decode(hex(HEADER_1Q + "016103636f6d00" + "0001" + "0001"));
        assertEquals(n("a.com"), m.qname());
        assertEquals(RType.A, m.qtype());
        assertEquals(0xabcd, m.id());
        assertTrue(m.rd());
        assertFalse(m.response());
    }

    @Test
    void rejectsSelfPointer() {
        // question name is a pointer to offset 12 = itself
        assertDecodeError(HEADER_1Q + "c00c" + "0001" + "0001", "pointer");
    }

    @Test
    void rejectsForwardPointer() {
        // pointer at 12 points to offset 14 (after itself)
        assertDecodeError(HEADER_1Q + "c00e" + "0001" + "0001", "pointer");
    }

    @Test
    void rejectsPointerLoopThroughALabel() {
        // offset 12: label "a" (01 61), offset 14: pointer to 12 -> infinite label loop
        assertDecodeError(HEADER_1Q + "0161" + "c00c" + "0001" + "0001", "pointer");
    }

    @Test
    void rejectsReservedLabelTypes() {
        assertDecodeError(HEADER_1Q + "4161" + "00" + "0001" + "0001", "label");
        assertDecodeError(HEADER_1Q + "8161" + "00" + "0001" + "0001", "label");
    }

    @Test
    void rejectsTrailingBytes() {
        assertDecodeError(HEADER_1Q + "016103636f6d00" + "0001" + "0001" + "00", "trailing");
    }

    @Test
    void rejectsWrongCounts() {
        // qdcount 0
        assertDecodeError("abcd" + "0100" + "0000" + "0000" + "0000" + "0000", "question");
        // qdcount 2
        assertDecodeError("abcd" + "0100" + "0002" + "0000" + "0000" + "0000" + "016103636f6d0000010001016103636f6d00" + "00010001", "question");
        // ancount 1 but no records follow
        assertDecodeError("abcd" + "8100" + "0001" + "0001" + "0000" + "0000" + "016103636f6d00" + "0001" + "0001", "truncated");
    }

    @Test
    void rejectsReservedFlagsOpcodeClassAndType() {
        String q = "016103636f6d00" + "0001" + "0001";
        assertDecodeError("abcd" + "0140" + "0001" + "0000" + "0000" + "0000" + q, "flags");   // Z bit
        assertDecodeError("abcd" + "0120" + "0001" + "0000" + "0000" + "0000" + q, "flags");   // AD bit
        assertDecodeError("abcd" + "0900" + "0001" + "0000" + "0000" + "0000" + q, "opcode"); // opcode 1
        assertDecodeError("abcd" + "0100" + "0001" + "0000" + "0000" + "0000" + "016103636f6d00" + "0001" + "0003", "class");
        assertDecodeError("abcd" + "0100" + "0001" + "0000" + "0000" + "0000" + "016103636f6d00" + "0063" + "0001", "type");
        assertDecodeError("abcd" + "0106" + "0001" + "0000" + "0000" + "0000" + q, "rcode");   // rcode 6
    }

    @Test
    void rejectsBadRdataLengths() {
        String head = "abcd" + "8100" + "0001" + "0001" + "0000" + "0000" + "016103636f6d00" + "0001" + "0001";
        String owner = "c00c";
        // control: A with rdlength 4
        Message ok = Wire.decode(hex(head + owner + "0001" + "0001" + "0000003c" + "0004" + "01020304"));
        assertEquals(RData.A.of("1.2.3.4"), ok.answers().get(0).data());
        assertDecodeError(head + owner + "0001" + "0001" + "0000003c" + "0003" + "010203", "rdlength");
        assertDecodeError(head + owner + "0001" + "0001" + "0000003c" + "0005" + "0102030405", "rdlength");
        assertDecodeError(head + owner + "001c" + "0001" + "0000003c" + "0004" + "01020304", "rdlength");
        // rdlength larger than the remaining message
        assertDecodeError(head + owner + "0001" + "0001" + "0000003c" + "0004" + "010203", "truncated");
        // CNAME whose name runs past rdlength: rdlength 1 but name is 3 bytes "01 61 00"
        assertDecodeError(head + owner + "0005" + "0001" + "0000003c" + "0001" + "016100", "rdlength");
        // TXT chunk longer than rdlength
        assertDecodeError(head + owner + "0010" + "0001" + "0000003c" + "0002" + "0561", "rdlength");
        // TXT with empty rdata
        assertDecodeError(head + owner + "0010" + "0001" + "0000003c" + "0000", "rdlength");
        // TTL with the high bit set
        assertDecodeError(head + owner + "0001" + "0001" + "80000000" + "0004" + "01020304", "ttl");
    }

    @Test
    void rejectsNameLongerThan255ThroughPointers() {
        // name made of 130 one-byte labels = 261 bytes
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 130; i++) {
            sb.append("0161");
        }
        assertDecodeError(HEADER_1Q + sb + "00" + "0001" + "0001", "name");
    }

    static void assertDecodeError(String hexMessage, String fragment) {
        DecodeException e = assertThrows(DecodeException.class, () -> Wire.decode(hex(hexMessage)), hexMessage);
        assertTrue(e.getMessage().toLowerCase().contains(fragment), "message [" + e.getMessage() + "] should mention " + fragment);
    }

    @Test
    void everyStrictPrefixOfAValidMessageIsRejected() {
        Message q = query(0x4242, "www.example.com", RType.MX);
        Message r = response(q, List.of(new Rr(n("www.example.com"), 30, new RData.Mx(5, n("mx.example.com")))),
                List.of(new Rr(n("example.com"), 30, new RData.Ns(n("ns.example.com")))), List.of());
        byte[] b = Wire.encode(r);
        assertEquals(r, Wire.decode(b));
        for (int len = 0; len < b.length; len++) {
            byte[] cut = java.util.Arrays.copyOf(b, len);
            assertThrows(DecodeException.class, () -> Wire.decode(cut), "prefix length " + len);
        }
    }

    @Test
    void flippingAnyByteGivesDecodeExceptionOrStableMessage() {
        Message q = query(0x0102, "a.example.com", RType.A);
        Message r = response(q,
                List.of(new Rr(n("a.example.com"), 30, new RData.Cname(n("b.example.com"))),
                        new Rr(n("b.example.com"), 30, RData.A.of("203.0.113.9")),
                        new Rr(n("b.example.com"), 30, new RData.Txt(List.of("hi", "there")))),
                List.of(new Rr(n("example.com"), 30, new RData.Soa(n("ns.example.com"), n("h.example.com"), 1, 2, 3, 4, 5))),
                List.of(new Rr(n("ns.example.com"), 30, new RData.Aaaa(1, 2))));
        byte[] b = Wire.encode(r);
        int survived = 0;
        for (int i = 0; i < b.length; i++) {
            for (int bit = 0; bit < 8; bit++) {
                byte[] c = b.clone();
                c[i] ^= (byte) (1 << bit);
                try {
                    Message m = Wire.decode(c);
                    assertEquals(m, Wire.decode(Wire.encode(m)), "byte " + i + " bit " + bit);
                    survived++;
                } catch (DecodeException expected) {
                    // fine
                }
            }
        }
        assertTrue(survived > 0, "some flips (ttl, address, id) must still decode");
        assertTrue(survived < b.length * 8, "some flips must be rejected");
    }
}
