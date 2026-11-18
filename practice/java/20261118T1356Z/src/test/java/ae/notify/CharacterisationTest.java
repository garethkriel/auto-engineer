package ae.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Literal expectations; run against both the legacy oracle and the refactored facade. */
class CharacterisationTest {
    interface Impl {
        String render(String ch, String pr, String user, String title, String body, int hour, Map<String, String> prefs);
    }

    static final Impl LEGACY = LegacyNotifier::render;
    static final Impl REFACTORED = Notifier::render;

    private static final String L160 = "x".repeat(160);

    static void table(Impl i) {
        // validation
        assertEquals("ERR:no-channel", i.render(null, "high", "u", "t", "b", 12, null));
        assertEquals("ERR:no-channel", i.render("  ", "bogus", null, "t", "b", 99, null));
        assertEquals("ERR:no-user", i.render("email", "bogus", " ", "t", "b", 99, null));
        assertEquals("ERR:bad-priority", i.render("email", " urgent ", "u", "t", "b", 99, null));
        assertEquals("ERR:bad-hour", i.render("nope", "low", "u", "t", "b", 24, null));
        assertEquals("ERR:bad-hour", i.render("email", null, "u", "t", "b", -1, null));
        assertEquals("ERR:unknown-channel:fax", i.render(" FAX ", null, "u", "t", "b", 9, null));
        // prefs
        assertEquals("SKIP:disabled", i.render("Email", "high", "u", "t", "b", 3, Map.of("email", " OFF ")));
        assertEquals("EMAIL to=u subject=t body=b", i.render("email", null, " u ", "t", "b", 9, Map.of("email", "on")));
        assertEquals("EMAIL to=u subject=t body=b", i.render("email", null, "u", "t", "b", 9, Map.of("sms", "off")));
        // quiet hours: 22..6 skip sms/push unless high; email/slack unaffected
        assertEquals("SKIP:quiet-hours", i.render("sms", "normal", "u", "t", "b", 22, null));
        assertEquals("SKIP:quiet-hours", i.render("push", "low", "u", "t", "b", 6, null));
        assertEquals("PUSH to=u title=t body=b sound=none", i.render("push", "low", "u", "t", "b", 7, null));
        assertEquals("SMS to=u text=t: b", i.render("sms", null, "u", "t", "b", 21, null));
        assertEquals("SMS to=u text=!! t: b", i.render("sms", "HIGH", "u", "t", "b", 23, null));
        assertEquals("EMAIL to=u subject=[FYI] t body=b", i.render("email", "low", "u", "t", "b", 0, null));
        assertEquals("SLACK to=#u text=*t*\nb", i.render("slack", null, "u", "t", "b", 2, null));
        // email
        assertEquals("EMAIL to=u subject=(no subject) body=", i.render("email", null, "u", null, null, 9, null));
        assertEquals("EMAIL to=u subject=[URGENT] (no subject) body=b", i.render("email", "high", "u", "", "b", 9, null));
        // sms composition and truncation at the 160 boundary
        assertEquals("SMS to=u text=b", i.render("sms", null, "u", "", "b", 9, null));
        assertEquals("SMS to=u text=t", i.render("sms", null, "u", "t", "", 9, null));
        assertEquals("SMS to=u text=", i.render("sms", null, "u", null, null, 9, null));
        assertEquals("SMS to=u text=" + L160, i.render("sms", null, "u", "", L160, 9, null));
        assertEquals("SMS to=u text=" + "x".repeat(157) + "...", i.render("sms", null, "u", "", L160 + "y", 9, null));
        assertEquals("SMS to=u text=!! " + "x".repeat(154) + "...", i.render("sms", "high", "u", "", L160, 9, null));
        // push truncation: title 40 / body 100, sound by priority
        assertEquals("PUSH to=u title=" + "t".repeat(40) + " body=" + "b".repeat(100) + " sound=default",
                i.render("push", null, "u", "t".repeat(40), "b".repeat(100), 9, null));
        assertEquals("PUSH to=u title=" + "t".repeat(37) + "... body=" + "b".repeat(97) + "... sound=alarm",
                i.render("push", "high", "u", "t".repeat(41), "b".repeat(101), 3, null));
        assertEquals("PUSH to=u title=(no title) body= sound=default", i.render("push", null, "u", null, null, 9, null));
        // slack
        assertEquals("SLACK to=#u text=*(no title)*", i.render("slack", null, "u", "", "", 9, null));
        assertEquals("SLACK to=#u text=:rotating_light: *t*\nb", i.render("slack", "high", "u", "t", "b", 9, null));
        assertEquals("SLACK to=#u text=*(no title)*\nb", i.render("slack", "low", "u", "", "b", 9, null));
    }

    @Test
    void legacyMatchesTable() {
        table(LEGACY);
    }

    @Test
    void refactoredMatchesTable() {
        table(REFACTORED);
    }

}
