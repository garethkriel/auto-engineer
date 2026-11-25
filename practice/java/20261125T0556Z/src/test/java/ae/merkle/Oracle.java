package ae.merkle;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Independent from-scratch root computation, written without any MerkleMap code. */
final class Oracle {
    private Oracle() {}

    static byte[] sha(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) {
                md.update(p);
            }
            return md.digest();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    static String path(String key) {
        byte[] h = sha(key.getBytes(StandardCharsets.UTF_8));
        String hex = "0123456789abcdef";
        return "" + hex.charAt((h[0] >> 4) & 15) + hex.charAt(h[0] & 15)
                + hex.charAt((h[1] >> 4) & 15) + hex.charAt(h[1] & 15);
    }

    static byte[] int32(int v) {
        return new byte[] {(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    static byte[] bucketHash(Map<String, String> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0);
        for (Map.Entry<String, String> e : new TreeMap<>(entries).entrySet()) {
            byte[] k = e.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] v = e.getValue().getBytes(StandardCharsets.UTF_8);
            out.writeBytes(int32(k.length));
            out.writeBytes(k);
            out.writeBytes(int32(v.length));
            out.writeBytes(v);
        }
        return sha(out.toByteArray());
    }

    /** Hash of the subtree at {@code prefix} given all entries (recursive, no caching). */
    static byte[] hash(String prefix, Map<String, Map<String, String>> byPath) {
        if (prefix.length() == 4) {
            return bucketHash(byPath.getOrDefault(prefix, Map.of()));
        }
        List<byte[]> kids = new ArrayList<>();
        for (char c : "0123456789abcdef".toCharArray()) {
            kids.add(hash(prefix + c, byPath));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(1);
        for (byte[] k : kids) {
            out.writeBytes(k);
        }
        return sha(out.toByteArray());
    }

    static byte[] root(Map<String, String> content) {
        Map<String, Map<String, String>> byPath = new HashMap<>();
        for (Map.Entry<String, String> e : content.entrySet()) {
            byPath.computeIfAbsent(path(e.getKey()), p -> new TreeMap<>()).put(e.getKey(), e.getValue());
        }
        // Memoise empty subtrees implicitly by pruning: recursion over all 69905 nodes is acceptable but slow
        // when repeated, so only descend where entries exist.
        return fast("", byPath);
    }

    private static final Map<Integer, byte[]> EMPTY = new HashMap<>();

    private static byte[] empty(int depth) {
        byte[] cached = EMPTY.get(depth);
        if (cached != null) {
            return cached;
        }
        byte[] h;
        if (depth == 4) {
            h = bucketHash(Map.of());
        } else {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(1);
            for (int i = 0; i < 16; i++) {
                out.writeBytes(empty(depth + 1));
            }
            h = sha(out.toByteArray());
        }
        EMPTY.put(depth, h);
        return h;
    }

    private static byte[] fast(String prefix, Map<String, Map<String, String>> byPath) {
        boolean any = false;
        for (String p : byPath.keySet()) {
            if (p.startsWith(prefix)) {
                any = true;
                break;
            }
        }
        if (!any) {
            return empty(prefix.length());
        }
        if (prefix.length() == 4) {
            return bucketHash(byPath.get(prefix));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(1);
        for (char c : "0123456789abcdef".toCharArray()) {
            out.writeBytes(fast(prefix + c, byPath));
        }
        return sha(out.toByteArray());
    }
}
