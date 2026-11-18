# refactor-library-lending

- **Description:** worked example of a level-18 refactoring: a god class (`LegacyLibrary`, Object[] records, magic strings, duplicated tier constants) turned into `Library` + `Tier` enum, `Member`, `Loan` record, `HoldQueue`, `Catalog`, sealed `Result`, with identical string results.
- **Language:** java
- **Tags:** refactoring, characterisation-tests, differential-testing, enum-with-behaviour, sealed-result, guard-order
- **Source round:** 20261118T1456Z (`practice/java/20261118T1456Z`)

## Technique
1. Write a literal characterisation table in an abstract contract class; run it green on the legacy class first.
2. Run the same table on the refactored facade (subclass with a different factory).
3. Seeded random sequences replay on both; compare every result and every query, and assert the set of outcome codes seen covers all codes.
4. Mutation-check: change one constant (cap, queue size) and confirm both the table and the differential test fail.

Guard order in each command is copied verbatim from the legacy code (e.g. checkout: day, member, book, blocked, limit, dup, unavailable, held).
