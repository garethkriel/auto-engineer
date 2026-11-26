package ae.ot;

import java.util.Objects;
import java.util.Optional;

/** OT client: Synchronized / AwaitingAck(out) / AwaitingAckWithBuffer(out, buf). */
public final class Client {

    /** The three protocol states. */
    public enum State { SYNCHRONIZED, AWAITING_ACK, AWAITING_ACK_WITH_BUFFER }

    /** An operation to send to the server, based on revision {@code baseRevision}. */
    public record Send(int baseRevision, TextOp op) {}

    private final String id;
    private String doc;
    private int revision;
    private TextOp out;
    private TextOp buf;

    public Client(String id, String initial) {
        this.id = Objects.requireNonNull(id);
        this.doc = Objects.requireNonNull(initial);
    }

    public String id() { return id; }

    public String document() { return doc; }

    public int revision() { return revision; }

    public State state() {
        if (out == null) return State.SYNCHRONIZED;
        return buf == null ? State.AWAITING_ACK : State.AWAITING_ACK_WITH_BUFFER;
    }

    /** Applies a local edit; returns what must be sent now, if anything. */
    public Optional<Send> local(TextOp op) {
        String next = op.apply(doc); // validates before any mutation
        TextOp newBuf = buf == null ? null : TextOp.compose(buf, op);
        doc = next;
        if (out == null) {
            out = op;
            return Optional.of(new Send(revision, op));
        }
        if (buf == null) buf = op;
        else buf = newBuf;
        return Optional.empty();
    }

    /** Applies an operation broadcast by the server (from another client). */
    public void remote(TextOp serverOp) {
        TextOp op = serverOp;
        TextOp newOut = out;
        TextOp newBuf = buf;
        if (out != null) {
            TextOp.Pair p = TextOp.transform(op, out);
            op = p.first();
            newOut = p.second();
            if (buf != null) {
                TextOp.Pair q = TextOp.transform(op, buf);
                op = q.first();
                newBuf = q.second();
            }
        }
        String next = op.apply(doc); // validate before mutating
        doc = next;
        out = newOut;
        buf = newBuf;
        revision++;
    }

    /** The server accepted our outstanding op; returns the buffered op to send next, if any. */
    public Optional<Send> ack() {
        if (out == null) throw new IllegalStateException("unexpected ack in state SYNCHRONIZED");
        revision++;
        if (buf == null) {
            out = null;
            return Optional.empty();
        }
        out = buf;
        buf = null;
        return Optional.of(new Send(revision, out));
    }
}
