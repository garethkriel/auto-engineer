package ae.notify;

import java.util.Optional;

/** A delivery channel: strategy that renders a notification into its wire line. */
public interface Channel {
    /** Lower-case registry name, e.g. {@code sms}. */
    String name();

    /** Whether the channel is muted during quiet hours for non-high priorities. */
    boolean respectsQuietHours();

    /** Renders the notification line. */
    String render(Notification n);

    /** Looks up a built-in channel by its normalised (trimmed, lower-case) name. */
    static Optional<Channel> byName(String name) {
        return Channels.find(name);
    }
}
