package ae.b2t;

import java.util.Arrays;
import java.util.Objects;

/** BIP173/BIP350 segregated-witness address encoding. */
public final class Segwit {
    private Segwit() { }

    /** Decoded witness program. */
    public record Decoded(int version, byte[] program) { }

    private static void check(int version, byte[] program) {
        if (version < 0 || version > 16) {
            throw new IllegalArgumentException("witness version");
        }
        if (program.length < 2 || program.length > 40) {
            throw new IllegalArgumentException("program length");
        }
        if (version == 0 && program.length != 20 && program.length != 32) {
            throw new IllegalArgumentException("v0 program length");
        }
    }

    public static String encode(String hrp, int version, byte[] program) {
        Objects.requireNonNull(program);
        check(version, program);
        byte[] g = Bech32.convertBits(program, 8, 5, true);
        byte[] data = new byte[g.length + 1];
        data[0] = (byte) version;
        System.arraycopy(g, 0, data, 1, g.length);
        return Bech32.encode(hrp, data, version == 0 ? Bech32.Variant.BECH32 : Bech32.Variant.BECH32M);
    }

    public static Decoded decode(String hrp, String addr) {
        Objects.requireNonNull(hrp);
        Bech32.Decoded d = Bech32.decode(addr);
        if (!d.hrp().equals(hrp.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("wrong hrp");
        }
        if (d.data5().length < 1) {
            throw new IllegalArgumentException("empty data");
        }
        int version = d.data5()[0];
        if (version > 16) {
            throw new IllegalArgumentException("witness version");
        }
        byte[] program = Bech32.convertBits(Arrays.copyOfRange(d.data5(), 1, d.data5().length), 5, 8, false);
        check(version, program);
        Bech32.Variant want = version == 0 ? Bech32.Variant.BECH32 : Bech32.Variant.BECH32M;
        if (d.variant() != want) {
            throw new IllegalArgumentException("wrong checksum variant for version");
        }
        return new Decoded(version, program);
    }
}
