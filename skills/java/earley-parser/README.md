# earley-parser

- **Description:** Earley parser for arbitrary context-free grammars (`ae.earley`): text grammar format with line-numbered errors, Aycock-Horspool nullable handling, recognition with error offset and expected-terminal set, exact (BigInteger) parse-tree counting with infinite-ambiguity detection, deterministic bounded tree enumeration, linear chart for left recursion.
- **Language:** java
- **Tags:** parsing, earley, context-free-grammar, ambiguity, nullable, oracle-tests
- **Source round:** `practice/java/20261126T1356Z`

Design points: items are packed longs (rule, dot, origin); completed spans are stored per (nonterminal, start, end) so counting and enumeration work from the chart alone. Counting only descends into spans on a feasible full derivation (forward and backward reachability), so a cycle seen on the recursion stack is a real `N =>+ N` and means infinite ambiguity. Tests: 76, including an independent derivability-fixpoint oracle over 300 seeded random grammars. Run `mvn -q -B test`.
