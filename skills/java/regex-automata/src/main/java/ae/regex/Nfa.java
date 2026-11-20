package ae.regex;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/** Thompson NFA with epsilon edges and {@link CharSet}-labelled edges; one start and one accept state. */
public final class Nfa {
    /** Guard against patterns like {@code (a{1000}){1000}} that would expand to millions of states. */
    static final int MAX_STATES = 200_000;

    record Edge(CharSet set, int to) { }

    private final List<List<Integer>> eps = new ArrayList<>();
    private final List<List<Edge>> edges = new ArrayList<>();
    private int start;
    private int accept;

    private Nfa() { }

    public static Nfa build(Node root) {
        Nfa n = new Nfa();
        int[] f = n.frag(root);
        n.start = f[0];
        n.accept = f[1];
        return n;
    }

    int start() {
        return start;
    }

    int accept() {
        return accept;
    }

    int stateCount() {
        return eps.size();
    }

    List<Edge> edgesOf(int state) {
        return edges.get(state);
    }

    private int newState() {
        if (eps.size() >= MAX_STATES) {
            throw new RegexSyntaxException("pattern too large (more than " + MAX_STATES + " NFA states)", 0);
        }
        eps.add(new ArrayList<>());
        edges.add(new ArrayList<>());
        return eps.size() - 1;
    }

    private void epsilon(int from, int to) {
        eps.get(from).add(to);
    }

    /** Builds a fragment and returns {start, end}. */
    private int[] frag(Node n) {
        switch (n) {
            case Node.Empty e -> {
                int s = newState();
                return new int[] {s, s};
            }
            case Node.Lit l -> {
                int s = newState();
                int t = newState();
                edges.get(s).add(new Edge(l.set(), t));
                return new int[] {s, t};
            }
            case Node.Cat c -> {
                int[] a = frag(c.left());
                int[] b = frag(c.right());
                epsilon(a[1], b[0]);
                return new int[] {a[0], b[1]};
            }
            case Node.Alt a -> {
                int s = newState();
                int e = newState();
                int[] x = frag(a.left());
                int[] y = frag(a.right());
                epsilon(s, x[0]);
                epsilon(s, y[0]);
                epsilon(x[1], e);
                epsilon(y[1], e);
                return new int[] {s, e};
            }
            case Node.Star st -> {
                int s = newState();
                int e = newState();
                int[] in = frag(st.body());
                epsilon(s, e);
                epsilon(s, in[0]);
                epsilon(in[1], in[0]);
                epsilon(in[1], e);
                return new int[] {s, e};
            }
            case Node.Repeat r -> {
                int s = newState();
                int cur = s;
                for (int i = 0; i < r.min(); i++) {
                    int[] f = frag(r.body());
                    epsilon(cur, f[0]);
                    cur = f[1];
                }
                int e = newState();
                if (r.max() == -1) {
                    int[] f = frag(new Node.Star(r.body()));
                    epsilon(cur, f[0]);
                    epsilon(f[1], e);
                } else {
                    for (int i = r.min(); i < r.max(); i++) {
                        epsilon(cur, e); // may stop before this optional copy
                        int[] f = frag(r.body());
                        epsilon(cur, f[0]);
                        cur = f[1];
                    }
                    epsilon(cur, e);
                }
                return new int[] {s, e};
            }
        }
    }

    /** Adds every state reachable through epsilon edges to {@code set}. */
    BitSet closure(BitSet set) {
        ArrayDeque<Integer> work = new ArrayDeque<>();
        for (int i = set.nextSetBit(0); i >= 0; i = set.nextSetBit(i + 1)) {
            work.push(i);
        }
        while (!work.isEmpty()) {
            for (int t : eps.get(work.pop())) {
                if (!set.get(t)) {
                    set.set(t);
                    work.push(t);
                }
            }
        }
        return set;
    }

    /** Direct simulation: the reference implementation the DFA is checked against. */
    public boolean matches(String input) {
        BitSet cur = new BitSet();
        cur.set(start);
        closure(cur);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            BitSet next = new BitSet();
            for (int s = cur.nextSetBit(0); s >= 0; s = cur.nextSetBit(s + 1)) {
                for (Edge e : edges.get(s)) {
                    if (e.set().contains(c)) {
                        next.set(e.to());
                    }
                }
            }
            if (next.isEmpty()) {
                return false;
            }
            cur = closure(next);
        }
        return cur.get(accept);
    }
}
