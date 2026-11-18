package ae.jobs.journal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Journal kept in memory; used by tests and as the simplest adapter. */
public final class InMemoryJournal implements Journal {
    private final List<String> lines = new ArrayList<>();

    @Override
    public synchronized void append(String line) {
        Objects.requireNonNull(line, "line");
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("line must not contain newlines");
        }
        lines.add(line);
    }

    @Override
    public synchronized List<String> readAll() {
        return List.copyOf(lines);
    }
}
