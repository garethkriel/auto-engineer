# Practice 20261113T2356Z — Loan amortization schedule (level 4)

Topics: custom checked exception, `Optional`, `java.time`, `BigDecimal`.

## Task
Package `loan`:
- `InvalidLoanException` (checked) thrown for bad input.
- `Loan` record(`BigDecimal principal`, `BigDecimal annualRatePercent`, `int months`, `LocalDate firstPaymentDate`) with factory `Loan.of(...)` that throws `InvalidLoanException` when principal <= 0, rate < 0, months < 1, or any argument is null.
- `Installment` record(`int number`, `LocalDate dueDate`, `BigDecimal payment`, `BigDecimal interest`, `BigDecimal principal`, `BigDecimal balance`).
- `AmortizationSchedule` (immutable): `AmortizationSchedule.of(Loan)`, `installments()` (unmodifiable list), `totalInterest()`, `payoffDate()`, `installmentDueOn(LocalDate)` -> `Optional<Installment>` (first installment due on or after the date, empty if after the last).

## Rules / assumptions
- Money is rounded to 2 dp, `RoundingMode.HALF_UP`. Level payment = P*r/(1-(1+r)^-n), r = annualRate/1200, rounded; zero rate => P/n rounded.
- Interest each month = previous balance * r rounded. The final installment pays off the remaining balance exactly (payment = balance + interest).
- Due date i (0-based) = `firstPaymentDate.plusMonths(i)` (day clamped to month end, no drift).

## Success
`mvn -q -B test` passes; tests written first.
