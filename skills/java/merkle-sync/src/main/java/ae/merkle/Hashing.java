package ae.merkle;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/** Hash definitions shared by the tree, proofs and sync. */
final class Hashing {
    static final int DEPTH = 4;
    static final int FANOUT = 16;
    static final int HASH_BYTES = 32;
    static final byte LEAF_TAG = 0;
    static final byte INNER_TAG = 1;

    /** EMPTY[level] is the hash of a subtree at that level (0 = root, DEPTH = bucket) holding no entries. */
    static final byte[][] EMPTY = new byte[DEPTH + 1][];

    static {
        EMPTY[DEPTH] = leafHash(new java.util.TreeMap<>());
        for (int level = DEPTH - 1; level >= 0; level--) {
            byte[][] kids = new byte[FANOUT][];
            java.util.Arrays.fill(kids, EMPTY[level + 1]);
            EMPTY[level] = innerHash(kids);
        }
    }

    private Hashing() {}

    static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Bucket hash: tag, then length-prefixed key and value for every entry in key order. */
    static byte[] leafHash(java.util.SortedMap<String, String> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(LEAF_TAG);
        for (Map.Entry<String, String> e : entries.entrySet()) {
            byte[] k = e.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] v = e.getValue().getBytes(StandardCharsets.UTF_8);
            writeInt(out, k.length);
            out.writeBytes(k);
            writeInt(out, v.length);
            out.writeBytes(v);
        }
        return digest().digest(out.toByteArray());
    }

    static byte[] innerHash(byte[][] children) {
        if (children.length != FANOUT) {
            throw new IllegalArgumentException("expected " + FANOUT + " children");
        }
        MessageDigest md = digest();
        md.update(INNER_TAG);
        for (byte[] c : children) {
            md.update(c);
        }
        return md.digest();
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write(v >>> 24);
        out.write(v >>> 16);
        out.write(v >>> 8);
        out.write(v);
    }

    /** Hex path (DEPTH nibbles) of the bucket a key lives in. */
    static String pathOf(String key) {
        byte[] h = digest().digest(key.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(DEPTH);
        for (int i = 0; i < DEPTH; i++) {
            int b = h[i / 2] & 0xff;
            sb.append(Character.forDigit(i % 2 == 0 ? b >>> 4 : b & 15, 16));
        }
        return sb.toString();
    }
}
