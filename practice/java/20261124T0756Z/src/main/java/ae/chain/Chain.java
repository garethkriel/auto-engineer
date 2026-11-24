package ae.chain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A block tree with an active best chain, a live UTXO set and reorganisation. */
public final class Chain {
    /** What {@link #add} did with a valid block. */
    public enum Outcome { EXTENDED, SIDE_CHAIN, REORGANISED, DUPLICATE }

    /** A transaction inclusion proof for the active chain. */
    public record Inclusion(String blockId, String merkleRoot, List<Merkle.Step> steps) {
    }

    public static final String NO_PARENT = "0".repeat(64);

    private final int difficulty;
    private final long subsidy;
    private final Map<String, Block> blocks = new HashMap<>();
    private final List<String> active = new ArrayList<>();
    private final List<UtxoSet.Undo> undos = new ArrayList<>();
    private final UtxoSet utxo = new UtxoSet();

    public Chain(int difficulty, long subsidy, String genesisOwner) {
        if (difficulty < 0 || subsidy <= 0) {
            throw new IllegalArgumentException("difficulty must be >= 0 and subsidy > 0");
        }
        this.difficulty = difficulty;
        this.subsidy = subsidy;
        Block genesis = Block.mine(NO_PARENT, 0,
                List.of(Tx.coinbase(genesisOwner, subsidy, 0)), difficulty);
        undos.add(utxo.apply(genesis, subsidy));
        blocks.put(genesis.id(), genesis);
        active.add(genesis.id());
    }

    public synchronized String tip() {
        return active.get(active.size() - 1);
    }

    public synchronized long height() {
        return active.size() - 1L;
    }

    public synchronized List<String> activeIds() {
        return List.copyOf(active);
    }

    public synchronized Optional<Block> block(String id) {
        return Optional.ofNullable(blocks.get(id));
    }

    public synchronized long balance(String owner) {
        return utxo.balance(owner);
    }

    public synchronized Map<TxIn, TxOut> utxos() {
        return utxo.snapshot();
    }

    /** Validates and stores a block, possibly switching the active chain. */
    public synchronized Outcome add(Block b) {
        String id = b.id();
        if (blocks.containsKey(id)) {
            return Outcome.DUPLICATE;
        }
        Block parent = blocks.get(b.prevId());
        if (parent == null) {
            throw new InvalidBlockException("unknown parent " + b.prevId());
        }
        checkHeader(b, parent);
        if (b.prevId().equals(tip())) {
            undos.add(utxo.apply(b, subsidy));
            blocks.put(id, b);
            active.add(id);
            return Outcome.EXTENDED;
        }
        replayTo(b.prevId()).apply(b, subsidy);
        blocks.put(id, b);
        if (b.height() <= height()) {
            return Outcome.SIDE_CHAIN;
        }
        reorganise(b);
        return Outcome.REORGANISED;
    }

    private void checkHeader(Block b, Block parent) {
        if (b.height() != parent.height() + 1) {
            throw new InvalidBlockException("bad height " + b.height());
        }
        if (Hashing.leadingZeroHexDigits(b.id()) < difficulty) {
            throw new InvalidBlockException("insufficient proof of work");
        }
        if (b.txs().isEmpty()) {
            throw new InvalidBlockException("block has no transactions");
        }
        if (!Merkle.root(b.txIds()).equals(b.merkleRoot())) {
            throw new InvalidBlockException("merkle root mismatch");
        }
    }

    private boolean isActive(String id) {
        int h = (int) blocks.get(id).height();
        return h < active.size() && active.get(h).equals(id);
    }

    private void reorganise(Block newTip) {
        List<Block> path = new ArrayList<>();
        Block cur = newTip;
        while (!isActive(cur.id())) {
            path.add(cur);
            cur = blocks.get(cur.prevId());
        }
        Collections.reverse(path);
        while (active.size() - 1 > cur.height()) {
            utxo.undo(undos.remove(undos.size() - 1));
            active.remove(active.size() - 1);
        }
        for (Block p : path) {
            undos.add(utxo.apply(p, subsidy));
            active.add(p.id());
        }
    }

    /** Rebuilds the UTXO set at {@code id} from genesis (slow, independent of the live set). */
    private UtxoSet replayTo(String id) {
        List<Block> path = new ArrayList<>();
        for (Block b = blocks.get(id); b != null; b = blocks.get(b.prevId())) {
            path.add(b);
        }
        Collections.reverse(path);
        UtxoSet set = new UtxoSet();
        for (Block b : path) {
            set.apply(b, subsidy);
        }
        return set;
    }

    /** Proof that a transaction is in the active chain. */
    public synchronized Optional<Inclusion> proveInclusion(String txId) {
        for (String id : active) {
            Block b = blocks.get(id);
            int i = b.txIds().indexOf(txId);
            if (i >= 0) {
                return Optional.of(new Inclusion(id, b.merkleRoot(), Merkle.proof(b.txIds(), i)));
            }
        }
        return Optional.empty();
    }

    /** Throws {@link IllegalStateException} if the live state disagrees with a replay. */
    public synchronized void checkInvariants() {
        if (undos.size() != active.size()) {
            throw new IllegalStateException("undo log length mismatch");
        }
        for (int h = 0; h < active.size(); h++) {
            Block b = blocks.get(active.get(h));
            if (b.height() != h || (h > 0 && !b.prevId().equals(active.get(h - 1)))) {
                throw new IllegalStateException("active chain not linked at " + h);
            }
        }
        if (!replayTo(tip()).snapshot().equals(utxo.snapshot())) {
            throw new IllegalStateException("utxo set differs from replay");
        }
    }
}
