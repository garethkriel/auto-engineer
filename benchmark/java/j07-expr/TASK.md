# j07-expr: Arithmetic expression evaluator

Java 21, standard library only. Put your solution under `bench-solutions/java/j07-expr/src/main/java/`, using exactly the package and public signatures below. CI adds the hidden JUnit 5 tests, and the task passes only if every one of them passes.

```java
package bench.expr;
public final class ExprEvaluator {
    public static double eval(String expression);
}
```

Grammar (whitespace is allowed between tokens):

```
expr   := term (('+' | '-') term)*
term   := factor (('*' | '/') factor)*
factor := '-' factor | '(' expr ')' | number
number := digit+ ('.' digit+)?
```

- The usual precedence applies, and `+ - * /` are left-associative. Unary minus may repeat, so `--2` is 2.
- Division by zero throws `ArithmeticException`.
- Null, or anything that doesn't match the grammar, throws `IllegalArgumentException`. Examples: empty input, a trailing operator, unbalanced parentheses, two numbers in a row, letters, `1.`, `.5` and `()`.
