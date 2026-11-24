package ae.elevator;

/** Travel direction of a car or a hall call. */
public enum Direction {
    UP, DOWN;

    /** Direction needed to get from {@code from} to {@code to}; the floors must differ. */
    static Direction between(int from, int to) {
        return to > from ? UP : DOWN;
    }
}
