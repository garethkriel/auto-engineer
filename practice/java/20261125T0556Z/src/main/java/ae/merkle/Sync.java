package ae.merkle;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/** Top-down anti-entropy: finds and applies the difference between a local map and a (possibly hostile) remote. */
public final class Sync {
    private Sync() {}

    /**
     * Result of a comparison from the local point of view.
     *
     * @param localOnly keys only the local map has
     * @param remoteOnly keys (with remote values) only the remote has
     * @param changed keys both have with different values (remote values)
     * @param requests number of remote calls made
     * @param remoteRoot the root hash the remote advertised and that every reply was verified against
     */
    public record Diff(SortedSet<String> localOnly, SortedMap<String, String> remoteOnly,
                       SortedMap<String, String> changed, int requests, byte[] remoteRoot) {
        public boolean isEmpty() {
            return localOnly.isEmpty() && remoteOnly.isEmpty() && changed.isEmpty();
        }
    }

    /** Compares; never modifies {@code local}. Throws IllegalStateException on any inconsistent remote reply. */
    public static Diff diff(MerkleMap local, Remote remote) {
        Objects.requireNonNull(local, "local");
        Objects.requireNonNull(remote, "remote");
        State st = new State();
        byte[] remoteRoot = checkHash(remote.hashAt(""), "root");
        st.requests++;
        if (!Arrays.equals(remoteRoot, local.rootHash())) {
            descend(local, remote, "", remoteRoot, st);
        }
        return new Diff(st.localOnly, st.remoteOnly, st.changed, st.requests, remoteRoot);
    }

    /**
     * Makes {@code local} equal to the remote. The change is applied to a copy first and committed only if the
     * result hashes to the remote's advertised root.
     */
    public static Diff pull(MerkleMap local, Remote remote) {
        Diff d = diff(local, remote);
        if (d.isEmpty()) {
            return d;
        }
        MerkleMap trial = local.copy();
        apply(trial, d);
        if (!Arrays.equals(trial.rootHash(), d.remoteRoot())) {
            throw new IllegalStateException("remote content does not reproduce its advertised root");
        }
        apply(local, d);
        return d;
    }

    private static void apply(MerkleMap target, Diff d) {
        d.localOnly().forEach(target::remove);
        d.remoteOnly().forEach(target::put);
        d.changed().forEach(target::put);
    }

    private static final class State {
        int requests;
        final SortedSet<String> localOnly = new TreeSet<>();
        final SortedMap<String, String> remoteOnly = new TreeMap<>();
        final SortedMap<String, String> changed = new TreeMap<>();
    }

    private static byte[] checkHash(byte[] h, String what) {
        if (h == null || h.length != Hashing.HASH_BYTES) {
            throw new IllegalStateException("malformed " + what + " hash from remote");
        }
        return h;
    }

    /** Invariant: {@code advertised} is what the remote claimed for {@code path} and differs from local's hash. */
    private static void descend(MerkleMap local, Remote remote, String path, byte[] advertised, State st) {
        if (path.length() == Hashing.DEPTH) {
            List<Proof.Entry> entries = remote.bucket(path);
            st.requests++;
            if (entries == null) {
                throw new IllegalStateException("malformed bucket from remote at " + path);
            }
            TreeMap<String, String> remoteBucket = new TreeMap<>();
            for (Proof.Entry e : entries) {
                if (e == null || e.key() == null || e.value() == null
                        || !Hashing.pathOf(e.key()).equals(path) || remoteBucket.put(e.key(), e.value()) != null) {
                    throw new IllegalStateException("bad bucket entry from remote at " + path);
                }
            }
            if (!Arrays.equals(Hashing.leafHash(remoteBucket), advertised)) {
                throw new IllegalStateException("bucket " + path + " does not match its advertised hash");
            }
            SortedMap<String, String> mine = local.bucketEntries(path);
            for (Map.Entry<String, String> e : mine.entrySet()) {
                String theirs = remoteBucket.get(e.getKey());
                if (theirs == null) {
                    st.localOnly.add(e.getKey());
                } else if (!theirs.equals(e.getValue())) {
                    st.changed.put(e.getKey(), theirs);
                }
            }
            for (Map.Entry<String, String> e : remoteBucket.entrySet()) {
                if (!mine.containsKey(e.getKey())) {
                    st.remoteOnly.put(e.getKey(), e.getValue());
                }
            }
            return;
        }
        byte[][] kids = remote.childHashes(path);
        st.requests++;
        if (kids == null || kids.length != Hashing.FANOUT) {
            throw new IllegalStateException("expected " + Hashing.FANOUT + " child hashes at '" + path + "'");
        }
        for (byte[] k : kids) {
            checkHash(k, "child");
        }
        if (!Arrays.equals(Hashing.innerHash(kids), advertised)) {
            throw new IllegalStateException("children do not hash to the advertised value at '" + path + "'");
        }
        byte[][] mine = local.childHashes(path);
        for (int i = 0; i < Hashing.FANOUT; i++) {
            if (!Arrays.equals(kids[i], mine[i])) {
                descend(local, remote, path + Character.forDigit(i, 16), kids[i], st);
            }
        }
    }
}
