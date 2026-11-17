# j14-json: Strict JSON parser

Java 21, standard library only. Put your solution under `bench-solutions/java/j14-json/src/main/java/`, using exactly the package and public signatures below. CI adds the hidden JUnit 5 tests, and the task passes only if every one of them passes.

```java
package bench.json;
public final class JsonParser {
    public static Object parse(String text);
}
```

Parse exactly one JSON value (RFC 8259) and return it as plain Java objects:

| JSON | Java |
|---|---|
| object | `java.util.LinkedHashMap<String, Object>`, keys in document order |
| array | `java.util.ArrayList<Object>` |
| string | `String` |
| number with no fraction and no exponent that fits in a `long` | `Long` (so `-0` is `0L`) |
| any other number, including integers too big for `long` | `Double` |
| `true` / `false` | `Boolean` |
| `null` | Java `null` |

**Strictness:** each of these throws `IllegalArgumentException`:
- **Whitespace:** whitespace other than space, tab, LF and CR (for example, no-break space).
- **Numbers:**
  - leading zeros (`01`), a leading `+`, `1.`, `.5`, `1e`;
  - `NaN` and `Infinity`.
- **Structure:**
  - trailing commas and empty elements (`[1,,2]`);
  - single quotes, comments, and unquoted keys;
  - a missing `:` between key and value, or an empty value;
  - duplicate keys in one object.
- **Strings:**
  - raw control characters (below U+0020);
  - unknown escapes (`\x`);
  - a malformed `\u` escape;
  - an unterminated string.
- **Input as a whole:**
  - text after the value;
  - empty or whitespace-only input;
  - `null` input.

**Escapes:** support `\" \\ \/ \b \f \n \r \t \uXXXX`, with hex digits in either case. A surrogate pair written as two `\u` escapes yields the matching two Java `char`s.

**Nesting:** arrays and objects nested at least 2,000 levels deep must parse.
