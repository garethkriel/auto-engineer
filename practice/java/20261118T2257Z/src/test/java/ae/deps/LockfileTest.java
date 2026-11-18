package ae.deps;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LockfileTest {
    private static Resolution sample() throws Exception {
        InMemoryRegistry reg = new InMemoryRegistry();
        reg.add("web", "2.0.0", "log", "^1.0.0").add("log", "1.4.0").add("zed", "0.1.0-beta.1");
        return new Resolver(reg).resolve(ResolverTest.roots("zed", ">=0.1.0-beta.1", "web", "*"));
    }

    @Test
    void formatIsSortedLinePerPackage() throws Exception {
        assertEquals("log@1.4.0\nweb@2.0.0\nzed@0.1.0-beta.1\n", Lockfile.format(sample()));
    }

    @Test
    void parseRoundTrip() throws Exception {
        Map<String, Version> m = Lockfile.parse(Lockfile.format(sample()));
        assertEquals(sample().versions(), m);
        assertEquals(Map.of(), Lockfile.parse("# nothing\n\n"));
    }

    @Test
    void parseRejectsBadLines() {
        for (String s : new String[] {"log", "log@", "@1.0.0", "log@1.0", "Log@1.0.0", "log@1.0.0\nlog@1.0.1", "a b@1.0.0"}) {
            assertThrows(IllegalArgumentException.class, () -> Lockfile.parse(s), s);
        }
    }

    @Test
    void fileRoundTripLeavesNoTempFiles(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("deps.lock");
        Lockfile.write(f, sample());
        assertEquals(sample().versions(), Lockfile.read(f));
        // overwrite
        InMemoryRegistry reg = new InMemoryRegistry().add("only", "3.0.0");
        Lockfile.write(f, new Resolver(reg).resolve(ResolverTest.roots("only", "*")));
        assertEquals(Map.of("only", Version.parse("3.0.0")), Lockfile.read(f));
        try (Stream<Path> s = Files.list(dir)) {
            assertEquals(List.of("deps.lock"), s.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void lockKeepsOldVersionsWhileNewReleaseExists() throws Exception {
        InMemoryRegistry reg = new InMemoryRegistry();
        reg.add("app", "1.0.0", "lib", "^1.0.0", "util", "~2.0.0");
        reg.add("lib", "1.0.0").add("util", "2.0.1");
        Resolver res = new Resolver(reg);
        Map<String, Constraint> roots = ResolverTest.roots("app", "*");
        Resolution first = res.resolve(roots);
        Map<String, Version> lock = Lockfile.parse(Lockfile.format(first));
        reg.add("lib", "1.1.0").add("util", "2.0.5").add("app", "1.1.0", "lib", "^1.0.0");
        // fresh resolution moves everything forward; locked resolution moves nothing
        assertEquals("1.1.0", res.resolve(roots).versions().get("app").toString());
        assertEquals(first.versions(), res.resolve(roots, lock).versions());
        // updating one package = dropping its lock entry
        Map<String, Version> partial = new java.util.TreeMap<>(lock);
        partial.remove("lib");
        Resolution upd = res.resolve(roots, partial);
        assertEquals("1.1.0", upd.versions().get("lib").toString());
        assertEquals("1.0.0", upd.versions().get("app").toString());
        assertEquals("2.0.1", upd.versions().get("util").toString());
    }
}
