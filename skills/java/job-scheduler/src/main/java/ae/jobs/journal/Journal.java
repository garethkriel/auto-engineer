package ae.jobs.journal;

import java.util.List;

/** Append-only line log. {@code append} must be durable when it returns. */
public interface Journal {
    /** Appends one line (no newline characters). */
    void append(String line);

    /** Returns every complete line in append order. */
    List<String> readAll();
}
