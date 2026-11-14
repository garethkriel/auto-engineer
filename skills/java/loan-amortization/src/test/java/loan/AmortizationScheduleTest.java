package loan;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AmortizationScheduleTest {

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private static AmortizationSchedule schedule(String p, String rate, int months, LocalDate first)
            throws InvalidLoanException {
        return AmortizationSchedule.of(Loan.of(bd(p), bd(rate), months, first));
    }

    @Test
    void standardLoanFirstInstallment() throws Exception {
        AmortizationSchedule s = schedule("10000", "12", 12, LocalDate.of(2026, 1, 15));
        Installment first = s.installments().get(0);
        assertEquals(1, first.number());
        assertEquals(bd("888.49"), first.payment());
        assertEquals(bd("100.00"), first.interest());
        assertEquals(bd("788.49"), first.principal());
        assertEquals(bd("9211.51"), first.balance());
    }

    @Test
    void balanceReachesExactlyZeroAndPrincipalSumsToLoan() throws Exception {
        AmortizationSchedule s = schedule("250000", "6.5", 360, LocalDate.of(2026, 3, 1));
        List<Installment> list = s.installments();
        assertEquals(360, list.size());
        assertEquals(0, list.get(359).balance().compareTo(BigDecimal.ZERO));
        BigDecimal sum = list.stream().map(Installment::principal).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(bd("250000.00"), sum);
    }

    @Test
    void balanceNeverIncreases() throws Exception {
        AmortizationSchedule s = schedule("5000", "9.9", 24, LocalDate.of(2026, 1, 1));
        BigDecimal prev = bd("5000");
        for (Installment i : s.installments()) {
            assertTrue(i.balance().compareTo(prev) < 0, "balance must fall at " + i.number());
            prev = i.balance();
        }
    }

    @Test
    void totalInterestEqualsSumOfInterest() throws Exception {
        AmortizationSchedule s = schedule("10000", "12", 12, LocalDate.of(2026, 1, 15));
        BigDecimal sum = s.installments().stream().map(Installment::interest)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(sum, s.totalInterest());
        // total paid - principal
        BigDecimal paid = s.installments().stream().map(Installment::payment)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(paid.subtract(bd("10000")), s.totalInterest());
    }

    @Test
    void zeroRateSplitsEvenlyAndLastAbsorbsRounding() throws Exception {
        AmortizationSchedule s = schedule("1000", "0", 3, LocalDate.of(2026, 1, 1));
        List<Installment> l = s.installments();
        assertEquals(bd("333.33"), l.get(0).payment());
        assertEquals(bd("333.33"), l.get(1).payment());
        assertEquals(bd("333.34"), l.get(2).payment());
        assertEquals(bd("0.00"), s.totalInterest());
    }

    @Test
    void dueDatesClampToMonthEndWithoutDrift() throws Exception {
        AmortizationSchedule s = schedule("300", "0", 3, LocalDate.of(2026, 1, 31));
        assertEquals(LocalDate.of(2026, 1, 31), s.installments().get(0).dueDate());
        assertEquals(LocalDate.of(2026, 2, 28), s.installments().get(1).dueDate());
        assertEquals(LocalDate.of(2026, 3, 31), s.installments().get(2).dueDate());
        assertEquals(LocalDate.of(2026, 3, 31), s.payoffDate());
    }

    @Test
    void installmentDueOnFindsFirstOnOrAfter() throws Exception {
        AmortizationSchedule s = schedule("300", "0", 3, LocalDate.of(2026, 1, 31));
        assertEquals(1, s.installmentDueOn(LocalDate.of(2025, 12, 1)).orElseThrow().number());
        assertEquals(2, s.installmentDueOn(LocalDate.of(2026, 2, 1)).orElseThrow().number());
        assertEquals(2, s.installmentDueOn(LocalDate.of(2026, 2, 28)).orElseThrow().number());
        assertEquals(3, s.installmentDueOn(LocalDate.of(2026, 3, 1)).orElseThrow().number());
        assertEquals(Optional.empty(), s.installmentDueOn(LocalDate.of(2026, 4, 1)));
    }

    @Test
    void installmentListIsUnmodifiable() throws Exception {
        AmortizationSchedule s = schedule("300", "0", 3, LocalDate.of(2026, 1, 1));
        assertThrows(UnsupportedOperationException.class, () -> s.installments().clear());
    }

    @Test
    void singleMonthLoan() throws Exception {
        AmortizationSchedule s = schedule("1000", "12", 1, LocalDate.of(2026, 5, 5));
        Installment only = s.installments().get(0);
        assertEquals(bd("1010.00"), only.payment());
        assertEquals(bd("10.00"), only.interest());
        assertEquals(bd("0.00"), only.balance());
    }

    @Test
    void invalidLoansAreRejectedWithCheckedException() {
        LocalDate d = LocalDate.of(2026, 1, 1);
        assertThrows(InvalidLoanException.class, () -> Loan.of(bd("0"), bd("5"), 12, d));
        assertThrows(InvalidLoanException.class, () -> Loan.of(bd("-1"), bd("5"), 12, d));
        assertThrows(InvalidLoanException.class, () -> Loan.of(bd("100"), bd("-0.1"), 12, d));
        assertThrows(InvalidLoanException.class, () -> Loan.of(bd("100"), bd("5"), 0, d));
        assertThrows(InvalidLoanException.class, () -> Loan.of(null, bd("5"), 12, d));
        assertThrows(InvalidLoanException.class, () -> Loan.of(bd("100"), null, 12, d));
        assertThrows(InvalidLoanException.class, () -> Loan.of(bd("100"), bd("5"), 12, null));
        assertTrue(Exception.class.isAssignableFrom(InvalidLoanException.class));
        assertFalse(RuntimeException.class.isAssignableFrom(InvalidLoanException.class));
    }
}
