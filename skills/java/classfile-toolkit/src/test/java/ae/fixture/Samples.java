package ae.fixture;

import java.util.List;
import java.util.function.IntSupplier;

/** Fixture compiled by Maven: switches, wide constants, try/catch, lambdas, loops, monitors, arrays. */
public class Samples implements Comparable<Samples> {
    public static final long BIG = 0x1234_5678_9ABCL;
    public static final double PI2 = 6.283185307179586;
    private final int value;
    protected volatile String label = "kéy\0😀";

    public Samples(int value) {
        this.value = value;
    }

    @Override
    public int compareTo(Samples o) {
        return Integer.compare(value, o.value);
    }

    public static String table(int x) {
        switch (x) {
            case 1: return "one";
            case 2: return "two";
            case 3: return "three";
            case 4: return "four";
            default: return "many";
        }
    }

    public static int sparse(int x) {
        switch (x) {
            case -1000: return 1;
            case 7: return 2;
            case 100000: return 3;
            default: return 0;
        }
    }

    public static long mix(long a, double b, float c, int d) {
        long r = a * 3 + (long) (b / 2) - (long) c;
        for (int i = 0; i < d; i++) {
            r ^= (r << 3) + i;
        }
        return r + BIG + (long) PI2 + 70000 + 32000 + 100;
    }

    public int guarded(List<String> in) {
        int total = 0;
        try {
            for (String s : in) {
                total += Integer.parseInt(s);
            }
        } catch (NumberFormatException e) {
            total = -1;
        } finally {
            total++;
        }
        synchronized (this) {
            total += value;
        }
        return total;
    }

    public IntSupplier lambda(int k) {
        return () -> k + value;
    }

    public static int[][] grid(int n) {
        int[][] g = new int[n][n];
        long[] l = new long[2];
        Object o = l;
        if (o instanceof long[] arr && arr.length == 2) {
            g[0] = new int[] {1, 2, 3};
        }
        return g;
    }

    public static String text(Object o, int i) {
        return "v=" + o + i + (o == null ? 'n' : 'x');
    }

    abstract static class Shape {
        abstract double area();
    }

    interface Walker {
        void walk(int steps) throws java.io.IOException;

        default int twice(int s) {
            return s * 2;
        }
    }
}
