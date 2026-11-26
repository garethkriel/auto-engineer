package ae.utf;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class Utf8Test {
    private static byte[] b(int... v) {
        byte[] r = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            r[i] = (byte) v[i];
        }
        return r;
    }

    private static int[] cps(int... v) {
        return v;
    }

    @Test
    void encodesBoundaryScalarsToPublishedBytes() {
        assertArrayEquals(b(0x00), Utf8.encode(cps(0)));
        assertArrayEquals(b(0x7F), Utf8.encode(cps(0x7F)));
        assertArrayEquals(b(0xC2, 0x80), Utf8.encode(cps(0x80)));
        assertArrayEquals(b(0xDF, 0xBF), Utf8.encode(cps(0x7FF)));
        assertArrayEquals(b(0xE0, 0xA0, 0x80), Utf8.encode(cps(0x800)));
        assertArrayEquals(b(0xE2, 0x82, 0xAC), Utf8.encode(cps(0x20AC)));
        assertArrayEquals(b(0xED, 0x9F, 0xBF), Utf8.encode(cps(0xD7FF)));
        assertArrayEquals(b(0xEE, 0x80, 0x80), Utf8.encode(cps(0xE000)));
        assertArrayEquals(b(0xEF, 0xBF, 0xBF), Utf8.encode(cps(0xFFFF)));
        assertArrayEquals(b(0xF0, 0x90, 0x80, 0x80), Utf8.encode(cps(0x10000)));
        assertArrayEquals(b(0xF0, 0x9F, 0x98, 0x80), Utf8.encode(cps(0x1F600)));
        assertArrayEquals(b(0xF4, 0x8F, 0xBF, 0xBF), Utf8.encode(cps(0x10FFFF)));
    }

    @Test
    void encodeRejectsNonScalarsWithIndex() {
        for (int bad : new int[] {0xD800, 0xDBFF, 0xDC00, 0xDFFF, 0x110000, -1, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            CodecException e = assertThrows(CodecException.class, () -> Utf8.encode(cps(0x41, 0x42, bad)));
            assertEquals(2, e.offset(), "value " + bad);
        }
    }

    @Test
    void strictDecodeReportsStartOfFirstBadSequence() {
        // {bytes, expected offset}; each row differs from a valid neighbour by one rule.
        int[][] rows = {
            {0, 0xC0, 0x80},            // overlong 2-byte
            {0, 0xC1, 0xBF},            // overlong 2-byte (max)
            {0, 0xE0, 0x9F, 0xBF},      // overlong 3-byte
            {0, 0xED, 0xA0, 0x80},      // surrogate D800
            {0, 0xED, 0xBF, 0xBF},      // surrogate DFFF
            {0, 0xF0, 0x8F, 0xBF, 0xBF}, // overlong 4-byte
            {0, 0xF4, 0x90, 0x80, 0x80}, // > 10FFFF
            {0, 0xF5, 0x80, 0x80, 0x80},
            {0, 0x80},                  // lone continuation
            {0, 0xFF},
            {1, 0x41, 0xE2, 0x82},      // truncated at end: start of the E2
            {2, 0x41, 0x42, 0xE2, 0x41}, // broken by ASCII
            {3, 0x41, 0x42, 0x43, 0xF0, 0x9F, 0x98}, // truncated 4-byte
        };
        for (int[] row : rows) {
            int expected = row[0];
            int[] data = java.util.Arrays.copyOfRange(row, 1, row.length);
            byte[] bytes = new byte[data.length];
            for (int i = 0; i < data.length; i++) {
                bytes[i] = (byte) data[i];
            }
            CodecException e = assertThrows(CodecException.class, () -> Utf8.decodeStrict(bytes),
                java.util.Arrays.toString(data));
            assertEquals(expected, e.offset(), java.util.Arrays.toString(data));
        }
    }

    @Test
    void controlsDifferingByOneRuleDecode() {
        assertArrayEquals(cps(0x80), Utf8.decodeStrict(b(0xC2, 0x80)));
        assertArrayEquals(cps(0x800), Utf8.decodeStrict(b(0xE0, 0xA0, 0x80)));
        assertArrayEquals(cps(0xD7FF), Utf8.decodeStrict(b(0xED, 0x9F, 0xBF)));
        assertArrayEquals(cps(0x10000), Utf8.decodeStrict(b(0xF0, 0x90, 0x80, 0x80)));
        assertArrayEquals(cps(0x10FFFF), Utf8.decodeStrict(b(0xF4, 0x8F, 0xBF, 0xBF)));
        assertArrayEquals(cps(0xFFFD), Utf8.decodeStrict(b(0xEF, 0xBF, 0xBD)));
    }

    @Test
    void lossyUsesMaximalSubpartReplacement() {
        int f = 0xFFFD;
        assertArrayEquals(cps(f, f, f, f), Utf8.decodeLossy(b(0xF0, 0x80, 0x80, 0x80)));
        assertArrayEquals(cps(f, f, f), Utf8.decodeLossy(b(0xED, 0xA0, 0x80)));
        assertArrayEquals(cps(f, 0x41), Utf8.decodeLossy(b(0xE2, 0x82, 0x41)));
        assertArrayEquals(cps(f), Utf8.decodeLossy(b(0xE2, 0x82)));
        assertArrayEquals(cps(f), Utf8.decodeLossy(b(0xF0, 0x9F, 0x98)));
        assertArrayEquals(cps(f, f), Utf8.decodeLossy(b(0xC0, 0x80)));
        assertArrayEquals(cps(f, f), Utf8.decodeLossy(b(0xF4, 0x90)));
        assertArrayEquals(cps(0x41, f), Utf8.decodeLossy(b(0x41, 0xE2)));
        assertArrayEquals(cps(f, 0x20AC), Utf8.decodeLossy(b(0xE2, 0xE2, 0x82, 0xAC)));
        assertArrayEquals(cps(), Utf8.decodeLossy(b()));
    }

    private static final int[] ALPHABET = {0x00, 0x41, 0x7F, 0x80, 0x8F, 0x90, 0x9F, 0xA0, 0xBD, 0xBF, 0xC0, 0xC1, 0xC2,
        0xDF, 0xE0, 0xE1, 0xEC, 0xED, 0xEE, 0xEF, 0xF0, 0xF1, 0xF3, 0xF4, 0xF5, 0xFF};

    /** Every sequence of length 0..4 over the boundary alphabet, compared with the JDK in both modes. */
    @Test
    void exhaustiveBoundaryAlphabetAgreesWithJdk() throws CharacterCodingException {
        int count = 0;
        List<byte[]> level = new ArrayList<>();
        level.add(new byte[0]);
        for (int len = 0; len <= 4; len++) {
            List<byte[]> next = new ArrayList<>();
            for (byte[] s : level) {
                compareWithJdk(s);
                count++;
                if (len < 4) {
                    for (int a : ALPHABET) {
                        byte[] t = java.util.Arrays.copyOf(s, s.length + 1);
                        t[s.length] = (byte) a;
                        next.add(t);
                    }
                }
            }
            level = next;
        }
        int n = ALPHABET.length;
        assertEquals(1 + n + n * n + n * n * n + n * n * n * n, count);
    }

    private static void compareWithJdk(byte[] s) {
        String jdkLossy = new String(s, StandardCharsets.UTF_8);
        int[] mine = Utf8.decodeLossy(s);
        // Known oracle divergence: the JDK folds "ED A0..BF" into ONE replacement, WHATWG (and this decoder, see
        // lossyUsesMaximalSubpartReplacement) emits two. Skip only the lossy comparison for those inputs.
        if (!hasEdFollowedByHighContinuation(s)) {
            assertEquals(jdkLossy, new String(mine, 0, mine.length), java.util.Arrays.toString(s));
        }
        boolean jdkValid;
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(s));
            jdkValid = true;
        } catch (CharacterCodingException e) {
            jdkValid = false;
        }
        if (jdkValid) {
            assertArrayEquals(mine, Utf8.decodeStrict(s));
        } else {
            assertThrows(CodecException.class, () -> Utf8.decodeStrict(s), java.util.Arrays.toString(s));
        }
    }

    private static boolean hasEdFollowedByHighContinuation(byte[] s) {
        for (int i = 0; i + 1 < s.length; i++) {
            if ((s[i] & 0xFF) == 0xED && (s[i + 1] & 0xFF) >= 0xA0 && (s[i + 1] & 0xFF) <= 0xBF) {
                return true;
            }
        }
        return false;
    }

    private static int[] randomScalars(Random r, int n) {
        int[] c = new int[n];
        for (int i = 0; i < n; i++) {
            int kind = r.nextInt(4);
            int v;
            switch (kind) {
                case 0 -> v = r.nextInt(0x80);
                case 1 -> v = 0x80 + r.nextInt(0x780);
                case 2 -> v = 0x800 + r.nextInt(0xF800);
                default -> v = 0x10000 + r.nextInt(0x100000);
            }
            if (v >= 0xD800 && v <= 0xDFFF) {
                v = 0x20AC;
            }
            c[i] = v;
        }
        return c;
    }

    @Test
    void randomRoundTripMatchesJdkEncoder() {
        Random r = new Random(7);
        for (int iter = 0; iter < 500; iter++) {
            int[] c = randomScalars(r, r.nextInt(40));
            byte[] mine = Utf8.encode(c);
            assertArrayEquals(new String(c, 0, c.length).getBytes(StandardCharsets.UTF_8), mine);
            assertArrayEquals(c, Utf8.decodeStrict(mine));
        }
    }

    private static byte[] noisy(Random r) {
        byte[] valid = Utf8.encode(randomScalars(r, r.nextInt(12)));
        byte[] out = valid.clone();
        int flips = r.nextInt(4);
        for (int f = 0; f < flips && out.length > 0; f++) {
            out[r.nextInt(out.length)] = (byte) ALPHABET[r.nextInt(ALPHABET.length)];
        }
        return out;
    }

    @Test
    void chunkingNeverChangesTheLossyResult() {
        Random r = new Random(11);
        for (int iter = 0; iter < 3000; iter++) {
            byte[] data = noisy(r);
            int[] whole = Utf8.decodeLossy(data);
            Utf8Decoder d = Utf8Decoder.lossy();
            int p = 0;
            while (p < data.length) {
                int len = r.nextInt(Math.min(4, data.length - p) + 1); // includes empty chunks
                d.feed(data, p, len);
                p += len;
                if (len == 0 && r.nextBoolean()) {
                    d.feed(data, p, 1);
                    p++;
                }
            }
            assertArrayEquals(whole, d.finish(), java.util.Arrays.toString(data));
        }
    }

    @Test
    void chunkedStrictReportsTheSameAbsoluteOffset() {
        Random r = new Random(13);
        int errors = 0;
        for (int iter = 0; iter < 3000; iter++) {
            byte[] data = noisy(r);
            long expectedOffset = -1;
            int[] expected = null;
            try {
                expected = Utf8.decodeStrict(data);
            } catch (CodecException e) {
                expectedOffset = e.offset();
                errors++;
            }
            Utf8Decoder d = Utf8Decoder.strict();
            long gotOffset = -1;
            int[] got = null;
            try {
                int p = 0;
                while (p < data.length) {
                    int len = 1 + r.nextInt(3);
                    len = Math.min(len, data.length - p);
                    d.feed(data, p, len);
                    p += len;
                }
                got = d.finish();
            } catch (CodecException e) {
                gotOffset = e.offset();
            }
            assertEquals(expectedOffset, gotOffset, java.util.Arrays.toString(data));
            if (expected != null) {
                assertArrayEquals(expected, got);
            }
        }
        assertTrue(errors > 300 && errors < 2900, "generator mix: " + errors + " erroneous of 3000");
    }

    @Test
    void finishResetsTheDecoder() {
        Utf8Decoder d = Utf8Decoder.lossy();
        d.feed(b(0xE2, 0x82)); // dangling
        assertArrayEquals(cps(0xFFFD), d.finish());
        d.feed(b(0xAC)); // must be a fresh lone continuation, not the tail of the old sequence
        assertArrayEquals(cps(0xFFFD), d.finish());
        d.feed(b(0xE2, 0x82, 0xAC));
        assertArrayEquals(cps(0x20AC), d.finish());
    }

    @Test
    void strictOffsetsRestartAfterFinish() {
        Utf8Decoder d = Utf8Decoder.strict();
        d.feed(b(0x61, 0x62, 0x63));
        assertArrayEquals(cps(0x61, 0x62, 0x63), d.finish());
        CodecException e = assertThrows(CodecException.class, () -> d.feed(b(0x80)));
        assertEquals(0, e.offset());
    }

    @Test
    void feedValidatesRange() {
        Utf8Decoder d = Utf8Decoder.lossy();
        assertThrows(IndexOutOfBoundsException.class, () -> d.feed(new byte[2], 1, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> d.feed(new byte[2], -1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> d.feed(new byte[2], 0, -1));
    }
}
