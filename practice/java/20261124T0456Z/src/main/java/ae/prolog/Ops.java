package ae.prolog;

import java.util.Map;

/** The fixed operator table. */
final class Ops {
    enum Type { XFX, XFY, YFX }

    record Op(int prec, Type type) {
        int leftMax() {
            return type == Type.YFX ? prec : prec - 1;
        }

        int rightMax() {
            return type == Type.XFY ? prec : prec - 1;
        }
    }

    static final Map<String, Op> INFIX = Map.ofEntries(
        Map.entry(":-", new Op(1200, Type.XFX)),
        Map.entry(";", new Op(1100, Type.XFY)),
        Map.entry("->", new Op(1050, Type.XFY)),
        Map.entry(",", new Op(1000, Type.XFY)),
        Map.entry("=", new Op(700, Type.XFX)),
        Map.entry("\\=", new Op(700, Type.XFX)),
        Map.entry("==", new Op(700, Type.XFX)),
        Map.entry("\\==", new Op(700, Type.XFX)),
        Map.entry("is", new Op(700, Type.XFX)),
        Map.entry("=:=", new Op(700, Type.XFX)),
        Map.entry("=\\=", new Op(700, Type.XFX)),
        Map.entry("<", new Op(700, Type.XFX)),
        Map.entry(">", new Op(700, Type.XFX)),
        Map.entry("=<", new Op(700, Type.XFX)),
        Map.entry(">=", new Op(700, Type.XFX)),
        Map.entry("+", new Op(500, Type.YFX)),
        Map.entry("-", new Op(500, Type.YFX)),
        Map.entry("*", new Op(400, Type.YFX)),
        Map.entry("//", new Op(400, Type.YFX)),
        Map.entry("mod", new Op(400, Type.YFX)),
        Map.entry("rem", new Op(400, Type.YFX)));

    /** Prefix operators: name to priority (all {@code fy}). */
    static final Map<String, Integer> PREFIX = Map.of("-", 200, "\\+", 900);

    static boolean isSymbolChar(char c) {
        return "+-*/\\^<>=~:.?@#&$".indexOf(c) >= 0;
    }

    private Ops() {}
}
