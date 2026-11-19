package ae.sat;

import java.util.ArrayList;
import java.util.List;

/** DIMACS CNF reader and writer. */
public final class Dimacs {
    private Dimacs() {
    }

    /** Parses DIMACS text; errors are {@link IllegalArgumentException}s whose message starts with {@code line N:}. */
    public static Cnf parse(String text) {
        String[] lines = text.split("\n", -1);
        int vars = -1;
        int declared = -1;
        List<int[]> clauses = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        int lastLine = 0;
        for (int i = 0; i < lines.length; i++) {
            int no = i + 1;
            String line = lines[i].strip();
            if (line.startsWith("%")) {
                break;
            }
            if (line.isEmpty() || line.startsWith("c")) {
                continue;
            }
            lastLine = no; // errors detected at end of input point at the last line with content
            if (line.startsWith("p")) {
                if (vars >= 0) {
                    throw err(no, "duplicate header");
                }
                String[] t = line.split("\\s+");
                if (t.length != 4 || !t[1].equals("cnf")) {
                    throw err(no, "malformed header");
                }
                try {
                    vars = Integer.parseInt(t[2]);
                    declared = Integer.parseInt(t[3]);
                } catch (NumberFormatException e) {
                    throw err(no, "malformed header");
                }
                if (vars < 0 || declared < 0) {
                    throw err(no, "malformed header");
                }
                continue;
            }
            if (vars < 0) {
                throw err(no, "missing header");
            }
            for (String tok : line.split("\\s+")) {
                int lit;
                try {
                    lit = Integer.parseInt(tok);
                } catch (NumberFormatException e) {
                    throw err(no, "bad token '" + tok + "'");
                }
                if (lit == 0) {
                    if (clauses.size() == declared) {
                        throw err(no, "too many clauses");
                    }
                    clauses.add(current.stream().mapToInt(Integer::intValue).toArray());
                    current.clear();
                } else {
                    if (Math.abs((long) lit) > vars) {
                        throw err(no, "literal " + lit + " out of range");
                    }
                    current.add(lit);
                }
            }
        }
        if (vars < 0) {
            throw err(1, "missing header");
        }
        if (!current.isEmpty()) {
            throw err(lastLine, "unterminated clause");
        }
        if (clauses.size() != declared) {
            throw err(lastLine, "expected " + declared + " clauses but found " + clauses.size());
        }
        return new Cnf(vars, clauses);
    }

    /** Writes the formula, one clause per line, {@code 0}-terminated. */
    public static String write(Cnf cnf) {
        StringBuilder sb = new StringBuilder();
        sb.append("p cnf ").append(cnf.numVars()).append(' ').append(cnf.clauseCount()).append('\n');
        for (int i = 0; i < cnf.clauseCount(); i++) {
            for (int lit : cnf.clauseAt(i)) {
                sb.append(lit).append(' ');
            }
            sb.append("0\n");
        }
        return sb.toString();
    }

    private static IllegalArgumentException err(int line, String msg) {
        return new IllegalArgumentException("line " + line + ": " + msg);
    }
}
