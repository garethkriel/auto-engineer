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

/** Adds business time (real elapsed time inside daily windows) to an instant. */
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
        HashSet<LocalDate> hs = new HashSet<>();
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
        Instant s = start.toInstant();
        LocalDate d = start.withZoneSameInstant(zone).toLocalDate().minusDays(1);
        Duration rem = work;
        while (true) {
            if (workdays.contains(d.getDayOfWeek()) && !holidays.contains(d)) {
                Instant wo = ZonedDateTime.of(d, open, zone).toInstant();
                Instant wc = ZonedDateTime.of(d, close, zone).toInstant();
                Instant eff = s.isAfter(wo) ? s : wo;
                if (eff.isBefore(wc)) {
                    Duration avail = Duration.between(eff, wc);
                    if (rem.compareTo(avail) <= 0) {
                        return eff.plus(rem).atZone(zone);
                    }
                    rem = rem.minus(avail);
                }
            }
            d = d.plusDays(1);
        }
    }
}
