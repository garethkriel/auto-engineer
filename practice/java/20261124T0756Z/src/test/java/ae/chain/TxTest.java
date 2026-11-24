package ae.chain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TxTest {
    @Test
    void idIsDeterministicAndHex() {
        Tx a = new Tx("alice", List.of(new TxIn("x", 0)), List.of(new TxOut("bob", 5)), "m");
        Tx b = new Tx("alice", List.of(new TxIn("x", 0)), List.of(new TxOut("bob", 5)), "m");
        assertEquals(a.id(), b.id());
        assertEquals(64, a.id().length());
    }

    @Test
    void fieldBoundariesAreUnambiguous() {
        Tx a = new Tx("a", List.of(), List.of(new TxOut("bc", 1)), "");
        Tx b = new Tx("ab", List.of(), List.of(new TxOut("c", 1)), "");
        assertNotEquals(a.id(), b.id());
        Tx c = new Tx("s", List.of(), List.of(new TxOut("o", 1)), "xy");
        Tx d = new Tx("s", List.of(), List.of(new TxOut("ox", 1)), "y");
        assertNotEquals(c.id(), d.id());
    }

    @Test
    void everyFieldAffectsId() {
        Tx base = new Tx("s", List.of(new TxIn("x", 0)), List.of(new TxOut("o", 1)), "m");
        assertNotEquals(base.id(), new Tx("t", base.inputs(), base.outputs(), "m").id());
        assertNotEquals(base.id(), new Tx("s", List.of(new TxIn("x", 1)), base.outputs(), "m").id());
        assertNotEquals(base.id(), new Tx("s", base.inputs(), List.of(new TxOut("o", 2)), "m").id());
        assertNotEquals(base.id(), new Tx("s", base.inputs(), base.outputs(), "n").id());
    }

    @Test
    void coinbaseHasNoInputsAndHeightMemo() {
        Tx cb = Tx.coinbase("miner", 50, 7);
        assertTrue(cb.isCoinbase());
        assertEquals("height:7", cb.memo());
        assertNotEquals(cb.id(), Tx.coinbase("miner", 50, 8).id());
    }

    @Test
    void rejectsBadValues() {
        assertThrows(IllegalArgumentException.class, () -> new TxOut("a", 0));
        assertThrows(IllegalArgumentException.class, () -> new TxOut("a", -3));
        assertThrows(IllegalArgumentException.class, () -> new TxIn("a", -1));
    }
}
