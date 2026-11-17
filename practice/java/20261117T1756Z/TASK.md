# Task 20261117T1756Z: `tpl` mini template engine (level 13, language tools)

Overlap check: `benchmark/java/` names are j01-roman … j35-minisql (expression evaluator j07, json j14, script j28, regex j22, minisql j35, cron j20).
A text template language is none of those: it is a text-to-text engine with block tags, filters and HTML escaping, not an expression/JSON/SQL/script/regex task.

## Goal
Package `tpl`. `Template.parse(String)` compiles a template once (immutable, reusable); `render(Map<String,?>)` produces text.

## Syntax
- `{{ operand | filter | filter "arg" }}` output. Operand: dotted path (`user.name`, `items.1` for list index), string literal (`"x"` or `'x'` with `\` escapes) or integer.
- Output is HTML-escaped (`& < > " '`) unless `raw` appears anywhere in the filter chain.
- Filters: `upper`, `lower`, `trim`, `length` (String/Collection/Map), `raw`, `default "x"` (replaces missing, null or empty string).
- `{% if cond %}…{% elif cond %}…{% else %}…{% endif %}`; cond grammar: `or` < `and` < `not` < comparison (`==`, `!=`) of operands. Numbers compare by value across Integer/Long/Double/BigDecimal. Truthiness: missing/null/false/""/0/empty collection are false.
- `{% for x in path %}…{% endfor %}` over any Iterable; inside, `loop.index` (1-based), `loop.index0`, `loop.first`, `loop.last`, `loop.length`. Loop variables shadow outer ones and vanish after the loop.
- `{# comment #}`; `{%-`, `{{-`, `-%}`, `-}}` strip adjacent whitespace.

## Errors
Unchecked `TemplateException` (message, `line()`, `column()`, both 1-based) with subclasses `TemplateSyntaxException` (parse time: unclosed tag/string/block, unexpected end tag, unknown tag or filter, bad token, missing operand) and `TemplateRenderException` (undefined variable, non-iterable loop target, `length` of a non-sized value). Parsing arbitrary text must only ever throw `TemplateException`.

## Success criteria
`mvn -q -B test` passes: unit tests, error positions, truthiness table, a seeded fuzz test (only `TemplateException` escapes) and a time-bounded 100k-iteration render.
