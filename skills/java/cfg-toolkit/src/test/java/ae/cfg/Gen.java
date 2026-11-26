package ae.cfg;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Seeded random program generators. */
final class Gen {
    private Gen() { }

    private static final String[] OPS = {"add", "sub", "mul", "lt", "eq"};

    /**
     * Structured programs: straight-line code, if/else and counter-bounded while loops (so every program terminates
     * well inside the step budget). Variables v0..v(m-1) are all assigned in the entry block, so no read is ever
     * undefined. Loop counters/bounds are fresh variables defined right before their loop.
     */
    static String structured(Random rnd, int params, int vars) {
        return new Structured(rnd, params, vars).build();
    }

    private static final class Structured {
        final Random rnd;
        final int params;
        final int vars;
        final StringBuilder sb = new StringBuilder();
        int labels;
        int loops;

        Structured(Random rnd, int params, int vars) {
            this.rnd = rnd;
            this.params = params;
            this.vars = vars;
        }

        String v() {
            return "v" + rnd.nextInt(vars);
        }

        String operand() {
            return rnd.nextInt(5) == 0 ? "p" + rnd.nextInt(Math.max(1, params)) : v();
        }

        void line(String s) {
            sb.append("  ").append(s).append('\n');
        }

        void label(String l) {
            sb.append(l).append(":\n");
        }

        void assign() {
            switch (rnd.nextInt(4)) {
                case 0 -> line(v() + " = const " + (rnd.nextInt(21) - 10));
                case 1 -> line(v() + " = copy " + operand());
                default -> line(v() + " = " + OPS[rnd.nextInt(OPS.length)] + " " + operand() + ", " + operand());
            }
        }

        void stmts(int depth, int count) {
            for (int i = 0; i < count; i++) {
                int pick = depth <= 0 ? 0 : rnd.nextInt(6);
                if (pick <= 3) {
                    assign();
                } else if (pick == 4) {
                    ifElse(depth);
                } else {
                    whileLoop(depth);
                }
            }
        }

        void ifElse(int depth) {
            int id = labels++;
            String c = "c" + id;
            line(c + " = " + OPS[3 + rnd.nextInt(2)] + " " + operand() + ", " + operand());
            line("br " + c + ", T" + id + ", E" + id);
            label("T" + id);
            stmts(depth - 1, 1 + rnd.nextInt(3));
            line("jmp J" + id);
            label("E" + id);
            if (rnd.nextBoolean()) {
                stmts(depth - 1, 1 + rnd.nextInt(2));
            }
            line("jmp J" + id);
            label("J" + id);
        }

        void whileLoop(int depth) {
            int id = labels++;
            String i = "i" + id;
            String bound = "n" + id;
            String one = "o" + id;
            line(i + " = const 0");
            line(bound + " = const " + (1 + rnd.nextInt(3)));
            line(one + " = const 1");
            line("jmp H" + id);
            label("H" + id);
            line("k" + id + " = lt " + i + ", " + bound);
            line("br k" + id + ", B" + id + ", X" + id);
            label("B" + id);
            stmts(depth - 1, 1 + rnd.nextInt(3));
            line(i + " = add " + i + ", " + one);
            line("jmp H" + id);
            label("X" + id);
        }

        String build() {
            StringBuilder head = new StringBuilder("func s(");
            for (int i = 0; i < params; i++) {
                head.append(i == 0 ? "" : ", ").append('p').append(i);
            }
            head.append(")\nentry:\n");
            sb.append(head);
            for (int i = 0; i < vars; i++) {
                line("v" + i + (params > 0 && rnd.nextBoolean() ? " = copy p" + rnd.nextInt(params)
                        : " = const " + (rnd.nextInt(7) - 3)));
            }
            stmts(3, 3 + rnd.nextInt(6));
            String acc = "v0";
            for (int i = 1; i < vars; i++) {
                if (rnd.nextBoolean()) {
                    line("t" + i + " = add " + acc + ", v" + i);
                    acc = "t" + i;
                }
            }
            line("ret " + acc);
            return sb.toString();
        }
    }

    /**
     * Arbitrary (possibly irreducible, possibly with unreachable blocks) control-flow graphs of 1..9 blocks. Every
     * block has a few random instructions over {a, x, y, z}; x, y, z are defined by the first instruction of block 0
     * so the text is valid, while reads can still be "undefined" at run time (these programs are only analysed).
     */
    static String randomCfg(Random rnd) {
        int n = 1 + rnd.nextInt(9);
        String[] vars = {"a", "x", "y", "z"};
        StringBuilder sb = new StringBuilder("func r(a)\n");
        for (int b = 0; b < n; b++) {
            sb.append("b").append(b).append(":\n");
            if (b == 0) {
                sb.append("  x = const 1\n  y = const 2\n  z = const 3\n");
            }
            int count = rnd.nextInt(4);
            for (int i = 0; i < count; i++) {
                String d = vars[1 + rnd.nextInt(3)];
                if (rnd.nextInt(3) == 0) {
                    sb.append("  ").append(d).append(" = copy ").append(vars[rnd.nextInt(4)]).append('\n');
                } else {
                    sb.append("  ").append(d).append(" = add ").append(vars[rnd.nextInt(4)]).append(", ")
                            .append(vars[rnd.nextInt(4)]).append('\n');
                }
            }
            switch (rnd.nextInt(4)) {
                case 0 -> sb.append("  ret ").append(vars[rnd.nextInt(4)]).append('\n');
                case 1 -> sb.append("  jmp b").append(rnd.nextInt(n)).append('\n');
                default -> sb.append("  br ").append(vars[rnd.nextInt(4)]).append(", b").append(rnd.nextInt(n))
                        .append(", b").append(rnd.nextInt(n)).append('\n');
            }
        }
        return sb.toString();
    }

    static List<long[]> argSets(Random rnd, int params, int count) {
        List<long[]> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long[] a = new long[params];
            for (int j = 0; j < params; j++) {
                a[j] = rnd.nextInt(15) - 7;
            }
            out.add(a);
        }
        return out;
    }
}
