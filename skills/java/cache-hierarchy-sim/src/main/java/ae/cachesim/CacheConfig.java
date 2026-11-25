package ae.cachesim;

import java.util.Objects;

/**
 * Cache geometry and policies. {@code sizeBytes = sets * ways * lineBytes}; line size and set count are powers of two.
 *
 * @param writeBack true: write-back + write-allocate; false: write-through + no-allocate
 */
public record CacheConfig(int sizeBytes, int lineBytes, int ways, Policy policy, boolean writeBack, long seed) {

    public CacheConfig {
        Objects.requireNonNull(policy, "policy");
        if (lineBytes < 1 || Integer.bitCount(lineBytes) != 1) {
            throw new IllegalArgumentException("lineBytes must be a power of two: " + lineBytes);
        }
        if (ways < 1) {
            throw new IllegalArgumentException("ways must be >= 1: " + ways);
        }
        if (sizeBytes < 1 || sizeBytes % ((long) lineBytes * ways) != 0) {
            throw new IllegalArgumentException("sizeBytes must be a multiple of lineBytes * ways: " + sizeBytes);
        }
        int sets = sizeBytes / (lineBytes * ways);
        if (Integer.bitCount(sets) != 1) {
            throw new IllegalArgumentException("set count must be a power of two: " + sets);
        }
        if (policy == Policy.PLRU && (Integer.bitCount(ways) != 1 || ways > 32)) {
            throw new IllegalArgumentException("PLRU needs power-of-two ways <= 32: " + ways);
        }
    }

    /** Number of sets. */
    public int sets() {
        return sizeBytes / (lineBytes * ways);
    }
}
