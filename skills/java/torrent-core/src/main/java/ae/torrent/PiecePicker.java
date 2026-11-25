package ae.torrent;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

/**
 * One leecher's view of its neighbours' bitfields. Selection is rarest-first: among the candidate
 * pieces, the one held by the fewest known peers wins; ties go to the lowest piece index.
 */
public final class PiecePicker {
    private final int pieceCount;
    private final int[] availability;
    private final Map<String, BitSet> peers = new HashMap<>();

    public PiecePicker(int pieceCount) {
        if (pieceCount < 0) {
            throw new IllegalArgumentException("pieceCount < 0");
        }
        this.pieceCount = pieceCount;
        this.availability = new int[pieceCount];
    }

    private void checkPiece(int piece) {
        if (piece < 0 || piece >= pieceCount) {
            throw new IllegalArgumentException("piece " + piece + " out of range");
        }
    }

    /** Registers a peer with its bitfield (copied); an existing peer is replaced. */
    public void addPeer(String id, BitSet has) {
        if (has.length() > pieceCount) {
            throw new IllegalArgumentException("bitfield longer than piece count");
        }
        removePeer(id);
        BitSet copy = (BitSet) has.clone();
        peers.put(id, copy);
        for (int i = copy.nextSetBit(0); i >= 0; i = copy.nextSetBit(i + 1)) {
            availability[i]++;
        }
    }

    /** Records that a known peer now has a piece; repeated announcements are ignored. */
    public void peerHave(String id, int piece) {
        BitSet bits = peers.get(id);
        if (bits == null) {
            throw new IllegalArgumentException("unknown peer " + id);
        }
        checkPiece(piece);
        if (!bits.get(piece)) {
            bits.set(piece);
            availability[piece]++;
        }
    }

    /** Forgets a peer; unknown ids are ignored. */
    public void removePeer(String id) {
        BitSet bits = peers.remove(id);
        if (bits != null) {
            for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
                availability[i]--;
            }
        }
    }

    public int availability(int piece) {
        checkPiece(piece);
        return availability[piece];
    }

    public int peerCount() {
        return peers.size();
    }

    /** Returns the rarest candidate (lowest index on ties) or -1 when there is none. */
    public int pick(BitSet candidates) {
        if (candidates.length() > pieceCount) {
            throw new IllegalArgumentException("candidate beyond piece count");
        }
        int best = -1;
        for (int i = candidates.nextSetBit(0); i >= 0; i = candidates.nextSetBit(i + 1)) {
            if (best < 0 || availability[i] < availability[best]) {
                best = i;
            }
        }
        return best;
    }
}
