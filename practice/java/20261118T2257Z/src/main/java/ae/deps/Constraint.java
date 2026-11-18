package ae.deps;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** An npm-style version range: {@code ||}-joined alternatives of space-separated comparators. */
public final class Constraint {
    private enum Op { EQ, GT, GE, LT, LE }

    /** One comparison; {@code synthetic} bounds (derived upper limits) never allow prereleases. */
    private record Cmp(Op op, Version v, boolean synthetic) {
        boolean holds(Version x) {
            int c = x.compareTo(v);
            return switch (op) {
                case EQ -> c == 0;
                case GT -> c > 0;
                case GE -> c >= 0;
                case LT -> c < 0;
                case LE -> c <= 0;
            };
        }
    }

    private static final Pattern PARTIAL = Pattern.compile(
        "(\\d+|[xX*])(?:\\.(\\d+|[xX*]))?(?:\\.(\\d+|[xX*]))?(?:-([0-9A-Za-z.-]+))?");
    private static final Pattern OPERATOR = Pattern.compile("\\^|~|>=|<=|>|<|=");

    private final String text;
    private final List<List<Cmp>> alternatives;

    private Constraint(String text, List<List<Cmp>> alternatives) {
        this.text = text;
        this.alternatives = alternatives;
    }

    /** Parses a range; blank means any release version. @throws IllegalArgumentException on bad syntax */
    public static Constraint parse(String source) {
        Objects.requireNonNull(source, "source");
        String trimmed = source.trim();
        List<List<Cmp>> alts = new ArrayList<>();
        for (String part : trimmed.split("\\|\\|", -1)) alts.add(parseAlternative(part.trim(), source));
        return new Constraint(trimmed, List.copyOf(alts));
    }

    private static List<Cmp> parseAlternative(String part, String source) {
        List<Cmp> out = new ArrayList<>();
        String[] tokens = part.isEmpty() ? new String[0] : part.split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            String t = tokens[i];
            if (OPERATOR.matcher(t).matches()) { // operator separated from its version by whitespace
                if (i + 1 >= tokens.length) throw bad(source, "operator '" + t + "' without version");
                t = t + tokens[++i];
            }
            out.addAll(desugar(t, source));
        }
        return List.copyOf(out);
    }

    private static IllegalArgumentException bad(String source, String why) {
        return new IllegalArgumentException("bad constraint '" + source + "': " + why);
    }

    private static List<Cmp> desugar(String token, String source) {
        Matcher om = Pattern.compile("^(\\^|~|>=|<=|>|<|=)?(.*)$").matcher(token);
        if (!om.matches()) throw bad(source, token);
        String op = om.group(1) == null ? "" : om.group(1);
        Matcher m = PARTIAL.matcher(om.group(2));
        if (!m.matches()) throw bad(source, "cannot parse '" + token + "'");
        int[] nums = new int[3];
        int n = 0;
        boolean wild = false;
        for (int g = 1; g <= 3; g++) {
            String s = m.group(g);
            if (s == null) {
                wild = true;
                continue;
            }
            boolean isWild = s.equals("x") || s.equals("X") || s.equals("*");
            if (isWild) {
                wild = true;
            } else {
                if (wild) throw bad(source, "number after wildcard in '" + token + "'");
                if (s.length() > 1 && s.startsWith("0")) throw bad(source, "leading zero in '" + token + "'");
                try {
                    nums[n++] = Integer.parseInt(s);
                } catch (NumberFormatException e) {
                    throw bad(source, "number out of range in '" + token + "'");
                }
            }
        }
        List<String> pre = List.of();
        if (m.group(4) != null) {
            if (n != 3) throw bad(source, "prerelease needs a full version in '" + token + "'");
            try {
                pre = Version.parse("0.0.0-" + m.group(4)).pre();
            } catch (IllegalArgumentException e) {
                throw bad(source, "bad prerelease in '" + token + "'");
            }
        }
        Version lower = new Version(nums[0], nums[1], nums[2], pre);
        List<Cmp> r = new ArrayList<>();
        switch (op) {
            case "", "=" -> {
                if (n == 3) r.add(new Cmp(Op.EQ, lower, false));
                else if (n > 0) {
                    r.add(new Cmp(Op.GE, lower, false));
                    r.add(upper(nums, n));
                }
            }
            case ">" -> {
                if (n == 0) r.add(nothing());
                else if (n == 3) r.add(new Cmp(Op.GT, lower, false));
                else r.add(new Cmp(Op.GE, new Version(next(nums, n)[0], next(nums, n)[1], 0), false));
            }
            case ">=" -> {
                if (n > 0) r.add(new Cmp(Op.GE, lower, false));
            }
            case "<" -> {
                if (n == 0) r.add(nothing());
                else r.add(new Cmp(Op.LT, lower, false));
            }
            case "<=" -> {
                if (n == 3) r.add(new Cmp(Op.LE, lower, false));
                else if (n > 0) r.add(upper(nums, n));
            }
            case "^" -> {
                if (n > 0) {
                    r.add(new Cmp(Op.GE, lower, false));
                    r.add(caretUpper(nums, n));
                }
            }
            case "~" -> {
                if (n > 0) {
                    r.add(new Cmp(Op.GE, lower, false));
                    r.add(n == 1 ? upper(nums, 1) : upper(nums, 2));
                }
            }
            default -> throw bad(source, token);
        }
        return r;
    }

    /** First excluded major.minor after a partial of {@code n} fields (n = 1 or 2); returns {major, minor}. */
    private static int[] next(int[] nums, int n) {
        return n == 1 ? new int[] {nums[0] + 1, 0} : new int[] {nums[0], nums[1] + 1};
    }

    /** Exclusive upper bound just above the partial {@code n}: {@code <next.0-0}; n = 3 means the version itself. */
    private static Cmp upper(int[] nums, int n) {
        if (n == 3) return new Cmp(Op.LT, new Version(nums[0], nums[1], nums[2] + 1, List.of("0")), true);
        int[] x = next(nums, n);
        return new Cmp(Op.LT, new Version(x[0], x[1], 0, List.of("0")), true);
    }

    private static Cmp caretUpper(int[] nums, int n) {
        Version v;
        if (nums[0] > 0) v = new Version(nums[0] + 1, 0, 0, List.of("0"));
        else if (n == 1) v = new Version(1, 0, 0, List.of("0"));
        else if (nums[1] > 0 || n == 2) v = new Version(0, nums[1] + 1, 0, List.of("0"));
        else v = new Version(0, 0, nums[2] + 1, List.of("0"));
        return new Cmp(Op.LT, v, true);
    }

    private static Cmp nothing() {
        return new Cmp(Op.LT, new Version(0, 0, 0, List.of("0")), true);
    }

    /** True if {@code v} satisfies at least one alternative, under the prerelease rule. */
    public boolean matches(Version v) {
        Objects.requireNonNull(v, "v");
        for (List<Cmp> alt : alternatives) {
            boolean ok = true;
            for (Cmp c : alt) {
                if (!c.holds(v)) {
                    ok = false;
                    break;
                }
            }
            if (ok && (!v.isPrerelease() || allowsPrerelease(alt, v))) return true;
        }
        return false;
    }

    private static boolean allowsPrerelease(List<Cmp> alt, Version v) {
        for (Cmp c : alt) {
            if (!c.synthetic() && c.v().isPrerelease() && c.v().major() == v.major() && c.v().minor() == v.minor()
                && c.v().patch() == v.patch()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return text;
    }
}
