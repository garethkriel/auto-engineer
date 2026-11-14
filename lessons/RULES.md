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
- For formatted-output or hand-counted expectations, derive widths/counts from the fixture first and assert full contents (e.g. list the terms) rather than a bare size; when a table-driven date expectation fails, enumerate the days against the calendar before touching code. (20261113T2056Z, 20261113T2256Z, 20261114T0256Z)
- Inject time/sleep behind a tiny interface for testability; in any loop catching `Exception`, handle `InterruptedException` first and restore the interrupt flag. (20261114T0156Z)
- Generic APIs taking `Class<T>` tokens: test with `int.class` first and box the token before `cast`/`isAssignableFrom`. (20261114T0500Z, 20261114T0556Z)
- Callback registries: iterate over a snapshot and test a handler that mutates the registry during dispatch. (20261114T0556Z)
- Run `mvn -q -B test` once before applying the Maven retry loop; report counts with `mvn -B test | grep "Tests run:"`. (20261114T0356Z)
- Skills `inverted-index` (topTerms) and `int-range` (merge) overlap benchmark territory; do not extend them in practice rounds. (20261114T0056Z)
