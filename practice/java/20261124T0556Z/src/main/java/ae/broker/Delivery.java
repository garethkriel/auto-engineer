package ae.broker;

/** One outgoing PUBLISH packet as seen by a client. {@code packetId} is 0 for QoS 0. */
public record Delivery(String topic, String payload, int qos, boolean retain, boolean dup, int packetId) {}
