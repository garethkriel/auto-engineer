package ae.xsort;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExternalSorterTest {
    @TempDir Path tmp;

    record P(int key, int seq) {}

    private static final Codec<P> P_CODEC = new Codec<>() {
        @Override public void write(DataOutput out, P v) throws IOException { out.writeInt(v.key()); out.writeInt(v.seq()); }
        @Override public P read(DataInput in) throws IOException { return new P(in.readInt(), in.readInt()); }
    };
    private static final Comparator<P> BY_KEY = Comparator.comparingInt(P::key);

    private long filesIn(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) { return s.count(); }
    }

    private Path workDir() throws IOException { return Files.createDirectory(tmp.resolve("w" + System.nanoTime())); }

    private ExternalSorter.Stats run(List<P> in, int m, int fan, List<P> out, Path dir) {
        return new ExternalSorter<>(P_CODEC, BY_KEY, m, fan, dir).sort(in.iterator(), out::add);
    }

    @Test
    void differentialAgainstStableListSort() throws IOException {
        Random rnd = new Random(2024);
        int[] ns = {0, 1, 2, 4, 5, 6, 17, 100, 777};
        int[] ms = {1, 2, 5};
        int[] fans = {2, 3, 8};
        for (int n : ns) for (int m : ms) for (int fan : fans) {
            List<P> in = new ArrayList<>();
            for (int i = 0; i < n; i++) in.add(new P(rnd.nextInt(Math.max(1, n / 3)), i)); // many ties
            List<P> expected = new ArrayList<>(in);
            expected.sort(BY_KEY); // stable
            Path dir = workDir();
            List<P> out = new ArrayList<>();
            ExternalSorter.Stats st = run(in, m, fan, out, dir);
            String ctx = "n=" + n + " m=" + m + " fan=" + fan;
            assertEquals(expected, out, ctx);
            assertEquals(n, st.recordsRead(), ctx);
            assertEquals(n, st.runLengths().stream().mapToLong(Long::longValue).sum(), ctx);
            assertEquals(0, filesIn(dir), "temp files left: " + ctx);
            assertTrue(st.peakRecordsInMemory() <= m, ctx);
        }
    }

    @Test
    void sortedInputIsOneRunNoMergePassNoSpillFileLeft() throws IOException {
        List<P> in = new ArrayList<>();
        for (int i = 0; i < 50; i++) in.add(new P(i, i));
        Path dir = workDir();
        List<P> out = new ArrayList<>();
        ExternalSorter.Stats st = run(in, 3, 2, out, dir);
        assertEquals(in, out);
        assertEquals(List.of(50L), st.runLengths());
        assertEquals(0, st.mergePasses());
        assertEquals(1, st.tempFilesCreated());
        assertEquals(0, filesIn(dir));
    }

    @Test
    void emptyInputCreatesNothing() throws IOException {
        Path dir = workDir();
        List<P> out = new ArrayList<>();
        ExternalSorter.Stats st = run(List.of(), 3, 2, out, dir);
        assertEquals(List.of(), out);
        assertEquals(0, st.mergePasses());
        assertEquals(0, st.tempFilesCreated());
        assertEquals(List.of(), st.runLengths());
    }

    @Test
    void mergePassCountIsCeilLogOfRunsToBaseFanIn() throws IOException {
        // reverse-sorted n records with memory m gives ceil(n/m) runs.
        // (runs, fan) -> passes: (9,3)=2, (10,3)=3, (27,3)=3, (28,3)=4, (4,2)=2, (5,2)=3, (8,8)=1, (9,8)=2, (2,2)=1
        int[][] cases = {{9, 3, 2}, {10, 3, 3}, {27, 3, 3}, {28, 3, 4}, {4, 2, 2}, {5, 2, 3}, {8, 8, 1}, {9, 8, 2}, {2, 2, 1}};
        for (int[] c : cases) {
            int runs = c[0], fan = c[1], passes = c[2];
            int m = 4;
            List<P> in = new ArrayList<>();
            int n = runs * m; // exact multiple: every run holds exactly m records
            for (int i = n; i >= 1; i--) in.add(new P(i, n - i));
            Path dir = workDir();
            List<P> out = new ArrayList<>();
            ExternalSorter.Stats st = run(in, m, fan, out, dir);
            String ctx = "runs=" + runs + " fan=" + fan;
            assertEquals(runs, st.runLengths().size(), ctx);
            assertEquals(passes, st.mergePasses(), ctx);
            assertEquals(IntStream.rangeClosed(1, n).boxed().toList(), out.stream().map(P::key).toList(), ctx);
            assertEquals(0, filesIn(dir), ctx);
        }
    }

    @Test
    void stableAcrossIntermediateMergePasses() throws IOException {
        // all keys equal: output must be exact input order even with several merge levels
        List<P> in = new ArrayList<>();
        for (int i = 0; i < 200; i++) in.add(new P(i % 2, i));
        List<P> expected = new ArrayList<>(in);
        expected.sort(BY_KEY);
        List<P> out = new ArrayList<>();
        ExternalSorter.Stats st = run(in, 2, 2, out, workDir());
        assertTrue(st.mergePasses() >= 3, "want a multi-level merge, got " + st.mergePasses());
        assertEquals(expected, out);
    }

    @Test
    void codecFailureWhileSpillingCleansUp() throws IOException {
        for (int failAt : new int[] {1, 7, 40, 120}) {
            int[] writes = {0};
            Codec<P> bad = new Codec<>() {
                @Override public void write(DataOutput o, P v) throws IOException {
                    if (++writes[0] == failAt) throw new IOException("disk full");
                    P_CODEC.write(o, v);
                }
                @Override public P read(DataInput i) throws IOException { return P_CODEC.read(i); }
            };
            List<P> in = new ArrayList<>();
            for (int i = 150; i > 0; i--) in.add(new P(i, i));
            Path dir = workDir();
            RuntimeException e = assertThrows(RuntimeException.class,
                    () -> new ExternalSorter<>(bad, BY_KEY, 5, 2, dir).sort(in.iterator(), p -> {}));
            assertTrue(e.getMessage().contains("disk full") || String.valueOf(e.getCause()).contains("disk full"), e.toString());
            assertEquals(0, filesIn(dir), "failAt=" + failAt);
        }
    }

    @Test
    void comparatorFailureDuringMergeCleansUp() throws IOException {
        int[] calls = {0};
        Comparator<P> bad = (a, b) -> {
            if (++calls[0] > 400) throw new IllegalStateException("boom");
            return Integer.compare(a.key(), b.key());
        };
        List<P> in = new ArrayList<>();
        for (int i = 300; i > 0; i--) in.add(new P(i, i));
        Path dir = workDir();
        assertThrows(IllegalStateException.class,
                () -> new ExternalSorter<>(P_CODEC, bad, 4, 3, dir).sort(in.iterator(), p -> {}));
        assertTrue(calls[0] > 400, "comparator must actually have failed");
        assertEquals(0, filesIn(dir));
    }

    @Test
    void consumerFailureMidOutputCleansUp() throws IOException {
        List<P> in = new ArrayList<>();
        for (int i = 100; i > 0; i--) in.add(new P(i, i));
        Path dir = workDir();
        int[] seen = {0};
        assertThrows(IllegalStateException.class, () -> new ExternalSorter<>(P_CODEC, BY_KEY, 4, 2, dir)
                .sort(in.iterator(), p -> { if (++seen[0] == 10) throw new IllegalStateException("stop"); }));
        assertEquals(10, seen[0]);
        assertEquals(0, filesIn(dir));
    }

    @Test
    void consumedRunFilesAreDeletedBeforeTheFinalMerge() throws IOException {
        // 60 reverse-sorted records, m=2 -> 30 runs, fanIn 3: 30 -> 10 -> 4 -> 2 then final merge.
        List<P> in = new ArrayList<>();
        for (int i = 60; i >= 1; i--) in.add(new P(i, i));
        Path dir = workDir();
        long[] filesAtFirstOutput = {-1};
        new ExternalSorter<>(P_CODEC, BY_KEY, 2, 3, dir).sort(in.iterator(), p -> {
            if (filesAtFirstOutput[0] < 0) {
                try { filesAtFirstOutput[0] = filesIn(dir); } catch (IOException e) { throw new UncheckedIOException(e); }
            }
        });
        assertEquals(2, filesAtFirstOutput[0], "only the two runs of the final merge may still exist");
    }

    @Test
    void sorterInstanceIsReusable() throws IOException {
        Path dir = workDir();
        ExternalSorter<P> s = new ExternalSorter<>(P_CODEC, BY_KEY, 3, 2, dir);
        for (int round = 0; round < 3; round++) {
            List<P> in = new ArrayList<>();
            for (int i = 20; i > 0; i--) in.add(new P(i, i + round));
            List<P> out = new ArrayList<>();
            s.sort(in.iterator(), out::add);
            assertEquals(IntStream.rangeClosed(1, 20).boxed().toList(), out.stream().map(P::key).toList());
        }
        assertEquals(0, filesIn(dir));
    }

    @Test
    void argumentValidation() {
        assertThrows(IllegalArgumentException.class, () -> new ExternalSorter<>(P_CODEC, BY_KEY, 0, 2, tmp));
        assertThrows(IllegalArgumentException.class, () -> new ExternalSorter<>(P_CODEC, BY_KEY, 1, 1, tmp));
        assertThrows(NullPointerException.class, () -> new ExternalSorter<>(null, BY_KEY, 1, 2, tmp));
        assertThrows(NullPointerException.class, () -> new ExternalSorter<>(P_CODEC, null, 1, 2, tmp));
        assertThrows(NullPointerException.class, () -> new ExternalSorter<>(P_CODEC, BY_KEY, 1, 2, null));
        assertThrows(IllegalArgumentException.class, () -> new ExternalSorter<>(P_CODEC, BY_KEY, 1, 2, tmp.resolve("missing")));
    }

    // ---- sortLines -------------------------------------------------------------------------

    @Test
    void sortLinesHandlesUnicodeEmptyLinesAndCrLf() throws IOException {
        Path in = tmp.resolve("in.txt"), out = tmp.resolve("out.txt");
        String text = "pear\r\n\nálamo\napple\n" + "x".repeat(70_000) + "\nbanana\n\n";
        Files.writeString(in, text, StandardCharsets.UTF_8);
        Path dir = workDir();
        ExternalSorter.Stats st = ExternalSorter.sortLines(in, out, 2, 2, dir);
        // lines: "pear","","álamo","apple","xxx..","banana","" -> sorted by String.compareTo (UTF-16 order)
        String expected = "\n\napple\nbanana\npear\n" + "x".repeat(70_000) + "\nálamo\n";
        assertEquals(expected, Files.readString(out, StandardCharsets.UTF_8));
        assertEquals(7, st.recordsRead());
        assertEquals(0, filesIn(dir));
    }

    @Test
    void sortLinesEmptyInputGivesEmptyOutput() throws IOException {
        Path in = tmp.resolve("e.txt"), out = tmp.resolve("eo.txt");
        Files.writeString(in, "");
        ExternalSorter.sortLines(in, out, 2, 2, workDir());
        assertEquals("", Files.readString(out));
    }

    @Test
    void sortLinesMatchesCollectionsSortOnRandomFile() throws IOException {
        Random rnd = new Random(5);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 1500; i++) lines.add(Integer.toString(rnd.nextInt(400), 36));
        Path in = tmp.resolve("r.txt"), out = tmp.resolve("ro.txt");
        Files.write(in, lines);
        ExternalSorter.sortLines(in, out, 16, 4, workDir());
        List<String> expected = new ArrayList<>(lines);
        Collections.sort(expected);
        assertEquals(expected, Files.readAllLines(out));
    }

    @Test
    void failedSortLinesLeavesNoOutputAndKeepsOldOutput() throws IOException {
        Path outDir = Files.createDirectory(tmp.resolve("od"));
        Path out = outDir.resolve("sorted.txt");
        Files.writeString(out, "OLD\n");
        Path missing = tmp.resolve("does-not-exist.txt");
        assertThrows(RuntimeException.class, () -> ExternalSorter.sortLines(missing, out, 2, 2, workDir()));
        assertEquals("OLD\n", Files.readString(out), "previous output untouched");
        try (Stream<Path> s = Files.list(outDir)) {
            assertEquals(List.of("sorted.txt"), s.map(p -> p.getFileName().toString()).toList(), "no partial temp output");
        }
    }

    @Test
    void successfulSortLinesReplacesOldOutputAndLeavesNoTemp() throws IOException {
        Path outDir = Files.createDirectory(tmp.resolve("od2"));
        Path out = outDir.resolve("sorted.txt");
        Files.writeString(out, "OLD\n");
        Path in = tmp.resolve("i2.txt");
        Files.writeString(in, "b\na\n");
        ExternalSorter.sortLines(in, out, 1, 2, workDir());
        assertEquals("a\nb\n", Files.readString(out));
        try (Stream<Path> s = Files.list(outDir)) { assertEquals(1, s.count()); }
    }
}
