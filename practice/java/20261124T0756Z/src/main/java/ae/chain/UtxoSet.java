package ae.chain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The set of unspent outputs, with atomic block application and undo. */
final class UtxoSet {
    /** One reversible change: created or spent output. */
    record Op(TxIn key, TxOut value, boolean created) {
    }

    /** The log needed to revert one block. */
    record Undo(List<Op> ops) {
    }

    private final Map<TxIn, TxOut> map = new HashMap<>();

    Map<TxIn, TxOut> snapshot() {
        return new HashMap<>(map);
    }

    long balance(String owner) {
        long sum = 0;
        for (TxOut o : map.values()) {
            if (o.owner().equals(owner)) {
                sum += o.amount();
            }
        }
        return sum;
    }

    /** Validates and applies a block; on failure the set is left unchanged. */
    Undo apply(Block block, long subsidy) {
        List<Op> ops = new ArrayList<>();
        try {
            applyChecked(block, subsidy, ops);
        } catch (InvalidBlockException e) {
            undo(new Undo(ops));
            throw e;
        }
        return new Undo(ops);
    }

    private void applyChecked(Block block, long subsidy, List<Op> ops) {
        Set<String> seen = new HashSet<>();
        long fees = 0;
        for (int t = 0; t < block.txs().size(); t++) {
            Tx tx = block.txs().get(t);
            if (!seen.add(tx.id())) {
                throw new InvalidBlockException("duplicate transaction " + tx.id());
            }
            if (t == 0) {
                if (!tx.isCoinbase()) {
                    throw new InvalidBlockException("first transaction must be coinbase");
                }
                if (!tx.memo().equals("height:" + block.height())) {
                    throw new InvalidBlockException("bad coinbase memo");
                }
                continue;
            }
            if (tx.isCoinbase()) {
                throw new InvalidBlockException("unexpected coinbase");
            }
            fees = addChecked(fees, applyTx(tx, ops));
        }
        Tx coinbase = block.txs().get(0);
        long paid = 0;
        for (TxOut o : coinbase.outputs()) {
            paid = addChecked(paid, o.amount());
        }
        if (paid > addChecked(subsidy, fees)) {
            throw new InvalidBlockException("coinbase pays too much");
        }
        add(coinbase, ops);
    }

    private long applyTx(Tx tx, List<Op> ops) {
        if (tx.outputs().isEmpty()) {
            throw new InvalidBlockException("transaction has no outputs");
        }
        long in = 0;
        for (TxIn ref : tx.inputs()) {
            TxOut spent = map.get(ref);
            if (spent == null) {
                throw new InvalidBlockException("missing input " + ref.txId() + ":" + ref.index());
            }
            if (!spent.owner().equals(tx.signer())) {
                throw new InvalidBlockException("signer does not own input");
            }
            in = addChecked(in, spent.amount());
            map.remove(ref);
            ops.add(new Op(ref, spent, false));
        }
        long out = 0;
        for (TxOut o : tx.outputs()) {
            out = addChecked(out, o.amount());
        }
        if (out > in) {
            throw new InvalidBlockException("outputs exceed inputs");
        }
        add(tx, ops);
        return in - out;
    }

    private void add(Tx tx, List<Op> ops) {
        String id = tx.id();
        for (int i = 0; i < tx.outputs().size(); i++) {
            TxIn key = new TxIn(id, i);
            map.put(key, tx.outputs().get(i));
            ops.add(new Op(key, tx.outputs().get(i), true));
        }
    }

    private static long addChecked(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            throw new InvalidBlockException("amount overflow");
        }
    }

    /** Reverts a previously returned log. */
    void undo(Undo undo) {
        List<Op> ops = undo.ops();
        for (int i = ops.size() - 1; i >= 0; i--) {
            Op op = ops.get(i);
            if (op.created()) {
                map.remove(op.key());
            } else {
                map.put(op.key(), op.value());
            }
        }
    }
}
