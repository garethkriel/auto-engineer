# shell-word-expansion

- **Description:** `ae.shellx.WordExpander.expand(line, env)` expands a command line into words using a documented bash subset: quote lexing (`'..'`, `".."`, backslash), bash-faithful brace expansion (nested lists, integer sequences with step and zero padding, "a `}` before any comma is plain text"), parameter expansion (`$N`, `${N}`, `:-`, `-`, `:+`, `+`, `${#N}`, nested operands), word splitting of unquoted results, `""` keeps an empty word. Errors carry the 0-based position.
- **Language:** java
- **Tags:** shell, lexer, brace-expansion, parameter-expansion, word-splitting, differential-testing, bash-oracle
- **Source round:** practice/java/20261125T0756Z
- **Design:** characters carry a kind (PLAIN, DQ, LIT, SPLIT, MARK) so each stage knows what it may touch; MARK chars are zero-width and end `$NAME` scanning at a closing quote.
- **Testing pattern:** the real `bash -c 'set -f; printf "%s\0" START ...'` is the oracle for 600 random lines plus 300 malformed lines (skipped if bash is missing). Reuse this for any "subset of an existing tool" task.
- **Tests:** `mvn -q -B test` (12 tests).
