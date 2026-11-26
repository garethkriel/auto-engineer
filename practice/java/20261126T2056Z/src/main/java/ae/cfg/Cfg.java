package ae.cfg;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Control-flow graph over the blocks of a function (block index = position in the function). */
public final class Cfg {
    private final Function fn;
    private final Map<String, Integer> index;
    private final List<List<Integer>> succ = new ArrayList<>();
    private final List<List<Integer>> pred = new ArrayList<>();
    private final List<Integer> rpo;
    private final boolean[] reachable;

    public Cfg(Function fn) {
        this.fn = fn;
        this.index = fn.labelIndex();
        int n = fn.blocks().size();
        for (int i = 0; i < n; i++) {
            succ.add(new ArrayList<>());
            pred.add(new ArrayList<>());
        }
        for (int b = 0; b < n; b++) {
            List<Instr> ins = fn.blocks().get(b).instrs();
            for (String t : ins.get(ins.size() - 1).targets()) {
                int s = index.get(t);
                if (!succ.get(b).contains(s)) {
                    succ.get(b).add(s);
                    pred.get(s).add(b);
                }
            }
        }
        this.reachable = new boolean[n];
        this.rpo = reversePostOrder(n);
        for (int b : rpo) {
            reachable[b] = true;
        }
    }

    /** Iterative DFS post-order from the entry, reversed. */
    private List<Integer> reversePostOrder(int n) {
        boolean[] seen = new boolean[n];
        int[] next = new int[n];
        List<Integer> post = new ArrayList<>();
        int[] stack = new int[n];
        int sp = 0;
        stack[sp++] = 0;
        seen[0] = true;
        while (sp > 0) {
            int b = stack[sp - 1];
            if (next[b] < succ.get(b).size()) {
                int s = succ.get(b).get(next[b]++);
                if (!seen[s]) {
                    seen[s] = true;
                    stack[sp++] = s;
                }
            } else {
                post.add(b);
                sp--;
            }
        }
        List<Integer> out = new ArrayList<>(post.reversed());
        return List.copyOf(out);
    }

    public Function function() {
        return fn;
    }

    public int size() {
        return fn.blocks().size();
    }

    public List<Integer> succ(int b) {
        return List.copyOf(succ.get(b));
    }

    public List<Integer> pred(int b) {
        return List.copyOf(pred.get(b));
    }

    public boolean reachable(int b) {
        return reachable[b];
    }

    /** Reachable blocks in reverse post-order; the entry comes first. */
    public List<Integer> rpo() {
        return rpo;
    }

    public int index(String label) {
        Integer i = index.get(label);
        if (i == null) {
            throw new IllegalArgumentException("unknown label " + label);
        }
        return i;
    }
}
