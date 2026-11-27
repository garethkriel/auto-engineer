package ae.datalog;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class DifferentialTest {
    @Test
    void transitiveClosureMatchesFloydWarshall() {
        Random rnd = new Random(20261127);
        for (int trial = 0; trial < 60; trial++) {
            int n = 2 + rnd.nextInt(9);
            boolean[][] r = new boolean[n][n];
            StringBuilder sb = new StringBuilder("tc(X,Y) :- e(X,Y). tc(X,Z) :- tc(X,Y), tc(Y,Z).\n");
            int edges = rnd.nextInt(n * 2 + 1);
            for (int i = 0; i < edges; i++) {
                int a = rnd.nextInt(n), b = rnd.nextInt(n);
                r[a][b] = true;
                sb.append("e(").append(a).append(',').append(b).append(").\n");
            }
            for (int k = 0; k < n; k++)
                for (int i = 0; i < n; i++)
                    for (int j = 0; j < n; j++) if (r[i][k] && r[k][j]) r[i][j] = true;
            Set<List<String>> expect = new HashSet<>();
            for (int i = 0; i < n; i++)
                for (int j = 0; j < n; j++) if (r[i][j]) expect.add(List.of("" + i, "" + j));
            Engine e = new Engine(Parser.parse(sb.toString()));
            for (Engine.Mode m : Engine.Mode.values())
                assertEquals(expect, e.evaluate(m).getOrDefault("tc", Set.of()), "trial " + trial + " " + m + "\n" + sb);
        }
    }

    @Test
    void negationMatchesBruteForce() {
        // sink(X): node with no outgoing edge; dead(X): not reachable from 0 and not a sink-only node
        Random rnd = new Random(77);
        for (int trial = 0; trial < 60; trial++) {
            int n = 2 + rnd.nextInt(8);
            boolean[][] adj = new boolean[n][n];
            StringBuilder sb = new StringBuilder("""
                reach(0).
                reach(Y) :- reach(X), e(X, Y).
                hasout(X) :- e(X, _).
                sink(X) :- n(X), not hasout(X).
                cut(X) :- n(X), not reach(X), not sink(X).
                """);
            for (int i = 0; i < n; i++) sb.append("n(").append(i).append(").\n");
            for (int i = 0; i < n * 2; i++) {
                int a = rnd.nextInt(n), b = rnd.nextInt(n);
                if (!adj[a][b]) sb.append("e(").append(a).append(',').append(b).append(").\n");
                adj[a][b] = true;
            }
            boolean[] seen = new boolean[n];
            Deque<Integer> dq = new ArrayDeque<>(List.of(0));
            seen[0] = true;
            while (!dq.isEmpty()) {
                int x = dq.poll();
                for (int y = 0; y < n; y++) if (adj[x][y] && !seen[y]) { seen[y] = true; dq.add(y); }
            }
            Set<List<String>> sink = new HashSet<>(), cut = new HashSet<>();
            for (int x = 0; x < n; x++) {
                boolean out = false;
                for (int y = 0; y < n; y++) out |= adj[x][y];
                if (!out) sink.add(List.of("" + x));
                if (!seen[x] && out) cut.add(List.of("" + x));
            }
            Engine e = new Engine(Parser.parse(sb.toString()));
            for (Engine.Mode m : Engine.Mode.values()) {
                Model model = e.evaluate(m);
                assertEquals(sink, model.getOrDefault("sink", Set.of()), "sink " + trial + m + "\n" + sb);
                assertEquals(cut, model.getOrDefault("cut", Set.of()), "cut " + trial + m + "\n" + sb);
            }
        }
    }

    /** Random positive+stratified programs over a small domain; naive and semi-naive must agree. */
    @Test
    void naiveEqualsSemiNaiveOnRandomPrograms() {
        Random rnd = new Random(5150);
        int nonEmpty = 0, withNeg = 0;
        for (int trial = 0; trial < 150; trial++) {
            String src = RandomProgram.generate(rnd);
            Engine e = new Engine(Parser.parse(src));
            Model a = e.evaluate(Engine.Mode.NAIVE);
            Model b = e.evaluate(Engine.Mode.SEMI_NAIVE);
            assertEquals(a, b, src);
            if (a.values().stream().anyMatch(s -> !s.isEmpty())) nonEmpty++;
            if (src.contains("not ")) withNeg++;
        }
        assertTrue(nonEmpty > 140, "nonEmpty=" + nonEmpty);
        assertTrue(withNeg > 40, "withNeg=" + withNeg);
    }

    @Test
    void generatedProgramsAreValid() {
        Random rnd = new Random(1);
        for (int i = 0; i < 200; i++) assertDoesNotThrow(() -> new Engine(Parser.parse(RandomProgram.generate(rnd))));
    }
}
