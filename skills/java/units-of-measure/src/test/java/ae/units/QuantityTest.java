package ae.units;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class QuantityTest {

    private static Rational r(String decimal) {
        return Rational.parseDecimal(decimal);
    }

    private static Quantity q(String text) {
        return Quantity.parse(text);
    }

    private static Rational conv(String from, String to) {
        return q(from).to(to).value();
    }

    @Test
    void parseLiterals() {
        assertEquals(r("12.5"), q("12.5 km/h").value());
        assertEquals(UnitRegistry.parse("km/h"), q("12.5 km/h").unit());
        assertEquals(r("-300"), q("-3e2 m").value());
        assertEquals(r("0.0015"), q("1.5E-3 s").value());
        assertEquals(r("7"), q("  7   kg ").value());
        assertEquals(r("0.5"), q(".5 m").value());
        assertEquals(Rational.of(5), q("5").value());
        assertTrue(q("5").unit().dimension().isDimensionless());
        assertEquals(r("5"), q("5eV").value()); // the 'e' belongs to the unit: no exponent digits follow
        assertEquals("L^2 M T^-2", q("5eV").unit().dimension().describe());
        assertEquals(r("5000"), q("5e3V").value());
        assertEquals("L^2 M T^-3 I^-1", q("5e3V").unit().dimension().describe());
        assertEquals(r("2"), q("+2 m").value());
    }

    @Test
    void parseRejectsMalformedQuantities() {
        for (String bad : new String[] {"", "   ", "km", "1.5.2 m", "5 foo", "5 m s", "m 5", "--5 m", "5 m/", "1e5000 m", "5 degC*m"}) {
            assertThrows(IllegalArgumentException.class, () -> q(bad), "[" + bad + "]");
        }
        assertThrows(NullPointerException.class, () -> Quantity.parse(null));
    }

    @Test
    void publishedConversionLiterals() {
        assertEquals(r("1609.344"), conv("1 mi", "m"));
        assertEquals(r("0.3048"), conv("1 ft", "m"));
        assertEquals(Rational.of(381, 1250), conv("12 in", "m")); // 12 * 0.0254 = 0.3048
        assertEquals(Rational.of(16764, 625), conv("60 mi/h", "m/s")); // 26.8224
        assertEquals(r("3600000"), conv("1 kW*h", "J"));
        assertEquals(Rational.of(8896443230521L, 2000000000000L), conv("1 lbf", "N")); // 4.4482216152605
        assertEquals(r("3.785411784"), conv("1 gal", "L"));
        assertEquals(r("3785.411784"), conv("1 gal", "mL"));
        assertEquals(r("101325"), conv("1 atm", "Pa"));
        assertEquals(r("1.01325"), conv("1 atm", "bar"));
        assertEquals(r("4.184"), conv("1 cal", "J"));
        assertEquals(r("1.602176634e-19"), conv("1 eV", "J"));
        assertEquals(r("1000"), conv("1 kg", "g"));
        assertEquals(Rational.of(1, 1000), conv("1 g", "kg"));
        assertEquals(r("16"), conv("1 lb", "oz"));
        assertEquals(r("1000"), conv("1 t", "kg"));
        assertEquals(Rational.of(1, 1000000), conv("1 mg", "kg"));
        assertEquals(Rational.of(1), conv("1 N", "kg*m/s^2"));
        assertEquals(Rational.of(1), conv("1 J", "N*m"));
        assertEquals(Rational.of(1), conv("1 V*A", "W"));
        assertEquals(Rational.of(1), conv("1 Pa", "N/m^2"));
        assertEquals(Rational.of(1), conv("1 Hz", "1/s"));
        assertEquals(Rational.of(1), conv("1 C", "A*s"));
        assertEquals(Rational.of(1), conv("1 L", "dm^3"));
        assertEquals(r("100000"), conv("1 bar", "Pa"));
        assertEquals(r("100"), conv("1 hPa", "Pa"));
        assertEquals(Rational.of(1), conv("1 hPa", "mbar"));
        assertEquals(r("0.01"), conv("1 mm^2", "cm^2"));
        assertEquals(r("86400"), conv("1 d", "s"));
        assertEquals(r("1440"), conv("1 d", "min"));
        assertEquals(6894.757293168362, q("1 psi").to("Pa").value().doubleValue(), 1e-9);
    }

    @Test
    void temperatureLiterals() {
        assertEquals(Rational.of(212), conv("100 degC", "degF"));
        assertEquals(r("273.15"), conv("0 degC", "K"));
        assertEquals(Rational.of(0), conv("32 degF", "degC"));
        assertEquals(Rational.of(-40), conv("-40 degC", "degF"));
        assertEquals(Rational.of(-40), conv("-40 degF", "degC"));
        assertEquals(Rational.of(45967, 180), conv("0 degF", "K")); // 459.67 * 5/9
        assertEquals(Rational.of(-160, 9), conv("0 degF", "degC")); // (0 - 32) * 5/9
        assertEquals(r("26.85"), conv("300 K", "degC"));
        assertEquals(Rational.of(0), conv("273.15 K", "degC"));
        assertEquals(Rational.of(0), conv("0 K", "K"));
        assertEquals(r("-273.15"), conv("0 K", "degC"));
        assertEquals(Rational.of(1, 1000), conv("1 mK", "K"));
        assertEquals(Rational.of(0), conv("-459.67 degF", "K"));
    }

    @Test
    void conversionRejectsDimensionMismatchNamingBothDimensions() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> q("1 m").to("s"));
        assertTrue(ex.getMessage().contains("L") && ex.getMessage().contains("T"), ex.getMessage());
        // control: differs from the failing call only by the dimension of the target
        assertDoesNotThrow(() -> q("1 m").to("ft"));
        assertThrows(IllegalArgumentException.class, () -> q("1 degC").to("m"));
        assertThrows(IllegalArgumentException.class, () -> q("1").to("m"));
        assertDoesNotThrow(() -> q("1").to("m/m"));
    }

    @Test
    void conversionKeepsTheOriginalQuantityImmutable() {
        Quantity a = q("5 km");
        Quantity b = a.to("m");
        assertEquals(r("5"), a.value());
        assertEquals(r("5000"), b.value());
        assertEquals(UnitRegistry.parse("km"), a.unit());
        assertEquals(UnitRegistry.parse("m"), b.unit());
    }

    @Test
    void plusAndMinusUseTheLeftUnit() {
        Quantity sum = q("1 km").plus(q("500 m"));
        assertEquals(Rational.of(3, 2), sum.value());
        assertEquals(UnitRegistry.parse("km"), sum.unit());
        assertEquals(r("1500"), sum.to("m").value());
        Quantity flipped = q("500 m").plus(q("1 km"));
        assertEquals(r("1500"), flipped.value());
        assertEquals(UnitRegistry.parse("m"), flipped.unit());
        assertEquals(r("0.6952"), q("1 m").minus(q("1 ft")).value());
        assertEquals(r("-1"), q("0 m").minus(q("1 m")).value());
    }

    @Test
    void plusRejectsDimensionMismatchAndAffineUnits() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> q("1 m").plus(q("1 s")));
        assertTrue(ex.getMessage().contains("L") && ex.getMessage().contains("T"), ex.getMessage());
        assertThrows(IllegalArgumentException.class, () -> q("1 m").minus(q("1 s")));
        assertThrows(IllegalArgumentException.class, () -> q("1 degC").plus(q("1 degC")));
        assertThrows(IllegalArgumentException.class, () -> q("1 degC").minus(q("1 degC")));
        assertThrows(IllegalArgumentException.class, () -> q("1 K").plus(q("1 degC")));
        // control: kelvin differences are fine
        assertEquals(r("3"), q("1 K").plus(q("2 K")).value());
    }

    @Test
    void timesDividedByAndPow() {
        Quantity area = q("2 m").times(q("3 m"));
        assertEquals(r("6"), area.to("m^2").value());
        assertEquals(Rational.of(10), q("100 m").dividedBy(q("10 s")).to("m/s").value());
        assertEquals(Rational.of(5, 18), q("1 km").dividedBy(q("1 h")).to("m/s").value());
        assertEquals(r("9"), q("3 m").pow(2).to("m^2").value());
        Quantity inv = q("2 km").pow(-1);
        assertEquals(Rational.of(1, 2), inv.value());
        assertEquals(Rational.of(1, 2000), inv.to("1/m").value());
        assertEquals(Rational.of(1), q("7 m").pow(0).value());
        assertTrue(q("7 m").pow(0).unit().dimension().isDimensionless());
        assertEquals(r("0.3048"), q("0.3048 m").pow(1).value());
        assertThrows(ArithmeticException.class, () -> q("1 m").dividedBy(q("0 s")));
        assertThrows(IllegalArgumentException.class, () -> q("1 degC").times(q("2 m")));
        assertThrows(IllegalArgumentException.class, () -> q("2 m").dividedBy(q("1 degC")));
        assertThrows(IllegalArgumentException.class, () -> q("1 degC").pow(2));
    }

    @Test
    void siValueAndEqualsQuantity() {
        assertEquals(r("273.15"), q("0 degC").siValue());
        assertEquals(r("1000"), q("1 km").siValue());
        assertTrue(q("1 km").equalsQuantity(q("1000 m")));
        assertTrue(q("0 degC").equalsQuantity(q("273.15 K")));
        assertTrue(q("212 degF").equalsQuantity(q("100 degC")));
        assertFalse(q("1 km").equalsQuantity(q("1001 m")));
        assertFalse(q("1 km").equalsQuantity(q("1000 s"))); // different dimension: false, not an exception
        assertFalse(q("1 degC").equalsQuantity(q("1 K")));
    }

    @Test
    void toStringShowsValueAndDimension() {
        assertTrue(q("12 km").toString().contains("12"));
        assertTrue(q("12 km").toString().contains("L"));
    }
}
