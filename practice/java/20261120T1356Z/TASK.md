# Task: ae.minilang — a small language toolchain (level 20 capstone)

Overlap check: nothing in `benchmark/java/` or `skills/java/` is a language implementation of this kind
(closest: `peg-engine` is a grammar engine, `hm-type-inference` types a lambda calculus, `mini-sql-engine` is SQL).

## Goal
`ae.minilang.Lang`: lex, parse, check, optimise and run a tiny imperative language through four interchangeable
engines — tree-walking interpreter, stack-bytecode VM, and each of those after an AST optimiser. All four must
produce identical output and identical error kind. Standard library only.

## Language
- Values: 64-bit signed integers only. Truth = non-zero; comparisons, `!`, `&&`, `||` yield 0 or 1.
- Program = top-level `fn name(a, b) { ... }` definitions (hoisted, mutual recursion allowed) and top-level statements (the "main" body).
- Statements: `let x = e;` `x = e;` `if (e) {..} [else {..} | else if ...]` `while (e) {..}` `return [e];` `print e;` `{..}` `e;`
- Expressions: integer literals, variables, calls `f(a, b)`, unary `- !`, binary `* / %`, `+ -`, `< <= > >=`, `== !=`, `&&`, `||` (short-circuit, left-assoc, C precedence).
- `//` line comments. Keywords: let fn if else while return print.

## Semantics decisions (assumptions)
- Block scoping with shadowing; `let` redeclaring a name in the same scope (params and function body share one scope) is a NAME error; `let x = x + 1` reads the outer `x`.
- Functions cannot see main's variables (no globals, no closures). A function falling off its end returns 0; `return;` returns 0; top-level `return` halts the program.
- Evaluation order is left to right, arguments left to right.
- `/` truncates toward zero, `%` is the Java remainder. Overflow (`+ - * -x`, `Long.MIN / -1`) -> OVERFLOW; division/modulo by zero -> DIV_ZERO.
- Call depth limit 100 (main is depth 1): calling at depth 100 -> STACK_OVERFLOW. A per-engine step budget -> STEP_LIMIT (never compared across engines).
- Static errors before any code runs: SYNTAX (with `line L, col C`), NAME (unknown/duplicate variable, function, duplicate param), ARITY.
- Result = printed values so far + error kind (null if none); a runtime error keeps the output printed before it.
- The optimiser must preserve output and error kind: it never folds an expression that would throw, never drops an expression that could throw or call, but removes dead branches, `while (0)`, pure expression statements and code after `return`.
- A bytecode verifier checks jump targets, stack-depth consistency, slot bounds, call arity and that every path ends in RET; the VM refuses unverified code.

## Success criteria
- Hand-computed literal tests for lexer, parser (precedence/assoc), checker errors with positions, every operator, scoping, recursion, depth limit, step limit.
- Printer round trip (print(parse(x)) re-parses to the same text) and differential tests: seeded random terminating programs (with overflow/div-zero provocations) run on all four engines must agree; the optimiser's bytecode is never longer.
- Verifier tested on crafted bad bytecode (one defect each, plus a control that loads).
- Arithmetic helper checked against BigInteger.
