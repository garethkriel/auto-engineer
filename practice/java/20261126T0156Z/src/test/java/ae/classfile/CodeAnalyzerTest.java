package ae.classfile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CodeAnalyzerTest {
    private static byte[] hex(String s) {
        return java.util.HexFormat.of().parseHex(s.replace(" ", ""));
    }

    private static Analysis run(Img img) {
        ClassFile cf = ClassReader.parse(img.bytes());
        return CodeAnalyzer.analyze(cf, cf.methods().get(0));
    }

    private static Analysis st(String desc, String code, int[]... table) {
        return run(new Img().method(Img.STATIC, "m", desc, 50, 50, hex(code), table));
    }

    private static void rejects(String desc, String code, String fragment) {
        ClassFormatException e = assertThrows(ClassFormatException.class, () -> st(desc, code), code);
        assertTrue(e.getMessage().contains(fragment), "message '" + e.getMessage() + "' should contain '" + fragment + "'");
    }

    @Test
    void intArithmetic() {
        Analysis a = st("()I", "04 05 60 ac");
        assertEquals(2, a.maxStack());
        assertEquals(0, a.localsRequired());
        assertEquals(4, a.reachable());
    }

    @Test
    void longsTakeTwoSlots() {
        assertEquals(4, st("()J", "0a 0a 61 ad").maxStack());
        // lconst_0 lconst_1 dup2_x2 pop2 pop2 pop2 return: 2,4,6,4,2,0
        assertEquals(6, st("()V", "09 0a 5e 58 58 58 b1").maxStack());
    }

    @Test
    void staticInvokeUsesDescriptor() {
        Img i = new Img();
        int f = i.methodRef("ae/T", "f", "(IJ)D");
        // iconst_0 lconst_0 invokestatic f dreturn: 1, 3, then pops 3 pushes 2
        Analysis a = run(i.method(Img.STATIC, "m", "()D", 9, 9, hex(String.format("03 09 b8 %04x af", f))));
        assertEquals(3, a.maxStack());
    }

    @Test
    void virtualInvokePopsReceiver() {
        Img i = new Img();
        int f = i.methodRef("ae/T", "f", "(IJ)D");
        // aload_0 iconst_0 lconst_0 invokevirtual f dreturn: depth 1,2,4 then 2
        Analysis a = run(i.method(0, "m", "()D", 9, 9, hex(String.format("2a 03 09 b6 %04x af", f))));
        assertEquals(4, a.maxStack());
        assertEquals(1, a.localsRequired());
    }

    @Test
    void invokeInterfaceCountMustMatchDescriptor() {
        Img i = new Img();
        int f = i.interfaceMethodRef("ae/I", "f", "(IJ)D");
        String ok = String.format("2a 03 09 b9 %04x 04 00 af", f);
        assertEquals(4, run(i.method(0, "m", "()D", 9, 9, hex(ok))).maxStack());
        for (String count : new String[] {"03", "05", "00"}) {
            Img j = new Img();
            int g = j.interfaceMethodRef("ae/I", "f", "(IJ)D");
            Img img = j.method(0, "m", "()D", 9, 9, hex(String.format("2a 03 09 b9 %04x %s 00 af", g, count)));
            ClassFormatException e = assertThrows(ClassFormatException.class, () -> run(img), count);
            assertTrue(e.getMessage().contains("invokeinterface count"), e.getMessage());
        }
    }

    @Test
    void fieldInstructionsUseFieldSize() {
        Img i = new Img();
        int j = i.fieldRef("ae/T", "j", "J");
        int d = i.fieldRef("ae/T", "d", "D");
        int z = i.fieldRef("ae/T", "z", "Z");
        assertEquals(2, run(i.method(Img.STATIC, "a", "()J", 9, 9, hex(String.format("b2 %04x ad", j)))).maxStack());
        Img k = new Img();
        int kj = k.fieldRef("ae/T", "j", "J");
        // lconst_0 putstatic J: depth 2 then 0
        assertEquals(2, run(k.method(Img.STATIC, "a", "()V", 9, 9, hex(String.format("09 b3 %04x b1", kj)))).maxStack());
        Img g = new Img();
        int gd = g.fieldRef("ae/T", "d", "D");
        assertEquals(2, run(g.method(0, "a", "()D", 9, 9, hex(String.format("2a b4 %04x af", gd)))).maxStack());
        Img p = new Img();
        int pj = p.fieldRef("ae/T", "j", "J");
        // aload_0 lconst_0 putfield J: 1, 3, then 0
        assertEquals(3, run(p.method(0, "a", "()V", 9, 9, hex(String.format("2a 09 b5 %04x b1", pj)))).maxStack());
        // a boolean field is one slot
        Img q = new Img();
        int qz = q.fieldRef("ae/T", "z", "Z");
        assertEquals(1, run(q.method(Img.STATIC, "a", "()I", 9, 9, hex(String.format("b2 %04x ac", qz)))).maxStack());
        assertTrue(i.bytes().length > 0 && d > 0 && z > 0);
    }

    @Test
    void ldcFamilySizes() {
        Img i = new Img();
        int integer = i.integer(5);
        assertEquals(1, run(i.method(Img.STATIC, "a", "()I", 9, 9, hex(String.format("12 %02x ac", integer)))).maxStack());
        Img l = new Img();
        int lng = l.longC(7);
        assertEquals(2, run(l.method(Img.STATIC, "b", "()J", 9, 9, hex(String.format("14 %04x ad", lng)))).maxStack());
        Img c = new Img();
        int cj = c.dynamic(0, "x", "J");
        assertEquals(2, run(c.method(Img.STATIC, "c", "()J", 9, 9, hex(String.format("13 %04x ad", cj)))).maxStack());
        Img d = new Img();
        int di = d.dynamic(0, "y", "I");
        assertEquals(1, run(d.method(Img.STATIC, "c", "()I", 9, 9, hex(String.format("13 %04x ac", di)))).maxStack());
    }

    @Test
    void ldcWithWrongConstantKindIsRejected() {
        Img a = new Img();
        int lng = a.longC(7);
        // 1-byte ldc of a long is illegal
        a.method(Img.STATIC, "m", "()J", 9, 9, hex(String.format("12 %02x ad", lng)));
        ClassFormatException e = assertThrows(ClassFormatException.class, () -> run(a));
        assertTrue(e.getMessage().contains("ldc"), e.getMessage());
        Img b = new Img();
        int integer = b.integer(1);
        b.method(Img.STATIC, "m", "()J", 9, 9, hex(String.format("14 %04x ad", integer)));
        ClassFormatException e2 = assertThrows(ClassFormatException.class, () -> run(b));
        assertTrue(e2.getMessage().contains("ldc2_w"), e2.getMessage());
        Img c = new Img();
        int u = c.utf8("x");
        c.method(Img.STATIC, "m", "()I", 9, 9, hex(String.format("13 %04x ac", u)));
        assertThrows(ClassFormatException.class, () -> run(c));
    }

    @Test
    void indyAndMultianewarray() {
        Img i = new Img();
        int y = i.indy(0, "f", "(J)I");
        // lconst_0 invokedynamic ireturn: 2, then 1
        assertEquals(2, run(i.method(Img.STATIC, "m", "()I", 9, 9, hex(String.format("09 ba %04x 00 00 ac", y)))).maxStack());
        Img j = new Img();
        int c = j.cls("[[I");
        // iconst_0 iconst_0 multianewarray dims 2 areturn: 1, 2, then 1
        assertEquals(2, run(j.method(Img.STATIC, "m", "()Ljava/lang/Object;", 9, 9,
            hex(String.format("03 03 c5 %04x 02 b0", c)))).maxStack());
        Img k = new Img();
        int c3 = k.cls("[[[I");
        // dims larger than the array type's rank is invalid
        Img bad = k.method(Img.STATIC, "m", "()Ljava/lang/Object;", 9, 9, hex(String.format("03 03 03 03 c5 %04x 04 b0", c3)));
        assertThrows(ClassFormatException.class, () -> run(bad));
    }

    @Test
    void branchesMergeWithEqualDepth() {
        // 0 iload_0; 1 ifeq ->8; 4 iconst_1; 5 goto ->9; 8 iconst_2; 9 ireturn
        Analysis a = st("(I)I", "1a 99 00 07 04 a7 00 04 05 ac");
        assertEquals(1, a.maxStack());
        assertEquals(1, a.localsRequired());
        assertEquals(6, a.reachable());
    }

    @Test
    void mergeWithDifferentDepthIsRejected() {
        // else branch pushes two values before the join at 10
        rejects("(I)I", "1a 99 00 07 04 a7 00 05 05 05 ac", "inconsistent stack depth");
    }

    @Test
    void loopWithBalancedBodyIsAccepted() {
        // 0 iconst_0; 1 istore_0; 2 iinc 0 1; 5 iload_0; 6 bipush 10; 8 if_icmplt ->2; 11 return
        Analysis a = st("()V", "03 3b 84 00 01 1a 10 0a a1 ff fa b1");
        assertEquals(2, a.maxStack());
        assertEquals(1, a.localsRequired());
        assertEquals(7, a.reachable());
    }

    @Test
    void loopThatGrowsTheStackIsRejected() {
        rejects("()V", "03 a7 ff ff", "inconsistent stack depth");
    }

    @Test
    void underflowFallOffAndLeftovers() {
        rejects("()V", "57 b1", "underflow");
        rejects("()I", "ac", "underflow");
        rejects("()V", "03 03 5b b1", "underflow");
        rejects("()V", "03 3f b1", "underflow");
        rejects("()V", "03 57", "falls off");
        assertEquals(1, st("()V", "03 b1").maxStack());
    }

    @Test
    void handlersStartWithOneSlotAndOnlyWhenCoveredCodeIsReachable() {
        // 0 nop; 1 return; 2 astore_0 (handler); 3 return
        Analysis a = st("()V", "00 b1 4b b1", new int[] {0, 1, 2, 0});
        assertEquals(1, a.maxStack());
        assertEquals(1, a.localsRequired());
        assertEquals(4, a.reachable());
        Analysis none = st("()V", "00 b1 4b b1");
        assertEquals(0, none.maxStack());
        assertEquals(2, none.reachable());
        // handler guarding only unreachable code (1..2) is ignored
        Analysis dead = st("()V", "b1 00 4b b1", new int[] {1, 2, 2, 0});
        assertEquals(1, dead.reachable());
        assertEquals(0, dead.maxStack());
    }

    @Test
    void handlerWithCatchTypeAndEntryDepthConflictIsRejected() {
        Img i = new Img();
        int ex = i.cls("java/lang/Exception");
        // handler at 2 is also entered by fallthrough from 1 with depth 0 -> conflict with depth 1
        Img img = i.method(Img.STATIC, "m", "()V", 9, 9, hex("00 00 4b b1"), new int[] {0, 1, 2, ex});
        assertThrows(ClassFormatException.class, () -> run(img));
    }

    @Test
    void unreachableCodeIsIgnored() {
        Analysis a = st("()V", "b1 03 03 03");
        assertEquals(0, a.maxStack());
        assertEquals(1, a.reachable());
    }

    @Test
    void localsRequiredCoversSizesWideAndArguments() {
        assertEquals(4, st("()V", "2d 57 b1").localsRequired());          // aload_3
        assertEquals(4, st("()V", "20 58 b1").localsRequired());          // lload_2 uses slots 2,3
        assertEquals(7, st("()V", "16 05 58 b1").localsRequired());       // lload 5 uses 5,6
        assertEquals(301, st("()V", "c4 15 01 2c 57 b1").localsRequired());
        assertEquals(302, st("()V", "c4 16 01 2c 58 b1").localsRequired());
        assertEquals(8, st("()V", "84 07 01 b1").localsRequired());
        assertEquals(301, st("()V", "c4 84 01 2c 00 05 b1").localsRequired());
        assertEquals(5, run(new Img().method(0, "m", "(JJ)V", 0, 5, hex("b1"))).localsRequired());
        assertEquals(2, st("(D)V", "b1").localsRequired());
        assertEquals(1, st("(I)V", "b1").localsRequired());
    }

    @Test
    void localsRequiredForEveryWideKindOfLoadAndStore() {
        assertEquals(7, st("()V", "18 05 58 b1").localsRequired());      // dload 5 (slots 5,6)
        assertEquals(7, st("()V", "0e 39 05 b1").localsRequired());      // dconst_0 dstore 5
        assertEquals(6, st("()V", "09 37 04 b1").localsRequired());      // lconst_0 lstore 4
        assertEquals(5, st("()V", "29 58 b1").localsRequired());         // dload_3 (slots 3,4)
        assertEquals(3, st("()V", "09 40 b1").localsRequired());         // lstore_1 (slots 1,2)
        assertEquals(2, st("()V", "0e 47 b1").localsRequired());         // dstore_0 (slots 0,1)
        assertEquals(5, st("()V", "0e 4a b1").localsRequired());         // dstore_3 (slots 3,4)
    }

    @Test
    void ldc2wOfDynamicNeedsTwoSlotDescriptor() {
        Img ok = new Img();
        int j = ok.dynamic(0, "x", "J");
        assertEquals(2, run(ok.method(Img.STATIC, "m", "()J", 9, 9, hex(String.format("14 %04x ad", j)))).maxStack());
        Img bad = new Img();
        int i = bad.dynamic(0, "x", "I");
        bad.method(Img.STATIC, "m", "()I", 9, 9, hex(String.format("14 %04x ac", i)));
        ClassFormatException e = assertThrows(ClassFormatException.class, () -> run(bad));
        assertTrue(e.getMessage().contains("ldc2_w"), e.getMessage());
    }

    @Test
    void jsrAndRetAreUnsupported() {
        rejects("()V", "a8 00 03 b1", "jsr/ret");
        rejects("()V", "a9 00", "jsr/ret");
    }

    @Test
    void athrowAndSwitchEndOrSplitFlow() {
        assertEquals(1, st("()V", "01 bf").maxStack());
        // 0 iconst_0; 1 lookupswitch (pad 2) default ->12 (+11), 0 pairs; 12 return
        Analysis a = st("()V", "03 ab 00 00  00 00 00 0b  00 00 00 00  b1");
        assertEquals(1, a.maxStack());
        assertEquals(3, a.reachable());
    }

    @Test
    void badPoolReferencesAreRejected() {
        Img a = new Img();
        int integer = a.integer(1);
        Img i = a.method(Img.STATIC, "m", "()I", 9, 9, hex(String.format("b2 %04x ac", integer)));
        ClassFormatException e = assertThrows(ClassFormatException.class, () -> run(i));
        assertTrue(e.getMessage().contains("wrong constant type"), e.getMessage());
        Img b = new Img().method(Img.STATIC, "m", "()I", 9, 9, hex("b2 ff ff ac"));
        ClassFormatException e2 = assertThrows(ClassFormatException.class, () -> run(b));
        assertTrue(e2.getMessage().contains("constant pool index"), e2.getMessage());
        Img c = new Img();
        int bad = c.methodRef("ae/T", "f", "(V)V");
        Img img = c.method(Img.STATIC, "m", "()V", 9, 9, hex(String.format("b8 %04x b1", bad)));
        assertThrows(ClassFormatException.class, () -> run(img));
    }

    @Test
    void branchIntoTheMiddleOfAnInstructionIsRejectedByTheAnalyzer() {
        rejects("()V", "03 99 00 05 11 00 07 b1", "branch target");
    }
}
