package ae.vcs;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepoTest {
    MemoryObjectStore store;
    Repo repo;
    AtomicLong clock;

    @BeforeEach
    void setUp() {
        store = new MemoryObjectStore();
        clock = new AtomicLong(100);
        repo = new Repo(store, new MemoryRefs(), () -> clock.incrementAndGet());
    }

    static Map<String, byte[]> files(String... kv) {
        Map<String, byte[]> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        return m;
    }

    static Map<String, String> text(Map<String, byte[]> m) {
        Map<String, String> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k, new String(v, StandardCharsets.UTF_8)));
        return out;
    }

    ObjectId c(String branch, String... kv) {
        return repo.commit(branch, files(kv), "t", "m");
    }

    @Test
    void commitThenCheckoutRoundTrips() {
        ObjectId id = c("main", "a.txt", "hello", "dir/b.txt", "bee");
        assertEquals(Optional.of(id), repo.head("main"));
        assertEquals(Map.of("a.txt", "hello", "dir/b.txt", "bee"), text(repo.checkout(id)));
        Commit commit = repo.readCommit(id);
        assertEquals(List.of(), commit.parents());
        assertEquals(101L, commit.time());
    }

    @Test
    void firstCommitTreeIdMatchesKnownLiteral() {
        ObjectId id = c("main", "a.txt", "hello");
        assertEquals(CodecAndStoreTest.TREE_A_HELLO, repo.readCommit(id).tree().hex());
    }

    @Test
    void secondCommitHasFirstAsParentAndEmptyCommitIsRejected() {
        ObjectId a = c("main", "f", "1");
        assertThrows(IllegalStateException.class, () -> c("main", "f", "1"));
        assertEquals(Optional.of(a), repo.head("main"));
        ObjectId b = c("main", "f", "2");
        assertEquals(List.of(a), repo.readCommit(b).parents());
    }

    @Test
    void invalidCommitsLeaveNoTraceInRefs() {
        assertThrows(IllegalArgumentException.class, () -> c("main", "a", "1", "a/b", "2"));
        assertThrows(IllegalArgumentException.class, () -> c("main", "../x", "1"));
        assertThrows(IllegalArgumentException.class, () -> c("bad/name", "x", "1"));
        assertEquals(Optional.empty(), repo.head("main"));
    }

    @Test
    void identicalContentIsStoredOnce() {
        c("main", "x", "same", "y", "same");
        Map<String, byte[]> snapshot = repo.checkout(repo.head("main").orElseThrow());
        assertArrayEquals(snapshot.get("x"), snapshot.get("y"));
        ObjectId blob = ObjectId.ofRaw(Codec.raw(Codec.BLOB, "same".getBytes(StandardCharsets.UTF_8)));
        assertTrue(store.get(blob).isPresent());
    }

    @Test
    void branchCreatesPointerAndRefusesDuplicatesAndUnknownCommits() {
        ObjectId a = c("main", "f", "1");
        repo.branch("dev", a);
        assertEquals(Optional.of(a), repo.head("dev"));
        assertThrows(IllegalStateException.class, () -> repo.branch("dev", a));
        assertThrows(CorruptObjectException.class,
            () -> repo.branch("x", new ObjectId(CodecAndStoreTest.BLOB_HELLO)));
        assertEquals(Optional.empty(), repo.head("x"));
    }

    @Test
    void diffListsAddedModifiedDeletedSortedByPath() {
        ObjectId a = c("main", "keep", "k", "mod", "1", "del", "d");
        ObjectId b = c("main", "keep", "k", "mod", "2", "new", "n");
        assertEquals(List.of(new Change("del", Change.Kind.DELETED), new Change("mod", Change.Kind.MODIFIED),
            new Change("new", Change.Kind.ADDED)), repo.diff(a, b));
        assertEquals(List.of(), repo.diff(a, a));
    }

    // ---- history ----

    @Test
    void logIsNewestFirstOnALine() {
        ObjectId a = c("main", "f", "1");
        ObjectId b = c("main", "f", "2");
        ObjectId d = c("main", "f", "3");
        assertEquals(List.of(d, b, a), repo.log(d));
        assertEquals(List.of(a), repo.log(a));
    }

    @Test
    void logPutsChildrenBeforeParentsEvenWithSkewedClocks() {
        ObjectId root = c("main", "f", "0");          // time 101
        repo.branch("dev", root);
        clock.set(500);
        ObjectId onMain = c("main", "f", "1", "m", "m"); // time 501
        clock.set(10);
        ObjectId onDev = c("dev", "f", "0", "d", "d");   // time 11: OLDER than its parent
        assertEquals(List.of(onDev, root), repo.log(onDev));
        clock.set(20);
        ObjectId merge = ((MergeResult.Merged) repo.merge("main", "dev", "t", "merge")).commit(); // time 21
        // root has two children; it may appear only after both, although 101 > 11 would sort it earlier
        assertEquals(List.of(merge, onMain, onDev, root), repo.log(merge));
    }

    @Test
    void mergeBaseOfForkIsTheForkPoint() {
        ObjectId root = c("main", "f", "0");
        repo.branch("dev", root);
        ObjectId m1 = c("main", "f", "1");
        ObjectId d1 = c("dev", "g", "1");
        assertEquals(Optional.of(root), repo.mergeBase(m1, d1));
        assertEquals(Optional.of(root), repo.mergeBase(d1, m1));
        assertEquals(Optional.of(root), repo.mergeBase(root, d1));
        assertEquals(Optional.of(m1), repo.mergeBase(m1, m1));
    }

    @Test
    void mergeBaseOfUnrelatedHistoriesIsEmpty() {
        ObjectId a = c("one", "f", "1");
        ObjectId b = c("two", "f", "1");
        assertEquals(Optional.empty(), repo.mergeBase(a, b));
    }

    @Test
    void crissCrossMergeBasePicksTheNewestOfTheTwoBestAncestors() {
        ObjectId root = c("main", "r", "0");
        repo.branch("a", root);
        repo.branch("b", root);
        ObjectId a1 = c("a", "r", "0", "a", "1"); // older of the two
        ObjectId b1 = c("b", "r", "0", "b", "1"); // newer
        repo.branch("a1", a1);
        assertInstanceOf(MergeResult.Merged.class, repo.merge("a", "b", "t", "a absorbs b1"));
        assertInstanceOf(MergeResult.Merged.class, repo.merge("b", "a1", "t", "b absorbs a1"));
        ObjectId ma = repo.head("a").orElseThrow();
        ObjectId mb = repo.head("b").orElseThrow();
        assertNotEquals(ma, mb);
        assertEquals(List.of(a1, b1), repo.readCommit(ma).parents());
        assertEquals(List.of(b1, a1), repo.readCommit(mb).parents());
        assertEquals(Optional.of(b1), repo.mergeBase(ma, mb));
        assertEquals(Optional.of(b1), repo.mergeBase(mb, ma));
    }

    // ---- merge truth table ----

    @Test
    void mergeUpToDateAndFastForward() {
        ObjectId a = c("main", "f", "1");
        repo.branch("dev", a);
        assertEquals(new MergeResult.UpToDate(), repo.merge("main", "dev", "t", "m"));
        ObjectId d = c("dev", "f", "2");
        assertEquals(new MergeResult.UpToDate(), repo.merge("dev", "main", "t", "m"));
        assertEquals(new MergeResult.FastForward(d), repo.merge("main", "dev", "t", "m"));
        assertEquals(Optional.of(d), repo.head("main"));
        assertEquals(new MergeResult.UpToDate(), repo.merge("main", "dev", "t", "m"));
    }

    @Test
    void mergeCombinesDisjointChangesAndRecordsBothParents() {
        ObjectId root = c("main", "a", "1", "b", "1", "c", "1");
        repo.branch("dev", root);
        ObjectId m = c("main", "a", "main", "b", "1", "c", "1");
        ObjectId d = c("dev", "a", "1", "b", "1", "c", "1", "d", "new");
        MergeResult r = repo.merge("main", "dev", "Merger", "merge dev");
        ObjectId merged = ((MergeResult.Merged) r).commit();
        assertEquals(List.of(m, d), repo.readCommit(merged).parents());
        assertEquals(Map.of("a", "main", "b", "1", "c", "1", "d", "new"), text(repo.checkout(merged)));
        assertEquals(Optional.of(merged), repo.head("main"));
        assertEquals(Optional.of(d), repo.head("dev"));
        assertEquals("Merger", repo.readCommit(merged).author());
    }

    @Test
    void identicalChangesOnBothSidesAreNotAConflict() {
        ObjectId root = c("main", "a", "1", "x", "1");
        repo.branch("dev", root);
        c("main", "a", "2", "x", "1", "n", "same");
        c("dev", "a", "2", "x", "1", "n", "same", "extra", "e");
        ObjectId merged = ((MergeResult.Merged) repo.merge("main", "dev", "t", "m")).commit();
        assertEquals(Map.of("a", "2", "x", "1", "n", "same", "extra", "e"), text(repo.checkout(merged)));
    }

    @Test
    void bothDeletingAPathDeletesIt() {
        ObjectId root = c("main", "a", "1", "b", "1");
        repo.branch("dev", root);
        c("main", "b", "1");
        c("dev", "b", "1", "z", "z");
        ObjectId merged = ((MergeResult.Merged) repo.merge("main", "dev", "t", "m")).commit();
        assertEquals(Map.of("b", "1", "z", "z"), text(repo.checkout(merged)));
    }

    @Test
    void oneSidedDeleteWinsWhenTheOtherSideLeftItAlone() {
        ObjectId root = c("main", "a", "1", "b", "1");
        repo.branch("dev", root);
        c("main", "a", "1", "b", "1", "m", "m");
        c("dev", "b", "1");
        ObjectId merged = ((MergeResult.Merged) repo.merge("main", "dev", "t", "m")).commit();
        assertEquals(Map.of("b", "1", "m", "m"), text(repo.checkout(merged)));
    }

    @Test
    void conflictsAreReportedSortedAndTouchNothing() {
        ObjectId root = c("main", "mm", "0", "md", "0", "del", "0", "ok", "0");
        repo.branch("dev", root);
        ObjectId m = c("main", "mm", "main", "md", "main", "ok", "0", "add", "x");
        ObjectId d = c("dev", "mm", "dev", "del", "0", "ok", "0", "add", "y", "md2", "z");
        // md: main modified, dev deleted. del: main deleted, dev untouched (clean). add: both added differently.
        MergeResult r = repo.merge("main", "dev", "t", "m");
        assertEquals(new MergeResult.Conflicted(List.of(
            new Conflict("add", Conflict.Kind.BOTH_CHANGED),
            new Conflict("md", Conflict.Kind.CHANGED_DELETED),
            new Conflict("mm", Conflict.Kind.BOTH_CHANGED))), r);
        assertEquals(Optional.of(m), repo.head("main"));
        assertEquals(Optional.of(d), repo.head("dev"));
    }

    @Test
    void fileDirectoryClashIsAConflict() {
        ObjectId root = c("main", "keep", "k");
        repo.branch("dev", root);
        c("main", "keep", "k", "a", "file");
        c("dev", "keep", "k", "a/b", "nested");
        ObjectId before = repo.head("main").orElseThrow();
        assertEquals(new MergeResult.Conflicted(List.of(new Conflict("a", Conflict.Kind.FILE_DIRECTORY))),
            repo.merge("main", "dev", "t", "m"));
        assertEquals(Optional.of(before), repo.head("main"));
    }

    @Test
    void unrelatedHistoriesMergeWhenTheyDoNotCollide() {
        c("one", "a", "1");
        c("two", "b", "2");
        ObjectId merged = ((MergeResult.Merged) repo.merge("one", "two", "t", "m")).commit();
        assertEquals(Map.of("a", "1", "b", "2"), text(repo.checkout(merged)));
        c("x", "same", "1");
        c("y", "same", "2");
        assertEquals(new MergeResult.Conflicted(List.of(new Conflict("same", Conflict.Kind.BOTH_CHANGED))),
            repo.merge("x", "y", "t", "m"));
    }

    @Test
    void mergeOfUnknownBranchFailsWithoutSideEffects() {
        c("main", "a", "1");
        assertThrows(IllegalArgumentException.class, () -> repo.merge("main", "nope", "t", "m"));
        assertThrows(IllegalArgumentException.class, () -> repo.merge("nope", "main", "t", "m"));
    }

    @Test
    void mergeAfterMergeUsesTheMergeCommitAsBase() {
        ObjectId root = c("main", "a", "1");
        repo.branch("dev", root);
        c("main", "a", "1", "m1", "x");
        c("dev", "a", "1", "d1", "x");
        assertInstanceOf(MergeResult.Merged.class, repo.merge("main", "dev", "t", "first"));
        c("dev", "a", "1", "d1", "x", "d2", "y");
        // second merge: base is the old dev tip, so only d2 comes in and nothing conflicts
        ObjectId merged = ((MergeResult.Merged) repo.merge("main", "dev", "t", "second")).commit();
        assertEquals(Map.of("a", "1", "m1", "x", "d1", "x", "d2", "y"), text(repo.checkout(merged)));
    }

    // ---- fsck ----

    @Test
    void fsckIsCleanOnHealthyRepoAndFindsMissingAndCorruptObjects() {
        c("main", "f", "1", "g", "2");
        ObjectId b = c("main", "f", "1", "g", "3");
        assertEquals(List.of(), repo.fsck());
        ObjectId blob = ObjectId.ofRaw(Codec.raw(Codec.BLOB, "2".getBytes(StandardCharsets.UTF_8)));
        store.remove(blob);
        List<String> problems = repo.fsck();
        assertEquals(List.of("blob " + blob + ": missing object " + blob), problems);
        ObjectId tree = repo.readCommit(b).tree();
        store.tamper(tree, Codec.raw(Codec.TREE, new byte[0]));
        problems = repo.fsck();
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("tree " + tree + ": hash mismatch")), problems.toString());
    }

    @Test
    void fsckReportsABranchPointingAtAMissingCommit() {
        ObjectId a = c("main", "f", "1");
        store.remove(a);
        assertEquals(List.of("commit " + a + ": missing object " + a), repo.fsck());
    }

    // ---- persistence adapters ----

    @Test
    void repoOnFileAdaptersSurvivesReopenAndMerges(@TempDir Path dir) {
        AtomicLong t = new AtomicLong();
        Repo r1 = new Repo(new FileObjectStore(dir.resolve("o")), new FileRefs(dir.resolve("r")), t::incrementAndGet);
        ObjectId root = r1.commit("main", files("a", "1"), "t", "root");
        r1.branch("dev", root);
        r1.commit("main", files("a", "1", "m", "m"), "t", "m");
        r1.commit("dev", files("a", "1", "d", "d"), "t", "d");
        Repo r2 = new Repo(new FileObjectStore(dir.resolve("o")), new FileRefs(dir.resolve("r")), t::incrementAndGet);
        ObjectId merged = ((MergeResult.Merged) r2.merge("main", "dev", "t", "merge")).commit();
        assertEquals(Map.of("a", "1", "m", "m", "d", "d"), text(r2.checkout(merged)));
        assertEquals(List.of(), r2.fsck());
        assertEquals(4, r2.log(merged).size());
    }

    @Test
    void fileStoreCorruptionSurfacesThroughRepo(@TempDir Path dir) throws Exception {
        FileObjectStore fs = new FileObjectStore(dir.resolve("o"));
        Repo r = new Repo(fs, new MemoryRefs(), () -> 1L);
        ObjectId id = r.commit("main", files("a", "hello"), "t", "m");
        ObjectId blob = new ObjectId(CodecAndStoreTest.BLOB_HELLO);
        Files.write(fs.pathOf(blob), "garbage".getBytes(StandardCharsets.UTF_8));
        assertThrows(CorruptObjectException.class, () -> r.checkout(id));
        assertEquals(1, r.fsck().size());
    }
}
