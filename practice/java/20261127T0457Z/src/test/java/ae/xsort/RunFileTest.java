package ae.xsort;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunFileTest {
    @TempDir Path tmp;

    private List<String> readAll(Path f) throws IOException {
        List<String> out = new ArrayList<>();
        try (RunFile.Reader<String> r = RunFile.open(f, Codecs.STRING)) {
            while (r.hasNext()) out.add(r.next());
        }
        return out;
    }

    @Test
    void roundTripIncludingEmptyRunAndLongStrings() throws IOException {
        Path f = tmp.resolve("a.run");
        List<String> data = List.of("", "héllo", "x".repeat(100_000), "日本語");
        try (RunFile.Writer<String> w = RunFile.create(f, Codecs.STRING)) { data.forEach(w::write); }
        assertEquals(data, readAll(f));
        Path e = tmp.resolve("e.run");
        try (RunFile.Writer<String> w = RunFile.create(e, Codecs.STRING)) { /* empty */ }
        assertEquals(List.of(), readAll(e));
    }

    @Test
    void everyTruncationIsDetected() throws IOException {
        Path f = tmp.resolve("t.run");
        try (RunFile.Writer<String> w = RunFile.create(f, Codecs.STRING)) { w.write("alpha"); w.write("beta"); }
        byte[] full = Files.readAllBytes(f);
        for (int cut = 0; cut < full.length; cut++) {
            Path g = tmp.resolve("cut" + cut);
            Files.write(g, Arrays.copyOf(full, cut));
            assertThrows(CorruptRunException.class, () -> readAll(g), "cut at " + cut + " of " + full.length);
        }
        assertEquals(List.of("alpha", "beta"), readAll(f), "control: the full file is fine");
    }

    @Test
    void badMarkerByteIsCorrupt() throws IOException {
        Path f = tmp.resolve("b.run");
        Files.write(f, new byte[] {7});
        assertThrows(CorruptRunException.class, () -> readAll(f));
    }

    @Test
    void stringCodecRejectsNegativeLength() throws IOException {
        Path f = tmp.resolve("n.run");
        // marker 1, then length -1
        Files.write(f, new byte[] {1, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
        assertThrows(CorruptRunException.class, () -> readAll(f));
    }

    @Test
    void longCodecRoundTrip() throws IOException {
        Path f = tmp.resolve("l.run");
        long[] v = {Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE};
        try (RunFile.Writer<Long> w = RunFile.create(f, Codecs.LONG)) { for (long x : v) w.write(x); }
        List<Long> got = new ArrayList<>();
        try (RunFile.Reader<Long> r = RunFile.open(f, Codecs.LONG)) { while (r.hasNext()) got.add(r.next()); }
        assertEquals(List.of(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE), got);
    }
}
