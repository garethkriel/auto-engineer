package ae.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ae.jobs.Support.ManualClock;
import ae.jobs.journal.CorruptJournalException;
import ae.jobs.journal.FileJournal;
import ae.jobs.journal.InMemoryJournal;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalTest {
    private static final RetryPolicy POLICY = new RetryPolicy(3, 10, 100);
    private final ManualClock clock = new ManualClock(0);

    private static final JobHandler HANDLER = p -> {
        if (p.equals("bad")) {
            throw new PermanentFailure("tab\there");
        }
    };

    private static Map<String, JobHandler> handlers() {
        return Map.of("w", HANDLER);
    }

    @Test
    void everyEventTypeRoundTripsIncludingAwkwardCharacters() {
        String nasty = "tab\t newline\n percent% plus+ comma, unicode é中 \u0000 nul";
        List<Event> events = List.of(
                new Event.Submitted("id,1", "t\tw", nasty, List.of("a,b", "c d", ""), -5),
                new Event.Submitted("x", "w", "", List.of(), Long.MAX_VALUE),
                new Event.Started("id,1", 7),
                new Event.Succeeded("id,1"),
                new Event.Failed("id,1", 2, nasty, Long.MIN_VALUE),
                new Event.Died("id,1", 3, ""),
                new Event.Cancelled("id,1", nasty));
        for (Event e : events) {
            String line = EventCodec.encode(e);
            assertTrue(line.indexOf('\n') < 0 && line.indexOf('\r') < 0, "line must be single-line: " + line);
            assertEquals(e, EventCodec.decode(line));
        }
    }

    @Test
    void decodeRejectsGarbage() {
        String good = EventCodec.encode(new Event.Succeeded("a"));
        assertThrows(CorruptJournalException.class, () -> EventCodec.decode(""));
        assertThrows(CorruptJournalException.class, () -> EventCodec.decode("short"));
        assertThrows(CorruptJournalException.class, () -> EventCodec.decode("zzzzzzzz O\ta"));
        assertThrows(CorruptJournalException.class, () -> EventCodec.decode(good.substring(0, good.length() - 1) + "b"));
        // valid checksum but unknown record kind / wrong field count / bad number / bad percent escape
        for (String body : List.of("Z\ta", "O", "A\ta\tnotanumber", "O\t%zz")) {
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(body.getBytes(StandardCharsets.UTF_8));
            String line = String.format("%08x %s", crc.getValue(), body);
            assertThrows(CorruptJournalException.class, () -> EventCodec.decode(line), body);
        }
    }

    @Test
    void impossibleTransitionsWithValidChecksumsAreRejected() {
        List<List<Event>> bad = List.of(
                List.of(new Event.Started("ghost", 1)),
                List.of(new Event.Submitted("a", "w", "", List.of(), 0), new Event.Succeeded("a")),
                List.of(new Event.Submitted("a", "w", "", List.of(), 0),
                        new Event.Submitted("a", "w", "", List.of(), 0)),
                List.of(new Event.Submitted("a", "w", "", List.of("missing"), 0)),
                List.of(new Event.Submitted("a", "w", "", List.of(), 0), new Event.Started("a", 2)),
                List.of(new Event.Submitted("a", "w", "", List.of(), 0), new Event.Started("a", 1),
                        new Event.Failed("a", 5, "e", 10)),
                List.of(new Event.Submitted("a", "w", "", List.of(), 0), new Event.Started("a", 1),
                        new Event.Cancelled("a", "r")));
        int n = 0;
        for (List<Event> events : bad) {
            InMemoryJournal j = new InMemoryJournal();
            events.forEach(e -> j.append(EventCodec.encode(e)));
            assertThrows(CorruptJournalException.class, () -> new JobScheduler(j, clock, POLICY, handlers()),
                    "case " + n);
            n++;
        }
    }

    private Path runScenario(Path file) {
        JobScheduler s = new JobScheduler(new FileJournal(file), clock, POLICY, handlers());
        s.submit(JobSpec.of("a", "w", "ok"));
        s.submit(JobSpec.of("b", "w", "bad").after("a"));
        s.submit(JobSpec.of("c", "w", "ok").after("b"));
        s.submit(JobSpec.of("d", "w", "ok"));
        s.tick();
        return file;
    }

    @Test
    void tornTailIsIgnoredTrimmedAndLaterAppendsStayClean(@TempDir Path dir) throws IOException {
        Path file = runScenario(dir.resolve("j.log"));
        List<String> before = new FileJournal(file).readAll();
        Files.write(file, "0badc0de S\tpartial-rec".getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);
        assertEquals(before, new FileJournal(file).readAll());
        JobScheduler s = new JobScheduler(new FileJournal(file), clock, POLICY, handlers());
        s.submit(JobSpec.of("e", "w", "ok"));
        s.tick();
        String text = Files.readString(file);
        assertTrue(text.endsWith("\n"));
        assertTrue(!text.contains("partial-rec"));
        List<String> after = new FileJournal(file).readAll();
        assertEquals(before, after.subList(0, before.size()));
        assertTrue(after.size() > before.size());
        // and the whole thing still replays
        assertEquals(State.SUCCEEDED, new JobScheduler(new FileJournal(file), clock, POLICY, handlers())
                .status("e").orElseThrow().state());
    }

    @Test
    void checksumFailureInTheMiddleIsCorruption(@TempDir Path dir) throws IOException {
        Path file = runScenario(dir.resolve("j.log"));
        List<String> lines = new ArrayList<>(Files.readAllLines(file));
        String second = lines.get(1);
        char flipped = second.charAt(second.length() - 1) == 'x' ? 'y' : 'x';
        lines.set(1, second.substring(0, second.length() - 1) + flipped);
        Files.write(file, (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        assertThrows(CorruptJournalException.class,
                () -> new JobScheduler(new FileJournal(file), clock, POLICY, handlers()));
    }

    /** Recovered state of the first {@code k} journal lines, using the same recovery path. */
    private List<List<JobView>> prefixStates(List<String> lines) {
        List<List<JobView>> out = new ArrayList<>();
        for (int k = 0; k <= lines.size(); k++) {
            InMemoryJournal j = new InMemoryJournal();
            lines.subList(0, k).forEach(j::append);
            out.add(new JobScheduler(j, clock, POLICY, handlers()).all());
        }
        return out;
    }

    @Test
    void everyTruncationRecoversToAPrefixOfTheHistory(@TempDir Path dir) throws IOException {
        Path file = runScenario(dir.resolve("j.log"));
        byte[] bytes = Files.readAllBytes(file);
        List<List<JobView>> prefixes = prefixStates(Files.readAllLines(file));
        Path probe = dir.resolve("probe.log");
        for (int len = 0; len <= bytes.length; len++) {
            Files.write(probe, java.util.Arrays.copyOf(bytes, len));
            int completeLines = 0;
            for (int i = 0; i < len; i++) {
                if (bytes[i] == '\n') {
                    completeLines++;
                }
            }
            JobScheduler s = new JobScheduler(new FileJournal(probe), clock, POLICY, handlers());
            assertEquals(prefixes.get(completeLines), s.all(), "truncated to " + len + " bytes");
        }
    }

    @Test
    void flippingAnySingleByteIsDetectedOrHarmless(@TempDir Path dir) throws IOException {
        Path file = runScenario(dir.resolve("j.log"));
        byte[] bytes = Files.readAllBytes(file);
        List<List<JobView>> prefixes = prefixStates(Files.readAllLines(file));
        Path probe = dir.resolve("probe.log");
        int detected = 0;
        for (int i = 0; i < bytes.length; i++) {
            byte[] mutated = bytes.clone();
            mutated[i] ^= 0x01;
            Files.write(probe, mutated);
            try {
                List<JobView> got = new JobScheduler(new FileJournal(probe), clock, POLICY, handlers()).all();
                if (!prefixes.contains(got)) {
                    fail("byte " + i + " flipped silently produced a state that is no prefix of history: " + got);
                }
            } catch (CorruptJournalException expected) {
                detected++;
            }
        }
        assertTrue(detected > bytes.length / 2, "most flips must be detected, got " + detected + "/" + bytes.length);
    }

    @Test
    void emptyAndMissingJournalsStartEmpty(@TempDir Path dir) throws IOException {
        assertEquals(List.of(), new JobScheduler(new FileJournal(dir.resolve("missing.log")), clock, POLICY,
                handlers()).all());
        Path empty = Files.createFile(dir.resolve("empty.log"));
        assertEquals(List.of(), new JobScheduler(new FileJournal(empty), clock, POLICY, handlers()).all());
    }
}
