package ae.mc;

import java.util.*;

/** Test helpers: integer-node graphs as models plus brute-force oracles. */
final class RandomGraphs {
    private RandomGraphs() {}

    static Model<Integer> of(int n, int[][] edges, Set<Integer> init) {
        return new Model<>() {
            @Override
            public List<Integer> initial() {
                return new ArrayList<>(new TreeSet<>(init));
            }

            @Override
            public List<Move<Integer>> moves(Integer s) {
                List<Move<Integer>> out = new ArrayList<>();
                for (int[] e : edges) if (e[0] == s) out.add(new Move<>("e" + e[0] + "_" + e[1], e[1]));
                return out;
            }
        };
    }

    /** Distinct random edges (self loops allowed). */
    static int[][] randomEdges(Random rnd, int n, int count) {
        Set<Long> seen = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) seen.add((long) rnd.nextInt(n) * n + rnd.nextInt(n));
        int[][] out = new int[seen.size()][];
        int k = 0;
        for (long code : seen) out[k++] = new int[] {(int) (code / n), (int) (code % n)};
        return out;
    }

    static int outDegree(int[][] edges, int node) {
        int d = 0;
        for (int[] e : edges) if (e[0] == node) d++;
        return d;
    }

    /** Multi-source BFS distances; -1 if unreachable (Bellman-Ford style relaxation, independent of the checker). */
    static int[] distances(int n, int[][] edges, Set<Integer> init) {
        int[] dist = new int[n];
        Arrays.fill(dist, -1);
        for (int s : init) dist[s] = 0;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int[] e : edges) {
                if (dist[e[0]] >= 0 && (dist[e[1]] < 0 || dist[e[1]] > dist[e[0]] + 1)) {
                    dist[e[1]] = dist[e[0]] + 1;
                    changed = true;
                }
            }
        }
        return dist;
    }

    /**
     * Oracle for "eventually goal": greatest fixpoint of EG(not goal), with terminal states stuttering.
     * X = non-goal nodes; repeatedly drop nodes that have successors but none inside X.
     * A violation exists iff some initial node survives.
     */
    static boolean egViolation(int n, int[][] edges, Set<Integer> init, Set<Integer> goal) {
        boolean[] in = new boolean[n];
        for (int i = 0; i < n; i++) in[i] = !goal.contains(i);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < n; i++) {
                if (!in[i] || outDegree(edges, i) == 0) continue;
                boolean any = false;
                for (int[] e : edges) if (e[0] == i && in[e[1]]) any = true;
                if (!any) {
                    in[i] = false;
                    changed = true;
                }
            }
        }
        for (int s : init) if (in[s]) return true;
        return false;
    }
}
