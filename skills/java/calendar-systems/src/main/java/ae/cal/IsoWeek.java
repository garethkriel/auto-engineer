package ae.cal;

import java.util.Objects;

/** ISO 8601 week dates and ordinal dates (Gregorian). */
public final class IsoWeek {

    /**
     * An ISO week date.
     *
     * @param weekYear week-based year
     * @param week     week number, 1..52/53
     * @param day      day of week, 1 = Monday .. 7 = Sunday
     */
    public record WeekDate(long weekYear, int week, int day) {
    }

    private IsoWeek() {
    }

    private static long gregorian(long y, int m, int d) {
        return Calendars.toEpochDay(CalendarSystem.GREGORIAN, y, m, d);
    }

    /** Number of ISO weeks (52 or 53) in a week-based year: 53 iff 1 Jan is a Thursday, or a Wednesday in a leap year. */
    public static int weeksInYear(long weekYear) {
        int jan1 = Calendars.dayOfWeek(gregorian(weekYear, 1, 1));
        boolean leap = Calendars.isLeapYear(CalendarSystem.GREGORIAN, weekYear);
        return jan1 == 4 || (leap && jan1 == 3) ? 53 : 52;
    }

    /** Week date of an epoch day. */
    public static WeekDate of(long epochDay) {
        int dow = Calendars.dayOfWeek(epochDay);
        long thursday = epochDay - (dow - 1) + 3; // the Thursday of this ISO week decides the week-year
        long year = Calendars.fromEpochDay(CalendarSystem.GREGORIAN, thursday).year();
        int week = (int) ((thursday - gregorian(year, 1, 1)) / 7) + 1;
        return new WeekDate(year, week, dow);
    }

    /** Epoch day of a week date; rejects week 53 in 52-week years. */
    public static long toEpochDay(WeekDate w) {
        Objects.requireNonNull(w, "w");
        if (w.day() < 1 || w.day() > 7) {
            throw new IllegalArgumentException("day of week out of range in " + w);
        }
        if (w.week() < 1 || w.week() > weeksInYear(w.weekYear())) {
            throw new IllegalArgumentException("week out of range in " + w);
        }
        long jan4 = gregorian(w.weekYear(), 1, 4);
        long week1Monday = jan4 - (Calendars.dayOfWeek(jan4) - 1);
        return week1Monday + 7L * (w.week() - 1) + (w.day() - 1);
    }

    /** Formats as {@code YYYY-Www-D} (extended) or {@code YYYYWwwD} (basic); year must be 0..9999. */
    public static String format(WeekDate w, boolean extended) {
        Objects.requireNonNull(w, "w");
        toEpochDay(w);
        if (w.weekYear() < 0 || w.weekYear() > 9999) {
            throw new IllegalArgumentException("year must have 4 digits: " + w);
        }
        String sep = extended ? "-" : "";
        return String.format("%04d%sW%02d%s%d", w.weekYear(), sep, w.week(), sep, w.day());
    }

    private static boolean digits(String s, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** Parses extended or basic week dates strictly (no whitespace, 4-digit year, exact widths). */
    public static WeekDate parse(String text) {
        Objects.requireNonNull(text, "text");
        WeekDate w;
        if (text.length() == 10 && text.charAt(4) == '-' && text.charAt(5) == 'W' && text.charAt(8) == '-'
                && digits(text, 0, 4) && digits(text, 6, 8) && digits(text, 9, 10)) {
            w = new WeekDate(Integer.parseInt(text.substring(0, 4)), Integer.parseInt(text.substring(6, 8)),
                    text.charAt(9) - '0');
        } else if (text.length() == 8 && text.charAt(4) == 'W' && digits(text, 0, 4) && digits(text, 5, 8)) {
            w = new WeekDate(Integer.parseInt(text.substring(0, 4)), Integer.parseInt(text.substring(5, 7)),
                    text.charAt(7) - '0');
        } else {
            throw new IllegalArgumentException("malformed ISO week date: '" + text + "'");
        }
        try {
            toEpochDay(w);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid ISO week date '" + text + "': " + e.getMessage(), e);
        }
        return w;
    }

    /** Formats a Gregorian date as {@code YYYY-DDD}; the year must be 0..9999. */
    public static String formatOrdinal(long epochDay) {
        Ymd d = Calendars.fromEpochDay(CalendarSystem.GREGORIAN, epochDay);
        if (d.year() < 0 || d.year() > 9999) {
            throw new IllegalArgumentException("year must have 4 digits: " + d);
        }
        long doy = epochDay - gregorian(d.year(), 1, 1) + 1;
        return String.format("%04d-%03d", d.year(), doy);
    }

    /** Parses {@code YYYY-DDD} strictly. */
    public static long parseOrdinal(String text) {
        Objects.requireNonNull(text, "text");
        if (text.length() != 8 || text.charAt(4) != '-' || !digits(text, 0, 4) || !digits(text, 5, 8)) {
            throw new IllegalArgumentException("malformed ordinal date: '" + text + "'");
        }
        int year = Integer.parseInt(text.substring(0, 4));
        int doy = Integer.parseInt(text.substring(5, 8));
        if (doy < 1 || doy > Calendars.lengthOfYear(CalendarSystem.GREGORIAN, year)) {
            throw new IllegalArgumentException("day of year out of range: '" + text + "'");
        }
        return gregorian(year, 1, 1) + doy - 1;
    }
}
