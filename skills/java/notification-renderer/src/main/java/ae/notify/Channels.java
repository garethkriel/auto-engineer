package ae.notify;

import java.util.List;
import java.util.Optional;

/** Built-in channel strategies. */
final class Channels {
    private Channels() {}

    static final Channel EMAIL = new Channel() {
        public String name() { return "email"; }
        public boolean respectsQuietHours() { return false; }
        public String render(Notification n) {
            String prefix = switch (n.priority()) {
                case HIGH -> "[URGENT] ";
                case LOW -> "[FYI] ";
                case NORMAL -> "";
            };
            return "EMAIL to=" + n.user() + " subject=" + prefix + Text.orDefault(n.title(), "(no subject)")
                    + " body=" + n.body();
        }
    };

    static final Channel SMS = new Channel() {
        public String name() { return "sms"; }
        public boolean respectsQuietHours() { return true; }
        public String render(Notification n) {
            String text = n.title().isEmpty() ? n.body()
                    : n.body().isEmpty() ? n.title() : n.title() + ": " + n.body();
            if (n.priority() == Priority.HIGH) {
                text = "!! " + text;
            }
            return "SMS to=" + n.user() + " text=" + Text.truncate(text, 160);
        }
    };

    static final Channel PUSH = new Channel() {
        public String name() { return "push"; }
        public boolean respectsQuietHours() { return true; }
        public String render(Notification n) {
            String sound = switch (n.priority()) {
                case HIGH -> "alarm";
                case NORMAL -> "default";
                case LOW -> "none";
            };
            return "PUSH to=" + n.user() + " title=" + Text.truncate(Text.orDefault(n.title(), "(no title)"), 40)
                    + " body=" + Text.truncate(n.body(), 100) + " sound=" + sound;
        }
    };

    static final Channel SLACK = new Channel() {
        public String name() { return "slack"; }
        public boolean respectsQuietHours() { return false; }
        public String render(Notification n) {
            String text = "*" + Text.orDefault(n.title(), "(no title)") + "*";
            if (!n.body().isEmpty()) {
                text += "\n" + n.body();
            }
            if (n.priority() == Priority.HIGH) {
                text = ":rotating_light: " + text;
            }
            return "SLACK to=#" + n.user() + " text=" + text;
        }
    };

    private static final List<Channel> ALL = List.of(EMAIL, SMS, PUSH, SLACK);

    static Optional<Channel> find(String name) {
        return ALL.stream().filter(c -> c.name().equals(name)).findFirst();
    }
}
