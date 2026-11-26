package ae.patch;

/** One step of a line edit script. */
public sealed interface Edit {
    /** The line the step concerns. */
    String line();

    /** Line present in both texts. */
    record Keep(String line) implements Edit { }

    /** Line present only in the old text. */
    record Delete(String line) implements Edit { }

    /** Line present only in the new text. */
    record Insert(String line) implements Edit { }
}
