package ae.deps;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TextRegistryTest {
    @Test
    void parsesPackagesCommentsAndBlankLines() throws Exception {
        String text = """
            # sample index
            app@1.0.0: web ^2.0.0, log >=1.0.0 <2.0.0 || 3.x

            web@2.1.0: log ^1.0.0
            log@1.2.0:
            log@3.0.0: # trailing comment
            """;
        Registry reg = TextRegistry.parse(text);
        assertEquals(List.of(Version.parse("3.0.0"), Version.parse("1.2.0")), reg.versions("log"));
        Package app = reg.find("app", Version.parse("1.0.0")).orElseThrow();
        assertEquals("^2.0.0", app.deps().get("web").toString());
        assertEquals(">=1.0.0 <2.0.0 || 3.x", app.deps().get("log").toString());
        assertTrue(reg.find("log", Version.parse("3.0.0")).orElseThrow().deps().isEmpty());
        assertTrue(reg.find("log", Version.parse("9.0.0")).isEmpty());
        assertTrue(reg.versions("nothing").isEmpty());
        Resolution r = new Resolver(reg).resolve(Map.of("app", Constraint.parse("*")));
        assertEquals("1.2.0", r.versions().get("log").toString());
    }

    @Test
    void rejectsMalformedLinesWithLineNumber() {
        for (String line : new String[] {"app 1.0.0: x ^1", "app@1.0: x ^1", "app@1.0.0 x ^1", "App@1.0.0:", "app@1.0.0: lonely", "app@1.0.0: a ^1, a ^2", "app@1.0.0: a bogus"}) {
            String text = "ok@1.0.0:\n" + line + "\n";
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> TextRegistry.parse(text), line);
            assertTrue(e.getMessage().contains("line 2"), line + " -> " + e.getMessage());
        }
    }

    @Test
    void duplicatePackageVersionRejected() {
        assertThrows(IllegalArgumentException.class, () -> TextRegistry.parse("a@1.0.0:\na@1.0.0:\n"));
    }
}
