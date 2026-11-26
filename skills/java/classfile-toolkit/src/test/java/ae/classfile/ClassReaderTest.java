package ae.classfile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClassReaderTest {
    private static void rejects(byte[] image, String fragment) {
        ClassFormatException e = assertThrows(ClassFormatException.class, () -> ClassReader.parse(image));
        assertTrue(e.getMessage().contains(fragment), "message '" + e.getMessage() + "' should contain '" + fragment + "'");
    }

    private static void rejects(Consumer<Img> defect, String fragment) {
        Img i = Img.minimal();
        defect.accept(i);
        rejects(i.bytes(), fragment);
    }

    @Test
    void minimalImageParses() {
        ClassFile cf = ClassReader.parse(Img.minimal().bytes());
        assertEquals("T", cf.name());
        assertEquals("java/lang/Object", cf.superName());
        assertEquals(1, cf.methods().size());
        assertEquals("m", cf.methods().get(0).name());
        assertEquals("()V", cf.methods().get(0).descriptor());
        CodeAttr code = cf.code(cf.methods().get(0)).orElseThrow();
        assertArrayEquals(new byte[] {(byte) 0xb1}, code.code());
        assertEquals(0, code.maxStack());
        assertTrue(cf.fields().isEmpty());
        assertTrue(cf.interfaceNames().isEmpty());
        assertArrayEquals(Img.minimal().bytes(), ClassWriter.toBytes(cf));
    }

    @ParameterizedTest
    @ValueSource(ints = {45, 52, 65})
    void supportedVersionsParse(int major) {
        Img i = Img.minimal();
        i.major = major;
        assertEquals(major, ClassReader.parse(i.bytes()).major());
    }

    @Test
    void previewMinorVersionParses() {
        Img i = Img.minimal();
        i.minor = 65535;
        assertEquals(65535, ClassReader.parse(i.bytes()).minor());
    }

    @Test
    void headerDefects() {
        rejects(i -> i.magic = 0xCAFEBABF, "bad magic");
        rejects(i -> i.major = 44, "unsupported version");
        rejects(i -> i.major = 66, "unsupported version");
        rejects(i -> i.trailing = new byte[] {0}, "trailing bytes");
        rejects(new byte[0], "truncated");
        rejects(new byte[] {(byte) 0xCA, (byte) 0xFE}, "truncated");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, 13, 14, 21, 255})
    void unknownConstantTags(int tag) {
        rejects(i -> i.raw(1, (byte) tag, 0, 0, 0, 0), "unknown constant tag");
    }

    @Test
    void constantTypeAndIndexChecks() {
        rejects(i -> i.thisIdx = i.integer(5), "wrong constant type");
        rejects(i -> i.superIdx = i.utf8("x"), "wrong constant type");
        rejects(i -> i.interfaces = new int[] {i.utf8("x")}, "wrong constant type");
        rejects(i -> i.thisIdx = 0, "constant pool index");
        rejects(i -> i.thisIdx = 9999, "constant pool index");
        // second slot of a long is not addressable
        rejects(i -> i.thisIdx = i.longC(1) + 1, "constant pool index");
        // Class whose name is not Utf8
        rejects(i -> {
            int n = i.integer(3);
            i.thisIdx = i.raw(1, (byte) 7, (byte) (n >> 8), (byte) n);
        }, "wrong constant type");
        // Methodref whose owner is a Utf8
        rejects(i -> {
            int u = i.utf8("owner");
            int nt = i.nat("f", "()V");
            i.raw(1, (byte) 10, (byte) (u >> 8), (byte) u, (byte) (nt >> 8), (byte) nt);
        }, "wrong constant type");
        // control: the same Methodref with a Class owner is fine
        Img ok = Img.minimal();
        ok.methodRef("owner", "f", "()V");
        ClassReader.parse(ok.bytes());
    }

    @Test
    void methodHandleKinds() {
        rejects(i -> i.methodHandle(0, i.methodRef("a", "b", "()V")), "bad method handle kind");
        rejects(i -> i.methodHandle(10, i.methodRef("a", "b", "()V")), "bad method handle kind");
        rejects(i -> i.methodHandle(1, i.methodRef("a", "b", "()V")), "wrong constant type");
        rejects(i -> i.methodHandle(6, i.fieldRef("a", "b", "I")), "wrong constant type");
        rejects(i -> i.methodHandle(9, i.methodRef("a", "b", "()V")), "wrong constant type");
        rejects(i -> i.methodHandle(4, i.interfaceMethodRef("a", "b", "()V")), "wrong constant type");
        Img ok = Img.minimal();
        ok.methodHandle(6, ok.methodRef("a", "b", "()V"));
        ok.methodHandle(1, ok.fieldRef("a", "b", "I"));
        ok.methodHandle(9, ok.interfaceMethodRef("a", "b", "()V"));
        ClassReader.parse(ok.bytes());
    }

    @Test
    void superclassRules() {
        rejects(i -> i.superIdx = 0, "missing superclass");
        Img object = new Img();
        object.thisIdx = object.cls("java/lang/Object");
        object.superIdx = 0;
        assertNull(ClassReader.parse(object.bytes()).superName());
        Img module = new Img();
        module.access = Img.MODULE;
        module.thisIdx = module.cls("module-info");
        module.superIdx = 0;
        assertNull(ClassReader.parse(module.bytes()).superName());
    }

    @Test
    void duplicateMembers() {
        rejects(i -> i.field(1, "f", "I").field(1, "f", "I"), "duplicate member");
        rejects(i -> i.method(Img.STATIC, "m", "()V", 0, 0, new byte[] {(byte) 0xb1}), "duplicate member");
        Img ok = Img.minimal();
        ok.field(1, "f", "I").field(1, "f", "J").field(1, "m", "I");
        ok.method(Img.STATIC, "m", "()I", 1, 0, new byte[] {3, (byte) 0xac});
        assertEquals(2, ClassReader.parse(ok.bytes()).methods().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"a.b", "a;b", "a[b", "a/b", "", "<foo>", "<init>x", "a<b", "b>"})
    void badMethodNames(String name) {
        rejects(i -> i.method(Img.STATIC, name, "()V", 0, 0, new byte[] {(byte) 0xb1}), "bad name");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a.b", "a;b", "a[b", "a/b", ""})
    void badFieldNames(String name) {
        rejects(i -> i.field(1, name, "I"), "bad name");
    }

    @Test
    void legalSpecialNames() {
        Img i = Img.minimal();
        i.method(0, "<init>", "()V", 1, 1, new byte[] {(byte) 0xb1});
        i.method(Img.STATIC, "<clinit>", "()V", 0, 0, new byte[] {(byte) 0xb1});
        i.field(1, "<x>", "I");
        i.field(1, "a$b-c d", "I");
        assertEquals(3, ClassReader.parse(i.bytes()).methods().size());
    }

    @Test
    void badDescriptors() {
        rejects(i -> i.method(Img.STATIC, "n", "(V)V", 0, 0, new byte[] {(byte) 0xb1}), "bad descriptor");
        rejects(i -> i.method(Img.STATIC, "n", "I", 0, 0, new byte[] {(byte) 0xb1}), "bad descriptor");
        rejects(i -> i.field(1, "f", "V"), "bad descriptor");
        rejects(i -> i.field(1, "f", "()V"), "bad descriptor");
    }

    @Test
    void codeAttributePresenceRules() {
        rejects(i -> i.methodWith(Img.ABSTRACT, i.utf8("n"), i.utf8("()V"), i.codeAttr(0, 0, new byte[] {(byte) 0xb1})),
            "must not have Code");
        rejects(i -> i.methodWith(Img.NATIVE, i.utf8("n"), i.utf8("()V"), i.codeAttr(0, 0, new byte[] {(byte) 0xb1})),
            "must not have Code");
        rejects(i -> i.methodWith(Img.STATIC, i.utf8("n"), i.utf8("()V")), "missing Code");
        rejects(i -> i.methodWith(Img.STATIC, i.utf8("n"), i.utf8("()V"),
            i.codeAttr(0, 0, new byte[] {(byte) 0xb1}), i.codeAttr(0, 0, new byte[] {(byte) 0xb1})), "multiple Code");
        Img ok = Img.minimal();
        ok.methodWith(Img.ABSTRACT, ok.utf8("a"), ok.utf8("()V"));
        ok.methodWith(Img.NATIVE, ok.utf8("b"), ok.utf8("()V"));
        ClassFile cf = ClassReader.parse(ok.bytes());
        assertTrue(cf.code(cf.methods().get(1)).isEmpty());
        assertTrue(cf.code(cf.methods().get(2)).isEmpty());
    }

    @Test
    void codeBodyDefects() {
        byte[] ret = {(byte) 0xb1};
        rejects(i -> i.method(Img.STATIC, "n", "()V", 0, 0, new byte[0]), "bad code length");
        rejects(i -> i.method(Img.STATIC, "n", "()V", 0, 0, ret, new int[] {0, 0, 0, 0}), "bad exception table");
        rejects(i -> i.method(Img.STATIC, "n", "()V", 0, 0, ret, new int[] {1, 0, 0, 0}), "bad exception table");
        rejects(i -> i.method(Img.STATIC, "n", "()V", 0, 0, ret, new int[] {0, 2, 0, 0}), "bad exception table");
        rejects(i -> i.method(Img.STATIC, "n", "()V", 0, 0, ret, new int[] {0, 1, 1, 0}), "bad exception table");
        rejects(i -> i.method(Img.STATIC, "n", "()V", 0, 0, ret, new int[] {0, 1, 0, i.integer(3)}), "wrong constant type");
        Img ok = Img.minimal();
        ok.method(Img.STATIC, "n", "()V", 0, 0, new byte[] {0, (byte) 0xb1}, new int[] {0, 1, 1, ok.cls("java/lang/Exception")},
            new int[] {0, 2, 1, 0});
        ClassFile cf = ClassReader.parse(ok.bytes());
        assertEquals(2, cf.code(cf.methods().get(1)).orElseThrow().handlers().size());
    }

    @Test
    void codeAttributeLengthMustBeExact() {
        Img base = Img.minimal();
        byte[] attr = base.codeAttr(0, 0, new byte[] {(byte) 0xb1});
        // grow the declared attribute length by one and append a byte: content no longer matches the Code layout
        byte[] longer = new byte[attr.length + 1];
        System.arraycopy(attr, 0, longer, 0, attr.length);
        longer[5] += 1; // low byte of the u4 length (offset 2..5)
        Img i = new Img();
        i.methodWith(Img.STATIC, i.utf8("n"), i.utf8("()V"), patchName(i, longer));
        rejects(i.bytes(), "Code attribute length");
        // control: the unpatched attribute is fine
        Img ok = new Img();
        ok.methodWith(Img.STATIC, ok.utf8("n"), ok.utf8("()V"), ok.codeAttr(0, 0, new byte[] {(byte) 0xb1}));
        ClassReader.parse(ok.bytes());
    }

    /** The patched attribute was built against another image's pool; point its name at this image's "Code". */
    private static byte[] patchName(Img target, byte[] attr) {
        int idx = target.utf8("Code");
        byte[] r = attr.clone();
        r[0] = (byte) (idx >> 8);
        r[1] = (byte) idx;
        return r;
    }

    @Test
    void attributeBoundsAndNames() {
        Img i = Img.minimal();
        i.classAttr("SourceFile", new byte[] {0, 1});
        assertEquals(1, ClassReader.parse(i.bytes()).attributes().size());
        assertEquals("SourceFile", ClassReader.parse(i.bytes()).attributes().get(0).name());
        rejects(j -> j.methodWith(Img.ABSTRACT, j.utf8("a"), j.utf8("()V"),
            new byte[] {0, (byte) j.utf8("X"), 0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff}), "truncated");
        rejects(j -> j.methodWith(Img.ABSTRACT, j.utf8("a"), j.utf8("()V"), Img.attr(j.integer(1), new byte[0])),
            "wrong constant type");
    }

    @Test
    void constantValuesAreKeptBitExact() {
        Img i = Img.minimal();
        int f = i.floatC(0x7fc00001);
        int d = i.doubleC(0x7ff8000000000123L);
        int l = i.longC(Long.MIN_VALUE);
        int n = i.integer(Integer.MIN_VALUE);
        int s = i.string("kéy\0");
        int u = i.utf8("😀");
        ClassFile cf = ClassReader.parse(i.bytes());
        assertEquals(0x7fc00001, ((Constant.FloatC) cf.pool().get(f)).bits());
        assertEquals(0x7ff8000000000123L, ((Constant.DoubleC) cf.pool().get(d)).bits());
        assertEquals(Long.MIN_VALUE, ((Constant.LongC) cf.pool().get(l)).value());
        assertEquals(Integer.MIN_VALUE, ((Constant.IntegerC) cf.pool().get(n)).value());
        assertEquals("kéy\0", cf.pool().utf8(((Constant.StringRef) cf.pool().get(s)).utf8Index()));
        assertEquals("😀", cf.pool().utf8(u));
        assertEquals(d + 2, l);
        assertArrayEquals(i.bytes(), ClassWriter.toBytes(cf));
    }

    @Test
    void memberRefsResolve() {
        Img i = Img.minimal();
        int m = i.methodRef("a/B", "run", "(I)V");
        int y = i.indy(3, "call", "()I");
        ClassFile cf = ClassReader.parse(i.bytes());
        assertEquals(new MemberRef("a/B", "run", "(I)V"), cf.pool().memberRef(m));
        assertEquals(new MemberRef(null, "call", "()I"), cf.pool().memberRef(y));
        assertThrows(ClassFormatException.class, () -> cf.pool().memberRef(1));
    }

    @Test
    void everyTruncationIsRejected() {
        byte[] image = richImage();
        for (int len = 0; len < image.length; len++) {
            byte[] cut = java.util.Arrays.copyOf(image, len);
            assertThrows(ClassFormatException.class, () -> ClassReader.parse(cut), "prefix length " + len);
        }
    }

    @Test
    void flippingAnyByteEitherFailsCleanlyOrRoundTrips() {
        byte[] image = richImage();
        int accepted = 0;
        int rejected = 0;
        for (int pos = 0; pos < image.length; pos++) {
            for (int mask : new int[] {0xFF, 0x01, 0x80}) {
                byte[] bad = image.clone();
                bad[pos] ^= (byte) mask;
                try {
                    ClassFile cf = ClassReader.parse(bad);
                    assertArrayEquals(bad, ClassWriter.toBytes(cf), "accepted image must re-serialise identically, pos " + pos);
                    accepted++;
                } catch (ClassFormatException e) {
                    rejected++;
                }
            }
        }
        assertTrue(accepted > 20, "accepted mix " + accepted);
        assertTrue(rejected > 200, "rejected mix " + rejected);
    }

    private static byte[] richImage() {
        Img i = new Img();
        i.interfaces = new int[] {i.cls("java/lang/Runnable")};
        i.field(Img.PUBLIC, "count", "J");
        i.field(Img.PUBLIC, "name", "Ljava/lang/String;");
        int ex = i.cls("java/lang/Exception");
        int ref = i.methodRef("java/lang/Object", "hashCode", "()I");
        i.floatC(0x3f800000);
        i.doubleC(Double.doubleToRawLongBits(2.5));
        i.methodHandle(6, ref);
        i.methodType("()V");
        i.indy(0, "run", "()Ljava/lang/Runnable;");
        i.method(Img.PUBLIC, "run", "()V", 1, 1, new byte[] {0, 0, 0x4b, (byte) 0xb1}, new int[] {0, 2, 2, ex});
        i.methodWith(Img.ABSTRACT | Img.PUBLIC, i.utf8("abs"), i.utf8("(IJ)D"));
        i.classAttr("SourceFile", new byte[] {0, 1});
        return i.bytes();
    }

    @Test
    void richImageRoundTrips() {
        byte[] image = richImage();
        assertArrayEquals(image, ClassWriter.toBytes(ClassReader.parse(image)));
    }
}
