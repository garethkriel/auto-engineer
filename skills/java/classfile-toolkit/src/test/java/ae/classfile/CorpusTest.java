package ae.classfile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ae.fixture.Samples;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.spi.ToolProvider;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Verifies the toolkit against the running JDK: every java.util / java.lang class, javap and reflection. */
class CorpusTest {
    private static final FileSystem JRT = FileSystems.getFileSystem(URI.create("jrt:/"));

    private static byte[] bytesOf(Class<?> c) throws IOException {
        String res = c.getName().replace('.', '/') + ".class";
        try (InputStream in = ClassLoader.getSystemResourceAsStream(res)) {
            assertTrue(in != null, "resource " + res);
            return in.readAllBytes();
        }
    }

    private static List<Path> jdkClasses() throws IOException {
        List<Path> all = new ArrayList<>();
        for (String root : new String[] {"/modules/java.base/java/util", "/modules/java.base/java/lang"}) {
            try (Stream<Path> s = Files.walk(JRT.getPath(root))) {
                s.filter(p -> p.toString().endsWith(".class")).forEach(all::add);
            }
        }
        all.add(JRT.getPath("/modules/java.base/module-info.class"));
        return all;
    }

    @Test
    void everyJdkClassRoundTripsAndAnalyzes() throws IOException {
        List<Path> classes = jdkClasses();
        assertTrue(classes.size() > 1500, "corpus size " + classes.size());
        int methods = 0;
        int exact = 0;
        int instructions = 0;
        long slack = 0;
        for (Path p : classes) {
            byte[] image = Files.readAllBytes(p);
            ClassFile cf = ClassReader.parse(image);
            assertArrayEquals(image, ClassWriter.toBytes(cf), "round trip " + p);
            for (Member m : cf.methods()) {
                CodeAttr code = cf.code(m).orElse(null);
                if (code == null) {
                    continue;
                }
                String where = p + " " + m.name() + m.descriptor();
                List<Instruction> ins = Disassembler.decode(code.code());
                Disassembler.validateTargets(ins, code.code().length);
                Analysis a = CodeAnalyzer.analyze(cf, m);
                assertTrue(a.maxStack() <= code.maxStack(), "analyzed " + a.maxStack() + " > declared " + code.maxStack() + " in " + where);
                assertTrue(a.localsRequired() <= code.maxLocals(), "locals " + a.localsRequired() + " > " + code.maxLocals() + " in " + where);
                methods++;
                instructions += ins.size();
                if (a.maxStack() == code.maxStack()) {
                    exact++;
                }
                slack += code.maxStack() - a.maxStack();
            }
        }
        System.out.printf("corpus: %d classes, %d methods, %d instructions, exact max_stack %d (%.2f%%), total slack %d%n",
            classes.size(), methods, instructions, exact, 100.0 * exact / methods, slack);
        assertTrue(methods > 20000, "methods " + methods);
        assertTrue(exact >= 0.99 * methods, "exact " + exact + " of " + methods);
    }

    // ---- reflection oracle -------------------------------------------------

    private static final Class<?>[] REFLECTED = {
        String.class, java.util.HashMap.class, java.util.ArrayList.class, java.math.BigInteger.class,
        java.util.Optional.class, java.util.Collections.class, Integer.class, Samples.class,
        ae.fixture.Wide.class, java.util.function.Function.class, Runnable.class,
        java.util.concurrent.TimeUnit.class, java.util.TreeMap.class, StringBuilder.class,
    };

    @Test
    void membersMatchReflection() throws IOException {
        for (Class<?> c : REFLECTED) {
            ClassFile cf = ClassReader.parse(bytesOf(c));
            assertEquals(c.getName().replace('.', '/'), cf.name());
            Class<?> sup = c.getSuperclass();
            String expectedSuper = sup != null ? sup.getName().replace('.', '/') : c.isInterface() ? "java/lang/Object" : null;
            assertEquals(expectedSuper, cf.superName(), c.getName());
            assertEquals(Arrays.stream(c.getInterfaces()).map(i -> i.getName().replace('.', '/')).toList(),
                cf.interfaceNames(), c.getName());
            int mask = Modifier.PUBLIC | Modifier.FINAL | Modifier.INTERFACE | Modifier.ABSTRACT;
            assertEquals(c.getModifiers() & mask, cf.access() & mask, c.getName() + " class flags");

            Set<String> expectedFields = new TreeSet<>();
            for (Field f : c.getDeclaredFields()) {
                expectedFields.add(f.getName() + ":" + f.getType().descriptorString() + ":" + (f.getModifiers() & Modifier.fieldModifiers()));
            }
            Set<String> actualFields = cf.fields().stream()
                .map(f -> f.name() + ":" + f.descriptor() + ":" + (f.access() & Modifier.fieldModifiers()))
                .collect(Collectors.toCollection(TreeSet::new));
            assertEquals(expectedFields, actualFields, c.getName() + " fields");

            Set<String> expectedMethods = new TreeSet<>();
            for (Method m : c.getDeclaredMethods()) {
                expectedMethods.add(m.getName() + MethodType.methodType(m.getReturnType(), m.getParameterTypes()).toMethodDescriptorString()
                    + ":" + (m.getModifiers() & Modifier.methodModifiers()));
            }
            for (Constructor<?> k : c.getDeclaredConstructors()) {
                expectedMethods.add("<init>" + MethodType.methodType(void.class, k.getParameterTypes()).toMethodDescriptorString()
                    + ":" + (k.getModifiers() & Modifier.methodModifiers()));
            }
            Set<String> actualMethods = cf.methods().stream()
                .filter(m -> !m.name().equals("<clinit>"))
                .map(m -> m.name() + m.descriptor() + ":" + (m.access() & Modifier.methodModifiers()))
                .collect(Collectors.toCollection(TreeSet::new));
            assertEquals(expectedMethods, actualMethods, c.getName() + " methods");
        }
    }

    // ---- javap oracle ------------------------------------------------------

    private static final String[] JAVAP_CLASSES = {
        "java.util.HashMap", "java.util.ArrayList", "java.util.TreeMap", "java.lang.Integer", "java.lang.String",
        "java.lang.StringBuilder", "java.util.Collections", "java.util.Arrays", "java.math.BigInteger",
        "java.util.regex.Pattern", "java.util.concurrent.ConcurrentHashMap", "java.util.stream.Collectors",
        "java.util.Optional", "java.lang.Character", "java.util.Formatter", "java.util.Scanner", "java.lang.Math",
        "java.lang.invoke.MethodHandles", "java.util.BitSet", "java.util.concurrent.CompletableFuture",
        "java.lang.StringLatin1", "java.util.zip.ZipFile", "java.lang.Long", "java.util.PriorityQueue",
        "ae.fixture.Samples", "ae.fixture.Samples$Shape", "ae.fixture.Samples$Walker", "ae.fixture.Wide",
    };

    private static final Pattern INSTR = Pattern.compile("^\\s+(\\d+):\\s+(\\S+)\\s*(.*)$", Pattern.DOTALL);
    private static final Pattern ENTRY = Pattern.compile("^\\s*(-?\\d+|default):\\s*(-?\\d+)\\s*$");

    private static List<List<String>> javapCode(String className) throws IOException {
        String cp = Path.of(Samples.class.getProtectionDomain().getCodeSource().getLocation().getPath()).toString();
        return javapOutput(className, "-c", "-p", "-cp", cp, className);
    }

    private static List<List<String>> javapOutput(String className, String... args) {
        ToolProvider javap = ToolProvider.findFirst("javap").orElseThrow(() -> new AssertionError("javap not available"));
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int rc = javap.run(new PrintWriter(out), new PrintWriter(err), args);
        assertEquals(0, rc, "javap " + className + ": " + err);
        List<List<String>> groups = new ArrayList<>();
        List<String> current = null;
        String[] lines = out.toString().split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().equals("Code:")) {
                current = new ArrayList<>();
                groups.add(current);
                continue;
            }
            if (line.isBlank()) {
                current = null;
                continue;
            }
            if (current == null) {
                continue;
            }
            Matcher m = INSTR.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String mnemonic = m.group(2);
            if (mnemonic.equals("tableswitch") || mnemonic.equals("lookupswitch")) {
                List<String> entries = new ArrayList<>();
                String dflt = null;
                for (i++; !lines[i].trim().equals("}"); i++) {
                    Matcher e = ENTRY.matcher(lines[i]);
                    assertTrue(e.matches(), "switch entry '" + lines[i] + "' in " + className);
                    if (e.group(1).equals("default")) {
                        dflt = e.group(2);
                    } else {
                        entries.add(e.group(1) + ": " + e.group(2));
                    }
                }
                entries.add("default: " + dflt);
                current.add(m.group(1) + ": " + mnemonic + " {" + String.join(", ", entries) + "}");
                continue;
            }
            String operands = m.group(3);
            int comment = operands.indexOf("//");
            if (comment >= 0) {
                operands = operands.substring(0, comment);
            }
            operands = operands.trim().replaceAll("\\s+", " ");
            current.add(m.group(1) + ": " + mnemonic + (operands.isEmpty() ? "" : " " + operands));
        }
        return groups;
    }

    @Test
    void instructionStreamsMatchJavap() throws IOException {
        long compared = 0;
        Set<String> seen = new TreeSet<>();
        for (String name : JAVAP_CLASSES) {
            byte[] image = name.startsWith("ae.")
                ? Files.readAllBytes(Path.of(Samples.class.getProtectionDomain().getCodeSource().getLocation().getPath(),
                    name.replace('.', '/') + ".class"))
                : Files.readAllBytes(JRT.getPath("/modules/java.base/" + name.replace('.', '/') + ".class"));
            ClassFile cf = ClassReader.parse(image);
            List<List<String>> expected = javapCode(name);
            List<List<String>> actual = new ArrayList<>();
            for (Member m : cf.methods()) {
                CodeAttr code = cf.code(m).orElse(null);
                if (code != null) {
                    List<String> lines = new ArrayList<>();
                    for (Instruction in : Disassembler.decode(code.code())) {
                        lines.add(in.offset() + ": " + Disassembler.render(in));
                        seen.add(in.mnemonic());
                    }
                    actual.add(lines);
                }
            }
            assertEquals(expected.size(), actual.size(), name + " methods with code");
            for (int i = 0; i < expected.size(); i++) {
                assertEquals(expected.get(i), actual.get(i), name + " method #" + i);
                compared += expected.get(i).size();
            }
        }
        Set<String> unseen = new TreeSet<>();
        for (int op = 0; op < Opcodes.COUNT; op++) {
            if (!seen.contains(Opcodes.name(op)) && !seen.contains(Opcodes.name(op) + "_w")) {
                unseen.add(Opcodes.name(op));
            }
        }
        System.out.println("javap compared " + compared + " instructions, " + seen.size() + " distinct mnemonics; never seen: " + unseen);
        assertTrue(compared > 60_000, "compared " + compared); // measured: 67675 on JDK 21
        for (String must : List.of("tableswitch", "lookupswitch", "iload_w", "istore_w", "iinc_w", "invokedynamic",
            "invokeinterface", "multianewarray", "newarray", "ldc2_w", "ldc_w", "monitorenter", "athrow", "anewarray",
            "dup_x1", "lcmp", "i2l", "instanceof", "checkcast", "if_acmpne", "ifnull", "sipush", "iinc")) {
            assertTrue(seen.contains(must), "corpus never exercised " + must);
        }
    }

    /** Appends one instance of every opcode (plus all wide forms) with plausible operands. */
    @Test
    void everyOpcodeMatchesJavap(@org.junit.jupiter.api.io.TempDir Path tmp) throws IOException {
        Img img = new Img();
        int str = img.string("s");
        int lng = img.longC(5);
        int fld = img.fieldRef("T", "f", "I");
        int mth = img.methodRef("T", "g", "()V");
        int imth = img.interfaceMethodRef("T", "h", "()V");
        int indy = img.indy(0, "x", "()V");
        int cls = img.cls("T");
        java.io.ByteArrayOutputStream code = new java.io.ByteArrayOutputStream();
        for (int op = 0; op < Opcodes.COUNT; op++) {
            code.write(op);
            switch (op) {
                case 0x10 -> code.writeBytes(new byte[] {5});
                case 0x11 -> code.writeBytes(new byte[] {1, 2});
                case 0x12 -> code.writeBytes(new byte[] {(byte) str});
                case 0x13 -> code.writeBytes(new byte[] {0, (byte) str});
                case 0x14 -> code.writeBytes(new byte[] {0, (byte) lng});
                case 0x84 -> code.writeBytes(new byte[] {3, (byte) 0xff});
                case 0xa9, 0xbc -> code.write(op == 0xbc ? 10 : 7);
                case 0xb2, 0xb3, 0xb4, 0xb5 -> code.writeBytes(new byte[] {0, (byte) fld});
                case 0xb6, 0xb7, 0xb8 -> code.writeBytes(new byte[] {0, (byte) mth});
                case 0xb9 -> code.writeBytes(new byte[] {0, (byte) imth, 1, 0});
                case 0xba -> code.writeBytes(new byte[] {0, (byte) indy, 0, 0});
                case 0xbb, 0xbd, 0xc0, 0xc1 -> code.writeBytes(new byte[] {0, (byte) cls});
                case 0xc5 -> code.writeBytes(new byte[] {0, (byte) cls, 2});
                case 0xc8, 0xc9 -> code.writeBytes(new byte[] {0, 0, 0, 8});
                case 0xaa -> {
                    pad(code);
                    code.writeBytes(ints(20, -1, 1, 8, 12));      // default, low -1, high 1, three targets
                    code.writeBytes(ints(16));
                }
                case 0xab -> {
                    pad(code);
                    code.writeBytes(ints(20, 2, -7, 8, 300, 12)); // default, 2 pairs
                }
                case 0xc4 -> {
                    code.writeBytes(new byte[] {0x15, 1, 0x2c});
                    code.writeBytes(new byte[] {(byte) 0xc4, (byte) 0x84, 1, 0x2c, 3, (byte) 0xe8});
                    code.writeBytes(new byte[] {(byte) 0xc4, (byte) 0xa9, 0, 7});
                    code.writeBytes(new byte[] {(byte) 0xc4, 0x37, 0, 8});
                    code.writeBytes(new byte[] {(byte) 0xc4, 0x3a, (byte) 0xff, (byte) 0xff});
                }
                default -> {
                    if (op >= 0x15 && op <= 0x19 || op >= 0x36 && op <= 0x3a) {
                        code.write(7);
                    } else if (op >= 0x99 && op <= 0xa8 || op == 0xc6 || op == 0xc7) {
                        code.writeBytes(new byte[] {0, 5});
                    }
                }
            }
        }
        byte[] bytes = code.toByteArray();
        img.method(Img.STATIC, "m", "()V", 0, 0, bytes);
        Path file = tmp.resolve("T.class");
        Files.write(file, img.bytes());
        List<Instruction> ins = Disassembler.decode(bytes);
        List<String> mine = new ArrayList<>();
        Set<String> names = new TreeSet<>();
        for (Instruction in : ins) {
            mine.add(in.offset() + ": " + Disassembler.render(in));
            names.add(in.mnemonic());
        }
        for (int op = 0; op < Opcodes.COUNT; op++) {
            String expectedName = op == Opcodes.WIDE ? "iload_w" : Opcodes.name(op); // wide folds into *_w forms
            assertTrue(names.contains(expectedName), "opcode " + op + " " + expectedName + " missing from decode");
        }
        List<List<String>> expected = javapOutput("T", "-c", "-p", file.toString());
        assertEquals(1, expected.size());
        assertEquals(expected.get(0), mine);
        assertTrue(mine.size() > 205, "instructions " + mine.size());
    }

    private static void pad(java.io.ByteArrayOutputStream code) {
        while ((code.size() & 3) != 0) {
            code.write(0);
        }
    }

    private static byte[] ints(int... v) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(4 * v.length);
        for (int x : v) {
            b.putInt(x);
        }
        return b.array();
    }
}
