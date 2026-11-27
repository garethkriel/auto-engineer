package ae.cal;

/** Supported calendar systems. */
public enum CalendarSystem {
    /** Proleptic Gregorian calendar (year 0 exists, as in ISO 8601). */
    GREGORIAN,
    /** Proleptic Julian calendar (year 0 exists). */
    JULIAN,
    /** Julian before 1582-10-15 (Gregorian), Gregorian from then on; 1582-10-05..14 do not exist. */
    HYBRID,
    /** Tabular Islamic civil calendar; epoch is Friday 1 Muharram 1 = Julian 622-07-16. */
    ISLAMIC_CIVIL
}
