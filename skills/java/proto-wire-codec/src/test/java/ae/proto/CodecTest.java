package ae.proto;

import static ae.proto.VarintTest.hex;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

class CodecTest {
    static final Schema S = Schema.parse("""
            message Test1 { int32 a = 1; }
            message Test2 { string b = 2; }
            message Test3 { Test1 c = 3; }
            message Test4 { repeated int32 d = 4; }
            message R { R child = 1; }
            message All {
              int32 i32 = 1; int64 i64 = 2; uint32 u32 = 3; uint64 u64 = 4;
              sint32 s32 = 5; sint64 s64 = 6; bool b = 7; fixed32 f32 = 8; fixed64 f64 = 9;
              sfixed32 sf32 = 10; sfixed64 sf64 = 11; float fl = 12; double db = 13;
              string s = 14; bytes by = 15; Test1 sub = 16;
              repeated int32 ri = 17; repeated string rs = 18; repeated Test1 rm = 19;
              repeated double rd = 20; repeated bool rb = 21;
            }
            """);

    static MessageType t(String n) {
        return S.message(n);
    }

    static void roundTrip(Msg m, String hexBytes) {
        byte[] expected = hex(hexBytes);
        assertArrayEquals(expected, Codec.encode(m), "encode of " + m);
        assertEquals(m, Codec.decode(m.type(), expected), "decode of " + hexBytes);
    }

    // ---- literal vectors (public protobuf encoding guide) ----

    @Test
    void guideVectors() {
        roundTrip(Msg.of(t("Test1")).set("a", 150), "08 96 01");
        roundTrip(Msg.of(t("Test2")).set("b", "testing"), "12 07 74 65 73 74 69 6e 67");
        roundTrip(Msg.of(t("Test3")).set("c", Msg.of(t("Test1")).set("a", 150)), "1a 03 08 96 01");
        roundTrip(Msg.of(t("Test4")).add("d", 3).add("d", 270).add("d", 86942), "22 06 03 8e 02 9e a7 05");
    }

    @Test
    void explicitDefaultIsStillEncoded() {
        roundTrip(Msg.of(t("Test1")).set("a", 0), "08 00");
        roundTrip(Msg.of(t("Test1")), "");
    }

    @Test
    void scalarLiterals() {
        MessageType all = t("All");
        roundTrip(Msg.of(all).set("i32", -1), "08 ff ff ff ff ff ff ff ff ff 01");
        roundTrip(Msg.of(all).set("i64", Long.MIN_VALUE), "10 80 80 80 80 80 80 80 80 80 01");
        roundTrip(Msg.of(all).set("u32", 4294967295L), "18 ff ff ff ff 0f");
        roundTrip(Msg.of(all).set("u64", -1L), "20 ff ff ff ff ff ff ff ff ff 01");
        roundTrip(Msg.of(all).set("s32", -1), "28 01");
        roundTrip(Msg.of(all).set("s32", Integer.MIN_VALUE), "28 ff ff ff ff 0f");
        roundTrip(Msg.of(all).set("s64", -2L), "30 03");
        roundTrip(Msg.of(all).set("b", true), "38 01");
        roundTrip(Msg.of(all).set("b", false), "38 00");
        roundTrip(Msg.of(all).set("f32", 0x12345678L), "45 78 56 34 12");
        roundTrip(Msg.of(all).set("f64", 0x0102030405060708L), "49 08 07 06 05 04 03 02 01");
        roundTrip(Msg.of(all).set("sf32", -2), "55 fe ff ff ff");
        roundTrip(Msg.of(all).set("sf64", -2L), "59 fe ff ff ff ff ff ff ff");
        roundTrip(Msg.of(all).set("fl", 1.0f), "65 00 00 80 3f");
        roundTrip(Msg.of(all).set("db", 1.0), "69 00 00 00 00 00 00 f0 3f");
        roundTrip(Msg.of(all).set("by", Bytes.of(new byte[] {(byte) 0xde, (byte) 0xad})), "7a 02 de ad");
        roundTrip(Msg.of(all).set("s", "é€😀"), "72 09 c3 a9 e2 82 ac f0 9f 98 80");
        roundTrip(Msg.of(all).set("sub", Msg.of(t("Test1")).set("a", 1)), "82 01 02 08 01");
    }

    @Test
    void fieldsAreEmittedInNumberOrderRegardlessOfSetOrder() {
        MessageType all = t("All");
        Msg m = Msg.of(all).set("b", true).set("i32", 1);
        assertArrayEquals(hex("08 01 38 01"), Codec.encode(m));
    }

    @Test
    void int32DecodeTruncatesWideVarintLikeProtobuf() {
        Msg m = Codec.decode(t("All"), hex("08 ff ff ff ff 0f"));
        assertEquals(-1, m.get("i32")); // 0xFFFFFFFF as int32
        Msg n = Codec.decode(t("All"), hex("08 80 80 80 80 10")); // 2^32 -> 0
        assertEquals(0, n.get("i32"));
    }

    // ---- repeated ----

    @Test
    void repeatedPackedAndUnpacked() {
        MessageType all = t("All");
        assertArrayEquals(new byte[0], Codec.encode(Msg.of(all)));
        roundTrip(Msg.of(all).add("ri", 1), "8a 01 01 01");
        roundTrip(Msg.of(all).add("rs", "a").add("rs", "bc"), "92 01 01 61 92 01 02 62 63");
        roundTrip(Msg.of(all).add("rm", Msg.of(t("Test1")).set("a", 2)).add("rm", Msg.of(t("Test1"))),
                "9a 01 02 08 02 9a 01 00");
        roundTrip(Msg.of(all).add("rb", true).add("rb", false), "aa 01 02 01 00");
        roundTrip(Msg.of(all).add("rd", 1.0).add("rd", 2.0),
                "a2 01 10 00 00 00 00 00 00 f0 3f 00 00 00 00 00 00 00 40");
        Msg unpacked = Codec.decode(all, hex("88 01 05 88 01 06"));
        assertEquals(List.of(5, 6), unpacked.list("ri"));
        Msg mixed = Codec.decode(all, hex("88 01 05 8a 01 02 06 07 88 01 08"));
        assertEquals(List.of(5, 6, 7, 8), mixed.list("ri"));
    }

    @Test
    void emptyPackedPayloadIsAcceptedAndContributesNothing() {
        Msg m = Codec.decode(t("All"), hex("8a 01 00"));
        assertTrue(m.list("ri").isEmpty());
        assertFalse(m.has("ri"));
    }

    @Test
    void packedPayloadMustBeWholeElements() {
        // 7 bytes cannot hold doubles
        DecodeException e = assertThrows(DecodeException.class,
                () -> Codec.decode(t("All"), hex("a2 01 07 00 00 00 00 00 00 f0")));
        assertTrue(e.getMessage().contains("packed"), e.getMessage());
        // varint cut by the end of the packed payload even though bytes follow
        assertThrows(DecodeException.class, () -> Codec.decode(t("All"), hex("8a 01 02 80 80 05")));
    }

    // ---- merge semantics ----

    @Test
    void singularLastWinsAndMessagesAreNotMerged() {
        assertEquals(2, Codec.decode(t("Test1"), hex("08 01 08 02")).get("a"));
        Msg m = Codec.decode(t("Test3"), hex("1a 03 08 96 01 1a 00"));
        assertEquals(Msg.of(t("Test1")), m.get("c"));
        assertFalse(((Msg) m.get("c")).has("a"));
    }

    // ---- unknown fields ----

    @Test
    void unknownFieldsAreReemittedAfterKnownOnesInReadOrder() {
        // 10 05 (field 2 varint) before known a; 1a 03 61 62 63 (field 3 len) ; 25 01 00 00 00 (field 4 fixed32)
        byte[] in = hex("10 05 08 96 01 1a 03 61 62 63 25 01 00 00 00 31 01 02 03 04 05 06 07 08");
        Msg m = Codec.decode(t("Test1"), in);
        assertEquals(150, m.get("a"));
        assertEquals(4, m.unknownCount());
        assertArrayEquals(hex("08 96 01 10 05 1a 03 61 62 63 25 01 00 00 00 31 01 02 03 04 05 06 07 08"),
                Codec.encode(m));
    }

    @Test
    void unknownFieldsKeepPaddedVarintsByteExactly() {
        byte[] in = hex("10 85 80 00"); // field 2 = 5, padded
        assertArrayEquals(in, Codec.encode(Codec.decode(t("Test1"), in)));
    }

    @Test
    void unknownFieldsParticipateInEquality() {
        Msg a = Codec.decode(t("Test1"), hex("08 01"));
        Msg b = Codec.decode(t("Test1"), hex("08 01 10 05"));
        assertNotEquals(a, b);
        assertEquals(b, Codec.decode(t("Test1"), hex("10 05 08 01")));
        assertEquals(b.hashCode(), Codec.decode(t("Test1"), hex("10 05 08 01")).hashCode());
    }

    @Test
    void wireTypeMismatchOnKnownFieldBecomesUnknown() {
        byte[] in = hex("0a 01 41"); // field 1 as length-delimited, schema says varint
        Msg m = Codec.decode(t("Test1"), in);
        assertFalse(m.has("a"));
        assertEquals(1, m.unknownCount());
        assertArrayEquals(in, Codec.encode(m));
        // control: same field number with the right wire type is known
        assertTrue(Codec.decode(t("Test1"), hex("08 41")).has("a"));
        // a string field sent as varint, a varint field sent as fixed32
        assertEquals(1, Codec.decode(t("Test2"), hex("10 05")).unknownCount());
        assertEquals(1, Codec.decode(t("Test1"), hex("0d 01 00 00 00")).unknownCount());
    }

    @Test
    void largestFieldNumberRoundTripsAsUnknown() {
        byte[] in = hex("f8 ff ff ff 0f 01"); // field 536870911, wire 0, value 1
        Msg m = Codec.decode(t("Test1"), in);
        assertEquals(1, m.unknownCount());
        assertArrayEquals(in, Codec.encode(m));
    }

    // ---- strict errors ----

    private static DecodeException fail(String schemaMsg, String h) {
        return assertThrows(DecodeException.class, () -> Codec.decode(t(schemaMsg), hex(h)), h);
    }

    @Test
    void truncationsAreReportedWithOffsets() {
        assertTrue(fail("Test1", "08 96").getMessage().contains("truncated"));
        DecodeException e = fail("Test2", "12 07 74");
        assertTrue(e.getMessage().contains("length"), e.getMessage());
        assertEquals(1, e.getOffset());
        assertTrue(fail("All", "45 01 02").getMessage().contains("truncated"));
        assertTrue(fail("All", "49 01 02 03 04 05 06 07").getMessage().contains("truncated"));
        assertTrue(fail("Test1", "12").getMessage().contains("truncated")); // tag ok, length missing
    }

    @Test
    void hugeLengthIsRejectedWithoutAllocation() {
        assertTrue(fail("Test2", "12 ff ff ff ff ff ff ff ff ff 01").getMessage().contains("length"));
        assertTrue(fail("Test2", "12 ff ff ff ff 0f 41").getMessage().contains("length")); // 4 GiB-1
    }

    @Test
    void invalidTags() {
        assertTrue(fail("Test1", "00 01").getMessage().contains("field number"));
        assertTrue(fail("Test1", "0b").getMessage().contains("wire type")); // start group
        assertTrue(fail("Test1", "0c").getMessage().contains("wire type")); // end group
        assertTrue(fail("Test1", "0e").getMessage().contains("wire type"));
        assertTrue(fail("Test1", "0f").getMessage().contains("wire type"));
        assertTrue(fail("Test1", "80 80 80 80 10 01").getMessage().contains("tag")); // 2^32
        // control: largest legal tag value 0xFFFFFFFF would be field 536870911 wire 7 -> wire error, not tag error
        assertTrue(fail("Test1", "ff ff ff ff 0f").getMessage().contains("wire type"));
    }

    @Test
    void invalidUtf8() {
        assertTrue(fail("Test2", "12 01 ff").getMessage().contains("UTF-8"));
        assertTrue(fail("Test2", "12 02 c3 28").getMessage().contains("UTF-8"));
        assertTrue(fail("Test2", "12 03 ed a0 80").getMessage().contains("UTF-8")); // encoded surrogate
        assertEquals("", Codec.decode(t("Test2"), hex("12 00")).get("b"));
    }

    private static byte[] nest(int levels) {
        byte[] cur = new byte[0];
        for (int i = 0; i < levels; i++) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            o.write(0x0a);
            o.writeBytes(Varint.encode(cur.length));
            o.writeBytes(cur);
            cur = o.toByteArray();
        }
        return cur;
    }

    @Test
    void nestingDepthLimitIs32() {
        byte[] ok = nest(32);
        Msg m = Codec.decode(t("R"), ok);
        int depth = 0;
        while (m.has("child")) {
            m = (Msg) m.get("child");
            depth++;
        }
        assertEquals(32, depth);
        assertArrayEquals(ok, Codec.encode(Codec.decode(t("R"), ok)));
        DecodeException e = assertThrows(DecodeException.class, () -> Codec.decode(t("R"), nest(33)));
        assertTrue(e.getMessage().contains("depth"), e.getMessage());
    }

    @Test
    void errorInsideNestedMessageReportsAbsoluteOffset() {
        // Test3.c has length 3 but its payload "08 96" is cut inside the nested varint
        DecodeException e = assertThrows(DecodeException.class,
                () -> Codec.decode(t("Test3"), hex("1a 02 08 96")));
        assertEquals(3, e.getOffset()); // the byte 0x96 at index 3 wants a continuation
    }

    // ---- Msg API ----

    @Test
    void valueValidation() {
        MessageType all = t("All");
        Msg m = Msg.of(all);
        assertThrows(IllegalArgumentException.class, () -> m.set("i32", "x"));
        assertThrows(IllegalArgumentException.class, () -> m.set("i32", 1L));
        assertThrows(IllegalArgumentException.class, () -> m.set("u32", -1L));
        assertThrows(IllegalArgumentException.class, () -> m.set("u32", 4294967296L));
        assertThrows(IllegalArgumentException.class, () -> m.set("f32", -1L));
        assertThrows(IllegalArgumentException.class, () -> m.set("s", "bad\ud800"));
        assertThrows(IllegalArgumentException.class, () -> m.set("sub", Msg.of(all)));
        assertThrows(IllegalArgumentException.class, () -> m.set("ri", 1));
        assertThrows(IllegalArgumentException.class, () -> m.add("i32", 1));
        assertThrows(IllegalArgumentException.class, () -> m.set("nope", 1));
        assertThrows(NullPointerException.class, () -> m.set("s", null));
        // boundaries that are legal
        m.set("u32", 0L).set("u32", 4294967295L).set("u64", -1L).set("s", "ok😀");
    }

    @Test
    void msgIsImmutable() {
        Msg base = Msg.of(t("All")).add("ri", 1);
        Msg more = base.add("ri", 2);
        assertEquals(List.of(1), base.list("ri"));
        assertEquals(List.of(1, 2), more.list("ri"));
        assertThrows(UnsupportedOperationException.class, () -> more.list("ri").add(3));
        byte[] raw = {1, 2};
        Bytes b = Bytes.of(raw);
        raw[0] = 9;
        assertEquals(1, b.bytes()[0]);
        b.bytes()[1] = 9;
        assertEquals(2, b.bytes()[1]);
        assertEquals(Bytes.of(new byte[] {1, 2}), b);
        assertNotEquals(Bytes.of(new byte[] {1, 3}), b);
    }

    @Test
    void toStringIsDeterministic() {
        Msg m = Msg.of(t("All")).set("s", "testing").set("i32", 150).add("ri", 1).add("ri", 2)
                .set("sub", Msg.of(t("Test1")).set("a", 3));
        assertEquals("All{i32=150, s=\"testing\", sub=Test1{a=3}, ri=[1, 2]}", m.toString());
        assertEquals("Test1{}", Msg.of(t("Test1")).toString());
    }

    @Test
    void floatAndDoubleSpecialValuesRoundTrip() {
        MessageType all = t("All");
        for (double d : new double[] {0.0, -0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.MIN_VALUE}) {
            Msg m = Msg.of(all).set("db", d);
            assertEquals(m, Codec.decode(all, Codec.encode(m)));
        }
        Msg neg = Codec.decode(all, Codec.encode(Msg.of(all).set("fl", -0.0f)));
        assertEquals(Float.floatToRawIntBits(-0.0f), Float.floatToRawIntBits((Float) neg.get("fl")));
    }
}
