package ae.practice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class TemperatureConverterTest {

    @ParameterizedTest
    @CsvSource({"0, 32", "100, 212", "-40, -40", "37, 98.6", "-273.15, -459.67"})
    void convertsKnownPoints(double celsius, double fahrenheit) {
        assertEquals(fahrenheit, TemperatureConverter.celsiusToFahrenheit(celsius), 1e-9);
        assertEquals(celsius, TemperatureConverter.fahrenheitToCelsius(fahrenheit), 1e-9);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-273.15, -100.5, 0, 21.3, 1000})
    void roundTripIsExact(double celsius) {
        double back = TemperatureConverter.fahrenheitToCelsius(TemperatureConverter.celsiusToFahrenheit(celsius));
        assertEquals(celsius, back, 1e-9);
    }

    @Test
    void belowAbsoluteZeroRejected() {
        assertThrows(IllegalArgumentException.class, () -> TemperatureConverter.celsiusToFahrenheit(-273.16));
        assertThrows(IllegalArgumentException.class, () -> TemperatureConverter.fahrenheitToCelsius(-460));
    }
}
