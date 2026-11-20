package ae.suffix;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

/** Brute-force oracles shared by the tests. */
final class Naive {
    private Naive() {}

    static int[] suffixArray(int[] t) {
        Integer[] idx = new Integer[t.length];
        for (int i = 0; i < t.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> {
            int i = a, j = b;
            while (i < t.length && j < t.length) {
                if (t[i] != t[j]) return Integer.compare(t[i], t[j]);
                i++;
                j++;
            }
            return Integer.compare(t.length - a, t.length - b);
        });
        int[] r = new int[t.length];
        for (int i = 0; i < r.length; i++) r[i] = idx[i];
        return r;
    }

    static int[] ints(String s) {
        return s.chars().toArray();
    }

    static List<Integer> occurrences(String text, String p) {
        List<Integer> out = new ArrayList<>();
        for (int i = text.indexOf(p); i >= 0; i = text.indexOf(p, i + 1)) out.add(i);
        return out;
    }

    static TreeSet<String> distinct(String s) {
        TreeSet<String> set = new TreeSet<>();
        for (int i = 0; i < s.length(); i++)
            for (int j = i + 1; j <= s.length(); j++) set.add(s.substring(i, j));
        return set;
    }

    static String longestRepeated(String s) {
        String best = "";
        for (String sub : distinct(s)) {
            if (sub.length() > best.length() && occurrences(s, sub).size() >= 2) best = sub;
        }
        return best; // distinct() iterates ascending, so ties keep the smallest
    }

    static String longestCommon(String a, String b) {
        String best = "";
        for (String sub : distinct(a)) {
            if (sub.length() > best.length() && b.contains(sub)) best = sub;
        }
        return best;
    }

    static String random(Random r, int len, int alphabet) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append((char) ('a' + r.nextInt(alphabet)));
        return sb.toString();
    }
}
