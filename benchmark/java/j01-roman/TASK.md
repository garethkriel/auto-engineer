# j01-roman: Roman numerals

Java 21, standard library only. Put your solution under `bench-solutions/java/j01-roman/src/main/java/`, using exactly the package and public signatures below. CI adds the hidden JUnit 5 tests, and the task passes only if every one of them passes.

```java
package bench.roman;
public final class RomanNumerals {
    public static String toRoman(int n);
    public static int fromRoman(String s);
}
```

- `toRoman` converts 1–3999 to the canonical upper-case numeral, using subtractive notation (IV, IX, XL, XC, CD, CM). Anything outside 1–3999 throws `IllegalArgumentException`.
- `fromRoman` accepts only canonical upper-case numerals for 1–3999: exactly the strings `toRoman` can produce. Anything else throws `IllegalArgumentException`. That covers null, empty, lower case, spaces, non-canonical forms such as `IIII` or `IC`, and values above 3999.
