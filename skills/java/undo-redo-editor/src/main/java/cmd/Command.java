package cmd;

/** A reversible edit on a document. */
@FunctionalInterface
public interface Command {
    /**
     * Applies this edit and returns the command that exactly reverses it.
     *
     * @throws IllegalArgumentException if the edit is invalid; the document is then unchanged
     */
    Command apply(StringBuilder doc);
}
