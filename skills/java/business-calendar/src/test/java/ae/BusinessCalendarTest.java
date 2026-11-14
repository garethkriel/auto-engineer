package ae;

import static org.junit.jupiter.api.Assertions.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BusinessCalendarTest {
    // 2026-12-25 is a Friday; 2026-12-28 is a Monday.
    private static final LocalDate XMAS = LocalDate.of(2026, 12, 25);
    private final BusinessCalendar cal = BusinessCalendar.standard(List.of(XMAS));

    @ParameterizedTest
    @CsvSource({
        "2026-12-24,true",   // Thursday
        "2026-12-25,false",  // holiday
        "2026-12-26,false",  // Saturday
        "2026-12-27,false",  // Sunday
        "2026-12-28,true"
    })
    void isBusinessDayHandlesWeekendsAndHolidays(String date, boolean expected) {
        assertEquals(expected, cal.isBusinessDay(LocalDate.parse(date)));
    }

    @ParameterizedTest
    @CsvSource({
        "2026-12-23,1,2026-12-24",
        "2026-12-24,1,2026-12-28",   // skips holiday + weekend
        "2026-12-24,2,2026-12-29",
        "2026-12-28,-1,2026-12-24",  // backward skips weekend + holiday
        "2026-12-26,1,2026-12-28",   // from a weekend day
        "2026-12-26,-1,2026-12-24",
        "2026-12-23,10,2027-01-07"   // Jan 1 is not a holiday here
    })
    void addBusinessDaysSkipsNonBusinessDays(String start, int n, String expected) {
        assertEquals(LocalDate.parse(expected), cal.addBusinessDays(LocalDate.parse(start), n));
    }

    @Test
    void addZeroReturnsStartEvenOnWeekend() {
        LocalDate sat = LocalDate.of(2026, 12, 26);
        assertEquals(sat, cal.addBusinessDays(sat, 0));
    }

    @Test
    void addBusinessDaysRoundTripFromBusinessDay() {
        LocalDate start = LocalDate.of(2026, 12, 23);
        for (int n = 1; n <= 40; n++) {
            assertEquals(start, cal.addBusinessDays(cal.addBusinessDays(start, n), -n), "n=" + n);
        }
    }

    @Test
    void addBusinessDaysPastLocalDateMaxThrows() {
        assertThrows(java.time.DateTimeException.class,
            () -> cal.addBusinessDays(LocalDate.MAX, 1));
    }

    @ParameterizedTest
    @CsvSource({
        "2026-12-21,2026-12-28,4",   // Mon..Sun minus holiday Fri = 4 (Mon-Thu)
        "2026-12-21,2026-12-22,1",
        "2026-12-28,2026-12-28,0",
        "2026-12-28,2026-12-21,-4",
        "2026-12-25,2026-12-28,0",   // holiday + weekend only
        "2026-12-24,2026-12-29,2"    // Thu and Mon
    })
    void businessDaysBetweenIsHalfOpen(String from, String to, int expected) {
        assertEquals(expected, cal.businessDaysBetween(LocalDate.parse(from), LocalDate.parse(to)));
    }

    @Test
    void betweenAndAddAreConsistent() {
        LocalDate start = LocalDate.of(2026, 12, 21);
        for (int n = 0; n <= 30; n++) {
            LocalDate end = cal.addBusinessDays(start, n);
            assertEquals(n, cal.businessDaysBetween(start, end), "n=" + n);
        }
    }

    @Test
    void customWeekendFridaySaturday() {
        BusinessCalendar c = BusinessCalendar.of(
            EnumSet.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY), Set.of());
        assertFalse(c.isBusinessDay(LocalDate.of(2026, 12, 25)));
        assertTrue(c.isBusinessDay(LocalDate.of(2026, 12, 27))); // Sunday works
        assertEquals(LocalDate.of(2026, 12, 27), c.addBusinessDays(LocalDate.of(2026, 12, 24), 1));
    }

    @Test
    void lastBusinessDayOfMonth() {
        assertEquals(Optional_of(2026, 12, 31), cal.lastBusinessDayOfMonth(YearMonth.of(2026, 12)));
        // Nov 30 2026 is a Monday; Feb 28 2026 is a Saturday -> Fri Feb 27
        assertEquals(Optional_of(2026, 2, 27), cal.lastBusinessDayOfMonth(YearMonth.of(2026, 2)));
    }

    @Test
    void lastBusinessDayEmptyWhenWholeMonthBlocked() {
        List<LocalDate> all = new ArrayList<>();
        YearMonth ym = YearMonth.of(2026, 12);
        for (int d = 1; d <= ym.lengthOfMonth(); d++) {
            all.add(ym.atDay(d));
        }
        assertTrue(BusinessCalendar.standard(all).lastBusinessDayOfMonth(ym).isEmpty());
    }

    @Test
    void lastBusinessDaySkipsTrailingHoliday() {
        BusinessCalendar c = BusinessCalendar.standard(List.of(LocalDate.of(2026, 12, 31)));
        assertEquals(Optional_of(2026, 12, 30), c.lastBusinessDayOfMonth(YearMonth.of(2026, 12)));
    }

    @Test
    void allSevenWeekendDaysRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> BusinessCalendar.of(EnumSet.allOf(DayOfWeek.class), Set.of()));
    }

    @Test
    void nullArgumentsRejected() {
        assertThrows(NullPointerException.class, () -> BusinessCalendar.standard(null));
        assertThrows(NullPointerException.class, () -> BusinessCalendar.of(null, Set.of()));
        assertThrows(NullPointerException.class, () -> cal.isBusinessDay(null));
        assertThrows(NullPointerException.class, () -> cal.addBusinessDays(null, 1));
        assertThrows(NullPointerException.class,
            () -> BusinessCalendar.standard(java.util.Arrays.asList((LocalDate) null)));
    }

    @Test
    void inputsAreDefensivelyCopied() {
        List<LocalDate> holidays = new ArrayList<>(List.of(XMAS));
        BusinessCalendar c = BusinessCalendar.standard(holidays);
        holidays.clear();
        assertFalse(c.isBusinessDay(XMAS));
    }

    @Test
    void parseHolidaysSortsDedupesAndIgnoresComments() throws HolidayFormatException {
        List<LocalDate> parsed = BusinessCalendar.parseHolidays(List.of(
            "# year-end", "2026-12-25", "", "  2026-01-01  ", "2026-12-25 "));
        assertEquals(List.of(LocalDate.of(2026, 1, 1), XMAS), parsed);
    }

    @Test
    void parseHolidaysReportsLineNumber() {
        HolidayFormatException e = assertThrows(HolidayFormatException.class,
            () -> BusinessCalendar.parseHolidays(List.of("2026-12-25", "# c", "25/12/2026")));
        assertEquals(3, e.lineNumber());
        assertTrue(e.getMessage().contains("25/12/2026"));
        assertNotNull(e.getCause());
    }

    @Test
    void parseHolidaysRejectsImpossibleDate() {
        HolidayFormatException e = assertThrows(HolidayFormatException.class,
            () -> BusinessCalendar.parseHolidays(List.of("2026-02-30")));
        assertEquals(1, e.lineNumber());
    }

    private static java.util.Optional<LocalDate> Optional_of(int y, int m, int d) {
        return java.util.Optional.of(LocalDate.of(y, m, d));
    }
}
