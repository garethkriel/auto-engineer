# rotating-log

- **Description:** `RotatingLog` appends lines to a file and rotates by UTF-8 byte size into `file.1` (newest) … `file.N`; `maxBackups == 0` truncates; oversized lines are written alone, never split.
- **Language:** java
- **Tags:** java.nio.file, file rotation, @TempDir, parameterised tests, UTF-8
- **Source round:** practice/java/20261114T0956Z
- **Tests:** `mvn -q -B test` (18 tests)
