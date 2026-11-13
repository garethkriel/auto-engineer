# Task (level 2, topic: static vs instance state)

Implement `ae.account.Account`, a bank account with per-instance and class-level state.

- Instance state: `id` (int), `owner` (String), `balanceCents` (long).
- Static state: id sequence starting at 1 (`Account.open(owner)` assigns the next id) and `Account.openCount()` = accounts opened since last `resetForTests()`.
- `static Account open(String owner)`: owner non-null/non-blank else IllegalArgumentException; balance 0.
- `void deposit(long cents)` / `void withdraw(long cents)`: cents must be > 0 (IAE); withdraw beyond balance throws `IllegalStateException`, balance unchanged. Overflow on deposit throws ArithmeticException, balance unchanged.
- `void transferTo(Account other, long cents)`: atomic (either both change or neither); other null or same account -> IAE.
- `equals/hashCode` by id only; `toString()` = `Account#<id>[<owner>: <dollars>.<cc>]`, e.g. `Account#1[ann: 12.05]`.
- `static void resetForTests()` resets the sequence and count.

Success: `mvn -q -B test` passes.
Assumption: single-threaded use.
