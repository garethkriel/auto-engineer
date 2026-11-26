package ae.units;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Randomised checks against an independent table of published definitions kept in this file. */
class UnitsPropertyTest {

    private static Rational r(String decimal) {
        return Rational.parseDecimal(decimal);
    }

    /** symbol, group, SI factor (published/defined values written as decimals). */
    private record Row(String symbol, String group, Rational factor) {}

    private static final List<Row> TABLE = List.of(
        new Row("m", "len", r("1")), new Row("km", "len", r("1000")), new Row("mm", "len", r("0.001")),
        new Row("in", "len", r("0.0254")), new Row("ft", "len", r("0.3048")), new Row("yd", "len", r("0.9144")),
        new Row("mi", "len", r("1609.344")), new Row("um", "len", r("0.000001")), new Row("Gm", "len", r("1000000000")),
        new Row("dam", "len", r("10")),
        new Row("kg", "mass", r("1")), new Row("g", "mass", r("0.001")), new Row("mg", "mass", r("0.000001")),
        new Row("lb", "mass", r("0.45359237")), new Row("oz", "mass", r("0.028349523125")), new Row("t", "mass", r("1000")),
        new Row("Mg", "mass", r("1000")),
        new Row("s", "time", r("1")), new Row("ms", "time", r("0.001")), new Row("min", "time", r("60")),
        new Row("h", "time", r("3600")), new Row("d", "time", r("86400")), new Row("ns", "time", r("0.000000001")),
        new Row("Pa", "pressure", r("1")), new Row("kPa", "pressure", r("1000")), new Row("bar", "pressure", r("100000")),
        new Row("atm", "pressure", r("101325")), new Row("mbar", "pressure", r("100")), new Row("hPa", "pressure", r("100")),
        new Row("J", "energy", r("1")), new Row("kJ", "energy", r("1000")), new Row("cal", "energy", r("4.184")),
        new Row("eV", "energy", r("1.602176634e-19")), new Row("kW*h", "energy", r("3600000")),
        new Row("W*s", "energy", r("1")), new Row("N*m", "energy", r("1")), new Row("MJ", "energy", r("1000000")),
        new Row("km/h", "speed", r("1000").dividedBy(r("3600"))), new Row("m/s", "speed", r("1")),
        new Row("mi/h", "speed", r("0.44704")), new Row("ft/s", "speed", r("0.3048")),
        new Row("L", "volume", r("0.001")), new Row("mL", "volume", r("0.000001")), new Row("m^3", "volume", r("1")),
        new Row("gal", "volume", r("0.003785411784")), new Row("dm^3", "volume", r("0.001")));

    private static Rational randomValue(Random rnd) {
        long num = rnd.nextInt(2_000_001) - 1_000_000;
        long den = 1 + rnd.nextInt(999);
        return Rational.of(num, den);
    }

    @Test
    void tableSelfCheck() {
        // every group has at least four members so that the random pair picking is meaningful
        for (String g : List.of("len", "mass", "time", "pressure", "energy", "speed", "volume")) {
            assertTrue(TABLE.stream().filter(t -> t.group().equals(g)).count() >= 3, g);
        }
    }

    @Test
    void everyUnitMatchesItsPublishedFactor() {
        for (Row row : TABLE) {
            assertEquals(row.factor(), UnitRegistry.parse(row.symbol()).factor(), row.symbol());
        }
    }

    @Test
    void sameGroupUnitsShareADimensionAndDifferentGroupsDoNot() {
        for (Row a : TABLE) {
            for (Row b : TABLE) {
                Dimension da = UnitRegistry.parse(a.symbol()).dimension();
                Dimension db = UnitRegistry.parse(b.symbol()).dimension();
                boolean sameGroup = a.group().equals(b.group());
                // the seven groups have seven pairwise different dimensions (e.g. pressure L^-1 M T^-2 vs energy L^2 M T^-2)
                assertEquals(sameGroup, da.equals(db), a.symbol() + " vs " + b.symbol());
            }
        }
    }

    @Test
    void randomConversionsMatchTheFactorTableAndRoundTrip() {
        Random rnd = new Random(1126);
        for (int i = 0; i < 1500; i++) {
            Row a = TABLE.get(rnd.nextInt(TABLE.size()));
            List<Row> peers = new ArrayList<>(TABLE.stream().filter(t -> t.group().equals(a.group())).toList());
            Row b = peers.get(rnd.nextInt(peers.size()));
            Row c = peers.get(rnd.nextInt(peers.size()));
            Rational v = randomValue(rnd);
            Quantity qa = new Quantity(v, UnitRegistry.parse(a.symbol()));
            Quantity qb = qa.to(UnitRegistry.parse(b.symbol()));
            String ctx = v + " " + a.symbol() + " -> " + b.symbol() + " -> " + c.symbol();
            assertEquals(v.times(a.factor()).dividedBy(b.factor()), qb.value(), ctx);
            assertEquals(v, qb.to(UnitRegistry.parse(a.symbol())).value(), "round trip " + ctx);
            assertEquals(qa.to(UnitRegistry.parse(c.symbol())).value(), qb.to(UnitRegistry.parse(c.symbol())).value(), "transitive " + ctx);
            assertTrue(qa.equalsQuantity(qb), ctx);
        }
    }

    @Test
    void conversionIsLinearForNonAffineUnits() {
        Random rnd = new Random(55);
        for (int i = 0; i < 400; i++) {
            Row a = TABLE.get(rnd.nextInt(TABLE.size()));
            Row b = TABLE.stream().filter(t -> t.group().equals(a.group())).findFirst().orElseThrow();
            Unit ua = UnitRegistry.parse(a.symbol());
            Unit ub = UnitRegistry.parse(b.symbol());
            Rational x = randomValue(rnd);
            Rational y = randomValue(rnd);
            Rational lhs = new Quantity(x.plus(y), ua).to(ub).value();
            Rational rhs = new Quantity(x, ua).to(ub).value().plus(new Quantity(y, ua).to(ub).value());
            assertEquals(lhs, rhs, a.symbol());
        }
    }

    // ---- temperatures: independent (offset, factor) table in kelvin ----
    private static final Rational[][] TEMP = {
        {Rational.of(1), Rational.of(0)},                            // K
        {Rational.of(1), r("273.15")},                              // degC
        {Rational.of(5, 9), Rational.of(45967, 180)},               // degF
    };
    private static final String[] TEMP_SYMBOLS = {"K", "degC", "degF"};

    @Test
    void randomTemperatureConversionsMatchTheOffsetTable() {
        Random rnd = new Random(98);
        for (int i = 0; i < 600; i++) {
            int a = rnd.nextInt(3);
            int b = rnd.nextInt(3);
            Rational v = randomValue(rnd);
            Rational kelvin = v.times(TEMP[a][0]).plus(TEMP[a][1]);
            Rational expected = kelvin.minus(TEMP[b][1]).dividedBy(TEMP[b][0]);
            Quantity got = new Quantity(v, UnitRegistry.parse(TEMP_SYMBOLS[a])).to(TEMP_SYMBOLS[b]);
            assertEquals(expected, got.value(), v + " " + TEMP_SYMBOLS[a] + " -> " + TEMP_SYMBOLS[b]);
            assertEquals(v, got.to(TEMP_SYMBOLS[a]).value());
        }
    }

    // ---- random expression differential: generated tree vs the parser ----

    /** Independent evaluation: exponents as int[7] and factor as Rational. */
    private record Val(int[] exps, Rational factor) {
        Val times(Val o) {
            int[] e = new int[7];
            for (int i = 0; i < 7; i++) {
                e[i] = exps[i] + o.exps[i];
            }
            return new Val(e, factor.times(o.factor));
        }

        Val inverse() {
            int[] e = new int[7];
            for (int i = 0; i < 7; i++) {
                e[i] = -exps[i];
            }
            return new Val(e, Rational.of(1).dividedBy(factor));
        }

        Val pow(int n) {
            int[] e = new int[7];
            for (int i = 0; i < 7; i++) {
                e[i] = exps[i] * n;
            }
            return new Val(e, factor.pow(n));
        }
    }

    private static final String[][] ATOMS = {
        // symbol, exponents L M T I Th N J, factor
        {"m", "1,0,0,0,0,0,0", "1"}, {"kg", "0,1,0,0,0,0,0", "1"}, {"s", "0,0,1,0,0,0,0", "1"},
        {"A", "0,0,0,1,0,0,0", "1"}, {"K", "0,0,0,0,1,0,0", "1"}, {"mol", "0,0,0,0,0,1,0", "1"},
        {"cd", "0,0,0,0,0,0,1", "1"}, {"ft", "1,0,0,0,0,0,0", "0.3048"}, {"mm", "1,0,0,0,0,0,0", "0.001"},
        {"g", "0,1,0,0,0,0,0", "0.001"}, {"lb", "0,1,0,0,0,0,0", "0.45359237"}, {"h", "0,0,1,0,0,0,0", "3600"},
        {"ms", "0,0,1,0,0,0,0", "0.001"}, {"mA", "0,0,0,1,0,0,0", "0.001"},
    };

    private static final class Gen {
        final Random rnd;
        Gen(Random rnd) { this.rnd = rnd; }

        /** Returns the expression text; fills value through the holder. */
        String gen(int depth, Val[] out) {
            int kind = depth == 0 ? 0 : rnd.nextInt(5);
            if (kind == 0 || kind == 4) {
                String[] atom = ATOMS[rnd.nextInt(ATOMS.length)];
                String[] ex = atom[1].split(",");
                int[] e = new int[7];
                for (int i = 0; i < 7; i++) {
                    e[i] = Integer.parseInt(ex[i]);
                }
                out[0] = new Val(e, r(atom[2]));
                return atom[0];
            }
            Val[] left = new Val[1];
            String l = gen(depth - 1, left);
            if (kind == 3) {
                int n = rnd.nextInt(5) - 2;
                out[0] = left[0].pow(n);
                return "(" + l + ")^" + n;
            }
            Val[] right = new Val[1];
            String rt = gen(depth - 1, right);
            boolean mul = kind == 1;
            out[0] = mul ? left[0].times(right[0]) : left[0].times(right[0].inverse());
            // wrap an operand in parentheses unless it is an atom: the parser must honour the grouping
            String lt = l.matches("[A-Za-z]+") ? l : "(" + l + ")";
            String rtt = rt.matches("[A-Za-z]+") ? rt : "(" + rt + ")";
            return lt + (mul ? " * " : " / ") + rtt;
        }
    }

    @Test
    void randomExpressionsMatchTheIndependentEvaluator() {
        Random rnd = new Random(424242);
        Gen gen = new Gen(rnd);
        int nonTrivial = 0;
        for (int i = 0; i < 800; i++) {
            Val[] out = new Val[1];
            String text = gen.gen(rnd.nextInt(4), out);
            Unit u = UnitRegistry.parse(text);
            for (int k = 0; k < 7; k++) {
                assertEquals(out[0].exps()[k], u.dimension().exponent(k), text + " exponent " + k);
            }
            assertEquals(out[0].factor(), u.factor(), text);
            if (text.contains("/")) {
                nonTrivial++;
            }
        }
        assertTrue(nonTrivial > 100, "generator produced too few divisions: " + nonTrivial);
    }

    @Test
    void unparenthesisedChainsAreLeftAssociative() {
        Random rnd = new Random(31);
        for (int i = 0; i < 300; i++) {
            int n = 2 + rnd.nextInt(4);
            StringBuilder sb = new StringBuilder();
            Val acc = null;
            for (int j = 0; j < n; j++) {
                String[] atom = ATOMS[rnd.nextInt(ATOMS.length)];
                String[] ex = atom[1].split(",");
                int[] e = new int[7];
                for (int k = 0; k < 7; k++) {
                    e[k] = Integer.parseInt(ex[k]);
                }
                Val v = new Val(e, r(atom[2]));
                if (j == 0) {
                    acc = v;
                    sb.append(atom[0]);
                } else if (rnd.nextBoolean()) {
                    acc = acc.times(v);
                    sb.append('*').append(atom[0]);
                } else {
                    acc = acc.times(v.inverse());
                    sb.append('/').append(atom[0]);
                }
            }
            Unit u = UnitRegistry.parse(sb.toString());
            assertEquals(acc.factor(), u.factor(), sb.toString());
            for (int k = 0; k < 7; k++) {
                assertEquals(acc.exps()[k], u.dimension().exponent(k), sb + " exponent " + k);
            }
        }
    }

    @Test
    void garbageNeverEscapesAsAnythingButIllegalArgument() {
        Random rnd = new Random(77);
        String alphabet = "mskg^*/() 12-+.degC°µ";
        int rejected = 0;
        int accepted = 0;
        for (int i = 0; i < 3000; i++) {
            StringBuilder sb = new StringBuilder();
            int len = rnd.nextInt(9);
            for (int j = 0; j < len; j++) {
                sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
            }
            try {
                UnitRegistry.parse(sb.toString());
                accepted++;
            } catch (IllegalArgumentException expected) {
                rejected++;
            }
        }
        assertTrue(rejected > 500 && accepted > 20, "mix rejected=" + rejected + " accepted=" + accepted);
    }
}
