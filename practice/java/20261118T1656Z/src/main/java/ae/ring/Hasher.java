package ae.ring;

import java.nio.charset.StandardCharsets;

/** Maps a string to a 64-bit position on the ring. Must be deterministic. */
@FunctionalInterface
public interface Hasher {
    long hash(String s);

    /** FNV-1a over the UTF-8 bytes followed by the splitmix64 finaliser for avalanche. */
    Hasher DEFAULT = s -> {
        long h = 0xcbf29ce484222325L;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xffL);
            h *= 0x100000001b3L;
        }
        h ^= h >>> 30;
        h *= 0xbf58476d1ce4e5b9L;
        h ^= h >>> 27;
        h *= 0x94d049bb133111ebL;
        h ^= h >>> 31;
        return h;
    };
}
