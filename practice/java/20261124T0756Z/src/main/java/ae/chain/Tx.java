package ae.chain;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;

/** A transaction. A transaction without inputs is a coinbase. */
public record Tx(String signer, List<TxIn> inputs, List<TxOut> outputs, String memo) {
    public Tx {
        Objects.requireNonNull(signer, "signer");
        Objects.requireNonNull(memo, "memo");
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
    }

    /** The block reward transaction for the given height. */
    public static Tx coinbase(String owner, long amount, long height) {
        return new Tx("", List.of(), List.of(new TxOut(owner, amount)), "height:" + height);
    }

    public boolean isCoinbase() {
        return inputs.isEmpty();
    }

    /** SHA-256 of an unambiguous, length-prefixed encoding. */
    public String id() {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(signer);
            out.writeInt(inputs.size());
            for (TxIn in : inputs) {
                out.writeUTF(in.txId());
                out.writeInt(in.index());
            }
            out.writeInt(outputs.size());
            for (TxOut o : outputs) {
                out.writeUTF(o.owner());
                out.writeLong(o.amount());
            }
            out.writeUTF(memo);
            out.flush();
            return Hashing.sha256Hex(bytes.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
