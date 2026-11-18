package ae.deps;

import java.util.Map;
import java.util.TreeMap;

/** Parses a registry from text: {@code name@1.2.3: dep constraint, dep2 constraint}. */
public final class TextRegistry {
    private TextRegistry() {}

    /**
     * Parses the whole text.
     *
     * @throws IllegalArgumentException with the 1-based line number on any malformed line
     */
    public static Registry parse(String text) {
        InMemoryRegistry reg = new InMemoryRegistry();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            line = line.trim();
            if (line.isEmpty()) continue;
            try {
                reg.add(parseLine(line));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("line " + (i + 1) + ": " + e.getMessage(), e);
            }
        }
        return reg;
    }

    private static Package parseLine(String line) {
        int colon = line.indexOf(':');
        if (colon < 0) throw new IllegalArgumentException("missing ':' in '" + line + "'");
        String head = line.substring(0, colon).trim();
        int at = head.indexOf('@');
        if (at < 0) throw new IllegalArgumentException("missing '@' in '" + head + "'");
        String name = head.substring(0, at);
        Package.checkName(name);
        Version v = Version.parse(head.substring(at + 1));
        Map<String, Constraint> deps = new TreeMap<>();
        String rest = line.substring(colon + 1).trim();
        if (!rest.isEmpty()) {
            for (String seg : rest.split(",", -1)) {
                String s = seg.trim();
                int sp = s.indexOf(' ');
                if (sp < 0) throw new IllegalArgumentException("dependency '" + s + "' needs a constraint");
                String dn = s.substring(0, sp);
                Package.checkName(dn);
                if (deps.put(dn, Constraint.parse(s.substring(sp + 1))) != null) {
                    throw new IllegalArgumentException("duplicate dependency " + dn);
                }
            }
        }
        return new Package(name, v, deps);
    }
}
