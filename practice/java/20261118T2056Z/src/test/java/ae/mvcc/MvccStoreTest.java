package ae.mvcc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MvccStoreTest {
    private final MvccStore<String, String> store = new MvccStore<>();

    private void seed(String... kv) {
        Transaction<String, String> t = store.begin();
        for (int i = 0; i < kv.length; i += 2) {
            t.put(kv[i], kv[i + 1]);
        }
        t.commit();
    }

    private static List<String> keys(Map<String, String> m) {
        return List.copyOf(m.keySet());
    }

    @Test
    void readYourWritesAndInvisibleToOthersUntilCommit() {
        Transaction<String, String> a = store.begin();
        a.put("x", "1");
        assertEquals(Optional.of("1"), a.get("x"));
        Transaction<String, String> b = store.begin();
        assertEquals(Optional.empty(), b.get("x"));
        a.commit();
        assertEquals(Optional.empty(), b.get("x"), "b's snapshot predates a's commit");
        assertEquals(Optional.of("1"), store.begin().get("x"));
    }

    @Test
    void repeatableReadAcrossConcurrentCommit() {
        seed("x", "old");
        Transaction<String, String> reader = store.begin();
        assertEquals(Optional.of("old"), reader.get("x"));
        seed("x", "new");
        assertEquals(Optional.of("old"), reader.get("x"));
        assertEquals(Optional.of("new"), store.begin().get("x"));
    }

    @Test
    void deleteHidesKeyAndOldSnapshotStillSeesIt() {
        seed("x", "1");
        Transaction<String, String> old = store.begin();
        Transaction<String, String> d = store.begin();
        d.delete("x");
        assertEquals(Optional.empty(), d.get("x"));
        d.commit();
        assertEquals(Optional.of("1"), old.get("x"));
        assertEquals(Optional.empty(), store.begin().get("x"));
    }

    @Test
    void putAfterDeleteInSameTransactionWins() {
        seed("x", "1");
        Transaction<String, String> t = store.begin();
        t.delete("x");
        t.put("x", "2");
        t.commit();
        assertEquals(Optional.of("2"), store.begin().get("x"));
    }

    @Test
    void scanIsHalfOpenSortedAndMergesOwnWrites() {
        seed("a", "1", "b", "2", "c", "3", "d", "4");
        Transaction<String, String> t = store.begin();
        t.put("bb", "X");
        t.delete("c");
        assertEquals(List.of("a", "b", "bb"), keys(t.scan("a", "c")));
        assertEquals(List.of("a", "b", "bb", "d"), keys(t.scan("a", "z")));
        assertEquals("X", t.scan("a", "z").get("bb"));
        assertTrue(t.scan("c", "c").isEmpty(), "empty half-open range");
        assertTrue(t.scan("d", "a").isEmpty(), "inverted range is empty, not an error");
    }

    @Test
    void firstCommitterWinsOnSameKey() {
        seed("x", "0");
        Transaction<String, String> a = store.begin();
        Transaction<String, String> b = store.begin();
        a.put("x", "A");
        b.put("x", "B");
        a.commit();
        assertThrows(ConflictException.class, b::commit);
        assertFalse(b.isOpen());
        assertEquals(0, store.activeTransactions(), "a and b both released their snapshots");
        assertEquals(Optional.of("A"), store.begin().get("x"));
    }

    @Test
    void blindWriteWriteConflictWithoutReading() {
        Transaction<String, String> a = store.begin();
        Transaction<String, String> b = store.begin();
        a.put("k", "1");
        b.put("k", "2");
        a.commit();
        assertThrows(ConflictException.class, b::commit);
    }

    @Test
    void disjointWritersBothCommit() {
        Transaction<String, String> a = store.begin();
        Transaction<String, String> b = store.begin();
        a.put("p", "1");
        b.put("q", "2");
        assertEquals(1, a.commit());
        assertEquals(2, b.commit());
    }

    @Test
    void conflictAppliesNothing() {
        seed("x", "0");
        Transaction<String, String> a = store.begin();
        Transaction<String, String> b = store.begin();
        a.put("x", "A");
        b.put("fresh", "F");
        b.put("x", "B");
        a.commit();
        long before = store.lastCommitTs();
        assertThrows(ConflictException.class, b::commit);
        assertEquals(before, store.lastCommitTs());
        assertEquals(Optional.empty(), store.begin().get("fresh"), "no partial apply");
    }

    @Test
    void writeSkewAllowedUnderSnapshot() {
        seed("alice", "oncall", "bob", "oncall");
        Transaction<String, String> a = store.begin(Isolation.SNAPSHOT);
        Transaction<String, String> b = store.begin(Isolation.SNAPSHOT);
        // each sees the other on call and goes off call
        assertEquals(Optional.of("oncall"), a.get("bob"));
        assertEquals(Optional.of("oncall"), b.get("alice"));
        a.put("alice", "off");
        b.put("bob", "off");
        a.commit();
        b.commit(); // anomaly: nobody on call
        Transaction<String, String> v = store.begin();
        assertEquals(Optional.of("off"), v.get("alice"));
        assertEquals(Optional.of("off"), v.get("bob"));
    }

    @Test
    void writeSkewRejectedUnderSerializable() {
        seed("alice", "oncall", "bob", "oncall");
        Transaction<String, String> a = store.begin(Isolation.SERIALIZABLE);
        Transaction<String, String> b = store.begin(Isolation.SERIALIZABLE);
        a.get("bob");
        b.get("alice");
        a.put("alice", "off");
        b.put("bob", "off");
        a.commit();
        ConflictException e = assertThrows(ConflictException.class, b::commit);
        assertTrue(e.getMessage().contains("alice"), e.getMessage());
        assertEquals(Optional.of("oncall"), store.begin().get("bob"));
    }

    @Test
    void phantomInsertRejectedUnderSerializable() {
        seed("a1", "x");
        Transaction<String, String> scanner = store.begin(Isolation.SERIALIZABLE);
        assertEquals(List.of("a1"), keys(scanner.scan("a", "b")));
        seed("a2", "y"); // phantom inside the scanned range
        scanner.put("out", "z");
        assertThrows(ConflictException.class, scanner::commit);
        assertEquals(Optional.empty(), store.begin().get("out"));
    }

    @Test
    void phantomInsertAllowedUnderSnapshot() {
        seed("a1", "x");
        Transaction<String, String> scanner = store.begin(Isolation.SNAPSHOT);
        assertEquals(List.of("a1"), keys(scanner.scan("a", "b")));
        seed("a2", "y");
        assertEquals(List.of("a1"), keys(scanner.scan("a", "b")), "snapshot still excludes a2");
        scanner.put("out", "z");
        scanner.commit();
    }

    @Test
    void writeOutsideScannedRangeDoesNotConflict() {
        seed("a1", "x");
        Transaction<String, String> scanner = store.begin(Isolation.SERIALIZABLE);
        scanner.scan("a", "b");
        seed("c1", "y");
        scanner.put("out", "z");
        scanner.commit();
    }

    @Test
    void readOnlySerializableCommitsAtItsSnapshotEvenIfStale() {
        seed("x", "1");
        Transaction<String, String> r = store.begin(Isolation.SERIALIZABLE);
        r.get("x");
        seed("x", "2");
        assertEquals(1, r.commit());
        assertEquals(2, store.lastCommitTs());
    }

    @Test
    void closedTransactionRejectsEverything() {
        Transaction<String, String> t = store.begin();
        t.commit();
        assertThrows(IllegalStateException.class, () -> t.get("x"));
        assertThrows(IllegalStateException.class, () -> t.put("x", "1"));
        assertThrows(IllegalStateException.class, () -> t.delete("x"));
        assertThrows(IllegalStateException.class, () -> t.scan("a", "b"));
        assertThrows(IllegalStateException.class, t::commit);
        t.rollback(); // idempotent
        t.close();
    }

    @Test
    void rollbackDiscardsAndReleasesSnapshot() {
        Transaction<String, String> t = store.begin();
        t.put("x", "1");
        assertEquals(1, store.activeTransactions());
        t.rollback();
        assertEquals(0, store.activeTransactions());
        assertEquals(Optional.empty(), store.begin().get("x"));
    }

    @Test
    void tryWithResourcesReleasesUncommitted() {
        try (Transaction<String, String> t = store.begin()) {
            t.put("x", "1");
            assertEquals(1, store.activeTransactions());
        }
        assertEquals(0, store.activeTransactions());
    }

    @Test
    void nullArgumentsRejected() {
        Transaction<String, String> t = store.begin();
        assertThrows(NullPointerException.class, () -> t.put(null, "v"));
        assertThrows(NullPointerException.class, () -> t.put("k", null));
        assertThrows(NullPointerException.class, () -> t.get(null));
        assertThrows(NullPointerException.class, () -> t.delete(null));
        assertThrows(NullPointerException.class, () -> store.begin(null));
    }

    @Test
    void readOnlyAndEmptyCommitsDoNotAdvanceClock() {
        seed("x", "1");
        assertEquals(1, store.lastCommitTs());
        Transaction<String, String> t = store.begin();
        t.get("x");
        assertEquals(1, t.commit());
        assertEquals(1, store.lastCommitTs());
    }

    @Test
    void vacuumKeepsVersionsNeededByActiveReader() {
        seed("x", "v1");
        Transaction<String, String> old = store.begin();
        seed("x", "v2");
        seed("x", "v3");
        assertEquals(3, store.versionCount());
        assertEquals(0, store.vacuum(), "v1 is visible to old, v2 is newer than watermark");
        assertEquals(Optional.of("v1"), old.get("x"));
        old.rollback();
        assertEquals(2, store.vacuum());
        assertEquals(1, store.versionCount());
        assertEquals(Optional.of("v3"), store.begin().get("x"));
    }

    @Test
    void vacuumRemovesFullyDeletedKeysOnlyWhenNoOneCanSeeThem() {
        seed("x", "1");
        Transaction<String, String> old = store.begin();
        Transaction<String, String> d = store.begin();
        d.delete("x");
        d.commit();
        assertEquals(0, store.vacuum());
        assertEquals(1, store.keyCount());
        old.rollback();
        assertEquals(2, store.vacuum());
        assertEquals(0, store.keyCount());
        assertEquals(0, store.versionCount());
    }

    @Test
    void vacuumWithNoActiveTransactionsLeavesOneVersionPerLiveKey() {
        for (int i = 0; i < 5; i++) {
            seed("a", "a" + i, "b", "b" + i);
        }
        assertEquals(10, store.versionCount());
        assertEquals(8, store.vacuum());
        assertEquals(2, store.versionCount());
        Transaction<String, String> t = store.begin();
        assertEquals(Optional.of("a4"), t.get("a"));
        assertEquals(Optional.of("b4"), t.get("b"));
    }

    @Test
    void vacuumDoesNotAffectNewerVersionsAfterWatermark() {
        seed("x", "1");
        Transaction<String, String> mid = store.begin();
        seed("x", "2");
        store.vacuum();
        assertEquals(Optional.of("1"), mid.get("x"));
        assertEquals(Optional.of("2"), store.begin().get("x"));
    }

    @Test
    void conflictStillDetectedAfterVacuum() {
        seed("x", "0");
        Transaction<String, String> a = store.begin();
        seed("x", "1");
        store.vacuum();
        a.put("x", "2");
        assertThrows(ConflictException.class, a::commit);
    }
}
