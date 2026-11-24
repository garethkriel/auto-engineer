package ae.broker;

/** Topic name and topic filter rules (MQTT style). Levels are separated by '/'; empty levels count. */
public final class Topics {
    private Topics() {}

    /** A topic name (publish target): non-empty, no wildcards, no NUL. */
    public static void validateTopic(String topic) {
        if (topic == null || topic.isEmpty()) {
            throw new IllegalArgumentException("topic must be non-empty");
        }
        if (topic.indexOf('+') >= 0 || topic.indexOf('#') >= 0 || topic.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("topic contains wildcard or NUL: " + topic);
        }
    }

    /** A filter: '+' must fill a level, '#' must fill the last level. */
    public static void validateFilter(String filter) {
        if (filter == null || filter.isEmpty()) {
            throw new IllegalArgumentException("filter must be non-empty");
        }
        if (filter.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("filter contains NUL");
        }
        String[] levels = filter.split("/", -1);
        for (int i = 0; i < levels.length; i++) {
            String l = levels[i];
            if (l.indexOf('#') >= 0 && !(l.equals("#") && i == levels.length - 1)) {
                throw new IllegalArgumentException("bad '#' in filter: " + filter);
            }
            if (l.indexOf('+') >= 0 && !l.equals("+")) {
                throw new IllegalArgumentException("bad '+' in filter: " + filter);
            }
        }
    }

    /** Reference matcher; both arguments are assumed valid. */
    public static boolean matches(String filter, String topic) {
        String[] f = filter.split("/", -1);
        String[] t = topic.split("/", -1);
        if (topic.startsWith("$") && (f[0].equals("+") || f[0].equals("#"))) {
            return false;
        }
        for (int i = 0; i < f.length; i++) {
            if (f[i].equals("#")) {
                return true;
            }
            if (i >= t.length) {
                return false;
            }
            if (!f[i].equals("+") && !f[i].equals(t[i])) {
                return false;
            }
        }
        return f.length == t.length;
    }
}
