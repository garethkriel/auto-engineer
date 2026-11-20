# peg-engine

- **Description:** PEG grammar engine in package `ae.peg`: grammar text parser (`Grammar.parse`) with static analysis
  (undefined rules, empty loop bodies, direct/indirect/nullable-prefix/predicate left recursion with cycle path),
  packrat interpreter (`PegParser`, memo keyed by rule, position and inside-predicate flag), parse trees (`Node`,
  `sexp`), and farthest-failure error reports (`PegParseException` with line, column, sorted expectations).
- **Language:** java (JDK 21, JUnit 5 only)
- **Tags:** peg, packrat, parser, grammar, static-analysis, left-recursion, error-reporting, differential-testing
- **Source round:** `practice/java/20261120T0157Z`
- **Tests:** 63. Table-driven PEG semantics, parse/render round trip, analysis messages, linear-evaluation bound,
  and a differential test against `java.util.regex` using atomic groups and possessive quantifiers
  (`(?>A|B)`, `X*+`, lookahead), plus memo-vs-plain agreement on mutated JSON documents.

Reuse notes: PEG choice is committed and repetition possessive, so a regex oracle needs atomic groups. Keep the
memo key separate for lookahead evaluations, or expectations recorded silently inside a predicate are lost later.
Recursion depth follows input nesting depth (400 levels tested).
