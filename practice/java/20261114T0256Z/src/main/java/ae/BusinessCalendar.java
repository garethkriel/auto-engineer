package ae;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Immutable calendar that knows which days are business days (not weekend, not holiday). */
public final class BusinessCalendar {
    private final Set<DayOfWeek> weekend;
    private final Set<LocalDate> holidays;

    private BusinessCalendar(Set<DayOfWeek> weekend, Set<LocalDate> holidays) {
        this.weekend = weekend;
        this.holidays = holidays;
    }

    /** Creates a calendar; inputs are copied. A weekend of all seven days is rejected. */
    public static BusinessCalendar of(Set<DayOfWeek> weekend, Collection<LocalDate> holidays) {
        Objects.requireNonNull(weekend, "weekend");
        Objects.requireNonNull(holidays, "holidays");
        Set<DayOfWeek> w = weekend.isEmpty() ? EnumSet.noneOf(DayOfWeek.class) : EnumSet.copyOf(weekend);
        if (w.size() == DayOfWeek.values().length) {
            throw new IllegalArgumentException("weekend must leave at least one working weekday");
        }
        Set<LocalDate> h = new TreeSet<>();
        for (LocalDate d : holidays) {
            h.add(Objects.requireNonNull(d, "holiday"));
        }
        return new BusinessCalendar(Set.copyOf(w), Set.copyOf(h));
    }

    /** Saturday and Sunday are the weekend. */
    public static BusinessCalendar standard(Collection<LocalDate> holidays) {
        return of(EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), holidays);
    }

    public boolean isBusinessDay(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return !weekend.contains(date.getDayOfWeek()) && !holidays.contains(date);
    }

    /**
     * Moves {@code n} business days from {@code start} (backwards if negative). Zero returns
     * {@code start} unchanged. Throws {@link java.time.DateTimeException} past LocalDate's range.
     */
    public LocalDate addBusinessDays(LocalDate start, int n) {
        Objects.requireNonNull(start, "start");
        int step = n < 0 ? -1 : 1;
        // Math.abs(Integer.MIN_VALUE) overflows, so count in long.
        long remaining = Math.abs((long) n);
        LocalDate d = start;
        while (remaining > 0) {
            d = d.plusDays(step);
            if (isBusinessDay(d)) {
                remaining--;
            }
        }
        return d;
    }

    /** Business days in the half-open range [from, to); negative when {@code to} is earlier. */
    public int businessDaysBetween(LocalDate from, LocalDate to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (to.isBefore(from)) {
            return -businessDaysBetween(to, from);
        }
        int count = 0;
        for (LocalDate d = from; d.isBefore(to); d = d.plusDays(1)) {
            if (isBusinessDay(d)) {
                count++;
            }
        }
        return count;
    }

    public Optional<LocalDate> lastBusinessDayOfMonth(YearMonth month) {
        Objects.requireNonNull(month, "month");
        for (int day = month.lengthOfMonth(); day >= 1; day--) {
            LocalDate d = month.atDay(day);
            if (isBusinessDay(d)) {
                return Optional.of(d);
            }
        }
        return Optional.empty();
    }

    /** Parses ISO dates one per line; blank lines and {@code #} comments are skipped. */
    public static List<LocalDate> parseHolidays(List<String> lines) throws HolidayFormatException {
        Objects.requireNonNull(lines, "lines");
        Set<LocalDate> out = new TreeSet<>();
        int lineNo = 0;
        for (String raw : lines) {
            lineNo++;
            String line = Objects.requireNonNull(raw, "line").strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            try {
                out.add(LocalDate.parse(line));
            } catch (DateTimeParseException e) {
                throw new HolidayFormatException(lineNo, line, e);
            }
        }
        return List.copyOf(out);
    }
}
