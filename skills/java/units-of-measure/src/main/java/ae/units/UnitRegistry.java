package ae.units;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Symbol table and parser for unit expressions such as {@code kg*m/s^2} or {@code (mm)^2}.
 *
 * <p>Grammar (whitespace between tokens is ignored):
 * <pre>
 * expr := term (('*' | '/') term)*        left associative
 * term := atom ('^' ['+'|'-'] digits)?    exponent magnitude at most 64
 * atom := NAME | '1' | '(' expr ')'
 * </pre>
 * Symbols are case sensitive. A NAME resolves to an exact symbol first; otherwise to one SI prefix
 * (longest first) followed by a prefixable symbol. Affine units (degC, degF) are accepted only as the
 * whole expression.
 */
public final class UnitRegistry {

    private static final int MAX_EXPONENT = 64;
    /** Largest numerator or denominator (in bits) a parsed factor may reach. */
    private static final int MAX_FACTOR_BITS = 1 << 16;

    private record Def(Unit unit, boolean prefixable) {}

    private record Prefix(String symbol, int power) {}

    /** Longest symbols first so that "da" wins over "d". */
    private static final List<Prefix> PREFIXES = List.of(
        new Prefix("da", 1), new Prefix("Y", 24), new Prefix("Z", 21), new Prefix("E", 18), new Prefix("P", 15),
        new Prefix("T", 12), new Prefix("G", 9), new Prefix("M", 6), new Prefix("k", 3), new Prefix("h", 2),
        new Prefix("d", -1), new Prefix("c", -2), new Prefix("m", -3), new Prefix("u", -6), new Prefix("µ", -6),
        new Prefix("n", -9), new Prefix("p", -12), new Prefix("f", -15));

    private static final Map<String, Def> SYMBOLS = buildSymbols();

    private UnitRegistry() {}

    private static Rational dec(String text) {
        return Rational.parseDecimal(text);
    }

    private static Unit base(Dimension d, String factor) {
        return new Unit(d, dec(factor), Rational.of(0));
    }

    private static void put(Map<String, Def> map, String symbol, Unit unit, boolean prefixable) {
        map.put(symbol, new Def(unit, prefixable));
    }

    private static Map<String, Def> buildSymbols() {
        Map<String, Def> m = new LinkedHashMap<>();
        Unit metre = base(Dimension.LENGTH, "1");
        Unit kilogram = base(Dimension.MASS, "1");
        Unit second = base(Dimension.TIME, "1");
        Unit ampere = base(Dimension.CURRENT, "1");
        put(m, "m", metre, true);
        put(m, "g", base(Dimension.MASS, "0.001"), true);
        put(m, "s", second, true);
        put(m, "A", ampere, true);
        put(m, "K", base(Dimension.TEMPERATURE, "1"), true);
        put(m, "mol", base(Dimension.AMOUNT, "1"), true);
        put(m, "cd", base(Dimension.LUMINOUS, "1"), true);

        Unit newton = kilogram.times(metre).dividedBy(second.pow(2));
        Unit joule = newton.times(metre);
        Unit watt = joule.dividedBy(second);
        Unit pascal = newton.dividedBy(metre.pow(2));
        Unit volt = watt.dividedBy(ampere);
        put(m, "N", newton, true);
        put(m, "Hz", second.pow(-1), true);
        put(m, "Pa", pascal, true);
        put(m, "J", joule, true);
        put(m, "W", watt, true);
        put(m, "C", ampere.times(second), true);
        put(m, "V", volt, true);
        put(m, "ohm", volt.dividedBy(ampere), true);
        put(m, "L", base(Dimension.LENGTH.pow(3), "0.001"), true);
        put(m, "t", base(Dimension.MASS, "1000"), true);
        put(m, "bar", pascal.times(base(Dimension.NONE, "100000")), true);
        put(m, "cal", joule.times(base(Dimension.NONE, "4.184")), true);
        put(m, "eV", joule.times(base(Dimension.NONE, "1.602176634e-19")), true);

        Unit inch = base(Dimension.LENGTH, "0.0254");
        Unit pound = base(Dimension.MASS, "0.45359237");
        Unit lbf = pound.times(metre.dividedBy(second.pow(2)).times(base(Dimension.NONE, "9.80665")));
        put(m, "min", base(Dimension.TIME, "60"), false);
        put(m, "h", base(Dimension.TIME, "3600"), false);
        put(m, "d", base(Dimension.TIME, "86400"), false);
        put(m, "in", inch, false);
        put(m, "ft", base(Dimension.LENGTH, "0.3048"), false);
        put(m, "yd", base(Dimension.LENGTH, "0.9144"), false);
        put(m, "mi", base(Dimension.LENGTH, "1609.344"), false);
        put(m, "lb", pound, false);
        put(m, "oz", pound.times(base(Dimension.NONE, "0.0625")), false);
        put(m, "gal", base(Dimension.LENGTH.pow(3), "0.003785411784"), false);
        put(m, "lbf", lbf, false);
        put(m, "atm", pascal.times(base(Dimension.NONE, "101325")), false);
        put(m, "psi", lbf.dividedBy(inch.pow(2)), false);

        Unit degC = new Unit(Dimension.TEMPERATURE, Rational.of(1), dec("273.15"));
        Unit degF = new Unit(Dimension.TEMPERATURE, Rational.of(5, 9), Rational.of(45967, 180));
        put(m, "degC", degC, false);
        put(m, "°C", degC, false);
        put(m, "degF", degF, false);
        put(m, "°F", degF, false);
        return Map.copyOf(m);
    }

    /**
     * Parses a unit expression.
     *
     * @throws IllegalArgumentException on any syntax error, unknown symbol, out-of-range exponent or
     *     affine unit used inside a compound expression; the message names the offending text
     * @throws NullPointerException if {@code text} is null
     */
    public static Unit parse(String text) {
        Objects.requireNonNull(text, "text");
        Parser p = new Parser(text);
        try {
            Unit result = p.expr();
            p.skipWhitespace();
            if (!p.atEnd()) {
                throw p.error("unexpected text '" + text.substring(p.pos) + "'");
            }
            return result;
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("unit expression too large in '" + text + "': " + e.getMessage(), e);
        }
    }

    private static Unit lookup(String name) {
        Def exact = SYMBOLS.get(name);
        if (exact != null) {
            return exact.unit();
        }
        for (Prefix prefix : PREFIXES) {
            if (!name.startsWith(prefix.symbol())) {
                continue;
            }
            Def def = SYMBOLS.get(name.substring(prefix.symbol().length()));
            if (def != null && def.prefixable()) {
                Rational scale = Rational.of(10).pow(prefix.power());
                Unit u = def.unit();
                return new Unit(u.dimension(), u.factor().times(scale), u.offset());
            }
        }
        throw new IllegalArgumentException("unknown unit symbol '" + name + "'");
    }

    private static Unit checked(Unit u) {
        if (u.factor().numerator().bitLength() > MAX_FACTOR_BITS || u.factor().denominator().bitLength() > MAX_FACTOR_BITS) {
            throw new IllegalArgumentException("unit factor too large");
        }
        return u;
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " in unit expression '" + text + "' at index " + pos);
        }

        Unit expr() {
            Unit acc = term();
            while (true) {
                skipWhitespace();
                if (atEnd()) {
                    return acc;
                }
                char c = text.charAt(pos);
                if (c == '*') {
                    pos++;
                    acc = checked(acc.times(term()));
                } else if (c == '/') {
                    pos++;
                    acc = checked(acc.dividedBy(term()));
                } else {
                    return acc;
                }
            }
        }

        Unit term() {
            Unit base = atom();
            skipWhitespace();
            if (!atEnd() && text.charAt(pos) == '^') {
                pos++;
                return checked(base.pow(exponent()));
            }
            return base;
        }

        int exponent() {
            skipWhitespace();
            boolean negative = false;
            if (!atEnd() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                negative = text.charAt(pos) == '-';
                pos++;
                skipWhitespace();
            }
            int start = pos;
            while (!atEnd() && text.charAt(pos) >= '0' && text.charAt(pos) <= '9') {
                pos++;
            }
            if (start == pos) {
                throw error("expected exponent digits");
            }
            String digits = text.substring(start, pos);
            if (digits.length() > 3 || Integer.parseInt(digits) > MAX_EXPONENT) {
                throw error("exponent " + digits + " exceeds " + MAX_EXPONENT);
            }
            int value = Integer.parseInt(digits);
            return negative ? -value : value;
        }

        Unit atom() {
            skipWhitespace();
            if (atEnd()) {
                throw error("expected a unit");
            }
            char c = text.charAt(pos);
            if (c == '(') {
                pos++;
                Unit inner = expr();
                skipWhitespace();
                if (atEnd() || text.charAt(pos) != ')') {
                    throw error("expected ')'");
                }
                pos++;
                return inner;
            }
            if (c >= '0' && c <= '9') {
                int start = pos;
                while (!atEnd() && text.charAt(pos) >= '0' && text.charAt(pos) <= '9') {
                    pos++;
                }
                String digits = text.substring(start, pos);
                if (!digits.equals("1")) {
                    throw error("only '1' is allowed as a number, found '" + digits + "'");
                }
                return new Unit(Dimension.NONE, Rational.of(1), Rational.of(0));
            }
            if (Character.isLetter(c) || c == '°') {
                int start = pos;
                while (!atEnd() && (Character.isLetter(text.charAt(pos)) || text.charAt(pos) == '°')) {
                    pos++;
                }
                return lookup(text.substring(start, pos));
            }
            throw error("unexpected '" + c + "'");
        }
    }
}
