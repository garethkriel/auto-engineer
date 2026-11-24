# 20261124T1557Z — parser generator toolkit (level 20 capstone)

## Task
Package `ae.parsegen`: a context-free grammar toolkit built test-first.

- `Grammar.parse(text)`: text format `Lhs -> alt | alt`, one rule per line, `#` comments, `eps` or `ε` is the empty
  alternative, first LHS is the start symbol, symbols without a rule are terminals. Rejects (IllegalArgumentException):
  missing arrow, empty alternative, `eps` mixed with symbols, reserved symbols (anything starting with `$`),
  unproductive nonterminals, no rules.
- `Analysis`: nullable, FIRST (symbols and sequences), FOLLOW (with `$`).
- `LL1.build(g)`: predictive table, structured conflicts, table-driven parser producing `Tree`.
- `Slr.build(g)`: LR(0) automaton, SLR(1) action/goto, structured conflicts (shift/reduce, reduce/reduce; shift wins
  in the table), shift-reduce parser producing `Tree`.
- `Earley`: recogniser and `firstError` (index of the first token that is not a viable prefix; `n` when only the
  end of input is wrong; `-1` when accepted). Used as the independent oracle.
- `Generator`: `enumerate(g, maxLen)` all sentences up to a length (epsilon-free grammars only) and seeded `random`
  sentences for any grammar.
- `Tree` (symbol, production id, children, `sexp()`, `yield()`, `validate(g)`), `ParseException(index, expected)`.

## Design / trade-offs
- Parsers report `ParseException.index()` = position of the offending token (`tokens.size()` for end of input) and
  `expected()` = sorted terminals acceptable in the detecting state/table row (`$` = end). LL(1), SLR(1) and Earley
  must all agree on the index (viable-prefix property); `expected` may legitimately differ.
- A parser built over a grammar with conflicts throws IllegalStateException from `parse`.
- A literal `$` in the token list is rejected with IllegalArgumentException.
- Production ids follow rule order, alternatives left to right, from 0.

## Success criteria
`mvn -q -B clean test` passes. Textbook literals (dragon-book FIRST/FOLLOW sets, 12-state and 10-state SLR automata,
LL(1)-but-not-SLR, SLR-but-not-LL(1), dangling else), exhaustive all-strings differential against enumeration,
randomized cross-engine differential (two equivalent grammars, LL(1) vs SLR vs Earley), tree validity, deep input.

## Overlap check
Listed `benchmark/java/` (j01-roman … j35-minisql): j07-expr is expression evaluation, j28-script a script
language; none is a grammar-analysis / parser-generator task. Existing skills peg-engine, minilang-toolchain,
regex-automata are PEG / hand-written parsers / regex, not FIRST/FOLLOW + LL(1)/SLR table construction.

## Assumptions
Only the standard library. Recursion depth of tree conversion is bounded by tree depth; tested at ~1000 terms.
