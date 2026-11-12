package ae.practice;

/** Converts temperatures between Celsius and Fahrenheit. */
public final class TemperatureConverter {

    /** Absolute zero in degrees Celsius. */
    public static final double ABSOLUTE_ZERO_C = -273.15;

    private static final double EPSILON = 1e-9;

    private TemperatureConverter() {}

    /** Celsius to Fahrenheit; rejects values below absolute zero. */
    public static double celsiusToFahrenheit(double celsius) {
        if (celsius < ABSOLUTE_ZERO_C - EPSILON) {
            throw new IllegalArgumentException("below absolute zero: " + celsius + " C");
        }
        return celsius * 9.0 / 5.0 + 32.0;
    }

    /** Fahrenheit to Celsius; rejects values below absolute zero. */
    public static double fahrenheitToCelsius(double fahrenheit) {
        double celsius = (fahrenheit - 32.0) * 5.0 / 9.0;
        if (celsius < ABSOLUTE_ZERO_C - EPSILON) {
            throw new IllegalArgumentException("below absolute zero: " + fahrenheit + " F");
        }
        return celsius;
    }
}
