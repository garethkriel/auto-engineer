package ae.arch;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Deterministic random archive contents whose names never conflict. */
final class TestData {
    private TestData() {}

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static ArchiveEntry file(String name, String content) {
        return new ArchiveEntry(name, bytes(content));
    }

    /** Mix of compressible and incompressible content, length 0..max. */
    static byte[] content(Random r, int max) {
        int n = r.nextInt(max + 1);
        byte[] b = new byte[n];
        if (r.nextBoolean()) {
            r.nextBytes(b);
        } else {
            for (int i = 0; i < n; i++) {
                b[i] = (byte) ('a' + (i / 7) % 3);
            }
        }
        return b;
    }

    /** Files are named "f{i}.ext" (unique), directories "d{0..2}" are separate names, so no file is ever a parent. */
    static List<ArchiveEntry> random(long seed, int count, int maxLen) {
        return random(seed, count, maxLen, true);
    }

    /** {@code unicode=false} keeps names ASCII for tests that touch the file system (the CI locale may be POSIX). */
    static List<ArchiveEntry> random(long seed, int count, int maxLen, boolean unicode) {
        Random r = new Random(seed);
        List<ArchiveEntry> out = new ArrayList<>();
        Set<String> dirs = new HashSet<>();
        String[] stems = unicode
            ? new String[] {"", "d0/", "d1/", "d0/s/", "naïve/", "日本/x/"}
            : new String[] {"", "d0/", "d1/", "d0/s/", "naive/", "jp/x/"};
        for (int i = 0; i < count; i++) {
            String stem = stems[r.nextInt(stems.length)];
            if (!stem.isEmpty() && dirs.add(stem) && r.nextBoolean()) {
                out.add(new ArchiveEntry(stem, new byte[0]));
            }
            out.add(new ArchiveEntry(stem + "f" + i + (r.nextBoolean() ? ".txt" : ""), content(r, maxLen)));
        }
        return out;
    }
}
