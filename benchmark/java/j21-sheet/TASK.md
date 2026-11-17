# j21-sheet: Spreadsheet evaluator

Java 21, standard library only. Put your solution under `bench-solutions/java/j21-sheet/src/main/java/`, using exactly the package and public signatures below. CI adds the hidden JUnit 5 tests, and the task passes only if every one of them passes.

```java
package bench.sheet;
public final class Sheet {
    public Sheet();
    public void set(String cell, String input);
    public String get(String cell);   // the displayed value
}
```

## Cells and inputs

- **Cell names:** one upper-case letter `A`–`Z` followed by a row `1`–`999` with no leading zero, so `A1`–`Z999`. Any other name throws `IllegalArgumentException` from `set` and `get`, and so does a null input.
- **`set(cell, "")`** clears the cell. An empty cell displays as `""`.
- **Inputs:**
  - an input starting with `=` is a **formula**;
  - an input matching exactly `-?[0-9]+(\.[0-9]+)?` is a **number**;
  - anything else is **text**, displayed exactly as entered (including spaces).
- **Number display:** 0, including −0, shows `0`. Any other value shows `BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()`, so `3.50` shows `3.5` and `0.1+0.2` shows `0.30000000000000004`.
- **Up to date:** `get` always reflects the current contents. Changing a cell changes every formula that depends on it.

## Formulas

- **Operators:** spaces may appear between tokens. `+ - * /` follow the usual precedence and are left-associative. There's unary minus and parentheses.
- **Number literals:** `digits` or `digits.digits`, with no sign and no exponent.
- **Names:** cell references are upper case. The functions are exactly `SUM`, `MIN`, `MAX`, `AVERAGE` and `COUNT` (upper case), each taking one range `X1:Y2`, given as any two opposite corners.
- **Parse errors:** anything else shows `#PARSE!`. That includes invalid references such as `A0`, `AA1` and `A1000`, lower-case names, unknown functions, a function without a range, dangling operators, and `1.` or `.5`.

## Values and errors

- **References in arithmetic:**
  - a referenced empty cell counts as **0**;
  - a referenced text cell gives `#VALUE!`;
  - a referenced error cell passes its error on.
- **Binary operations:** if the left operand is an error, the result is that error. Otherwise, if the right operand is an error, the result is that one.
- **Division by zero** gives `#DIV/0!`.
- **Non-finite results:** any arithmetic or function result that isn't finite gives `#NUM!`.
- **Functions:**
  - They visit the range row by row (top row first), left to right. The first error cell met makes the result that error, and that applies to all five functions.
  - Text and empty cells are otherwise ignored.
  - `SUM` of no numbers is 0. `COUNT` counts number values. `AVERAGE` of no numbers is `#DIV/0!`. `MIN` and `MAX` of no numbers are 0.
- **Cycles:** a formula cell on a reference cycle shows `#CYCLE!`. So does any formula cell that references a cell on a cycle, whether directly, through other cells or through a range. `#CYCLE!` takes precedence over every other error.

## Performance

A chain of 1,000 dependent cells, and 50 levels of `Cn = C(n-1) + C(n-1)`, must evaluate within a few seconds.
