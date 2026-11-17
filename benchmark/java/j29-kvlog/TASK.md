# j29-kvlog: Crash-safe key-value log

Java 21, standard library only. Put your solution under `bench-solutions/java/j29-kvlog/src/main/java/`, using exactly the package and public signatures below. CI adds the hidden JUnit 5 tests, and the task passes only if every one of them passes.

```java
package bench.kvlog;
public final class LogStore implements AutoCloseable {
    public static LogStore open(java.nio.file.Path dir) throws java.io.IOException;
    public void put(String key, byte[] value) throws java.io.IOException;
    public byte[] get(String key);
    public void delete(String key) throws java.io.IOException;
    public int size();
    public java.util.SortedSet<String> keys();
    public void compact() throws java.io.IOException;
    public void close() throws java.io.IOException;
}
```

- **Files:** `open` creates `dir` (and missing parents) if needed. The store's data lives in one file, `dir/data.log`. Once `open`, `compact` or `close` has returned, `data.log` is the only file the store leaves in `dir`.
- **Log format:** your choice, with two rules.
  - The log is a sequence of records appended in the order the operations happen, with nothing written after the last record.
  - Each record carries a checksum over its whole content, so damage anywhere in a record is detected.
- **Writes:** `put` and `delete` have written their record to `data.log` before they return; there is no buffering in the JVM. You don't need `fsync`. Deleting a key that isn't there changes nothing.
- **Crashes:** a test may abandon a store without closing it and open a new store on the same directory. The new store must see every `put` and `delete` that returned. The abandoned store is never used again.
- **Recovery:** `open` reads the records from the start and stops at the first record that is incomplete or fails its checksum. It discards everything from that point by truncating `data.log` there, so later writes follow the last good record. A missing or empty `data.log` means an empty store.
- **Reads:** `get` returns a copy of the value, or `null` for a missing key. `put` stores a copy, so later changes to the caller's array have no effect. `size` is the number of keys. `keys` returns an unmodifiable sorted snapshot.
- **`compact()`:** rewrites `data.log` to hold only the live entries. The swap must be atomic: at every moment `data.log` is either the complete old file or the complete new one. Use a temporary file in `dir`, then an atomic move. Afterwards the file is no bigger than a fresh log holding the same entries would be.
- **Limits:** keys are non-empty strings of at most 1,000 characters, stored as UTF-8. Values are 0 to 1,048,576 bytes.
- **Errors:**
  - A null `dir`, a null, empty or too long key, or a null or too large value throws `IllegalArgumentException`.
  - After `close`, every method except `close` throws `IllegalStateException`. `close` is idempotent.
- **Performance:** 20,000 puts of 200-byte values, then reopening the store, must take a few seconds at most.
