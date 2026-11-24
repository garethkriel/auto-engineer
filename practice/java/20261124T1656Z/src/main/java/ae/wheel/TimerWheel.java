package ae.wheel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.PriorityQueue;

/**
 * Single-threaded hierarchical timing wheel driven by virtual time.
 *
 * <p>Layout: 4 levels of 64 slots (6 bits each). A timer with deadline {@code d} scheduled at time
 * {@code now} lives at level {@code L} = index of the highest 6-bit group in which {@code d} and
 * {@code now} differ, in slot {@code (d >> 6L) & 63}. If {@code d ^ now >= 2^24} it waits in an overflow
 * heap until {@code d >> 24 == now >> 24}. Per-level occupancy bitmasks let {@link #advanceTo} jump
 * straight to the next event (a level-0 slot to fire, a higher-level slot to cascade, or an overflow
 * pull) instead of walking ticks.
 *
 * <p>Firing order is {@code (deadline, schedule sequence)}. {@link #now()} equals the deadline while a
 * callback runs. Callbacks may schedule and cancel timers but must not call {@link #advanceTo}.
 */
public final class TimerWheel {
    private static final int BITS = 6;
    private static final int SLOTS = 1 << BITS;
    private static final long SLOT_MASK = SLOTS - 1;
    private static final int LEVELS = 4;
    private static final int OVERFLOW_SHIFT = BITS * LEVELS;
    private static final int OVERFLOW = -1;
    private static final int UNLINKED = -2;

    private static final int PENDING = 0;
    private static final int QUEUED = 1;
    private static final int RUNNING = 2;
    private static final int DONE = 3;

    /** Thrown by {@link #advanceTo} after the run if callbacks threw; first failure is the cause. */
    public static final class TimerFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        TimerFailure(Throwable first) {
            super("timer callback failed: " + first, first);
        }
    }

    /** A scheduled timer. */
    public static final class Handle {
        private final TimerWheel wheel;
        private final Runnable task;
        private final long period;
        private long deadline;
        private long seq;
        private int state = PENDING;
        private int level = UNLINKED;
        private int slot;
        private Handle prev;
        private Handle next;

        private Handle(TimerWheel wheel, Runnable task, long deadline, long period, long seq) {
            this.wheel = wheel;
            this.task = task;
            this.deadline = deadline;
            this.period = period;
            this.seq = seq;
        }

        /** Cancels the timer; true if this call stopped a future (or, for periodic, any further) firing. */
        public boolean cancel() {
            return wheel.cancel(this);
        }

        /** True once a one-shot fired or any timer was cancelled or ended. */
        public boolean isDone() {
            return state == DONE;
        }

        /** The (next) deadline in virtual time. */
        public long deadline() {
            return deadline;
        }
    }

    private static final Comparator<Handle> BY_ORDER = (a, b) ->
            a.deadline != b.deadline ? Long.compare(a.deadline, b.deadline) : Long.compare(a.seq, b.seq);

    private final Handle[][] heads = new Handle[LEVELS][SLOTS];
    private final long[] occupied = new long[LEVELS];
    private final PriorityQueue<Handle> overflow = new PriorityQueue<>(BY_ORDER);
    private long now;
    private long seq;
    private int size;
    private boolean advancing;

    public TimerWheel() {
        this(0);
    }

    public TimerWheel(long startTime) {
        if (startTime < 0) {
            throw new IllegalArgumentException("startTime must be >= 0");
        }
        this.now = startTime;
    }

    public long now() {
        return now;
    }

    /** Number of pending timers (not counting one whose callback is running right now). */
    public int size() {
        return size;
    }

    public Handle schedule(long delay, Runnable task) {
        Handle h = create(delay, 0, task);
        link(h);
        size++;
        return h;
    }

    /** Fixed-rate periodic timer: deadlines are {@code now+initialDelay + k*period}. */
    public Handle scheduleAtFixedRate(long initialDelay, long period, Runnable task) {
        if (period < 1) {
            throw new IllegalArgumentException("period must be >= 1");
        }
        Handle h = create(initialDelay, period, task);
        link(h);
        size++;
        return h;
    }

    private Handle create(long delay, long period, Runnable task) {
        Objects.requireNonNull(task, "task");
        if (delay < 0) {
            throw new IllegalArgumentException("delay must be >= 0");
        }
        if (delay > Long.MAX_VALUE - now) {
            throw new IllegalArgumentException("deadline overflows long");
        }
        return new Handle(this, task, now + delay, period, seq++);
    }

    /** Earliest pending deadline, if any. */
    public OptionalLong nextDeadline() {
        long best = Long.MAX_VALUE;
        boolean found = false;
        for (int level = 0; level < LEVELS; level++) {
            long mask = occupied[level];
            int cur = (int) ((now >>> (BITS * level)) & SLOT_MASK);
            mask &= level == 0 ? -1L << cur : (cur == SLOTS - 1 ? 0 : -1L << (cur + 1));
            if (mask != 0) {
                int slot = Long.numberOfTrailingZeros(mask);
                for (Handle h = heads[level][slot]; h != null; h = h.next) {
                    if (h.deadline < best || !found) {
                        best = Math.min(best, h.deadline);
                        found = true;
                    }
                }
            }
        }
        Handle top = overflow.peek();
        if (top != null) {
            best = found ? Math.min(best, top.deadline) : top.deadline;
            found = true;
        }
        return found ? OptionalLong.of(best) : OptionalLong.empty();
    }

    /**
     * Moves virtual time to {@code target}, running every timer whose deadline is {@code <= target}.
     *
     * @return the number of callbacks run
     * @throws TimerFailure after the run, if any callback threw a RuntimeException
     */
    public long advanceTo(long target) {
        if (advancing) {
            throw new IllegalStateException("advanceTo called from a timer callback");
        }
        if (target < now) {
            throw new IllegalArgumentException("cannot move time backwards: " + target + " < " + now);
        }
        advancing = true;
        List<Throwable> errors = new ArrayList<>();
        long fired = 0;
        try {
            while (true) {
                long next = nextEventTime();
                if (next < 0 || next > target) {
                    break;
                }
                if (next > now) {
                    now = next;
                    pullOverflow();
                    cascade();
                }
                fired += fireSlot(errors);
            }
            now = target;
        } finally {
            advancing = false;
        }
        if (!errors.isEmpty()) {
            TimerFailure failure = new TimerFailure(errors.get(0));
            for (int i = 1; i < errors.size(); i++) {
                failure.addSuppressed(errors.get(i));
            }
            throw failure;
        }
        return fired;
    }

    // ---- internals -------------------------------------------------------------------------

    private boolean cancel(Handle h) {
        switch (h.state) {
            case PENDING:
                unlink(h);
                h.state = DONE;
                size--;
                return true;
            case QUEUED:
                h.state = DONE;
                size--;
                return true;
            case RUNNING:
                if (h.period > 0) {
                    h.state = DONE;
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    /** Places a timer by its deadline relative to the current {@code now}. */
    private void link(Handle h) {
        long x = h.deadline ^ now;
        if ((x >>> OVERFLOW_SHIFT) != 0) {
            h.level = OVERFLOW;
            overflow.add(h);
            return;
        }
        int level = x == 0 ? 0 : (63 - Long.numberOfLeadingZeros(x)) / BITS;
        int slot = (int) ((h.deadline >>> (BITS * level)) & SLOT_MASK);
        Handle head = heads[level][slot];
        h.level = level;
        h.slot = slot;
        h.prev = null;
        h.next = head;
        if (head != null) {
            head.prev = h;
        }
        heads[level][slot] = h;
        occupied[level] |= 1L << slot;
    }

    private void unlink(Handle h) {
        if (h.level == OVERFLOW) {
            overflow.remove(h);
        } else if (h.level >= 0) {
            if (h.prev != null) {
                h.prev.next = h.next;
            } else {
                heads[h.level][h.slot] = h.next;
            }
            if (h.next != null) {
                h.next.prev = h.prev;
            }
            if (heads[h.level][h.slot] == null) {
                occupied[h.level] &= ~(1L << h.slot);
            }
        }
        h.level = UNLINKED;
        h.prev = null;
        h.next = null;
    }

    /** Time of the next event strictly needing work at or after {@code now}, or -1 if none. */
    private long nextEventTime() {
        long best = -1;
        long m0 = occupied[0] & (-1L << (int) (now & SLOT_MASK));
        if (m0 != 0) {
            best = (now & ~SLOT_MASK) | Long.numberOfTrailingZeros(m0);
        }
        for (int level = 1; level < LEVELS; level++) {
            int cur = (int) ((now >>> (BITS * level)) & SLOT_MASK);
            long m = cur == SLOTS - 1 ? 0 : occupied[level] & (-1L << (cur + 1));
            if (m != 0) {
                int shift = BITS * (level + 1);
                long t = ((now >>> shift) << shift) | ((long) Long.numberOfTrailingZeros(m) << (BITS * level));
                if (best < 0 || t < best) {
                    best = t;
                }
            }
        }
        Handle top = overflow.peek();
        if (top != null) {
            long t = (top.deadline >>> OVERFLOW_SHIFT) << OVERFLOW_SHIFT;
            if (best < 0 || t < best) {
                best = t;
            }
        }
        return best;
    }

    private void pullOverflow() {
        while (!overflow.isEmpty() && (overflow.peek().deadline >>> OVERFLOW_SHIFT) == (now >>> OVERFLOW_SHIFT)) {
            Handle h = overflow.poll();
            h.level = UNLINKED;
            link(h);
        }
    }

    /** Re-links the slot of every level whose block starts exactly at {@code now}, highest level first. */
    private void cascade() {
        for (int level = LEVELS - 1; level >= 1; level--) {
            long lowBits = (1L << (BITS * level)) - 1;
            if ((now & lowBits) != 0) {
                continue;
            }
            int slot = (int) ((now >>> (BITS * level)) & SLOT_MASK);
            Handle h = heads[level][slot];
            heads[level][slot] = null;
            occupied[level] &= ~(1L << slot);
            while (h != null) {
                Handle following = h.next;
                h.level = UNLINKED;
                h.prev = null;
                h.next = null;
                link(h);
                h = following;
            }
        }
    }

    /** Fires the level-0 slot of {@code now} until it stays empty. */
    private long fireSlot(List<Throwable> errors) {
        int idx = (int) (now & SLOT_MASK);
        long fired = 0;
        while (heads[0][idx] != null) {
            List<Handle> batch = new ArrayList<>();
            for (Handle h = heads[0][idx]; h != null; h = h.next) {
                batch.add(h);
            }
            for (Handle h : batch) {
                h.level = UNLINKED;
                h.prev = null;
                h.next = null;
                h.state = QUEUED;
            }
            heads[0][idx] = null;
            occupied[0] &= ~(1L << idx);
            batch.sort(BY_ORDER);
            for (Handle h : batch) {
                if (h.state != QUEUED) {
                    continue; // cancelled by an earlier callback of this batch
                }
                h.state = RUNNING;
                size--;
                try {
                    h.task.run();
                } catch (RuntimeException e) {
                    errors.add(e);
                }
                fired++;
                rearmOrFinish(h);
            }
        }
        return fired;
    }

    private void rearmOrFinish(Handle h) {
        if (h.state != RUNNING) {
            return;
        }
        long next = h.period > 0 ? h.deadline + h.period : -1;
        if (next < 0) { // one-shot, or periodic deadline overflowed
            h.state = DONE;
            return;
        }
        h.deadline = next;
        h.seq = seq++;
        h.state = PENDING;
        size++;
        link(h);
    }

    // ---- test support ----------------------------------------------------------------------

    /** Throws IllegalStateException unless every structural invariant holds. */
    void checkInvariants() {
        int counted = 0;
        for (int level = 0; level < LEVELS; level++) {
            for (int slot = 0; slot < SLOTS; slot++) {
                boolean bit = (occupied[level] & (1L << slot)) != 0;
                Handle head = heads[level][slot];
                if (bit != (head != null)) {
                    throw new IllegalStateException("occupancy bit mismatch at " + level + "/" + slot);
                }
                Handle prev = null;
                for (Handle h = head; h != null; h = h.next) {
                    counted++;
                    if (h.prev != prev || h.state != PENDING || h.level != level || h.slot != slot) {
                        throw new IllegalStateException("bad link/state at " + level + "/" + slot);
                    }
                    long x = h.deadline ^ now;
                    int expectLevel = x == 0 ? 0 : (63 - Long.numberOfLeadingZeros(x)) / BITS;
                    int expectSlot = (int) ((h.deadline >>> (BITS * expectLevel)) & SLOT_MASK);
                    if ((x >>> OVERFLOW_SHIFT) != 0 || expectLevel != level || expectSlot != slot
                            || h.deadline < now) {
                        throw new IllegalStateException("timer " + h.deadline + " misplaced at " + level + "/"
                                + slot + " now=" + now);
                    }
                    prev = h;
                }
            }
        }
        for (Handle h : overflow) {
            counted++;
            if (h.state != PENDING || h.level != OVERFLOW || ((h.deadline ^ now) >>> OVERFLOW_SHIFT) == 0) {
                throw new IllegalStateException("bad overflow entry " + h.deadline + " now=" + now);
            }
        }
        if (counted != size) {
            throw new IllegalStateException("size " + size + " but " + counted + " linked");
        }
    }

    /** Flips an occupancy bit so tests can prove {@link #checkInvariants} detects corruption. */
    void corruptOccupancyForTest() {
        occupied[0] ^= 1L;
    }
}
