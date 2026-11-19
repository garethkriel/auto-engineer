package ae.coord;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * Fair distributed lock recipe: each requester creates an ephemeral-sequential node under the
 * lock path; the lowest sequence holds the lock, every other waiter watches only the node
 * directly before it (no herd effect). The sequence number is a monotonic fencing token.
 */
public final class DistributedLock {
    /** Lifecycle of a {@link Handle}. */
    public enum State { WAITING, GRANTED, RELEASED, LOST }

    private static final String PREFIX = "lock-";

    private final CoordService svc;
    private final String lockPath;

    /** Ensures the lock path exists (creating missing ancestors) using an internal immortal session. */
    public DistributedLock(CoordService svc, String lockPath) {
        this.svc = Objects.requireNonNull(svc);
        this.lockPath = Objects.requireNonNull(lockPath);
        long boot = svc.connect(Long.MAX_VALUE);
        StringBuilder sb = new StringBuilder();
        for (String seg : lockPath.substring(1).split("/")) {
            sb.append('/').append(seg);
            if (svc.exists(boot, sb.toString(), null).isEmpty()) {
                try {
                    svc.create(boot, sb.toString(), "", CreateMode.PERSISTENT);
                } catch (CoordException e) {
                    if (e.code() != CoordException.Code.NODE_EXISTS) {
                        throw e;
                    }
                }
            }
        }
        svc.close(boot);
    }

    /** Queues for the lock; {@code onGranted} gets the fencing token exactly once. */
    public Handle request(long session, LongConsumer onGranted) {
        Objects.requireNonNull(onGranted);
        String path = svc.create(session, lockPath + "/" + PREFIX, "", CreateMode.EPHEMERAL_SEQUENTIAL);
        Handle h = new Handle(session, path, onGranted);
        h.evaluate();
        return h;
    }

    /** One queue position. Thread-safe. */
    public final class Handle {
        private final long session;
        private final String path;
        private final String name;
        private final long token;
        private final LongConsumer onGranted;
        private State state = State.WAITING;

        private Handle(long session, String path, LongConsumer onGranted) {
            this.session = session;
            this.path = path;
            this.name = CoordService.nameOf(path);
            this.token = Long.parseLong(name.substring(PREFIX.length()));
            this.onGranted = onGranted;
        }

        /**
         * Current state. A WAITING or GRANTED handle is verified against the service, so a handle
         * whose session expired (or whose node vanished) reports LOST.
         */
        public State state() {
            State s = rawState();
            if (s == State.WAITING || s == State.GRANTED) {
                try {
                    if (svc.exists(session, path, null).isEmpty()) {
                        markLost();
                    }
                } catch (CoordException e) {
                    markLost();
                }
                return rawState();
            }
            return s;
        }

        private synchronized State rawState() {
            return state;
        }

        /** The fencing token (the node's sequence number). */
        public long token() {
            return token;
        }

        /** Gives the lock up or leaves the queue; idempotent. */
        public void release() {
            synchronized (this) {
                if (state == State.RELEASED || state == State.LOST) {
                    return;
                }
                state = State.RELEASED;
            }
            try {
                svc.delete(session, path, -1);
            } catch (CoordException e) {
                if (e.code() != CoordException.Code.NO_NODE && e.code() != CoordException.Code.SESSION_EXPIRED) {
                    throw e;
                }
            }
        }

        private synchronized boolean markGranted() {
            if (state != State.WAITING) {
                return false;
            }
            state = State.GRANTED;
            return true;
        }

        private synchronized void markLost() {
            if (state == State.WAITING || state == State.GRANTED) {
                state = State.LOST;
            }
        }

        /** Decides between granted, waiting on the predecessor, or lost. Safe to call repeatedly. */
        void evaluate() {
            while (true) {
                if (rawState() != State.WAITING) {
                    return;
                }
                List<String> kids;
                try {
                    kids = svc.getChildren(session, lockPath, null);
                } catch (CoordException e) {
                    markLost();
                    return;
                }
                kids = kids.stream().filter(k -> k.startsWith(PREFIX)).toList();
                int mine = kids.indexOf(name);
                if (mine < 0) {
                    markLost();
                    return;
                }
                if (mine == 0) {
                    if (markGranted()) {
                        onGranted.accept(token);
                    }
                    return;
                }
                Consumer<WatchEvent> again = e -> evaluate();
                Optional<Stat> pred;
                try {
                    pred = svc.exists(session, lockPath + "/" + kids.get(mine - 1), again);
                } catch (CoordException e) {
                    markLost();
                    return;
                }
                if (pred.isPresent()) {
                    return; // the watch re-runs evaluate when the predecessor goes away
                }
                // predecessor vanished between the two calls: look again
            }
        }
    }
}
