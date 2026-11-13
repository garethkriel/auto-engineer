# account-static-state

- Description: `Account` class showing static (id sequence, open count) vs instance (balance) state; validated deposits/withdrawals, overflow-safe `Math.addExact`, atomic transfer (validate everything, then mutate), id-based equals/hashCode.
- Language: java
- Tags: static, instance, equals-hashCode, toString, validation, atomic-update
- Source round: practice/java/20261113T1156Z
- Tip: reset static state in `@BeforeEach` so tests are order-independent.
