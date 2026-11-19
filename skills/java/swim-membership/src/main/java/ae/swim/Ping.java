package ae.swim;

import java.util.List;

/** "Are you alive?" */
public record Ping(String from, String to, int seq, List<Update> updates) implements Message {
    public Ping {
        updates = List.copyOf(updates);
    }
}
