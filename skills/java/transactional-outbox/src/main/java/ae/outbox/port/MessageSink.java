package ae.outbox.port;

import ae.outbox.domain.OutboxMessage;

/** Port for the broker the relay publishes to. */
@FunctionalInterface
public interface MessageSink {
    void deliver(OutboxMessage message) throws Exception;
}
