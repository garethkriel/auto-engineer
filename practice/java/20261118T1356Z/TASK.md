# Practice 20261118T1356Z: refactor a messy notification renderer (level 18, refactoring)

## Task
`src/test/java/ae/notify/LegacyNotifier.java` is a messy but working renderer: one static method with
string-typed channel and priority, nested ifs and copy-pasted truncation. Characterise it, then refactor it
into `ae.notify` types (`Priority`, `Channel` strategies, `Notification`, sealed `Outcome`) behind a facade
`ae.notify.Notifier.render(...)` with the **same signature and exactly the same output strings**.

```
static String render(String channel, String priority, String user, String title, String body,
                     int hour, Map<String,String> prefs)
```

Legacy rules (characterised, not invented): see the legacy file. Output is `ERR:...`, `SKIP:...` or a rendered line.

## Constraints / assumptions
- Standard library only; legacy stays in the test tree as the differential oracle.
- Overlap check: `benchmark/java/` has no notification/channel renderer (closest names: j13-ratelimit, j33-pricing; unrelated algorithms).
- Practice rounds only add files, so the legacy file lives in the test tree of this round.

## Success criteria
1. Characterisation tests (literal expected strings) written and green against the legacy code BEFORE refactoring.
2. Refactored `Notifier` passes the same table and a differential grid over the cross-product of
   invalid/edge values (null, blank, padded, mixed case, hours -1..24, prefs null/off/on, long text boundaries) vs legacy.
3. Guard order is copied verbatim; no `static` string switch left in the new code.
4. `mvn -q -B clean test` passes; `python3 eval/validate_round.py` passes.
