# business-calendar

- **Description:** Immutable business-day calendar (configurable weekend, holiday set): `isBusinessDay`, `addBusinessDays` (negative allowed, 0 = identity), half-open `businessDaysBetween`, `Optional` `lastBusinessDayOfMonth`, and a holiday-file parser throwing a checked `HolidayFormatException` with a line number.
- **Language:** java (21)
- **Tags:** java.time, LocalDate, DayOfWeek, YearMonth, Optional, checked-exception, defensive-copy, immutable
- **Source round:** practice/java/20261114T0256Z
- **Tests:** `mvn -q -B test` (32 tests)

Notes: `addBusinessDays` loops day by day (fine for realistic ranges); count in `long` because `Math.abs(Integer.MIN_VALUE)` overflows.
