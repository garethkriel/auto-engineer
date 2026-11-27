package ae.wasm;

/** Supported value types. */
enum ValType {
    I32(0x7F), I64(0x7E);

    final int code;

    ValType(int code) {
        this.code = code;
    }
}
