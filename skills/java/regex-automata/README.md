# regex-automata

- **Language:** Java 21 (JDK only)
- **Tags:** regex, automata, thompson-nfa, subset-construction, dfa-minimisation, canonical-form, equivalence, witness, differential-testing
- **Source round:** practice/java/20261120T0956Z

`ae.regex`: `RegexParser` (sealed `Node` AST, errors with exact index), `CharSet` (interval sets over UTF-16),
`Nfa` (Thompson, direct simulation), `Dfa` (subset construction over character-class intervals, Moore minimisation,
canonical form where equal languages are `equals`, product algebra, shortest accepted string, `count(n)`), and the
`Regex` facade (`matches`, leftmost-longest `find`/`findAll`, `equivalent`, shortest `distinguish` witness,
`intersect`/`union`/`minus`/`complement`).

Reuse notes: alphabets as intervals keep subset construction independent of 65536; minimal complete DFA sizes include the
dead state and count *intervals* (a gap on each side of a literal is two classes); test against `java.util.regex` with a
generator whose constructs have identical full-match semantics, verify the generator first, and assert both outcomes occur.
