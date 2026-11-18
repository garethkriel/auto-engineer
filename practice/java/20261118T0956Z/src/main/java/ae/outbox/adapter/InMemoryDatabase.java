package ae.outbox.adapter;

import ae.outbox.domain.Booking;
import ae.outbox.domain.OutboxMessage;
import ae.outbox.domain.Show;
import ae.outbox.port.Database;
import ae.outbox.port.Tx;
import ae.outbox.port.TxWork;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory {@link Database}. Transactions are serialised by a lock and run on a private copy of the state; the copy
 * replaces the committed state only if the work returns normally.
 */
public final class InMemoryDatabase implements Database {
    private final ReentrantLock lock = new ReentrantLock();
    private State committed = new State();

    @Override
    public <T> T inTransaction(TxWork<T> work) {
        if (lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("nested transactions are not supported");
        }
        lock.lock();
        try {
            State working = committed.copy();
            TxImpl tx = new TxImpl(working);
            try {
                T result = work.run(tx);
                committed = working;
                return result;
            } finally {
                tx.active = false;
            }
        } finally {
            lock.unlock();
        }
    }

    private static final class State {
        final Map<String, Show> shows = new HashMap<>();
        final Map<String, Booking> bookings = new HashMap<>();
        final TreeMap<Long, OutboxMessage> outbox = new TreeMap<>();
        long bookingSeq;
        long outboxSeq;

        State copy() {
            State c = new State();
            c.shows.putAll(shows);
            c.bookings.putAll(bookings);
            c.outbox.putAll(outbox);
            c.bookingSeq = bookingSeq;
            c.outboxSeq = outboxSeq;
            return c;
        }
    }

    private static final class TxImpl implements Tx {
        private final State state;
        boolean active = true;

        TxImpl(State state) {
            this.state = state;
        }

        private void check() {
            if (!active) {
                throw new IllegalStateException("transaction is no longer active");
            }
        }

        @Override
        public Optional<Show> findShow(String id) {
            check();
            return Optional.ofNullable(state.shows.get(id));
        }

        @Override
        public void saveShow(Show show) {
            check();
            state.shows.put(show.id(), show);
        }

        @Override
        public Optional<Booking> findBooking(String id) {
            check();
            return Optional.ofNullable(state.bookings.get(id));
        }

        @Override
        public void saveBooking(Booking booking) {
            check();
            state.bookings.put(booking.id(), booking);
        }

        @Override
        public long nextBookingNumber() {
            check();
            return ++state.bookingSeq;
        }

        @Override
        public OutboxMessage enqueue(String key, String topic, String payload, Instant now) {
            check();
            OutboxMessage m = OutboxMessage.created(++state.outboxSeq, key, topic, payload, now);
            state.outbox.put(m.id(), m);
            return m;
        }

        @Override
        public List<OutboxMessage> pending() {
            check();
            List<OutboxMessage> out = new ArrayList<>();
            for (OutboxMessage m : state.outbox.values()) {
                if (m.status() == OutboxMessage.Status.PENDING) {
                    out.add(m);
                }
            }
            return out;
        }

        @Override
        public List<OutboxMessage> allMessages() {
            check();
            return new ArrayList<>(state.outbox.values());
        }

        @Override
        public void update(OutboxMessage message) {
            check();
            if (!state.outbox.containsKey(message.id())) {
                throw new IllegalArgumentException("unknown message " + message.id());
            }
            state.outbox.put(message.id(), message);
        }
    }
}
