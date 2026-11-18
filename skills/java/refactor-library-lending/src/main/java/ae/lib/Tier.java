package ae.lib;

import java.util.Locale;
import java.util.Optional;

/** Membership tier: every per-tier constant lives here exactly once. */
enum Tier {
    STD(3, 14, 1, 25, 1000),
    PREM(6, 28, 2, 10, 1000),
    KID(2, 7, 0, 5, 300);

    final int loanLimit;
    final int loanDays;
    final int maxRenewals;
    final int finePerDay;
    final int fineCap;

    Tier(int loanLimit, int loanDays, int maxRenewals, int finePerDay, int fineCap) {
        this.loanLimit = loanLimit;
        this.loanDays = loanDays;
        this.maxRenewals = maxRenewals;
        this.finePerDay = finePerDay;
        this.fineCap = fineCap;
    }

    /** Parses "std"/"prem"/"kid", trimmed and case-insensitive. */
    static Optional<Tier> parse(String text) {
        if (text == null) return Optional.empty();
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "std" -> Optional.of(STD);
            case "prem" -> Optional.of(PREM);
            case "kid" -> Optional.of(KID);
            default -> Optional.empty();
        };
    }

    /** Fine for returning {@code lateDays} after the due day (0 when not late). */
    int fineFor(int lateDays) {
        if (lateDays <= 0) return 0;
        return (int) Math.min((long) lateDays * finePerDay, fineCap);
    }
}
