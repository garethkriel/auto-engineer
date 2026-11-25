package ae.rv;

/** Thrown by {@link Codec#decode(int)} for words that are not a supported canonical instruction. */
public class IllegalInstructionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public IllegalInstructionException(int word) {
        super(String.format("illegal instruction 0x%08x", word));
    }
}
