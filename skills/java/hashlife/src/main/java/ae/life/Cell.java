package ae.life;

/** A live-cell coordinate. {@code x} grows east, {@code y} grows south. Ordered by row, then column. */
public record Cell(long x, long y) implements Comparable<Cell> {
    @Override
    public int compareTo(Cell o) {
        int c = Long.compare(y, o.y);
        return c != 0 ? c : Long.compare(x, o.x);
    }
}
