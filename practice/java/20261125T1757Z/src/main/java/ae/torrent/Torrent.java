package ae.torrent;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

/** Single-file torrent metainfo: name, piece length, total length and SHA-1 piece hashes. */
public final class Torrent {
    private static final int HASH = 20;

    private final BDict info;
    private final String name;
    private final int pieceLength;
    private final long length;
    private final byte[] hashes;
    private final byte[] infoHash;

    private Torrent(BDict info, String name, int pieceLength, long length, byte[] hashes) {
        this.info = info;
        this.name = name;
        this.pieceLength = pieceLength;
        this.length = length;
        this.hashes = hashes;
        this.infoHash = sha1(Bencode.encode(info));
    }

    public static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Builds metainfo for {@code data} split into pieces of {@code pieceLength} bytes. */
    public static Torrent create(String name, byte[] data, int pieceLength) {
        Objects.requireNonNull(name);
        Objects.requireNonNull(data);
        if (name.isEmpty()) {
            throw new IllegalArgumentException("empty name");
        }
        if (pieceLength <= 0) {
            throw new IllegalArgumentException("pieceLength must be positive");
        }
        int count = (data.length + pieceLength - 1) / pieceLength;
        byte[] hashes = new byte[count * HASH];
        for (int p = 0; p < count; p++) {
            int from = p * pieceLength;
            int to = Math.min(data.length, from + pieceLength);
            System.arraycopy(sha1(Arrays.copyOfRange(data, from, to)), 0, hashes, p * HASH, HASH);
        }
        BDict info = infoDict(name, pieceLength, data.length, hashes);
        return new Torrent(info, name, pieceLength, data.length, hashes);
    }

    private static BDict infoDict(String name, long pieceLength, long length, byte[] hashes) {
        return BDict.builder()
                .put("name", BStr.of(name))
                .put("piece length", new BInt(pieceLength))
                .put("length", new BInt(length))
                .put("pieces", new BStr(hashes))
                .build();
    }

    /** Parses and validates metainfo bytes: {@code d4:info d...e e}. */
    public static Torrent parse(byte[] bytes) {
        if (!(Bencode.decode(bytes) instanceof BDict outer)) {
            throw new BencodeException("metainfo is not a dictionary");
        }
        if (!(outer.get("info") instanceof BDict info)) {
            throw new BencodeException("missing info dictionary");
        }
        String name = requireStr(info, "name").utf8();
        long pl = requireInt(info, "piece length");
        long length = requireInt(info, "length");
        byte[] hashes = requireStr(info, "pieces").bytes();
        if (name.isEmpty()) {
            throw new BencodeException("empty name");
        }
        if (pl <= 0 || pl > Integer.MAX_VALUE) {
            throw new BencodeException("bad piece length " + pl);
        }
        if (length < 0) {
            throw new BencodeException("negative length");
        }
        if (hashes.length % HASH != 0) {
            throw new BencodeException("pieces is not a multiple of 20 bytes");
        }
        long expected = length / pl + (length % pl == 0 ? 0 : 1);
        if (hashes.length / HASH != expected) {
            throw new BencodeException("expected " + expected + " piece hashes, found " + hashes.length / HASH);
        }
        return new Torrent(info, name, (int) pl, length, hashes);
    }

    private static BStr requireStr(BDict d, String key) {
        if (d.get(key) instanceof BStr s) {
            return s;
        }
        throw new BencodeException("missing or non-string '" + key + "'");
    }

    private static long requireInt(BDict d, String key) {
        if (d.get(key) instanceof BInt i) {
            return i.value();
        }
        throw new BencodeException("missing or non-integer '" + key + "'");
    }

    public byte[] toBytes() {
        return Bencode.encode(BDict.builder().put("info", info).build());
    }

    public String name() {
        return name;
    }

    public int pieceLength() {
        return pieceLength;
    }

    public long length() {
        return length;
    }

    public int pieceCount() {
        return hashes.length / HASH;
    }

    public byte[] infoHash() {
        return infoHash.clone();
    }

    private void checkIndex(int piece) {
        if (piece < 0 || piece >= pieceCount()) {
            throw new IllegalArgumentException("piece " + piece + " out of range");
        }
    }

    /** Size of a piece; only the last one may be shorter than the piece length. */
    public int pieceSize(int piece) {
        checkIndex(piece);
        if (piece < pieceCount() - 1) {
            return pieceLength;
        }
        return (int) (length - (long) (pieceCount() - 1) * pieceLength);
    }

    public boolean verifyPiece(int piece, byte[] data) {
        checkIndex(piece);
        if (data.length != pieceSize(piece)) {
            return false;
        }
        byte[] want = Arrays.copyOfRange(hashes, piece * HASH, piece * HASH + HASH);
        return MessageDigest.isEqual(want, sha1(data));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Torrent t && info.equals(t.info);
    }

    @Override
    public int hashCode() {
        return info.hashCode();
    }

    @Override
    public String toString() {
        return "Torrent[" + name + ", " + length + " bytes, " + pieceCount() + " pieces, "
                + java.util.HexFormat.of().formatHex(infoHash) + "]";
    }
}
