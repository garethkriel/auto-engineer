package ae.ot;

import java.util.Objects;
import java.util.Optional;

/**
 * Client side of the protocol (Jupiter / ot.js style). At most one operation is in flight; edits made
 * while waiting are composed into a single buffered operation. Remote operations are transformed over
 * the pending operations before they touch the local document.
 */
public final class OtClient {

    /** What the client is waiting for. */
    public enum State { SYNCED, AWAITING_ACK, AWAITING_WITH_BUFFER }

    /** An operation to send to the server, based on the client's known server revision. */
    public record Message(int baseRevision, TextOp op) { }

    private String document;
    private int revision;
    private TextOp outstanding;   // in flight, null when synced
    private TextOp buffer;        // composed edits made since, null unless AWAITING_WITH_BUFFER

    public OtClient(String initialDocument) {
        this.document = Objects.requireNonNull(initialDocument);
    }

    public String document() {
        return document;
    }

    /** The last server revision this client has seen (acks included). */
    public int revision() {
        return revision;
    }

    public State state() {
        if (outstanding == null) return State.SYNCED;
        return buffer == null ? State.AWAITING_ACK : State.AWAITING_WITH_BUFFER;
    }

    /** All local edits not yet acknowledged, composed, or empty when synced. */
    public Optional<TextOp> pending() {
        if (outstanding == null) return Optional.empty();
        return Optional.of(buffer == null ? outstanding : outstanding.compose(buffer));
    }

    /** Applies a local edit; returns the message to send if nothing is in flight. */
    public Optional<Message> localEdit(TextOp op) {
        Objects.requireNonNull(op);
        String next = op.apply(document);   // validates the length before any state changes
        document = next;
        if (outstanding == null) {
            outstanding = op;
            return Optional.of(new Message(revision, op));
        }
        buffer = buffer == null ? op : buffer.compose(op);
        return Optional.empty();
    }

    /** The server acknowledged the in-flight operation as {@code newRevision}; returns the next message, if any. */
    public Optional<Message> serverAck(int newRevision) {
        if (outstanding == null) throw new IllegalStateException("ack with nothing in flight");
        if (newRevision != revision + 1) {
            throw new IllegalStateException("ack revision " + newRevision + ", expected " + (revision + 1));
        }
        revision = newRevision;
        if (buffer == null) {
            outstanding = null;
            return Optional.empty();
        }
        outstanding = buffer;
        buffer = null;
        return Optional.of(new Message(revision, outstanding));
    }

    /** Another client's operation, committed by the server as {@code newRevision}. */
    public void serverOp(int newRevision, TextOp op) {
        Objects.requireNonNull(op);
        if (newRevision != revision + 1) {
            throw new IllegalStateException("remote revision " + newRevision + ", expected " + (revision + 1));
        }
        TextOp toApply = op;
        TextOp newOutstanding = outstanding;
        TextOp newBuffer = buffer;
        if (outstanding != null) {
            TextOp.Pair p = TextOp.transform(toApply, outstanding);   // remote op has priority
            toApply = p.left();
            newOutstanding = p.right();
            if (buffer != null) {
                TextOp.Pair q = TextOp.transform(toApply, buffer);
                toApply = q.left();
                newBuffer = q.right();
            }
        }
        String next = toApply.apply(document);
        document = next;
        outstanding = newOutstanding;
        buffer = newBuffer;
        revision = newRevision;
    }

    /** Test hook: replaces the local document without any protocol effect. */
    void setDocumentForTest(String doc) {
        this.document = doc;
    }
}
