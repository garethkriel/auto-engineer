package ae.vcs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodecAndStoreTest {
    // SHA-256 literals computed independently with Python hashlib over "kind len\0payload".
    static final String BLOB_HELLO = "8aec4e4876f854f688d0ebfc8f37598f38e5fd6903cccc850ca36591175aeb60";
    static final String BLOB_EMPTY = "473a0f4c3be8a93681a267e3b1e9a7dcda1185436fe141f7749120a303721813";
    static final String TREE_EMPTY = "6ef19b41225c5369f1c104d45d8d85efa9b057b53b14b4b9b939dd74decc5321";
    static final String TREE_A_HELLO = "0cbf88725565cee21228fd8a480e3cac63891f3a5fd90a4f1dcc1cebf0e16601";

    @Test
    void blobIdsMatchKnownLiterals() {
        assertEquals(BLOB_HELLO, ObjectId.ofRaw(Codec.raw(Codec.BLOB, "hello".getBytes(StandardCharsets.UTF_8))).hex());
        assertEquals(BLOB_EMPTY, ObjectId.ofRaw(Codec.raw(Codec.BLOB, new byte[0])).hex());
    }

    @Test
    void treeIdsMatchKnownLiterals() {
        assertEquals(TREE_EMPTY, ObjectId.ofRaw(Codec.raw(Codec.TREE, Codec.encodeTree(Map.of()))).hex());
        byte[] t = Codec.encodeTree(Map.of("a.txt", new ObjectId(BLOB_HELLO)));
        assertEquals(TREE_A_HELLO, ObjectId.ofRaw(Codec.raw(Codec.TREE, t)).hex());
    }

    @Test
    void treeEncodingIgnoresInsertionOrderAndSortsByUtf8Bytes() {
        ObjectId x = new ObjectId(BLOB_HELLO);
        Map<String, ObjectId> one = new LinkedHashMap<>();
        Map<String, ObjectId> two = new LinkedHashMap<>();
        // UTF-8: U+FF5E = EF BD 9E sorts before U+1F600 = F0 9F 98 80; String.compareTo (UTF-16) says the opposite.
        for (String p : List.of("b", "～", "😀", "a/z", "a/b")) {
            one.put(p, x);
        }
        for (String p : List.of("a/b", "😀", "a/z", "～", "b")) {
            two.put(p, x);
        }
        assertArrayEquals(Codec.encodeTree(one), Codec.encodeTree(two));
        assertEquals(List.of("a/b", "a/z", "b", "～", "😀"),
            List.copyOf(Codec.decodeTree(Codec.encodeTree(one)).keySet()));
    }

    @Test
    void badPathsAreRejected() {
        ObjectId x = new ObjectId(BLOB_HELLO);
        for (String p : List.of("", "/a", "a/", "a//b", "a/./b", "../a", "a\nb", "a\0b")) {
            assertThrows(IllegalArgumentException.class, () -> Codec.encodeTree(Map.of(p, x)), p);
        }
    }

    @Test
    void commitRoundTripsWithAwkwardMessages() {
        ObjectId t = new ObjectId(TREE_EMPTY);
        ObjectId p1 = new ObjectId(BLOB_HELLO);
        ObjectId p2 = new ObjectId(BLOB_EMPTY);
        for (String msg : List.of("", "one line", "two\n\nparagraphs\n", "\n\n", "tree fake\nparent fake\n\nx", "ünï☃")) {
            Commit c = new Commit(t, List.of(p1, p2), "Ann <a@x>", -5L, msg);
            assertEquals(c, Codec.decodeCommit(Codec.encodeCommit(c)), "message: " + msg);
        }
        Commit root = new Commit(t, List.of(), "x", Long.MAX_VALUE, "m");
        assertEquals(root, Codec.decodeCommit(Codec.encodeCommit(root)));
    }

    @Test
    void authorWithNewlineIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new Commit(new ObjectId(TREE_EMPTY), List.of(), "a\nb", 0, "m"));
    }

    @Test
    void decodeRejectsMalformedRawObjects() {
        assertThrows(CorruptObjectException.class, () -> Codec.decode("blob 5".getBytes(StandardCharsets.US_ASCII)));
        assertThrows(CorruptObjectException.class, () -> Codec.decode("blob 5\0abc".getBytes(StandardCharsets.US_ASCII)));
        assertThrows(CorruptObjectException.class, () -> Codec.decode("blob x\0abc".getBytes(StandardCharsets.US_ASCII)));
        assertThrows(CorruptObjectException.class, () -> Codec.decode("blob -1\0".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("blob", Codec.decode("blob 3\0abc".getBytes(StandardCharsets.US_ASCII)).kind());
    }

    @Test
    void objectIdValidatesItsText() {
        assertThrows(IllegalArgumentException.class, () -> new ObjectId("abc"));
        assertThrows(IllegalArgumentException.class, () -> new ObjectId(BLOB_HELLO.toUpperCase()));
        assertEquals(new ObjectId(BLOB_HELLO), ObjectId.parse(BLOB_HELLO.toUpperCase() + "\n"));
    }

    @Test
    void memoryStoreRoundTripDedupAndTamperDetection() {
        MemoryObjectStore s = new MemoryObjectStore();
        byte[] raw = Codec.raw(Codec.BLOB, new byte[] {1, 2, 3});
        ObjectId id = s.put(raw);
        assertEquals(id, s.put(raw));
        raw[0] = 'x'; // caller mutation must not leak in
        assertArrayEquals(Codec.raw(Codec.BLOB, new byte[] {1, 2, 3}), s.get(id).orElseThrow());
        assertEquals(Optional.empty(), s.get(new ObjectId(BLOB_HELLO)));
        s.tamper(id, Codec.raw(Codec.BLOB, new byte[] {9}));
        assertThrows(CorruptObjectException.class, () -> s.get(id));
    }

    @Test
    void fileStoreRoundTripOverwriteAndNoTempFiles(@TempDir Path dir) throws IOException {
        FileObjectStore s = new FileObjectStore(dir.resolve("objs"));
        byte[] raw = Codec.raw(Codec.BLOB, "hello".getBytes(StandardCharsets.UTF_8));
        ObjectId id = s.put(raw);
        assertEquals(BLOB_HELLO, id.hex());
        assertEquals(id, s.put(raw));
        assertArrayEquals(raw, new FileObjectStore(dir.resolve("objs")).get(id).orElseThrow());
        assertTrue(Files.exists(dir.resolve("objs/8a/ec4e4876f854f688d0ebfc8f37598f38e5fd6903cccc850ca36591175aeb60")));
        try (Stream<Path> files = Files.walk(dir.resolve("objs"))) {
            assertEquals(List.of(), files.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().startsWith(".tmp-")).toList());
            assertEquals(1, Files.walk(dir.resolve("objs")).filter(Files::isRegularFile).count());
        }
        assertEquals(Optional.empty(), s.get(new ObjectId(BLOB_EMPTY)));
    }

    @Test
    void fileStoreDetectsEveryFlippedByteAndEveryTruncation(@TempDir Path dir) throws IOException {
        FileObjectStore s = new FileObjectStore(dir);
        byte[] raw = Codec.raw(Codec.BLOB, "some content".getBytes(StandardCharsets.UTF_8));
        ObjectId id = s.put(raw);
        Path file = s.pathOf(id);
        for (int i = 0; i < raw.length; i++) {
            byte[] bad = raw.clone();
            bad[i] ^= 0x01;
            Files.write(file, bad);
            assertThrows(CorruptObjectException.class, () -> s.get(id), "flip at " + i);
        }
        for (int len = 0; len < raw.length; len++) {
            Files.write(file, java.util.Arrays.copyOf(raw, len));
            assertThrows(CorruptObjectException.class, () -> s.get(id), "truncate to " + len);
        }
        Files.write(file, raw);
        assertArrayEquals(raw, s.get(id).orElseThrow());
    }

    @Test
    void fileRefsPersistOverwriteAndRejectBadNames(@TempDir Path dir) throws IOException {
        FileRefs r = new FileRefs(dir.resolve("refs"));
        assertEquals(Optional.empty(), r.get("main"));
        r.set("main", new ObjectId(BLOB_HELLO));
        r.set("dev", new ObjectId(BLOB_EMPTY));
        r.set("main", new ObjectId(TREE_EMPTY));
        FileRefs reopened = new FileRefs(dir.resolve("refs"));
        assertEquals(Optional.of(new ObjectId(TREE_EMPTY)), reopened.get("main"));
        assertEquals(List.of("dev", "main"), reopened.names());
        for (String bad : List.of("", ".hidden", "a/b", "..", "a b", "-x")) {
            assertThrows(IllegalArgumentException.class, () -> r.set(bad, new ObjectId(BLOB_HELLO)), bad);
        }
        Files.writeString(dir.resolve("refs/junk"), "not an id");
        assertThrows(CorruptObjectException.class, () -> reopened.get("junk"));
    }
}
