package ae.chain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChainTest {
    static final long SUBSIDY = 50;

    static Block mineOn(Chain c, String parentId, List<Tx> txsAfterCoinbase, String miner, long fees) {
        Block parent = c.block(parentId).orElseThrow();
        long h = parent.height() + 1;
        List<Tx> txs = new ArrayList<>();
        txs.add(Tx.coinbase(miner, SUBSIDY + fees, h));
        txs.addAll(txsAfterCoinbase);
        return Block.mine(parentId, h, txs, 1);
    }

    static TxIn genesisOutpoint(Chain c) {
        Block g = c.block(c.activeIds().get(0)).orElseThrow();
        return new TxIn(g.txs().get(0).id(), 0);
    }

    static void assertRejected(Chain c, Block b, String messagePart) {
        Map<TxIn, TxOut> before = c.utxos();
        String tip = c.tip();
        InvalidBlockException e = assertThrows(InvalidBlockException.class, () -> c.add(b));
        assertTrue(e.getMessage().contains(messagePart), e.getMessage());
        assertEquals(before, c.utxos());
        assertEquals(tip, c.tip());
        assertTrue(c.block(b.id()).isEmpty());
        c.checkInvariants();
    }

    @Test
    void genesisState() {
        Chain c = new Chain(1, SUBSIDY, "alice");
        assertEquals(0, c.height());
        assertEquals(SUBSIDY, c.balance("alice"));
        assertEquals(1, c.utxos().size());
        assertTrue(Hashing.leadingZeroHexDigits(c.tip()) >= 1);
        c.checkInvariants();
    }

    @Test
    void spendWithFeeAndChangeThenInclusionProof() {
        Chain c = new Chain(1, SUBSIDY, "alice");
        Tx pay = new Tx("alice", List.of(genesisOutpoint(c)),
                List.of(new TxOut("bob", 30), new TxOut("alice", 17)), "pay");
        Block b = mineOn(c, c.tip(), List.of(pay), "miner", 3);
        assertEquals(Chain.Outcome.EXTENDED, c.add(b));
        assertEquals(30, c.balance("bob"));
        assertEquals(17, c.balance("alice"));
        assertEquals(SUBSIDY + 3, c.balance("miner"));
        assertEquals(2 * SUBSIDY, c.balance("bob") + c.balance("alice") + c.balance("miner"));
        Chain.Inclusion inc = c.proveInclusion(pay.id()).orElseThrow();
        assertEquals(b.id(), inc.blockId());
        assertTrue(Merkle.verify(pay.id(), inc.steps(), c.block(inc.blockId()).orElseThrow().merkleRoot()));
        assertTrue(c.proveInclusion("nope").isEmpty());
        c.checkInvariants();
    }

    @Test
    void coinbasePayingLessBurns() {
        Chain c = new Chain(1, SUBSIDY, "alice");
        Block b = Block.mine(c.tip(), 1, List.of(Tx.coinbase("miner", 10, 1)), 1);
        assertEquals(Chain.Outcome.EXTENDED, c.add(b));
        assertEquals(10, c.balance("miner"));
    }

    @Test
    void duplicateBlockIsReportedNotReapplied() {
        Chain c = new Chain(1, SUBSIDY, "alice");
        Block b = mineOn(c, c.tip(), List.of(), "m", 0);
        c.add(b);
        assertEquals(Chain.Outcome.DUPLICATE, c.add(b));
        assertEquals(2, c.activeIds().size());
        assertEquals(SUBSIDY, c.balance("m"));
    }

    @Test
    void rejectsUnknownParentBadHeightBadWorkBadMerkle() {
        Chain c = new Chain(2, SUBSIDY, "alice");
        Block good = Block.mine(c.tip(), 1, List.of(Tx.coinbase("m", SUBSIDY, 1)), 2);
        assertRejected(c, Block.mine("f".repeat(64), 1, good.txs(), 2), "unknown parent");
        assertRejected(c, Block.mine(c.tip(), 2, List.of(Tx.coinbase("m", SUBSIDY, 2)), 2), "bad height");
        Block weak = good;
        for (long n = 0;; n++) {
            weak = new Block(good.prevId(), 1, n, good.merkleRoot(), good.txs());
            if (Hashing.leadingZeroHexDigits(weak.id()) < 2) {
                break;
            }
        }
        assertRejected(c, weak, "proof of work");
        Block badRoot = Block.mine(c.tip(), 1, good.txs(), 0);
        Block wrongRoot = null;
        for (long n = 0;; n++) {
            wrongRoot = new Block(c.tip(), 1, n, Hashing.sha256Hex("zzz"), good.txs());
            if (Hashing.leadingZeroHexDigits(wrongRoot.id()) >= 2) {
                break;
            }
        }
        assertFalse(badRoot.id().isEmpty());
        assertRejected(c, wrongRoot, "merkle root mismatch");
        assertEquals(Chain.Outcome.EXTENDED, c.add(good));
    }

    @Test
    void rejectsEmptyBlock() {
        Chain c = new Chain(1, SUBSIDY, "alice");
        Block empty = null;
        for (long n = 0;; n++) {
            empty = new Block(c.tip(), 1, n, Hashing.sha256Hex("e"), List.of());
            if (Hashing.leadingZeroHexDigits(empty.id()) >= 1) {
                break;
            }
        }
        assertRejected(c, empty, "no transactions");
    }

    @Test
    void coinbaseRules() {
        Chain c = new Chain(1, SUBSIDY, "alice");
        Tx pay = new Tx("alice", List.of(genesisOutpoint(c)), List.of(new TxOut("bob", 49)), "p");
        Tx cb = Tx.coinbase("m", SUBSIDY, 1);
        assertRejected(c, Block.mine(c.tip(), 1, List.of(pay), 1), "first transaction must be coinbase");
        assertRejected(c, Block.mine(c.tip(), 1, List.of(cb, Tx.coinbase("m", 1, 1)), 1), "unexpected coinbase");
        assertRejected(c, Block.mine(c.tip(), 1, List.of(Tx.coinbase("m", SUBSIDY + 1, 1)), 1),
                "coinbase pays too much");
        assertRejected(c, Block.mine(c.tip(), 1, List.of(Tx.coinbase("m", SUBSIDY + 2, 1), pay), 1),
                "coinbase pays too much"); // pay leaves a fee of exactly 1
        assertRejected(c, Block.mine(c.tip(), 1, List.of(Tx.coinbase("m", SUBSIDY, 5)), 1), "bad coinbase memo");
        // control: fee of exactly 1 allows SUBSIDY + 1
        assertEquals(Chain.Outcome.EXTENDED,
                c.add(Block.mine(c.tip(), 1, List.of(Tx.coinbase("m", SUBSIDY + 1, 1), pay), 1)));
    }

    @Test
    void transactionRules() {
        Chain c = new Chain(1, SUBSIDY, "alice");
        TxIn g = genesisOutpoint(c);
        Tx cb = Tx.coinbase("m", SUBSIDY, 1);
        assertRejected(c, Block.mine(c.tip(), 1, List.of(cb,
                new Tx("alice", List.of(new TxIn("0".repeat(64), 0)), List.of(new TxOut("b", 1)), "")), 1),
                "missing input");
        assertRejected(c, Block.mine(c.tip(), 1, List.of(cb,
                new Tx("mallory", List.of(g), List.of(new TxOut("b", 1)), "")), 1), "signer does not own");
        assertRejected(c, Block.mine(c.tip(), 1, List.of(cb,
                new Tx("alice", List.of(g), List.of(new TxOut("b", SUBSIDY + 1)), "")), 1), "outputs exceed");
        assertRejected(c, Block.mine(c.tip(), 1, List.of(cb,
                new Tx("alice", List.of(g), List.of(), "")), 1), "no outputs");
        Tx a = new Tx("alice", List.of(g), List.of(new TxOut("b", 10)), "a");
        Tx b = new Tx("alice", List.of(g), List.of(new TxOut("c", 10)), "b");
        assertRejected(c, Block.mine(c.tip(), 1, List.of(cb, a, b), 1), "missing input");
        assertRejected(c, Block.mine(c.tip(), 1, List.of(cb, a, a), 1), "duplicate transaction");
        Tx twice = new Tx("alice", List.of(g, g), List.of(new TxOut("b", 10)), "t");
        assertRejected(c, Block.mine(c.tip(), 1, List.of(cb, twice), 1), "missing input");
        assertEquals(Chain.Outcome.EXTENDED, c.add(Block.mine(c.tip(), 1, List.of(cb, a), 1)));
    }

    @Test
    void spendingOutputCreatedEarlierInSameBlockIsAllowedButNotTheCoinbase() {
        Chain c = new Chain(1, SUBSIDY, "alice");
        Tx first = new Tx("alice", List.of(genesisOutpoint(c)), List.of(new TxOut("bob", 40)), "1");
        Tx second = new Tx("bob", List.of(new TxIn(first.id(), 0)), List.of(new TxOut("carol", 40)), "2");
        Tx cb = Tx.coinbase("m", SUBSIDY + 10, 1);
        Tx spendCb = new Tx("m", List.of(new TxIn(cb.id(), 0)), List.of(new TxOut("z", 1)), "3");
        assertRejected(c, Block.mine(c.tip(), 1, List.of(cb, spendCb), 1), "missing input");
        assertRejected(c, Block.mine(c.tip(), 1, List.of(cb, second, first), 1), "missing input");
        assertEquals(Chain.Outcome.EXTENDED, c.add(Block.mine(c.tip(), 1, List.of(cb, first, second), 1)));
        assertEquals(40, c.balance("carol"));
        assertEquals(0, c.balance("bob"));
    }

    @Test
    void overflowIsRejectedNotWrapped() {
        Chain c = new Chain(1, Long.MAX_VALUE, "alice");
        Tx cb1 = Tx.coinbase("alice", Long.MAX_VALUE, 1);
        c.add(Block.mine(c.tip(), 1, List.of(cb1), 1));
        Tx merge = new Tx("alice", List.of(genesisOutpoint(c), new TxIn(cb1.id(), 0)),
                List.of(new TxOut("bob", 1)), "merge");
        assertRejected(c, Block.mine(c.tip(), 2, List.of(Tx.coinbase("m", Long.MAX_VALUE, 2), merge), 1),
                "amount overflow");
        Tx bigOut = new Tx("alice", List.of(genesisOutpoint(c)),
                List.of(new TxOut("b", Long.MAX_VALUE), new TxOut("c", 1)), "big");
        assertRejected(c, Block.mine(c.tip(), 2, List.of(Tx.coinbase("m", Long.MAX_VALUE, 2), bigOut), 1),
                "amount overflow");
    }

    @Test
    void longerSideBranchReorganisesAndRestoresSpentOutputs() {
        Chain c = new Chain(1, SUBSIDY, "alice");
        String genesis = c.tip();
        Tx pay = new Tx("alice", List.of(genesisOutpoint(c)), List.of(new TxOut("bob", 50)), "pay");
        Block a1 = mineOn(c, genesis, List.of(pay), "ma", 0);
        assertEquals(Chain.Outcome.EXTENDED, c.add(a1));
        Block a2 = mineOn(c, a1.id(), List.of(), "ma", 0);
        assertEquals(Chain.Outcome.EXTENDED, c.add(a2));
        assertEquals(50, c.balance("bob"));
        assertEquals(0, c.balance("alice"));
        Block b1 = mineOn(c, genesis, List.of(), "mb", 0);
        assertEquals(Chain.Outcome.SIDE_CHAIN, c.add(b1));
        Block b2 = mineOn(c, b1.id(), List.of(), "mb", 0);
        assertEquals(Chain.Outcome.SIDE_CHAIN, c.add(b2));
        assertEquals(a2.id(), c.tip(), "tie keeps first seen");
        Block b3 = mineOn(c, b2.id(), List.of(), "mb", 0);
        assertEquals(Chain.Outcome.REORGANISED, c.add(b3));
        assertEquals(b3.id(), c.tip());
        assertEquals(List.of(genesis, b1.id(), b2.id(), b3.id()), c.activeIds());
        assertEquals(50, c.balance("alice"));
        assertEquals(0, c.balance("bob"));
        assertEquals(0, c.balance("ma"));
        assertEquals(3 * SUBSIDY, c.balance("mb"));
        assertTrue(c.proveInclusion(pay.id()).isEmpty());
        c.checkInvariants();
        Block a3 = mineOn(c, a2.id(), List.of(), "ma", 0);
        assertEquals(Chain.Outcome.SIDE_CHAIN, c.add(a3));
        Block a4 = mineOn(c, a3.id(), List.of(), "ma", 0);
        assertEquals(Chain.Outcome.REORGANISED, c.add(a4));
        assertEquals(50, c.balance("bob"));
        assertTrue(c.proveInclusion(pay.id()).isPresent());
        c.checkInvariants();
    }

    @Test
    void sideBlockIsCheckedAgainstItsOwnParentState() {
        Chain c = new Chain(1, SUBSIDY, "alice");
        String genesis = c.tip();
        Tx pay = new Tx("alice", List.of(genesisOutpoint(c)), List.of(new TxOut("bob", 50)), "pay");
        Block a1 = mineOn(c, genesis, List.of(pay), "ma", 0);
        c.add(a1);
        Tx bobSpends = new Tx("bob", List.of(new TxIn(pay.id(), 0)), List.of(new TxOut("c", 50)), "x");
        // valid on the active tip, invalid on the side branch (where bob has nothing)
        Block onSide = mineOn(c, genesis, List.of(bobSpends), "mb", 0);
        assertRejected(c, onSide, "missing input");
        Block onTip = mineOn(c, a1.id(), List.of(bobSpends), "mb", 0);
        assertEquals(Chain.Outcome.EXTENDED, c.add(onTip));
    }

    @Test
    void invalidSideBlockDoesNotDisturbActiveChain() {
        Chain c = new Chain(1, SUBSIDY, "alice");
        String genesis = c.tip();
        c.add(mineOn(c, genesis, List.of(), "ma", 0));
        Block bad = mineOn(c, genesis, List.of(), "mb", 7);
        assertRejected(c, bad, "coinbase pays too much");
    }
}
