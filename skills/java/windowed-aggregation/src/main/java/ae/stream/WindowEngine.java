package ae.stream;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.zip.CRC32;

/**
 * Event-time windowed aggregation with a bounded-out-of-orderness watermark, allowed lateness,
 * tumbling / sliding / session windows and CRC-protected checkpoints. See TASK.md for the semantics.
 * Not thread-safe.
 */
public final class WindowEngine {
    static final int MAGIC = 0x57494E44; // "WIND"
    static final int VERSION = 1;
    /** magic (4) + version (1). */
    static final int HEADER_SIZE = 5;
    /** CRC32 stored as 8 bytes. */
    static final int FOOTER_SIZE = 8;

    private static final byte TUMBLING = 1;
    private static final byte SLIDING = 2;
    private static final byte SESSION = 3;

    private final WindowSpec spec;
    private final long maxOutOfOrder;
    private final long allowedLateness;
    private boolean hasMax;
    private long maxTime;
    private long watermark = Long.MIN_VALUE;
    private final TreeMap<String, TreeMap<Window, Agg>> state = new TreeMap<>();
    private final List<Dropped> dropped = new ArrayList<>();

    public WindowEngine(WindowSpec spec, long maxOutOfOrder, long allowedLateness) {
        this.spec = Objects.requireNonNull(spec, "spec");
        if (maxOutOfOrder < 0 || allowedLateness < 0) {
            throw new IllegalArgumentException("maxOutOfOrder and allowedLateness must be >= 0");
        }
        this.maxOutOfOrder = maxOutOfOrder;
        this.allowedLateness = allowedLateness;
    }

    public long watermark() {
        return watermark;
    }

    /** Cumulative list of events that were too late for a window. */
    public List<Dropped> dropped() {
        return Collections.unmodifiableList(new ArrayList<>(dropped));
    }

    public int openWindowCount() {
        int n = 0;
        for (TreeMap<Window, Agg> m : state.values()) {
            n += m.size();
        }
        return n;
    }

    /** Applies one event; returns the results it caused, sorted by (end, start, key, kind). */
    public List<Result> process(Event e) {
        Objects.requireNonNull(e, "event");
        final long prevWm = watermark;

        // Phase 1: compute everything that can throw, without touching any field.
        List<Dropped> newDrops = new ArrayList<>();
        Map<Window, Agg> puts = new LinkedHashMap<>();
        List<Window> removes = new ArrayList<>();
        TreeMap<Window, Agg> mine = state.getOrDefault(e.key(), new TreeMap<>());
        if (spec instanceof WindowSpec.Session s) {
            planSession(e, s, prevWm, mine, newDrops, puts, removes);
        } else {
            for (Window w : assign(e.time())) {
                if (satAdd(w.end(), allowedLateness) <= prevWm) {
                    newDrops.add(new Dropped(e, w));
                } else {
                    Agg old = mine.get(w);
                    puts.put(w, old == null ? Agg.of(e.value()) : old.merge(Agg.of(e.value())));
                }
            }
        }
        long newMax = hasMax ? Math.max(maxTime, e.time()) : e.time();
        long newWm = Math.max(watermark, satSub(newMax, maxOutOfOrder));

        // Phase 2: apply.
        if (!puts.isEmpty()) {
            TreeMap<Window, Agg> m = state.computeIfAbsent(e.key(), k -> new TreeMap<>());
            removes.forEach(m::remove);
            m.putAll(puts);
        }
        dropped.addAll(newDrops);
        hasMax = true;
        maxTime = newMax;
        watermark = newWm;

        List<Result> out = new ArrayList<>();
        for (Map.Entry<Window, Agg> p : puts.entrySet()) {
            if (p.getKey().end() <= prevWm) {
                out.add(new Result(e.key(), p.getKey(), p.getValue(), Kind.LATE_UPDATE));
            }
        }
        fire(prevWm, out);
        purge();
        sort(out);
        return out;
    }

    /** Terminal: fires every remaining window as ON_TIME and drops all later events. */
    public List<Result> flush() {
        long prevWm = watermark;
        watermark = Long.MAX_VALUE;
        List<Result> out = new ArrayList<>();
        fire(prevWm, out);
        purge();
        sort(out);
        return out;
    }

    private void planSession(Event e, WindowSpec.Session s, long prevWm, TreeMap<Window, Agg> mine,
                             List<Dropped> newDrops, Map<Window, Agg> puts, List<Window> removes) {
        Window proto = new Window(e.time(), Math.addExact(e.time(), s.gap()));
        if (satAdd(proto.end(), allowedLateness) <= prevWm) {
            newDrops.add(new Dropped(e, proto));
            return;
        }
        long start = proto.start();
        long end = proto.end();
        Agg agg = Agg.of(e.value());
        for (Map.Entry<Window, Agg> w : mine.entrySet()) {
            Window x = w.getKey();
            if (x.start() <= proto.end() && proto.start() <= x.end()) {
                removes.add(x);
                start = Math.min(start, x.start());
                end = Math.max(end, x.end());
                agg = agg.merge(w.getValue());
            }
        }
        puts.put(new Window(start, end), agg);
    }

    private List<Window> assign(long t) {
        switch (spec) {
            case WindowSpec.Tumbling tm -> {
                long s = Math.subtractExact(t, Math.floorMod(t, tm.size()));
                return List.of(new Window(s, Math.addExact(s, tm.size())));
            }
            case WindowSpec.Sliding sl -> {
                List<Window> ws = new ArrayList<>();
                long s = Math.subtractExact(t, Math.floorMod(t, sl.slide()));
                while (true) {
                    long end = Math.addExact(s, sl.size());
                    if (end <= t) {
                        break;
                    }
                    ws.add(new Window(s, end));
                    s = Math.subtractExact(s, sl.slide());
                }
                Collections.reverse(ws); // ascending start, so per-event drop order is deterministic
                return ws;
            }
            case WindowSpec.Session ignored -> throw new IllegalStateException("sessions are planned separately");
        }
    }

    /** Emits ON_TIME for every window whose end lies in (prevWm, watermark]. */
    private void fire(long prevWm, List<Result> out) {
        for (Map.Entry<String, TreeMap<Window, Agg>> k : state.entrySet()) {
            for (Map.Entry<Window, Agg> w : k.getValue().entrySet()) {
                long end = w.getKey().end();
                if (end > prevWm && end <= watermark) {
                    out.add(new Result(k.getKey(), w.getKey(), w.getValue(), Kind.ON_TIME));
                }
            }
        }
    }

    private void purge() {
        state.values().forEach(m -> m.keySet().removeIf(w -> satAdd(w.end(), allowedLateness) <= watermark));
        state.values().removeIf(TreeMap::isEmpty);
    }

    private static void sort(List<Result> out) {
        out.sort((a, b) -> {
            int c = Long.compare(a.window().end(), b.window().end());
            if (c == 0) {
                c = Long.compare(a.window().start(), b.window().start());
            }
            if (c == 0) {
                c = a.key().compareTo(b.key());
            }
            return c != 0 ? c : a.kind().compareTo(b.kind());
        });
    }

    private static long satAdd(long a, long nonNegative) {
        return a > Long.MAX_VALUE - nonNegative ? Long.MAX_VALUE : a + nonNegative;
    }

    private static long satSub(long a, long nonNegative) {
        return a < Long.MIN_VALUE + nonNegative ? Long.MIN_VALUE : a - nonNegative;
    }

    // ---- invariants -------------------------------------------------------------------------

    /** Returns a description of the first violated invariant, or null. */
    private String problem() {
        if (hasMax) {
            if (watermark != Long.MAX_VALUE && watermark != satSub(maxTime, maxOutOfOrder)) {
                return "watermark inconsistent with max time";
            }
        } else if (watermark != Long.MIN_VALUE && watermark != Long.MAX_VALUE) {
            return "watermark set without events";
        }
        for (Map.Entry<String, TreeMap<Window, Agg>> k : state.entrySet()) {
            if (k.getValue().isEmpty()) {
                return "empty window map for " + k.getKey();
            }
            Window prev = null;
            for (Map.Entry<Window, Agg> w : k.getValue().entrySet()) {
                Window x = w.getKey();
                Agg a = w.getValue();
                if (a.count() < 1 || a.min() > a.max()) {
                    return "bad aggregate in " + x;
                }
                if (satAdd(x.end(), allowedLateness) <= watermark) {
                    return "window " + x + " should have been purged";
                }
                String shape = shapeProblem(x);
                if (shape != null) {
                    return shape;
                }
                if (spec instanceof WindowSpec.Session && prev != null && x.start() <= prev.end()) {
                    return "sessions " + prev + " and " + x + " should have merged";
                }
                prev = x;
            }
        }
        return null;
    }

    private String shapeProblem(Window x) {
        switch (spec) {
            case WindowSpec.Tumbling tm -> {
                if (x.end() - x.start() != tm.size() || Math.floorMod(x.start(), tm.size()) != 0) {
                    return "misaligned tumbling window " + x;
                }
            }
            case WindowSpec.Sliding sl -> {
                if (x.end() - x.start() != sl.size() || Math.floorMod(x.start(), sl.slide()) != 0) {
                    return "misaligned sliding window " + x;
                }
            }
            case WindowSpec.Session s -> {
                if (x.end() - x.start() < s.gap()) {
                    return "session shorter than gap " + x;
                }
            }
        }
        return null;
    }

    /** Test hook: throws AssertionError when internal state is inconsistent. */
    void checkInvariants() {
        String p = problem();
        if (p != null) {
            throw new AssertionError(p);
        }
    }

    // ---- snapshot / restore -----------------------------------------------------------------

    /** Canonical checkpoint bytes: header, body, CRC32 footer. Equal state gives equal bytes. */
    public byte[] snapshot() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeByte(VERSION);
            switch (spec) {
                case WindowSpec.Tumbling tm -> {
                    out.writeByte(TUMBLING);
                    out.writeLong(tm.size());
                }
                case WindowSpec.Sliding sl -> {
                    out.writeByte(SLIDING);
                    out.writeLong(sl.size());
                    out.writeLong(sl.slide());
                }
                case WindowSpec.Session s -> {
                    out.writeByte(SESSION);
                    out.writeLong(s.gap());
                }
            }
            out.writeLong(maxOutOfOrder);
            out.writeLong(allowedLateness);
            out.writeBoolean(hasMax);
            out.writeLong(maxTime);
            out.writeLong(watermark);
            out.writeInt(state.size());
            for (Map.Entry<String, TreeMap<Window, Agg>> k : state.entrySet()) {
                writeString(out, k.getKey());
                out.writeInt(k.getValue().size());
                for (Map.Entry<Window, Agg> w : k.getValue().entrySet()) {
                    out.writeLong(w.getKey().start());
                    out.writeLong(w.getKey().end());
                    out.writeLong(w.getValue().count());
                    out.writeLong(w.getValue().sum());
                    out.writeLong(w.getValue().min());
                    out.writeLong(w.getValue().max());
                }
            }
            out.writeInt(dropped.size());
            for (Dropped d : dropped) {
                writeString(out, d.event().key());
                out.writeLong(d.event().time());
                out.writeLong(d.event().value());
                out.writeLong(d.window().start());
                out.writeLong(d.window().end());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        byte[] body = bytes.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(body, 0, body.length);
        byte[] image = new byte[body.length + FOOTER_SIZE];
        System.arraycopy(body, 0, image, 0, body.length);
        ByteBuffer.wrap(image).putLong(body.length, crc.getValue());
        return image;
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    /** Rebuilds an engine from {@link #snapshot()} bytes; rejects anything corrupt. */
    public static WindowEngine restore(byte[] image) {
        Objects.requireNonNull(image, "image");
        if (image.length < HEADER_SIZE + FOOTER_SIZE) {
            throw new CorruptSnapshotException("too short: " + image.length);
        }
        int bodyLen = image.length - FOOTER_SIZE;
        CRC32 crc = new CRC32();
        crc.update(image, 0, bodyLen);
        if (ByteBuffer.wrap(image).getLong(bodyLen) != crc.getValue()) {
            throw new CorruptSnapshotException("checksum mismatch");
        }
        try {
            ByteBuffer in = ByteBuffer.wrap(image, 0, bodyLen);
            if (in.getInt() != MAGIC) {
                throw new CorruptSnapshotException("bad magic");
            }
            if (in.get() != VERSION) {
                throw new CorruptSnapshotException("unsupported version");
            }
            WindowSpec spec = switch (in.get()) {
                case TUMBLING -> new WindowSpec.Tumbling(in.getLong());
                case SLIDING -> new WindowSpec.Sliding(in.getLong(), in.getLong());
                case SESSION -> new WindowSpec.Session(in.getLong());
                default -> throw new CorruptSnapshotException("unknown window type");
            };
            WindowEngine w = new WindowEngine(spec, in.getLong(), in.getLong());
            byte flag = in.get();
            if (flag != 0 && flag != 1) {
                throw new CorruptSnapshotException("bad flag");
            }
            w.hasMax = flag == 1;
            w.maxTime = in.getLong();
            w.watermark = in.getLong();
            int keys = count(in, 8);
            for (int i = 0; i < keys; i++) {
                String key = readString(in);
                int n = count(in, 48);
                TreeMap<Window, Agg> m = new TreeMap<>();
                for (int j = 0; j < n; j++) {
                    Window win = new Window(in.getLong(), in.getLong());
                    Agg agg = new Agg(in.getLong(), in.getLong(), in.getLong(), in.getLong());
                    if (m.put(win, agg) != null) {
                        throw new CorruptSnapshotException("duplicate window");
                    }
                }
                if (w.state.put(key, m) != null) {
                    throw new CorruptSnapshotException("duplicate key");
                }
            }
            int drops = count(in, 36);
            for (int i = 0; i < drops; i++) {
                Event ev = new Event(readString(in), in.getLong(), in.getLong());
                w.dropped.add(new Dropped(ev, new Window(in.getLong(), in.getLong())));
            }
            if (in.hasRemaining()) {
                throw new CorruptSnapshotException("trailing bytes");
            }
            String problem = w.problem();
            if (problem != null) {
                throw new CorruptSnapshotException(problem);
            }
            return w;
        } catch (BufferUnderflowException | IllegalArgumentException e) {
            throw new CorruptSnapshotException("malformed snapshot: " + e.getMessage());
        }
    }

    /** Reads an element count and rejects values that cannot fit in the remaining bytes. */
    private static int count(ByteBuffer in, int minElementSize) {
        int n = in.getInt();
        if (n < 0 || n > in.remaining() / minElementSize) {
            throw new CorruptSnapshotException("implausible count " + n);
        }
        return n;
    }

    private static String readString(ByteBuffer in) {
        int len = in.getInt();
        if (len < 0 || len > in.remaining()) {
            throw new CorruptSnapshotException("implausible string length " + len);
        }
        byte[] b = new byte[len];
        in.get(b);
        return new String(b, StandardCharsets.UTF_8);
    }
}
