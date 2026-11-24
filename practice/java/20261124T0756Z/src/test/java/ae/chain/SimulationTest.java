package ae.chain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Seeded random forks with invalid-block injection, checked against an independent oracle. */
class SimulationTest {
    static final long SUBSIDY = 50;
    static final String[] OWNERS = {"alice", "bob", "carol"};

    /** Oracle: blocks in acceptance order, replayed with a deliberately simple applier. */
    static final class Oracle {
        final Map<String, Block> blocks = new LinkedHashMap<>();

        /** UTXO state after the block with this id, as outpoint-string to owner:amount. */
        Map<String, String> stateAt(String id) {
            List<Block> path = new ArrayList<>();
            for (Block b = blocks.get(id); b != null; b = blocks.get(b.prevId())) {
                path.add(0, b);
            }
            Map<String, String> state = new HashMap<>();
            for (Block b : path) {
                for (Tx tx : b.txs()) {
                    for (TxIn in : tx.inputs()) {
                        assertTrue(state.remove(in.txId() + ":" + in.index()) != null, "oracle input missing");
                    }
                    for (int i = 0; i < tx.outputs().size(); i++) {
                        state.put(tx.id() + ":" + i, tx.outputs().get(i).owner() + ":" + tx.outputs().get(i).amount());
                    }
                }
            }
            return state;
        }

        /** Highest block, earliest accepted wins ties. */
        String best() {
            String best = null;
            long h = -1;
            for (Block b : blocks.values()) {
                if (b.height() > h) {
                    h = b.height();
                    best = b.id();
                }
            }
            return best;
        }
    }

    static Map<String, String> flatten(Map<TxIn, TxOut> utxos) {
        Map<String, String> m = new HashMap<>();
        utxos.forEach((k, v) -> m.put(k.txId() + ":" + k.index(), v.owner() + ":" + v.amount()));
        return m;
    }

    /** Builds random valid transactions against a working copy of the parent state. */
    static List<Tx> randomTxs(Random rnd, Map<String, String> state, long[] feesOut) {
        Map<String, String> work = new HashMap<>(state);
        List<Tx> txs = new ArrayList<>();
        long fees = 0;
        int want = rnd.nextInt(4);
        for (int n = 0; n < want && !work.isEmpty(); n++) {
            List<String> keys = new ArrayList<>(work.keySet());
            keys.sort(null);
            String key = keys.get(rnd.nextInt(keys.size()));
            String[] ownerAmount = work.remove(key).split(":");
            long amount = Long.parseLong(ownerAmount[1]);
            String[] ref = key.split(":");
            long fee = amount > 2 ? rnd.nextInt(2) : 0;
            long spendable = amount - fee;
            List<TxOut> outs = new ArrayList<>();
            if (spendable >= 2 && rnd.nextBoolean()) {
                long first = 1 + rnd.nextInt((int) spendable - 1);
                outs.add(new TxOut(OWNERS[rnd.nextInt(3)], first));
                outs.add(new TxOut(OWNERS[rnd.nextInt(3)], spendable - first));
            } else {
                outs.add(new TxOut(OWNERS[rnd.nextInt(3)], spendable));
            }
            Tx tx = new Tx(ownerAmount[0], List.of(new TxIn(ref[0], Integer.parseInt(ref[1]))), outs,
                    "n" + rnd.nextInt(1000));
            txs.add(tx);
            for (int i = 0; i < outs.size(); i++) {
                work.put(tx.id() + ":" + i, outs.get(i).owner() + ":" + outs.get(i).amount());
            }
            fees += fee;
        }
        feesOut[0] = fees;
        return txs;
    }

    @Test
    void generatedBlocksAreValidAndMixOutcomes() {
        for (int seed = 0; seed < 3; seed++) {
            int[] counts = run(seed, 60);
            assertTrue(counts[0] > 5, "extended " + counts[0]);
            assertTrue(counts[1] > 0, "side or reorg " + counts[1]);
        }
    }

    @Test
    void liveStateMatchesOracleAfterEveryStepAcrossSeeds() {
        int reorgs = 0;
        int rejected = 0;
        for (int seed = 10; seed < 16; seed++) {
            int[] counts = run(seed, 70);
            reorgs += counts[2];
            rejected += counts[3];
        }
        assertTrue(reorgs >= 3, "reorganisations seen: " + reorgs);
        assertTrue(rejected >= 20, "rejections seen: " + rejected);
    }

    /** Returns {extended, side+reorg, reorg, rejected}. */
    private int[] run(int seed, int steps) {
        Random rnd = new Random(seed);
        Chain chain = new Chain(1, SUBSIDY, "alice");
        Oracle oracle = new Oracle();
        oracle.blocks.put(chain.tip(), chain.block(chain.tip()).orElseThrow());
        int extended = 0;
        int sideOrReorg = 0;
        int reorgs = 0;
        int rejected = 0;
        for (int step = 0; step < steps; step++) {
            List<String> ids = new ArrayList<>(oracle.blocks.keySet());
            String parentId = chain.tip();
            if (rnd.nextInt(2) == 0) {
                List<String> recent = new ArrayList<>();
                for (String id : ids) {
                    if (oracle.blocks.get(id).height() + 2 >= chain.height()) {
                        recent.add(id);
                    }
                }
                parentId = recent.get(rnd.nextInt(recent.size()));
            }
            Block parent = oracle.blocks.get(parentId);
            Map<String, String> state = oracle.stateAt(parentId);
            long[] fees = new long[1];
            List<Tx> txs = randomTxs(rnd, state, fees);
            long h = parent.height() + 1;
            List<Tx> all = new ArrayList<>();
            all.add(Tx.coinbase(OWNERS[rnd.nextInt(3)], SUBSIDY + fees[0], h));
            all.addAll(txs);
            String expectMessage = null;
            if (rnd.nextInt(5) == 0) {
                expectMessage = corrupt(all, state, rnd);
            }
            Block b = Block.mine(parentId, h, all, 1);
            Map<TxIn, TxOut> before = chain.utxos();
            String tipBefore = chain.tip();
            if (expectMessage != null) {
                String want = expectMessage;
                InvalidBlockException e = assertThrows(InvalidBlockException.class, () -> chain.add(b));
                assertTrue(e.getMessage().contains(want), e.getMessage() + " vs " + want);
                assertEquals(before, chain.utxos());
                assertEquals(tipBefore, chain.tip());
                rejected++;
            } else {
                Chain.Outcome o = chain.add(b);
                oracle.blocks.put(b.id(), b);
                if (o == Chain.Outcome.EXTENDED) {
                    extended++;
                } else {
                    sideOrReorg++;
                }
                if (o == Chain.Outcome.REORGANISED) {
                    reorgs++;
                }
            }
            chain.checkInvariants();
            assertEquals(oracle.best(), chain.tip(), "best tip, seed " + seed + " step " + step);
            Map<String, String> expected = oracle.stateAt(oracle.best());
            assertEquals(expected, flatten(chain.utxos()), "utxo state, seed " + seed + " step " + step);
            BigInteger supply = BigInteger.ZERO;
            for (String v : expected.values()) {
                supply = supply.add(new BigInteger(v.split(":")[1]));
            }
            assertEquals(BigInteger.valueOf(SUBSIDY).multiply(BigInteger.valueOf(chain.height() + 1)), supply,
                    "supply = subsidy * blocks on the active chain");
        }
        return new int[] {extended, sideOrReorg, reorgs, rejected};
    }

    /** Breaks the block contents in one way and returns the message the chain must give. */
    private static String corrupt(List<Tx> all, Map<String, String> state, Random rnd) {
        int kind = rnd.nextInt(4);
        Tx cb = all.get(0);
        if (kind == 0) {
            all.set(0, Tx.coinbase("evil", cb.outputs().get(0).amount() + 1, Long.parseLong(cb.memo().substring(7))));
            return "coinbase pays too much";
        }
        List<String> keys = new ArrayList<>();
        for (String k : state.keySet()) {
            String[] r = k.split(":");
            TxIn candidate = new TxIn(r[0], Integer.parseInt(r[1]));
            if (all.stream().noneMatch(t -> t.inputs().contains(candidate))) {
                keys.add(k);
            }
        }
        if (keys.isEmpty()) {
            all.set(0, Tx.coinbase("evil", cb.outputs().get(0).amount() + 1, Long.parseLong(cb.memo().substring(7))));
            return "coinbase pays too much";
        }
        keys.sort(null);
        String key = keys.get(rnd.nextInt(keys.size()));
        String[] ownerAmount = state.get(key).split(":");
        long amount = Long.parseLong(ownerAmount[1]);
        String[] ref = key.split(":");
        TxIn in = new TxIn(ref[0], Integer.parseInt(ref[1]));
        // the corrupted tx spends a parent-state output no other tx in the block touches
        if (kind == 1) {
            all.add(new Tx("mallory", List.of(in), List.of(new TxOut("mallory", amount)), "c1"));
            return "signer does not own";
        }
        if (kind == 2) {
            all.add(new Tx(ownerAmount[0], List.of(in), List.of(new TxOut("mallory", amount + 1)), "c2"));
            return "outputs exceed";
        }
        all.add(new Tx(ownerAmount[0], List.of(in), List.of(new TxOut("m", amount)), "c3a"));
        all.add(new Tx(ownerAmount[0], List.of(in), List.of(new TxOut("m", amount)), "c3b"));
        return "missing input";
    }
}
