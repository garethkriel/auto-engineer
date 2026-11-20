package ae.lp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class LpParserTest {
    private static Rational[] r(long... v) {
        Rational[] out = new Rational[v.length];
        for (int i = 0; i < v.length; i++) out[i] = Rational.of(v[i]);
        return out;
    }

    @Test
    void parsesObjectiveAndConstraints() {
        LpModel m = LpParser.parse("""
                # sample
                max: 3 x + 2y

                c1: x + y <= 4
                x + 3y <= 6
                x >= 1/2
                """);
        assertEquals(LpModel.Sense.MAX, m.sense());
        assertEquals(List.of("x", "y"), m.variables());
        assertArrayEquals(r(3, 2), m.objective());
        assertEquals(3, m.constraints().size());
        LpModel.Constraint c1 = m.constraints().get(0);
        assertEquals("c1", c1.name());
        assertArrayEquals(r(1, 1), c1.coef());
        assertEquals(LpModel.Op.LE, c1.op());
        assertEquals(Rational.of(4), c1.rhs());
        assertEquals("c2", m.constraints().get(1).name());
        assertArrayEquals(r(1, 3), m.constraints().get(1).coef());
        LpModel.Constraint c3 = m.constraints().get(2);
        assertEquals(LpModel.Op.GE, c3.op());
        assertEquals(Rational.of(1, 2), c3.rhs());
        assertArrayEquals(r(1, 0), c3.coef());
    }

    @Test
    void minSenseRepeatedTermsSummedAndConstraintOnlyVariables() {
        LpModel m = LpParser.parse("min: x + x - 3 z\nr: x + 2 w - x + 4 w = -7\n");
        assertEquals(LpModel.Sense.MIN, m.sense());
        assertEquals(List.of("x", "z", "w"), m.variables());
        assertArrayEquals(r(2, -3, 0), m.objective());
        LpModel.Constraint c = m.constraints().get(0);
        assertArrayEquals(r(0, 0, 6), c.coef());
        assertEquals(LpModel.Op.EQ, c.op());
        assertEquals(Rational.of(-7), c.rhs());
    }

    @Test
    void fractionCoefficients() {
        LpModel m = LpParser.parse("min: -3/4 x4 + 20 x5\n1/4 x4 - 8 x5 <= 0\n");
        assertArrayEquals(new Rational[] {Rational.of(-3, 4), Rational.of(20)}, m.objective());
        assertArrayEquals(new Rational[] {Rational.of(1, 4), Rational.of(-8)}, m.constraints().get(0).coef());
    }

    @Test
    void modelWithNoConstraintsIsAllowed() {
        LpModel m = LpParser.parse("max: x");
        assertTrue(m.constraints().isEmpty());
    }

    private static void assertErr(String text, int line, String fragment) {
        LpParseException e = assertThrows(LpParseException.class, () -> LpParser.parse(text), text);
        assertEquals(line, e.line(), "line for " + text);
        assertTrue(e.getMessage().contains(fragment), e.getMessage() + " should contain " + fragment);
    }

    @Test
    void errorsCarryLineNumbers() {
        assertErr("", 1, "objective");
        assertErr("# only a comment\n\n", 3, "objective");
        assertErr("x + y <= 3\n", 1, "objective");
        assertErr("max: x\nmax: y\n", 2, "objective");
        assertErr("max: x\n\nx + y 3\n", 3, "operator");
        assertErr("max: x\nx + $ <= 3\n", 2, "character");
        assertErr("max: x\n<= 3\n", 2, "variable");
        assertErr("max: x\n1/0 x <= 3\n", 2, "denominator");
        assertErr("max: x\nx <= y\n", 2, "constant");
        assertErr("max: x\nx <= 3 4\n", 2, "trailing");
        assertErr("max: x\nc: x <= 3\nc: x >= 1\n", 3, "duplicate");
        assertErr("max: x +\n", 1, "term");
        assertErr("max: x\nx <= \n", 2, "constant");
    }

    @Test
    void modelIsImmutable() {
        LpModel m = LpParser.parse("max: x\nx <= 1\n");
        assertThrows(UnsupportedOperationException.class, () -> m.constraints().add(null));
        assertThrows(UnsupportedOperationException.class, () -> m.variables().add("q"));
        Rational[] copy = m.objective();
        copy[0] = Rational.of(99);
        assertEquals(Rational.ONE, m.objective()[0]);
    }
}
