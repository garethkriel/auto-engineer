package ae.qmc;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ImplicantTest {
    @Test
    void valueIsNormalisedUnderMask() {
        assertEquals(new Implicant(0b1000, 0b0011), new Implicant(0b1011, 0b0011));
        assertEquals(0b1000, new Implicant(0b1011, 0b0011).value());
    }

    @Test
    void coversAndLiterals() {
        Implicant i = new Implicant(0b1000, 0b0011); // 10-- over 4 vars: A B'
        assertTrue(i.covers(0b1000));
        assertTrue(i.covers(0b1011));
        assertFalse(i.covers(0b1100));
        assertFalse(i.covers(0b0000));
        assertEquals(2, i.literals(4));
        assertEquals(0, new Implicant(0, 0b1111).literals(4));
    }

    @Test
    void combineNeedsSameMaskAndOneBitDifference() {
        Implicant a = new Implicant(0b0100, 0), b = new Implicant(0b1100, 0);
        assertEquals(Optional.of(new Implicant(0b0100, 0b1000)), a.combine(b));
        assertEquals(a.combine(b), b.combine(a));
        assertEquals(Optional.empty(), a.combine(new Implicant(0b0011, 0))); // two bits differ
        assertEquals(Optional.empty(), a.combine(a)); // identical
        assertEquals(Optional.empty(), a.combine(new Implicant(0b0100, 0b0001))); // masks differ
        // masks differ AND the values differ in exactly one bit: only the mask check rejects it
        assertEquals(Optional.empty(), a.combine(new Implicant(0b1100, 0b0001)));
    }

    @Test
    void toStringUsesLettersMsbFirst() {
        assertEquals("AB'", new Implicant(0b10, 0).toString(2));
        assertEquals("BC'D'", new Implicant(0b0100, 0b1000).toString(4));
        assertEquals("1", new Implicant(0, 0b111).toString(3));
        assertEquals("A'", new Implicant(0b000, 0b011).toString(3));
    }

    @Test
    void canonicalOrderIsValueThenMask() {
        assertTrue(new Implicant(1, 0).compareTo(new Implicant(2, 0)) < 0);
        assertTrue(new Implicant(0, 1).compareTo(new Implicant(0, 2)) < 0);
        assertEquals(0, new Implicant(3, 4).compareTo(new Implicant(3, 4)));
    }
}
