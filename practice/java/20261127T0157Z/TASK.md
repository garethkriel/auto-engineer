# Task: ae.datalog — Datalog with stratified negation (level 20 capstone)

Language: Java 21, standard library only, JUnit 5. Package `ae.datalog`.

## Design
- `Term` (sealed): `Var(name)` / `Const(text)`. Variables start with an uppercase letter or `_`; constants are lowercase
  identifiers or (optionally negative) integers. Each bare `_` is a fresh anonymous variable.
- `Atom(pred, args)`; `Literal` (sealed): `Pos(Atom)`, `Neg(Atom)`, `Eq(Term,Term)`, `Neq(Term,Term)`.
- `Rule(head, body)`; a rule with empty body is a fact and must be ground.
- Grammar: `stmt := atom '.' | atom ':-' lit (',' lit)* '.' | '?-' atom '.'`; `lit := 'not' atom | atom | term ('='|'!=') term`;
  `%` starts a comment to end of line.
- `Parser.parse(String) -> Program(List<Rule> rules, List<Atom> queries)`; errors throw `DatalogException` with `line:col`.
- Validation (`Engine` constructor): consistent arity per predicate; safety = every variable in the head, in a `not`,
  or in `=`/`!=` occurs in a positive atom of the same rule (`X = c` with X otherwise unbound is unsafe: assumption, keeps things simple);
  stratification = no cycle through negation, else `DatalogException` naming the cycle predicates.
- `Engine.evaluate(Mode)` with `Mode.NAIVE` (re-run all rules to fixpoint) and `Mode.SEMI_NAIVE` (delta-driven per stratum).
  Both must produce the identical `Model` (`Map<String, Set<List<String>>>`).
- `Engine.query(Atom)` returns bindings (`List<Map<String,String>>` keyed by the query's variables) sorted by tuple, ints before
  atoms, ints numerically, atoms lexicographically; ground query returns one empty map if true, empty list if false.
- `Engine.stats()` = number of rule-body join steps in the last evaluate (used to show semi-naive does less work).

## Success criteria
`mvn -q -B clean test` passes: literal-vector tests (ancestor, same-generation, win-move via stratified negation,
unreachable nodes), parse/validation error tests asserting messages, random-graph differential tests against Floyd-Warshall and a
brute-force negation oracle, naive == semi-naive on random programs, a work-count inequality, and a mutation pass over the engine.
