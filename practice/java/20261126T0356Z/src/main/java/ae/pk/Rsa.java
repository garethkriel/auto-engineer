package ae.pk;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Random;

/** RSA key generation, raw primitives and RSASSA-PKCS1-v1_5 with SHA-256 (RFC 8017). */
public final class Rsa {
    private static final BigInteger E = BigInteger.valueOf(65537);

    /** DER prefix of DigestInfo for SHA-256 (RFC 8017 section 9.2 note 1). */
    static final byte[] SHA256_DIGEST_INFO_PREFIX = hexBytes("3031300d060960864801650304020105000420");

    private Rsa() { }

    private static byte[] hexBytes(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    /** Public key (n, e). */
    public record PublicKey(BigInteger n, BigInteger e) {
        /** Modulus length in bytes. */
        public int size() {
            return (n.bitLength() + 7) / 8;
        }
    }

    /** Private key with CRT parameters; the public constructor validates every relation. */
    public static final class PrivateKey {
        private final BigInteger p;
        private final BigInteger q;
        private final BigInteger e;
        private final BigInteger d;
        private final BigInteger dp;
        private final BigInteger dq;
        private final BigInteger qinv;

        public PrivateKey(BigInteger p, BigInteger q, BigInteger e, BigInteger d,
                          BigInteger dp, BigInteger dq, BigInteger qinv) {
            this(p, q, e, d, dp, dq, qinv, true);
        }

        private PrivateKey(BigInteger p, BigInteger q, BigInteger e, BigInteger d,
                           BigInteger dp, BigInteger dq, BigInteger qinv, boolean validate) {
            this.p = p;
            this.q = q;
            this.e = e;
            this.d = d;
            this.dp = dp;
            this.dq = dq;
            this.qinv = qinv;
            if (validate) {
                validate();
            }
        }

        /** Builds a key without consistency checks (tests use it to simulate a faulty CRT value). */
        static PrivateKey unchecked(BigInteger p, BigInteger q, BigInteger e, BigInteger d,
                                    BigInteger dp, BigInteger dq, BigInteger qinv) {
            return new PrivateKey(p, q, e, d, dp, dq, qinv, false);
        }

        private void validate() {
            BigInteger pm1 = p.subtract(BigInteger.ONE);
            BigInteger qm1 = q.subtract(BigInteger.ONE);
            if (p.equals(q) || p.signum() <= 0 || q.signum() <= 0) {
                throw new IllegalArgumentException("p and q must be distinct positive primes");
            }
            if (!d.multiply(e).mod(pm1).equals(BigInteger.ONE) || !d.multiply(e).mod(qm1).equals(BigInteger.ONE)) {
                throw new IllegalArgumentException("d is not an inverse of e");
            }
            if (!dp.equals(d.mod(pm1)) || !dq.equals(d.mod(qm1))) {
                throw new IllegalArgumentException("inconsistent CRT exponents");
            }
            if (!qinv.multiply(q).mod(p).equals(BigInteger.ONE)) {
                throw new IllegalArgumentException("inconsistent CRT coefficient");
            }
        }

        public BigInteger p() { return p; }
        public BigInteger q() { return q; }
        public BigInteger e() { return e; }
        public BigInteger d() { return d; }
        public BigInteger dp() { return dp; }
        public BigInteger dq() { return dq; }
        public BigInteger qinv() { return qinv; }
        public BigInteger n() { return p.multiply(q); }
        public PublicKey publicKey() { return new PublicKey(n(), e); }
    }

    /** Builds a key from two distinct primes (p is stored as the larger) and public exponent e. */
    public static PrivateKey fromPrimes(BigInteger p0, BigInteger q0, BigInteger e) {
        if (p0.equals(q0)) {
            throw new IllegalArgumentException("p == q");
        }
        BigInteger p = p0.max(q0);
        BigInteger q = p0.min(q0);
        Random rnd = new Random(0);
        if (!Primes.isProbablePrime(p, rnd) || !Primes.isProbablePrime(q, rnd)) {
            throw new IllegalArgumentException("p and q must be prime");
        }
        if (!e.testBit(0) || e.compareTo(BigInteger.valueOf(3)) < 0) {
            throw new IllegalArgumentException("e must be odd and >= 3");
        }
        BigInteger pm1 = p.subtract(BigInteger.ONE);
        BigInteger qm1 = q.subtract(BigInteger.ONE);
        BigInteger lambda = pm1.divide(pm1.gcd(qm1)).multiply(qm1);
        if (!e.gcd(lambda).equals(BigInteger.ONE)) {
            throw new IllegalArgumentException("gcd(e, lambda(n)) != 1");
        }
        BigInteger d = e.modInverse(lambda);
        return new PrivateKey(p, q, e, d, d.mod(pm1), d.mod(qm1), q.modInverse(p));
    }

    /** Generates a key with exactly {@code bits} bits (even, at least 512), e = 65537, from the given generator. */
    public static PrivateKey generate(int bits, Random rnd) {
        if (bits < 512 || bits % 2 != 0) {
            throw new IllegalArgumentException("bits must be even and at least 512");
        }
        int half = bits / 2;
        while (true) {
            BigInteger p = Primes.randomPrime(half, rnd);
            BigInteger q = Primes.randomPrime(half, rnd);
            if (!wellSeparated(p, q, half)) {
                continue; // too close: Fermat factoring would succeed
            }
            if (!p.subtract(BigInteger.ONE).gcd(E).equals(BigInteger.ONE)
                || !q.subtract(BigInteger.ONE).gcd(E).equals(BigInteger.ONE)) {
                continue;
            }
            return fromPrimes(p, q, E);
        }
    }

    /** True when |p - q| > 2^(half - 100), the usual guard against Fermat factoring. */
    static boolean wellSeparated(BigInteger p, BigInteger q, int half) {
        return p.subtract(q).abs().compareTo(BigInteger.ONE.shiftLeft(half - 100)) > 0;
    }

    /** RSAEP: m^e mod n for 0 <= m < n. */
    public static BigInteger rsaep(PublicKey k, BigInteger m) {
        if (m.signum() < 0 || m.compareTo(k.n()) >= 0) {
            throw new IllegalArgumentException("message representative out of range");
        }
        return m.modPow(k.e(), k.n());
    }

    /** RSADP via CRT; recomputes the public operation and throws if the result is inconsistent (fault attack). */
    public static BigInteger rsadp(PrivateKey k, BigInteger c) {
        BigInteger n = k.n();
        if (c.signum() < 0 || c.compareTo(n) >= 0) {
            throw new IllegalArgumentException("ciphertext representative out of range");
        }
        BigInteger m1 = c.modPow(k.dp(), k.p());
        BigInteger m2 = c.modPow(k.dq(), k.q());
        BigInteger h = k.qinv().multiply(m1.subtract(m2)).mod(k.p());
        BigInteger m = m2.add(k.q().multiply(h));
        if (!m.modPow(k.e(), n).equals(c)) {
            throw new IllegalStateException("CRT fault detected");
        }
        return m;
    }

    /** EMSA-PKCS1-v1_5 encoding of a SHA-256 digest into em_len bytes. */
    static byte[] encodeEm(byte[] hash, int emLen) {
        int tLen = SHA256_DIGEST_INFO_PREFIX.length + hash.length;
        if (emLen < tLen + 11) {
            throw new IllegalArgumentException("modulus too short");
        }
        byte[] em = new byte[emLen];
        em[1] = 1;
        Arrays.fill(em, 2, emLen - tLen - 1, (byte) 0xff);
        System.arraycopy(SHA256_DIGEST_INFO_PREFIX, 0, em, emLen - tLen, SHA256_DIGEST_INFO_PREFIX.length);
        System.arraycopy(hash, 0, em, emLen - hash.length, hash.length);
        return em;
    }

    private static byte[] sha256(byte[] m) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(m);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** RSASSA-PKCS1-v1_5 / SHA-256 signature, always modulus-length. */
    public static byte[] sign(PrivateKey k, byte[] message) {
        int len = k.publicKey().size();
        byte[] em = encodeEm(sha256(message), len);
        BigInteger s = rsadp(k, new BigInteger(1, em));
        return Curve.i2osp(s, len);
    }

    /** Strict verification: rebuilds the expected encoding and compares all bytes. */
    public static boolean verify(PublicKey k, byte[] message, byte[] signature) {
        int len = k.size();
        if (signature.length != len) {
            return false;
        }
        BigInteger s = new BigInteger(1, signature);
        if (s.compareTo(k.n()) >= 0) {
            return false;
        }
        byte[] em = Curve.i2osp(rsaep(k, s), len);
        return MessageDigest.isEqual(em, encodeEm(sha256(message), len));
    }
}
