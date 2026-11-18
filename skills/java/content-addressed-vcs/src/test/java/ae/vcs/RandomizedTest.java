package ae.vcs;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Differential tests: brute-force oracles written independently of Repo's algorithms. */
class RandomizedTest {
    private static final String[] PATHS = {"a", "b", "c", "d/x", "d/y", "e/f/g", "h"};

    /** Builds a random DAG of commits on branches; returns every commit with its parents. */
    private static final class World {
        final AtomicLong clock = new AtomicLong();
        final Repo repo;
        final Map<ObjectId, List<ObjectId>> parents = new HashMap<>();
        final List<ObjectId> all = new ArrayList<>();
        final List<String> branches = new ArrayList<>();
        final Random rnd;
        int counter;

        World(long seed) {
            rnd = new Random(seed);
            repo = new Repo(new MemoryObjectStore(), new MemoryRefs(), () -> rnd.nextInt(50)); // skewed times on purpose
        }

        Map<String, byte[]> randomFiles(Map<String, byte[]> from) {
            Map<String, byte[]> next = new TreeMap<>(from);
            String p = PATHS[rnd.nextInt(PATHS.length)];
            if (next.containsKey(p) && rnd.nextInt(3) == 0) {
                next.remove(p);
            } else {
                next.put(p, ("v" + (counter++)).getBytes(StandardCharsets.UTF_8));
            }
            return next;
        }

        void record(ObjectId id) {
            parents.put(id, repo.readCommit(id).parents());
            all.add(id);
        }

        void grow(int steps) {
            branches.add("b0");
            for (int i = 0; i < steps; i++) {
                String br = branches.get(rnd.nextInt(branches.size()));
                int action = rnd.nextInt(10);
                Optional<ObjectId> head = repo.head(br);
                if (head.isEmpty() || action < 6) {
                    Map<String, byte[]> base = head.isPresent() ? repo.checkout(head.get()) : Map.of();
                    Map<String, byte[]> next = randomFiles(base);
                    if (next.equals(base) || (head.isPresent() && sameFiles(next, base))) {
                        continue;
                    }
                    try {
                        record(repo.commit(br, next, "t", "c" + i));
                    } catch (IllegalStateException emptyCommit) {
                        // tree equal to parent's tree: skip
                    }
                } else if (action < 8 && head.isPresent()) {
                    String name = "b" + branches.size();
                    repo.branch(name, head.get());
                    branches.add(name);
                } else {
                    String other = branches.get(rnd.nextInt(branches.size()));
                    if (!other.equals(br) && repo.head(other).isPresent() && head.isPresent()) {
                        MergeResult r = repo.merge(br, other, "t", "m" + i);
                        if (r instanceof MergeResult.Merged m) {
                            record(m.commit());
                        }
                    }
                }
            }
        }

        static boolean sameFiles(Map<String, byte[]> a, Map<String, byte[]> b) {
            if (!a.keySet().equals(b.keySet())) {
                return false;
            }
            for (String k : a.keySet()) {
                if (!java.util.Arrays.equals(a.get(k), b.get(k))) {
                    return false;
                }
            }
            return true;
        }

        Set<ObjectId> ancestors(ObjectId id) {
            Set<ObjectId> out = new HashSet<>();
            List<ObjectId> todo = new ArrayList<>(List.of(id));
            while (!todo.isEmpty()) {
                ObjectId x = todo.remove(todo.size() - 1);
                if (out.add(x)) {
                    todo.addAll(parents.get(x));
                }
            }
            return out;
        }
    }

    @Test
    void mergeBaseAgreesWithBruteForceOnRandomDags() {
        int checked = 0;
        int multi = 0;
        for (long seed = 1; seed <= 40; seed++) {
            World w = new World(seed);
            w.grow(60);
            for (int i = 0; i < 25 && w.all.size() > 1; i++) {
                ObjectId x = w.all.get(w.rnd.nextInt(w.all.size()));
                ObjectId y = w.all.get(w.rnd.nextInt(w.all.size()));
                Set<ObjectId> common = new HashSet<>(w.ancestors(x));
                common.retainAll(w.ancestors(y));
                // best = common ancestors with no strict descendant among the common ancestors
                Set<ObjectId> best = new HashSet<>();
                for (ObjectId c : common) {
                    boolean hasDescendant = false;
                    for (ObjectId d : common) {
                        if (!d.equals(c) && w.ancestors(d).contains(c)) {
                            hasDescendant = true;
                        }
                    }
                    if (!hasDescendant) {
                        best.add(c);
                    }
                }
                Optional<ObjectId> got = w.repo.mergeBase(x, y);
                if (common.isEmpty()) {
                    assertEquals(Optional.empty(), got, "seed " + seed);
                } else {
                    assertTrue(got.isPresent() && best.contains(got.get()), "seed " + seed + " best " + best + " got " + got);
                    assertEquals(got, w.repo.mergeBase(y, x), "symmetry, seed " + seed);
                }
                assertEquals(w.ancestors(x).contains(y), w.repo.isAncestor(y, x), "isAncestor seed " + seed);
                if (best.size() > 1) {
                    multi++;
                }
                checked++;
            }
        }
        assertTrue(checked > 500, "checked " + checked);
        System.out.println("mergeBase pairs checked=" + checked + " with several best ancestors=" + multi);
    }

    @Test
    void logIsATopologicalOrderOfExactlyTheAncestors() {
        for (long seed = 100; seed < 130; seed++) {
            World w = new World(seed);
            w.grow(50);
            for (ObjectId tip : w.all) {
                List<ObjectId> log = w.repo.log(tip);
                assertEquals(w.ancestors(tip), new HashSet<>(log), "seed " + seed);
                assertEquals(log.size(), new HashSet<>(log).size(), "no duplicates, seed " + seed);
                Map<ObjectId, Integer> pos = new HashMap<>();
                for (int i = 0; i < log.size(); i++) {
                    pos.put(log.get(i), i);
                }
                for (ObjectId c : log) {
                    for (ObjectId p : w.parents.get(c)) {
                        assertTrue(pos.get(c) < pos.get(p), "child before parent, seed " + seed);
                    }
                }
                assertEquals(tip, log.get(0));
            }
        }
    }

    /** Independent per-path oracle: returns merged map or null when any path conflicts. */
    private static Map<String, String> oracle(Map<String, String> base, Map<String, String> ours, Map<String, String> theirs) {
        Map<String, String> out = new TreeMap<>();
        Set<String> paths = new HashSet<>();
        paths.addAll(base.keySet());
        paths.addAll(ours.keySet());
        paths.addAll(theirs.keySet());
        for (String p : paths) {
            String b = base.get(p);
            String o = ours.get(p);
            String t = theirs.get(p);
            String pick;
            if (java.util.Objects.equals(o, b)) {
                pick = t;
            } else if (java.util.Objects.equals(t, b) || java.util.Objects.equals(o, t)) {
                pick = o;
            } else {
                return null;
            }
            if (pick != null) {
                out.put(p, pick);
            }
        }
        return out;
    }

    private static Map<String, String> text(Map<String, byte[]> m) {
        Map<String, String> out = new TreeMap<>();
        m.forEach((k, v) -> out.put(k, new String(v, StandardCharsets.UTF_8)));
        return out;
    }

    @Test
    void mergeAgreesWithPerPathOracleAndIsSymmetric() {
        int merged = 0;
        int conflicted = 0;
        for (long seed = 1; seed <= 60; seed++) {
            Random rnd = new Random(seed);
            AtomicLong clock = new AtomicLong();
            Repo repo = new Repo(new MemoryObjectStore(), new MemoryRefs(), clock::incrementAndGet);
            World w = new World(seed);
            Map<String, byte[]> base = new TreeMap<>();
            base.put("a", "0".getBytes(StandardCharsets.UTF_8));
            base.put("d/x", "0".getBytes(StandardCharsets.UTF_8));
            ObjectId root = repo.commit("main", base, "t", "root");
            repo.branch("one", root);
            repo.branch("two", root);
            Map<String, byte[]> sideA = base;
            Map<String, byte[]> sideB = base;
            for (int i = 0, n = 1 + rnd.nextInt(4); i < n; i++) {
                Map<String, byte[]> next = w.randomFiles(sideA);
                if (!World.sameFiles(next, sideA) && noClash(next)) {
                    repo.commit("one", next, "t", "one" + i);
                    sideA = next;
                }
                Map<String, byte[]> next2 = w.randomFiles(sideB);
                if (!World.sameFiles(next2, sideB) && noClash(next2)) {
                    repo.commit("two", next2, "t", "two" + i);
                    sideB = next2;
                }
            }
            Optional<ObjectId> tipA = repo.head("one");
            Optional<ObjectId> tipB = repo.head("two");
            if (tipA.equals(Optional.of(root)) || tipB.equals(Optional.of(root))) {
                continue; // fast-forward / up-to-date cases are covered elsewhere
            }
            repo.branch("one2", tipA.orElseThrow());
            repo.branch("two2", tipB.orElseThrow());
            Map<String, String> expected = oracle(text(base), text(sideA), text(sideB));
            MergeResult ab = repo.merge("one", "two", "t", "ab");
            MergeResult ba = repo.merge("two2", "one2", "t", "ba");
            if (expected == null || hasClash(expected)) {
                assertInstanceOf(MergeResult.Conflicted.class, ab, "seed " + seed);
                assertInstanceOf(MergeResult.Conflicted.class, ba, "seed " + seed);
                MergeResult.Conflicted ca = (MergeResult.Conflicted) ab;
                MergeResult.Conflicted cb = (MergeResult.Conflicted) ba;
                assertEquals(ca.conflicts().stream().map(Conflict::path).toList(),
                    cb.conflicts().stream().map(Conflict::path).toList(), "symmetric paths, seed " + seed);
                assertEquals(tipA, repo.head("one"));
                conflicted++;
            } else {
                assertInstanceOf(MergeResult.Merged.class, ab, "seed " + seed);
                assertInstanceOf(MergeResult.Merged.class, ba, "seed " + seed);
                assertEquals(expected, text(repo.checkout(((MergeResult.Merged) ab).commit())), "oracle, seed " + seed);
                assertEquals(repo.readCommit(((MergeResult.Merged) ab).commit()).tree(),
                    repo.readCommit(((MergeResult.Merged) ba).commit()).tree(), "same tree either direction, seed " + seed);
                merged++;
            }
        }
        assertTrue(merged >= 10 && conflicted >= 10, "merged=" + merged + " conflicted=" + conflicted);
        System.out.println("merge cases: merged=" + merged + " conflicted=" + conflicted);
    }

    private static boolean noClash(Map<String, byte[]> files) {
        return Codec.findFileDirectoryClash(files.keySet()) == null;
    }

    private static boolean hasClash(Map<String, String> files) {
        return Codec.findFileDirectoryClash(files.keySet()) != null;
    }
}
