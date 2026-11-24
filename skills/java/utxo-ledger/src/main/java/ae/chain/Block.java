package ae.chain;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;

/** A block; its id is the SHA-256 of the header (not the transactions). */
public record Block(String prevId, long height, long nonce, String merkleRoot, List<Tx> txs) {
    public Block {
        Objects.requireNonNull(prevId, "prevId");
        Objects.requireNonNull(merkleRoot, "merkleRoot");
        txs = List.copyOf(txs);
    }

    public String id() {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(prevId);
            out.writeLong(height);
            out.writeLong(nonce);
            out.writeUTF(merkleRoot);
            out.flush();
            return Hashing.sha256Hex(bytes.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<String> txIds() {
        return txs.stream().map(Tx::id).toList();
    }

    /** Builds a block with a correct Merkle root and a nonce meeting the difficulty. */
    public static Block mine(String prevId, long height, List<Tx> txs, int difficulty) {
        String root = Merkle.root(txs.stream().map(Tx::id).toList());
        for (long nonce = 0;; nonce++) {
            Block b = new Block(prevId, height, nonce, root, txs);
            if (Hashing.leadingZeroHexDigits(b.id()) >= difficulty) {
                return b;
            }
        }
    }
}
