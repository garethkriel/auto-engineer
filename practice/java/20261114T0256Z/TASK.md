# Task 20261114T0256Z — BusinessCalendar (level 4: java.time, Optional, checked exception)

Overlap check: benchmark/java has j01-roman, j02-wordfreq, j03-lru, j04-csv, j05-intervals, j06-lcs, j07-expr, j08-graph, j09-bank, j10-counter, j11-streams, j12-fileindex. Date arithmetic over a calendar shares no core algorithm with any of them.

## Statement
Package `ae`. Immutable `BusinessCalendar`:
- `static BusinessCalendar of(Set<DayOfWeek> weekend, Collection<LocalDate> holidays)`; `static BusinessCalendar standard(Collection<LocalDate> holidays)` (Sat+Sun weekend). Null args -> NullPointerException; a weekend of all 7 days -> IllegalArgumentException. Inputs are defensively copied.
- `boolean isBusinessDay(LocalDate)`.
- `LocalDate addBusinessDays(LocalDate start, int n)`: n == 0 returns start unchanged (even if not a business day); n > 0 moves forward, n < 0 backward, counting only business days; result is always a business day when n != 0.
- `int businessDaysBetween(LocalDate from, LocalDate to)`: number of business days in the half-open range [from, to); negative of the reverse range's count when to < from; 0 when equal.
- `Optional<LocalDate> lastBusinessDayOfMonth(YearMonth)`: empty if every day of the month is a holiday/weekend.
- `static List<LocalDate> parseHolidays(List<String>) throws HolidayFormatException`: ISO dates (`2026-12-25`), blank lines and `#` comments ignored; checked `HolidayFormatException` reports the 1-based line number. Result is sorted, de-duplicated.

## Assumptions
Weekend/holidays are the only non-business days. Very large |n| is bounded by LocalDate's range (DateTimeException propagates).

## Success
`mvn -q -B test` passes in this folder.
