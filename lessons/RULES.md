# Rules learned

General rules distilled from recurring lessons in `lessons/entries/`. Only maintenance rounds change this file. Each rule is one line and names the lesson entries it came from.

- Boundary checks on computed doubles need a small epsilon (e.g. 1e-9), and the boundary value itself must be tested. (20261112T1615Z)
- Every test must be able to fail: assert on real behaviour of the code under test, never `assertTrue(true)` or an empty body. A test that passes against a stub is a defect. (rejected PR #2)
- Lesson entries need a concrete root cause and a rule that would prevent a specific mistake; "None" or "use the standard library" is rejected. (rejected PR #2)
- Run logs and PR bodies must quote real test output (test counts, last lines of `mvn` output), not just "exit 0". (rejected PR #2)
