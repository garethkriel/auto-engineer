package ae.wasm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

class ValidatorTest {
    /** A one-function module with signature {@code sig}, locals {@code locals}, one memory and one i32 mutable + one i64 immutable global. */
    private static byte[] module(String sig, String locals, Bin.C body) {
        Bin.M m = new Bin.M();
        int t = m.type(sig);
        m.type("i:i"); // type 1 used by call tests
        m.mem(1, -1);
        m.global('i', true, 0); // global 0: mutable i32
        m.global('l', false, 0); // global 1: immutable i64
        m.func(t, locals, body);
        return m.build();
    }

    private static void ok(String sig, String locals, Bin.C body) {
        ModuleParser.parse(module(sig, locals, body));
    }

    private static void bad(String sig, String locals, Bin.C body, String message) {
        ModuleException e = assertThrows(ModuleException.class,
                () -> ModuleParser.parse(module(sig, locals, body)), message);
        assertEquals(ModuleException.Kind.INVALID, e.kind(), e.getMessage());
        assertTrue(e.getMessage().contains(message), "expected '" + message + "' in: " + e.getMessage());
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
            // [sig, locals, body, expected message]
            Arguments.of(":i", "", Bin.c(), "stack underflow"),                             // result missing
            Arguments.of(":i", "", Bin.c().i64(1), "type mismatch"),                        // wrong result type
            Arguments.of(":", "", Bin.c().i32(1), "stack height mismatch"),                 // leftover value
            Arguments.of(":i", "", Bin.c().i32(1).i32(2), "stack height mismatch"),         // extra value
            Arguments.of(":i", "", Bin.c().i32(1).b(0x6A), "stack underflow"),              // i32.add with 1 operand
            Arguments.of(":", "", Bin.c().b(0x6A), "stack underflow"),
            Arguments.of(":i", "", Bin.c().i32(1).i64(2).b(0x6A), "type mismatch"),         // i32.add (i32, i64)
            Arguments.of(":i", "", Bin.c().i64(1).i32(2).b(0x6A), "type mismatch"),
            Arguments.of(":i", "", Bin.c().i64(1).i64(2).b(0x6A), "type mismatch"),
            Arguments.of(":l", "", Bin.c().i32(1).i32(2).b(0x7C), "type mismatch"),         // i64.add on i32s
            Arguments.of(":i", "", Bin.c().i64(1).b(0xA7).b(0xA7), "type mismatch"),        // wrap of an i32
            Arguments.of(":l", "", Bin.c().i64(1).b(0xAC), "type mismatch"),                // extend_s of an i64
            Arguments.of(":i", "", Bin.c().get(0), "unknown local"),
            Arguments.of("i:i", "", Bin.c().get(1), "unknown local"),
            Arguments.of("i:", "", Bin.c().i32(1).set(1), "unknown local"),
            Arguments.of("i:", "", Bin.c().i64(1).set(0), "type mismatch"),                 // set i32 local with i64
            Arguments.of("i:i", "", Bin.c().i64(1).tee(0), "type mismatch"),
            Arguments.of(":i", "", Bin.c().gget(9), "unknown global"),
            Arguments.of(":i", "", Bin.c().gget(2), "unknown global"),                       // module() declares globals 0 and 1
            Arguments.of(":", "", Bin.c().call(1), "unknown function"),                      // the module has exactly one function
            Arguments.of(":", "", Bin.c().i32(1).gset(1), "immutable global"),              // global 1 is i64 immutable
            Arguments.of(":", "", Bin.c().i64(1).gset(0), "type mismatch"),                 // global 0 is i32
            Arguments.of(":", "", Bin.c().i32(1).gset(7), "unknown global"),
            Arguments.of(":", "", Bin.c().call(5), "unknown function"),
            Arguments.of(":", "", Bin.c().br(1), "unknown label"),                          // only the function label exists
            Arguments.of(":", "", Bin.c().b(0x02, 0x40).br(2).end(), "unknown label"),
            Arguments.of(":", "", Bin.c().i32(0).brIf(1), "unknown label"),
            Arguments.of(":i", "", Bin.c().b(0x02, 0x7F).end(), "stack underflow"),          // block (result i32) empty
            Arguments.of(":", "", Bin.c().b(0x02, 0x40).i32(1).end(), "stack height mismatch"),
            Arguments.of(":i", "", Bin.c().i32(1).b(0x04, 0x7F).i32(1).end(), "if without else cannot have a result"),
            Arguments.of(":", "", Bin.c().b(0x04, 0x40).end(), "stack underflow"),          // if without condition
            Arguments.of(":", "", Bin.c().i64(1).b(0x04, 0x40).end(), "type mismatch"),     // i64 condition
            Arguments.of(":", "", Bin.c().b(0x05), "else without if"),
            Arguments.of(":", "", Bin.c().b(0x02, 0x40).b(0x05).end(), "else without if"),
            Arguments.of(":", "", Bin.c().i32(1).b(0x04, 0x40).b(0x05).b(0x05).end(), "else without if"),
            Arguments.of(":i", "", Bin.c().i32(1).b(0x04, 0x7F).i32(1).b(0x05).i64(2).end(), "type mismatch"),
            Arguments.of(":", "", Bin.c().b(0x1A), "stack underflow"),                      // drop on empty
            Arguments.of(":i", "", Bin.c().i32(1).i64(2).i32(1).b(0x1B), "type mismatch"),  // select with mixed operand types
            Arguments.of(":i", "", Bin.c().i32(1).i32(2).i64(1).b(0x1B), "type mismatch"),  // select condition must be i32
            Arguments.of(":", "", Bin.c().i32(0).i32(0).b(0x1B), "stack underflow"),
            // memory
            Arguments.of(":i", "", Bin.c().i32(0).mem(0x28, 3, 0), "alignment must not be larger than natural"),
            Arguments.of(":l", "", Bin.c().i32(0).mem(0x29, 4, 0), "alignment must not be larger than natural"),
            Arguments.of(":i", "", Bin.c().i32(0).mem(0x2C, 1, 0), "alignment must not be larger than natural"),
            Arguments.of(":i", "", Bin.c().i32(0).mem(0x2E, 2, 0), "alignment must not be larger than natural"),
            Arguments.of(":", "", Bin.c().i32(0).i32(0).mem(0x3A, 1, 0), "alignment must not be larger than natural"),
            Arguments.of(":", "", Bin.c().i32(0).i32(0).mem(0x3B, 2, 0), "alignment must not be larger than natural"),
            Arguments.of(":", "", Bin.c().i32(0).i64(0).mem(0x36, 2, 0), "type mismatch"),  // i32.store of i64
            Arguments.of(":", "", Bin.c().i64(0).i64(0).mem(0x37, 3, 0), "type mismatch"),  // i64.store with i64 address
            Arguments.of(":i", "", Bin.c().i64(0).mem(0x28, 2, 0), "type mismatch")         // i64 address
        );
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void rejects(String sig, String locals, Bin.C body, String message) {
        bad(sig, locals, body, message);
    }

    @Test
    void callChecksArgumentTypes() {
        // function 0 has type "ii:i"; module() adds only that one function, so build a two-function module
        Bin.M m = new Bin.M();
        int t = m.type("i:i");
        int callee = m.func(t, "", Bin.c().get(0));
        m.func(m.type(":i"), "", Bin.c().i64(1).call(callee));
        ModuleException e = assertThrows(ModuleException.class, () -> ModuleParser.parse(m.build()));
        assertEquals(ModuleException.Kind.INVALID, e.kind());
        assertTrue(e.getMessage().contains("type mismatch"), e.getMessage());
        Bin.M ok = new Bin.M();
        int t2 = ok.type("i:i");
        int c2 = ok.func(t2, "", Bin.c().get(0));
        ok.func(ok.type(":i"), "", Bin.c().i32(1).call(c2));
        ModuleParser.parse(ok.build());
        Bin.M under = new Bin.M();
        int t3 = under.type("i:i");
        int c3 = under.func(t3, "", Bin.c().get(0));
        under.func(under.type(":i"), "", Bin.c().call(c3));
        assertTrue(assertThrows(ModuleException.class, () -> ModuleParser.parse(under.build()))
                .getMessage().contains("stack underflow"));
    }

    @Test
    void returnChecksItsOperand() {
        bad(":i", "", Bin.c().ret(), "stack underflow");                  // return with nothing for the i32 result
        bad(":i", "", Bin.c().i64(1).ret(), "type mismatch");            // wrong result type
        ok(":i", "", Bin.c().i32(1).ret());
        ok(":", "", Bin.c().ret());
    }

    @Test
    void elseArmStartsWithAFreshReachableStack() {
        // then-arm ends in unreachable code; the else arm must not inherit the polymorphic state
        bad(":i", "", Bin.c().i32(1).b(0x04, 0x7F).b(0x00).b(0x05).end(), "stack underflow");
        bad(":i", "", Bin.c().i32(1).b(0x04, 0x7F).i32(1).b(0x05).b(0x6A).end(), "stack underflow");
        ok(":i", "", Bin.c().i32(1).b(0x04, 0x7F).b(0x00).b(0x05).i32(2).end());
    }

    @Test
    void unknownTypeIndexInFunctionSection() {
        Bin.M m = new Bin.M();
        m.type(":");
        m.funcTypes.add(3);
        m.codes.add(Bin.bytes(0, 0x0B));
        ModuleException e = assertThrows(ModuleException.class, () -> ModuleParser.parse(m.build()));
        assertEquals(ModuleException.Kind.INVALID, e.kind());
        assertTrue(e.getMessage().contains("unknown type"), e.getMessage());
        // one past the last type (there is exactly one type, index 0)
        Bin.M edge = new Bin.M();
        edge.type(":");
        edge.funcTypes.add(1);
        edge.codes.add(Bin.bytes(0, 0x0B));
        ModuleException e2 = assertThrows(ModuleException.class, () -> ModuleParser.parse(edge.build()));
        assertEquals(ModuleException.Kind.INVALID, e2.kind());
        assertTrue(e2.getMessage().contains("unknown type 1"), e2.getMessage());
    }

    @Test
    void multiValueResultsAreRejected() {
        Bin.M m = new Bin.M();
        int t = m.type(":ii");
        m.func(t, "", Bin.c().i32(1).i32(2));
        ModuleException e = assertThrows(ModuleException.class, () -> ModuleParser.parse(m.build()));
        assertEquals(ModuleException.Kind.INVALID, e.kind());
        assertTrue(e.getMessage().contains("multiple results"), e.getMessage());
    }

    @Test
    void memoryInstructionsNeedAMemory() {
        for (Bin.C c : new Bin.C[] {Bin.c().i32(0).mem(0x28, 2, 0), Bin.c().b(0x3F, 0), Bin.c().i32(1).b(0x40, 0)}) {
            Bin.M m = new Bin.M();
            int t = m.type(":i");
            m.func(t, "", c);
            ModuleException e = assertThrows(ModuleException.class, () -> ModuleParser.parse(m.build()));
            assertEquals(ModuleException.Kind.INVALID, e.kind());
            assertTrue(e.getMessage().contains("unknown memory"), e.getMessage());
        }
    }

    @Test
    void memoryOpsReservedByteMustBeZero() {
        Bin.M m = new Bin.M();
        int t = m.type(":i");
        m.mem(1, -1);
        m.func(t, "", Bin.c().b(0x3F, 1));
        ModuleException e = assertThrows(ModuleException.class, () -> ModuleParser.parse(m.build()));
        assertEquals(ModuleException.Kind.MALFORMED, e.kind());
        assertTrue(e.getMessage().contains("zero byte expected"), e.getMessage());
    }

    @Test
    void unsupportedBlockTypeIsMalformed() {
        Bin.M m = new Bin.M();
        int t = m.type(":");
        m.func(t, "", Bin.c().b(0x02, 0x00).end()); // block with type index 0
        ModuleException e = assertThrows(ModuleException.class, () -> ModuleParser.parse(m.build()));
        assertEquals(ModuleException.Kind.MALFORMED, e.kind());
        assertTrue(e.getMessage().contains("unsupported block type"), e.getMessage());
    }

    // ---- valid programs relying on the stack-polymorphic rule ----

    @Test
    void transferDiscardsOperandsBelowTheTargetResult() {
        ok(":", "", Bin.c().i32(1).ret());               // return in a void function with a stray operand
        ok(":", "", Bin.c().i32(1).i64(2).br(0));        // br to the function label with stray operands
        ok(":", "", Bin.c().b(0x02, 0x40).i32(1).br(0).end());
        ok(":", "", Bin.c().i32(1).i32(0).brTable(0, 0));
    }

    @Test
    void polymorphicStackAfterUnconditionalTransfer() {
        ok(":i", "", Bin.c().b(0x00).b(0x6A));                       // unreachable; i32.add  -> [i32]
        ok(":i", "", Bin.c().b(0x00));                               // unreachable alone satisfies a result
        ok(":i", "", Bin.c().i32(1).ret().b(0x6A));                  // return; i32.add
        ok(":i", "", Bin.c().i32(1).br(0).b(0x6A, 0x6A));            // br 0; add; add
        ok(":l", "", Bin.c().b(0x00).b(0x7C));                       // i64.add under unreachable
        ok(":", "", Bin.c().b(0x00).b(0x1A, 0x1A));                  // drop; drop under unreachable
        ok(":i", "", Bin.c().b(0x00).b(0x1B));                       // select under unreachable
        ok(":i", "", Bin.c().b(0x02, 0x7F).b(0x00).end());           // block (result i32) unreachable end
        ok(":i", "", Bin.c().i32(1).b(0x04, 0x7F).b(0x00).b(0x05).i32(2).end()); // if/else with unreachable arm
        ok(":i", "", Bin.c().b(0x02, 0x7F).i32(5).br(0).end());      // br with value
        ok(":i", "", Bin.c().b(0x02, 0x7F).i32(5).i32(6).br(0).end()); // br discards extra operand below
        ok(":i", "", Bin.c().i32(1).i32(0).brTable(0, 0, 0).b(0x6A));    // br_table to function label with polymorphic tail
    }

    @Test
    void unreachableDoesNotHideTypeErrorsOfConcreteOperands() {
        // after unreachable the known i64 operand must still mismatch an i32 op
        bad(":i", "", Bin.c().b(0x00).i64(1).i32(1).b(0x6A), "type mismatch");
        // a polymorphic pop does not excuse a leftover concrete value at end
        bad(":", "", Bin.c().b(0x00).i32(1), "stack height mismatch");
        // after br, a concrete value of the wrong type for the block result still fails
        bad(":i", "", Bin.c().b(0x02, 0x7F).b(0x00).i64(1).end(), "type mismatch");
    }

    @Test
    void brTableArityMustAgree() {
        // label 0 = inner block (empty), default = function label (result i32): arity differs
        bad(":i", "", Bin.c().b(0x02, 0x40).i32(1).i32(0).brTable(1, 0).end().i32(1), "type mismatch");
        // matching arities pass
        ok(":i", "", Bin.c().b(0x02, 0x7F).i32(1).i32(0).brTable(0, 0, 0).end());
        // br_table index must be i32
        bad(":", "", Bin.c().b(0x02, 0x40).i64(0).brTable(0, 0).end(), "type mismatch");
    }

    @Test
    void localsOfBothTypesAndParamsAreVisible() {
        ok("il:l", "il", Bin.c().get(1).get(3).b(0x7C).set(3).get(2).b(0xAC).get(3).b(0x7C));
    }

    @Test
    void brIfKeepsLabelValueOnTheStack() {
        ok(":i", "", Bin.c().b(0x02, 0x7F).i32(7).i32(1).brIf(0).end());
        bad(":i", "", Bin.c().b(0x02, 0x7F).i32(1).brIf(0).end(), "stack underflow"); // the only i32 is consumed as the condition
    }
}
