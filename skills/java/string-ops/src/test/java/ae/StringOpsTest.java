package ae;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StringOpsTest {
    @Test
    void encodeBasic() {
        assertEquals("3a1b2c", StringOps.runLengthEncode("aaabcc"));
        assertEquals("", StringOps.runLengthEncode(""));
        assertEquals("12x", StringOps.runLengthEncode("x".repeat(12)));
        assertEquals("2 1a", StringOps.runLengthEncode("  a"));
    }

    @Test
    void encodeRejectsDigitsAndNull() {
        assertThrows(IllegalArgumentException.class, () -> StringOps.runLengthEncode("a1b"));
        assertThrows(NullPointerException.class, () -> StringOps.runLengthEncode(null));
    }

    @Test
    void decodeBasicAndMultiDigit() {
        assertEquals("aaabcc", StringOps.runLengthDecode("3a1b2c"));
        assertEquals("", StringOps.runLengthDecode(""));
        assertEquals("x".repeat(12), StringOps.runLengthDecode("12x"));
        assertEquals("  a", StringOps.runLengthDecode("2 1a"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "abc", "3", "3a2", "0a", "00a", "99999999999a", "2147483647a2147483647b"})
    void decodeRejectsMalformed(String bad) {
        assertThrows(IllegalArgumentException.class, () -> StringOps.runLengthDecode(bad));
    }

    @Test
    void roundTrip() {
        String s = "aaaaaaaaaaaabbbZ  \tq";
        assertEquals(s, StringOps.runLengthDecode(StringOps.runLengthEncode(s)));
    }

    @Test
    void palindromeIgnoresCaseAndPunctuation() {
        assertTrue(StringOps.isPalindrome("A man, a plan, a canal: Panama"));
        assertTrue(StringOps.isPalindrome(""));
        assertTrue(StringOps.isPalindrome("!!!"));
        assertTrue(StringOps.isPalindrome("12321"));
        assertFalse(StringOps.isPalindrome("hello"));
        assertFalse(StringOps.isPalindrome("ab"));
        assertFalse(StringOps.isPalindrome("0P"));
    }

    @Test
    void capitalizePreservesWhitespace() {
        assertEquals("Hello World", StringOps.capitalizeWords("hELLO wORLD"));
        assertEquals("  A  B\tC\n", StringOps.capitalizeWords("  a  b\tc\n"));
        assertEquals("", StringOps.capitalizeWords(""));
        assertEquals("1abc", StringOps.capitalizeWords("1ABC"));
    }
}
