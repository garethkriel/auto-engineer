# loan-amortization

- **Description:** Level-payment loan amortization schedule. `BigDecimal` money (scale 2, HALF_UP), last installment clears the exact remaining balance, month-end-safe due dates via `plusMonths(i)`, `Optional` lookup by date, validating factory that throws a checked `InvalidLoanException`.
- **Language:** Java 21
- **Tags:** BigDecimal, java.time, Optional, checked-exception, records, finance
- **Source round:** practice/java/20261113T2356Z
- **Test:** `mvn -q -B test` (10 tests)
