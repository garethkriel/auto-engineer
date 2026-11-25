package ae.search;

/** Game-tree node counting. */
public final class Perft {
    private Perft() { }

    /** Number of positions exactly {@code depth} plies below {@code p} (games that ended earlier contribute nothing). */
    public static long count(Position p, int depth) {
        if (depth < 0) {
            throw new IllegalArgumentException("negative depth: " + depth);
        }
        if (depth == 0) {
            return 1;
        }
        long total = 0;
        for (int m : p.moves()) {
            total += count(p.play(m), depth - 1);
        }
        return total;
    }

    /** Number of nodes of the whole game tree, the root included. */
    public static long nodes(Position p) {
        long total = 1;
        for (int m : p.moves()) {
            total += nodes(p.play(m));
        }
        return total;
    }

    /** Number of complete games (leaves of the tree). */
    public static long games(Position p) {
        int[] ms = p.moves();
        if (ms.length == 0) {
            return 1;
        }
        long total = 0;
        for (int m : ms) {
            total += games(p.play(m));
        }
        return total;
    }
}
