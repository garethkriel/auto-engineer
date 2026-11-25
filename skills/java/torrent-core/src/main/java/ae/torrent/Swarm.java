package ae.torrent;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Deterministic in-process BitTorrent-style swarm over a full mesh.
 *
 * <p>Peer kinds: seeds (hold everything, may depart at a tick), poisoners (claim every piece but
 * serve corrupted data) and leechers (optionally starting with some pieces). Peers act in
 * insertion order; within a tick a piece obtained earlier is visible to later peers immediately.
 * Each leecher downloads up to {@code downloadSlots} pieces per tick, chosen by its own
 * {@link PiecePicker}; each peer uploads at most {@code uploadSlots} pieces per tick and the
 * uploader is the earliest-added eligible peer. Pieces are hash-verified; two failed pieces from the
 * same uploader ban that uploader for that leecher.
 */
public final class Swarm {
    private static final int STRIKES_TO_BAN = 2;

    private enum Kind { SEED, POISONER, LEECHER }

    private final class Peer {
        final String id;
        final Kind kind;
        final int departTick; // seeds only; Integer.MAX_VALUE = never
        boolean live = true;
        // leecher state
        final BitSet have = new BitSet();
        final byte[] store;
        final PiecePicker picker;
        final List<Integer> history = new ArrayList<>();
        final Map<String, Integer> strikes = new HashMap<>();
        final Set<String> banned = new HashSet<>();

        Peer(String id, Kind kind, int departTick, BitSet initial) {
            this.id = id;
            this.kind = kind;
            this.departTick = departTick;
            if (kind == Kind.LEECHER) {
                store = new byte[data.length];
                picker = new PiecePicker(torrent.pieceCount());
                for (int p = initial.nextSetBit(0); p >= 0; p = initial.nextSetBit(p + 1)) {
                    System.arraycopy(data, p * torrent.pieceLength(), store, p * torrent.pieceLength(),
                            torrent.pieceSize(p));
                    have.set(p);
                }
            } else {
                store = null;
                picker = null;
            }
        }

        /** Pieces this peer will offer right now. */
        BitSet offered() {
            if (kind == Kind.LEECHER) {
                return (BitSet) have.clone();
            }
            BitSet all = new BitSet();
            all.set(0, torrent.pieceCount());
            return all;
        }

        boolean complete() {
            return kind == Kind.SEED || (kind == Kind.LEECHER && have.cardinality() == torrent.pieceCount());
        }
    }

    private final Torrent torrent;
    private final byte[] data;
    private final int uploadSlots;
    private final int downloadSlots;
    private final Map<String, Peer> peers = new LinkedHashMap<>();
    private final Set<String> bans = new HashSet<>();
    private final Map<String, Integer> completionTick = new TreeMap<>();
    private boolean started;
    private int tick;
    private int hashFailures;
    private int maxServed;

    public Swarm(Torrent torrent, byte[] data, int uploadSlots, int downloadSlots) {
        if (uploadSlots <= 0 || downloadSlots <= 0) {
            throw new IllegalArgumentException("slots must be positive");
        }
        if (data.length != torrent.length()) {
            throw new IllegalArgumentException("data length does not match the torrent");
        }
        for (int p = 0; p < torrent.pieceCount(); p++) {
            int from = p * torrent.pieceLength();
            if (!torrent.verifyPiece(p, java.util.Arrays.copyOfRange(data, from, from + torrent.pieceSize(p)))) {
                throw new IllegalArgumentException("data does not match piece " + p);
            }
        }
        this.torrent = torrent;
        this.data = data.clone();
        this.uploadSlots = uploadSlots;
        this.downloadSlots = downloadSlots;
    }

    private void add(Peer p) {
        if (started) {
            throw new IllegalStateException("swarm already started");
        }
        if (peers.containsKey(p.id)) {
            throw new IllegalArgumentException("duplicate peer " + p.id);
        }
        peers.put(p.id, p);
    }

    public void addSeed(String id) {
        addSeed(id, Integer.MAX_VALUE);
    }

    /** A seed that stops serving at the start of tick {@code departTick} (ticks count from 1). */
    public void addSeed(String id, int departTick) {
        if (departTick < 1) {
            throw new IllegalArgumentException("departTick must be >= 1");
        }
        add(new Peer(id, Kind.SEED, departTick, new BitSet()));
    }

    public void addPoisoner(String id) {
        add(new Peer(id, Kind.POISONER, Integer.MAX_VALUE, new BitSet()));
    }

    public void addLeecher(String id) {
        addPartial(id, new BitSet());
    }

    /** A leecher that already owns the pieces in {@code initial}. */
    public void addPartial(String id, BitSet initial) {
        if (initial.length() > torrent.pieceCount()) {
            throw new IllegalArgumentException("bitfield beyond piece count");
        }
        add(new Peer(id, Kind.LEECHER, Integer.MAX_VALUE, initial));
    }

    private Peer peer(String id) {
        Peer p = peers.get(id);
        if (p == null) {
            throw new IllegalArgumentException("unknown peer " + id);
        }
        return p;
    }

    private void start() {
        started = true;
        for (Peer l : peers.values()) {
            if (l.kind == Kind.LEECHER) {
                for (Peer q : peers.values()) {
                    if (q != l) {
                        l.picker.addPeer(q.id, q.offered());
                    }
                }
            }
        }
    }

    /** Runs one tick; returns false when no leecher could request anything (stall). */
    public boolean step() {
        if (!started) {
            start();
        }
        tick++;
        for (Peer s : peers.values()) {
            if (s.kind == Kind.SEED && s.live && s.departTick <= tick) {
                s.live = false;
                for (Peer l : peers.values()) {
                    if (l.kind == Kind.LEECHER) {
                        l.picker.removePeer(s.id);
                    }
                }
            }
        }
        Map<String, Integer> served = new HashMap<>();
        boolean active = false;
        for (Peer l : peers.values()) {
            if (l.kind != Kind.LEECHER) {
                continue;
            }
            for (int k = 0; k < downloadSlots && !l.complete(); k++) {
                BitSet candidates = new BitSet();
                for (Peer q : peers.values()) {
                    if (eligible(l, q, served)) {
                        candidates.or(q.offered());
                    }
                }
                candidates.andNot(l.have);
                int piece = l.picker.pick(candidates);
                if (piece < 0) {
                    break;
                }
                Peer up = null;
                for (Peer q : peers.values()) {
                    if (eligible(l, q, served) && q.offered().get(piece)) {
                        up = q;
                        break;
                    }
                }
                active = true;
                int n = served.merge(up.id, 1, Integer::sum);
                maxServed = Math.max(maxServed, n);
                transfer(l, up, piece);
            }
        }
        return active;
    }

    private boolean eligible(Peer leecher, Peer q, Map<String, Integer> served) {
        return q != leecher && q.live && !leecher.banned.contains(q.id)
                && served.getOrDefault(q.id, 0) < uploadSlots;
    }

    private void transfer(Peer l, Peer up, int piece) {
        int from = piece * torrent.pieceLength();
        byte[] bytes = java.util.Arrays.copyOfRange(data, from, from + torrent.pieceSize(piece));
        if (up.kind == Kind.POISONER) {
            bytes[0] ^= (byte) 0xff;
        }
        if (!torrent.verifyPiece(piece, bytes)) {
            hashFailures++;
            int strikes = l.strikes.merge(up.id, 1, Integer::sum);
            if (strikes >= STRIKES_TO_BAN) {
                l.banned.add(up.id);
                l.picker.removePeer(up.id);
                bans.add(l.id + ">" + up.id);
            }
            return;
        }
        System.arraycopy(bytes, 0, l.store, from, bytes.length);
        l.have.set(piece);
        l.history.add(piece);
        for (Peer m : peers.values()) {
            if (m != l && m.kind == Kind.LEECHER && !m.banned.contains(l.id)) {
                m.picker.peerHave(l.id, piece);
            }
        }
        if (l.complete()) {
            completionTick.put(l.id, tick);
        }
    }

    private boolean leechersComplete() {
        for (Peer p : peers.values()) {
            if (p.kind == Kind.LEECHER && !p.complete()) {
                return false;
            }
        }
        return true;
    }

    /** Steps until every leecher is complete, a tick stalls, or {@code maxTicks} ticks have run. */
    public SwarmResult run(int maxTicks) {
        boolean stalled = false;
        while (!leechersComplete() && tick < maxTicks) {
            if (!step()) {
                stalled = true;
                break;
            }
        }
        return new SwarmResult(tick, leechersComplete(), stalled, hashFailures, bans, completionTick, maxServed);
    }

    public int tick() {
        return tick;
    }

    public BitSet have(String id) {
        return peer(id).offered();
    }

    public List<Integer> history(String id) {
        return List.copyOf(peer(id).history);
    }

    public boolean isComplete(String id) {
        return peer(id).complete();
    }

    public boolean isLive(String id) {
        return peer(id).live;
    }

    public boolean isBanned(String leecher, String uploader) {
        return peer(leecher).banned.contains(uploader);
    }

    /** Number of live, unbanned neighbours the leecher's picker believes hold the piece. */
    public int availability(String leecher, int piece) {
        Peer l = peer(leecher);
        if (l.kind != Kind.LEECHER) {
            throw new IllegalArgumentException(leecher + " is not a leecher");
        }
        if (!started) {
            start();
        }
        return l.picker.availability(piece);
    }

    /** The assembled file of a complete peer. */
    public byte[] content(String id) {
        Peer p = peer(id);
        if (!p.complete()) {
            throw new IllegalStateException(id + " is not complete");
        }
        return p.kind == Kind.LEECHER ? p.store.clone() : data.clone();
    }
}
