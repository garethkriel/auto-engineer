# j22-regex: Linear-time regular expressions

Java 21, standard library only. Put your solution under `bench-solutions/java/j22-regex/src/main/java/`, using exactly the package and public signatures below. CI adds the hidden JUnit 5 tests, and the task passes only if every one of them passes.

```java
package bench.regex;
public final class Regex {
    public static Regex compile(String pattern);
    public boolean matches(String input);   // the whole input matches
    public boolean find(String input);      // some substring (possibly empty) matches
}
```

## Syntax

| Construct | Meaning |
|---|---|
| any char except `( ) [ ] \| * + ? . ^ $ \` | itself (`{` and `}` are ordinary characters) |
| `.` | any one character |
| `\c` | the character `c` taken literally, for any `c` |
| `[...]` | a character class of single chars and ranges `a-b` (with `a ≤ b`). A leading `^` negates it. `-` is literal when it comes first or last, and `\c` escapes work inside. The class must not be empty. |
| `( ... )` | a group, which may be empty |
| `x\|y` | alternation, with the lowest precedence. Empty alternatives are allowed, as in `(a\|)b`. |
| `*` `+` `?` | zero-or-more, one-or-more or optional, applying to the preceding atom: a character, class, `.`, group or anchor |
| `^` `$` | match only at input position 0 or at the end of the input. They may appear anywhere in the pattern. |

**Quantifiers:** a quantifier must follow an atom. A quantifier at the start of the pattern, a group or an alternative throws `IllegalArgumentException`, and so do two quantifiers in a row (such as `a**` or `a*?`).

**Other errors:** each of these also throws `IllegalArgumentException`:
- unbalanced parentheses, or an unmatched `]`;
- an empty, unterminated or reversed (`[z-a]`) class;
- a trailing backslash;
- a null pattern or a null input.

Characters are Java `char`s (UTF-16 code units).

## Performance

Matching must take time proportional to |pattern| × |input|. The hidden tests run patterns such as `(a*)*b`, `(a|aa)*c` and `(x+x+)+y` against inputs of 20,000–50,000 characters, and run `find` on 100,000–200,000-character texts. Each must answer within seconds. Backtracking engines, `java.util.regex` included, are too slow or overflow the stack on these inputs.
