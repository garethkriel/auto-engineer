package bench.bizhours;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

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
        if (workdays.isEmpty() || !open.isBefore(close)) throw new IllegalArgumentException("invalid hours");
        for (DayOfWeek d : workdays) if (d == null) throw new IllegalArgumentException("null day");
        for (LocalDate d : holidays) if (d == null) throw new IllegalArgumentException("null holiday");
        this.zone = zone;
        this.workdays = Set.copyOf(workdays);
        this.open = open;
        this.close = close;
        this.holidays = Set.copyOf(holidays);
    }

    public ZonedDateTime add(ZonedDateTime start, Duration work) {
        if (start == null || work == null || work.isNegative()) throw new IllegalArgumentException("invalid argument");
        ZonedDateTime cursor = start.withZoneSameInstant(zone);
        Duration remaining = work;
        LocalDate date = cursor.toLocalDate();
        while (true) {
            if (workdays.contains(date.getDayOfWeek()) && !holidays.contains(date)) {
                ZonedDateTime wOpen = ZonedDateTime.of(date, open, zone);
                ZonedDateTime wClose = ZonedDateTime.of(date, close, zone);
                if (wClose.toInstant().isAfter(cursor.toInstant()) && wClose.toInstant().isAfter(wOpen.toInstant())) {
                    ZonedDateTime from = cursor.toInstant().isAfter(wOpen.toInstant()) ? cursor : wOpen;
                    Duration avail = Duration.between(from, wClose);
                    if (remaining.compareTo(avail) <= 0) {
                        return remaining.equals(avail) ? wClose : from.plus(remaining);
                    }
                    remaining = remaining.minus(avail);
                    cursor = wClose;
                }
            }
            date = date.plusDays(1);
        }
    }
}
