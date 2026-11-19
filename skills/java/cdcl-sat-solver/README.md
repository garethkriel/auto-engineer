# cdcl-sat-solver

- **Description:** CDCL SAT solver (`ae.sat.Solver`): two watched literals, first-UIP learning, non-chronological backjumping, VSIDS-style activity, phase saving, Luby restarts, conflict limit. DIMACS parser/writer, CNF encodings (pigeonhole, queens, colouring, seeded random 3-SAT), and independent checkers: `ModelChecker` for SAT, `RupChecker` for UNSAT proofs.
- **Language:** java
- **Tags:** SAT, CDCL, constraint solving, proof checking, differential testing, DIMACS
- **Source round:** practice/java/20261119T1656Z
- **Pattern to reuse:** make every answer self-certifying (model check / RUP proof check by code that shares nothing with the solver), test through one verifying helper, differential-test against brute force on seeded instances, and mutation-check the learning step. Solver instances are single-use.
