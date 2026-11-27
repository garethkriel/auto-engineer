# Task: ae.cal — calendar systems toolkit

Level 20 (capstone), topic: calendar arithmetic with independent oracles.

Overlap check (benchmark/java names read: j20-cron, j25-bizhours, j16-ranges, ... none is a calendar-conversion
library; existing skills `business-calendar`, `loan-amortization` use java.time rather than implementing
calendars). This task implements the calendars themselves.

## Design (package `ae.cal`, standard library only)
- Day numbers are **epoch days** (days since 1970-01-01 Gregorian, like `LocalDate.toEpochDay`).
- `CalendarSystem` enum: `GREGORIAN` (proleptic), `JULIAN` (proleptic), `HYBRID` (Julian before the
  1582-10-15 Gregorian cutover, Gregorian from it; 1582-10-05..14 do not exist), `ISLAMIC_CIVIL`
  (tabular, 30-year cycle with 11 leap years, epoch Friday 1 Muharram 1 = Julian 622-07-16).
- `Ymd(long year, int month, int day)` record, no validation of its own.
- Supported years: |year| <= 1_000_000; anything else is `IllegalArgumentException`.
- `Calendars` (static): `toEpochDay(sys, Ymd)`, `toEpochDay(sys,y,m,d)`, `fromEpochDay(sys, day)`, `isValid`,
  `isLeapYear`, `lengthOfMonth`, `lengthOfYear`, `convert(from,to,Ymd)`, `dayOfWeek(epochDay)` (1=Mon..7=Sun),
  `addMonths(sys, Ymd, months)` (day clamped to the target month length; HYBRID/others use sys rules),
  `monthsBetween(sys, a, b)` (whole elapsed months, truncated toward zero like `ChronoUnit.MONTHS.between`),
  `nthWeekday(sys, year, month, n, dow)` (n>=1 counts from month start, n=-1 last; IAE if it does not exist).
  Invalid dates throw `IllegalArgumentException`; failures have no side effects (all functions are pure).
- `IsoWeek`: `record WeekDate(long weekYear, int week, int day)`, `of(epochDay)`, `toEpochDay(WeekDate)`
  (validates week <= weeksInYear), `weeksInYear(weekYear)`, `format`/`parse` for `YYYY-Www-D` and basic
  `YYYYWwwD`, and `formatOrdinal(year, doy)`/`parseOrdinal` for `YYYY-DDD` (Gregorian). Years are exactly 4 digits.
- `Easter`: `gregorian(year)` (Gregorian date, years 1583..4099 valid), `julian(year)` (date in the Julian calendar,
  year >= 326), `orthodox(year)` (Julian Easter expressed as a Gregorian date).

## Success criteria
- Gregorian sweep of +-2,000,000 epoch days equals `java.time.LocalDate`; HYBRID and JULIAN equal
  `java.util.GregorianCalendar` (UTC, year >= 1) over years 1..3000.
- `addMonths`/`monthsBetween`/ISO week fields equal `java.time` on randomised input.
- Easter tables (literals) and ISLAMIC_CIVIL hand-computed literals pass; invalid dates are rejected with messages.
- `mvn -q -B test` passes; a small mutation pass kills every non-equivalent mutant.
