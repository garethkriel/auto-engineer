package ae.rangecoder;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.zip.CRC32;

/** Byte-array compressor built on the range coder, with five selectable context-model engines. */
public final class Compressor {
    /** Size of the container header: magic(2) + engine(1) + length(4) + crc32(4). */
    public static final int HEADER_SIZE = 11;
    /** A container may not claim more than this many output bytes per coder byte. */
    public static final int MAX_RATIO = 16384;
    static final int MAX_LENGTH = 1 << 28;
    /** Order-2 contexts beyond this many share the order-1 model of their last byte. */
    static final int MAX_CONTEXTS = 16384;

    /** Modelling strategy; the ordinal is stored in the container. */
    public enum Engine { FREQ0, FREQ1, FREQ2, BIT0, BIT1 }

    private Compressor() { }

    /** Compresses with one engine. */
    public static byte[] compress(byte[] in, Engine engine) {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(engine, "engine");
        RangeEncoder enc = new RangeEncoder();
        SymbolCoder coder = coderFor(engine);
        int p1 = 0;
        int p2 = 0;
        for (byte b : in) {
            int sym = b & 0xFF;
            coder.encode(enc, sym, p1, p2);
            p2 = p1;
            p1 = sym;
        }
        byte[] body = enc.finish();
        CRC32 crc = new CRC32();
        crc.update(in);
        long c = crc.getValue();
        byte[] out = new byte[HEADER_SIZE + body.length];
        out[0] = 'R';
        out[1] = 'C';
        out[2] = (byte) engine.ordinal();
        putInt(out, 3, in.length);
        putInt(out, 7, (int) c);
        System.arraycopy(body, 0, out, HEADER_SIZE, body.length);
        return out;
    }

    /** Tries every engine and returns the smallest container (ties go to the lowest ordinal). */
    public static byte[] compressBest(byte[] in) {
        byte[] best = null;
        for (Engine e : Engine.values()) {
            byte[] c = compress(in, e);
            if (best == null || c.length < best.length) best = c;
        }
        return best;
    }

    /**
     * Restores the original bytes.
     *
     * @throws CodecException for any malformed, truncated or corrupted container
     */
    public static byte[] decompress(byte[] data) {
        Objects.requireNonNull(data, "data");
        if (data.length < HEADER_SIZE) throw new CodecException("truncated header");
        if (data[0] != 'R' || data[1] != 'C') throw new CodecException("bad magic");
        int e = data[2];
        if (e < 0 || e >= Engine.values().length) throw new CodecException("unknown engine");
        int length = getInt(data, 3);
        long coderBytes = data.length - HEADER_SIZE;
        if (length < 0 || length > MAX_LENGTH || length > MAX_RATIO * (coderBytes + 1)) {
            throw new CodecException("length too large");
        }
        RangeDecoder dec = new RangeDecoder(data, HEADER_SIZE);
        SymbolCoder coder = coderFor(Engine.values()[e]);
        byte[] out = new byte[length];
        int p1 = 0;
        int p2 = 0;
        for (int i = 0; i < length; i++) {
            int sym = coder.decode(dec, p1, p2);
            out[i] = (byte) sym;
            p2 = p1;
            p1 = sym;
        }
        CRC32 crc = new CRC32();
        crc.update(out);
        if ((int) crc.getValue() != getInt(data, 7)) throw new CodecException("crc mismatch");
        return out;
    }

    /** Test hook: how many distinct order-2 contexts get their own model when compressing {@code in} with FREQ2. */
    static int order2ContextCount(byte[] in) {
        RangeEncoder enc = new RangeEncoder();
        FreqCoder coder = new FreqCoder(2);
        int p1 = 0;
        int p2 = 0;
        for (byte b : in) {
            coder.encode(enc, b & 0xFF, p1, p2);
            p2 = p1;
            p1 = b & 0xFF;
        }
        return coder.byPair.size();
    }

    private static void putInt(byte[] b, int at, int v) {
        b[at] = (byte) (v >>> 24);
        b[at + 1] = (byte) (v >>> 16);
        b[at + 2] = (byte) (v >>> 8);
        b[at + 3] = (byte) v;
    }

    private static int getInt(byte[] b, int at) {
        return (b[at] & 0xFF) << 24 | (b[at + 1] & 0xFF) << 16 | (b[at + 2] & 0xFF) << 8 | (b[at + 3] & 0xFF);
    }

    private static SymbolCoder coderFor(Engine e) {
        return switch (e) {
            case FREQ0 -> new FreqCoder(0);
            case FREQ1 -> new FreqCoder(1);
            case FREQ2 -> new FreqCoder(2);
            case BIT0 -> new BitTreeCoder(false);
            case BIT1 -> new BitTreeCoder(true);
        };
    }

    /** Encodes/decodes one byte given the two previous bytes; stateful (models adapt). */
    private interface SymbolCoder {
        void encode(RangeEncoder enc, int sym, int p1, int p2);

        int decode(RangeDecoder dec, int p1, int p2);
    }

    private static final class FreqCoder implements SymbolCoder {
        private final int order;
        private final FenwickModel single;
        private final FenwickModel[] byPrev = new FenwickModel[256];
        private final Map<Integer, FenwickModel> byPair = new HashMap<>();

        FreqCoder(int order) {
            this.order = order;
            this.single = order == 0 ? new FenwickModel(256) : null;
        }

        private FenwickModel model(int p1, int p2) {
            if (order == 0) return single;
            if (order == 1) return prev(p1);
            Integer key = (p2 << 8) | p1;
            FenwickModel m = byPair.get(key);
            if (m == null) {
                if (byPair.size() >= MAX_CONTEXTS) return prev(p1);
                m = new FenwickModel(256);
                byPair.put(key, m);
            }
            return m;
        }

        private FenwickModel prev(int p1) {
            FenwickModel m = byPrev[p1];
            if (m == null) {
                m = new FenwickModel(256);
                byPrev[p1] = m;
            }
            return m;
        }

        @Override
        public void encode(RangeEncoder enc, int sym, int p1, int p2) {
            FenwickModel m = model(p1, p2);
            enc.encode(m.cumFreq(sym), m.freq(sym), m.total());
            m.update(sym);
        }

        @Override
        public int decode(RangeDecoder dec, int p1, int p2) {
            FenwickModel m = model(p1, p2);
            int sym = m.findSymbol(dec.target(m.total()));
            dec.consume(m.cumFreq(sym), m.freq(sym), m.total());
            m.update(sym);
            return sym;
        }
    }

    private static final class BitTreeCoder implements SymbolCoder {
        private final BitModel[][] trees;
        private final boolean useContext;

        BitTreeCoder(boolean useContext) {
            this.useContext = useContext;
            this.trees = new BitModel[useContext ? 256 : 1][];
        }

        private BitModel[] tree(int p1) {
            int idx = useContext ? p1 : 0;
            BitModel[] t = trees[idx];
            if (t == null) {
                t = new BitModel[256];
                for (int i = 1; i < 256; i++) t[i] = new BitModel();
                trees[idx] = t;
            }
            return t;
        }

        @Override
        public void encode(RangeEncoder enc, int sym, int p1, int p2) {
            BitModel[] t = tree(p1);
            int node = 1;
            for (int i = 7; i >= 0; i--) {
                int bit = (sym >>> i) & 1;
                enc.encodeBit(t[node], bit);
                node = (node << 1) | bit;
            }
        }

        @Override
        public int decode(RangeDecoder dec, int p1, int p2) {
            BitModel[] t = tree(p1);
            int node = 1;
            for (int i = 0; i < 8; i++) node = (node << 1) | dec.decodeBit(t[node]);
            return node & 0xFF;
        }
    }
}
