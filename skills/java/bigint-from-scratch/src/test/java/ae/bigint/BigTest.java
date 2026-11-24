package ae.bigint;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Tests for {@link Big}; {@link BigInteger} is the oracle. */
class BigTest {

    private static final int[] EXTREME = {0, 1, 0x7fffffff, 0x80000000, 0xffffffff};

    // ---------- helpers ----------

    private static BigInteger o(Big b) {
        return new BigInteger(b.toString());
    }

    private static Big b(BigInteger x) {
        return Big.parse(x.toString());
    }

    private static BigInteger randomBits(Random r, int maxBits) {
        BigInteger x = new BigInteger(r.nextInt(maxBits + 1), r);
        return r.nextBoolean() ? x.negate() : x;
    }

    /** Operand whose 32-bit limbs come from the extreme set. */
    private static BigInteger extremeValue(Random r, int maxLimbs) {
        int n = 1 + r.nextInt(maxLimbs);
        BigInteger x = BigInteger.ZERO;
        for (int i = 0; i < n; i++) {
            x = x.shiftLeft(32).or(BigInteger.valueOf(EXTREME[r.nextInt(EXTREME.length)] & 0xffffffffL));
        }
        return r.nextInt(4) == 0 ? x.negate() : x;
    }

    private static int[] mag(BigInteger nonNegative) {
        byte[] bytes = nonNegative.toByteArray();
        int limbs = (bytes.length + 3) / 4;
        int[] out = new int[limbs];
        for (int i = 0; i < bytes.length; i++) {
            int fromEnd = bytes.length - 1 - i;
            out[fromEnd / 4] |= (bytes[i] & 0xff) << (8 * (fromEnd % 4));
        }
        int len = out.length;
        while (len > 0 && out[len - 1] == 0) {
            len--;
        }
        return java.util.Arrays.copyOf(out, len);
    }

    // ---------- literals ----------

    @Test
    void factorialOf30() {
        Big f = Big.ONE;
        for (int i = 2; i <= 30; i++) {
            f = f.multiply(Big.valueOf(i));
        }
        assertEquals("265252859812191058636308480000000", f.toString());
    }

    @Test
    void factorialOf100HasPublishedValue() {
        Big f = Big.ONE;
        for (int i = 2; i <= 100; i++) {
            f = f.multiply(Big.valueOf(i));
        }
        assertEquals("93326215443944152681699238856266700490715968264381621468592963895217599993229915608941463976156518"
                + "286253697920827223758251185210916864000000000000000000000000", f.toString());
        assertEquals(525, f.bitLength());
    }

    @Test
    void powerOfTwoLiterals() {
        assertEquals("1606938044258990275541962092341162602522202993782792835301376", Big.valueOf(2).pow(200).toString());
        assertEquals("340282366920938463463374607431768211456", Big.ONE.shiftLeft(128).toString());
        assertEquals(201, Big.valueOf(2).pow(200).bitLength());
    }

    @Test
    void limbBoundariesCarryAndBorrow() {
        Big m = Big.parse("4294967295"); // 2^32 - 1
        assertEquals("4294967296", m.add(Big.ONE).toString());
        assertEquals("18446744073709551616", m.add(Big.ONE).multiply(m.add(Big.ONE)).toString());
        assertEquals("18446744073709551615", Big.ONE.shiftLeft(64).subtract(Big.ONE).toString());
        assertEquals("4294967295", Big.ONE.shiftLeft(32).subtract(Big.ONE).toString());
        assertEquals("0", Big.ONE.shiftLeft(96).subtract(Big.ONE.shiftLeft(96)).toString());
    }

    @Test
    void zeroIsNormalisedAndEqual() {
        Big z = Big.valueOf(5).subtract(Big.valueOf(5));
        assertEquals(Big.ZERO, z);
        assertEquals(Big.ZERO.hashCode(), z.hashCode());
        assertEquals(0, z.signum());
        assertEquals(0, z.bitLength());
        assertEquals("0", Big.parse("-0").toString());
        assertEquals(Big.ZERO, Big.parse("000"));
        assertEquals(Big.ZERO, Big.ZERO.negate());
    }

    @Test
    void equalsDistinguishesSignAndValue() {
        assertNotEquals(Big.valueOf(1), Big.valueOf(-1));
        assertNotEquals(Big.valueOf(1), Big.valueOf(2));
        assertEquals(Big.parse("123456789012345678901234567890"), Big.parse("123456789012345678901234567890"));
        assertEquals(Big.parse("12345678901234567890").hashCode(), Big.parse("12345678901234567890").hashCode());
    }

    @ParameterizedTest
    @CsvSource({
        "7, 2, 3, 1",
        "-7, 2, -3, -1",
        "7, -2, -3, 1",
        "-7, -2, 3, -1",
        "6, 3, 2, 0",
        "-6, 3, -2, 0",
        "0, 5, 0, 0",
        "2, 5, 0, 2",
        "-2, 5, 0, -2",
    })
    void divisionTruncatesTowardZero(String a, String d, String q, String r) {
        Big[] qr = Big.parse(a).divideAndRemainder(Big.parse(d));
        assertEquals(q, qr[0].toString());
        assertEquals(r, qr[1].toString());
        assertEquals(q, Big.parse(a).divide(Big.parse(d)).toString());
        assertEquals(r, Big.parse(a).remainder(Big.parse(d)).toString());
    }

    @ParameterizedTest
    @CsvSource({"7, 3, 1", "-7, 3, 2", "-6, 3, 0", "0, 3, 0", "-1, 1000000000000000000000, 999999999999999999999"})
    void modIsNonNegative(String a, String m, String expected) {
        assertEquals(expected, Big.parse(a).mod(Big.parse(m)).toString());
    }

    @Test
    void divisionAndModErrors() {
        assertThrows(ArithmeticException.class, () -> Big.ONE.divide(Big.ZERO));
        assertThrows(ArithmeticException.class, () -> Big.ONE.remainder(Big.ZERO));
        assertThrows(ArithmeticException.class, () -> Big.ONE.divideAndRemainder(Big.ZERO));
        assertThrows(ArithmeticException.class, () -> Big.ONE.mod(Big.ZERO));
        assertThrows(ArithmeticException.class, () -> Big.ONE.mod(Big.valueOf(-3)));
    }

    @ParameterizedTest
    @CsvSource({"-5, 1, -3", "-5, 2, -2", "-1, 100, -1", "5, 1, 2", "-4, 1, -2", "0, 9, 0", "5, -1, 10", "-5, -1, -10"})
    void shiftRightFloorsAndNegativeShiftReverses(String v, int n, String expected) {
        assertEquals(expected, Big.parse(v).shiftRight(n).toString());
    }

    @Test
    void shiftsAcrossLimbs() {
        Big x = Big.parse("123456789012345678901234567890");
        assertEquals(x, x.shiftLeft(100).shiftRight(100));
        assertEquals(x.multiply(Big.valueOf(2).pow(33)), x.shiftLeft(33));
        assertEquals(x, x.shiftLeft(-0));
        assertEquals(Big.parse("-1"), Big.parse("-123456789012345678901234567890").shiftRight(500));
        assertEquals(Big.ZERO, x.shiftRight(500));
    }

    @Test
    void radixFormatting() {
        assertEquals("ff", Big.valueOf(255).toString(16));
        assertEquals("-zz", Big.valueOf(-1295).toString(36));
        assertEquals("1000000000000000000000000000000000", Big.ONE.shiftLeft(33).toString(2));
        assertEquals("0", Big.ZERO.toString(7));
        assertEquals(Big.valueOf(255), Big.parse("FF", 16));
        assertEquals(Big.valueOf(255), Big.parse("ff", 16));
        assertEquals(Big.valueOf(-1295), Big.parse("-ZZ", 36));
        assertEquals(Big.valueOf(5), Big.parse("+5"));
    }

    @ParameterizedTest
    @CsvSource(value = {"''", "'-'", "'+'", "'12a'", "' 1'", "'1 '", "'1_0'", "'--1'"})
    void parseRejectsMalformed(String text) {
        assertThrows(NumberFormatException.class, () -> Big.parse(text));
    }

    @Test
    void parseRejectsDigitOutOfRadixAndBadRadix() {
        assertThrows(NumberFormatException.class, () -> Big.parse("12", 2));
        assertThrows(NumberFormatException.class, () -> Big.parse("g", 16));
        assertThrows(IllegalArgumentException.class, () -> Big.parse("1", 1));
        assertThrows(IllegalArgumentException.class, () -> Big.parse("1", 37));
        assertThrows(IllegalArgumentException.class, () -> Big.ONE.toString(1));
        assertThrows(IllegalArgumentException.class, () -> Big.ONE.toString(37));
    }

    @Test
    void longValueExactBoundaries() {
        assertEquals(Long.MAX_VALUE, Big.valueOf(Long.MAX_VALUE).longValueExact());
        assertEquals(Long.MIN_VALUE, Big.valueOf(Long.MIN_VALUE).longValueExact());
        assertEquals("-9223372036854775808", Big.valueOf(Long.MIN_VALUE).toString());
        assertEquals(0L, Big.ZERO.longValueExact());
        assertThrows(ArithmeticException.class, () -> Big.valueOf(Long.MAX_VALUE).add(Big.ONE).longValueExact());
        assertThrows(ArithmeticException.class, () -> Big.valueOf(Long.MIN_VALUE).subtract(Big.ONE).longValueExact());
        assertThrows(ArithmeticException.class, () -> Big.ONE.shiftLeft(64).longValueExact());
    }

    @Test
    void powSpecialCases() {
        assertEquals(Big.ONE, Big.ZERO.pow(0));
        assertEquals(Big.ZERO, Big.ZERO.pow(5));
        assertEquals(Big.valueOf(-8), Big.valueOf(-2).pow(3));
        assertEquals(Big.valueOf(16), Big.valueOf(-2).pow(4));
        assertThrows(ArithmeticException.class, () -> Big.valueOf(2).pow(-1));
    }

    @Test
    void modPowKnownValuesAndErrors() {
        assertEquals("445", Big.valueOf(4).modPow(Big.valueOf(13), Big.valueOf(497)).toString());
        assertEquals(Big.ZERO, Big.valueOf(5).modPow(Big.valueOf(3), Big.ONE));
        assertEquals(Big.ONE, Big.valueOf(5).modPow(Big.ZERO, Big.valueOf(7)));
        assertEquals(Big.valueOf(6), Big.valueOf(-1).modPow(Big.valueOf(3), Big.valueOf(7)));
        // Fermat: a^(p-1) = 1 mod p for the Mersenne prime 2^127 - 1.
        Big p = Big.ONE.shiftLeft(127).subtract(Big.ONE);
        assertEquals(Big.ONE, Big.valueOf(3).modPow(p.subtract(Big.ONE), p));
        assertThrows(ArithmeticException.class, () -> Big.ONE.modPow(Big.valueOf(-1), Big.valueOf(7)));
        assertThrows(ArithmeticException.class, () -> Big.ONE.modPow(Big.ONE, Big.ZERO));
    }

    @Test
    void gcdAndInverseLiterals() {
        assertEquals(Big.valueOf(6), Big.valueOf(54).gcd(Big.valueOf(-24)));
        assertEquals(Big.ZERO, Big.ZERO.gcd(Big.ZERO));
        assertEquals(Big.valueOf(7), Big.ZERO.gcd(Big.valueOf(-7)));
        assertEquals(Big.valueOf(4), Big.valueOf(3).modInverse(Big.valueOf(11)));
        assertEquals(Big.valueOf(10), Big.valueOf(-1).modInverse(Big.valueOf(11)));
        assertEquals(Big.ZERO, Big.valueOf(5).modInverse(Big.ONE));
        assertThrows(ArithmeticException.class, () -> Big.valueOf(6).modInverse(Big.valueOf(9)));
        assertThrows(ArithmeticException.class, () -> Big.valueOf(6).modInverse(Big.ZERO));
    }

    @Test
    void isqrtLiteralsAndErrors() {
        assertEquals(Big.ZERO, Big.ZERO.isqrt());
        assertEquals(Big.ONE, Big.valueOf(3).isqrt());
        assertEquals(Big.valueOf(2), Big.valueOf(4).isqrt());
        Big x = Big.parse("123456789012345678901234567890");
        assertEquals(x, x.multiply(x).isqrt());
        assertEquals(x, x.multiply(x).add(x.shiftLeft(1)).isqrt()); // x^2 + 2x < (x+1)^2
        assertEquals(x.add(Big.ONE), x.multiply(x).add(x.shiftLeft(1)).add(Big.ONE).isqrt());
        assertThrows(ArithmeticException.class, () -> Big.valueOf(-1).isqrt());
    }

    @Test
    void multiplyEmptyAndOneLimbEdgeCases() {
        assertArrayEquals(new int[0], Big.mulSchool(new int[0], new int[] {5}));
        assertArrayEquals(new int[0], Big.mulKaratsuba(new int[] {5}, new int[0], 2));
        assertArrayEquals(new int[] {1, 0xfffffffe}, Big.mulSchool(new int[] {0xffffffff}, new int[] {0xffffffff}));
    }

    // ---------- differential tests ----------

    @Test
    void parseAndFormatRoundTripAgainstOracle() {
        Random r = new Random(1);
        for (int i = 0; i < 400; i++) {
            BigInteger x = i % 3 == 0 ? extremeValue(r, 12) : randomBits(r, 700);
            assertEquals(x.toString(), Big.parse(x.toString()).toString(), "dec " + x);
            for (int radix : new int[] {2, 3, 8, 16, 31, 36}) {
                String s = x.toString(radix);
                assertEquals(s, Big.parse(s, radix).toString(radix), "radix " + radix + " " + s);
                assertEquals(x, o(Big.parse(s, radix)), "value radix " + radix);
            }
        }
    }

    @Test
    void addSubtractNegateCompareAgainstOracle() {
        Random r = new Random(2);
        for (int i = 0; i < 1500; i++) {
            BigInteger x = i % 2 == 0 ? extremeValue(r, 6) : randomBits(r, 300);
            BigInteger y = i % 2 == 0 ? extremeValue(r, 6) : randomBits(r, 300);
            Big bx = b(x);
            Big by = b(y);
            String msg = x + " , " + y;
            assertEquals(x.add(y), o(bx.add(by)), "add " + msg);
            assertEquals(x.subtract(y), o(bx.subtract(by)), "sub " + msg);
            assertEquals(x.negate(), o(bx.negate()), "neg " + msg);
            assertEquals(x.abs(), o(bx.abs()), "abs " + msg);
            assertEquals(x.signum(), bx.signum(), "signum " + msg);
            assertEquals(Integer.signum(x.compareTo(y)), Integer.signum(bx.compareTo(by)), "cmp " + msg);
            assertEquals(x.equals(y), bx.equals(by), "eq " + msg);
            assertEquals(x.abs().bitLength(), bx.bitLength(), "bitLength " + msg);
        }
    }

    @Test
    void multiplyAgainstOracleIncludingKaratsubaSizes() {
        Random r = new Random(3);
        for (int i = 0; i < 300; i++) {
            BigInteger x = randomBits(r, i < 200 ? 400 : 4000);
            BigInteger y = randomBits(r, i < 200 ? 400 : 4000);
            assertEquals(x.multiply(y), o(b(x).multiply(b(y))), "mul " + x + " * " + y);
        }
    }

    @Test
    void karatsubaEqualsSchoolbookOnUnequalAndExtremeOperands() {
        Random r = new Random(4);
        for (int i = 0; i < 600; i++) {
            BigInteger x = (i % 2 == 0 ? extremeValue(r, 40) : randomBits(r, 1300)).abs();
            BigInteger y = (i % 2 == 0 ? extremeValue(r, 40) : randomBits(r, 300)).abs();
            int[] a = mag(x);
            int[] c = mag(y);
            int[] school = Big.mulSchool(a, c);
            assertArrayEquals(mag(x.multiply(y)), school, "school " + x + " * " + y);
            for (int threshold : new int[] {2, 3, 5}) {
                assertArrayEquals(school, Big.mulKaratsuba(a, c, threshold), "kara t=" + threshold + " " + x + " * " + y);
            }
        }
    }

    @Test
    void divisionAgainstOracleWithExtremeLimbsReachesAddBack() {
        Random r = new Random(5);
        long before = Big.addBacks();
        int checked = 0;
        for (int i = 0; i < 40000; i++) {
            BigInteger x = extremeValue(r, 8);
            BigInteger y = extremeValue(r, 4);
            if (y.signum() == 0) {
                continue;
            }
            BigInteger[] want = x.divideAndRemainder(y);
            Big[] got = b(x).divideAndRemainder(b(y));
            assertEquals(want[0], o(got[0]), "q " + x + " / " + y);
            assertEquals(want[1], o(got[1]), "r " + x + " / " + y);
            checked++;
        }
        assertTrue(checked > 30000, "checked " + checked);
        assertTrue(Big.addBacks() > before, "add-back path never exercised");
    }

    @Test
    void divisionExhaustiveOverSmallLimbAlphabet() {
        int[] alphabet = {0, 1, 0x80000000, 0xffffffff};
        int checked = 0;
        for (int uCode = 0; uCode < 256; uCode++) { // 4 limbs, base-4 digits select the limb values
            BigInteger u = BigInteger.ZERO;
            for (int k = 3; k >= 0; k--) {
                u = u.shiftLeft(32).or(BigInteger.valueOf(alphabet[(uCode >> (2 * k)) & 3] & 0xffffffffL));
            }
            for (int vCode = 0; vCode < 64; vCode++) { // 3 limbs
                BigInteger v = BigInteger.ZERO;
                for (int k = 2; k >= 0; k--) {
                    v = v.shiftLeft(32).or(BigInteger.valueOf(alphabet[(vCode >> (2 * k)) & 3] & 0xffffffffL));
                }
                if (v.signum() == 0) {
                    continue;
                }
                BigInteger[] want = u.divideAndRemainder(v);
                Big[] got = b(u).divideAndRemainder(b(v));
                assertEquals(want[0], o(got[0]), "q " + u + " / " + v);
                assertEquals(want[1], o(got[1]), "r " + u + " / " + v);
                checked++;
            }
        }
        assertEquals(256 * 63, checked);
    }

    @Test
    void modPowWithModulusOneIsZeroEvenForZeroExponent() {
        assertEquals(Big.ZERO, Big.valueOf(5).modPow(Big.ZERO, Big.ONE));
        assertEquals(Big.ZERO, Big.ZERO.modPow(Big.ZERO, Big.ONE));
    }

    @Test
    void divisionAgainstOracleRandomSizes() {
        Random r = new Random(6);
        for (int i = 0; i < 3000; i++) {
            BigInteger x = randomBits(r, 900);
            BigInteger y = randomBits(r, 1 + r.nextInt(500));
            if (y.signum() == 0) {
                continue;
            }
            Big[] got = b(x).divideAndRemainder(b(y));
            BigInteger[] want = x.divideAndRemainder(y);
            assertEquals(want[0], o(got[0]), "q " + x + " / " + y);
            assertEquals(want[1], o(got[1]), "r " + x + " / " + y);
            // Reconstruction identity and remainder bound hold independently of the oracle.
            assertEquals(b(x), got[0].multiply(b(y)).add(got[1]), "identity " + x + " / " + y);
            assertTrue(got[1].abs().compareTo(b(y).abs()) < 0, "bound " + x + " / " + y);
        }
    }

    @Test
    void modPowGcdInverseIsqrtPowAgainstOracle() {
        Random r = new Random(7);
        for (int i = 0; i < 250; i++) {
            BigInteger x = randomBits(r, 200);
            BigInteger y = randomBits(r, 200);
            BigInteger m = new BigInteger(1 + r.nextInt(200), r).add(BigInteger.ONE);
            BigInteger e = new BigInteger(r.nextInt(120), r);
            assertEquals(x.gcd(y), o(b(x).gcd(b(y))), "gcd " + x + " " + y);
            assertEquals(x.mod(m), o(b(x).mod(b(m))), "mod " + x + " " + m);
            assertEquals(x.modPow(e, m), o(b(x).modPow(b(e), b(m))), "modPow " + x + " " + e + " " + m);
            assertEquals(x.abs().sqrt(), o(b(x).abs().isqrt()), "isqrt " + x);
            if (x.gcd(m).equals(BigInteger.ONE)) {
                assertEquals(x.modInverse(m), o(b(x).modInverse(b(m))), "inv " + x + " " + m);
            } else {
                assertThrows(ArithmeticException.class, () -> b(x).modInverse(b(m)));
            }
            int p = r.nextInt(40);
            assertEquals(x.pow(p), o(b(x).pow(p)), "pow " + x + "^" + p);
        }
    }

    @Test
    void shiftsAgainstOracle() {
        Random r = new Random(8);
        for (int i = 0; i < 1000; i++) {
            BigInteger x = i % 2 == 0 ? extremeValue(r, 6) : randomBits(r, 300);
            int n = r.nextInt(200);
            assertEquals(x.shiftLeft(n), o(b(x).shiftLeft(n)), "shl " + x + " " + n);
            assertEquals(x.shiftRight(n), o(b(x).shiftRight(n)), "shr " + x + " " + n);
            assertEquals(x.shiftRight(n), o(b(x).shiftLeft(-n)), "shl neg " + x + " " + n);
            assertEquals(x.shiftLeft(n), o(b(x).shiftRight(-n)), "shr neg " + x + " " + n);
        }
    }

    @Test
    void longRoundTrip() {
        Random r = new Random(9);
        long[] edge = {0, 1, -1, Long.MAX_VALUE, Long.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, 1L << 32, -(1L << 32)};
        for (long v : edge) {
            assertEquals(v, Big.valueOf(v).longValueExact());
            assertEquals(Long.toString(v), Big.valueOf(v).toString());
        }
        for (int i = 0; i < 500; i++) {
            long v = r.nextLong();
            assertEquals(v, Big.valueOf(v).longValueExact());
            assertEquals(BigInteger.valueOf(v), o(Big.valueOf(v)));
        }
    }
}
