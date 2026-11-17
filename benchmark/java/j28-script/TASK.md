# j28-script: Interpreter for a small language

Java 21, standard library only. Put your solution under `bench-solutions/java/j28-script/src/main/java/`, using exactly the package and public signatures below. CI adds the hidden JUnit 5 tests, and the task passes only if every one of them passes.

```java
package bench.script;
public final class Interpreter {
    public static java.util.List<String> run(String source);
}

package bench.script;
public final class ScriptException extends RuntimeException {
    public ScriptException(int line, int column, String message);
    public int line();
    public int column();
}
```

`run` parses the whole program, then runs it, and returns the lines written by `print`, in order. Any error throws `ScriptException`. Its `getMessage()` must be `"<line>:<column>: <message>"`; the tests check only the position, not the message text.

## Positions

Lines and columns start at 1. Only `\n` ends a line; every other character, including `\r` and tab, is one column. A token's position is that of its first character. The end of input is the position just after the last character, so for a source ending in `\n` it is column 1 of the next line.

## Lexical rules

- Whitespace (space, tab, `\r`, `\n`) separates tokens. `//` starts a comment that runs to the end of the line.
- An integer literal is one or more ASCII digits. If its value doesn't fit in a `long`, that is an error at the literal.
- An identifier is an ASCII letter or `_`, followed by ASCII letters, digits or `_`.
- Keywords: `let fn if else while return print true false nil`.
- Operators and punctuation: `+ - * / % ( ) { } , ; = == != < <= > >= && || !`.
- Any other character is an error at that character.

## Grammar

```
program    = { statement } EOF
statement  = "let" IDENT "=" expr ";"
           | "fn" IDENT "(" [ params ] ")" block
           | "if" "(" expr ")" block [ "else" ( block | ifStatement ) ]
           | "while" "(" expr ")" block
           | "return" [ expr ] ";"
           | "print" expr ";"
           | IDENT "=" expr ";"
           | expr ";"
params     = IDENT { "," IDENT }
block      = "{" { statement } "}"
expr       = and { "||" and }
and        = equality { "&&" equality }
equality   = comparison { ( "==" | "!=" ) comparison }
comparison = additive { ( "<" | "<=" | ">" | ">=" ) additive }
additive   = term { ( "+" | "-" ) term }
term       = unary { ( "*" | "/" | "%" ) unary }
unary      = ( "-" | "!" ) unary | call
call       = primary { "(" [ expr { "," expr } ] ")" }
primary    = INT | "true" | "false" | "nil" | IDENT | "(" expr ")" | "fn" "(" [ params ] ")" block
```

- A statement starting with `fn` followed by an identifier is a declaration. `fn` followed by `(` starts an anonymous function expression.
- A statement starting with an identifier followed by `=` is an assignment.
- Binary operators are left-associative.
- A syntax error is reported at the first token that doesn't fit the grammar. If the program has a syntax error anywhere, nothing runs.
- Two more syntax errors: `return` outside any function body (at the `return`), and a repeated parameter name in one parameter list (at the repeat).

## Values

Integers (64-bit, with Java `long` overflow), booleans, `nil`, and functions.

- `print` writes integers in decimal, `true`/`false`, `nil`, `<fn NAME>` for a declared function and `<fn>` for an anonymous one.

## Semantics

- **Arithmetic** (`+ - * / %` and unary `-`) and **comparison** (`< <= > >=`) need integers. `/` and `%` behave like Java's `long` operators. Dividing by zero is an error at the operator.
- **`!`**, **`&&`** and **`||`** need booleans. `&&` and `||` short-circuit: the right operand is evaluated only when needed, and must then be a boolean too.
- **`==` and `!=`** accept any values. Integers and booleans compare by value, `nil` equals `nil`, functions compare by identity, and values of different types are never equal.
- An operand of the wrong type is an error at the operator. A condition in `if` or `while` that isn't a boolean is an error at the `if` or `while` keyword.
- **Scopes:** the top level is the global scope. Every block and every function call gets its own scope, nested lexically in the enclosing one.
  - `let x = e;` evaluates `e`, then declares `x` in the current scope. `fn name(...) {...}` declares `name` in the current scope when the statement runs.
  - Declaring a name that already exists in the same scope is an error at the name. Shadowing a name from an outer scope is allowed.
  - Assignment `x = e;` updates the nearest enclosing declaration of `x`. Reading or assigning an undeclared name is an error at the name.
- **Functions** are closures over the scope they were created in. They see later changes to captured variables, and assignments inside them change those variables.
- **Calls:** the callee is evaluated first, then the arguments from left to right. Calling something that isn't a function, or passing the wrong number of arguments, is an error at the call's `(`. The parameters are declared in the call's scope, and the function body's statements run in that same scope.
- **Return:** `return e;` returns `e`. `return;` and reaching the end of the body both return `nil`.
- **Depth:** calls nested at least 1,000 deep must work. Unbounded recursion must end in a `ScriptException`, never a `StackOverflowError`.
- **Speed:** a loop of one million iterations with a few operations each must finish within a few seconds.
