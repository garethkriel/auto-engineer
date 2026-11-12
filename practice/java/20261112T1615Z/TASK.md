# Practice 20261112T1615Z: temperature converter (level 1)

**Pipeline test round.** The builder wrote this by hand to test CI, the AI reviewer and auto-merge end to end. It is a genuine, if simple, level-1 exercise.

## Task

Write `ae.practice.TemperatureConverter` with:

- `static double celsiusToFahrenheit(double c)`
- `static double fahrenheitToCelsius(double f)`

Both reject temperatures below absolute zero (−273.15 °C) with `IllegalArgumentException`.

## Success criteria

- 0 °C ↔ 32 °F, 100 °C ↔ 212 °F, and −40 is the same on both scales.
- Round trips are exact within 1e-9.
- Below absolute zero throws, in both directions.
