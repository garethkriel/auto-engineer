package ae.proto;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class SchemaTest {
    static final String GOOD = """
            // a comment
            message Outer {
              int32 a = 1;   // trailing comment
              repeated sint64 xs = 4;
              Inner in = 3;
              repeated Outer kids = 9;
            }
            message Inner { string s = 2; bytes raw = 1; bool flag = 7; }
            """;

    @Test
    void parsesMessagesFieldsAndForwardAndRecursiveReferences() {
        Schema s = Schema.parse(GOOD);
        assertEquals(List.of("Outer", "Inner"), s.messageNames());
        MessageType outer = s.message("Outer");
        assertEquals("Outer", outer.name());
        assertEquals(List.of(1, 3, 4, 9), outer.fields().stream().map(Field::number).toList());
        Field xs = outer.field("xs");
        assertEquals(4, xs.number());
        assertEquals(Kind.SINT64, xs.kind());
        assertTrue(xs.repeated());
        assertNull(xs.message());
        assertSame(s.message("Inner"), outer.field(3).message());
        assertSame(outer, outer.field("kids").message());
        assertNull(outer.field(2));
        assertNull(outer.field("nope"));
    }

    @Test
    void allScalarKindsParse() {
        String text = "message M { int32 a=1; int64 b=2; uint32 c=3; uint64 d=4; sint32 e=5; sint64 f=6;"
                + " bool g=7; fixed32 h=8; fixed64 i=9; sfixed32 j=10; sfixed64 k=11; float l=12;"
                + " double m=13; string n=14; bytes o=15; }";
        MessageType m = Schema.parse(text).message("M");
        assertEquals(15, m.fields().size());
        assertEquals(Kind.SFIXED64, m.field("k").kind());
        assertEquals(Kind.BYTES, m.field("o").kind());
    }

    @Test
    void missingMessageLookupThrows() {
        assertThrows(IllegalArgumentException.class, () -> Schema.parse(GOOD).message("Nope"));
    }

    private static String err(String text) {
        return assertThrows(SchemaException.class, () -> Schema.parse(text)).getMessage();
    }

    @Test
    void duplicateFieldNumberReportsLine() {
        String m = err("message A {\n int32 a = 1;\n int32 b = 1;\n}");
        assertTrue(m.startsWith("line 3:"), m);
        assertTrue(m.contains("duplicate field number"), m);
    }

    @Test
    void duplicateFieldNameReportsLine() {
        String m = err("message A {\n int32 a = 1;\n int64 a = 2;\n}");
        assertTrue(m.startsWith("line 3:") && m.contains("duplicate field name"), m);
    }

    @Test
    void duplicateMessage() {
        String m = err("message A {}\nmessage A {}");
        assertTrue(m.startsWith("line 2:") && m.contains("duplicate message"), m);
    }

    @Test
    void unknownTypeReportsLineOfField() {
        String m = err("message A {\n Missing x = 1;\n}");
        assertTrue(m.startsWith("line 2:") && m.contains("unknown type Missing"), m);
    }

    @Test
    void numberRange() {
        assertTrue(err("message A { int32 a = 0; }").contains("field number"));
        assertTrue(err("message A { int32 a = 536870912; }").contains("field number"));
        assertTrue(err("message A { int32 a = 19000; }").contains("reserved"));
        assertTrue(err("message A { int32 a = 19999; }").contains("reserved"));
        // controls just outside / at the edges are fine
        Schema.parse("message A { int32 a = 18999; int32 b = 20000; int32 c = 1; int32 d = 536870911; }");
    }

    @Test
    void syntaxErrors() {
        assertTrue(err("message A { int32 a = 1 }").startsWith("line 1:"));
        assertTrue(err("message { int32 a = 1; }").contains("message name"));
        assertTrue(err("message A { int32 a = 1;").contains("end of input"));
        assertTrue(err("message A { int32 a = x; }").contains("number"));
        assertTrue(err("int32 a = 1;").contains("message"));
    }

    @Test
    void emptySchemaAndEmptyMessageAreValid() {
        assertTrue(Schema.parse("// nothing\n").messageNames().isEmpty());
        assertTrue(Schema.parse("message E {}").message("E").fields().isEmpty());
    }
}
