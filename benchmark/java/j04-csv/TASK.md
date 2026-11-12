# j04-csv: CSV line parser

Java 21, standard library only. Put your solution under `bench-solutions/java/j04-csv/src/main/java/`, using exactly the package and public signatures below. CI adds the hidden JUnit 5 tests, and the task passes only if every one of them passes.

```java
package bench.csv;
public final class CsvParser {
    public static List<String> parseLine(String line);
}
```

- Commas separate fields. A line with n commas has n+1 fields, and empty fields are kept, so the empty line parses to one empty field.
- A field that starts with `"` is quoted. It may contain commas, and `""` inside it stands for one `"`. The closing quote must be followed by a comma or the end of the line.
- Spaces are never trimmed.
- These throw `IllegalArgumentException`:
  - null input;
  - an unterminated quoted field;
  - text after a closing quote;
  - a `"` inside an unquoted field.
