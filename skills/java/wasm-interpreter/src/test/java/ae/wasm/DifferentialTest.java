package ae.wasm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Random well-typed expression trees are compiled to wasm and run, and compared with an independent evaluator that
 * works on BigInteger (unsigned canonical values modulo 2^width) and never uses the JDK integer helpers that the
 * interpreter relies on. The generator guarantees validity by construction.
 */
class DifferentialTest {
    /** Raised by the oracle when the expression traps. */
    private static final class Oracle extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final Trap.Kind kind;

        Oracle(Trap.Kind kind) {
            super(kind.name(), null, false, false);
            this.kind = kind;
        }
    }

    private static final BigInteger TWO = BigInteger.TWO;

    private static BigInteger mod(BigInteger x, int w) {
        return x.mod(TWO.pow(w));
    }

    private static BigInteger signed(BigInteger u, int w) {
        return u.testBit(w - 1) ? u.subtract(TWO.pow(w)) : u;
    }

    private abstract static class Node {
        final int w; // 32 or 64

        Node(int w) {
            this.w = w;
        }

        abstract void emit(Bin.C c);

        abstract BigInteger eval(BigInteger[] params);
    }

    private static Node leafParam(int w, int idx) {
        return new Node(w) {
            void emit(Bin.C c) { c.get(idx); }
            BigInteger eval(BigInteger[] p) { return p[idx]; }
        };
    }

    private static Node leafConst(int w, BigInteger v) {
        return new Node(w) {
            void emit(Bin.C c) {
                BigInteger sv = signed(v, w);
                if (w == 32) {
                    c.i32(sv.intValueExact());
                } else {
                    c.i64(sv.longValueExact());
                }
            }

            BigInteger eval(BigInteger[] p) { return v; }
        };
    }

    /** Note: the operand width must not be called {@code w}: it would be shadowed by the inherited field Node.w. */
    private static Node binary(int inW, int op, String name, Node a, Node b, int outW) {
        return new Node(outW) {
            void emit(Bin.C c) { a.emit(c); b.emit(c); c.b(op); }

            BigInteger eval(BigInteger[] p) {
                BigInteger x = a.eval(p);
                BigInteger y = b.eval(p);
                return apply(name, inW, x, y);
            }
        };
    }

    private static Node unary(int inW, int op, String name, Node a, int outW) {
        return new Node(outW) {
            void emit(Bin.C c) { a.emit(c); c.b(op); }

            BigInteger eval(BigInteger[] p) { return applyUnary(name, inW, a.eval(p)); }
        };
    }

    private static BigInteger bool(boolean b) {
        return b ? BigInteger.ONE : BigInteger.ZERO;
    }

    private static BigInteger apply(String name, int w, BigInteger a, BigInteger b) {
        int k = b.mod(BigInteger.valueOf(w)).intValue();
        BigInteger sa = signed(a, w);
        BigInteger sb = signed(b, w);
        BigInteger min = TWO.pow(w - 1).negate();
        return switch (name) {
            case "add" -> mod(a.add(b), w);
            case "sub" -> mod(a.subtract(b), w);
            case "mul" -> mod(a.multiply(b), w);
            case "and" -> a.and(b);
            case "or" -> a.or(b);
            case "xor" -> a.xor(b);
            case "shl" -> mod(a.shiftLeft(k), w);
            case "shr_u" -> a.shiftRight(k);
            case "shr_s" -> mod(sa.shiftRight(k), w);
            case "rotl" -> mod(a.shiftLeft(k).or(a.shiftRight(w - k)), w);
            case "rotr" -> mod(a.shiftRight(k).or(a.shiftLeft(w - k)), w);
            case "div_u" -> {
                if (b.signum() == 0) {
                    throw new Oracle(Trap.Kind.DIV_ZERO);
                }
                yield a.divide(b);
            }
            case "rem_u" -> {
                if (b.signum() == 0) {
                    throw new Oracle(Trap.Kind.DIV_ZERO);
                }
                yield a.remainder(b);
            }
            case "div_s" -> {
                if (b.signum() == 0) {
                    throw new Oracle(Trap.Kind.DIV_ZERO);
                }
                if (sa.equals(min) && sb.equals(BigInteger.ONE.negate())) {
                    throw new Oracle(Trap.Kind.INT_OVERFLOW);
                }
                yield mod(sa.divide(sb), w); // BigInteger.divide truncates toward zero
            }
            case "rem_s" -> {
                if (b.signum() == 0) {
                    throw new Oracle(Trap.Kind.DIV_ZERO);
                }
                yield mod(sa.remainder(sb), w);
            }
            case "eq" -> bool(a.equals(b));
            case "ne" -> bool(!a.equals(b));
            case "lt_u" -> bool(a.compareTo(b) < 0);
            case "gt_u" -> bool(a.compareTo(b) > 0);
            case "le_u" -> bool(a.compareTo(b) <= 0);
            case "ge_u" -> bool(a.compareTo(b) >= 0);
            case "lt_s" -> bool(sa.compareTo(sb) < 0);
            case "gt_s" -> bool(sa.compareTo(sb) > 0);
            case "le_s" -> bool(sa.compareTo(sb) <= 0);
            case "ge_s" -> bool(sa.compareTo(sb) >= 0);
            default -> throw new IllegalStateException(name);
        };
    }

    private static BigInteger applyUnary(String name, int w, BigInteger a) {
        return switch (name) {
            case "clz" -> BigInteger.valueOf(w - a.bitLength());
            case "ctz" -> BigInteger.valueOf(a.signum() == 0 ? w : a.getLowestSetBit());
            case "popcnt" -> BigInteger.valueOf(a.bitCount());
            case "eqz" -> bool(a.signum() == 0);
            case "wrap" -> mod(a, 32);
            case "extend_s" -> mod(signed(a, 32), 64);
            case "extend_u" -> a;
            case "ext8" -> mod(signed(mod(a, 8), 8), w);
            case "ext16" -> mod(signed(mod(a, 16), 16), w);
            case "ext32" -> mod(signed(mod(a, 32), 32), 64);
            default -> throw new IllegalStateException(name);
        };
    }

    private static final String[] ARITH = {"add", "sub", "mul", "div_s", "div_u", "rem_s", "rem_u", "and", "or",
        "xor", "shl", "shr_s", "shr_u", "rotl", "rotr"};
    private static final String[] CMP = {"eq", "ne", "lt_s", "lt_u", "gt_s", "gt_u", "le_s", "le_u", "ge_s", "ge_u"};

    private static int arithOp(String n, int w) {
        int base = w == 32 ? 0x6A : 0x7C;
        // wasm order: add sub mul div_s div_u rem_s rem_u and or xor shl shr_s shr_u rotl rotr
        for (int i = 0; i < ARITH.length; i++) {
            if (ARITH[i].equals(n)) {
                return base + i;
            }
        }
        throw new IllegalStateException(n);
    }

    private static int cmpOp(String n, int w) {
        int base = w == 32 ? 0x46 : 0x51;
        for (int i = 0; i < CMP.length; i++) {
            if (CMP[i].equals(n)) {
                return base + i;
            }
        }
        throw new IllegalStateException(n);
    }

    private static final BigInteger[] EDGES32 = {BigInteger.ZERO, BigInteger.ONE, BigInteger.valueOf(0xFFFFFFFFL),
        BigInteger.valueOf(0x80000000L), BigInteger.valueOf(0x7FFFFFFFL), BigInteger.valueOf(31),
        BigInteger.valueOf(32), BigInteger.valueOf(33), BigInteger.TWO};

    private static BigInteger randomValue(Random r, int w) {
        int pick = r.nextInt(6);
        if (pick < 2) {
            BigInteger e = EDGES32[r.nextInt(EDGES32.length)];
            if (w == 64 && r.nextBoolean()) {
                e = e.shiftLeft(32 * r.nextInt(2)).or(BigInteger.valueOf(r.nextInt(3)));
            }
            return mod(e, w);
        }
        if (pick == 2) {
            return mod(BigInteger.valueOf(r.nextInt(70) - 35), w);
        }
        byte[] bytes = new byte[w / 8];
        r.nextBytes(bytes);
        return new BigInteger(1, bytes);
    }

    private static Node gen(Random r, int w, int depth) {
        if (depth == 0 || r.nextInt(10) == 0) {
            // params: 0,1 are i32; 2,3 are i64
            if (r.nextInt(3) == 0) {
                return leafConst(w, randomValue(r, w));
            }
            return leafParam(w, (w == 32 ? 0 : 2) + r.nextInt(2));
        }
        int kind = r.nextInt(w == 32 ? 12 : 9);
        switch (kind) {
            case 0, 1, 2, 3: {
                String n = ARITH[r.nextInt(ARITH.length)];
                return binary(w, arithOp(n, w), n, gen(r, w, depth - 1), gen(r, w, depth - 1), w);
            }
            case 4: {
                String n = new String[] {"clz", "ctz", "popcnt"}[r.nextInt(3)];
                int op = (w == 32 ? 0x67 : 0x79) + (n.equals("clz") ? 0 : n.equals("ctz") ? 1 : 2);
                return unary(w, op, n, gen(r, w, depth - 1), w);
            }
            case 5: {
                // select(a, b, cond)
                Node a = gen(r, w, depth - 1);
                Node b = gen(r, w, depth - 1);
                Node cnd = gen(r, 32, depth - 1);
                return new Node(w) {
                    void emit(Bin.C c) { a.emit(c); b.emit(c); cnd.emit(c); c.b(0x1B); }

                    BigInteger eval(BigInteger[] p) {
                        BigInteger x = a.eval(p);
                        BigInteger y = b.eval(p);
                        BigInteger z = cnd.eval(p);
                        return z.signum() != 0 ? x : y;
                    }
                };
            }
            case 6: {
                // if/else expression: only the taken arm is evaluated (and may trap)
                Node cnd = gen(r, 32, depth - 1);
                Node a = gen(r, w, depth - 1);
                Node b = gen(r, w, depth - 1);
                return new Node(w) {
                    void emit(Bin.C c) {
                        cnd.emit(c);
                        c.b(0x04, w == 32 ? 0x7F : 0x7E);
                        a.emit(c);
                        c.b(0x05);
                        b.emit(c);
                        c.end();
                    }

                    BigInteger eval(BigInteger[] p) {
                        return cnd.eval(p).signum() != 0 ? a.eval(p) : b.eval(p);
                    }
                };
            }
            case 7: {
                // sign extension of the same width
                String n = r.nextBoolean() ? "ext8" : "ext16";
                int op = w == 32 ? (n.equals("ext8") ? 0xC0 : 0xC1) : (n.equals("ext8") ? 0xC2 : 0xC3);
                return unary(w, op, n, gen(r, w, depth - 1), w);
            }
            case 8: {
                if (w == 64) {
                    if (r.nextBoolean()) {
                        return extend(r, depth);
                    }
                    return unary(64, 0xC4, "ext32", gen(r, 64, depth - 1), 64);
                }
                return unary(64, 0xA7, "wrap", gen(r, 64, depth - 1), 32);
            }
            case 9: {
                // i32 comparisons of i32 operands
                String n = CMP[r.nextInt(CMP.length)];
                return binary(32, cmpOp(n, 32), n, gen(r, 32, depth - 1), gen(r, 32, depth - 1), 32);
            }
            case 10: {
                String n = CMP[r.nextInt(CMP.length)];
                return binary(64, cmpOp(n, 64), n, gen(r, 64, depth - 1), gen(r, 64, depth - 1), 32);
            }
            default: {
                return unary(64, 0x50, "eqz", gen(r, 64, depth - 1), 32);
            }
        }
    }

    private static Node extend(Random r, int depth) {
        boolean signedExt = r.nextBoolean();
        return unary(32, signedExt ? 0xAC : 0xAD, signedExt ? "extend_s" : "extend_u", gen(r, 32, depth - 1), 64);
    }

    @Test
    void randomExpressionTreesAgreeWithTheBigIntegerOracle() {
        Random r = new Random(20261126L);
        int traps = 0;
        int values = 0;
        int checked = 0;
        for (int iter = 0; iter < 2500; iter++) {
            int w = r.nextBoolean() ? 32 : 64;
            Node tree = gen(r, w, 1 + r.nextInt(4));
            Bin.C body = Bin.c();
            tree.emit(body);
            Bin.M m = new Bin.M();
            int t = m.type("iill:" + (tree.w == 32 ? "i" : "l"));
            m.exportFunc("f", m.func(t, "", body));
            Instance inst = Instance.instantiate(ModuleParser.parse(m.build()));
            for (int rep = 0; rep < 3; rep++) {
                BigInteger[] params = {randomValue(r, 32), randomValue(r, 32), randomValue(r, 64), randomValue(r, 64)};
                long[] args = {params[0].intValue(), params[1].intValue(), params[2].longValue(), params[3].longValue()};
                Object expected;
                try {
                    expected = tree.eval(params);
                } catch (Oracle o) {
                    expected = o.kind;
                }
                Object actual;
                try {
                    long v = inst.invoke("f", args)[0];
                    actual = tree.w == 32 ? BigInteger.valueOf(v & 0xFFFFFFFFL) : mod(BigInteger.valueOf(v), 64);
                    if (tree.w == 32) {
                        assertEquals(v, (long) (int) v, "i32 result must be sign-extended");
                    }
                } catch (Trap t2) {
                    actual = t2.kind();
                }
                assertEquals(expected, actual, "iter " + iter + " rep " + rep + " params " + java.util.Arrays.toString(params)
                        + " body " + java.util.HexFormat.of().formatHex(body.bytes()));
                checked++;
                if (expected instanceof Trap.Kind) {
                    traps++;
                } else {
                    values++;
                }
            }
        }
        assertEquals(7500, checked);
        assertTrue(traps > 50, "traps " + traps);
        assertTrue(values > 5000, "values " + values);
    }
}
