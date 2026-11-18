package bench.bizhours;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/** Adds amounts of business time (real elapsed time inside daily windows) to instants. */
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
        Set<DayOfWeek> wd = EnumSet.noneOf(DayOfWeek.class);
        for (DayOfWeek d : workdays) {
            if (d == null) {
                throw new IllegalArgumentException("null workday");
            }
            wd.add(d);
        }
        if (wd.isEmpty()) {
            throw new IllegalArgumentException("workdays must not be empty");
        }
        if (!open.isBefore(close)) {
            throw new IllegalArgumentException("open must be before close");
        }
        Set<LocalDate> hs = new HashSet<>();
        for (LocalDate h : holidays) {
            if (h == null) {
                throw new IllegalArgumentException("null holiday");
            }
            hs.add(h);
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
        Instant t = start.toInstant();
        Duration remaining = work;
        // start one day early: a window is anchored to its local date, offsets may shift instants
        LocalDate date = start.withZoneSameInstant(zone).toLocalDate().minusDays(1);
        while (true) {
            if (workdays.contains(date.getDayOfWeek()) && !holidays.contains(date)) {
                Instant s = ZonedDateTime.of(date, open, zone).toInstant();
                Instant e = ZonedDateTime.of(date, close, zone).toInstant();
                Instant from = s.isBefore(t) ? t : s;
                if (from.isBefore(e)) {
                    Duration avail = Duration.between(from, e);
                    if (remaining.compareTo(avail) <= 0) {
                        return ZonedDateTime.ofInstant(from.plus(remaining), zone);
                    }
                    remaining = remaining.minus(avail);
                    t = e;
                }
            }
            date = date.plusDays(1);
        }
    }
}
