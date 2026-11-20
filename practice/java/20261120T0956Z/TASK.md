# Capstone: regular-expression engine built on automata (`ae.regex`)

## Task
Build a small regex engine that compiles a pattern to an NFA (Thompson), then to a complete DFA over
character classes, minimises it to a canonical form, and offers language-level algebra
(equivalence with shortest distinguishing witness, intersection, complement, counting).
Standard library only. The semantics of full-string matching must equal `java.util.regex.Pattern.matches`
for the supported syntax (used as the test oracle).

## Assumptions (nobody to ask)
- Alphabet = UTF-16 code units 0..0xFFFF. Strings are matched code unit by code unit.
- Supported syntax: literals, `.` (any char except `\n \r \u0085    `, like Java), `|`, groups `( )`
  (non-capturing semantics), `* + ?`, `{m} {m,} {m,n}` (bounds <= 1000), classes `[a-z_] [^...]` with ranges and
  escapes, escapes `\d \D \w \W \s \S \n \t \r \f` and `\` + any non-alphanumeric char as a literal.
- Rejected with `RegexSyntaxException(message, index)`: unclosed group/class, unmatched `)`, nothing to repeat
  (`*a`, `{2}`), stacked quantifiers (`a**`, `a*?`), malformed `{`, reversed range `[z-a]`, empty class `[]`,
  nested `[` inside a class, unknown alphanumeric escape, a trailing backslash, anchors `^ $` (unsupported).
- `{` needs an atom before it and a valid quantifier body; `}` and `]` outside a class are literals.
- Search semantics (`find`): leftmost start, then longest match (POSIX-like), not Java's leftmost-first.

## API
- `ae.regex.CharSet`: immutable set of chars as sorted disjoint ranges: `of`, `range`, `union`, `intersect`,
  `minus`, `negate`, `contains`, `size`, `isEmpty`, `ANY`, `EMPTY`.
- `ae.regex.RegexParser.parse(String) -> Node` (sealed AST), `Nfa.build(Node)`, `Nfa.matches(String)`.
- `ae.regex.Dfa`: `fromNfa`, `matches`, `longestMatchEnd(text, from)`, `minimize()` (canonical form: equal languages give
  `equals` DFAs), `complement`, `intersect`, `union`, `minus`, `equivalent`, `shortestAccepted`, `count(n)`, `stateCount`.
- `ae.regex.Regex`: `compile`, `matches`, `find`, `findAll`, `equivalent`, `distinguish(other)` (shortest string in the
  symmetric difference), `example`, `count(n)`, `minimalStateCount`, `intersect`, `complement`, `isEmptyLanguage`.

## Success criteria
1. Table tests of matching and of syntax errors with exact error indices.
2. Minimal complete DFA state counts for known languages (dead state included when reachable).
3. Differential tests with a seeded random pattern generator against `java.util.regex` on every string up to a bounded
   length: NFA == DFA == minimised DFA == Pattern; `find` vs brute force; equivalence/witness vs enumeration;
   canonical-form equality agrees with product-construction equivalence; intersect/complement vs boolean combination;
   `count(n)` vs enumeration.
4. `mvn -q -B clean test` passes.
