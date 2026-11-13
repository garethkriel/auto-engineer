# int-range

- **Description:** immutable half-open integer range `[start, end)` with `contains`, `overlaps`, `merge`, value `equals`/`hashCode`, `toString`. Pattern for small immutable value classes with validated constructors.
- **Language:** java
- **Tags:** value-class, immutability, equals-hashCode, overflow-safe, ranges
- **Source round:** practice/java/20261113T1544Z
- **Key points:** `length()` casts to long before subtracting; empty ranges never overlap; adjacent ranges merge but do not overlap.
