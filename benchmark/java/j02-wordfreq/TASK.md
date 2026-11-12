# j02-wordfreq: Top-k word frequency

Java 21, standard library only. Put your solution under `bench-solutions/java/j02-wordfreq/src/main/java/`, using exactly the package and public signatures below. CI adds the hidden JUnit 5 tests, and the task passes only if every one of them passes.

```java
package bench.wordfreq;
public final class WordFrequency {
    public static List<Map.Entry<String, Integer>> top(String text, int k);
}
```

- A word is a maximal run of Unicode letters or digits. Every other character separates words, including spaces, punctuation, apostrophes and hyphens. Words are compared lower-cased with `Locale.ROOT`.
- Return at most `k` entries (word → count), ordered by count descending, with ties broken alphabetically ascending. If there are fewer than `k` distinct words, return all of them. `k == 0` returns an empty list.
- `text == null` or `k < 0` throws `IllegalArgumentException`.
