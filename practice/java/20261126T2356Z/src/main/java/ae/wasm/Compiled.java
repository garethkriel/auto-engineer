package ae.wasm;

/** A validated function lowered to a flat int array with resolved branch targets. */
record Compiled(int[] code, int maxStack, int nParams, int nResults, int nLocals) {
}
