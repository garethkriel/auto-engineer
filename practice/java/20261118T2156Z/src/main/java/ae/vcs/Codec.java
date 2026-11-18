package ae.vcs;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Canonical raw encodings: {@code kind SP length NUL payload}. */
final class Codec {
    static final String BLOB = "blob";
    static final String TREE = "tree";
    static final String COMMIT = "commit";

    /** Sorts by unsigned UTF-8 bytes, the order used in encoded trees. */
    static final Comparator<String> PATH_ORDER = (a, b) ->
        Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));

    /** A decoded raw object. */
    record Decoded(String kind, byte[] payload) { }

    private Codec() { }

    static byte[] raw(String kind, byte[] payload) {
        byte[] head = (kind + " " + payload.length + "\0").getBytes(StandardCharsets.US_ASCII);
        byte[] out = Arrays.copyOf(head, head.length + payload.length);
        System.arraycopy(payload, 0, out, head.length, payload.length);
        return out;
    }

    static Decoded decode(byte[] raw) {
        int nul = -1;
        for (int i = 0; i < raw.length && i < 32; i++) {
            if (raw[i] == 0) {
                nul = i;
                break;
            }
        }
        if (nul < 0) {
            throw new CorruptObjectException("no header terminator");
        }
        String[] head = new String(raw, 0, nul, StandardCharsets.US_ASCII).split(" ", -1);
        if (head.length != 2) {
            throw new CorruptObjectException("bad header");
        }
        int len;
        try {
            len = Integer.parseInt(head[1]);
        } catch (NumberFormatException e) {
            throw new CorruptObjectException("bad length");
        }
        if (len < 0 || len != raw.length - nul - 1) {
            throw new CorruptObjectException("length mismatch");
        }
        return new Decoded(head[0], Arrays.copyOfRange(raw, nul + 1, raw.length));
    }

    static void checkPath(String path) {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.endsWith("/")
            || path.indexOf('\n') >= 0 || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("bad path: " + path);
        }
        for (String seg : path.split("/", -1)) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) {
                throw new IllegalArgumentException("bad path: " + path);
            }
        }
    }

    /** First path that is a proper directory prefix of another path, or null. */
    static String findFileDirectoryClash(Iterable<String> sortedPaths) {
        List<String> all = new ArrayList<>();
        sortedPaths.forEach(all::add);
        java.util.Set<String> set = new java.util.HashSet<>(all);
        for (String p : all) {
            for (int i = p.indexOf('/'); i >= 0; i = p.indexOf('/', i + 1)) {
                if (set.contains(p.substring(0, i))) {
                    return p.substring(0, i);
                }
            }
        }
        return null;
    }

    static byte[] encodeTree(Map<String, ObjectId> entries) {
        TreeMap<String, ObjectId> sorted = new TreeMap<>(PATH_ORDER);
        sorted.putAll(entries);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (var e : sorted.entrySet()) {
            checkPath(e.getKey());
            out.writeBytes(e.getValue().hexBytes());
            out.write(' ');
            out.writeBytes(e.getKey().getBytes(StandardCharsets.UTF_8));
            out.write('\n');
        }
        return out.toByteArray();
    }

    static TreeMap<String, ObjectId> decodeTree(byte[] payload) {
        TreeMap<String, ObjectId> out = new TreeMap<>(PATH_ORDER);
        String text = new String(payload, StandardCharsets.UTF_8);
        if (text.isEmpty()) {
            return out;
        }
        if (!text.endsWith("\n")) {
            throw new CorruptObjectException("tree not newline terminated");
        }
        for (String line : text.substring(0, text.length() - 1).split("\n", -1)) {
            if (line.length() < 66 || line.charAt(64) != ' ') {
                throw new CorruptObjectException("bad tree line");
            }
            try {
                String path = line.substring(65);
                checkPath(path);
                if (out.put(path, new ObjectId(line.substring(0, 64))) != null) {
                    throw new CorruptObjectException("duplicate path " + path);
                }
            } catch (IllegalArgumentException e) {
                throw new CorruptObjectException("bad tree entry: " + e.getMessage());
            }
        }
        return out;
    }

    static byte[] encodeCommit(Commit c) {
        StringBuilder sb = new StringBuilder();
        sb.append("tree ").append(c.tree().hex()).append('\n');
        for (ObjectId p : c.parents()) {
            sb.append("parent ").append(p.hex()).append('\n');
        }
        sb.append("author ").append(c.author()).append('\n');
        sb.append("time ").append(c.time()).append("\n\n");
        sb.append(c.message());
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    static Commit decodeCommit(byte[] payload) {
        String text = new String(payload, StandardCharsets.UTF_8);
        int blank = text.indexOf("\n\n");
        if (blank < 0) {
            throw new CorruptObjectException("commit without blank line");
        }
        ObjectId tree = null;
        List<ObjectId> parents = new ArrayList<>();
        String author = null;
        Long time = null;
        try {
            for (String line : text.substring(0, blank).split("\n", -1)) {
                int sp = line.indexOf(' ');
                if (sp < 0) {
                    throw new CorruptObjectException("bad commit header line");
                }
                String key = line.substring(0, sp);
                String val = line.substring(sp + 1);
                switch (key) {
                    case "tree" -> tree = new ObjectId(val);
                    case "parent" -> parents.add(new ObjectId(val));
                    case "author" -> author = val;
                    case "time" -> time = Long.parseLong(val);
                    default -> throw new CorruptObjectException("unknown header " + key);
                }
            }
        } catch (IllegalArgumentException e) {
            throw new CorruptObjectException("bad commit header: " + e.getMessage());
        }
        if (tree == null || author == null || time == null) {
            throw new CorruptObjectException("missing commit header");
        }
        return new Commit(tree, parents, author, time, text.substring(blank + 2));
    }
}
