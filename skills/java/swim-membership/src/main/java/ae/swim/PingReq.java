package ae.swim;

import java.util.List;

/** "Please probe {@code target} for me." {@code seq} is the requester's probe number. */
public record PingReq(String from, String to, String target, int seq, List<Update> updates) implements Message {
    public PingReq {
        updates = List.copyOf(updates);
    }
}
