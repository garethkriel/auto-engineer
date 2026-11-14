package cmd;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class EditorTest {
    private static StringBuilder sb(String s) {
        return new StringBuilder(s);
    }

    @Test
    void insertReturnsDeleteInverse() {
        StringBuilder d = sb("ac");
        Command inv = Commands.insert(1, "b").apply(d);
        assertEquals("abc", d.toString());
        inv.apply(d);
        assertEquals("ac", d.toString());
    }

    @Test
    void deleteInverseRestoresRemovedText() {
        StringBuilder d = sb("hello world");
        Command inv = Commands.delete(5, 6).apply(d);
        assertEquals("hello", d.toString());
        inv.apply(d);
        assertEquals("hello world", d.toString());
    }

    @Test
    void replaceInverseRestoresOriginal() {
        StringBuilder d = sb("cat sat");
        Command inv = Commands.replace(0, 3, "horse").apply(d);
        assertEquals("horse sat", d.toString());
        inv.apply(d);
        assertEquals("cat sat", d.toString());
    }

    @Test
    void insertAtEndAllowedButBeyondRejectedWithoutChange() {
        StringBuilder d = sb("ab");
        Commands.insert(2, "c").apply(d);
        assertEquals("abc", d.toString());
        assertThrows(IllegalArgumentException.class, () -> Commands.insert(4, "x").apply(d));
        assertThrows(IllegalArgumentException.class, () -> Commands.insert(-1, "x").apply(d));
        assertEquals("abc", d.toString());
    }

    @Test
    void deleteOutOfRangeRejectedWithoutChange() {
        StringBuilder d = sb("abc");
        assertThrows(IllegalArgumentException.class, () -> Commands.delete(1, 3).apply(d));
        assertThrows(IllegalArgumentException.class, () -> Commands.delete(0, -1).apply(d));
        assertThrows(IllegalArgumentException.class, () -> Commands.delete(Integer.MAX_VALUE, 5).apply(d));
        assertThrows(IllegalArgumentException.class, () -> Commands.delete(2, Integer.MAX_VALUE).apply(d));
        assertEquals("abc", d.toString());
    }

    @Test
    void nullTextRejected() {
        assertThrows(NullPointerException.class, () -> Commands.insert(0, null));
        assertThrows(NullPointerException.class, () -> Commands.replace(0, 0, null));
        assertThrows(NullPointerException.class, () -> Commands.macro(null));
    }

    @Test
    void macroAppliesInOrderAndInverseUndoesInReverse() {
        StringBuilder d = sb("");
        Command m = Commands.macro(List.of(Commands.insert(0, "abc"), Commands.insert(3, "def"), Commands.delete(0, 1)));
        Command inv = m.apply(d);
        assertEquals("bcdef", d.toString());
        inv.apply(d);
        assertEquals("", d.toString());
    }

    @Test
    void failingMacroRollsBackEarlierSteps() {
        StringBuilder d = sb("xy");
        Command m = Commands.macro(List.of(Commands.insert(0, "AB"), Commands.delete(0, 1), Commands.delete(10, 1)));
        assertThrows(IllegalArgumentException.class, () -> m.apply(d));
        assertEquals("xy", d.toString());
    }

    @Test
    void macroSnapshotsItsStepList() {
        java.util.ArrayList<Command> steps = new java.util.ArrayList<>(List.of(Commands.insert(0, "a")));
        Command m = Commands.macro(steps);
        steps.add(Commands.insert(0, "z"));
        StringBuilder d = sb("");
        m.apply(d);
        assertEquals("a", d.toString());
    }

    @Test
    void undoRedoRoundTrip() {
        Editor e = new Editor(10);
        e.execute(Commands.insert(0, "hello"));
        e.execute(Commands.insert(5, " world"));
        assertEquals("hello world", e.text());
        assertTrue(e.undo());
        assertEquals("hello", e.text());
        assertTrue(e.undo());
        assertEquals("", e.text());
        assertFalse(e.undo());
        assertFalse(e.canUndo());
        assertTrue(e.redo());
        assertTrue(e.redo());
        assertEquals("hello world", e.text());
        assertFalse(e.redo());
    }

    @Test
    void executeClearsRedo() {
        Editor e = new Editor(10);
        e.execute(Commands.insert(0, "a"));
        e.undo();
        assertTrue(e.canRedo());
        e.execute(Commands.insert(0, "b"));
        assertFalse(e.canRedo());
        assertEquals("b", e.text());
    }

    @Test
    void failedExecuteChangesNothing() {
        Editor e = new Editor(10);
        e.execute(Commands.insert(0, "a"));
        e.undo();
        assertThrows(IllegalArgumentException.class, () -> e.execute(Commands.delete(5, 1)));
        assertEquals("", e.text());
        assertTrue(e.canRedo(), "redo must survive a failed execute");
        assertFalse(e.canUndo());
    }

    @Test
    void historyLimitDropsOldest() {
        Editor e = new Editor(2);
        e.execute(Commands.insert(0, "a"));
        e.execute(Commands.insert(1, "b"));
        e.execute(Commands.insert(2, "c"));
        assertTrue(e.undo());
        assertTrue(e.undo());
        assertFalse(e.undo());
        assertEquals("a", e.text());
    }

    @Test
    void invalidLimitRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Editor(0));
        assertThrows(IllegalArgumentException.class, () -> new Editor(-3));
    }

    @Test
    void dirtyTracking() {
        Editor e = new Editor(5);
        assertFalse(e.isDirty());
        e.execute(Commands.insert(0, "a"));
        assertTrue(e.isDirty());
        e.save();
        assertFalse(e.isDirty());
        e.execute(Commands.insert(1, "b"));
        assertTrue(e.isDirty());
        e.undo();
        assertFalse(e.isDirty(), "undo back to the save point is clean");
        e.undo();
        assertTrue(e.isDirty());
        e.redo();
        assertFalse(e.isDirty());
    }

    @Test
    void divergingHistoryAfterSaveStaysDirty() {
        Editor e = new Editor(5);
        e.execute(Commands.insert(0, "a"));
        e.execute(Commands.insert(1, "b"));
        e.save();
        e.undo();
        e.execute(Commands.insert(1, "c"));
        e.undo();
        e.redo();
        assertTrue(e.isDirty(), "different edit must not look like the saved state");
    }

    @Test
    void macroIsOneUndoStep() {
        Editor e = new Editor(5);
        e.execute(Commands.macro(List.of(Commands.insert(0, "ab"), Commands.insert(2, "cd"))));
        assertEquals("abcd", e.text());
        e.undo();
        assertEquals("", e.text());
    }

    @Test
    void emptyEditsStillRecordHistory() {
        Editor e = new Editor(5);
        e.execute(Commands.insert(0, ""));
        assertTrue(e.canUndo());
        assertTrue(e.undo());
        assertEquals("", e.text());
    }
}
