package ae.notify;

/** Result of dispatching one notification; {@link #text()} is the legacy wire string. */
public sealed interface Outcome {
    String text();

    /** Rejected input, e.g. {@code ERR:no-user}. */
    record Failed(String code) implements Outcome {
        public String text() { return "ERR:" + code; }
    }

    /** Intentionally not sent, e.g. {@code SKIP:disabled}. */
    record Skipped(String reason) implements Outcome {
        public String text() { return "SKIP:" + reason; }
    }

    /** Rendered message line. */
    record Sent(String line) implements Outcome {
        public String text() { return line; }
    }
}
