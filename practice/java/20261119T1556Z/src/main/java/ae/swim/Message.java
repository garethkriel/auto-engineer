package ae.swim;

import java.util.List;

/** Wire messages. Every message piggybacks gossip updates. */
public sealed interface Message permits Ping, PingReq, Ack {
    String from();

    String to();

    List<Update> updates();
}
