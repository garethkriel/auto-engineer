package bench.bizhours;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Set;

/** Adds working time, measured in real elapsed time inside business windows. */
public final class BusinessClock {
    private final ZoneId zone;
    private final Set<DayOfWeek> workdays;
    private final LocalTime open;
    private final LocalTime close;
    private final Set<LocalDate> holidays;

    public BusinessClock(ZoneId zone, Set<DayOfWeek> workdays, LocalTime open, LocalTime close, Set<LocalDate> holidays) {
        if (zone == null || workdays == null || open == null || close == null || holidays == null) {
            throw new IllegalArgumentException("null argument");
        }
        Set<DayOfWeek> wd = new HashSet<>();
        for (DayOfWeek d : workdays) {
            if (d == null) throw new IllegalArgumentException("null workday");
            wd.add(d);
        }
        Set<LocalDate> hd = new HashSet<>();
        for (LocalDate d : holidays) {
            if (d == null) throw new IllegalArgumentException("null holiday");
            hd.add(d);
        }
        if (wd.isEmpty()) throw new IllegalArgumentException("no workdays");
        if (!open.isBefore(close)) throw new IllegalArgumentException("open must be before close");
        this.zone = zone;
        this.workdays = wd;
        this.open = open;
        this.close = close;
        this.holidays = hd;
    }

    public ZonedDateTime add(ZonedDateTime start, Duration work) {
        if (start == null || work == null || work.isNegative()) throw new IllegalArgumentException("bad argument");
        ZonedDateTime cur = start.withZoneSameInstant(zone);
        Duration remaining = work;
        LocalDate date = cur.toLocalDate().minusDays(1);
        while (true) {
            if (workdays.contains(date.getDayOfWeek()) && !holidays.contains(date)) {
                ZonedDateTime ws = ZonedDateTime.of(date, open, zone);
                ZonedDateTime we = ZonedDateTime.of(date, close, zone);
                if (we.isAfter(cur) && we.isAfter(ws)) {
                    ZonedDateTime eff = ws.isAfter(cur) ? ws : cur;
                    Duration avail = Duration.between(eff, we);
                    if (remaining.compareTo(avail) <= 0) {
                        return eff.plus(remaining);
                    }
                    remaining = remaining.minus(avail);
                    cur = we;
                }
            }
            date = date.plusDays(1);
        }
    }
}
