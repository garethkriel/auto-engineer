package ae.dns;

/** Sends one query to a server address and returns its reply, or null when the server does not answer. */
@FunctionalInterface
public interface Transport {
    Message send(int serverAddress, Message query);
}
