# notification-renderer

- **Language:** java
- **Tags:** refactoring, strategy-pattern, characterisation-tests, differential-testing, sealed-interface
- **Source round:** 20261118T1356Z

Refactoring example: a string-typed channel/priority renderer (email, sms, push, slack) split into `Priority`,
`Channel` strategies, `Text` helpers, a sealed `Outcome` and a `Dispatcher` (guard order preserved). `Notifier.render`
keeps the legacy signature. The legacy implementation stays in `src/test` as an oracle; `DifferentialTest` compares
>100k edge-value combinations, `CharacterisationTest` holds literal expectations run against both versions.

Reuse the pattern: literal table first against legacy, then refactor, then grid vs oracle.
