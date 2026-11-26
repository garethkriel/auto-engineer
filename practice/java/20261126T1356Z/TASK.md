# Task: `ae.earley` — an Earley parser for arbitrary context-free grammars

Level 20 capstone. Overlap check: `benchmark/java/` names j01-roman .. j35-minisql were listed. Nearest are j07-expr (expression evaluation) and j28-script (scripting language); both are fixed-grammar evaluators. This task is a general CFG recogniser with ambiguity counting and tree enumeration, so none of them matches. Existing skill `peg-engine` is deterministic PEG; Earley handles ambiguous/left-recursive/nullable grammars, which PEG does not.

## Grammar text format (`Grammar.parse`)
- One rule per line: `Name -> alt | alt | ...`. A line starting with `|` adds alternatives to the previous rule's left-hand side. The same name may appear on several lines (alternatives accumulate in order of appearance).
- Names: `[A-Za-z_][A-Za-z0-9_]*`. Terminals: a single code point in single quotes, `'a'`; escapes `\'` and `\\` only (so `'\n'` is two-char-invalid). Symbols are separated by whitespace.
- An alternative with no symbols is epsilon (`A -> 'a' A |` and `A -> | 'a'`).
- `#` starts a comment unless inside quotes. Blank lines are ignored.
- The start symbol is the left-hand side of the first rule.
- Errors throw `GrammarException` whose message starts with `line N:` (1-based): bad token, missing `->`, undefined nonterminal (line of its first use), bad terminal, leading `|` with no previous rule, no rules at all (`line 0: no rules`).
- `Rule.toString()` is `S -> A 'b'` (epsilon: `S ->`). Rule index = position in `Grammar.rules()`.
- `Grammar.nullable()` = sorted set of nonterminals deriving the empty string.

## Parser (`EarleyParser(Grammar).parse(String input)` -> `ParseResult`)
Input is read as code points. Earley with the Aycock-Horspool nullable fix in the predictor.
- `accepted()`.
- On rejection: `errorOffset()` = the code-point index of the first input symbol that no chart item can consume (equals the input length when the input is a proper prefix of a sentence); `expected()` = sorted set of code points that could have been consumed at that offset. On acceptance `errorOffset() == -1` and `expected()` is empty.
- `chartItems()` = total number of distinct Earley items over all sets (complexity witness: left-recursive grammars must stay linear in input length).
- `treeCount()` -> `Optional<BigInteger>`: number of distinct parse trees, `0` when rejected, `Optional.empty()` when infinite (cyclic derivation `N =>+ N` over the same span inside a successful parse).
- `trees(int limit)` -> up to `limit` distinct trees, deterministic order: rules in grammar order, then first child span ending earliest, then recursively. Trees repeating the same nonterminal over the same span on one root-to-leaf path are excluded (so the list is finite even for cyclic grammars; for acyclic parses `trees(limit).size() == min(limit, treeCount)`).
- `Tree`: sealed `Leaf(codePoint)` and `Node(symbol, ruleIndex, children)`; `toString()` is an S-expression (`(S (A 'a') 'b')`, empty node `(S)`); `yield()` is the covered text.

## Success criteria
Tests-first suite passes with `mvn -q -B test`: grammar-format literals and error lines, recogniser tables (left/right/middle recursion, nullable chains, code points above the BMP), error offsets and expected sets, Catalan ambiguity counts, cyclic grammars, a linear chart-size test, and seeded random-grammar differential tests against an independent brute-force oracle (derivability fixpoint + cycle detection + counting), plus a hand-run mutation pass.
