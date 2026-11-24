package ae.chain;

import java.util.ArrayList;
import java.util.List;

/** Merkle trees over transaction ids with inclusion proofs. */
public final class Merkle {
    private Merkle() {
    }

    /** One proof step: the sibling hash and whether it sits to the left. */
    public record Step(String sibling, boolean left) {
    }

    static String leaf(String id) {
        return Hashing.sha256Hex("L" + id);
    }

    static String node(String left, String right) {
        return Hashing.sha256Hex("N" + left + right);
    }

    /** Root of the tree; an odd last node is promoted unchanged. */
    public static String root(List<String> ids) {
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("no leaves");
        }
        List<String> level = new ArrayList<>();
        for (String id : ids) {
            level.add(leaf(id));
        }
        while (level.size() > 1) {
            level = parents(level);
        }
        return level.get(0);
    }

    private static List<String> parents(List<String> level) {
        List<String> next = new ArrayList<>();
        for (int i = 0; i < level.size(); i += 2) {
            next.add(i + 1 < level.size() ? node(level.get(i), level.get(i + 1)) : level.get(i));
        }
        return next;
    }

    /** Proof that {@code ids.get(index)} is in the tree. */
    public static List<Step> proof(List<String> ids, int index) {
        if (index < 0 || index >= ids.size()) {
            throw new IllegalArgumentException("index out of range: " + index);
        }
        List<String> level = new ArrayList<>();
        for (String id : ids) {
            level.add(leaf(id));
        }
        List<Step> steps = new ArrayList<>();
        int i = index;
        while (level.size() > 1) {
            if (i % 2 == 1) {
                steps.add(new Step(level.get(i - 1), true));
            } else if (i + 1 < level.size()) {
                steps.add(new Step(level.get(i + 1), false));
            }
            level = parents(level);
            i /= 2;
        }
        return steps;
    }

    /** Checks a proof against a root. */
    public static boolean verify(String id, List<Step> proof, String root) {
        String h = leaf(id);
        for (Step s : proof) {
            h = s.left() ? node(s.sibling(), h) : node(h, s.sibling());
        }
        return h.equals(root);
    }
}
