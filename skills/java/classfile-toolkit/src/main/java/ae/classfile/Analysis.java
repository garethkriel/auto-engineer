package ae.classfile;

/**
 * Result of {@link CodeAnalyzer#analyze}.
 *
 * @param maxStack       maximum operand-stack depth in slots (long and double count 2)
 * @param localsRequired locals needed by the arguments and by every reachable load/store
 * @param reachable      number of instructions reachable from the entry point and handlers
 */
public record Analysis(int maxStack, int localsRequired, int reachable) {
}
