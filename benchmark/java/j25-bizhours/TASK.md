# j25-bizhours: Business-hours arithmetic

Java 21, standard library only. Put your solution under `bench-solutions/java/j25-bizhours/src/main/java/`, using exactly the package and public signatures below. CI adds the hidden JUnit 5 tests, and the task passes only if every one of them passes.

```java
package bench.bizhours;
public final class BusinessClock {
    public BusinessClock(java.time.ZoneId zone, java.util.Set<java.time.DayOfWeek> workdays,
                         java.time.LocalTime open, java.time.LocalTime close,
                         java.util.Set<java.time.LocalDate> holidays);
    public java.time.ZonedDateTime add(java.time.ZonedDateTime start, java.time.Duration work);
}
```

- **Business time:** the **real elapsed time** inside each business window.
  - A business window exists on every local date in `zone` whose day of week is in `workdays` and which isn't in `holidays`.
  - It runs from `ZonedDateTime.of(date, open, zone)` up to, but not including, `ZonedDateTime.of(date, close, zone)`.
  - Because of that conversion, an opening time inside a DST gap moves later by the gap's length, and a time inside a DST overlap uses the earlier offset. So a window can be shorter or longer than `close − open` on a transition day. For example, a 01:00–04:00 window in New York lasts 2 real hours on spring-forward day and 4 on fall-back day.
- **`add(start, work)`:**
  - It returns the instant at which `work` of business time has passed, counting from `start`, as a `ZonedDateTime` in `zone`, whatever zone `start` is in.
  - If `start` is outside business time, counting begins at the next window's opening.
  - Work that ends exactly at a window's close returns that close time, not the next opening.
  - Zero work returns `start` (converted to `zone`) when it's inside a window, and otherwise the next opening.
  - Sub-second precision is kept.
- **Copies:** the constructor copies `workdays` and `holidays`, so later changes to the caller's sets have no effect.
- **Errors:** each of these throws `IllegalArgumentException`:
  - a null argument;
  - empty `workdays`;
  - `open` not before `close`;
  - negative `work`.
- **Performance:** 10,000 hours of work across years of frequent holidays, or a gap of more than 13 years with no business days, must be computed within seconds.
