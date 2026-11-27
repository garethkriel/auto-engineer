package ae.datalog;

import java.util.*;

/** Generates valid stratified programs: predicates are layered, negation only refers to strictly lower layers. */
final class RandomProgram {
    private RandomProgram() {}

    static String generate(Random rnd) {
        StringBuilder sb = new StringBuilder();
        int dom = 3 + rnd.nextInt(3);
        for (int i = 0; i < 2 * dom; i++)
            sb.append("b").append(rnd.nextInt(2)).append("(c").append(rnd.nextInt(dom)).append(",c").append(rnd.nextInt(dom)).append(").\n");
        // predicates d0..d3 (arity 2); layer(dK)=K/2 ; positive recursion allowed within the same layer or lower
        int preds = 2 + rnd.nextInt(3);
        for (int p = 0; p < preds; p++) {
            int rules = 1 + rnd.nextInt(2);
            for (int r = 0; r < rules; r++) sb.append(rule(rnd, p, preds)).append('\n');
        }
        return sb.toString();
    }

    private static String rule(Random rnd, int p, int preds) {
        int layer = p / 2;
        List<String> body = new ArrayList<>();
        int k = 1 + rnd.nextInt(3);
        String[] vars = {"X", "Y", "Z"};
        List<String> pos = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            String pred = rnd.nextBoolean() ? "b" + rnd.nextInt(2) : "d" + rnd.nextInt(Math.min(preds, 2 * layer + 2));
            String a = vars[rnd.nextInt(3)], b = vars[rnd.nextInt(3)];
            pos.add(a);
            pos.add(b);
            body.add(pred + "(" + a + "," + b + ")");
        }
        String h1 = pos.get(rnd.nextInt(pos.size())), h2 = pos.get(rnd.nextInt(pos.size()));
        if (layer > 0 && rnd.nextInt(3) > 0) {
            String lower = "d" + rnd.nextInt(2 * layer);
            body.add("not " + lower + "(" + h1 + "," + h2 + ")");
        }
        if (rnd.nextInt(4) == 0) body.add(h1 + " != " + h2);
        return "d" + p + "(" + h1 + "," + h2 + ") :- " + String.join(", ", body) + ".";
    }
}
