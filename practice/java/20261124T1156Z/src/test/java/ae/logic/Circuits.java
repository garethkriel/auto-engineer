package ae.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Test fixtures: adder netlists and a random circuit generator. */
final class Circuits {
    private Circuits() {}

    private static String a(int i) { return "a" + i; }
    private static String b(int i) { return "b" + i; }
    private static String header(int n) {
        StringBuilder sb = new StringBuilder("input");
        for (int i = 0; i < n; i++) sb.append(' ').append(a(i));
        for (int i = 0; i < n; i++) sb.append(' ').append(b(i));
        sb.append(" cin\noutput");
        for (int i = 0; i < n; i++) sb.append(" s").append(i);
        return sb.append(" cout\n").toString();
    }
    /** Name of carry INTO bit i: cin for 0, cout for n, c{i} otherwise. */
    private static String carry(int i, int n) { return i == 0 ? "cin" : i == n ? "cout" : "c" + i; }

    /** Ripple-carry adder, all gates unit delay. */
    static String ripple(int n) {
        StringBuilder sb = new StringBuilder(header(n));
        for (int i = 0; i < n; i++) {
            sb.append("p").append(i).append(" = XOR ").append(a(i)).append(' ').append(b(i)).append('\n');
            sb.append("g").append(i).append(" = AND ").append(a(i)).append(' ').append(b(i)).append('\n');
            sb.append("s").append(i).append(" = XOR p").append(i).append(' ').append(carry(i, n)).append('\n');
            sb.append("t").append(i).append(" = AND p").append(i).append(' ').append(carry(i, n)).append('\n');
            sb.append(carry(i + 1, n)).append(" = OR g").append(i).append(" t").append(i).append('\n');
        }
        return sb.toString();
    }

    /** Flat carry-lookahead adder: every carry is one wide OR of ANDs. */
    static String lookahead(int n) {
        StringBuilder sb = new StringBuilder(header(n));
        for (int i = 0; i < n; i++) {
            sb.append("p").append(i).append(" = XOR ").append(a(i)).append(' ').append(b(i)).append('\n');
            sb.append("g").append(i).append(" = AND ").append(a(i)).append(' ').append(b(i)).append('\n');
            sb.append("s").append(i).append(" = XOR p").append(i).append(' ').append(carry(i, n)).append('\n');
        }
        for (int k = 0; k < n; k++) {
            List<String> terms = new ArrayList<>();
            for (int j = k; j >= 0; j--) {
                if (j == k) { terms.add("g" + k); continue; }
                StringBuilder and = new StringBuilder("k" + (k + 1) + "_" + j + " = AND g" + j);
                for (int m = j + 1; m <= k; m++) and.append(" p").append(m);
                sb.append(and).append('\n');
                terms.add("k" + (k + 1) + "_" + j);
            }
            StringBuilder and = new StringBuilder("k" + (k + 1) + "_c = AND");
            for (int m = 0; m <= k; m++) and.append(" p").append(m);
            sb.append(and).append(" cin\n");
            terms.add("k" + (k + 1) + "_c");
            sb.append(carry(k + 1, n)).append(" = OR ").append(String.join(" ", terms)).append('\n');
        }
        return sb.toString();
    }

    /** Random circuit text; gates g0.. only use earlier gates, inputs i0.. and DFF outputs q0... */
    static String random(Random r, int inputs, int gates, int dffs) {
        StringBuilder sb = new StringBuilder();
        List<String> pool = new ArrayList<>();
        sb.append("input");
        for (int i = 0; i < inputs; i++) { sb.append(" i").append(i); pool.add("i" + i); }
        sb.append('\n');
        List<String> all = new ArrayList<>(pool);
        for (int i = 0; i < dffs; i++) all.add("q" + i);
        for (int i = 0; i < gates; i++) all.add("g" + i);
        for (int i = 0; i < dffs; i++) {
            sb.append("q").append(i).append(" = DFF ").append(all.get(r.nextInt(all.size())))
              .append(" @").append(r.nextInt(3)).append('\n');
            pool.add("q" + i);
        }
        String[] ops = {"AND", "OR", "XOR", "NAND", "NOR", "XNOR", "NOT", "BUF", "MUX", "AND", "XOR"};
        for (int i = 0; i < gates; i++) {
            String op = ops[r.nextInt(ops.length)];
            int arity = switch (op) { case "NOT", "BUF" -> 1; case "MUX" -> 3; default -> 2 + r.nextInt(2); };
            sb.append("g").append(i).append(" = ").append(op);
            for (int k = 0; k < arity; k++) sb.append(' ').append(pool.get(r.nextInt(pool.size())));
            sb.append(" @").append(r.nextInt(5)).append('\n');
            pool.add("g" + i);
        }
        sb.append("output");
        for (int i = Math.max(0, gates - 3); i < gates; i++) sb.append(" g").append(i);
        for (int i = 0; i < dffs; i++) sb.append(" q").append(i);
        return sb.append('\n').toString();
    }
}
