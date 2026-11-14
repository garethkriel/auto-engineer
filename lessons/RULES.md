# Rules learned

General rules distilled from recurring lessons in `lessons/entries/`. Only maintenance rounds change this file. Each rule is one line and names the lesson entries it came from.

- Boundary checks on computed doubles need a small epsilon (e.g. 1e-9), and the boundary value itself must be tested. (20261112T1615Z)
- Every test must be able to fail: assert on real behaviour of the code under test, never `assertTrue(true)` or an empty body. A test that passes against a stub is a defect. (rejected PR #2)
- Lesson entries need a concrete root cause and a rule that would prevent a specific mistake; "None" or "use the standard library" is rejected. (rejected PR #2)
- Run logs and PR bodies must quote real test output (test counts, last lines of `mvn` output), not just "exit 0". (rejected PR #2)
- Maven "Could not find artifact" while curl to the same URL returns 200 is a transient negative-cache failure: loop `mvn -q -B -U test` (up to ~6 tries), confirm with a final run, and don't count it as a debug attempt. (20261113T0857Z, 20261113T1002Z, 20261113T1156Z, 20261113T1544Z)
- Template poms with the exact `CHANGE_ME` placeholder and `diff` against `templates/java/pom.xml`; only the project artifactId line may differ. (20261113T1757Z)
- Use `Math.floorMod` for wrap-around indexes and a boolean/Optional instead of an in-band sentinel; always test with `Integer.MIN_VALUE`/`MAX_VALUE` inputs. (20261113T0757Z)
- Make failures side-effect free: validate arguments and compute every throwing result (e.g. `Math.addExact`) into locals before mutating fields or static counters. (20261113T1156Z)
- For value classes, normalise in the single factory so `equals`/`hashCode` compare plain fields; use `Math.*Exact` or `BigInteger` for overflow-prone arithmetic. (20261113T1002Z, 20261113T1544Z)
- Re-read hand-written expected values for ambiguity before running tests; a wrong expectation is a test defect, not a code defect. (20261113T0857Z)
- Before choosing a practice topic, list `benchmark/java/` directory names and reject any task whose core algorithm matches one (word-frequency/top-k counting, interval merging, LRU cache, CSV parsing, LCS, expression evaluation, graph search, bank accounts, counters, streams, file indexing, Roman numerals). A different surface (new names, extra methods) does not count as a different task; state the overlap check in the lesson and run log. (rejected PRs #13, #16, #18)
