package ae.sketch;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Seeded, deterministic 64-bit hash (FNV-1a accumulation followed by a splitmix64 finaliser). */
public final class Hash64 {
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private Hash64() {
    }

    /** Hashes {@code data} with {@code seed}; different seeds give independent-looking functions. */
    public static long hash(byte[] data, long seed) {
        Objects.requireNonNull(data, "data");
        long h = FNV_OFFSET ^ mix(seed);
        for (byte b : data) {
            h ^= (b & 0xffL);
            h *= FNV_PRIME;
        }
        return mix(h ^ data.length);
    }

    /** Hashes the UTF-8 encoding of {@code s}. */
    public static long hash(String s, long seed) {
        Objects.requireNonNull(s, "s");
        return hash(s.getBytes(StandardCharsets.UTF_8), seed);
    }

    /** splitmix64 finaliser: a bijection with strong avalanche. */
    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
