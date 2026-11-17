# template-engine

- **Description:** Mini text template engine (`tpl.Template`): `{{ path | filters }}`, `{% if/elif/else %}`, `{% for %}` with `loop.*`, comments, whitespace control, HTML auto-escape. Two-stage parse (raw pieces, then recursive descent over a sealed-interface AST) with 1-based line/column errors for parse and render failures.
- **Language:** java
- **Tags:** tokeniser, recursive-descent, sealed-interface, pattern-matching, error-positions, text-templates
- **Source round:** practice/java/20261117T1756Z
- **Reuse notes:** `Source` maps offsets to line/column; `Parser.lex` + `ExprParser` is a template for small tag/expression grammars; immutable compiled `Template` is thread-safe. Known limit: a literal `}}` inside a string literal ends the tag.
- **Tests:** `mvn -q -B test` (55 tests incl. seeded fuzz and a time-bounded 100k-iteration render).
