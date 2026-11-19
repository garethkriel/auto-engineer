package ae.deflate;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Independent oracles built on java.util.zip (tests only). */
final class JdkOracle {
    private JdkOracle() {}

    static byte[] deflate(byte[] in, int level, int strategy, boolean nowrap) {
        Deflater d = new Deflater(level, nowrap);
        d.setStrategy(strategy);
        d.setInput(in);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!d.finished()) {
            out.write(buf, 0, d.deflate(buf));
        }
        d.end();
        return out.toByteArray();
    }

    static byte[] inflate(byte[] in, boolean nowrap) throws DataFormatException {
        Inflater inf = new Inflater(nowrap);
        // zlib's raw mode can need one extra input byte to report 'finished' (JDK docs: dummy byte for nowrap)
        inf.setInput(nowrap ? java.util.Arrays.copyOf(in, in.length + 1) : in);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!inf.finished()) {
            int n = inf.inflate(buf);
            if (n == 0 && !inf.finished() && (inf.needsInput() || inf.needsDictionary())) {
                throw new DataFormatException("incomplete input");
            }
            out.write(buf, 0, n);
        }
        inf.end();
        return out.toByteArray();
    }
}
