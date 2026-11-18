package ae.notify;

import java.util.Map;

/** The messy original. Kept unchanged as the oracle for the refactoring. */
final class LegacyNotifier {
    private LegacyNotifier() {}

    static String render(String channel, String priority, String user, String title, String body,
                         int hour, Map<String, String> prefs) {
        if (channel == null || channel.trim().isEmpty()) {
            return "ERR:no-channel";
        }
        String ch = channel.trim().toLowerCase(java.util.Locale.ROOT);
        if (user == null || user.trim().isEmpty()) {
            return "ERR:no-user";
        }
        String pr;
        if (priority == null) {
            pr = "normal";
        } else {
            pr = priority.trim().toLowerCase(java.util.Locale.ROOT);
            if (!pr.equals("low") && !pr.equals("normal") && !pr.equals("high")) {
                return "ERR:bad-priority";
            }
        }
        if (hour < 0 || hour > 23) {
            return "ERR:bad-hour";
        }
        if (!ch.equals("email") && !ch.equals("sms") && !ch.equals("push") && !ch.equals("slack")) {
            return "ERR:unknown-channel:" + ch;
        }
        String u = user.trim();
        String t = title == null ? "" : title;
        String b = body == null ? "" : body;
        if (prefs != null) {
            String p = prefs.get(ch);
            if (p != null && p.trim().equalsIgnoreCase("off")) {
                return "SKIP:disabled";
            }
        }
        boolean quiet = hour >= 22 || hour < 7;
        if (quiet && !pr.equals("high")) {
            if (ch.equals("sms") || ch.equals("push")) {
                return "SKIP:quiet-hours";
            }
        }
        if (ch.equals("email")) {
            String subject = t.isEmpty() ? "(no subject)" : t;
            if (pr.equals("high")) {
                subject = "[URGENT] " + subject;
            } else if (pr.equals("low")) {
                subject = "[FYI] " + subject;
            }
            return "EMAIL to=" + u + " subject=" + subject + " body=" + b;
        } else if (ch.equals("sms")) {
            String text = t.isEmpty() ? b : (b.isEmpty() ? t : t + ": " + b);
            if (pr.equals("high")) {
                text = "!! " + text;
            }
            if (text.length() > 160) {
                text = text.substring(0, 157) + "...";
            }
            return "SMS to=" + u + " text=" + text;
        } else if (ch.equals("push")) {
            String pt = t.isEmpty() ? "(no title)" : t;
            if (pt.length() > 40) {
                pt = pt.substring(0, 37) + "...";
            }
            String pb = b;
            if (pb.length() > 100) {
                pb = pb.substring(0, 97) + "...";
            }
            String sound;
            if (pr.equals("high")) {
                sound = "alarm";
            } else if (pr.equals("normal")) {
                sound = "default";
            } else {
                sound = "none";
            }
            return "PUSH to=" + u + " title=" + pt + " body=" + pb + " sound=" + sound;
        } else {
            String text = "*" + (t.isEmpty() ? "(no title)" : t) + "*";
            if (!b.isEmpty()) {
                text = text + "\n" + b;
            }
            if (pr.equals("high")) {
                text = ":rotating_light: " + text;
            }
            return "SLACK to=#" + u + " text=" + text;
        }
    }
}
