package ae.proto;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class FuzzTest {
    static final MessageType ALL = CodecTest.S.message("All");
    static final MessageType T1 = CodecTest.S.message("Test1");
    static final BigInteger TWO64 = BigInteger.ONE.shiftLeft(64);

    // ---- reference encoder: BigInteger varints, written independently of Varint ----

    static void refVarint(ByteArrayOutputStream o, BigInteger u) {
        BigInteger low = BigInteger.valueOf(0x7f);
        while (u.compareTo(low) > 0) {
            o.write(u.and(low).intValue() | 0x80);
            u = u.shiftRight(7);
        }
        o.write(u.intValue());
    }

    /** two's complement view of a signed value as an unsigned 64-bit number. */
    static BigInteger u64(long v) {
        BigInteger b = BigInteger.valueOf(v);
        return v < 0 ? b.add(TWO64) : b;
    }

    /** zigzag from its definition: non-negative n -> 2n, negative n -> -2n-1. */
    static BigInteger zz(long v) {
        BigInteger b = BigInteger.valueOf(v);
        return v >= 0 ? b.shiftLeft(1) : b.negate().shiftLeft(1).subtract(BigInteger.ONE);
    }

    static void refTag(ByteArrayOutputStream o, int field, int wire) {
        refVarint(o, BigInteger.valueOf(((long) field << 3) | wire));
    }

    static void refLe(ByteArrayOutputStream o, long v, int n) {
        for (int i = 0; i < n; i++) o.write((int) ((v >>> (8 * i)) & 0xff));
    }

    static void refLen(ByteArrayOutputStream o, int field, byte[] payload) {
        refTag(o, field, 2);
        refVarint(o, BigInteger.valueOf(payload.length));
        o.writeBytes(payload);
    }

    static long randLong(Random r) {
        return r.nextLong() >> r.nextInt(64);
    }

    static String randString(Random r) {
        StringBuilder sb = new StringBuilder();
        int n = r.nextInt(6);
        for (int i = 0; i < n; i++) {
            switch (r.nextInt(4)) {
                case 0 -> sb.append((char) ('a' + r.nextInt(26)));
                case 1 -> sb.append((char) (0xa0 + r.nextInt(0x500)));
                case 2 -> sb.append((char) (0x4e00 + r.nextInt(100)));
                default -> sb.appendCodePoint(0x1F600 + r.nextInt(50));
            }
        }
        return sb.toString();
    }

    static boolean pick(Random r) {
        return r.nextInt(3) == 0;
    }

    /** Random All message plus the exact bytes the reference encoder expects (ascending field order). */
    static Msg gen(Random r, ByteArrayOutputStream ref) {
        Msg m = Msg.of(ALL);
        if (pick(r)) {
            int v = (int) randLong(r);
            m = m.set("i32", v);
            refTag(ref, 1, 0);
            refVarint(ref, u64(v)); // int32 is sign-extended to 64 bits
        }
        if (pick(r)) {
            long v = randLong(r);
            m = m.set("i64", v);
            refTag(ref, 2, 0);
            refVarint(ref, u64(v));
        }
        if (pick(r)) {
            long v = r.nextLong() & 0xFFFFFFFFL;
            m = m.set("u32", v);
            refTag(ref, 3, 0);
            refVarint(ref, BigInteger.valueOf(v));
        }
        if (pick(r)) {
            long v = randLong(r);
            m = m.set("u64", v);
            refTag(ref, 4, 0);
            refVarint(ref, u64(v));
        }
        if (pick(r)) {
            int v = (int) randLong(r);
            m = m.set("s32", v);
            refTag(ref, 5, 0);
            refVarint(ref, zz(v));
        }
        if (pick(r)) {
            long v = randLong(r);
            m = m.set("s64", v);
            refTag(ref, 6, 0);
            refVarint(ref, zz(v));
        }
        if (pick(r)) {
            boolean v = r.nextBoolean();
            m = m.set("b", v);
            refTag(ref, 7, 0);
            ref.write(v ? 1 : 0);
        }
        if (pick(r)) {
            long v = r.nextLong() & 0xFFFFFFFFL;
            m = m.set("f32", v);
            refTag(ref, 8, 5);
            refLe(ref, v, 4);
        }
        if (pick(r)) {
            long v = r.nextLong();
            m = m.set("f64", v);
            refTag(ref, 9, 1);
            refLe(ref, v, 8);
        }
        if (pick(r)) {
            int v = r.nextInt();
            m = m.set("sf32", v);
            refTag(ref, 10, 5);
            refLe(ref, v, 4);
        }
        if (pick(r)) {
            long v = r.nextLong();
            m = m.set("sf64", v);
            refTag(ref, 11, 1);
            refLe(ref, v, 8);
        }
        if (pick(r)) {
            float v = Float.intBitsToFloat(r.nextInt());
            if (Float.isNaN(v)) v = 1.5f;
            m = m.set("fl", v);
            refTag(ref, 12, 5);
            refLe(ref, Float.floatToIntBits(v), 4);
        }
        if (pick(r)) {
            double v = r.nextGaussian() * 1e6;
            m = m.set("db", v);
            refTag(ref, 13, 1);
            refLe(ref, Double.doubleToLongBits(v), 8);
        }
        if (pick(r)) {
            String v = randString(r);
            m = m.set("s", v);
            refLen(ref, 14, v.getBytes(StandardCharsets.UTF_8));
        }
        if (pick(r)) {
            byte[] v = new byte[r.nextInt(5)];
            r.nextBytes(v);
            m = m.set("by", Bytes.of(v));
            refLen(ref, 15, v);
        }
        if (pick(r)) {
            int a = (int) randLong(r);
            m = m.set("sub", Msg.of(T1).set("a", a));
            ByteArrayOutputStream inner = new ByteArrayOutputStream();
            refTag(inner, 1, 0);
            refVarint(inner, u64(a));
            refLen(ref, 16, inner.toByteArray());
        }
        int n = r.nextInt(4);
        if (n > 0) { // packed
            ByteArrayOutputStream inner = new ByteArrayOutputStream();
            for (int i = 0; i < n; i++) {
                int v = (int) randLong(r);
                m = m.add("ri", v);
                refVarint(inner, u64(v));
            }
            refLen(ref, 17, inner.toByteArray());
        }
        n = r.nextInt(3);
        for (int i = 0; i < n; i++) { // unpacked, one field per element
            String v = randString(r);
            m = m.add("rs", v);
            refLen(ref, 18, v.getBytes(StandardCharsets.UTF_8));
        }
        n = r.nextInt(3);
        for (int i = 0; i < n; i++) {
            ByteArrayOutputStream inner = new ByteArrayOutputStream();
            if (r.nextBoolean()) {
                int a = r.nextInt();
                m = m.add("rm", Msg.of(T1).set("a", a));
                refTag(inner, 1, 0);
                refVarint(inner, u64(a));
            } else {
                m = m.add("rm", Msg.of(T1));
            }
            refLen(ref, 19, inner.toByteArray());
        }
        n = r.nextInt(3);
        if (n > 0) {
            ByteArrayOutputStream inner = new ByteArrayOutputStream();
            for (int i = 0; i < n; i++) {
                double v = r.nextDouble();
                m = m.add("rd", v);
                refLe(inner, Double.doubleToLongBits(v), 8);
            }
            refLen(ref, 20, inner.toByteArray());
        }
        n = r.nextInt(4);
        if (n > 0) {
            ByteArrayOutputStream inner = new ByteArrayOutputStream();
            for (int i = 0; i < n; i++) {
                boolean v = r.nextBoolean();
                m = m.add("rb", v);
                inner.write(v ? 1 : 0);
            }
            refLen(ref, 21, inner.toByteArray());
        }
        return m;
    }

    @Test
    void encoderMatchesIndependentReferenceAndDecodeInvertsIt() {
        Random r = new Random(2026);
        int nonEmpty = 0;
        for (int i = 0; i < 3000; i++) {
            ByteArrayOutputStream ref = new ByteArrayOutputStream();
            Msg m = gen(r, ref);
            byte[] bytes = Codec.encode(m);
            assertArrayEquals(ref.toByteArray(), bytes, "case " + i + " " + m);
            Msg back = Codec.decode(ALL, bytes);
            assertEquals(m, back, "case " + i);
            assertArrayEquals(bytes, Codec.encode(back), "canonical re-encode " + i);
            if (bytes.length > 0) nonEmpty++;
        }
        assertTrue(nonEmpty > 2900, "generator produced mostly empty messages: " + nonEmpty);
    }

    @Test
    void everyTruncationAndEveryByteFlipEitherDecodesOrThrowsDecodeException() {
        Random r = new Random(99);
        int decodedAfterFlip = 0, rejectedAfterFlip = 0, rejectedPrefix = 0;
        for (int i = 0; i < 150; i++) {
            Msg m = gen(r, new ByteArrayOutputStream());
            byte[] bytes = Codec.encode(m);
            for (int cut = 0; cut < bytes.length; cut++) {
                byte[] prefix = java.util.Arrays.copyOf(bytes, cut);
                try {
                    Msg got = Codec.decode(ALL, prefix);
                    assertEquals(got, Codec.decode(ALL, Codec.encode(got)), "stable re-decode");
                } catch (DecodeException e) {
                    rejectedPrefix++;
                    assertTrue(e.getOffset() >= 0 && e.getOffset() <= cut, e.getOffset() + " > " + cut);
                }
            }
            for (int pos = 0; pos < bytes.length; pos++) {
                byte[] bad = bytes.clone();
                bad[pos] ^= (byte) (1 << r.nextInt(8));
                try {
                    Msg got = Codec.decode(ALL, bad);
                    decodedAfterFlip++;
                    assertEquals(got, Codec.decode(ALL, Codec.encode(got)), "stable re-decode after flip");
                } catch (DecodeException e) {
                    rejectedAfterFlip++;
                    assertTrue(e.getOffset() >= 0 && e.getOffset() <= bad.length);
                }
            }
        }
        // both outcomes must occur, otherwise the fuzz is not exercising the decoder
        assertTrue(rejectedPrefix > 100, "prefix rejections " + rejectedPrefix);
        assertTrue(decodedAfterFlip > 100, "flips that still decode " + decodedAfterFlip);
        assertTrue(rejectedAfterFlip > 100, "flips that are rejected " + rejectedAfterFlip);
    }

    @Test
    void randomGarbageNeverThrowsAnythingButDecodeException() {
        Random r = new Random(5);
        List<Integer> sizes = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            byte[] junk = new byte[r.nextInt(40)];
            r.nextBytes(junk);
            try {
                Codec.decode(ALL, junk);
                sizes.add(junk.length);
            } catch (DecodeException expected) {
                assertTrue(expected.getOffset() >= 0);
            }
        }
        assertFalse(sizes.isEmpty(), "some garbage should decode (e.g. empty input)");
    }
}
