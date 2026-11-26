package ae.cfg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A function: parameters plus blocks; block 0 is the entry. Validated on construction. */
public record Function(String name, List<String> params, List<Block> blocks) {
    private static final String ID = "[A-Za-z_][A-Za-z0-9_]*";
    private static final Pattern HEADER = Pattern.compile("func (" + ID + ")\\((.*)\\)");
    private static final Pattern LABEL = Pattern.compile("(" + ID + "):");
    private static final Pattern CONST = Pattern.compile("(" + ID + ") = const (-?[0-9]+)");
    private static final Pattern BIN = Pattern.compile("(" + ID + ") = (add|sub|mul|lt|eq) (" + ID + "), (" + ID + ")");
    private static final Pattern COPY = Pattern.compile("(" + ID + ") = copy (" + ID + ")");
    private static final Pattern BR = Pattern.compile("br (" + ID + "), (" + ID + "), (" + ID + ")");
    private static final Pattern JMP = Pattern.compile("jmp (" + ID + ")");
    private static final Pattern RET = Pattern.compile("ret (" + ID + ")");

    public Function {
        Objects.requireNonNull(name, "name");
        params = List.copyOf(params);
        blocks = List.copyOf(blocks);
        validate(params, blocks);
    }

    private static void validate(List<String> params, List<Block> blocks) {
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("function has no blocks");
        }
        Set<String> paramSet = new HashSet<>();
        for (String p : params) {
            if (!paramSet.add(p)) {
                throw new IllegalArgumentException("duplicate param " + p);
            }
        }
        Set<String> labels = new HashSet<>();
        Set<String> defined = new HashSet<>(paramSet);
        for (Block b : blocks) {
            if (!labels.add(b.label())) {
                throw new IllegalArgumentException("duplicate label " + b.label());
            }
            List<Instr> ins = b.instrs();
            if (ins.isEmpty() || !ins.get(ins.size() - 1).isTerminator()) {
                throw new IllegalArgumentException("block " + b.label() + " must end with a terminator");
            }
            for (int i = 0; i < ins.size() - 1; i++) {
                if (ins.get(i).isTerminator()) {
                    throw new IllegalArgumentException("block " + b.label() + " has a terminator before its last instruction");
                }
            }
            ins.forEach(i -> i.def().ifPresent(defined::add));
        }
        for (Block b : blocks) {
            for (Instr i : b.instrs()) {
                for (String t : i.targets()) {
                    if (!labels.contains(t)) {
                        throw new IllegalArgumentException("unknown label " + t + " in block " + b.label());
                    }
                }
                for (String u : i.uses()) {
                    if (!defined.contains(u)) {
                        throw new IllegalArgumentException("undefined variable " + u + " in block " + b.label());
                    }
                }
            }
        }
    }

    /** Parses the text form described in TASK.md. */
    public static Function parse(String text) {
        Objects.requireNonNull(text, "text");
        String name = null;
        List<String> params = new ArrayList<>();
        List<Block> blocks = new ArrayList<>();
        String label = null;
        List<Instr> cur = new ArrayList<>();
        int lineNo = 0;
        for (String raw : text.split("\n", -1)) {
            lineNo++;
            int hash = raw.indexOf('#');
            String line = (hash >= 0 ? raw.substring(0, hash) : raw).strip();
            if (line.isEmpty()) {
                continue;
            }
            if (name == null) {
                Matcher m = HEADER.matcher(line);
                if (!m.matches()) {
                    throw syntax(lineNo, "expected function header");
                }
                name = m.group(1);
                String ps = m.group(2).strip();
                if (!ps.isEmpty()) {
                    for (String p : ps.split(",", -1)) {
                        if (!p.strip().matches(ID)) {
                            throw syntax(lineNo, "bad parameter '" + p.strip() + "'");
                        }
                        params.add(p.strip());
                    }
                }
                continue;
            }
            Matcher lm = LABEL.matcher(line);
            if (lm.matches()) {
                if (label != null) {
                    blocks.add(new Block(label, cur));
                }
                label = lm.group(1);
                cur = new ArrayList<>();
                continue;
            }
            if (label == null) {
                throw syntax(lineNo, "instruction before the first label");
            }
            cur.add(instr(line, lineNo));
        }
        if (name == null) {
            throw new IllegalArgumentException("line 1: expected function header");
        }
        if (label != null) {
            blocks.add(new Block(label, cur));
        }
        return new Function(name, params, blocks);
    }

    private static Instr instr(String line, int lineNo) {
        Matcher m;
        if ((m = CONST.matcher(line)).matches()) {
            try {
                return new Instr.Const(m.group(1), Long.parseLong(m.group(2)));
            } catch (NumberFormatException e) {
                throw syntax(lineNo, "constant out of range");
            }
        }
        if ((m = BIN.matcher(line)).matches()) {
            return new Instr.Bin(m.group(1), Instr.Op.valueOf(m.group(2).toUpperCase(java.util.Locale.ROOT)), m.group(3), m.group(4));
        }
        if ((m = COPY.matcher(line)).matches()) {
            return new Instr.Copy(m.group(1), m.group(2));
        }
        if ((m = BR.matcher(line)).matches()) {
            return new Instr.Br(m.group(1), m.group(2), m.group(3));
        }
        if ((m = JMP.matcher(line)).matches()) {
            return new Instr.Jmp(m.group(1));
        }
        if ((m = RET.matcher(line)).matches()) {
            return new Instr.Ret(m.group(1));
        }
        throw syntax(lineNo, "cannot parse '" + line + "'");
    }

    private static IllegalArgumentException syntax(int lineNo, String msg) {
        return new IllegalArgumentException("line " + lineNo + ": " + msg);
    }

    /** Canonical text; {@code parse(toText())} equals this function. */
    public String toText() {
        StringBuilder sb = new StringBuilder("func ").append(name).append('(').append(String.join(", ", params)).append(")\n");
        for (Block b : blocks) {
            sb.append(b.label()).append(":\n");
            for (Instr i : b.instrs()) {
                sb.append("  ").append(i.text()).append('\n');
            }
        }
        return sb.toString();
    }

    /** Label -> block index. */
    Map<String, Integer> labelIndex() {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < blocks.size(); i++) {
            m.put(blocks.get(i).label(), i);
        }
        return m;
    }
}
