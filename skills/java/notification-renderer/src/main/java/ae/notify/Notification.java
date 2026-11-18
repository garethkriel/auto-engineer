package ae.notify;

import java.util.Objects;

/** A validated notification: recipient trimmed, title/body never null. */
public record Notification(String user, Priority priority, String title, String body) {
    public Notification {
        Objects.requireNonNull(user);
        Objects.requireNonNull(priority);
        Objects.requireNonNull(title);
        Objects.requireNonNull(body);
    }
}
