package ae.outbox.app;

import ae.outbox.domain.OutboxMessage;
import ae.outbox.port.MessageSink;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** A consumer-side sink that processes each message id once, however often it is delivered. */
public final class IdempotentReceiver implements MessageSink {
    private final Set<Long> seen = new HashSet<>();
    private final Consumer<OutboxMessage> handler;

    public IdempotentReceiver(Consumer<OutboxMessage> handler) {
        this.handler = Objects.requireNonNull(handler);
    }

    @Override
    public synchronized void deliver(OutboxMessage message) {
        if (seen.contains(message.id())) {
            return;
        }
        handler.accept(message); // if the handler throws, the id stays unseen and a redelivery retries it
        seen.add(message.id());
    }
}
