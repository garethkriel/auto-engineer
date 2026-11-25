package ae.kad;

import java.util.Comparator;

/** A 64-bit Kademlia node/key identifier. Distance is the XOR of two ids, compared as unsigned. */
public record NodeId(long value) {

    /** XOR distance. Interpret the result as unsigned (use {@link Long#compareUnsigned}). */
    public long distance(NodeId other) {
        return value ^ other.value;
    }

    /** Index (0..63) of the highest bit in which the ids differ, or -1 if they are equal. */
    public int bucketIndex(NodeId other) {
        long x = distance(other);
        return x == 0 ? -1 : 63 - Long.numberOfLeadingZeros(x);
    }

    /** Orders ids by ascending XOR distance to {@code target}. */
    public static Comparator<NodeId> closerTo(NodeId target) {
        return (a, b) -> Long.compareUnsigned(a.distance(target), b.distance(target));
    }

    @Override
    public String toString() {
        return String.format("%016x", value);
    }
}
