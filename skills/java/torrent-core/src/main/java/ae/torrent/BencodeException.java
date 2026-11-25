package ae.torrent;

/** Thrown for malformed bencode or invalid metainfo. */
public class BencodeException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public BencodeException(String message) {
        super(message);
    }
}
