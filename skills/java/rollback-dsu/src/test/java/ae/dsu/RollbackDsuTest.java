package ae.dsu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RollbackDsuTest {
    @Test
    void startsAsSingletons() {
        RollbackDsu d = new RollbackDsu(4);
        assertEquals(4, d.components());
        assertFalse(d.connected(0, 1));
        assertEquals(1, d.componentSize(3));
    }

    @Test
    void unionMergesAndReportsNoOps() {
        RollbackDsu d = new RollbackDsu(5);
        assertTrue(d.union(0, 1));
        assertTrue(d.union(1, 2));
        assertFalse(d.union(0, 2));
        assertTrue(d.connected(0, 2));
        assertEquals(3, d.componentSize(1));
        assertEquals(3, d.components());
        assertEquals(2, d.snapshot(), "no-op union must not be recorded");
    }

    @Test
    void rollbackRestoresExactPreviousState() {
        RollbackDsu d = new RollbackDsu(6);
        d.union(0, 1);
        int mark = d.snapshot();
        d.union(2, 3);
        d.union(1, 3);
        d.union(4, 5);
        assertTrue(d.connected(0, 2));
        assertEquals(2, d.components());
        d.rollbackTo(mark);
        assertTrue(d.connected(0, 1));
        assertFalse(d.connected(2, 3));
        assertFalse(d.connected(0, 2));
        assertEquals(5, d.components());
        assertEquals(2, d.componentSize(0));
        assertEquals(1, d.componentSize(4));
    }

    @Test
    void undoOneStepAndEmptyUndoThrows() {
        RollbackDsu d = new RollbackDsu(3);
        assertThrows(IllegalStateException.class, d::undo);
        d.union(0, 1);
        d.undo();
        assertFalse(d.connected(0, 1));
        assertEquals(3, d.components());
        assertThrows(IllegalStateException.class, d::undo);
    }

    @Test
    void canReapplyAfterRollback() {
        RollbackDsu d = new RollbackDsu(4);
        d.union(0, 1);
        d.union(2, 3);
        d.rollbackTo(0);
        assertTrue(d.union(1, 2));
        assertEquals(2, d.componentSize(1), "only 1-2 is merged after the rollback");
        assertEquals(1, d.componentSize(0));
        assertFalse(d.connected(2, 3));
        assertEquals(3, d.components());
    }

    @Test
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> new RollbackDsu(-1));
        RollbackDsu d = new RollbackDsu(2);
        assertThrows(IllegalArgumentException.class, () -> d.find(2));
        assertThrows(IllegalArgumentException.class, () -> d.union(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> d.rollbackTo(1));
        assertThrows(IllegalArgumentException.class, () -> d.rollbackTo(-1));
        assertEquals(0, new RollbackDsu(0).components());
    }

    @Test
    void longChainKeepsTreeShallow() {
        int n = 1 << 12;
        RollbackDsu d = new RollbackDsu(n);
        for (int i = 1; i < n; i++) {
            d.union(i - 1, i);
        }
        assertEquals(1, d.components());
        assertEquals(n, d.componentSize(0));
        d.rollbackTo(0);
        assertEquals(n, d.components());
    }
}
