package loan;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable level-payment amortization schedule. */
public final class AmortizationSchedule {
    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final BigDecimal MONTHS_PERCENT = BigDecimal.valueOf(1200);

    private final List<Installment> installments;
    private final BigDecimal totalInterest;

    private AmortizationSchedule(List<Installment> installments, BigDecimal totalInterest) {
        this.installments = List.copyOf(installments);
        this.totalInterest = totalInterest;
    }

    /** Builds the schedule for {@code loan}. */
    public static AmortizationSchedule of(Loan loan) {
        Objects.requireNonNull(loan, "loan");
        BigDecimal principal = loan.principal().setScale(SCALE, ROUNDING);
        int n = loan.months();
        MathContext mc = MathContext.DECIMAL128;
        BigDecimal r = loan.annualRatePercent().divide(MONTHS_PERCENT, mc);
        BigDecimal level = levelPayment(principal, r, n, mc);

        List<Installment> rows = new ArrayList<>(n);
        BigDecimal balance = principal;
        BigDecimal totalInterest = BigDecimal.ZERO.setScale(SCALE);
        for (int i = 0; i < n; i++) {
            BigDecimal interest = balance.multiply(r).setScale(SCALE, ROUNDING);
            boolean last = i == n - 1;
            BigDecimal payment = last ? balance.add(interest) : level;
            BigDecimal principalPart = payment.subtract(interest);
            balance = balance.subtract(principalPart);
            totalInterest = totalInterest.add(interest);
            rows.add(new Installment(i + 1, loan.firstPaymentDate().plusMonths(i), payment, interest,
                    principalPart, balance));
        }
        return new AmortizationSchedule(rows, totalInterest);
    }

    private static BigDecimal levelPayment(BigDecimal principal, BigDecimal r, int n, MathContext mc) {
        if (r.signum() == 0) {
            return principal.divide(BigDecimal.valueOf(n), SCALE, ROUNDING);
        }
        BigDecimal growth = BigDecimal.ONE.add(r).pow(n, mc);
        // P * r * g / (g - 1)  ==  P * r / (1 - g^-1)
        BigDecimal raw = principal.multiply(r, mc).multiply(growth, mc).divide(growth.subtract(BigDecimal.ONE), mc);
        return raw.setScale(SCALE, ROUNDING);
    }

    public List<Installment> installments() {
        return installments;
    }

    public BigDecimal totalInterest() {
        return totalInterest;
    }

    public LocalDate payoffDate() {
        return installments.get(installments.size() - 1).dueDate();
    }

    /** First installment due on or after {@code date}, or empty when the loan is already paid off by then. */
    public Optional<Installment> installmentDueOn(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return installments.stream().filter(i -> !i.dueDate().isBefore(date)).findFirst();
    }
}
