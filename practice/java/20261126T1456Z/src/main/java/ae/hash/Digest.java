package ae.hash;

/** A streaming message digest. Instances are not thread-safe. */
public interface Digest {
    /** Output length of {@link #digest()} in bytes. */
    int digestLength();

    /** Internal block size in bytes (the sponge rate for Keccak); HMAC pads keys to this length. */
    int blockLength();

    /** Absorbs {@code len} bytes of {@code data} starting at {@code off}. */
    void update(byte[] data, int off, int len);

    /** Absorbs all of {@code data}. */
    default void update(byte[] data) {
        update(data, 0, data.length);
    }

    /** Finishes the hash, returns it and resets the instance to its initial state. */
    byte[] digest();

    /** Discards absorbed input. */
    void reset();
}
