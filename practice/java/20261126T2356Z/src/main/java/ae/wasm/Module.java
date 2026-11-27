package ae.wasm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A decoded and validated module. Obtain one from {@link ModuleParser#parse(byte[])}. */
public final class Module {
    /** An export entry: kind 0 = function, 2 = memory, 3 = global. */
    record Export(int kind, int index) {
    }

    /** An active data segment (offset is an unsigned 32-bit value). */
    record DataSegment(long offset, byte[] bytes) {
    }

    final List<FuncType> types = new ArrayList<>();
    final List<Integer> funcTypes = new ArrayList<>();
    boolean hasMemory;
    int memMin;
    long memMax = -1; // -1 = no declared maximum
    final List<ValType> globalTypes = new ArrayList<>();
    final List<Boolean> globalMutable = new ArrayList<>();
    final List<Long> globalInit = new ArrayList<>();
    final Map<String, Export> exports = new LinkedHashMap<>();
    int start = -1;
    final List<DataSegment> data = new ArrayList<>();
    Compiled[] compiled = new Compiled[0];

    Module() {
    }

    /** Number of defined functions. */
    public int functionCount() {
        return funcTypes.size();
    }

    /** Names of exported functions in declaration order. */
    public List<String> exportedFunctions() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Export> e : exports.entrySet()) {
            if (e.getValue().kind() == 0) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** Parameter count of an exported function. */
    public int exportArity(String name) {
        Export e = exports.get(name);
        if (e == null || e.kind() != 0) {
            throw new IllegalArgumentException("no exported function " + name);
        }
        return types.get(funcTypes.get(e.index())).params().size();
    }

    FuncType funcType(int funcIndex) {
        return types.get(funcTypes.get(funcIndex));
    }
}
