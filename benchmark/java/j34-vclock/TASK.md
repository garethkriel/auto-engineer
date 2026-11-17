# j34-vclock: Replicated store with vector clocks

Java 21, standard library only. Put your solution under `bench-solutions/java/j34-vclock/src/main/java/`, using exactly the package and public signatures below. CI adds the hidden JUnit 5 tests, and the task passes only if every one of them passes.

```java
package bench.vclock;
public final class Replica {
    public Replica(String id);
    public String id();
    public Read get(String key);
    public void put(String key, String value, Context context);
    public void delete(String key, Context context);
    public void syncFrom(Replica other);

    public record Read(java.util.List<String> values, Context context) { }

    public static final class Context {
        public static final Context EMPTY;
    }
}
```

Several replicas, each in the same JVM, store string values per key and exchange state only through `syncFrom`. No replica ever sees another's state except by syncing.

## Vector clocks

- A vector clock maps replica ids to positive counters; a missing id counts as 0.
- `A <= B` when every counter in `A` is at most the matching counter in `B`.
- `A < B` when `A <= B` and `A != B`.
- `Context` is an immutable vector clock. `Context.EMPTY` is the empty clock. Two contexts are `equals` (with a matching `hashCode`) exactly when they hold the same clock. A context from one replica may be used with any other.

## Versions

- For each key, a replica holds a set of versions. Each version has a vector clock, plus either a value or a *tombstone* (a deletion).
- **`get(key)`:**
  - `values` is the values of the key's non-tombstone versions, sorted with `String.compareTo`, with duplicates kept.
  - `context` is the entry-wise maximum of all the key's version clocks, tombstones included.
  - An unknown key gives an empty list and a context equal to `Context.EMPTY`.
  - `values` is unmodifiable.
- **`put(key, value, context)` on replica `R`:**
  1. Make a new clock `C` equal to `context`.
  2. Set `C[R]` to 1 plus the largest of `context[R]` and `V[R]` over the key's current versions `V` on `R`.
  3. Remove every current version of the key whose clock is `<= C`.
  4. Add a version with clock `C` and the value.
- **`delete(key, context)`:** the same as `put`, but the new version is a tombstone.
- **`syncFrom(other)`:**
  - For every key `other` holds, this replica's versions become the union of both sets.
  - Versions with equal clocks are the same version and are kept once.
  - Then every version whose clock is `<` another version's clock in the union is dropped.
  - Only this replica changes. `syncFrom(this)` does nothing.

## Errors

A null or empty `id`, or a null `key`, `value`, `context` or `other`, throws `IllegalArgumentException`.
