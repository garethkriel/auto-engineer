package ae.chain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MerkleTest {
    private static String h(String s) {
        return Hashing.sha256Hex(s);
    }

    private static List<String> ids(int n) {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            l.add("tx" + i);
        }
        return l;
    }

    @Test
    void sha256KnownAnswer() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", h("abc"));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", h(""));
    }

    @Test
    void singleLeafRootIsLeafHash() {
        assertEquals(h("La"), Merkle.root(List.of("a")));
    }

    @Test
    void threeLeavesPromoteOddNode() {
        String ab = h("N" + h("La") + h("Lb"));
        assertEquals(h("N" + ab + h("Lc")), Merkle.root(List.of("a", "b", "c")));
    }

    @Test
    void fiveLeavesShape() {
        String ab = h("N" + h("La") + h("Lb"));
        String cd = h("N" + h("Lc") + h("Ld"));
        String abcd = h("N" + ab + cd);
        assertEquals(h("N" + abcd + h("Le")), Merkle.root(List.of("a", "b", "c", "d", "e")));
    }

    @Test
    void duplicatingLastLeafChangesRoot() {
        assertNotEquals(Merkle.root(List.of("a", "b", "c")), Merkle.root(List.of("a", "b", "c", "c")));
    }

    @Test
    void leafAndNodeAreDomainSeparated() {
        String ab = h("N" + h("La") + h("Lb"));
        assertNotEquals(Merkle.root(List.of("a", "b")), Merkle.root(List.of(ab)));
    }

    @Test
    void orderMatters() {
        assertNotEquals(Merkle.root(List.of("a", "b")), Merkle.root(List.of("b", "a")));
    }

    @Test
    void everyIndexOfEverySizeVerifies() {
        for (int n = 1; n <= 40; n++) {
            List<String> ids = ids(n);
            String root = Merkle.root(ids);
            for (int i = 0; i < n; i++) {
                assertTrue(Merkle.verify(ids.get(i), Merkle.proof(ids, i), root), n + "/" + i);
            }
        }
    }

    @Test
    void tamperedProofsFail() {
        List<String> ids = ids(7);
        String root = Merkle.root(ids);
        List<Merkle.Step> proof = Merkle.proof(ids, 3);
        assertFalse(Merkle.verify("other", proof, root));
        assertFalse(Merkle.verify(ids.get(3), proof, h("x")));
        List<Merkle.Step> flipped = new ArrayList<>(proof);
        flipped.set(0, new Merkle.Step(flipped.get(0).sibling(), !flipped.get(0).left()));
        assertFalse(Merkle.verify(ids.get(3), flipped, root));
        assertFalse(Merkle.verify(ids.get(3), proof.subList(1, proof.size()), root));
        assertFalse(Merkle.verify(ids.get(2), proof, root));
    }

    @Test
    void rejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> Merkle.root(List.of()));
        assertThrows(IllegalArgumentException.class, () -> Merkle.proof(ids(3), 3));
        assertThrows(IllegalArgumentException.class, () -> Merkle.proof(ids(3), -1));
    }
}
