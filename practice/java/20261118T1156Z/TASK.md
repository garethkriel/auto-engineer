# Task: refactor a messy access-control checker (level 18, refactoring)

Topic: refactoring (characterisation tests first, replace conditionals with polymorphism).
Overlap check: `benchmark/java/` names (roman, wordfreq, lru, csv, ..., fsm, pricing, minisql) were listed; an access-control rule checker matches none of those algorithms.

## Starting code (supplied, messy but working)
`src/test/java/ae/acl/LegacyAccess.java`: one static method `check(role, action, type, owner, user, archived, sensitivity)`
returning `"ALLOW"` or `"DENY:<reason>"` via deeply nested if/else on role and action strings. It is kept in the test tree as the oracle.

## Goal
Create `ae.acl` in `src/main/java` with the same observable behaviour but no string-switch spaghetti:
- `Role` (enum, per-role policy via polymorphism), `Action` enum, `Resource` record, `Decision` record (`toString()` is the legacy string),
- `AccessChecker.check(String,String,String,String,String,boolean,int)` returning exactly the legacy string,
- `AccessChecker.decide(Role, Action, Resource, String user)` typed API.

## Process
1. Write characterisation tests (literal table of legacy answers) and run them against `LegacyAccess` first.
2. Refactor into `ae.acl`; run the same table against the new code.
3. Differential test: exhaustive grid of inputs, legacy vs refactored, identical strings.

## Success criteria
`mvn -q -B clean test` passes; the grid covers unknown/null/mixed-case/padded roles and actions, null type/owner/user, sensitivity -1..4.

## Assumption
Legacy quirks (validation order: role, action, sensitivity, archived) are behaviour to preserve.
