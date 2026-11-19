package ae.datalog;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class EvaluationTest {

    private static String edges(int[][] e) {
        StringBuilder sb = new StringBuilder();
        for (int[] x : e) {
            sb.append("edge(n").append(x[0]).append(", n").append(x[1]).append(").\n");
        }
        return sb.toString();
    }

    private static final String TC = """
        tc(X, Y) :- edge(X, Y).
        tc(X, Z) :- tc(X, Y), edge(Y, Z).
        """;

    private static final String TC_QUADRATIC = """
        tc(X, Y) :- edge(X, Y).
        tc(X, Z) :- tc(X, Y), tc(Y, Z).
        """;

    private static boolean[][] floyd(int n, List<int[]> es) {
        boolean[][] d = new boolean[n][n];
        for (int[] e : es) {
            d[e[0]][e[1]] = true;
        }
        for (int k = 0; k < n; k++) {
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    if (d[i][k] && d[k][j]) {
                        d[i][j] = true;
                    }
                }
            }
        }
        return d;
    }

    @Test
    void transitiveClosureMatchesFloydWarshallOnRandomGraphs() {
        Random rnd = new Random(42);
        for (int round = 0; round < 40; round++) {
            int n = 2 + rnd.nextInt(9);
            int m = rnd.nextInt(2 * n + 1);
            List<int[]> es = new ArrayList<>();
            for (int i = 0; i < m; i++) {
                es.add(new int[] {rnd.nextInt(n), rnd.nextInt(n)});
            }
            boolean[][] expected = floyd(n, es);
            for (String rules : List.of(TC, TC_QUADRATIC)) {
                for (Mode mode : Mode.values()) {
                    Result r = Datalog.run(edges(es.toArray(new int[0][])) + rules, mode);
                    List<List<String>> want = new ArrayList<>();
                    for (int i = 0; i < n; i++) {
                        for (int j = 0; j < n; j++) {
                            if (expected[i][j]) {
                                want.add(List.of("n" + i, "n" + j));
                            }
                        }
                    }
                    want.sort(Result.TUPLE_ORDER);
                    assertEquals(want, r.relation("tc"), "round " + round + " " + mode);
                }
            }
        }
    }

    @Test
    void semiNaiveDoesLessJoinWorkThanNaiveOnAChain() {
        int n = 60;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n - 1; i++) {
            sb.append("edge(n").append(i).append(", n").append(i + 1).append(").\n");
        }
        sb.append(TC);
        Result naive = Datalog.run(sb.toString(), Mode.NAIVE);
        Result semi = Datalog.run(sb.toString(), Mode.SEMI_NAIVE);
        assertEquals(n * (n - 1) / 2, semi.relation("tc").size());
        assertEquals(naive.relation("tc"), semi.relation("tc"));
        assertTrue(semi.joinWork() * 5 < naive.joinWork(),
            "semi=" + semi.joinWork() + " naive=" + naive.joinWork());
    }

    @Test
    void longChainFinishesQuickly() {
        int n = 200;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n - 1; i++) {
            sb.append("edge(n").append(i).append(", n").append(i + 1).append(").\n");
        }
        sb.append(TC).append("?- tc(n0, X).");
        Result r = assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () -> Datalog.run(sb.toString()));
        assertEquals(n - 1, r.answers(0).size());
        assertEquals(n * (n - 1) / 2, r.relation("tc").size());
    }

    @Test
    void cyclesTerminate() {
        Result r = Datalog.run("edge(a,b). edge(b,c). edge(c,a)." + TC);
        assertEquals(9, r.relation("tc").size());
    }

    @Test
    void mutualRecursionReachesFixpoint() {
        Result r = Datalog.run("""
            zero(n0). succ(n0, n1). succ(n1, n2). succ(n2, n3). succ(n3, n4).
            even(X) :- zero(X).
            even(Y) :- odd(X), succ(X, Y).
            odd(Y) :- even(X), succ(X, Y).
            ?- even(X). ?- odd(X).
            """);
        assertEquals(3, r.answers(0).size());
        assertEquals(2, r.answers(1).size());
    }

    /** Random programs over 3 predicates with 2-ary relations; compares the two modes. */
    @Test
    void naiveAndSemiNaiveAgreeOnRandomStratifiedPrograms() {
        Random rnd = new Random(7);
        String[] idb = {"a", "b", "c"};
        for (int round = 0; round < 120; round++) {
            StringBuilder sb = new StringBuilder();
            int facts = 3 + rnd.nextInt(10);
            for (int i = 0; i < facts; i++) {
                sb.append("e(k").append(rnd.nextInt(5)).append(", k").append(rnd.nextInt(5)).append(").\n");
            }
            int rules = 2 + rnd.nextInt(5);
            for (int i = 0; i < rules; i++) {
                int h = rnd.nextInt(3);
                sb.append(idb[h]).append("(X, Z) :- ");
                int kind = rnd.nextInt(4);
                // positive dependencies only on indexes <= h, negation only on indexes < h: always stratified
                String p1 = rnd.nextBoolean() ? "e" : idb[rnd.nextInt(h + 1)];
                String p2 = rnd.nextBoolean() ? "e" : idb[rnd.nextInt(h + 1)];
                switch (kind) {
                    case 0 -> sb.append("e(X, Z)");
                    case 1 -> sb.append(p1).append("(X, Y), ").append(p2).append("(Y, Z)");
                    case 2 -> sb.append(p1).append("(X, Z), X != Z");
                    default -> {
                                                String neg = h == 0 || rnd.nextBoolean() ? "e" : idb[rnd.nextInt(h)];
                        sb.append("e(X, Z), not ").append(neg).append("(Z, X)");
                    }
                }
                sb.append(".\n");
            }
            String src = sb.toString();
            Result n = Datalog.run(src, Mode.NAIVE);
            Result s = Datalog.run(src, Mode.SEMI_NAIVE);
            for (String p : List.of("a", "b", "c")) {
                assertEquals(n.relation(p), s.relation(p), "pred " + p + " round " + round + "\n" + src);
            }
        }
    }
}
