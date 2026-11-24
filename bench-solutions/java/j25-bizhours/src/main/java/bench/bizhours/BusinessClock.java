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

/** Adds business time (real elapsed time inside business windows) to an instant. */
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
        EnumSet<DayOfWeek> w = EnumSet.noneOf(DayOfWeek.class);
        for (DayOfWeek d : workdays) {
            if (d == null) {
                throw new IllegalArgumentException("null workday");
            }
            w.add(d);
        }
        HashSet<LocalDate> h = new HashSet<>();
        for (LocalDate d : holidays) {
            if (d == null) {
                throw new IllegalArgumentException("null holiday");
            }
            h.add(d);
        }
        this.zone = zone;
        this.workdays = w;
        this.open = open;
        this.close = close;
        this.holidays = h;
    }

    public ZonedDateTime add(ZonedDateTime start, Duration work) {
        if (start == null || work == null || work.isNegative()) {
            throw new IllegalArgumentException("bad argument");
        }
        ZonedDateTime local = start.withZoneSameInstant(zone);
        Instant t = local.toInstant();
        Duration remaining = work;
        LocalDate date = local.toLocalDate().minusDays(1);
        while (true) {
            if (workdays.contains(date.getDayOfWeek()) && !holidays.contains(date)) {
                Instant ws = ZonedDateTime.of(date, open, zone).toInstant();
                Instant we = ZonedDateTime.of(date, close, zone).toInstant();
                Instant s = ws.isAfter(t) ? ws : t;
                if (s.isBefore(we)) {
                    Duration avail = Duration.between(s, we);
                    if (remaining.compareTo(avail) <= 0) {
                        return ZonedDateTime.ofInstant(s.plus(remaining), zone);
                    }
                    remaining = remaining.minus(avail);
                    t = we;
                }
            }
            date = date.plusDays(1);
        }
    }
}
