package ae.hm;

import java.util.Objects;

/** Entry point: principal types for the tiny functional language described in TASK.md. */
public final class Hm {

    private Hm() {}

    /**
     * Infers the principal type of {@code source}.
     *
     * @return the type with variables named a, b, c ... by first appearance
     * @throws ParseException on a syntax error
     * @throws TypeException on a type error
     */
    public static String typeOf(String source) {
        return Type.show(principalType(source));
    }

    /** Like {@link #typeOf} but returns the type itself, with variables numbered 0, 1, ... by first appearance. */
    public static Type principalType(String source) {
        Objects.requireNonNull(source, "source");
        Expr expr = Parser.parse(source);
        return Type.normalize(new Infer(source).infer(expr));
    }
}
