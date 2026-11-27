package ae.cal;

import static ae.cal.CalendarSystem.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class IsoWeekAndEasterTest {

    private static long g(int y, int m, int d) {
        return Calendars.toEpochDay(GREGORIAN, y, m, d);
    }

    @Test
    void publishedIsoWeekExamples() {
        assertEquals(new IsoWeek.WeekDate(2009, 53, 4), IsoWeek.of(g(2009, 12, 31)));
        assertEquals(new IsoWeek.WeekDate(2009, 53, 5), IsoWeek.of(g(2010, 1, 1)));
        assertEquals(new IsoWeek.WeekDate(2009, 53, 7), IsoWeek.of(g(2010, 1, 3)));
        assertEquals(new IsoWeek.WeekDate(2010, 1, 1), IsoWeek.of(g(2010, 1, 4)));
        assertEquals(new IsoWeek.WeekDate(2008, 1, 1), IsoWeek.of(g(2007, 12, 31)));
        assertEquals(new IsoWeek.WeekDate(2015, 53, 3), IsoWeek.of(g(2015, 12, 30)));
        assertEquals(new IsoWeek.WeekDate(2016, 52, 7), IsoWeek.of(g(2017, 1, 1)));
    }

    @Test
    void weeksInYear() {
        int[] long53 = {2004, 2009, 2015, 2020, 2026};
        for (int y : long53) {
            assertEquals(53, IsoWeek.weeksInYear(y), "year " + y);
        }
        int[] short52 = {2005, 2006, 2007, 2008, 2010, 2021, 2024, 2025};
        for (int y : short52) {
            assertEquals(52, IsoWeek.weeksInYear(y), "year " + y);
        }
    }

    @Test
    void toEpochDayRejectsBadWeeks() {
        assertThrows(IllegalArgumentException.class, () -> IsoWeek.toEpochDay(new IsoWeek.WeekDate(2024, 53, 1)));
        assertEquals(g(2020, 12, 31), IsoWeek.toEpochDay(new IsoWeek.WeekDate(2020, 53, 4)));
        assertThrows(IllegalArgumentException.class, () -> IsoWeek.toEpochDay(new IsoWeek.WeekDate(2020, 0, 1)));
        assertThrows(IllegalArgumentException.class, () -> IsoWeek.toEpochDay(new IsoWeek.WeekDate(2020, 1, 8)));
        assertThrows(IllegalArgumentException.class, () -> IsoWeek.toEpochDay(new IsoWeek.WeekDate(2020, 1, 0)));
    }

    @Test
    void formatAndParse() {
        IsoWeek.WeekDate w = new IsoWeek.WeekDate(2009, 53, 4);
        assertEquals("2009-W53-4", IsoWeek.format(w, true));
        assertEquals("2009W534", IsoWeek.format(w, false));
        assertEquals(w, IsoWeek.parse("2009-W53-4"));
        assertEquals(w, IsoWeek.parse("2009W534"));
        assertEquals("0099-W01-1", IsoWeek.format(new IsoWeek.WeekDate(99, 1, 1), true));
        assertEquals(new IsoWeek.WeekDate(99, 1, 1), IsoWeek.parse("0099-W01-1"));
        for (String bad : new String[] {"", "2009-W53", "2009-W5-4", "2009-W53-44", "2009-w53-4", "2009W53-4",
            "2009-W534", "09-W53-4", "20090-W53-4", "2009-W00-1", "2009-W54-1", "2009-W53-0", "2009-W53-8",
            "2009-W53-4 ", " 2009-W53-4", "2009W5340", "2009W534 ", "+009-W53-4", "2009-W5３-4", "2023-W53-1"}) {
            assertThrows(IllegalArgumentException.class, () -> IsoWeek.parse(bad), "'" + bad + "'");
        }
        assertThrows(NullPointerException.class, () -> IsoWeek.parse(null));
    }

    @Test
    void parseRejectsWeek53OnShortYearWithMessage() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> IsoWeek.parse("2023-W53-1"));
        assertTrue(e.getMessage().contains("2023-W53-1"), e.getMessage());
        // control: the same text on a 53-week year parses
        assertEquals(new IsoWeek.WeekDate(2020, 53, 1), IsoWeek.parse("2020-W53-1"));
    }

    @Test
    void ordinalDates() {
        assertEquals("2024-060", IsoWeek.formatOrdinal(g(2024, 2, 29)));
        assertEquals("2023-060", IsoWeek.formatOrdinal(g(2023, 3, 1)));
        assertEquals("2024-366", IsoWeek.formatOrdinal(g(2024, 12, 31)));
        assertEquals("2024-001", IsoWeek.formatOrdinal(g(2024, 1, 1)));
        assertEquals(g(2024, 2, 29), IsoWeek.parseOrdinal("2024-060"));
        assertEquals(g(2023, 12, 31), IsoWeek.parseOrdinal("2023-365"));
        assertEquals(g(2024, 12, 31), IsoWeek.parseOrdinal("2024-366"));
        for (String bad : new String[] {"2023-366", "2024-367", "2024-000", "2024-1", "2024060", "2024-0600",
            "24-060", "2024-06a", "", "2024-060 "}) {
            assertThrows(IllegalArgumentException.class, () -> IsoWeek.parseOrdinal(bad), "'" + bad + "'");
        }
    }

    private static Ymd ymd(long y, int m, int d) {
        return new Ymd(y, m, d);
    }

    @Test
    void gregorianEasterTable() {
        assertEquals(ymd(1818, 3, 22), Easter.gregorian(1818)); // earliest possible
        assertEquals(ymd(2285, 3, 22), Easter.gregorian(2285));
        assertEquals(ymd(1943, 4, 25), Easter.gregorian(1943)); // latest possible
        assertEquals(ymd(1886, 4, 25), Easter.gregorian(1886));
        assertEquals(ymd(1981, 4, 19), Easter.gregorian(1981));
        assertEquals(ymd(2038, 4, 25), Easter.gregorian(2038));
        assertEquals(ymd(1900, 4, 15), Easter.gregorian(1900));
        assertEquals(ymd(1961, 4, 2), Easter.gregorian(1961));
        assertEquals(ymd(2000, 4, 23), Easter.gregorian(2000));
        assertEquals(ymd(2008, 3, 23), Easter.gregorian(2008));
        assertEquals(ymd(2011, 4, 24), Easter.gregorian(2011));
        assertEquals(ymd(2019, 4, 21), Easter.gregorian(2019));
        assertEquals(ymd(2024, 3, 31), Easter.gregorian(2024));
        assertEquals(ymd(2025, 4, 20), Easter.gregorian(2025));
    }

    @Test
    void orthodoxEasterInGregorianDates() {
        assertEquals(ymd(2024, 5, 5), Easter.orthodox(2024));
        assertEquals(ymd(2023, 4, 16), Easter.orthodox(2023));
        assertEquals(ymd(2021, 5, 2), Easter.orthodox(2021));
        assertEquals(ymd(2019, 4, 28), Easter.orthodox(2019));
        assertEquals(ymd(2010, 4, 4), Easter.orthodox(2010));
        assertEquals(ymd(2025, 4, 20), Easter.orthodox(2025));
        assertEquals(ymd(2024, 4, 22), Easter.julian(2024)); // the Julian-calendar date itself
        assertEquals(ymd(2024, 4, 22), Calendars.convert(GREGORIAN, JULIAN, ymd(2024, 5, 5)));
    }

    @Test
    void easterAlwaysFallsOnSundayInRange() {
        for (int y = 1583; y <= 4099; y++) {
            Ymd e = Easter.gregorian(y);
            assertEquals(7, Calendars.dayOfWeek(Calendars.toEpochDay(GREGORIAN, e)), "gregorian " + y);
            assertTrue(e.month() == 3 || e.month() == 4, "month " + y);
            Ymd o = Easter.orthodox(y);
            assertEquals(7, Calendars.dayOfWeek(Calendars.toEpochDay(GREGORIAN, o)), "orthodox " + y);
        }
        for (int y = 326; y <= 3000; y++) {
            Ymd j = Easter.julian(y);
            assertEquals(7, Calendars.dayOfWeek(Calendars.toEpochDay(JULIAN, j)), "julian " + y);
        }
    }

    @Test
    void easterIsAfterTheSpringEquinoxFullMoonAndRejectsOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> Easter.gregorian(1582));
        assertThrows(IllegalArgumentException.class, () -> Easter.gregorian(4100));
        assertThrows(IllegalArgumentException.class, () -> Easter.julian(325));
        assertThrows(IllegalArgumentException.class, () -> Easter.orthodox(325));
        // Gregorian Easter occupies exactly the 35 dates 22 March .. 25 April over a long span
        boolean[] seen = new boolean[36];
        for (int y = 1583; y <= 4099; y++) {
            Ymd e = Easter.gregorian(y);
            int idx = e.month() == 3 ? e.day() - 22 : 10 + e.day() - 1;
            seen[idx] = true;
        }
        for (int i = 0; i < 35; i++) {
            assertTrue(seen[i], "date index " + i + " never an Easter");
        }
    }

    /** Independent oracle: Gauss's Easter formula with its two exceptions (different structure from Meeus). */
    private static Ymd gaussEaster(int y) {
        int k = y / 100;
        int p = (13 + 8 * k) / 25;
        int q = k / 4;
        int mm = (15 - p + k - q) % 30;
        int n = (4 + k - q) % 7;
        int a = y % 19;
        int d = (19 * a + mm) % 30;
        int e = (2 * (y % 4) + 4 * (y % 7) + 6 * d + n) % 7;
        int day = 22 + d + e; // counted from 1 March: 57 = 26 April, 56 = 25 April
        if (day == 57) {
            day = 50; // 26 April becomes 19 April
        } else if (day == 56 && d == 28 && e == 6 && a > 10) {
            day = 49; // 25 April becomes 18 April
        }
        return day > 31 ? new Ymd(y, 4, day - 31) : new Ymd(y, 3, day);
    }

    @Test
    void gregorianEasterMatchesGaussOracleForEveryYear() {
        for (int y = 1583; y <= 4099; y++) {
            assertEquals(gaussEaster(y), Easter.gregorian(y), "year " + y);
        }
    }
}
