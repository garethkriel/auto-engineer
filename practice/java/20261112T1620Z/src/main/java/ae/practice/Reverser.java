package ae.practice;

/** Reverses strings. */
public final class Reverser {
    private Reverser() {}

    public static String reverse(String s) {
        if (s == null) {
            throw new IllegalArgumentException("null");
        }
        return new StringBuilder(s).reverse().toString();
    }
}
