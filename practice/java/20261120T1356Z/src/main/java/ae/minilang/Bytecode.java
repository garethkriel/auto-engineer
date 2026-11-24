package ae.minilang;

import java.util.List;

/** A compiled program: function 0 is main, the rest follow in declaration order. */
record Bytecode(List<Function> functions) {
    /** One compiled function: {@code arity} parameters occupy slots 0..arity-1 of {@code nLocals}. */
    record Function(String name, int arity, int nLocals, long[] code) { }

    int size() {
        int n = 0;
        for (Function f : functions) {
            n += f.code().length;
        }
        return n;
    }
}
