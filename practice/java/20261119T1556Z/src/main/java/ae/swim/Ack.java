package ae.swim;

import java.util.List;

/** "{@code about} is alive." A relayed ack keeps the requester's {@code seq}. */
public record Ack(String from, String to, int seq, String about, List<Update> updates) implements Message {
    public Ack {
        updates = List.copyOf(updates);
    }
}
