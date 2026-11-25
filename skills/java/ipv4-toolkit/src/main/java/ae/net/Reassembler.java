package ae.net;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stateful IPv4 reassembly. Time is passed in by the caller (milliseconds) so behaviour is deterministic.
 * Overlapping bytes: the first arrival wins. Any malformed fragment discards the whole datagram's state.
 */
public final class Reassembler {
    /** Largest reassembled payload: 65535 minus a 20-byte header. */
    public static final int MAX_PAYLOAD = Ipv4Packet.MAX_TOTAL_LENGTH - 20;

    /** Counters of discarded datagrams. */
    public record Stats(long malformed, long expired, long evicted) {
    }

    private record Key(int src, int dst, int protocol, int id) {
    }

    private static final class Entry {
        final long firstArrival;
        final long seq;
        byte[] buf = new byte[64];
        final BitSet have = new BitSet();
        int maxEnd;
        int lastEnd = -1;
        Ipv4Packet head;

        Entry(long firstArrival, long seq) {
            this.firstArrival = firstArrival;
            this.seq = seq;
        }
    }

    private final long timeoutMillis;
    private final int maxDatagrams;
    private final Map<Key, Entry> entries = new LinkedHashMap<>();
    private long seq;
    private long malformed;
    private long expired;
    private long evicted;

    public Reassembler(long timeoutMillis, int maxDatagrams) {
        if (timeoutMillis <= 0 || maxDatagrams <= 0) {
            throw new IllegalArgumentException("timeout and capacity must be positive");
        }
        this.timeoutMillis = timeoutMillis;
        this.maxDatagrams = maxDatagrams;
    }

    public int pending() {
        return entries.size();
    }

    public Stats stats() {
        return new Stats(malformed, expired, evicted);
    }

    public Optional<Ipv4Packet> accept(Ipv4Packet p, long nowMillis) {
        if (!p.mf() && p.fragOffset() == 0) {
            return Optional.of(p);
        }
        expire(nowMillis);
        Key key = new Key(p.src(), p.dst(), p.protocol(), p.id());
        Entry e = entries.get(key);
        int start = p.fragOffset() * 8;
        int len = p.payloadLength();
        int end = start + len;
        boolean bad = len == 0 || end > MAX_PAYLOAD || (p.mf() && len % 8 != 0);
        if (!bad && e != null && e.lastEnd >= 0) {
            bad = p.mf() ? end > e.lastEnd : end != e.lastEnd;
        }
        if (!bad && e != null && !p.mf()) {
            bad = e.maxEnd > end;
        }
        if (bad) {
            malformed++;
            entries.remove(key);
            return Optional.empty();
        }
        if (e == null) {
            if (entries.size() >= maxDatagrams) {
                evictOldest();
            }
            e = new Entry(nowMillis, seq++);
            entries.put(key, e);
        }
        store(e, p, start, end);
        if (e.lastEnd >= 0 && e.have.nextClearBit(0) >= e.lastEnd) {
            entries.remove(key);
            return Optional.of(new Ipv4Packet(e.head.tos(), e.head.id(), e.head.df(), false, 0, e.head.ttl(),
                    e.head.protocol(), e.head.src(), e.head.dst(), e.head.options(),
                    java.util.Arrays.copyOf(e.buf, e.lastEnd)));
        }
        return Optional.empty();
    }

    private static void store(Entry e, Ipv4Packet p, int start, int end) {
        if (end > e.buf.length) {
            e.buf = java.util.Arrays.copyOf(e.buf, Math.max(end, e.buf.length * 2));
        }
        byte[] data = p.payload();
        for (int i = start; i < end; i++) {
            if (!e.have.get(i)) {
                e.have.set(i);
                e.buf[i] = data[i - start];
            }
        }
        e.maxEnd = Math.max(e.maxEnd, end);
        if (!p.mf()) {
            e.lastEnd = end;
        }
        if (start == 0 && e.head == null) {
            e.head = p;
        }
    }

    private void expire(long now) {
        List<Key> dead = new ArrayList<>();
        for (Map.Entry<Key, Entry> me : entries.entrySet()) {
            if (now - me.getValue().firstArrival >= timeoutMillis) {
                dead.add(me.getKey());
            }
        }
        for (Key k : dead) {
            entries.remove(k);
            expired++;
        }
    }

    private void evictOldest() {
        Key oldest = null;
        Entry best = null;
        for (Map.Entry<Key, Entry> me : entries.entrySet()) {
            Entry c = me.getValue();
            if (best == null || c.firstArrival < best.firstArrival
                    || (c.firstArrival == best.firstArrival && c.seq < best.seq)) {
                best = c;
                oldest = me.getKey();
            }
        }
        entries.remove(oldest);
        evicted++;
    }
}
