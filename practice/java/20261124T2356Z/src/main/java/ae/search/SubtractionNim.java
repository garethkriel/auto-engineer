package ae.search;

/**
 * Subtraction game: a pile of stones, each turn take 1..maxTake stones. In the normal variant the player who
 * takes the last stone wins; in the misere variant that player loses.
 */
public final class SubtractionNim implements Position {
    private final int stones;
    private final int maxTake;
    private final boolean misere;
    private final int ply;

    public SubtractionNim(int stones, int maxTake, boolean misere) {
        this(stones, maxTake, misere, 0);
    }

    private SubtractionNim(int stones, int maxTake, boolean misere, int ply) {
        if (stones < 0) {
            throw new IllegalArgumentException("negative pile: " + stones);
        }
        if (maxTake < 1) {
            throw new IllegalArgumentException("maxTake must be at least 1: " + maxTake);
        }
        this.stones = stones;
        this.maxTake = maxTake;
        this.misere = misere;
        this.ply = ply;
    }

    @Override
    public int[] moves() {
        int n = Math.min(stones, maxTake);
        int[] ms = new int[n];
        for (int i = 0; i < n; i++) {
            ms[i] = i + 1;
        }
        return ms;
    }

    @Override
    public Position play(int move) {
        if (move < 1 || move > maxTake || move > stones) {
            throw new IllegalArgumentException("illegal take " + move + " from " + stones);
        }
        return new SubtractionNim(stones - move, maxTake, misere, ply + 1);
    }

    @Override
    public Status status() {
        if (stones > 0) {
            return Status.ONGOING;
        }
        // The previous player took the last stone: the mover has lost (normal) or won (misere).
        return misere ? Status.MOVER_WON : Status.MOVER_LOST;
    }

    @Override
    public int heuristic() {
        return 0;
    }

    @Override
    public long key() {
        // Terminal scores depend on the ply, so the ply is part of the key.
        return stones * 1_000_003L + ply;
    }

    @Override
    public int ply() {
        return ply;
    }
}
