package ae.prolog;

/** A constant symbol. The empty list is {@code Atom("[]")}. */
public record Atom(String name) implements Term {
    public Atom {
        java.util.Objects.requireNonNull(name);
    }
}
