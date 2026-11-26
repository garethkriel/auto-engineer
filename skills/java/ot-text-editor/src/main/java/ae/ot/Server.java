package ae.ot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Central OT server: a document plus the log of every accepted (transformed) operation. */
public final class Server {

    /** An accepted operation, as broadcast to all clients. */
    public record Accepted(int revision, String clientId, TextOp op) {}

    private final String initial;
    private final List<Accepted> log = new ArrayList<>();
    private String doc;

    public Server(String initial) {
        this.initial = Objects.requireNonNull(initial);
        this.doc = initial;
    }

    public String document() { return doc; }

    public int revision() { return log.size(); }

    /**
     * Accepts {@code op}, made by {@code clientId} against revision {@code baseRev}.
     * The op is transformed over log entries {@code baseRev..} (history is the first
     * argument, so it wins insert ties). Failures leave the server unchanged.
     */
    public Accepted receive(String clientId, int baseRev, TextOp op) {
        Objects.requireNonNull(clientId);
        Objects.requireNonNull(op);
        if (baseRev < 0 || baseRev > log.size()) {
            throw new IllegalArgumentException("bad base revision " + baseRev + " (server at " + log.size() + ")");
        }
        TextOp cur = op;
        for (int i = baseRev; i < log.size(); i++) {
            if (cur.baseLength() != log.get(i).op().baseLength()) {
                throw new IllegalArgumentException("op base length " + cur.baseLength()
                        + " does not match revision " + baseRev + " document");
            }
            cur = TextOp.transform(log.get(i).op(), cur).second();
        }
        if (cur.baseLength() != doc.length()) {
            throw new IllegalArgumentException("op base length " + cur.baseLength()
                    + " does not match document length " + doc.length());
        }
        String next = cur.apply(doc);
        doc = next;
        Accepted acc = new Accepted(log.size() + 1, clientId, cur);
        log.add(acc);
        return acc;
    }

    /** Re-applies the whole log to the initial text. */
    public String replayLog() {
        String d = initial;
        for (Accepted a : log) d = a.op().apply(d);
        return d;
    }
}
