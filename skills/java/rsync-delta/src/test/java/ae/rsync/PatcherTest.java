package ae.rsync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PatcherTest {
    @Test
    void appliesCopiesAndLiterals() throws Exception {
        byte[] basis = TestData.ascii("0123456789");
        byte[] target = TestData.ascii("89--0123");
        Delta d = new Delta(List.of(new Delta.Copy(8, 2), new Delta.Literal(TestData.ascii("--")), new Delta.Copy(0, 4)),
                8, Delta.sha256(target));
        assertArrayEquals(target, Patcher.apply(basis, d));
    }

    @Test
    void sameLengthButDifferentBasisIsDetected() {
        byte[] basis = TestData.random(50, 300);
        byte[] target = TestData.concat(TestData.slice(basis, 0, 100), TestData.random(51, 20), TestData.slice(basis, 100, 300));
        Delta d = DeltaEncoder.compute(Signature.of(basis, 32), target);
        byte[] other = basis.clone();
        other[150] ^= 1;
        PatchException e = assertThrows(PatchException.class, () -> Patcher.apply(other, d));
        assertTrue(e.getMessage().contains("digest"), e.getMessage());
    }

    @Test
    void copyBeyondBasisIsDetected() {
        byte[] basis = TestData.random(52, 20);
        Delta d = new Delta(List.of(new Delta.Copy(10, 15)), 15, Delta.sha256(new byte[15]));
        PatchException e = assertThrows(PatchException.class, () -> Patcher.apply(basis, d));
        assertTrue(e.getMessage().contains("range"), e.getMessage());
    }

    @Test
    void copyRangeCheckSurvivesOverflow() {
        byte[] basis = TestData.random(53, 20);
        Delta d = new Delta(List.of(new Delta.Copy(Long.MAX_VALUE - 2, 10)), 10, Delta.sha256(new byte[10]));
        assertThrows(PatchException.class, () -> Patcher.apply(basis, d));
    }

    @Test
    void emptyDeltaYieldsEmptyOutput() throws Exception {
        Delta d = new Delta(List.of(), 0, Delta.sha256(new byte[0]));
        assertArrayEquals(new byte[0], Patcher.apply(TestData.random(54, 9), d));
    }

    @Test
    void copyRecordRejectsNegativeOffsetAndNonPositiveLength() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new Delta.Copy(-1, 3));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new Delta.Copy(0, 0));
    }
}
