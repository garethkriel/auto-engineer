package bench.bizhours;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

/** Adds business time (real elapsed time inside daily business windows) to an instant. */
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
        try {
            this.workdays = Set.copyOf(workdays);
            this.holidays = Set.copyOf(holidays);
        } catch (NullPointerException e) {
            throw new IllegalArgumentException("null element", e);
        }
        if (this.workdays.isEmpty()) {
            throw new IllegalArgumentException("no workdays");
        }
        if (!open.isBefore(close)) {
            throw new IllegalArgumentException("open must be before close");
        }
        this.zone = zone;
        this.open = open;
        this.close = close;
    }

    public ZonedDateTime add(ZonedDateTime start, Duration work) {
        if (start == null || work == null) {
            throw new IllegalArgumentException("null argument");
        }
        if (work.isNegative()) {
            throw new IllegalArgumentException("negative work");
        }
        Instant cursor = start.toInstant();
        Duration remaining = work;
        LocalDate date = start.withZoneSameInstant(zone).toLocalDate().minusDays(1);
        while (true) {
            if (workdays.contains(date.getDayOfWeek()) && !holidays.contains(date)) {
                Instant ws = ZonedDateTime.of(date, open, zone).toInstant();
                Instant we = ZonedDateTime.of(date, close, zone).toInstant();
                if (we.isAfter(ws) && we.isAfter(cursor)) {
                    Instant eff = ws.isAfter(cursor) ? ws : cursor;
                    Duration avail = Duration.between(eff, we);
                    if (remaining.compareTo(avail) <= 0) {
                        return ZonedDateTime.ofInstant(eff.plus(remaining), zone);
                    }
                    remaining = remaining.minus(avail);
                    cursor = we;
                }
            }
            date = date.plusDays(1);
        }
    }
}
