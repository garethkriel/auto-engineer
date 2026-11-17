# Task: `ae.mdlite` Markdown-subset parser and HTML renderer (level 13)

Overlap check: benchmark dirs j04-csv, j07-expr, j14-json, j18-diff, j20-cron, j22-regex, j28-script, j35-minisql are not markup/document-structure tasks; earlier rounds covered a template engine (tpl) and a TOML parser (tconf). A block + inline markup parser with a sealed AST and safe HTML rendering is different. Assumption: positions are 1-based line/column of the offending token.

## API
- `Markdown.parse(String) -> Document(List<Block>)`; `Markdown.toHtml(String)`; `Markdown.render(Document)`.
- Errors: `MarkdownException(line, column, message)`.
- `sealed interface Block`: `Heading(level, inlines)`, `Paragraph(inlines)`, `CodeBlock(lang, text)`, `Quote(blocks)`, `ListBlock(ordered, items: List<List<Inline>>)`, `Rule`.
- `sealed interface Inline`: `Text`, `Emph`, `Strong`, `Code`, `Link(text, url)`.

## Rules
- Blocks: `#`..`######` + space (or bare `#`); paragraphs (soft breaks kept as `\n`); fenced ``` with optional language `[A-Za-z0-9_+-]*` (else error at the language column); unclosed fence -> error at the fence; `---`/`***`/`___` (3+) rule; `>` quotes parsed recursively (depth > 50 -> error); `-`/`*` bullet and `N.` ordered lists, items continue on lines indented 2+ spaces; a kind change or blank line starts a new list.
- Inlines: `*em*`, `**strong**`, `` `code` ``, `[text](url)`, backslash escapes of `\ * _ ` [ ] ( ) # >`. A `*` next to whitespace or without a closer is literal; `[text]` without `(url)` is literal; `[text](url` without `)` -> error at the `[`. Inline nesting deeper than 100 -> error.
- Safety: all text/code/attributes HTML-escaped; raw HTML never survives; only `http`, `https`, `mailto` and scheme-less urls allowed (`javascript:`, `data:`, `//host`, whitespace/control chars in url -> error at the `[`).

## Success
`mvn -q -B clean test` passes (hand-written examples, error positions, full render, fuzz, time-bounded adversarial input).
