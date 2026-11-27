package ae.xsort;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Stable external merge sort. At most {@code memoryRecords} records are held while generating runs
 * (replacement selection) and at most {@code fanIn} heads while merging (loser tree). Runs are merged
 * in groups of {@code fanIn} consecutive runs until at most {@code fanIn} remain; the last merge goes
 * straight to the consumer. Every temporary file is deleted on every exit path.
 */
public final class ExternalSorter<T> {
    /**
     * Statistics of one sort. {@code mergePasses} is 0 for zero or one run, otherwise the number of
     * merge levels including the final merge to the consumer, i.e. ceil(log_fanIn(runs)).
     */
    public record Stats(long recordsRead, List<Long> runLengths, int mergePasses, int peakRecordsInMemory,
                        int tempFilesCreated) {}

    private final Codec<T> codec;
    private final Comparator<? super T> cmp;
    private final int memoryRecords;
    private final int fanIn;
    private final Path tempDir;

    public ExternalSorter(Codec<T> codec, Comparator<? super T> cmp, int memoryRecords, int fanIn, Path tempDir) {
        this.codec = Objects.requireNonNull(codec, "codec");
        this.cmp = Objects.requireNonNull(cmp, "cmp");
        Objects.requireNonNull(tempDir, "tempDir");
        if (memoryRecords < 1) throw new IllegalArgumentException("memoryRecords must be >= 1");
        if (fanIn < 2) throw new IllegalArgumentException("fanIn must be >= 2");
        if (!Files.isDirectory(tempDir)) throw new IllegalArgumentException("not a directory: " + tempDir);
        this.memoryRecords = memoryRecords;
        this.fanIn = fanIn;
        this.tempDir = tempDir;
    }

    /** Sorts {@code input} and hands the records to {@code out} in order. */
    public Stats sort(Iterator<T> input, Consumer<? super T> out) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(out, "out");
        Set<Path> live = new HashSet<>();
        int[] created = {0};
        try {
            List<Path> runs = new ArrayList<>();
            List<Long> lengths = new ArrayList<>();
            long[] read = {0};
            Iterator<T> counted = new Iterator<>() {
                @Override public boolean hasNext() { return input.hasNext(); }
                @Override public T next() { read[0]++; return input.next(); }
            };
            int peak = writeRuns(counted, runs, lengths, live, created);
            int passes = 0;
            if (!runs.isEmpty()) {
                while (runs.size() > fanIn) {
                    List<Path> next = new ArrayList<>();
                    for (int i = 0; i < runs.size(); i += fanIn) {
                        List<Path> group = runs.subList(i, Math.min(runs.size(), i + fanIn));
                        if (group.size() == 1) {
                            next.add(group.get(0));
                            continue;
                        }
                        Path merged = newTemp(live, created);
                        try (RunFile.Writer<T> w = RunFile.create(merged, codec)) {
                            mergeGroup(group, w::write);
                        }
                        deleteAll(group, live);
                        next.add(merged);
                    }
                    runs = next;
                    passes++;
                }
                mergeGroup(runs, out);
                passes++;
                if (runs.size() == 1 && lengths.size() == 1) passes = 0; // a single run is just streamed
            }
            return new Stats(read[0], List.copyOf(lengths), passes, peak, created[0]);
        } finally {
            for (Path p : live) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort: the original failure (if any) is more useful than a cleanup error
                }
            }
        }
    }

    private int writeRuns(Iterator<T> input, List<Path> runs, List<Long> lengths, Set<Path> live, int[] created) {
        class Sink implements ReplacementSelection.RunSink<T>, AutoCloseable {
            RunFile.Writer<T> writer;
            long count;

            @Override public void startRun() {
                finish();
                Path p = newTemp(live, created);
                runs.add(p);
                writer = RunFile.create(p, codec);
            }

            @Override public void accept(T v) {
                writer.write(v);
                count++;
            }

            void finish() {
                if (writer != null) {
                    RunFile.Writer<T> w = writer;
                    writer = null;
                    lengths.add(count);
                    count = 0;
                    w.close();
                }
            }

            @Override public void close() {
                finish();
            }
        }
        try (Sink sink = new Sink()) {
            return ReplacementSelection.generate(input, cmp, memoryRecords, sink);
        }
    }

    private void mergeGroup(List<Path> group, Consumer<? super T> sink) {
        List<RunFile.Reader<T>> readers = new ArrayList<>();
        try {
            for (Path p : group) readers.add(RunFile.open(p, codec));
            LoserTree<T> tree = new LoserTree<>(readers, cmp);
            while (tree.hasNext()) sink.accept(tree.next());
        } finally {
            for (RunFile.Reader<T> r : readers) {
                try {
                    r.close();
                } catch (UncheckedIOException ignored) {
                    // closing a reader cannot lose data
                }
            }
        }
    }

    private Path newTemp(Set<Path> live, int[] created) {
        try {
            Path p = Files.createTempFile(tempDir, "xsort-", ".run");
            live.add(p);
            created[0]++;
            return p;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteAll(List<Path> files, Set<Path> live) {
        for (Path p : files) {
            try {
                Files.deleteIfExists(p);
                live.remove(p);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /**
     * Sorts the lines of a UTF-8 text file by {@link String#compareTo}. Lines end with {@code \n} or
     * {@code \r\n}; the output always uses {@code \n}. The output appears atomically, or not at all.
     */
    public static Stats sortLines(Path in, Path out, int memoryLines, int fanIn, Path tempDir) {
        ExternalSorter<String> sorter =
                new ExternalSorter<>(Codecs.STRING, Comparator.<String>naturalOrder(), memoryLines, fanIn, tempDir);
        Path outDir = out.toAbsolutePath().getParent();
        try (BufferedReader reader = Files.newBufferedReader(in, StandardCharsets.UTF_8)) {
            Path tmpOut = Files.createTempFile(outDir, ".xsort-out-", ".tmp");
            try {
                Stats stats;
                try (BufferedWriter w = Files.newBufferedWriter(tmpOut, StandardCharsets.UTF_8)) {
                    stats = sorter.sort(lineIterator(reader), line -> {
                        try {
                            w.write(line);
                            w.write('\n');
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
                }
                Files.move(tmpOut, out, StandardCopyOption.ATOMIC_MOVE);
                return stats;
            } finally {
                Files.deleteIfExists(tmpOut);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Iterator<String> lineIterator(BufferedReader reader) {
        return new Iterator<>() {
            private String next;

            @Override public boolean hasNext() {
                if (next == null) {
                    try {
                        next = reader.readLine();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                return next != null;
            }

            @Override public String next() {
                if (!hasNext()) throw new NoSuchElementException();
                String s = next;
                next = null;
                return s;
            }
        };
    }
}
