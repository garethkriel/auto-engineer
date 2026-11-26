package ae.ot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The authoritative side of a collaborative session: a linear history of committed operations.
 * Clients submit an operation together with the revision it was based on; the server transforms it
 * over every operation committed since (committed operations win insert ties), applies it and
 * appends it.
 */
public final class OtServer {

    /** Outcome of a submission: the new revision and the operation as committed. */
    public record Result(int revision, TextOp op) { }

    private final String initial;
    private String document;
    private final List<TextOp> history = new ArrayList<>();
    private final List<Integer> lengths = new ArrayList<>();   // lengths.get(r) = document length at revision r

    public OtServer(String initialDocument) {
        this.initial = Objects.requireNonNull(initialDocument);
        this.document = initialDocument;
        lengths.add(initialDocument.length());
    }

    public String document() {
        return document;
    }

    /** Number of committed operations. */
    public int revision() {
        return history.size();
    }

    public List<TextOp> history() {
        return List.copyOf(history);
    }

    public String initialDocument() {
        return initial;
    }

    /** The document as of {@code revision} (0 = the initial document). */
    public String documentAt(int revision) {
        if (revision < 0 || revision > history.size()) throw new IllegalArgumentException("revision " + revision);
        String d = initial;
        for (int i = 0; i < revision; i++) d = history.get(i).apply(d);
        return d;
    }

    /**
     * Commits {@code op}, which was made against revision {@code baseRevision}.
     *
     * @throws IllegalArgumentException if the revision is unknown or the operation does not fit
     *     the document at that revision; the server is unchanged in that case
     */
    public Result submit(int baseRevision, TextOp op) {
        Objects.requireNonNull(op);
        if (baseRevision < 0 || baseRevision > history.size()) {
            throw new IllegalArgumentException("unknown base revision " + baseRevision);
        }
        if (op.baseLength() != lengths.get(baseRevision)) {
            throw new IllegalArgumentException("op base length " + op.baseLength()
                    + " != document length " + lengths.get(baseRevision) + " at revision " + baseRevision);
        }
        TextOp current = op;
        for (int i = baseRevision; i < history.size(); i++) {
            current = TextOp.transform(history.get(i), current).right();
        }
        String next = current.apply(document);   // computed before any field changes
        document = next;
        history.add(current);
        lengths.add(next.length());
        return new Result(history.size(), current);
    }
}
