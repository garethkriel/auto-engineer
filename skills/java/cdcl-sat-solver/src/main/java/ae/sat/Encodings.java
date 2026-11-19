package ae.sat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Generators of CNF benchmark families. */
public final class Encodings {
    private Encodings() {
    }

    /** Variable (p*holes + h + 1): pigeon p sits in hole h. Every pigeon needs a hole, no hole is shared. */
    public static Cnf pigeonhole(int pigeons, int holes) {
        List<int[]> cs = new ArrayList<>();
        for (int p = 0; p < pigeons; p++) {
            int[] c = new int[holes];
            for (int h = 0; h < holes; h++) {
                c[h] = p * holes + h + 1;
            }
            cs.add(c);
        }
        for (int h = 0; h < holes; h++) {
            for (int a = 0; a < pigeons; a++) {
                for (int b = a + 1; b < pigeons; b++) {
                    cs.add(new int[] {-(a * holes + h + 1), -(b * holes + h + 1)});
                }
            }
        }
        return new Cnf(pigeons * holes, cs);
    }

    /** Variable (r*n + c + 1): a queen on row r, column c. Exactly one per row, at most one per column/diagonal. */
    public static Cnf queens(int n) {
        List<int[]> cs = new ArrayList<>();
        for (int r = 0; r < n; r++) {
            int[] row = new int[n];
            for (int c = 0; c < n; c++) {
                row[c] = r * n + c + 1;
            }
            cs.add(row);
        }
        for (int r1 = 0; r1 < n; r1++) {
            for (int c1 = 0; c1 < n; c1++) {
                for (int r2 = r1; r2 < n; r2++) {
                    for (int c2 = 0; c2 < n; c2++) {
                        if (r2 == r1 && c2 <= c1) {
                            continue;
                        }
                        boolean attack = r1 == r2 || c1 == c2 || Math.abs(r1 - r2) == Math.abs(c1 - c2);
                        if (attack) {
                            cs.add(new int[] {-(r1 * n + c1 + 1), -(r2 * n + c2 + 1)});
                        }
                    }
                }
            }
        }
        return new Cnf(n * n, cs);
    }

    /** Variable (v*colours + k + 1): vertex v has colour k. Each vertex gets a colour; edge ends differ. */
    public static Cnf colouring(int vertices, List<int[]> edges, int colours) {
        List<int[]> cs = new ArrayList<>();
        for (int v = 0; v < vertices; v++) {
            int[] c = new int[colours];
            for (int k = 0; k < colours; k++) {
                c[k] = v * colours + k + 1;
            }
            cs.add(c);
        }
        for (int[] e : edges) {
            for (int k = 0; k < colours; k++) {
                cs.add(new int[] {-(e[0] * colours + k + 1), -(e[1] * colours + k + 1)});
            }
        }
        return new Cnf(vertices * colours, cs);
    }

    /** Uniform random 3-SAT: each clause has three distinct variables with random signs. Seeded, so reproducible. */
    public static Cnf random3Sat(int vars, int clauses, long seed) {
        if (vars < 3) {
            throw new IllegalArgumentException("need at least 3 variables");
        }
        Random rnd = new Random(seed);
        List<int[]> cs = new ArrayList<>();
        for (int i = 0; i < clauses; i++) {
            int a = 1 + rnd.nextInt(vars);
            int b;
            do {
                b = 1 + rnd.nextInt(vars);
            } while (b == a);
            int c;
            do {
                c = 1 + rnd.nextInt(vars);
            } while (c == a || c == b);
            cs.add(new int[] {rnd.nextBoolean() ? a : -a, rnd.nextBoolean() ? b : -b, rnd.nextBoolean() ? c : -c});
        }
        return new Cnf(vars, cs);
    }
}
