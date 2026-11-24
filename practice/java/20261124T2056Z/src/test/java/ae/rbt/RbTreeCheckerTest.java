package ae.rbt;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Comparator;
import org.junit.jupiter.api.Test;

/** One hand-built corrupt tree per rule of {@link RbTreeMap#check()}, plus valid controls. */
class RbTreeCheckerTest {

    private static RbTreeMap.Node<Integer, String> n(int key, boolean red, int size) {
        RbTreeMap.Node<Integer, String> x = new RbTreeMap.Node<>();
        x.key = key;
        x.val = "v";
        x.red = red;
        x.size = size;
        return x;
    }

    private static RbTreeMap<Integer, String> tree(RbTreeMap.Node<Integer, String> root) {
        return RbTreeMap.of(Comparator.<Integer>naturalOrder(), root);
    }

    private static void assertRule(String prefix, RbTreeMap.Node<Integer, String> root) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> tree(root).check());
        assertTrue(e.getMessage().startsWith(prefix), "expected " + prefix + " but was: " + e.getMessage());
    }

    @Test
    void validHandBuiltTreesPass() {
        tree(null).check();
        tree(n(1, false, 1)).check();
        // black 2 with a red left child (a 3-node)
        RbTreeMap.Node<Integer, String> root = n(2, false, 2);
        root.left = n(1, true, 1);
        tree(root).check();
        // black 2 with two black children
        RbTreeMap.Node<Integer, String> full = n(2, false, 3);
        full.left = n(1, false, 1);
        full.right = n(3, false, 1);
        tree(full).check();
    }

    @Test
    void redRootRejected() {
        assertRule("root-red", n(1, true, 1));
    }

    @Test
    void redRightLinkRejected() {
        RbTreeMap.Node<Integer, String> root = n(1, false, 2);
        root.right = n(2, true, 1);
        assertRule("red-right", root);
    }

    @Test
    void twoRedsInARowRejected() {
        RbTreeMap.Node<Integer, String> root = n(2, false, 3);
        root.left = n(1, true, 2);
        root.left.left = n(0, true, 1);
        assertRule("double-red", root);
    }

    @Test
    void unequalBlackHeightRejected() {
        RbTreeMap.Node<Integer, String> root = n(2, false, 2);
        root.left = n(1, false, 1); // right side is empty: black height 0 vs 1
        assertRule("black-height", root);
    }

    @Test
    void misorderedKeyRejected() {
        RbTreeMap.Node<Integer, String> root = n(1, false, 2);
        root.left = n(5, true, 1);
        assertRule("order", root);
    }

    @Test
    void keyBelowAnAncestorBoundRejected() {
        // 4 is a valid right child of nothing local (4 > 3 is not required here); it sits in the right
        // subtree of 6 but is smaller than 6: only a bounds-based check sees it.
        RbTreeMap.Node<Integer, String> root = n(6, false, 4);
        root.left = n(3, false, 1);
        RbTreeMap.Node<Integer, String> right = n(9, false, 2);
        right.left = n(4, true, 1);
        root.right = right;
        assertRule("order", root);
    }

    @Test
    void wrongSizeRejected() {
        RbTreeMap.Node<Integer, String> root = n(2, false, 5);
        root.left = n(1, true, 1);
        assertRule("size", root);
    }

    @Test
    void sizeOfLeafMustBeOne() {
        assertRule("size", n(1, false, 2));
    }

    @Test
    void duplicateKeyRejected() {
        RbTreeMap.Node<Integer, String> root = n(2, false, 2);
        root.left = n(2, true, 1);
        assertRule("order", root);
    }
}
