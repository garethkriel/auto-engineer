package ae.rv;

/** A machine trap: misaligned or out-of-range access, illegal instruction, unknown ecall. */
public class Trap extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public Trap(String message) {
        super(message);
    }
}
