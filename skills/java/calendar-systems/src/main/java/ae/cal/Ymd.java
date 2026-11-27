package ae.cal;

/**
 * A year/month/day triple. The record does no validation; {@link Calendars} validates against a calendar system.
 *
 * @param year  proleptic year
 * @param month month, 1-based
 * @param day   day of month, 1-based
 */
public record Ymd(long year, int month, int day) {
    @Override
    public String toString() {
        return year + "-" + month + "-" + day;
    }
}
