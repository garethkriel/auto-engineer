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

/** Adds business time (real elapsed time inside daily windows) to a start instant. */
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
        EnumSet<DayOfWeek> wd = EnumSet.noneOf(DayOfWeek.class);
        for (DayOfWeek d : workdays) {
            if (d == null) {
                throw new IllegalArgumentException("null workday");
            }
            wd.add(d);
        }
        if (wd.isEmpty()) {
            throw new IllegalArgumentException("no workdays");
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
        if (start == null || work == null || work.isNegative()) {
            throw new IllegalArgumentException("bad argument");
        }
        ZonedDateTime s = start.withZoneSameInstant(zone);
        Duration remaining = work;
        LocalDate date = s.toLocalDate();
        while (true) {
            if (workdays.contains(date.getDayOfWeek()) && !holidays.contains(date)) {
                ZonedDateTime o = ZonedDateTime.of(date, open, zone);
                ZonedDateTime c = ZonedDateTime.of(date, close, zone);
                ZonedDateTime from = o.isBefore(s) ? s : o;
                if (from.isBefore(c)) {
                    Duration avail = Duration.between(from, c);
                    if (remaining.compareTo(avail) <= 0) {
                        return from.plus(remaining);
                    }
                    remaining = remaining.minus(avail);
                }
            }
            date = date.plusDays(1);
        }
    }
}
