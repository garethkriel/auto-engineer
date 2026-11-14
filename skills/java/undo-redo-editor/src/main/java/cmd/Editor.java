package cmd;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/** Text editor with bounded undo/redo history and save-point tracking. */
public final class Editor {
    /** One history step: the command that reverses it, and the revisions either side. */
    private record Entry(Command reverse, long from, long to) {}

    private final StringBuilder doc = new StringBuilder();
    private final Deque<Entry> undoStack = new ArrayDeque<>();
    private final Deque<Entry> redoStack = new ArrayDeque<>();
    private final int limit;
    private long revision;
    private long counter;
    private long savedRevision;

    public Editor(int historyLimit) {
        if (historyLimit < 1) {
            throw new IllegalArgumentException("historyLimit must be >= 1");
        }
        this.limit = historyLimit;
    }

    /** Executes a command; clears redo history only if it succeeds. */
    public void execute(Command command) {
        Objects.requireNonNull(command, "command");
        Command inverse = command.apply(doc);
        long next = ++counter;
        undoStack.push(new Entry(inverse, revision, next));
        if (undoStack.size() > limit) {
            undoStack.removeLast();
        }
        redoStack.clear();
        revision = next;
    }

    public boolean undo() {
        return step(undoStack, redoStack);
    }

    public boolean redo() {
        return step(redoStack, undoStack);
    }

    private boolean step(Deque<Entry> from, Deque<Entry> to) {
        Entry e = from.peek();
        if (e == null) {
            return false;
        }
        Command opposite = e.reverse().apply(doc);
        from.pop();
        to.push(new Entry(opposite, e.to(), e.from()));
        revision = e.from();
        return true;
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    public String text() {
        return doc.toString();
    }

    public void save() {
        savedRevision = revision;
    }

    public boolean isDirty() {
        return revision != savedRevision;
    }
}
