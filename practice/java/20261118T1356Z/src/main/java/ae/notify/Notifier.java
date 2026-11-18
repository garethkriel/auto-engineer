package ae.notify;

import java.util.Map;

/** Facade with the legacy signature: renders one notification to its wire string. */
public final class Notifier {
    private Notifier() {}

    /**
     * Renders a notification.
     *
     * @return {@code ERR:...} for invalid input, {@code SKIP:...} when suppressed, otherwise the channel line
     */
    public static String render(String channel, String priority, String user, String title, String body,
                                int hour, Map<String, String> prefs) {
        return Dispatcher.dispatch(channel, priority, user, title, body, hour, prefs).text();
    }

    /** Same as {@link #render} but returns the typed outcome. */
    public static Outcome dispatch(String channel, String priority, String user, String title, String body,
                                   int hour, Map<String, String> prefs) {
        return Dispatcher.dispatch(channel, priority, user, title, body, hour, prefs);
    }
}
