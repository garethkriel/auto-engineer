# j35-minisql: In-memory SQL database

Java 21, standard library only. Put your solution under `bench-solutions/java/j35-minisql/src/main/java/`, using exactly the package and public signatures below. CI adds the hidden JUnit 5 tests, and the task passes only if every one of them passes.

```java
package bench.minisql;
public final class Database {
    public Database();
    public Result execute(String sql);

    public record Result(java.util.List<String> columns, java.util.List<java.util.List<Object>> rows, int affected) { }
}

package bench.minisql;
public final class SqlException extends RuntimeException {
    public SqlException(String message);
}
```

`execute` runs exactly one statement, optionally followed by `;`. Every error throws `SqlException`, and a statement that fails changes nothing.

## Lexical rules

- Keywords and identifiers are case-insensitive. Identifiers are stored and reported in lower case. An identifier is an ASCII letter or `_`, then ASCII letters, digits or `_`.
- These words are reserved and can't be identifiers: `CREATE TABLE DROP INSERT INTO VALUES SELECT FROM WHERE GROUP BY HAVING ORDER ASC DESC LIMIT OFFSET UPDATE SET DELETE AND OR NOT NULL IS AS TRUE FALSE INT TEXT COUNT SUM MIN MAX`.
- Integer literals are ASCII digits and must fit in a `long`. There are no negative literals: `-5` is unary minus applied to `5`.
- String literals use single quotes, with `''` standing for one quote.
- Symbols: `( ) , ; * + - / % = <> != < <= > >= ||`. `!=` means `<>`. Any other character is an error.

## Values and types

Columns are `INT` (Java `Long`) or `TEXT` (`String`). Expressions can also produce booleans (`Boolean`). Any value may be `NULL` (Java `null`). Only `Long`, `String` or `null` can be stored: storing anything else in a column, or a value of the wrong type, is an error.

## Statements

- `CREATE TABLE t (col TYPE, ...)` creates a table with at least one column. The table must not exist already, and its column names must be distinct.
- `DROP TABLE t` removes a table.
- `INSERT INTO t [(col, ...)] VALUES (expr, ...), (expr, ...) ...`
  - Without a column list, each tuple gives every column in table order.
  - With a list, the listed columns (distinct, existing) get the values and the rest are `NULL`.
  - The number of values must match. The expressions can't refer to columns.
  - `affected` is the number of rows inserted.
- `UPDATE t SET col = expr, ... [WHERE cond]`
  - Every expression sees the row's values from before the update. A column may appear only once.
  - `affected` is the number of matching rows.
- `DELETE FROM t [WHERE cond]`. `affected` is the number of rows deleted.
- `SELECT items FROM t [WHERE cond] [GROUP BY col, ...] [HAVING cond] [ORDER BY expr [ASC|DESC], ...] [LIMIT n [OFFSET m]]`
  - `items` is `*` alone, or a list of `expr [AS alias]`.

## Results

- For `SELECT`, `columns` holds the output column names and `rows` holds one list per output row. Both are unmodifiable, rows included, and rows may contain `null`. `affected` is 0.
- For every other statement, `columns` and `rows` are empty. `affected` is as described above, and 0 for `CREATE` and `DROP`.
- **Output names:** an item's name is:
  - its alias if it has one;
  - otherwise the column name, if the item is a bare column;
  - otherwise `col<N>`, where N is its 1-based position.

  `*` expands to every column, in table order.
- **Order of rows:** a table keeps rows in insertion order. `UPDATE` keeps a row in its position. Without `ORDER BY`, rows come out in table order. Groups come out in the order of their first row.

## Expressions

From lowest to highest precedence:

```
expr    := and { OR and }
and     := not { AND not }
not     := NOT not | cmp
cmp     := add [ ( = | <> | < | <= | > | >= ) add | IS [NOT] NULL ]
add     := mul { ( + | - | || ) mul }
mul     := unary { ( * | / | % ) unary }
unary   := - unary | primary
primary := INT | STRING | NULL | TRUE | FALSE | column | ( expr )
         | COUNT(*) | COUNT(expr) | SUM(expr) | MIN(expr) | MAX(expr)
```

A comparison can't be chained: `a = b = c` is a syntax error.

- **Arithmetic** (`+ - * / %` and unary `-`) needs `INT` operands, and gives `NULL` if either operand is `NULL`. `/` and `%` behave like Java's `long` operators. Dividing by zero is an error.
- **`||`** concatenates two `TEXT` values, or gives `NULL` if either is `NULL`.
- **Comparisons:** both operands must be `INT`, both `TEXT` (compared with `String.compareTo`) or both boolean. The result is `NULL` if either operand is `NULL`.
- **`IS NULL` and `IS NOT NULL`** always give `TRUE` or `FALSE`.
- **`NOT`, `AND`, `OR`** take booleans or `NULL`, and use SQL three-valued logic. Both operands are always evaluated.
- **Conditions:** `WHERE` and `HAVING` conditions must give a boolean or `NULL`. A row or group is kept only when the condition is `TRUE`.
- **Type errors:** any other operand type is an error.
- **Name checks:** unknown tables and columns are errors even when the table is empty.

## Aggregates and groups

- A `SELECT` is an *aggregate query* if it has `GROUP BY` or `HAVING`, or if an aggregate function appears in its items or in an `ORDER BY` expression.
- In an aggregate query:
  - With `GROUP BY`, rows with equal values in all the grouped columns form a group, and NULLs count as equal. Without `GROUP BY`, all the rows that pass `WHERE` form exactly one group, even if there are none.
  - Each group gives one output row, provided it passes `HAVING`.
  - Outside aggregate arguments, a column may appear only if it is grouped. `*` isn't allowed.
- **Aggregate functions:**
  - `COUNT(*)` counts rows. `COUNT(e)` counts non-NULL values of `e`.
  - `SUM` needs `INT` values. `MIN` and `MAX` take `INT` or `TEXT`.
  - All three ignore `NULL`s and give `NULL` when there are no other values.
- Aggregates can't be nested, and can't appear in `WHERE`, `INSERT` or `UPDATE`.

## ORDER BY, LIMIT and OFFSET

- Each `ORDER BY` item is one of:
  - a bare integer `k`, meaning output column `k` (1-based; out of range is an error);
  - a bare name equal to an output column name, meaning that output column (output names win over table columns);
  - otherwise an expression, evaluated per row, or per group in an aggregate query.
- **Sorting:** ascending unless `DESC`. `NULL` sorts before every other value, so it comes first ascending and last descending. Booleans sort `FALSE` before `TRUE`. Comparing values of different types is an error.
- **Ties:** the sort is stable, so ties keep their earlier order.
- `LIMIT n [OFFSET m]` applies after sorting: skip `m` rows, then return at most `n`.

## Performance

Inserting 100,000 rows in batches of 1,000 per statement and then running filtering, grouping and sorting queries over them must take a few seconds at most.
