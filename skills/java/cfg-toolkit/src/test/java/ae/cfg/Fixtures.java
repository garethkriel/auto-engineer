package ae.cfg;

/** Hand-written programs shared by the tests. Block indexes are listed next to each fixture. */
final class Fixtures {
    private Fixtures() { }

    /** Blocks: entry0 left1 right2 join3. */
    static final String DIAMOND = """
            func f(a)
            entry:
              c = lt a, a
              br c, left, right
            left:
              x = const 1
              jmp join
            right:
              x = const 2
              jmp join
            join:
              ret x
            """;

    /** Blocks: entry0 head1 body2 exit3. */
    static final String LOOP = """
            func g(n)
            entry:
              i = const 0
              one = const 1
              jmp head
            head:
              c = lt i, n
              br c, body, exit
            body:
              i = add i, one
              jmp head
            exit:
              ret i
            """;

    /** Blocks: entry0 A1 B2 X3. A and B form a two-entry cycle. */
    static final String IRREDUCIBLE = """
            func h(a)
            entry:
              br a, A, B
            A:
              jmp B
            B:
              br a, A, X
            X:
              ret a
            """;

    /** Diamond plus an unreachable block `dead` (index 4) that jumps into join. */
    static final String UNREACHABLE = """
            func u(a)
            entry:
              br a, left, right
            left:
              jmp join
            right:
              jmp join
            join:
              ret a
            dead:
              jmp join
            """;

    /** Entry block that is its own loop header. Blocks: entry0 out1. */
    static final String SELF_LOOP = """
            func e(a)
            entry:
              br a, entry, out
            out:
              ret a
            """;

    /** sum of 0..n inclusive. */
    static final String SUM = """
            func sum(n)
            entry:
              s = const 0
              i = const 0
              one = const 1
              jmp head
            head:
              c = lt n, i
              br c, exit, body
            body:
              s = add s, i
              i = add i, one
              jmp head
            exit:
              ret s
            """;
}
