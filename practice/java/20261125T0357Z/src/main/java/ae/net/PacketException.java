package ae.net;

/** Thrown when bytes do not form a valid packet or segment. */
public class PacketException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PacketException(String message) {
        super(message);
    }
}
