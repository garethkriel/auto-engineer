package ae.sketch;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** HyperLogLog cardinality estimator with linear-counting small-range correction. Not thread safe. */
public final class HyperLogLog {
    static final int MAGIC = 0x484c4c31; // "HLL1"
    public static final int MIN_PRECISION = 4;
    public static final int MAX_PRECISION = 16;
    private static final long SEED = 0x51ed270b0e5aL;

    private final int precision;
    private final byte[] registers;

    private HyperLogLog(int precision, byte[] registers) {
        this.precision = precision;
        this.registers = registers;
    }

    public static HyperLogLog create(int precision) {
        if (precision < MIN_PRECISION || precision > MAX_PRECISION) {
            throw new IllegalArgumentException("precision must be in 4..16");
        }
        return new HyperLogLog(precision, new byte[1 << precision]);
    }

    public int precision() {
        return precision;
    }

    /** Relative standard error 1.04 / sqrt(registers). */
    public double standardError() {
        return 1.04 / Math.sqrt(registers.length);
    }

    public void add(String item) {
        add(Objects.requireNonNull(item, "item").getBytes(StandardCharsets.UTF_8));
    }

    public void add(byte[] item) {
        long h = Hash64.hash(item, SEED);
        int idx = (int) (h >>> (64 - precision));
        long rest = h << precision;
        // rank = position of first 1-bit in the remaining 64-p bits, capped at 64-p+1
        int rank = rest == 0 ? 64 - precision + 1 : Long.numberOfLeadingZeros(rest) + 1;
        if (rank > registers[idx]) {
            registers[idx] = (byte) rank;
        }
    }

    public double estimate() {
        int m = registers.length;
        double sum = 0;
        int zeros = 0;
        for (byte r : registers) {
            sum += Math.pow(2.0, -r);
            if (r == 0) {
                zeros++;
            }
        }
        double alpha = switch (m) {
            case 16 -> 0.673;
            case 32 -> 0.697;
            case 64 -> 0.709;
            default -> 0.7213 / (1.0 + 1.079 / m);
        };
        double raw = alpha * m * (double) m / sum;
        if (raw <= 2.5 * m && zeros > 0) {
            return m * Math.log(m / (double) zeros);
        }
        return raw;
    }

    /** Register-wise maximum: afterwards this sketch equals the sketch of both input streams. */
    public void merge(HyperLogLog other) {
        Objects.requireNonNull(other, "other");
        if (other.precision != precision) {
            throw new IllegalArgumentException("precision mismatch");
        }
        for (int i = 0; i < registers.length; i++) {
            if (other.registers[i] > registers[i]) {
                registers[i] = other.registers[i];
            }
        }
    }

    public byte[] toBytes() {
        ByteBuffer b = ByteBuffer.allocate(4 + 1 + registers.length);
        b.putInt(MAGIC).put((byte) precision).put(registers);
        return Codec.seal(b.array());
    }

    public static HyperLogLog fromBytes(byte[] image) {
        ByteBuffer b = Codec.open(image, MAGIC, 1);
        int p = b.get();
        if (p < MIN_PRECISION || p > MAX_PRECISION) {
            throw new IllegalArgumentException("bad precision");
        }
        if (b.remaining() != 1 << p) {
            throw new IllegalArgumentException("length does not match precision");
        }
        byte[] regs = new byte[1 << p];
        b.get(regs);
        int max = 64 - p + 1;
        for (byte r : regs) {
            if (r < 0 || r > max) {
                throw new IllegalArgumentException("register out of range");
            }
        }
        return new HyperLogLog(p, regs);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HyperLogLog h && h.precision == precision && Arrays.equals(h.registers, registers);
    }

    @Override
    public int hashCode() {
        return 31 * precision + Arrays.hashCode(registers);
    }
}
