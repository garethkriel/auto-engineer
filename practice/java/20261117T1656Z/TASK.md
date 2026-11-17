# Task: `tmpl` — a small template engine (tokeniser, recursive-descent parser, sealed AST, evaluator)

Level 13 (language tools). Topic: tokenisers, recursive-descent parsers, sealed-interface ASTs with
pattern matching, evaluators, error messages with line and column.

**Overlap check.** `benchmark/java/` names: j07-expr, j14-json, j22-regex, j28-script, j35-minisql, j04-csv,
j20-cron … The nearest are expression/script/SQL parsers. This task is a *text template engine*
(text + `{{ }}` output + `{% %}` blocks + filters + HTML escaping + whitespace control) which is a different
problem: it has a mode-switching lexer over mixed text, block nesting, scopes and rendering rather than an
arithmetic/statement language. Its expression sub-language is only a boolean/comparison condition language.

## Public API (package `tmpl`)
- `Template.parse(String source)` → immutable, reusable `Template`; throws `TemplateSyntaxException`.
- `String Template.render(Map<String, ?> context)`; throws `TemplateRenderException`.
- `TemplateException` (unchecked base) has `getLine()`, `getColumn()` (1-based); messages end with
  `(line L, column C)`.

## Syntax
- `{{ expr | filter | filter(arg) }}` output; value is HTML-escaped (`& < > " '`) unless the `raw` filter is used.
  `null`/missing renders as empty. Whole-valued doubles render without `.0`.
- `{# comment #}` produces nothing.
- `{% if c %}…{% elif c %}…{% else %}…{% endif %}`; `{% for x in expr %}…{% endfor %}`.
  Inside a loop: `loop.index` (1-based), `loop.first`, `loop.last`. The loop variable shadows and does not leak.
  Iterating `null`/missing is an empty loop; iterating a non-iterable is a render error.
- Whitespace control: `{{-`, `{%-`, `{#-` strip whitespace before; `-}}`, `-%}`, `-#}` strip whitespace after.
- Expressions: paths `a.b.0` (Map keys / List indexes, missing → null), string literals `'x'`/`"x"` (escapes `\\ \' \" \n`),
  non-negative numbers, `true`, `false`, `null`, comparisons `== != < <= > >=`, `not`, `and`, `or`
  (precedence not > comparison > and > or), parentheses.
- Truthiness: null, false, "", 0, empty collection/map are false; everything else true.
- Numbers compare by value (`1 == 1.0`); strings compare lexicographically; ordering a number with a string is a render error.
- Filters: `upper`, `lower`, `trim`, `length` (string/collection/map), `default(x)` (null or "" → x),
  `join(sep)`, `raw`. Unknown filter / wrong arity are syntax errors found at parse time.
- Syntax errors (unterminated delimiters/strings, unknown tag/filter, unclosed or stray blocks, `else` twice,
  `elif` after `else`, bad expressions, nesting deeper than 100) carry the line/column of the offending token;
  "unclosed block" points at the opening tag.

## Success criteria
`mvn -q -B test` passes: behaviour tests for every feature above, exact line/column assertions for errors,
reuse/non-mutation tests, and a seeded fuzz test that random garbage only ever yields `TemplateException` subclasses.
Assumptions: no negative number literals (so `{{-` is always a trim marker); no `in` operator; lenient missing variables.
