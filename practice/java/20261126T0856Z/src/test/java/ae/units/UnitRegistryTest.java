package ae.units;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class UnitRegistryTest {

    private static Rational r(String decimal) {
        return Rational.parseDecimal(decimal);
    }

    private static Unit u(String text) {
        return UnitRegistry.parse(text);
    }

    // symbol, dimension description, factor to SI (decimal literal)
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "m|L|1",
        "g|M|0.001",
        "kg|M|1",
        "s|T|1",
        "A|I|1",
        "K|Th|1",
        "mol|N|1",
        "cd|J|1",
        "N|L M T^-2|1",
        "Hz|T^-1|1",
        "Pa|L^-1 M T^-2|1",
        "J|L^2 M T^-2|1",
        "W|L^2 M T^-3|1",
        "C|T I|1",
        "V|L^2 M T^-3 I^-1|1",
        "ohm|L^2 M T^-3 I^-2|1",
        "L|L^3|0.001",
        "t|M|1000",
        "min|T|60",
        "h|T|3600",
        "d|T|86400",
        "in|L|0.0254",
        "ft|L|0.3048",
        "yd|L|0.9144",
        "mi|L|1609.344",
        "lb|M|0.45359237",
        "oz|M|0.028349523125",
        "gal|L^3|0.003785411784",
        "lbf|L M T^-2|4.4482216152605",
        "bar|L^-1 M T^-2|100000",
        "atm|L^-1 M T^-2|101325",
        "cal|L^2 M T^-2|4.184",
        "eV|L^2 M T^-2|1.602176634e-19",
    })
    void baseTable(String symbol, String dim, String factor) {
        Unit unit = u(symbol);
        assertEquals(dim, unit.dimension().describe(), symbol);
        assertEquals(r(factor), unit.factor(), symbol);
        assertEquals(Rational.of(0), unit.offset(), symbol);
        assertFalse(unit.isAffine(), symbol);
    }

    @Test
    void psiIsLbfPerSquareInch() {
        // 8896443230521/2000000000000 N / (0.0254 m)^2, derived with Python fractions
        assertEquals(Rational.of(8896443230521L, 1290320000L), u("psi").factor());
        assertEquals("L^-1 M T^-2", u("psi").dimension().describe());
        assertEquals(6894.757293168362, u("psi").factor().doubleValue(), 1e-9);
    }

    // text, dimension, factor
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "mm|L|0.001",
        "Mm|L|1000000",
        "km|L|1000",
        "dm|L|0.1",
        "cm|L|0.01",
        "dam|L|10",
        "hm|L|100",
        "um|L|0.000001",
        "µm|L|0.000001",
        "nm|L|0.000000001",
        "pm|L|0.000000000001",
        "fm|L|0.000000000000001",
        "Gm|L|1000000000",
        "Tm|L|1000000000000",
        "Pm|L|1000000000000000",
        "Em|L|1000000000000000000",
        "Zm|L|1000000000000000000000",
        "Ym|L|1000000000000000000000000",
        "ms|T|0.001",
        "kg|M|1",
        "mg|M|0.000001",
        "dag|M|0.01",
        "Mg|M|1000",
        "mmol|N|0.001",
        "ccd|J|0.01",
        "PA|I|1000000000000000",
        "mK|Th|0.001",
        "hPa|L^-1 M T^-2|100",
        "mbar|L^-1 M T^-2|100",
        "kN|L M T^-2|1000",
        "mL|L^3|0.000001",
    })
    void prefixResolution(String text, String dim, String factor) {
        assertEquals(dim, u(text).dimension().describe(), text);
        assertEquals(r(factor), u(text).factor(), text);
    }

    @Test
    void exactSymbolBeatsPrefixSplit() {
        // "min" is minutes, not milli-inch (and inch is not prefixable anyway); "cd" is candela, not centi-day.
        assertEquals(r("60"), u("min").factor());
        assertEquals("J", u("cd").dimension().describe());
        // "mi" is a mile, not milli-"i"; "h" is an hour, "hm" is a hectometre
        assertEquals(r("1609.344"), u("mi").factor());
        assertEquals(r("3600"), u("h").factor());
        assertEquals(r("100"), u("hm").factor());
        // "d" is a day, "dm" a decimetre, "da" is the prefix deka so "dam" is 10 m
        assertEquals(r("86400"), u("d").factor());
        assertEquals(r("0.1"), u("dm").factor());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Km", "MM", "kft", "mmin", "hh", "dd", "kin", "Mi", "T", "k", "da", "mlb", "kdegC", "mdegF", "katm", "kWh", "kmi", "mgal", "xyz", "KM", "S"})
    void invalidSymbolsAreRejectedAndNameTheText(String text) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> u(text), text);
        assertTrue(ex.getMessage().contains(text), ex.getMessage());
    }

    @Test
    void temperaturesAreAffine() {
        assertEquals(new Unit(Dimension.TEMPERATURE, Rational.of(1), r("273.15")), u("degC"));
        assertEquals(new Unit(Dimension.TEMPERATURE, Rational.of(5, 9), Rational.of(45967, 180)), u("degF"));
        assertEquals(u("degC"), u("°C"));
        assertEquals(u("degF"), u("°F"));
        assertTrue(u("degC").isAffine());
        assertTrue(u("degF").isAffine());
        assertFalse(u("K").isAffine());
        assertEquals(u("degC"), u("(degC)"));
        assertEquals(u("degC"), u("degC^1"));
    }

    // expression, dimension, factor
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "m/s|L T^-1|1",
        "m/s/s|L T^-2|1",
        "m/(s*s)|L T^-2|1",
        "m/s*s|L|1",
        "kg*m/s^2|L M T^-2|1",
        "kg * m / s ^ 2|L M T^-2|1",
        "m^-2|L^-2|1",
        "m^+2|L^2|1",
        "m^ -2|L^-2|1",
        "(m/s)^2|L^2 T^-2|1",
        "((m))|L|1",
        "1/s|T^-1|1",
        "1|1|1",
        "m/m|1|1",
        "m^0|1|1",
        "mm^2|L^2|0.000001",
        "(mm)^2|L^2|0.000001",
        "cm^3|L^3|0.000001",
        "mi/h|L T^-1|0.44704",
        "kW*h|L^2 M T^-2|3600000",
        "N*m|L^2 M T^-2|1",
        "V*A|L^2 M T^-3|1",
        "mg/L|L^-3 M|0.001",
        "g/cm^3|L^-3 M|1000",
        "m^64|L^64|1",
        "m^-64|L^-64|1",
    })
    void expressions(String expr, String dim, String factor) {
        Unit unit = u(expr);
        assertEquals(dim, unit.dimension().describe(), expr);
        assertEquals(r(factor), unit.factor(), expr);
    }

    @Test
    void kilometresPerHourIsFiveEighteenthsOfMetrePerSecond() {
        // 1000 m / 3600 s = 5/18 (not a terminating decimal)
        assertEquals(Rational.of(5, 18), u("km/h").factor());
        assertEquals("L T^-1", u("km/h").dimension().describe());
    }

    @Test
    void mixedOperatorsAreLeftAssociative() {
        // m*s/kg*s = ((m*s)/kg)*s ; m/(kg*s) is different
        assertEquals("L M^-1 T^2", u("m*s/kg*s").dimension().describe());
        assertEquals("L M^-1 T^-1", u("m/(kg*s)").dimension().describe());
        assertEquals("L M^-1 T^-2", u("m/kg/s/s").dimension().describe());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "foo", "m/", "/m", "m*", "*m", "m^", "m^^2", "m^2^3", "m**2", "(m", "m)", "()", "m s",
        "m^2.5", "m^65", "m^-65", "2", "12", "2m", "m+s", "kg;", "degC*m", "m*degC", "1/degC", "degC^2", "(degC)^2",
        "degC/degC", "degC^0", "degC*1", "m/(", "m^(2)", "m^a", "m^-", "1/", "^2"})
    void grammarViolationsAreRejected(String text) {
        assertThrows(IllegalArgumentException.class, () -> u(text), "[" + text + "]");
    }

    @ParameterizedTest
    @ValueSource(strings = {"m^64", "(m)", "m*s", "m^-64", "degC", "(degC)", "degC^1", "1", "m/m", "((m))^2", "m^+0"})
    void controlsForRejectionTableParse(String text) {
        assertDoesNotThrow(() -> u(text), text);
    }

    @Test
    void messagesNameTheOffendingText() {
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class, () -> u("kg*xx"));
        assertTrue(unknown.getMessage().contains("xx"), unknown.getMessage());
        IllegalArgumentException trailing = assertThrows(IllegalArgumentException.class, () -> u("m s"));
        assertTrue(trailing.getMessage().contains("s"), trailing.getMessage());
        IllegalArgumentException affine = assertThrows(IllegalArgumentException.class, () -> u("degC*m"));
        assertTrue(affine.getMessage().toLowerCase().contains("affine"), affine.getMessage());
        IllegalArgumentException range = assertThrows(IllegalArgumentException.class, () -> u("m^65"));
        assertTrue(range.getMessage().contains("65"), range.getMessage());
    }

    @Test
    void nullIsRejected() {
        assertThrows(NullPointerException.class, () -> UnitRegistry.parse(null));
    }

    @Test
    void unitAlgebraRejectsAffineOperands() {
        Unit c = u("degC");
        Unit m = u("m");
        assertThrows(IllegalArgumentException.class, () -> c.times(m));
        assertThrows(IllegalArgumentException.class, () -> m.times(c));
        assertThrows(IllegalArgumentException.class, () -> m.dividedBy(c));
        assertThrows(IllegalArgumentException.class, () -> c.dividedBy(m));
        assertThrows(IllegalArgumentException.class, () -> c.pow(2));
        assertThrows(IllegalArgumentException.class, () -> c.pow(0));
        assertSame(c, c.pow(1));
        assertEquals(u("m^2"), m.times(m));
        assertEquals(u("1"), m.dividedBy(m));
    }

    @Test
    void nestedPowersAreBudgetedInsteadOfExhaustingMemory() {
        // (ft/m) is dimensionless with factor 381/1250 (9 and 11 bits); each ^64 multiplies the bits by 64.
        // 1 level: 704 bits; 2 levels: 45056 bits (under the 65536 cap); 3 levels would be ~2.9M bits.
        assertEquals(Rational.of(381, 1250).pow(64), u("(ft/m)^64").factor());
        assertEquals(Rational.of(381, 1250).pow(4096), u("((ft/m)^64)^64").factor());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> u("(((ft/m)^64)^64)^64"));
        assertTrue(ex.getMessage().contains("too large"), ex.getMessage());
        // only the DENOMINATOR grows: g = 1/1000, (g^64)^64 has a 40k-bit denominator, its square 80k > 65536
        assertDoesNotThrow(() -> u("(g^64)^64"));
        assertThrows(IllegalArgumentException.class, () -> u("(g^64)^64 * (g^64)^64"));
        // dimension exponents overflow int long before memory matters: (m^64)^64 ... six levels is 2^36
        assertDoesNotThrow(() -> u("((m^64)^64)^64"));
        assertThrows(IllegalArgumentException.class, () -> u("((((((m^64)^64)^64)^64)^64)^64)"));
    }
}
