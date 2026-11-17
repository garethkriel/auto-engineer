# markdown-lite

- **Description:** Strict Markdown-subset parser to a sealed-interface AST (`Block`, `Inline`) plus a safe HTML renderer. Headings, paragraphs, fenced code, quotes, lists, rules, emphasis/strong/code/links, escapes. Errors carry 1-based line/column; URL scheme allow-list; nesting caps; linear-time on unclosed delimiters.
- **Language:** java
- **Tags:** parser, ast, sealed-interface, pattern-matching, markdown, html-escaping, positioned-errors
- **Entry points:** `Markdown.parse`, `Markdown.toHtml`, `Markdown.render`
- **Source round:** practice/java/20261117T2056Z
