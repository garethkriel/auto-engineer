# datalog-engine

- **Description:** small Datalog engine (`ae.datalog`): parser with line/column errors, arity/safety/stratification checks, stratified negation, `!=`, anonymous `_`, queries; naive and semi-naive bottom-up evaluation.
- **Language:** java
- **Tags:** datalog, fixpoint, semi-naive, stratification, parser, logic-programming
- **Source round:** practice/java/20261119T0956Z
- **Use:** `Datalog.run(source)` -> `Result.relation(pred)`, `Result.answers(i)`; `Datalog.run(source, Mode.NAIVE)` is the oracle.
- **Tests:** 25 (classic programs, error positions, Floyd-Warshall oracle, random naive vs semi-naive differential).
