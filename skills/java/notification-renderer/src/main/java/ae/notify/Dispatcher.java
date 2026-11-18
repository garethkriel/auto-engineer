package ae.notify;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Validation and policy (preferences, quiet hours) around channel rendering. Guard order matches the legacy code. */
final class Dispatcher {
    private static final int QUIET_FROM = 22;
    private static final int QUIET_UNTIL = 7;

    private Dispatcher() {}

    static Outcome dispatch(String channel, String priority, String user, String title, String body,
                            int hour, Map<String, String> prefs) {
        if (channel == null || channel.isBlank()) {
            return new Outcome.Failed("no-channel");
        }
        String channelName = channel.trim().toLowerCase(Locale.ROOT);
        if (user == null || user.isBlank()) {
            return new Outcome.Failed("no-user");
        }
        Priority prio = Priority.NORMAL;
        if (priority != null) {
            Optional<Priority> parsed = Priority.parse(priority);
            if (parsed.isEmpty()) {
                return new Outcome.Failed("bad-priority");
            }
            prio = parsed.get();
        }
        if (hour < 0 || hour > 23) {
            return new Outcome.Failed("bad-hour");
        }
        Optional<Channel> found = Channel.byName(channelName);
        if (found.isEmpty()) {
            return new Outcome.Failed("unknown-channel:" + channelName);
        }
        Channel ch = found.get();
        if (isDisabled(prefs, channelName)) {
            return new Outcome.Skipped("disabled");
        }
        if (ch.respectsQuietHours() && isQuiet(hour) && prio != Priority.HIGH) {
            return new Outcome.Skipped("quiet-hours");
        }
        Notification n = new Notification(user.trim(), prio, title == null ? "" : title, body == null ? "" : body);
        return new Outcome.Sent(ch.render(n));
    }

    private static boolean isDisabled(Map<String, String> prefs, String channelName) {
        if (prefs == null) {
            return false;
        }
        String pref = prefs.get(channelName);
        return pref != null && pref.trim().equalsIgnoreCase("off");
    }

    private static boolean isQuiet(int hour) {
        return hour >= QUIET_FROM || hour < QUIET_UNTIL;
    }
}
