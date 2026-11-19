package ae.deflate;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/** Shared deterministic fixtures. */
final class Samples {
    private Samples() {}

    static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] random(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    static byte[] text(int n, long seed) {
        String[] words = {"the", "quick", "brown", "fox", "jumps", "over", "lazy", "dog", "stream", "block", "window", "match"};
        Random r = new Random(seed);
        StringBuilder sb = new StringBuilder();
        while (sb.length() < n) {
            sb.append(words[r.nextInt(words.length)]).append(r.nextInt(5) == 0 ? ".\n" : " ");
        }
        return ascii(sb.substring(0, n));
    }

    /** Low-entropy bytes (4 symbols) so that matches and literals mix. */
    static byte[] lowEntropy(int n, long seed) {
        Random r = new Random(seed);
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) ('a' + r.nextInt(4));
        }
        return b;
    }

    static Map<String, byte[]> corpus() {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("empty", new byte[0]);
        m.put("one byte", new byte[] {42});
        m.put("two bytes", ascii("ab"));
        m.put("hello", ascii("hello"));
        m.put("zeros 100000", new byte[100_000]);
        m.put("abc repeated", ascii("abc".repeat(5000)));
        m.put("random 70000", random(70_000, 1));
        m.put("text 50000", text(50_000, 2));
        m.put("low entropy 40000", lowEntropy(40_000, 3));
        byte[] mixed = new byte[90_000];
        System.arraycopy(text(30_000, 4), 0, mixed, 0, 30_000);
        System.arraycopy(random(30_000, 5), 0, mixed, 30_000, 30_000);
        System.arraycopy(text(30_000, 4), 0, mixed, 60_000, 30_000);
        m.put("text+random+text", mixed);
        return m;
    }
}
