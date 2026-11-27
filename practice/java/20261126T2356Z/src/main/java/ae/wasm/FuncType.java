package ae.wasm;

import java.util.List;

/** A function type with at most one result (the result is {@code null} when absent). */
record FuncType(List<ValType> params, ValType result) {
}
