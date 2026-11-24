package ae.parsegen;

import java.util.Arrays;
import java.util.List;

/** Shared grammars and helpers for the tests. */
final class Fx {
    private Fx() {}

    /** Dragon-book LL(1) expression grammar. Production ids: E0 E1_0=1 E1_eps=2 T=3 T1_mul=4 T1_eps=5 F_par=6 F_id=7. */
    static final String LL = """
            # dragon book 4.28
            E  -> T E1
            E1 -> + T E1 | eps
            T  -> F T1
            T1 -> * F T1 | eps
            F  -> ( E ) | id
            """;

    /** Left-recursive version of the same language. Ids: E+T=0 E=T=1 T*F=2 T=F=3 F=(E)=4 F=id=5. */
    static final String LR = """
            E -> E + T | T
            T -> T * F | F
            F -> ( E ) | id
            """;

    /** Classic SLR(1) failure (dragon book 4.49): shift/reduce on "=". */
    static final String LVAL = """
            S -> L = R | R
            L -> * R | id
            R -> L
            """;

    /** LL(1) but not SLR(1): reduce/reduce in state 0 on a and b. */
    static final String LLNOTSLR = """
            S -> A a A b | B b B a
            A -> eps
            B -> eps
            """;

    static final String ELSE = """
            S -> if e then S | if e then S else S | a
            """;

    static final String AMB = "E -> E + E | a\n";

    /** Epsilon, not LL(1); language is a^i b^k with i <= k. */
    static final String AB = """
            S -> A S b | eps
            A -> a | eps
            """;

    static final String SB = "S -> a S b | c\n";

    static final String LIST = """
            L -> id T
            T -> , L | .
            """;

    static List<String> toks(String s) {
        return s.isBlank() ? List.of() : Arrays.asList(s.trim().split("\\s+"));
    }
}
