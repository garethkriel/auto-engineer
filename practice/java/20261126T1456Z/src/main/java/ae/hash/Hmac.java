package ae.hash;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Supplier;

/** HMAC (RFC 2104) over any {@link Digest}; keys longer than the block are hashed first. */
public final class Hmac {
    private final Digest inner;
    private final Digest outer;
    private final byte[] ipad;
    private final byte[] opad;

    public Hmac(Supplier<Digest> hash, byte[] key) {
        Objects.requireNonNull(key);
        this.inner = hash.get();
        this.outer = hash.get();
        int block = inner.blockLength();
        byte[] k = key;
        if (k.length > block) {
            Digest d = hash.get();
            d.update(k);
            k = d.digest();
        }
        ipad = new byte[block];
        opad = new byte[block];
        Arrays.fill(ipad, (byte) 0x36);
        Arrays.fill(opad, (byte) 0x5c);
        for (int i = 0; i < k.length; i++) {
            ipad[i] ^= k[i];
            opad[i] ^= k[i];
        }
        inner.update(ipad);
    }

    public void update(byte[] data, int off, int len) {
        inner.update(data, off, len);
    }

    public void update(byte[] data) {
        inner.update(data, 0, data.length);
    }

    /** Returns the tag and restarts with the same key. */
    public byte[] doFinal() {
        byte[] ih = inner.digest();
        outer.reset();
        outer.update(opad);
        outer.update(ih);
        byte[] tag = outer.digest();
        inner.update(ipad);
        return tag;
    }

    public int macLength() {
        return inner.digestLength();
    }

    public static byte[] mac(Supplier<Digest> hash, byte[] key, byte[] message) {
        Hmac h = new Hmac(hash, key);
        h.update(message);
        return h.doFinal();
    }
}
