package ae.hash;

import static ae.hash.TestUtil.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class BlakeTest {
    // Literals from Python hashlib.blake2b / blake2s (RFC 7693 appendix for "abc").
    private static byte[] seq(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) i;
        }
        return b;
    }

    @Test
    void blake2bVectors() {
        assertEquals("ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d17d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923",
                hashStr(new Blake2b(), "abc"));
        assertEquals("786a02f742015903c6c6fd852552d272912f4740e15847618a86e217f71f5419d25e1031afee585313896444934eb04b903a685b1448b755d56f701afe9be2ce",
                hashStr(new Blake2b(), ""));
    }

    @Test
    void blake2sVectors() {
        assertEquals("508c5e8c327c14e2e1a72ba34eeb452f37458b209ed63a294d999b4c86675982", hashStr(new Blake2s(), "abc"));
        assertEquals("69217a3079908094e11121d042354a7c1f55b6482ca1a51e1b250dfd1ed0eef9", hashStr(new Blake2s(), ""));
    }

    @Test
    void keyedVectors() {
        // Official-KAT style: key 00..3f (b) / 00..1f (s).
        Blake2b b = new Blake2b(64, seq(64));
        assertEquals("10ebb67700b1868efb4417987acf4690ae9d972fb7a590c2f02871799aaa4786b5e996e8f0f4eb981fc214b005f42d2ff4233499391653df7aefcbc13fc51568",
                Hex.encode(b.digest()));
        b.update(new byte[] {0});
        assertEquals("961f6dd1e4dd30f63901690c512e78e4b45e4742ed197c3c5e45c549fd25f2e4187b0bc9fe30492b16b0d0bc4ef9b0f34c7003fac09a5ef1532e69430234cebd",
                Hex.encode(b.digest()));
        Blake2s s = new Blake2s(32, seq(32));
        assertEquals("48a8997da407876b3d79c0d92325ad3b89cbb754d86ab71aee047ad345fd2c49", Hex.encode(s.digest()));
    }

    @Test
    void chainsCoverBlockBoundaries() {
        // Lengths 0..300 hit 127/128/129 and 255/256/257 (b) and 63/64/65 (s).
        assertEquals("3eb2aedcf22b05697871895739c0c4cbf094742175e5ed0b71e2b3d652b505f1251be936d131fc24e87eb848dd66a32c1ae4e0b8985bd1071e9e9d83dce9726a", chain(new Blake2b()));
        assertEquals("da4b7f1bcda6ddd6ecb2c2fd624799f9229959764676c07b1fdf73e9122b0c5c", chain(new Blake2s()));
        assertEquals("be3ab66c63b09f553cdf9df70c3c4ae54dfc6a16", chain(new Blake2b(20, seq(17))));
        assertEquals("7562df1a1592587638", chain(new Blake2s(9, seq(32))));
    }

    @Test
    void variableOutputLengthsChangeTheParameterBlock() {
        assertEquals("6b", hashStr(new Blake2b(1, null), "abc"));
        byte[] a200 = new byte[200];
        java.util.Arrays.fill(a200, (byte) 'a');
        Blake2b b = new Blake2b(33, null);
        b.update(a200);
        assertEquals("3570e6010e1ba1a8dce47cad256765308fdb6a6fdbe47bee4cb64d3f07b89a4d65", Hex.encode(b.digest()));
        Blake2s s = new Blake2s(17, null);
        s.update(a200);
        assertEquals("95b6910946f5d2512f9969e1f0b4221073", Hex.encode(s.digest()));
        assertEquals(33, b.digestLength());
    }

    @Test
    void millionAs() {
        Blake2b k = new Blake2b();
        byte[] block = new byte[1000];
        java.util.Arrays.fill(block, (byte) 'a');
        for (int i = 0; i < 1000; i++) {
            k.update(block);
        }
        assertEquals("98fb3efb7206fd19ebf69b6f312cf7b64e3b94dbe1a17107913975a793f177e1d077609d7fba363cbba00d05f7aa4e4fa8715d6428104c0a75643b0ff3fd3eaf", Hex.encode(k.digest()));
    }

    @Test
    void chunkingInvariantAndReuse() {
        Random rnd = new Random(7);
        for (int iter = 0; iter < 300; iter++) {
            byte[] data = new byte[rnd.nextInt(600)];
            rnd.nextBytes(data);
            byte[] key = rnd.nextBoolean() ? null : seq(1 + rnd.nextInt(32));
            int outLen = 1 + rnd.nextInt(32);
            Digest ref = iter % 2 == 0 ? new Blake2b(outLen, key) : new Blake2s(outLen, key);
            Digest test = iter % 2 == 0 ? new Blake2b(outLen, key) : new Blake2s(outLen, key);
            ref.update(data);
            byte[] expected = ref.digest();
            assertArrayEquals(expected, chunked(test, data, rnd), "iter=" + iter);
            // digest() reset must restore the keyed initial state, not just the buffer.
            assertArrayEquals(expected, chunked(test, data, rnd), "reuse iter=" + iter);
        }
    }

    @Test
    void keyChangesOutputAndKeyedEmptyDiffersFromUnkeyed() {
        byte[] k1 = seq(5);
        byte[] k2 = seq(5);
        k2[4] ^= 1;
        assertNotEquals(Hex.encode(new Blake2b(32, k1).digest()), Hex.encode(new Blake2b(32, k2).digest()));
        assertNotEquals(Hex.encode(new Blake2b(32, k1).digest()), Hex.encode(new Blake2b(32, null).digest()));
    }

    @Test
    void sizesAndValidation() {
        assertEquals(128, new Blake2b().blockLength());
        assertEquals(64, new Blake2b().digestLength());
        assertEquals(64, new Blake2s().blockLength());
        assertEquals(32, new Blake2s().digestLength());
        assertThrows(IllegalArgumentException.class, () -> new Blake2b(0, null));
        assertThrows(IllegalArgumentException.class, () -> new Blake2b(65, null));
        assertThrows(IllegalArgumentException.class, () -> new Blake2b(64, new byte[65]));
        assertThrows(IllegalArgumentException.class, () -> new Blake2s(0, null));
        assertThrows(IllegalArgumentException.class, () -> new Blake2s(33, null));
        assertThrows(IllegalArgumentException.class, () -> new Blake2s(32, new byte[33]));
        assertDoesNotThrow(() -> new Blake2b(64, new byte[64]));
        assertDoesNotThrow(() -> new Blake2s(32, new byte[32]));
        assertThrows(IndexOutOfBoundsException.class, () -> new Blake2b().update(new byte[4], 2, 3));
        // An empty key array behaves exactly like no key.
        assertEquals(hashStr(new Blake2b(), "abc"), hashStr(new Blake2b(64, new byte[0]), "abc"));
    }

    @Test
    void keyIsCopiedByTheConstructor() {
        byte[] key = seq(8);
        Blake2b b = new Blake2b(32, key);
        Blake2s s = new Blake2s(32, key);
        String bFirst = Hex.encode(b.digest());
        String sFirst = Hex.encode(s.digest());
        key[0] ^= 0x55;
        b.reset();  // reset() re-reads the stored key, so an aliased array would show the change here
        s.reset();
        assertEquals(bFirst, Hex.encode(b.digest()), "Blake2b must copy the key");
        assertEquals(sFirst, Hex.encode(s.digest()), "Blake2s must copy the key");
    }
}
