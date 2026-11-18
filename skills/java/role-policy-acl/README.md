# role-policy-acl

- **Description:** Refactoring pattern and reference code: string-switch access checker turned into enum-with-behaviour role policies (`Role`), `Action`, `Resource` record, `Decision` record; includes the legacy oracle and a grid differential test harness.
- **Language:** java
- **Tags:** refactoring, characterisation-tests, differential-testing, polymorphism, enum-strategy, access-control
- **Source round:** practice/java/20261118T1156Z
- **Reuse:** copy `LegacyAccess` + `DifferentialTest` shape for any refactor: keep old code as oracle in tests, iterate the cross-product of valid and invalid inputs, preserve guard order.
