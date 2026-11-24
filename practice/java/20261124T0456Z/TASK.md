# 20261124T0456Z: ae.prolog, a Prolog-subset engine (level 20 capstone)

Language: Java 21, standard library only, JUnit 5. Package `ae.prolog`.

## Overlap check
`benchmark/java/` (j01-j35: roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, ... minisql) has no
logic-programming / unification / backtracking-with-cut task. Nearest: j07-expr (arithmetic evaluator) and j35-minisql;
neither involves unification, SLD resolution, cut or a clause database. `skills/java/datalog-engine` is bottom-up
and has no cut, lists, or arithmetic; this task is top-down SLD with an explicit choicepoint stack.

## Design
- `Term` = sealed: `Atom`, `Int` (long), `Var` (mutable binding), `Struct` (name + args). Lists are `'.'(H,T)` ending in `[]`.
- `Parser`: tokenizer (line/column in errors, `%` and `/* */` comments, quoted atoms) + operator-precedence parser
  (`:- ; -> , \+ = \= == \== is =:= =\= < > =< >= + - * // mod rem`, prefix `-`, negative literals, lists).
- `Engine`: clause database, **iterative** machine (continuation list + choicepoint stack + trail), so deep recursion
  does not overflow the Java stack. Cut barriers, if-then-else, `\+`, `call/1..4`, `findall/3`, arithmetic with
  exact (overflow-checked) long maths, `assertz/asserta/retract` (logical update view), `sort/2 msort/2 compare/3`,
  type tests, a Prolog-text prelude (append, member, reverse, length, select, permutation, between, once, ...).
- Solutions are lazy (`Iterator<Solution>`); each Solution holds deep copies, so later backtracking cannot change it.
- Errors are `PrologError` with a `Kind` (SYNTAX, INSTANTIATION, TYPE, EXISTENCE, EVALUATION, RESOURCE, PERMISSION).
- Step limit (`maxSteps`) turns runaway queries into a RESOURCE error.

## Success criteria
`mvn -q -B clean test` passes with: literal tests for parsing, cut/negation/if-then-else semantics, error kinds;
differential tests against Java oracles (list splits, permutation counts, n-queens counts, sorting, arithmetic with
`Math.*Exact`, parse(format(t)) round trip on random terms); deep-recursion tests (200000 steps); and a hand-run
mutation check recorded in the lesson.
