package ae.parsegen;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/** A syntax error: {@code index} is the offending token position ({@code tokens.size()} means end of input). */
public final class ParseException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int index;
    private final transient Set<String> expected;

    public ParseException(int index, Set<String> expected) {
        super("unexpected token at index " + index + ", expected one of " + new TreeSet<>(expected));
        this.index = index;
        this.expected = Collections.unmodifiableSet(new TreeSet<>(expected));
    }

    public int index() {
        return index;
    }

    /** Terminals acceptable at that point, sorted; {@code $} stands for end of input. */
    public Set<String> expected() {
        return expected;
    }
}
