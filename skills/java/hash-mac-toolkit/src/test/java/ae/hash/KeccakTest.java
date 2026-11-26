package ae.hash;

import static ae.hash.TestUtil.*;
import static org.junit.jupiter.api.Assertions.*;

import java.security.MessageDigest;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class KeccakTest {
    // Literals below come from Python hashlib (sha3_*, shake_*), generated while authoring.

    @ParameterizedTest
    @CsvSource({
        "224,e642824c3f8cf24ad09234ee7d3c766fc9a3a5168d0c94ad73b46fdf",
        "256,3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532",
        "384,ec01498288516fc926459f58e2c6ad8df9b473cb0fc08c2596da7cf0e49be4b298d88cea927ac7f539f1edf228376d25",
        "512,b751850b1a57168a5693cd924b6b096e08f621827444f70d884f5d0240d2712e10e116e9192af3c91a7ec57647e3934057340b4cf408d5a56592f8274eec53f0"})
    void sha3AbcVectors(int bits, String expected) {
        assertEquals(expected, hashStr(sha3(bits), "abc"));
    }

    static Keccak sha3(int bits) {
        return switch (bits) {
            case 224 -> Keccak.sha3_224();
            case 256 -> Keccak.sha3_256();
            case 384 -> Keccak.sha3_384();
            case 512 -> Keccak.sha3_512();
            default -> throw new IllegalArgumentException();
        };
    }

    @Test
    void sha3EmptyMessageVector() {
        assertEquals("a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a",
                hashStr(Keccak.sha3_256(), ""));
    }

    @Test
    void shakeVectors() {
        Keccak s128 = Keccak.shake128();
        s128.update("abc".getBytes());
        assertEquals("5881092dd818bf5cf8a3ddb793fbcba74097d5c526a6d35f97b83351940f2cc844c50af32acd3f2c",
                Hex.encode(s128.squeeze(40)));
        Keccak s256 = Keccak.shake256();
        assertEquals("46b9dd2b0ba88d13233b3feb743eeb243fcd52ea62b81b82b50c27646ed5762fd75dc4ddd8c0f200cb05019d67b592f6fc821c49479ab48640292eacb3b7c4be",
                Hex.encode(s256.squeeze(64)));
    }

    @Test
    void chainOfLengthsCoversEveryRateBoundary() {
        // Lengths 0..300 cross the rates 144, 136, 104, 72 (SHA3) and 168, 136 (SHAKE) at -1/0/+1.
        assertEquals("c3e220287bbbe4f55a033bf4025357172b04bb8082b2efd2e8a9cfe1", chain(Keccak.sha3_224()));
        assertEquals("8d5115b9f924b82804f0e5f484c1d89a5b4e94ff6459703f2995d140e24083a3", chain(Keccak.sha3_256()));
        assertEquals("8aed7e0100517e19cf1066cb4efe508a00bc112fa0c773bca3537b877baa8985892a2c45b9130d7030ed85104b528726", chain(Keccak.sha3_384()));
        assertEquals("e7528e9785cb60eb5c9ecd0da49f89dd27847fb2dd16965257e33bc74a7928a0b7ed9102cadd178ce54974c0354121738096e8a2382f8c21f92a6f652000e5b7", chain(Keccak.sha3_512()));
        assertEquals("4789e64a00cc848fb1a2f7b775a542e350902f815a5cd2043f2099b9988fbc37", chain(Keccak.shake128()));
        assertEquals("84901eab3e9c004ac62c6dfbebf346b37bd8cae3e2174ec2494990039cf2909a72357d0db963a541f7fc7365a3473c87b6443eec2fc43522e824f00e9419a7df", chain(Keccak.shake256()));
    }

    @Test
    void millionAs() {
        Keccak k = Keccak.sha3_256();
        byte[] block = new byte[1000];
        java.util.Arrays.fill(block, (byte) 'a');
        for (int i = 0; i < 1000; i++) {
            k.update(block);
        }
        assertEquals("5c8875ae474a3634ba4fd55ec85bffd661f32aca75c6d699d0cdcb6c115891c1", Hex.encode(k.digest()));
    }

    @Test
    void sizesAndRates() {
        assertEquals(28, Keccak.sha3_224().digestLength());
        assertEquals(144, Keccak.sha3_224().blockLength());
        assertEquals(136, Keccak.sha3_256().blockLength());
        assertEquals(104, Keccak.sha3_384().blockLength());
        assertEquals(72, Keccak.sha3_512().blockLength());
        assertEquals(168, Keccak.shake128().blockLength());
        assertEquals(136, Keccak.shake256().blockLength());
        assertEquals(32, Keccak.shake128().digestLength());
        assertEquals(64, Keccak.shake256().digestLength());
    }

    @Test
    void randomChunkingMatchesOneShotAndJdk() throws Exception {
        Random rnd = new Random(42);
        for (int iter = 0; iter < 300; iter++) {
            byte[] data = new byte[rnd.nextInt(700)];
            rnd.nextBytes(data);
            int bits = new int[] {224, 256, 384, 512}[iter % 4];
            byte[] jdk = MessageDigest.getInstance("SHA3-" + bits).digest(data);
            assertArrayEquals(jdk, chunked(sha3(bits), data, rnd), "bits=" + bits + " len=" + data.length);
        }
    }

    @Test
    void digestResetsForReuse() {
        Keccak k = Keccak.sha3_256();
        k.update("junk".getBytes());
        k.digest();
        assertEquals("3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532", hashStr(k, "abc"));
        k.update("partial".getBytes());
        k.reset();
        assertEquals("3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532", hashStr(k, "abc"));
    }

    @Test
    void shakeSqueezeIsStreamable() {
        byte[] data = msg(150);
        Keccak one = Keccak.shake128();
        one.update(data);
        byte[] all = one.squeeze(500);
        Keccak parts = Keccak.shake128();
        parts.update(data);
        byte[] joined = new byte[500];
        int[] sizes = {1, 167, 1, 168, 0, 100, 63};  // crosses the 168-byte rate repeatedly
        int pos = 0;
        for (int s : sizes) {
            byte[] p = parts.squeeze(s);
            System.arraycopy(p, 0, joined, pos, s);
            pos += s;
        }
        assertEquals(500, pos);
        assertArrayEquals(all, joined);
    }

    @Test
    void shakeLongOutputMatchesJdkFreeLiteralPrefix() {
        // Prefix property: a longer squeeze starts with the shorter one.
        Keccak a = Keccak.shake256();
        a.update("abc".getBytes());
        byte[] longOut = a.squeeze(300);
        Keccak b = Keccak.shake256();
        b.update("abc".getBytes());
        assertArrayEquals(java.util.Arrays.copyOf(longOut, 17), b.squeeze(17));
    }

    @Test
    void updateAfterSqueezeIsRejectedAndResetRecovers() {
        Keccak k = Keccak.shake128();
        k.squeeze(1);
        assertThrows(IllegalStateException.class, () -> k.update(new byte[1]));
        k.reset();
        k.update("abc".getBytes());
        assertEquals("5881092d", Hex.encode(k.squeeze(4)));
    }

    @Test
    void fixedDigestDigestAfterSqueezeIsRejected() {
        Keccak k = Keccak.sha3_256();
        assertThrows(IllegalStateException.class, () -> k.squeeze(4), "fixed-length SHA-3 is not an XOF");
    }

    @Test
    void argumentValidation() {
        Keccak k = Keccak.shake128();
        assertThrows(IllegalArgumentException.class, () -> k.squeeze(-1));
        assertThrows(NullPointerException.class, () -> Keccak.sha3_256().update(null, 0, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> Keccak.sha3_256().update(new byte[4], 3, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> Keccak.sha3_256().update(new byte[4], -1, 1));
    }
}
