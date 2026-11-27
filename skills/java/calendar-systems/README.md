# calendar-systems

- **Description:** Calendar arithmetic over epoch days: proleptic Gregorian and Julian, Julian-to-Gregorian hybrid with the 1582-10-05..14 gap, tabular Islamic civil, month addition with clamping, whole-month differences, n-th weekday, ISO 8601 week dates and ordinal dates (strict parsers), and Easter (Western, Julian, Orthodox).
- **Language:** java (standard library only)
- **Tags:** calendar, date, julian-day, iso-week, computus, differential-testing, oracle
- **Source round:** practice/java/20261127T0356Z
- **Tests:** `mvn -q -B test` (34 tests: 4M-day sweep vs `LocalDate`, `GregorianCalendar` oracle sweeps, day-by-day walk tests, Gauss Easter oracle).

Key ideas: single pivot (epoch day via JDN); `lengthOfMonth` vs `maxDayNumber` differ in hybrid October 1582; a round-trip test alone cannot expose a wrong shared table, so month starts are checked for contiguity.
