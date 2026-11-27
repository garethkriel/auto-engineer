package ae.cal;

import static ae.cal.CalendarSystem.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Random;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

/** Differential tests against java.time (Gregorian) and java.util.GregorianCalendar (Julian, hybrid). */
class OracleTest {

    private static final long MILLIS_PER_DAY = 86_400_000L;

    @Test
    void gregorianSweepMatchesLocalDate() {
        for (long day = -2_000_000; day <= 2_000_000; day++) {
            LocalDate ld = LocalDate.ofEpochDay(day);
            Ymd got = Calendars.fromEpochDay(GREGORIAN, day);
            if (got.year() != ld.getYear() || got.month() != ld.getMonthValue() || got.day() != ld.getDayOfMonth()) {
                fail("epoch day " + day + ": expected " + ld + " got " + got);
            }
            assertEquals(day, Calendars.toEpochDay(GREGORIAN, got), "round trip " + day);
            assertEquals(ld.getDayOfWeek().getValue(), Calendars.dayOfWeek(day), "dow " + ld);
        }
    }

    @Test
    void lengthsAndLeapMatchJavaTime() {
        for (int y = -5000; y <= 5000; y++) {
            assertEquals(java.time.Year.isLeap(y), Calendars.isLeapYear(GREGORIAN, y), "leap " + y);
            assertEquals(java.time.Year.of(y).length(), Calendars.lengthOfYear(GREGORIAN, y), "len " + y);
            for (int m = 1; m <= 12; m++) {
                assertEquals(java.time.YearMonth.of(y, m).lengthOfMonth(), Calendars.lengthOfMonth(GREGORIAN, y, m));
            }
        }
    }

    private static GregorianCalendar calendar(boolean pureJulian) {
        GregorianCalendar gc = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        gc.setGregorianChange(new Date(pureJulian ? Long.MAX_VALUE : -12219292800000L)); // default cutover
        gc.setLenient(false);
        return gc;
    }

    private void sweepAgainstGregorianCalendar(CalendarSystem sys, boolean pureJulian) {
        GregorianCalendar gc = calendar(pureJulian);
        long first = Calendars.toEpochDay(sys, 1, 1, 1);
        long last = Calendars.toEpochDay(sys, 3000, 12, 31);
        for (long day = first; day <= last; day++) {
            gc.setTimeInMillis(day * MILLIS_PER_DAY);
            assertEquals(GregorianCalendar.AD, gc.get(java.util.Calendar.ERA), "era " + day);
            Ymd got = Calendars.fromEpochDay(sys, day);
            long y = gc.get(java.util.Calendar.YEAR);
            int m = gc.get(java.util.Calendar.MONTH) + 1;
            int d = gc.get(java.util.Calendar.DAY_OF_MONTH);
            if (got.year() != y || got.month() != m || got.day() != d) {
                fail(sys + " epoch day " + day + ": expected " + y + "-" + m + "-" + d + " got " + got);
            }
            if (Calendars.toEpochDay(sys, got) != day) {
                fail(sys + " round trip " + day);
            }
        }
    }

    @Test
    void julianSweepMatchesGregorianCalendar() {
        sweepAgainstGregorianCalendar(JULIAN, true);
    }

    @Test
    void hybridSweepMatchesGregorianCalendar() {
        sweepAgainstGregorianCalendar(HYBRID, false);
    }

    @Test
    void hybridAndJulianLeapAndLengthsMatchGregorianCalendar() {
        GregorianCalendar hybrid = calendar(false);
        GregorianCalendar julian = calendar(true);
        for (int y = 1; y <= 3000; y++) {
            assertEquals(hybrid.isLeapYear(y), Calendars.isLeapYear(HYBRID, y), "hybrid leap " + y);
            assertEquals(julian.isLeapYear(y), Calendars.isLeapYear(JULIAN, y), "julian leap " + y);
        }
        assertEquals(355, Calendars.lengthOfYear(HYBRID, 1582));
        assertEquals(366, Calendars.lengthOfYear(HYBRID, 1600));
    }

    @Test
    void addMonthsAndMonthsBetweenMatchJavaTime() {
        Random rnd = new Random(20261127L);
        for (int i = 0; i < 200_000; i++) {
            LocalDate a = LocalDate.ofEpochDay(rnd.nextInt(2_000_000) - 700_000);
            long months = rnd.nextInt(6001) - 3000;
            Ymd start = new Ymd(a.getYear(), a.getMonthValue(), a.getDayOfMonth());
            LocalDate expected = a.plusMonths(months);
            Ymd got = Calendars.addMonths(GREGORIAN, start, months);
            assertEquals(new Ymd(expected.getYear(), expected.getMonthValue(), expected.getDayOfMonth()), got,
                    a + " + " + months);
            LocalDate b = LocalDate.ofEpochDay(a.toEpochDay() + rnd.nextInt(40_000) - 20_000);
            Ymd end = new Ymd(b.getYear(), b.getMonthValue(), b.getDayOfMonth());
            assertEquals(ChronoUnit.MONTHS.between(a, b), Calendars.monthsBetween(GREGORIAN, start, end),
                    a + " .. " + b);
        }
    }

    @Test
    void addMonthsWithEveryDayOfMonthMatchesJavaTime() {
        for (int day = 1; day <= 31; day++) {
            for (int m = 1; m <= 12; m++) {
                if (day > java.time.YearMonth.of(2023, m).lengthOfMonth()) {
                    continue;
                }
                LocalDate a = LocalDate.of(2023, m, day);
                for (int add = -30; add <= 30; add++) {
                    LocalDate e = a.plusMonths(add);
                    assertEquals(new Ymd(e.getYear(), e.getMonthValue(), e.getDayOfMonth()),
                            Calendars.addMonths(GREGORIAN, new Ymd(2023, m, day), add), a + " + " + add);
                }
            }
        }
    }

    @Test
    void isoWeekFieldsMatchJavaTime() {
        for (long day = -400_000; day <= 400_000; day++) {
            LocalDate ld = LocalDate.ofEpochDay(day);
            IsoWeek.WeekDate w = IsoWeek.of(day);
            if (w.weekYear() != ld.get(IsoFields.WEEK_BASED_YEAR)
                    || w.week() != ld.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
                    || w.day() != ld.getDayOfWeek().getValue()) {
                fail(ld + " expected " + ld.get(IsoFields.WEEK_BASED_YEAR) + "-W"
                        + ld.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) + "-" + ld.getDayOfWeek().getValue() + " got " + w);
            }
            assertEquals(day, IsoWeek.toEpochDay(w), "round trip " + ld);
        }
    }

    @Test
    void weeksInYearMatchesJavaTime() {
        for (int y = -3000; y <= 6000; y++) {
            LocalDate dec28 = LocalDate.of(y, 12, 28); // always in the last week of its week-year
            assertEquals(dec28.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR), IsoWeek.weeksInYear(y), "year " + y);
        }
    }
}
