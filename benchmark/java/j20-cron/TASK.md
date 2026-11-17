# j20-cron: Five-field cron schedule

Java 21, standard library only. Put your solution under `bench-solutions/java/j20-cron/src/main/java/`, using exactly the package and public signatures below. CI adds the hidden JUnit 5 tests, and the task passes only if every one of them passes.

```java
package bench.cron;
public final class CronSchedule {
    public static CronSchedule parse(String expression);
    public java.time.LocalDateTime next(java.time.LocalDateTime after);
}
```

**Expression:** five fields, separated by one or more spaces, with leading and trailing whitespace ignored:

| Field | Values |
|---|---|
| minute | 0–59 |
| hour | 0–23 |
| day-of-month | 1–31 |
| month | 1–12 |
| day-of-week | 0–7, where 0 and 7 are both Sunday |

Each field is a comma-separated list of items, and each item is one of:
- `*` (every value);
- `n` (a single number);
- `a-b` (a range, with `a ≤ b`);
- `*/s`, `a-b/s` or `a/s`. These step through the range in steps of `s ≥ 1`, and `a/s` means from `a` to the field's maximum.

There are no names (`JAN`, `MON`). Anything else throws `IllegalArgumentException`: the wrong number of fields, values out of range, a step of 0, `a > b`, empty list items, letters, a missing number, or null.

**Day matching (classic cron rule):** a field is unrestricted only if it is exactly `*`.
- If day-of-month and day-of-week are **both** restricted, a day matches when **either** one matches. For example, `0 12 13 * 5` fires on the 13th **and** on every Friday.
- If only one of them is restricted, only that one applies.

**`next(after)`:**
- It returns the earliest matching minute **strictly after** `after`, with seconds and nanoseconds zero.
- A null `after` throws `IllegalArgumentException`.
- If nothing matches within 8 years, as with `0 0 30 2 *`, it throws `IllegalStateException`.
- Repeated calls must be fast: 20,000 successive calls should take a few seconds at most.
