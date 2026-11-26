package ae.classfile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class DescriptorTest {
    @ParameterizedTest
    @CsvSource({
        "I,I,0,1", "J,J,0,2", "D,D,0,2", "Z,Z,0,1", "[J,J,1,1", "[[Ljava/lang/String;,L,2,1",
        "Ljava/util/Map$Entry;,L,0,1",
    })
    void parsesFieldDescriptors(String desc, char kind, int dims, int slots) {
        Type t = Descriptor.parseField(desc);
        assertEquals(kind, t.kind());
        assertEquals(dims, t.dims());
        assertEquals(slots, t.slots());
    }

    @Test
    void classNameIsKept() {
        assertEquals("java/lang/String", Descriptor.parseField("[Ljava/lang/String;").className());
    }

    @Test
    void parsesMethodDescriptor() {
        MethodDescriptor m = Descriptor.parseMethod("(IJ[DLjava/lang/Object;)V");
        assertEquals(4, m.params().size());
        assertEquals(1 + 2 + 1 + 1, m.argSlots());
        assertTrue(m.ret().isVoid());
        assertEquals(0, m.ret().slots());
        assertEquals(2, Descriptor.parseMethod("()D").ret().slots());
        assertEquals(0, Descriptor.parseMethod("()V").argSlots());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "V", "Q", "L;", "Lx", "Ljava/lang/String", "[", "[V", "II", "I)", "(I", "()", "(V)V", "(I)VV",
        "(I)", "(Ljava/lang/String)V", "Ljava.lang.String;", "[[", "L;V"})
    void rejectsBadDescriptors(String d) {
        if (d.startsWith("(") || d.endsWith(")V") || d.equals("()") || d.equals("I)") || d.equals("(I)VV")) {
            assertThrows(ClassFormatException.class, () -> Descriptor.parseMethod(d), d);
        } else {
            assertThrows(ClassFormatException.class, () -> Descriptor.parseField(d), d);
        }
    }

    @Test
    void fieldDescriptorRejectsVoidButReturnAcceptsIt() {
        assertThrows(ClassFormatException.class, () -> Descriptor.parseField("V"));
        assertTrue(Descriptor.parseMethod("()V").ret().isVoid());
        assertFalse(Descriptor.parseMethod("()I").ret().isVoid());
        assertThrows(ClassFormatException.class, () -> Descriptor.parseMethod("()[V"));
    }

    @Test
    void slotLimitIsExactly255() {
        assertEquals(255, Descriptor.parseMethod("(" + "I".repeat(255) + ")V").argSlots());
        assertThrows(ClassFormatException.class, () -> Descriptor.parseMethod("(" + "I".repeat(256) + ")V"));
        assertEquals(255, Descriptor.parseMethod("(" + "J".repeat(127) + "I)V").argSlots());
        assertThrows(ClassFormatException.class, () -> Descriptor.parseMethod("(" + "J".repeat(128) + ")V"));
    }

    @Test
    void dimensionLimitIsExactly255() {
        assertEquals(255, Descriptor.parseField("[".repeat(255) + "I").dims());
        assertThrows(ClassFormatException.class, () -> Descriptor.parseField("[".repeat(256) + "I"));
    }
}
