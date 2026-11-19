package ae.vfs;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Differential test: random link-free operations vs a naive path-keyed model; fsck after every step. */
class VfsModelTest {
    /** Model: path -> content, or null value for a directory. Root "/" is always a dir. */
    static final class Model {
        final TreeMap<String, String> m = new TreeMap<>();

        Model() {
            m.put("/", null);
        }

        static String parent(String p) {
            int i = p.lastIndexOf('/');
            return i == 0 ? "/" : p.substring(0, i);
        }

        boolean isDir(String p) {
            return m.containsKey(p) && m.get(p) == null;
        }

        Errno parentCheck(String p) {
            String par = parent(p);
            // ancestors: first missing -> ENOENT, first file -> ENOTDIR
            String[] parts = par.equals("/") ? new String[0] : par.substring(1).split("/");
            String cur = "";
            for (String part : parts) {
                cur = cur + "/" + part;
                if (!m.containsKey(cur)) return Errno.ENOENT;
                if (!isDir(cur)) return Errno.ENOTDIR;
            }
            return null;
        }

        Errno mkdir(String p) {
            Errno e = parentCheck(p);
            if (e != null) return e;
            if (m.containsKey(p)) return Errno.EEXIST;
            m.put(p, null);
            return null;
        }

        Errno write(String p, String c) {
            Errno e = parentCheck(p);
            if (e != null) return e;
            if (isDir(p)) return Errno.EISDIR;
            m.put(p, c);
            return null;
        }

        Errno unlink(String p) {
            Errno e = parentCheck(p);
            if (e != null) return e;
            if (!m.containsKey(p)) return Errno.ENOENT;
            if (isDir(p)) return Errno.EISDIR;
            m.remove(p);
            return null;
        }

        boolean hasChildren(String p) {
            String pre = p.equals("/") ? "/" : p + "/";
            return m.keySet().stream().anyMatch(k -> !k.equals(p) && k.startsWith(pre));
        }

        Errno rmdir(String p) {
            Errno e = parentCheck(p);
            if (e != null) return e;
            if (!m.containsKey(p)) return Errno.ENOENT;
            if (!isDir(p)) return Errno.ENOTDIR;
            if (hasChildren(p)) return Errno.ENOTEMPTY;
            m.remove(p);
            return null;
        }

        Errno rename(String a, String b) {
            Errno e = parentCheck(a);
            if (e != null) return e;
            e = parentCheck(b);
            if (e != null) return e;
            if (!m.containsKey(a)) return Errno.ENOENT;
            if (a.equals(b)) return null;
            if (isDir(a) && b.startsWith(a + "/")) return Errno.EINVAL;
            if (m.containsKey(b)) {
                if (isDir(a) && !isDir(b)) return Errno.ENOTDIR;
                if (!isDir(a) && isDir(b)) return Errno.EISDIR;
                if (isDir(b) && hasChildren(b)) return Errno.ENOTEMPTY;
                m.remove(b);
            }
            List<String> moved = new ArrayList<>();
            for (String k : m.keySet()) if (k.equals(a) || k.startsWith(a + "/")) moved.add(k);
            List<String[]> items = new ArrayList<>();
            for (String k : moved) items.add(new String[] {b + k.substring(a.length()), m.get(k)});
            for (String k : moved) m.remove(k);
            for (String[] it : items) m.put(it[0], it[1]);
            return null;
        }

        List<String> list(String p) {
            String pre = p.equals("/") ? "/" : p + "/";
            List<String> out = new ArrayList<>();
            for (String k : m.keySet()) {
                if (k.length() > pre.length() && k.startsWith(pre) && k.indexOf('/', pre.length()) < 0) {
                    out.add(k.substring(pre.length()));
                }
            }
            return out;
        }
    }

    static final String[] NAMES = {"a", "b", "c"};

    static String randomPath(Random r) {
        int depth = 1 + r.nextInt(3);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append('/').append(NAMES[r.nextInt(NAMES.length)]);
        return sb.toString();
    }

    static Errno run(Runnable op) {
        try {
            op.run();
            return null;
        } catch (VfsException e) {
            return e.errno();
        }
    }

    @Test
    void randomOperationsMatchModel() {
        for (long seed = 1; seed <= 40; seed++) {
            Random r = new Random(seed);
            Vfs fs = new Vfs(1_000_000);
            Vfs.Session s = fs.root();
            Model model = new Model();
            int succeeded = 0;
            for (int step = 0; step < 300; step++) {
                String p = randomPath(r);
                String q = randomPath(r);
                String op;
                Errno expected;
                Errno actual;
                switch (r.nextInt(5)) {
                    case 0 -> {
                        op = "mkdir " + p;
                        expected = model.mkdir(p);
                        actual = run(() -> s.mkdir(p));
                    }
                    case 1 -> {
                        String c = "v" + step;
                        op = "write " + p + " " + c;
                        expected = model.write(p, c);
                        actual = run(() -> s.write(p, c.getBytes(StandardCharsets.UTF_8)));
                    }
                    case 2 -> {
                        op = "unlink " + p;
                        expected = model.unlink(p);
                        actual = run(() -> s.unlink(p));
                    }
                    case 3 -> {
                        op = "rmdir " + p;
                        expected = model.rmdir(p);
                        actual = run(() -> s.rmdir(p));
                    }
                    default -> {
                        op = "rename " + p + " " + q;
                        expected = model.rename(p, q);
                        actual = run(() -> s.rename(p, q));
                    }
                }
                String ctx = "seed " + seed + " step " + step + " " + op;
                assertEquals(expected, actual, ctx);
                if (expected == null) succeeded++;
                assertEquals(List.of(), fs.fsck(), ctx);
                // full-state comparison
                long bytes = 0;
                for (var e : model.m.entrySet()) {
                    String path = e.getKey();
                    if (e.getValue() == null) {
                        assertEquals(model.list(path), s.list(path), ctx + " list " + path);
                    } else {
                        assertEquals(e.getValue(), new String(s.read(path), StandardCharsets.UTF_8), ctx + " read " + path);
                        bytes += e.getValue().length();
                    }
                }
                assertEquals(bytes, fs.usedBytes(), ctx);
            }
            assertTrue(succeeded > 30, "seed " + seed + " exercised too few successful operations: " + succeeded);
        }
    }
}
