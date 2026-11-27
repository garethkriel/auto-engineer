package ae.cal;

import static ae.cal.CalendarSystem.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CalendarsTest {

    private static Ymd ymd(long y, int m, int d) {
        return new Ymd(y, m, d);
    }

    @Test
    void epochIsThursdayFirstOfJanuary1970() {
        assertEquals(0, Calendars.toEpochDay(GREGORIAN, 1970, 1, 1));
        assertEquals(4, Calendars.dayOfWeek(0)); // Thursday
        assertEquals(ymd(1970, 1, 1), Calendars.fromEpochDay(GREGORIAN, 0));
    }

    @Test
    void knownGregorianLiterals() {
        assertEquals(19358, Calendars.toEpochDay(GREGORIAN, 2023, 1, 1)); // 53*365 + 13 leap days
        assertEquals(-719162, Calendars.toEpochDay(GREGORIAN, 1, 1, 1));
        assertEquals(11017, Calendars.toEpochDay(GREGORIAN, 2000, 3, 1)); // 2000-01-01 = 10957, + 31 + 29 days
        assertEquals(ymd(2000, 2, 29), Calendars.fromEpochDay(GREGORIAN, 11017 - 1));
    }

    @Test
    void gregorianLeapRules() {
        assertTrue(Calendars.isLeapYear(GREGORIAN, 2000));
        assertFalse(Calendars.isLeapYear(GREGORIAN, 1900));
        assertTrue(Calendars.isLeapYear(GREGORIAN, 2024));
        assertFalse(Calendars.isLeapYear(GREGORIAN, 2023));
        assertTrue(Calendars.isLeapYear(GREGORIAN, 0));
        assertTrue(Calendars.isLeapYear(GREGORIAN, -4));
        assertFalse(Calendars.isLeapYear(GREGORIAN, -100));
        assertTrue(Calendars.isLeapYear(GREGORIAN, -400));
    }

    @Test
    void julianLeapRulesAndDivergence() {
        assertTrue(Calendars.isLeapYear(JULIAN, 1900));
        assertTrue(Calendars.isLeapYear(JULIAN, 100));
        assertFalse(Calendars.isLeapYear(JULIAN, 1901));
        assertEquals(29, Calendars.lengthOfMonth(JULIAN, 1900, 2));
        assertEquals(28, Calendars.lengthOfMonth(GREGORIAN, 1900, 2));
        // 1900-03-01 Gregorian is Julian 1900-02-17 (13 days apart)
        assertEquals(ymd(1900, 2, 17), Calendars.convert(GREGORIAN, JULIAN, ymd(1900, 3, 1)));
        // October Revolution: 1917-10-25 Julian = 1917-11-07 Gregorian
        assertEquals(ymd(1917, 11, 7), Calendars.convert(JULIAN, GREGORIAN, ymd(1917, 10, 25)));
    }

    @Test
    void hybridSkipsTheCutoverGap() {
        long oct4 = Calendars.toEpochDay(HYBRID, 1582, 10, 4);
        long oct15 = Calendars.toEpochDay(HYBRID, 1582, 10, 15);
        assertEquals(oct4 + 1, oct15);
        assertEquals(-141427, oct15); // 1582-10-15 Gregorian
        assertEquals(ymd(1582, 10, 4), Calendars.fromEpochDay(HYBRID, oct15 - 1));
        assertEquals(21, Calendars.lengthOfMonth(HYBRID, 1582, 10));
        assertEquals(355, Calendars.lengthOfYear(HYBRID, 1582));
        for (int d = 5; d <= 14; d++) {
            assertFalse(Calendars.isValid(HYBRID, 1582, 10, d), "day " + d);
        }
        assertTrue(Calendars.isValid(HYBRID, 1582, 10, 31));
        assertTrue(Calendars.isLeapYear(HYBRID, 1500));
        assertFalse(Calendars.isLeapYear(HYBRID, 1700));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Calendars.toEpochDay(HYBRID, 1582, 10, 10));
        assertTrue(e.getMessage().contains("1582-10-10"), e.getMessage());
    }

    @Test
    void islamicCivilHandComputedLiterals() {
        // Epoch: Friday 1 Muharram 1 AH = Julian 622-07-16 (JDN 1948440 => epoch day 1948440-2440588)
        long epoch = Calendars.toEpochDay(ISLAMIC_CIVIL, 1, 1, 1);
        assertEquals(-492148, epoch);
        assertEquals(ymd(622, 7, 16), Calendars.fromEpochDay(JULIAN, epoch));
        assertEquals(5, Calendars.dayOfWeek(epoch)); // Friday
        // one 30-year cycle = 10631 days
        assertEquals(epoch + 10631, Calendars.toEpochDay(ISLAMIC_CIVIL, 31, 1, 1));
        // 1444 years = 48 cycles (510288 days) + years 1..4 of the cycle (354+355+354+354), year 2 leap
        assertEquals(epoch + 511705, Calendars.toEpochDay(ISLAMIC_CIVIL, 1445, 1, 1));
        assertEquals(ymd(2023, 7, 19), Calendars.fromEpochDay(GREGORIAN, epoch + 511705));
    }

    @Test
    void islamicLeapYearsInTheCycle() {
        int[] leap = {2, 5, 7, 10, 13, 16, 18, 21, 24, 26, 29};
        int count = 0;
        for (int y = 1; y <= 30; y++) {
            boolean expected = false;
            for (int l : leap) {
                expected |= l == y;
            }
            assertEquals(expected, Calendars.isLeapYear(ISLAMIC_CIVIL, y), "year " + y);
            assertEquals(expected ? 355 : 354, Calendars.lengthOfYear(ISLAMIC_CIVIL, y), "year " + y);
            if (expected) {
                count++;
            }
        }
        assertEquals(11, count);
        assertEquals(30, Calendars.lengthOfMonth(ISLAMIC_CIVIL, 2, 12));
        assertEquals(29, Calendars.lengthOfMonth(ISLAMIC_CIVIL, 1, 12));
        assertEquals(30, Calendars.lengthOfMonth(ISLAMIC_CIVIL, 1, 1));
        assertEquals(29, Calendars.lengthOfMonth(ISLAMIC_CIVIL, 1, 2));
    }

    @Test
    void invalidDatesAreRejected() {
        assertFalse(Calendars.isValid(GREGORIAN, 2023, 2, 29));
        assertFalse(Calendars.isValid(GREGORIAN, 2023, 0, 1));
        assertFalse(Calendars.isValid(GREGORIAN, 2023, 13, 1));
        assertFalse(Calendars.isValid(GREGORIAN, 2023, 4, 31));
        assertFalse(Calendars.isValid(GREGORIAN, 2023, 4, 0));
        assertFalse(Calendars.isValid(GREGORIAN, 1_000_001, 1, 1));
        assertFalse(Calendars.isValid(ISLAMIC_CIVIL, 1, 12, 30));
        assertTrue(Calendars.isValid(ISLAMIC_CIVIL, 2, 12, 30));
        assertTrue(Calendars.isValid(GREGORIAN, 1_000_000, 12, 31));
        assertTrue(Calendars.isValid(GREGORIAN, -1_000_000, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> Calendars.toEpochDay(GREGORIAN, 2023, 2, 29));
        assertThrows(IllegalArgumentException.class, () -> Calendars.lengthOfMonth(GREGORIAN, 2023, 13));
        assertThrows(IllegalArgumentException.class, () -> Calendars.fromEpochDay(GREGORIAN, Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> Calendars.fromEpochDay(GREGORIAN, Long.MIN_VALUE));
        assertThrows(NullPointerException.class, () -> Calendars.toEpochDay(null, 2023, 1, 1));
    }

    @Test
    void dayOfWeekHandlesNegativeEpochDays() {
        assertEquals(3, Calendars.dayOfWeek(-1)); // 1969-12-31 Wednesday
        assertEquals(7, Calendars.dayOfWeek(-4)); // 1969-12-28 Sunday
        assertEquals(1, Calendars.dayOfWeek(-3)); // 1969-12-29 Monday
        assertEquals(1, Calendars.dayOfWeek(Calendars.toEpochDay(GREGORIAN, 2024, 1, 1)));
        assertEquals(7, Calendars.dayOfWeek(Calendars.toEpochDay(GREGORIAN, 2023, 12, 31))); // Sunday
    }

    @Test
    void addMonthsClampsDay() {
        assertEquals(ymd(2023, 2, 28), Calendars.addMonths(GREGORIAN, ymd(2023, 1, 31), 1));
        assertEquals(ymd(2024, 2, 29), Calendars.addMonths(GREGORIAN, ymd(2024, 1, 31), 1));
        assertEquals(ymd(2023, 12, 29), Calendars.addMonths(GREGORIAN, ymd(2024, 2, 29), -2));
        assertEquals(ymd(2023, 2, 28), Calendars.addMonths(GREGORIAN, ymd(2023, 3, 31), -1));
        assertEquals(ymd(2023, 4, 30), Calendars.addMonths(GREGORIAN, ymd(2023, 3, 31), 1));
        assertEquals(ymd(2024, 1, 15), Calendars.addMonths(GREGORIAN, ymd(2023, 1, 15), 12));
        assertEquals(ymd(2022, 12, 15), Calendars.addMonths(GREGORIAN, ymd(2023, 1, 15), -1));
        assertEquals(ymd(1, 12, 1), Calendars.addMonths(GREGORIAN, ymd(0, 12, 1), 12));
        assertEquals(ymd(2023, 1, 31), Calendars.addMonths(GREGORIAN, ymd(2023, 1, 31), 0));
        // Islamic: 29-day month clamps a 30th
        assertEquals(ymd(1, 2, 29), Calendars.addMonths(ISLAMIC_CIVIL, ymd(1, 1, 30), 1));
        // HYBRID: 1582-09-20 + 1 month lands in the cutover month; day 20 -> 1582-10-20 exists
        assertEquals(ymd(1582, 10, 20), Calendars.addMonths(HYBRID, ymd(1582, 9, 20), 1));
        // 1582-09-10 + 1 month = 1582-10-10, which does not exist; clamp must not return a gap date
        Ymd gap = Calendars.addMonths(HYBRID, ymd(1582, 9, 10), 1);
        assertTrue(Calendars.isValid(HYBRID, gap.year(), gap.month(), gap.day()), gap.toString());
        assertEquals(ymd(1582, 10, 4), gap);
        // a day after the gap must not be clamped to the 21 real days of the month
        assertEquals(ymd(1582, 10, 25), Calendars.addMonths(HYBRID, ymd(1582, 9, 25), 1));
        assertEquals(ymd(1582, 10, 31), Calendars.addMonths(HYBRID, ymd(1582, 8, 31), 2));
        assertEquals(ymd(1582, 10, 31), Calendars.nthWeekday(HYBRID, 1582, 10, -1, 7)); // Sunday 31 Oct 1582
        // 1582-10-04 was a Thursday and 1582-10-15 a Friday (day numbers jump, weekdays do not)
        assertEquals(4, Calendars.dayOfWeek(Calendars.toEpochDay(HYBRID, 1582, 10, 4)));
        assertEquals(5, Calendars.dayOfWeek(Calendars.toEpochDay(HYBRID, 1582, 10, 15)));
        assertEquals(ymd(1582, 10, 15), Calendars.nthWeekday(HYBRID, 1582, 10, 1, 5));
        assertEquals(ymd(1582, 10, 1), Calendars.nthWeekday(HYBRID, 1582, 10, 1, 1)); // Monday 1 Oct
    }

    @Test
    void monthsBetweenCountsWholeMonths() {
        assertEquals(0, Calendars.monthsBetween(GREGORIAN, ymd(2023, 1, 31), ymd(2023, 2, 28)));
        assertEquals(1, Calendars.monthsBetween(GREGORIAN, ymd(2023, 1, 31), ymd(2023, 3, 1)));
        assertEquals(1, Calendars.monthsBetween(GREGORIAN, ymd(2023, 1, 15), ymd(2023, 2, 15)));
        assertEquals(-1, Calendars.monthsBetween(GREGORIAN, ymd(2023, 2, 15), ymd(2023, 1, 15)));
        assertEquals(0, Calendars.monthsBetween(GREGORIAN, ymd(2023, 2, 14), ymd(2023, 1, 15)));
        assertEquals(12, Calendars.monthsBetween(GREGORIAN, ymd(2023, 3, 10), ymd(2024, 3, 10)));
        assertEquals(11, Calendars.monthsBetween(GREGORIAN, ymd(2023, 3, 10), ymd(2024, 3, 9)));
        assertEquals(0, Calendars.monthsBetween(GREGORIAN, ymd(2023, 3, 10), ymd(2023, 3, 10)));
    }

    @Test
    void nthWeekday() {
        // US Thanksgiving: 4th Thursday of November 2024 is the 28th; last Monday of May 2024 is the 27th
        assertEquals(ymd(2024, 11, 28), Calendars.nthWeekday(GREGORIAN, 2024, 11, 4, 4));
        assertEquals(ymd(2024, 5, 27), Calendars.nthWeekday(GREGORIAN, 2024, 5, -1, 1));
        assertEquals(ymd(2024, 9, 1), Calendars.nthWeekday(GREGORIAN, 2024, 9, 1, 7)); // Sunday the 1st
        assertEquals(ymd(2024, 9, 29), Calendars.nthWeekday(GREGORIAN, 2024, 9, 5, 7)); // 5th Sunday exists
        assertEquals(ymd(2024, 9, 30), Calendars.nthWeekday(GREGORIAN, 2024, 9, -1, 1));
        // Feb 2024 has 29 days and starts on Thursday: a 5th Thursday exists; Feb 2023 (28 days) has only four
        assertEquals(ymd(2024, 2, 29), Calendars.nthWeekday(GREGORIAN, 2024, 2, 5, 4));
        assertThrows(IllegalArgumentException.class, () -> Calendars.nthWeekday(GREGORIAN, 2023, 2, 5, 3));
        assertThrows(IllegalArgumentException.class, () -> Calendars.nthWeekday(GREGORIAN, 2024, 2, 0, 4));
        assertThrows(IllegalArgumentException.class, () -> Calendars.nthWeekday(GREGORIAN, 2024, 2, 1, 8));
        assertThrows(IllegalArgumentException.class, () -> Calendars.nthWeekday(GREGORIAN, 2024, 2, -2, 4));
    }

    /** Walks every day of a span: the successor of each date is the next day or the first of the next month. */
    private static void walk(CalendarSystem sys, long fromYear, long toYear) {
        long first = Calendars.toEpochDay(sys, fromYear, 1, 1);
        long last = Calendars.toEpochDay(sys, toYear, 12, 29);
        Ymd prev = Calendars.fromEpochDay(sys, first);
        for (long day = first + 1; day <= last; day++) {
            Ymd cur = Calendars.fromEpochDay(sys, day);
            if (!Calendars.isValid(sys, cur.year(), cur.month(), cur.day())) {
                fail(sys + " produced invalid " + cur + " for epoch day " + day);
            }
            if (Calendars.toEpochDay(sys, cur) != day) {
                fail(sys + " round trip failed at " + cur);
            }
            boolean sameMonth = cur.year() == prev.year() && cur.month() == prev.month();
            if (sameMonth) {
                if (cur.day() != prev.day() + 1 && !(sys == HYBRID && prev.day() == 4 && cur.day() == 15)) {
                    fail(sys + " day jump " + prev + " -> " + cur);
                }
            } else {
                int lastDay = sys == HYBRID && prev.year() == 1582 && prev.month() == 10 ? 31
                        : Calendars.lengthOfMonth(sys, prev.year(), prev.month());
                if (cur.day() != 1 || prev.day() != lastDay) {
                    fail(sys + " month change " + prev + " -> " + cur + " (month length " + lastDay + ")");
                }
                boolean nextMonth = (cur.year() == prev.year() && cur.month() == prev.month() + 1)
                        || (cur.year() == prev.year() + 1 && prev.month() == 12 && cur.month() == 1);
                if (!nextMonth) {
                    fail(sys + " month skipped " + prev + " -> " + cur);
                }
            }
            prev = cur;
        }
    }

    @Test
    void everySystemWalksDayByDayWithoutGapsOrRepeats() {
        walk(ISLAMIC_CIVIL, -50, 3000); // 1..2 cycle boundaries and negative years too
        walk(GREGORIAN, -400, 2500);
        walk(JULIAN, -400, 2500);
        walk(HYBRID, 1500, 1700);
    }

    @Test
    void islamicYearAndMonthStartsAreContiguous() {
        long expected = Calendars.toEpochDay(ISLAMIC_CIVIL, 1, 1, 1);
        for (int y = 1; y <= 120; y++) {
            for (int m = 1; m <= 12; m++) {
                assertEquals(expected, Calendars.toEpochDay(ISLAMIC_CIVIL, y, m, 1), y + "-" + m);
                expected += Calendars.lengthOfMonth(ISLAMIC_CIVIL, y, m);
            }
        }
        assertEquals(4 * 10631, expected - Calendars.toEpochDay(ISLAMIC_CIVIL, 1, 1, 1)); // 120 years = 4 cycles
    }
}
