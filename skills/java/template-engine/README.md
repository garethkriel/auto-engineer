# template-engine

- **Description:** `tmpl.Template`: a small Jinja-like template engine. A mode-switching lexer (text / `{{ }}` / `{% %}` / `{# #}` with `-` whitespace trim markers), a recursive-descent block parser and expression parser (`or` < `and` < `not` < comparison, parentheses, filters with literal args), sealed `Node`/`Expr` ASTs evaluated by pattern-matching `switch`, lexical scopes that never mutate the caller's map, HTML escaping with `raw`, and syntax/render errors that report exact 1-based line and column (every token keeps an absolute source offset; `Positions` converts it).
- **Language:** java (21)
- **Tags:** tokeniser, recursive descent, sealed interface, pattern matching, AST, evaluator, error positions, template, fuzz test
- **Source round:** 20261117T1656Z
- **Reuse notes:** copy `Lexer`/`ExprParser`/`Positions` for any small language that needs precise error positions; the depth limit (100) in the parsers prevents `StackOverflowError` on hostile input; EOF errors point at the end of the last token.
- Tests: `mvn -q -B test` (67 tests: behaviour, exact error positions, reuse, seeded fuzz).
