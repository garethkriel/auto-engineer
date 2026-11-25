# Task (level 20 capstone): `ae.shellx` shell word expansion

Overlap check: `benchmark/java` directory names (j01-roman, j02-wordfreq, j03-lru, j04-csv, j05-intervals, j06-lcs, j07-expr, j08-graph, j09-bank, j10-counter, j11-streams, j12-fileindex, j13-ratelimit, j14-json, j15-topo, j16-ranges, j17-cache, j18-diff, j19-allocate, j20-cron, j21-sheet, j22-regex, j23-segtree, j24-queue, j25-bizhours, j26-rwlock, j27-primes, j28-script, j29-kvlog, j30-miniserver, j31-ranked, j32-fsm, j33-pricing, j34-vclock, j35-minisql) were read. None is a shell lexer / brace + parameter expansion (j28-script is a scripting task name; this is a word-expansion pipeline with no evaluator). Assumed no overlap.

## API
`ae.shellx.WordExpander.expand(String line, Map<String,String> env) -> List<String>`; throws `ae.shellx.ShellSyntaxException` (unchecked, `position()` = 0-based index in `line`).

## Semantics (a documented subset of bash, pathname expansion off, i.e. `set -f`)
Stages in bash order: quote lexing -> brace expansion -> parameter expansion -> word splitting -> quote removal.
1. Unquoted whitespace (space, tab) separates words. `'...'` literal. `"..."` allows `$` expansion; inside, `\` escapes only `"`, `\`, `$` (otherwise the backslash stays). Unquoted `\c` yields literal c. Unterminated quote or trailing backslash -> ShellSyntaxException at the opening quote / the backslash. `""` and `''` keep an empty word alive.
2. Brace expansion (unquoted braces only, not `${`): `{a,b}` comma lists (nested, with prefix/suffix, cartesian order: left group outermost), integer sequences `{1..3}`, `{3..1}`, `{1..10..4}` (step magnitude), negatives, zero padding (`{01..03}`). A group without a top-level comma and not a sequence stays literal; unmatched `{` stays literal. More than 10_000 words -> IllegalStateException.
3. Parameters: `$NAME`, `${NAME}`, `${NAME:-w}`, `${NAME-w}`, `${NAME:+w}`, `${NAME+w}`, `${#NAME}`. NAME = `[A-Za-z_][A-Za-z0-9_]*`. `$0`-`$9` expand to empty (no positional arguments). `$` followed by anything else is literal (special parameters such as `$$`, `$?` are outside the subset and not tested against bash). Operand words are expanded recursively (nested `${...}` and quotes allowed). Anything else inside `${...}` -> ShellSyntaxException ("bad substitution") at the `${`; no closing brace -> exception at the `${`.
4. Unquoted expansion results are split on runs of space/tab/newline; empty unquoted result produces no word; quoted expansions never split.

## Success criteria
- Literal tests, structural invariants and a seeded differential test against real `bash` (skipped if bash missing).
- Mutation check of the main rules.
- `mvn -q -B clean test` green.
