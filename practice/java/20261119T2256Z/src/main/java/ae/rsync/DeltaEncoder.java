package ae.rsync;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Sender side: finds target regions that already exist in the receiver's basis. */
public final class DeltaEncoder {
    private DeltaEncoder() {}

    public static Delta compute(Signature sig, byte[] target) {
        Objects.requireNonNull(sig, "sig");
        Objects.requireNonNull(target, "target");
        int block = sig.blockSize();
        int tailLen = sig.lastBlockLength();
        int n = target.length;

        // (weak, length) -> ascending block indexes. Length is part of the key because the last block may be short.
        Map<Long, List<Integer>> index = new HashMap<>();
        for (int i = 0; i < sig.blocks().size(); i++) {
            index.computeIfAbsent(key(sig.blocks().get(i).weak(), sig.lengthOfBlock(i)), k -> new ArrayList<>()).add(i);
        }

        MessageDigest md = DigestSupport.sha256();
        List<Delta.Op> ops = new ArrayList<>();
        long prevCopyEnd = -1; // basis offset right after the previous op if it was a copy, else -1
        int pos = 0;
        int litFrom = 0;
        Rolling roll = null;
        while (pos < n) {
            int len = Math.min(block, n - pos);
            int match = -1;
            if (len == block || len == tailLen) {
                int weak;
                if (len == block) {
                    if (roll == null) {
                        roll = Rolling.of(target, pos, block);
                    }
                    weak = roll.value();
                } else {
                    weak = Rolling.weak(target, pos, len);
                }
                List<Integer> cands = index.get(key(weak, len));
                if (cands != null) {
                    md.update(target, pos, len);
                    byte[] strong = md.digest();
                    for (int c : cands) {
                        if (Arrays.equals(sig.blocks().get(c).strong(), strong)) {
                            if (match < 0) {
                                match = c;
                            }
                            if ((long) c * block == prevCopyEnd && litFrom == pos) {
                                match = c; // keep the copy contiguous with the previous one
                                break;
                            }
                        }
                    }
                }
            }
            if (match >= 0) {
                if (litFrom < pos) {
                    ops.add(new Delta.Literal(Arrays.copyOfRange(target, litFrom, pos)));
                    prevCopyEnd = -1;
                }
                long off = (long) match * block;
                int last = ops.size() - 1;
                if (last >= 0 && ops.get(last) instanceof Delta.Copy prev && prev.offset() + prev.length() == off) {
                    ops.set(last, new Delta.Copy(prev.offset(), prev.length() + len));
                } else {
                    ops.add(new Delta.Copy(off, len));
                }
                prevCopyEnd = off + len;
                pos += len;
                litFrom = pos;
                roll = null;
            } else {
                pos++;
                if (roll != null && len == block && pos + block <= n) {
                    roll.roll(target[pos - 1], target[pos + block - 1]);
                } else {
                    roll = null;
                }
            }
        }
        if (litFrom < n) {
            ops.add(new Delta.Literal(Arrays.copyOfRange(target, litFrom, n)));
        }
        return new Delta(ops, n, Delta.sha256(target));
    }

    private static long key(int weak, int len) {
        return (weak & 0xFFFFFFFFL) | ((long) len << 32);
    }
}
