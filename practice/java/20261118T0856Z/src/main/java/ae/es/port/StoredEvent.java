package ae.es.port;

import ae.es.domain.CartEvent;

/** An event as persisted: global {@code position} (1-based, dense) and per-stream {@code version} (1-based). */
public record StoredEvent(long position, String streamId, long version, CartEvent event) { }
