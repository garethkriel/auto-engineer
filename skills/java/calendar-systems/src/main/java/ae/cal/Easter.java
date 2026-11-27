package ae.cal;

/** Computus: dates of Easter Sunday (Meeus/Jones/Butcher algorithms). */
public final class Easter {

    private Easter() {
    }

    /** Western (Gregorian) Easter as a Gregorian date; valid for years 1583..4099. */
    public static Ymd gregorian(long year) {
        if (year < 1583 || year > 4099) {
            throw new IllegalArgumentException("Gregorian Easter supported for 1583..4099: " + year);
        }
        long a = year % 19;
        long b = year / 100;
        long c = year % 100;
        long d = b / 4;
        long e = b % 4;
        long f = (b + 8) / 25;
        long g = (b - f + 1) / 3;
        long h = (19 * a + b - d - g + 15) % 30;
        long i = c / 4;
        long k = c % 4;
        long l = (32 + 2 * e + 2 * i - h - k) % 7;
        long m = (a + 11 * h + 22 * l) / 451;
        int month = (int) ((h + l - 7 * m + 114) / 31);
        int day = (int) ((h + l - 7 * m + 114) % 31) + 1;
        return new Ymd(year, month, day);
    }

    /** Easter in the Julian calendar (the date is a Julian-calendar date); valid for years &gt;= 326. */
    public static Ymd julian(long year) {
        if (year < 326 || year > Calendars.MAX_YEAR) {
            throw new IllegalArgumentException("Julian Easter supported for 326..1000000: " + year);
        }
        long a = year % 4;
        long b = year % 7;
        long c = year % 19;
        long d = (19 * c + 15) % 30;
        long e = (2 * a + 4 * b - d + 34) % 7;
        int month = (int) ((d + e + 114) / 31);
        int day = (int) ((d + e + 114) % 31) + 1;
        return new Ymd(year, month, day);
    }

    /** Orthodox Easter expressed as a Gregorian date; valid for years 326..1000000 (the Julian rule applies). */
    public static Ymd orthodox(long year) {
        return Calendars.convert(CalendarSystem.JULIAN, CalendarSystem.GREGORIAN, julian(year));
    }
}
