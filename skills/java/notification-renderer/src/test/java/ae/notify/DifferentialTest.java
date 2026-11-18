package ae.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Exhaustive grid of edge values: refactored facade must equal the legacy oracle on every combination. */
class DifferentialTest {
    private static final List<String> CHANNELS = java.util.Arrays.asList(
            null, "", "  ", "email", " EMAIL ", "Sms", "push", "SLACK", "fax", " Fax ");
    private static final List<String> PRIOS = java.util.Arrays.asList(
            null, "", "low", " HIGH ", "Normal", "urgent");
    private static final List<String> USERS = java.util.Arrays.asList(null, "", " ", "bob", " bob ");
    private static final List<String> TEXTS = java.util.Arrays.asList(
            null, "", "t", "t".repeat(39), "t".repeat(40), "t".repeat(41), "m".repeat(98), "m".repeat(100),
            "m".repeat(101), "m".repeat(157), "m".repeat(158), "m".repeat(160), "m".repeat(161), "a\nb");
    private static final int[] HOURS = {-1, 0, 6, 7, 12, 21, 22, 23, 24};
    private static final List<Map<String, String>> PREFS = java.util.Arrays.asList(
            null, Map.of(), Map.of("email", "off"), Map.of("sms", " Off "), Map.of("push", "on"),
            Map.of("slack", "OFF", "email", "off"));

    @Test
    void gridMatchesLegacy() {
        int checked = 0;
        for (String ch : CHANNELS) for (String pr : PRIOS) for (String u : USERS)
            for (String title : TEXTS) for (int h : HOURS) for (Map<String, String> p : PREFS) {
                String body = TEXTS.get((title == null ? 0 : title.length()) % TEXTS.size());
                String ctx = "ch=" + ch + " pr=" + pr + " u=" + u + " title=" + title + " h=" + h + " prefs=" + p;
                assertEquals(LegacyNotifier.render(ch, pr, u, title, body, h, p),
                        Notifier.render(ch, pr, u, title, body, h, p), ctx);
                checked++;
            }
        assertTrue(checked > 100_000, "grid too small: " + checked);
    }

    @Test
    void titleBodyCrossProductMatchesLegacyForValidInputs() {
        for (String ch : List.of("email", "sms", "push", "slack")) for (String pr : List.of("low", "normal", "high"))
            for (String title : TEXTS) for (String body : TEXTS) for (int h : new int[] {3, 12}) {
                assertEquals(LegacyNotifier.render(ch, pr, "u", title, body, h, null),
                        Notifier.render(ch, pr, "u", title, body, h, null),
                        ch + "/" + pr + "/" + title + "/" + body + "/" + h);
            }
    }

    @Test
    void mutatedPrefsMapIsReadOnlyOnce() {
        Map<String, String> prefs = new HashMap<>();
        prefs.put("sms", "off");
        assertEquals("SKIP:disabled", Notifier.render("sms", null, "u", "t", "b", 12, prefs));
        prefs.put("sms", "on");
        assertEquals("SMS to=u text=t: b", Notifier.render("sms", null, "u", "t", "b", 12, prefs));
        assertEquals(Map.of("sms", "on"), prefs);
    }

    @Test
    void typedOutcomesExposeCategory() {
        assertInstanceOf(Outcome.Failed.class, Notifier.dispatch("fax", null, "u", "t", "b", 1, null));
        assertInstanceOf(Outcome.Skipped.class, Notifier.dispatch("sms", null, "u", "t", "b", 1, null));
        Outcome sent = Notifier.dispatch("slack", "high", "u", "t", "b", 1, null);
        assertInstanceOf(Outcome.Sent.class, sent);
        assertEquals("SLACK to=#u text=:rotating_light: *t*\nb", sent.text());
    }

    @Test
    void channelsAreDiscoverableByNameAndTruncationHelperWorks() {
        assertEquals("sms", Channel.byName("sms").orElseThrow().name());
        assertTrue(Channel.byName("SMS").isEmpty(), "lookup expects normalised names");
        assertEquals("abcdefg", Text.truncate("abcdefg", 7));
        assertEquals("abcd...", Text.truncate("abcdefgh", 7));
    }
}
