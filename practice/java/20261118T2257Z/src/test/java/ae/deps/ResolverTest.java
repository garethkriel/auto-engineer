package ae.deps;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResolverTest {
    static Map<String, Constraint> roots(String... pairs) {
        Map<String, Constraint> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], Constraint.parse(pairs[i + 1]));
        return m;
    }

    static Map<String, String> asText(Resolution r) {
        Map<String, String> m = new java.util.TreeMap<>();
        r.versions().forEach((k, v) -> m.put(k, v.toString()));
        return m;
    }

    @Test
    void picksHighestSatisfyingVersion() throws Exception {
        InMemoryRegistry reg = new InMemoryRegistry();
        reg.add("lib", "1.0.0").add("lib", "1.4.2").add("lib", "1.5.0-beta.1").add("lib", "2.0.0");
        Resolution r = new Resolver(reg).resolve(roots("lib", "^1.0.0"));
        assertEquals(Map.of("lib", "1.4.2"), asText(r));
        assertEquals(List.of("lib"), r.installOrder());
    }

    @Test
    void transitiveDependenciesAndInstallOrder() throws Exception {
        InMemoryRegistry reg = new InMemoryRegistry();
        reg.add("app", "1.0.0", "web", "^2.0.0", "log", "*");
        reg.add("web", "2.1.0", "http", "~1.1.0", "log", "^1.0.0");
        reg.add("http", "1.1.5");
        reg.add("http", "1.2.0");
        reg.add("log", "1.0.0").add("log", "1.3.0").add("log", "2.0.0");
        Resolution r = new Resolver(reg).resolve(roots("app", "1.0.0"));
        assertEquals(Map.of("app", "1.0.0", "web", "2.1.0", "http", "1.1.5", "log", "1.3.0"), asText(r));
        // dependencies first; siblings visited in sorted name order: app -> log, web -> (http, log)
        assertEquals(List.of("log", "http", "web", "app"), r.installOrder());
    }

    @Test
    void backtracksWhenFirstChoiceConflictsLater() throws Exception {
        // picking a@2 first forces c@^2, but b requires c@^1, so a@2 must be abandoned
        InMemoryRegistry reg = new InMemoryRegistry();
        reg.add("a", "2.0.0", "c", "^2.0.0");
        reg.add("a", "1.0.0", "c", "^1.0.0");
        reg.add("b", "1.0.0", "c", "^1.0.0");
        reg.add("c", "1.0.0").add("c", "1.1.0").add("c", "2.0.0");
        Resolution r = new Resolver(reg).resolve(roots("a", "*", "b", "*"));
        assertEquals(Map.of("a", "1.0.0", "b", "1.0.0", "c", "1.1.0"), asText(r));
    }

    @Test
    void backtracksDeepButFindsOnlySolution() throws Exception {
        InMemoryRegistry reg = new InMemoryRegistry();
        reg.add("root", "1.0.0", "x", "*", "y", "*");
        for (int i = 1; i <= 5; i++) reg.add("x", i + ".0.0", "z", "=" + i + ".0.0");
        for (int i = 1; i <= 5; i++) reg.add("z", i + ".0.0");
        reg.add("y", "1.0.0", "z", "=3.0.0");
        Resolution r = new Resolver(reg).resolve(roots("root", "*"));
        assertEquals(Map.of("root", "1.0.0", "x", "3.0.0", "y", "1.0.0", "z", "3.0.0"), asText(r));
    }

    @Test
    void conflictMessageNamesPackageAndRequirers() {
        InMemoryRegistry reg = new InMemoryRegistry();
        reg.add("a", "1.0.0", "c", "^1.0.0");
        reg.add("b", "1.0.0", "c", "^2.0.0");
        reg.add("c", "1.0.0").add("c", "2.0.0");
        ResolutionException e = assertThrows(ResolutionException.class,
            () -> new Resolver(reg).resolve(roots("a", "*", "b", "*")));
        String m = e.getMessage();
        assertTrue(m.contains("c"), m);
        assertTrue(m.contains("^1.0.0") && m.contains("a@1.0.0"), m);
        assertTrue(m.contains("^2.0.0") && m.contains("b@1.0.0"), m);
    }

    @Test
    void noMatchingVersionAtRoot() {
        InMemoryRegistry reg = new InMemoryRegistry();
        reg.add("a", "1.0.0");
        ResolutionException e = assertThrows(ResolutionException.class,
            () -> new Resolver(reg).resolve(roots("a", "^2.0.0")));
        assertTrue(e.getMessage().contains("a") && e.getMessage().contains("<root>") && e.getMessage().contains("^2.0.0"), e.getMessage());
    }

    @Test
    void unknownPackage() {
        InMemoryRegistry reg = new InMemoryRegistry();
        reg.add("a", "1.0.0", "ghost", "*");
        ResolutionException e = assertThrows(ResolutionException.class, () -> new Resolver(reg).resolve(roots("a", "*")));
        assertTrue(e.getMessage().contains("unknown package") && e.getMessage().contains("ghost"), e.getMessage());
        ResolutionException e2 = assertThrows(ResolutionException.class, () -> new Resolver(reg).resolve(roots("nope", "*")));
        assertTrue(e2.getMessage().contains("unknown package") && e2.getMessage().contains("nope"), e2.getMessage());
    }

    @Test
    void prereleaseOnlyWhenAskedFor() throws Exception {
        InMemoryRegistry reg = new InMemoryRegistry();
        reg.add("lib", "1.0.0").add("lib", "1.1.0-rc.1");
        assertEquals("1.0.0", asText(new Resolver(reg).resolve(roots("lib", "^1.0.0"))).get("lib"));
        assertEquals("1.1.0-rc.1", asText(new Resolver(reg).resolve(roots("lib", ">=1.1.0-rc.1 <2.0.0"))).get("lib"));
    }

    @Test
    void cyclesAreAllowedAndOrderIgnoresBackEdge() throws Exception {
        InMemoryRegistry reg = new InMemoryRegistry();
        reg.add("a", "1.0.0", "b", "*");
        reg.add("b", "1.0.0", "c", "*");
        reg.add("c", "1.0.0", "a", "*");
        Resolution r = new Resolver(reg).resolve(roots("a", "*"));
        assertEquals(Map.of("a", "1.0.0", "b", "1.0.0", "c", "1.0.0"), asText(r));
        assertEquals(List.of("c", "b", "a"), r.installOrder());
    }

    @Test
    void selfDependencyIsHarmless() throws Exception {
        InMemoryRegistry reg = new InMemoryRegistry();
        reg.add("a", "1.0.0", "a", "^1.0.0");
        assertEquals(Map.of("a", "1.0.0"), asText(new Resolver(reg).resolve(roots("a", "*"))));
        InMemoryRegistry bad = new InMemoryRegistry();
        bad.add("a", "1.0.0", "a", "^2.0.0");
        assertThrows(ResolutionException.class, () -> new Resolver(bad).resolve(roots("a", "*")));
    }

    @Test
    void emptyRootsGiveEmptyResolution() throws Exception {
        Resolution r = new Resolver(new InMemoryRegistry()).resolve(Map.of());
        assertTrue(r.versions().isEmpty());
        assertTrue(r.installOrder().isEmpty());
    }

    @Test
    void searchBudgetIsReported() {
        // 12 independent packages x each has 3 versions, but "end" can never be satisfied: exponential without pruning
        InMemoryRegistry reg = new InMemoryRegistry();
        String[] deps = new String[26];
        for (int i = 0; i < 13; i++) {
            deps[2 * i] = "p" + i;
            deps[2 * i + 1] = "*";
            reg.add("p" + i, "1.0.0", "q", "=1.0.0").add("p" + i, "2.0.0", "q", "=1.0.0").add("p" + i, "3.0.0", "q", "=1.0.0");
        }
        reg.add("q", "1.0.0", "never", "*");
        reg.add("never", "1.0.0", "q", "=9.0.0");
        reg.add("root", "1.0.0", deps);
        ResolutionException e = assertThrows(ResolutionException.class,
            () -> new Resolver(reg, 500).resolve(roots("root", "*")));
        assertTrue(e.getMessage().contains("search budget exceeded") || e.getMessage().contains("q"), e.getMessage());
        // with the default budget the same registry is still reported as unsatisfiable, not as a hang
        assertThrows(ResolutionException.class, () -> new Resolver(reg).resolve(roots("root", "*")));
    }

    @Test
    void preferredVersionWinsWhenItSatisfies() throws Exception {
        InMemoryRegistry reg = new InMemoryRegistry();
        reg.add("lib", "1.0.0").add("lib", "1.2.0").add("lib", "1.3.0");
        Resolver res = new Resolver(reg);
        assertEquals("1.3.0", asText(res.resolve(roots("lib", "^1.0.0"))).get("lib"));
        assertEquals("1.2.0", asText(res.resolve(roots("lib", "^1.0.0"), Map.of("lib", Version.parse("1.2.0")))).get("lib"));
        // preference that no longer satisfies is ignored; one not in the registry is ignored too
        assertEquals("1.3.0", asText(res.resolve(roots("lib", ">=1.3.0"), Map.of("lib", Version.parse("1.2.0")))).get("lib"));
        assertEquals("1.3.0", asText(res.resolve(roots("lib", "^1.0.0"), Map.of("lib", Version.parse("1.2.9")))).get("lib"));
        // unrelated names in the preference map are harmless
        assertEquals("1.3.0", asText(res.resolve(roots("lib", "^1.0.0"), Map.of("zzz", Version.parse("9.9.9")))).get("lib"));
    }

    @Test
    void rootsMustNotBeNullAndResultIsImmutable() throws Exception {
        InMemoryRegistry reg = new InMemoryRegistry().add("a", "1.0.0");
        assertThrows(NullPointerException.class, () -> new Resolver(reg).resolve(null));
        Resolution r = new Resolver(reg).resolve(roots("a", "*"));
        assertThrows(UnsupportedOperationException.class, () -> r.versions().put("b", Version.parse("1.0.0")));
        assertThrows(UnsupportedOperationException.class, () -> r.installOrder().add("b"));
    }
}
