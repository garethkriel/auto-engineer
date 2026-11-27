package ae.cal;

import java.util.Objects;

/**
 * Pure calendar arithmetic over epoch days (days since 1970-01-01 Gregorian).
 *
 * <p>Conversions go through the Julian Day Number (JDN of the civil day, i.e. the noon-based integer), using the
 * classic Richards/Meeus integer algorithms with floor division so negative values work. Islamic dates use the
 * tabular 30-year cycle (leap years where {@code (14 + 11 * y) mod 30 < 11}).
 */
public final class Calendars {

    /** Largest absolute year accepted. */
    public static final long MAX_YEAR = 1_000_000L;

    private static final long JDN_OF_EPOCH = 2_440_588L;
    private static final long ISLAMIC_EPOCH_JDN = 1_948_440L;
    /** Epoch day of 1582-10-15 Gregorian, the first day of the Gregorian calendar in HYBRID. */
    private static final long CUTOVER = -141_427L;

    private Calendars() {
    }

    // ---------------------------------------------------------------- validation

    private static void checkYear(long year) {
        if (year < -MAX_YEAR || year > MAX_YEAR) {
            throw new IllegalArgumentException("year out of range: " + year);
        }
    }

    private static void checkMonth(int month) {
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("month out of range: " + month);
        }
    }

    /** Returns true when the year is a leap year in the given system (HYBRID: Julian rule before 1583). */
    public static boolean isLeapYear(CalendarSystem sys, long year) {
        Objects.requireNonNull(sys, "sys");
        checkYear(year);
        switch (sys) {
            case GREGORIAN:
                return gregorianLeap(year);
            case JULIAN:
                return Math.floorMod(year, 4L) == 0;
            case HYBRID:
                return year < 1583 ? Math.floorMod(year, 4L) == 0 : gregorianLeap(year);
            case ISLAMIC_CIVIL:
                return Math.floorMod(14 + 11 * year, 30L) < 11;
            default:
                throw new AssertionError(sys);
        }
    }

    private static boolean gregorianLeap(long year) {
        return Math.floorMod(year, 4L) == 0 && (Math.floorMod(year, 100L) != 0 || Math.floorMod(year, 400L) == 0);
    }

    /** Number of days in a month; HYBRID October 1582 has 21. */
    public static int lengthOfMonth(CalendarSystem sys, long year, int month) {
        Objects.requireNonNull(sys, "sys");
        checkYear(year);
        checkMonth(month);
        if (sys == CalendarSystem.ISLAMIC_CIVIL) {
            if (month == 12) {
                return isLeapYear(sys, year) ? 30 : 29;
            }
            return month % 2 == 1 ? 30 : 29;
        }
        if (sys == CalendarSystem.HYBRID && year == 1582 && month == 10) {
            return 21;
        }
        switch (month) {
            case 2:
                return isLeapYear(sys, year) ? 29 : 28;
            case 4:
            case 6:
            case 9:
            case 11:
                return 30;
            default:
                return 31;
        }
    }

    /** Largest day number of the month; differs from the length only for HYBRID October 1582 (31 vs 21). */
    private static int maxDayNumber(CalendarSystem sys, long year, int month) {
        if (sys == CalendarSystem.HYBRID && year == 1582 && month == 10) {
            return 31;
        }
        return lengthOfMonth(sys, year, month);
    }

    /** Number of days in a year. */
    public static int lengthOfYear(CalendarSystem sys, long year) {
        int total = 0;
        for (int m = 1; m <= 12; m++) {
            total += lengthOfMonth(sys, year, m);
        }
        return total;
    }

    /** Returns whether the date exists in the system. Never throws for non-null {@code sys}. */
    public static boolean isValid(CalendarSystem sys, long year, int month, int day) {
        Objects.requireNonNull(sys, "sys");
        if (year < -MAX_YEAR || year > MAX_YEAR || month < 1 || month > 12 || day < 1) {
            return false;
        }
        if (day > maxDayNumber(sys, year, month)) {
            return false;
        }
        return !(sys == CalendarSystem.HYBRID && year == 1582 && month == 10 && day > 4 && day < 15);
    }

    // ---------------------------------------------------------------- conversion

    /** Epoch day of a valid date. */
    public static long toEpochDay(CalendarSystem sys, Ymd date) {
        Objects.requireNonNull(date, "date");
        return toEpochDay(sys, date.year(), date.month(), date.day());
    }

    /** Epoch day of a valid date; throws {@link IllegalArgumentException} naming the date otherwise. */
    public static long toEpochDay(CalendarSystem sys, long year, int month, int day) {
        if (!isValid(sys, year, month, day)) {
            throw new IllegalArgumentException("invalid " + sys + " date " + year + "-" + month + "-" + day);
        }
        return jdn(sys, year, month, day) - JDN_OF_EPOCH;
    }

    private static long jdn(CalendarSystem sys, long y, int m, int d) {
        switch (sys) {
            case GREGORIAN:
                return gregorianJdn(y, m, d);
            case JULIAN:
                return julianJdn(y, m, d);
            case HYBRID:
                boolean gregorian = y > 1582 || (y == 1582 && (m > 10 || (m == 10 && d >= 15)));
                return gregorian ? gregorianJdn(y, m, d) : julianJdn(y, m, d);
            case ISLAMIC_CIVIL:
                return islamicJdn(y, m, d);
            default:
                throw new AssertionError(sys);
        }
    }

    private static long gregorianJdn(long y, int m, int d) {
        long a = (14 - m) / 12;
        long yy = y + 4800 - a;
        long mm = m + 12 * a - 3;
        return d + (153 * mm + 2) / 5 + 365 * yy + Math.floorDiv(yy, 4L) - Math.floorDiv(yy, 100L)
                + Math.floorDiv(yy, 400L) - 32045;
    }

    private static long julianJdn(long y, int m, int d) {
        long a = (14 - m) / 12;
        long yy = y + 4800 - a;
        long mm = m + 12 * a - 3;
        return d + (153 * mm + 2) / 5 + 365 * yy + Math.floorDiv(yy, 4L) - 32083;
    }

    private static long islamicJdn(long y, int m, int d) {
        // ceil(29.5 * (m - 1)) == (59 * (m - 1) + 1) / 2
        return d + (59L * (m - 1) + 1) / 2 + 354 * (y - 1) + Math.floorDiv(3 + 11 * y, 30L)
                + ISLAMIC_EPOCH_JDN - 1;
    }

    /** Date of an epoch day in the given system. Throws if the year would exceed {@link #MAX_YEAR}. */
    public static Ymd fromEpochDay(CalendarSystem sys, long epochDay) {
        Objects.requireNonNull(sys, "sys");
        if (epochDay < -400_000_000_000L || epochDay > 400_000_000_000L) {
            throw new IllegalArgumentException("epoch day out of range: " + epochDay);
        }
        long jdn = epochDay + JDN_OF_EPOCH;
        Ymd result;
        switch (sys) {
            case GREGORIAN:
                result = fromGregorianJdn(jdn);
                break;
            case JULIAN:
                result = fromJulianJdn(jdn);
                break;
            case HYBRID:
                result = epochDay >= CUTOVER ? fromGregorianJdn(jdn) : fromJulianJdn(jdn);
                break;
            case ISLAMIC_CIVIL:
                result = fromIslamicJdn(jdn);
                break;
            default:
                throw new AssertionError(sys);
        }
        if (result.year() < -MAX_YEAR || result.year() > MAX_YEAR) {
            throw new IllegalArgumentException("epoch day out of range: " + epochDay);
        }
        return result;
    }

    private static Ymd fromGregorianJdn(long jdn) {
        long a = jdn + 32044;
        long b = Math.floorDiv(4 * a + 3, 146097L);
        long c = a - Math.floorDiv(146097 * b, 4L);
        return finishRichards(100 * b, c);
    }

    private static Ymd fromJulianJdn(long jdn) {
        return finishRichards(0, jdn + 32082);
    }

    private static Ymd finishRichards(long centuryYears, long c) {
        long d = Math.floorDiv(4 * c + 3, 1461L);
        long e = c - Math.floorDiv(1461 * d, 4L);
        long m = (5 * e + 2) / 153;
        int day = (int) (e - (153 * m + 2) / 5 + 1);
        int month = (int) (m + 3 - 12 * (m / 10));
        long year = centuryYears + d - 4800 + m / 10;
        return new Ymd(year, month, day);
    }

    private static Ymd fromIslamicJdn(long jdn) {
        long year = Math.floorDiv(30 * (jdn - ISLAMIC_EPOCH_JDN) + 10646, 10631L);
        long yearStart = islamicJdn(year, 1, 1);
        // month = min(12, ceil((jdn - 29 - yearStart) / 29.5) + 1)
        long month = Math.min(12, Math.floorDiv(2 * (jdn - 29 - yearStart) + 58, 59L) + 1);
        int day = (int) (jdn - islamicJdn(year, (int) month, 1) + 1);
        return new Ymd(year, (int) month, day);
    }

    /** Converts a date between systems (the source date must be valid). */
    public static Ymd convert(CalendarSystem from, CalendarSystem to, Ymd date) {
        Objects.requireNonNull(to, "to");
        return fromEpochDay(to, toEpochDay(from, date));
    }

    /** ISO day of week: 1 = Monday .. 7 = Sunday. */
    public static int dayOfWeek(long epochDay) {
        return (int) Math.floorMod(epochDay + 3, 7L) + 1;
    }

    // ---------------------------------------------------------------- month arithmetic

    /** Adds months, clamping the day to the target month (and never landing in the HYBRID gap). */
    public static Ymd addMonths(CalendarSystem sys, Ymd date, long months) {
        Objects.requireNonNull(date, "date");
        toEpochDay(sys, date); // validates the start
        long total = Math.addExact(Math.multiplyExact(date.year(), 12L), date.month() - 1L);
        total = Math.addExact(total, months);
        long year = Math.floorDiv(total, 12L);
        int month = (int) Math.floorMod(total, 12L) + 1;
        checkYear(year);
        int day = Math.min(date.day(), maxDayNumber(sys, year, month));
        if (!isValid(sys, year, month, day)) {
            day = 4; // HYBRID 1582-10-05..14: the last real day before the gap
        }
        return new Ymd(year, month, day);
    }

    /** Whole months from {@code a} to {@code b}, truncated toward zero (as {@code ChronoUnit.MONTHS.between}). */
    public static long monthsBetween(CalendarSystem sys, Ymd a, Ymd b) {
        toEpochDay(sys, a);
        toEpochDay(sys, b);
        long total = (b.year() * 12 + b.month()) - (a.year() * 12 + a.month());
        long dayDiff = b.day() - a.day();
        if (total > 0 && dayDiff < 0) {
            total--;
        } else if (total < 0 && dayDiff > 0) {
            total++;
        }
        return total;
    }

    /**
     * The n-th given weekday of a month. {@code n >= 1} counts from the start, {@code n == -1} is the last.
     *
     * @param dow ISO day of week 1..7
     */
    public static Ymd nthWeekday(CalendarSystem sys, long year, int month, int n, int dow) {
        if (dow < 1 || dow > 7) {
            throw new IllegalArgumentException("dow out of range: " + dow);
        }
        if (n == 0 || n < -1) {
            throw new IllegalArgumentException("n must be >= 1 or -1: " + n);
        }
        int length = lengthOfMonth(sys, year, month);
        long first = toEpochDay(sys, year, month, 1);
        long last = first + length - 1; // real days only: the HYBRID gap is not counted in length
        long target;
        if (n > 0) {
            target = first + Math.floorMod(dow - dayOfWeek(first), 7) + 7L * (n - 1);
        } else {
            target = last - Math.floorMod(dayOfWeek(last) - dow, 7);
        }
        if (target > last || target < first) {
            throw new IllegalArgumentException("no such weekday: n=" + n + " dow=" + dow + " in " + year + "-" + month);
        }
        return fromEpochDay(sys, target);
    }
}
