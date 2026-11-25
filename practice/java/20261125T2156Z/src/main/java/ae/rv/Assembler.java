package ae.rv;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Two-pass assembler for the RV32IM subset (see TASK.md for syntax). Base address is 0. */
public final class Assembler {
    private static final Pattern MEM = Pattern.compile("^(.*)\\((\\w+)\\)$");
    private static final Pattern LABEL = Pattern.compile("^([A-Za-z_.][A-Za-z0-9_.]*):\\s*(.*)$");
    private static final Map<String, Integer> REGS = new HashMap<>();
    private static final Map<String, Op> OPS = new HashMap<>();

    static {
        for (int i = 0; i < 32; i++) {
            REGS.put("x" + i, i);
        }
        String[] abi = {"zero", "ra", "sp", "gp", "tp", "t0", "t1", "t2", "s0", "s1", "a0", "a1", "a2", "a3", "a4",
            "a5", "a6", "a7", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "s10", "s11", "t3", "t4", "t5", "t6"};
        for (int i = 0; i < 32; i++) {
            REGS.put(abi[i], i);
        }
        REGS.put("fp", 8);
        for (Op op : Op.values()) {
            OPS.put(op.mnemonic(), op);
        }
    }

    private record Stmt(int line, String mnemonic, List<String> args, int addr) { }

    private Assembler() { }

    /** Assembles the source text. */
    public static Program assemble(String source) {
        Map<String, Integer> labels = new LinkedHashMap<>();
        List<Stmt> stmts = new ArrayList<>();
        int addr = 0;
        String[] lines = source.split("\n", -1);
        for (int n = 0; n < lines.length; n++) {
            int lineNo = n + 1;
            String text = lines[n];
            int hash = text.indexOf('#');
            if (hash >= 0) {
                text = text.substring(0, hash);
            }
            text = text.strip();
            Matcher m = LABEL.matcher(text);
            while (m.matches()) {
                if (labels.put(m.group(1), addr) != null) {
                    throw new AsmException(lineNo, "duplicate label " + m.group(1));
                }
                text = m.group(2).strip();
                m = LABEL.matcher(text);
            }
            if (text.isEmpty()) {
                continue;
            }
            int sp = indexOfWhitespace(text);
            String mnemonic = (sp < 0 ? text : text.substring(0, sp)).toLowerCase(Locale.ROOT);
            List<String> args = new ArrayList<>();
            if (sp >= 0) {
                for (String a : text.substring(sp).split(",", -1)) {
                    args.add(a.strip());
                }
            }
            Stmt s = new Stmt(lineNo, mnemonic, args, addr);
            addr += 4 * size(s);
            stmts.add(s);
        }
        List<Integer> words = new ArrayList<>();
        for (Stmt s : stmts) {
            try {
                emit(s, labels, words);
            } catch (AsmException e) {
                throw e;
            } catch (IllegalArgumentException e) {
                throw new AsmException(s.line, e.getMessage() + " in '" + s.mnemonic + "'");
            }
        }
        int[] out = new int[words.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = words.get(i);
        }
        return new Program(out, labels);
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /** Words emitted by a statement; only {@code li} depends on its operand. */
    private static int size(Stmt s) {
        if (!s.mnemonic.equals("li")) {
            return 1;
        }
        if (s.args.size() != 2) {
            throw new AsmException(s.line, "li expects 2 operands");
        }
        long v = parseLong(s, s.args.get(1));
        if (v < Integer.MIN_VALUE || v > 0xFFFFFFFFL) {
            throw new AsmException(s.line, "immediate out of range: " + s.args.get(1));
        }
        int value = (int) v;
        return fitsI(value) || ((value << 20) >> 20) == 0 ? 1 : 2;
    }

    private static boolean fitsI(long v) {
        return v >= -2048 && v <= 2047;
    }

    private static long parseLong(Stmt s, String text) {
        try {
            return Long.decode(text);
        } catch (NumberFormatException e) {
            throw new AsmException(s.line, "bad number '" + text + "'");
        }
    }

    private static int reg(Stmt s, String text) {
        Integer r = REGS.get(text.toLowerCase(Locale.ROOT));
        if (r == null) {
            throw new AsmException(s.line, "bad register '" + text + "'");
        }
        return r;
    }

    private static int imm(Stmt s, String text) {
        long v = parseLong(s, text);
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
            throw new AsmException(s.line, "immediate out of range: " + text);
        }
        return (int) v;
    }

    private static int target(Stmt s, String text, Map<String, Integer> labels) {
        Integer a = labels.get(text);
        if (a != null) {
            return a - s.addr;
        }
        if (!text.isEmpty() && (Character.isDigit(text.charAt(0)) || text.charAt(0) == '-')) {
            return imm(s, text);
        }
        throw new AsmException(s.line, "unknown label '" + text + "'");
    }

    private static void need(Stmt s, int n) {
        if (s.args.size() != n) {
            throw new AsmException(s.line, s.mnemonic + " expects " + n + " operands, got " + s.args.size());
        }
    }

    private static void add(List<Integer> out, Op op, int rd, int rs1, int rs2, int imm) {
        out.add(Codec.encode(new Instr(op, rd, rs1, rs2, imm)));
    }

    private static void emit(Stmt s, Map<String, Integer> labels, List<Integer> out) {
        List<String> a = s.args;
        switch (s.mnemonic) {
            case ".word" -> {
                need(s, 1);
                long v = parseLong(s, a.get(0));
                if (v < Integer.MIN_VALUE || v > 0xFFFFFFFFL) {
                    throw new AsmException(s.line, ".word out of range: " + a.get(0));
                }
                out.add((int) v);
            }
            case "nop" -> { need(s, 0); add(out, Op.ADDI, 0, 0, 0, 0); }
            case "ret" -> { need(s, 0); add(out, Op.JALR, 0, 1, 0, 0); }
            case "mv" -> { need(s, 2); add(out, Op.ADDI, reg(s, a.get(0)), reg(s, a.get(1)), 0, 0); }
            case "not" -> { need(s, 2); add(out, Op.XORI, reg(s, a.get(0)), reg(s, a.get(1)), 0, -1); }
            case "neg" -> { need(s, 2); add(out, Op.SUB, reg(s, a.get(0)), 0, reg(s, a.get(1)), 0); }
            case "seqz" -> { need(s, 2); add(out, Op.SLTIU, reg(s, a.get(0)), reg(s, a.get(1)), 0, 1); }
            case "snez" -> { need(s, 2); add(out, Op.SLTU, reg(s, a.get(0)), 0, reg(s, a.get(1)), 0); }
            case "j" -> { need(s, 1); add(out, Op.JAL, 0, 0, 0, target(s, a.get(0), labels)); }
            case "jr" -> { need(s, 1); add(out, Op.JALR, 0, reg(s, a.get(0)), 0, 0); }
            case "beqz" -> branch(s, labels, out, Op.BEQ, false, true);
            case "bnez" -> branch(s, labels, out, Op.BNE, false, true);
            case "bltz" -> branch(s, labels, out, Op.BLT, false, true);
            case "bgez" -> branch(s, labels, out, Op.BGE, false, true);
            case "bgt" -> branch(s, labels, out, Op.BLT, true, false);
            case "ble" -> branch(s, labels, out, Op.BGE, true, false);
            case "bgtu" -> branch(s, labels, out, Op.BLTU, true, false);
            case "bleu" -> branch(s, labels, out, Op.BGEU, true, false);
            case "li" -> {
                int rd = reg(s, a.get(0));
                long v = parseLong(s, a.get(1));
                if (v < Integer.MIN_VALUE || v > 0xFFFFFFFFL) {
                    throw new AsmException(s.line, "immediate out of range: " + a.get(1));
                }
                int value = (int) v;
                if (fitsI(value)) {
                    add(out, Op.ADDI, rd, 0, 0, value);
                } else {
                    int lo = (value << 20) >> 20;
                    add(out, Op.LUI, rd, 0, 0, (value - lo) >>> 12);
                    if (lo != 0) {
                        add(out, Op.ADDI, rd, rd, 0, lo);
                    }
                }
            }
            case "jal" -> {
                if (a.size() == 1) {
                    add(out, Op.JAL, 1, 0, 0, target(s, a.get(0), labels));
                } else {
                    need(s, 2);
                    add(out, Op.JAL, reg(s, a.get(0)), 0, 0, target(s, a.get(1), labels));
                }
            }
            default -> {
                Op op = OPS.get(s.mnemonic);
                if (op == null) {
                    throw new AsmException(s.line, "unknown mnemonic '" + s.mnemonic + "'");
                }
                emitReal(s, op, labels, out);
            }
        }
    }

    /** Pseudo branches: swap = exchange the two compared registers; zero = compare against x0. */
    private static void branch(Stmt s, Map<String, Integer> labels, List<Integer> out, Op op, boolean swap,
            boolean zero) {
        if (zero) {
            need(s, 2);
            add(out, op, 0, reg(s, s.args.get(0)), 0, target(s, s.args.get(1), labels));
        } else {
            need(s, 3);
            int x = reg(s, s.args.get(0));
            int y = reg(s, s.args.get(1));
            add(out, op, 0, swap ? y : x, swap ? x : y, target(s, s.args.get(2), labels));
        }
    }

    private static void emitReal(Stmt s, Op op, Map<String, Integer> labels, List<Integer> out) {
        List<String> a = s.args;
        switch (op.format) {
            case R -> {
                need(s, 3);
                add(out, op, reg(s, a.get(0)), reg(s, a.get(1)), reg(s, a.get(2)), 0);
            }
            case SH -> {
                need(s, 3);
                add(out, op, reg(s, a.get(0)), reg(s, a.get(1)), 0, imm(s, a.get(2)));
            }
            case I -> {
                if (op.isLoad() || op == Op.JALR && a.size() == 2) {
                    need(s, 2);
                    Matcher m = MEM.matcher(a.get(1));
                    if (!m.matches()) {
                        throw new AsmException(s.line, "expected imm(reg), got '" + a.get(1) + "'");
                    }
                    String off = m.group(1).strip();
                    add(out, op, reg(s, a.get(0)), reg(s, m.group(2)), 0, off.isEmpty() ? 0 : imm(s, off));
                } else {
                    need(s, 3);
                    add(out, op, reg(s, a.get(0)), reg(s, a.get(1)), 0, imm(s, a.get(2)));
                }
            }
            case S -> {
                need(s, 2);
                Matcher m = MEM.matcher(a.get(1));
                if (!m.matches()) {
                    throw new AsmException(s.line, "expected imm(reg), got '" + a.get(1) + "'");
                }
                String off = m.group(1).strip();
                add(out, op, 0, reg(s, m.group(2)), reg(s, a.get(0)), off.isEmpty() ? 0 : imm(s, off));
            }
            case B -> {
                need(s, 3);
                add(out, op, 0, reg(s, a.get(0)), reg(s, a.get(1)), target(s, a.get(2), labels));
            }
            case U -> {
                need(s, 2);
                add(out, op, reg(s, a.get(0)), 0, 0, imm(s, a.get(1)));
            }
            case J -> throw new AsmException(s.line, "internal: jal handled above");
            case SYS -> {
                need(s, 0);
                add(out, op, 0, 0, 0, 0);
            }
        }
    }
}
