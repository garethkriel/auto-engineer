package bench.bizhours;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/** Adds amounts of real elapsed business time, honouring workdays, holidays and DST. */
public final class BusinessClock {
    private final ZoneId zone;
    private final Set<DayOfWeek> workdays;
    private final LocalTime open;
    private final LocalTime close;
    private final Set<LocalDate> holidays;

    public BusinessClock(ZoneId zone, Set<DayOfWeek> workdays, LocalTime open, LocalTime close,
                         Set<LocalDate> holidays) {
        if (zone == null || workdays == null || open == null || close == null || holidays == null) {
            throw new IllegalArgumentException("null argument");
        }
        if (workdays.isEmpty()) {
            throw new IllegalArgumentException("no workdays");
        }
        if (!open.isBefore(close)) {
            throw new IllegalArgumentException("open must be before close");
        }
        EnumSet<DayOfWeek> wd = EnumSet.noneOf(DayOfWeek.class);
        for (DayOfWeek d : workdays) {
            if (d == null) {
                throw new IllegalArgumentException("null workday");
            }
            wd.add(d);
        }
        Set<LocalDate> hs = new HashSet<>();
        for (LocalDate d : holidays) {
            if (d == null) {
                throw new IllegalArgumentException("null holiday");
            }
            hs.add(d);
        }
        this.zone = zone;
        this.workdays = wd;
        this.open = open;
        this.close = close;
        this.holidays = hs;
    }

    public ZonedDateTime add(ZonedDateTime start, Duration work) {
        if (start == null || work == null) {
            throw new IllegalArgumentException("null argument");
        }
        if (work.isNegative()) {
            throw new IllegalArgumentException("negative work");
        }
        ZonedDateTime cursor = start.withZoneSameInstant(zone);
        Duration remaining = work;
        // Start one day early in case a window shifted by a DST gap spills past midnight.
        LocalDate date = cursor.toLocalDate().minusDays(1);
        while (true) {
            if (workdays.contains(date.getDayOfWeek()) && !holidays.contains(date)) {
                ZonedDateTime ws = ZonedDateTime.of(date, open, zone);
                ZonedDateTime we = ZonedDateTime.of(date, close, zone);
                ZonedDateTime from = cursor.isAfter(ws) ? cursor : ws;
                if (from.isBefore(we)) {
                    Duration avail = Duration.between(from, we);
                    if (remaining.compareTo(avail) <= 0) {
                        return from.plus(remaining);
                    }
                    remaining = remaining.minus(avail);
                    cursor = we;
                }
            }
            date = date.plusDays(1);
        }
    }
}
