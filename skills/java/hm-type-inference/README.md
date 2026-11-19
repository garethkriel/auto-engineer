# hm-type-inference

- **Language:** Java 21 (standard library only)
- **Tags:** type-inference, hindley-milner, unification, let-polymorphism, parser, sealed-interfaces, property-testing
- **Source round:** practice/java/20261119T1456Z

Principal-type inference (Algorithm W) for a tiny functional language: lambdas, application, `let`, `let rec`, `if`, pairs, Int/Bool/list builtins.
Entry point `ae.hm.Hm.typeOf(src)` returns e.g. `(a -> b) -> (c -> a) -> c -> b`; failures are `ParseException` / `TypeException` (MISMATCH, OCCURS, UNBOUND) with 1-based line/col.

Reusable ideas:
- Single mutable substitution + occurs check on `bind`; `resolve` applies it; `checkInvariant()` verifies acyclicity.
- Generalise only variables not free in the (resolved) environment; `let rec` binds the name monomorphically, then generalises.
- Pretty printing renames variables by first appearance, with one shared naming for multi-type error messages.
- Tests: known principal types, exact error positions, generalisation traps, and a type-directed random term generator with
  independent oracles (instance matcher, let-inlining, print/parse round trip, `+ 1` mismatch) plus mutation kill checks.
