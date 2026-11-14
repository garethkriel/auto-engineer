# undo-redo-editor

- **Description:** Command pattern where `apply` returns its own inverse: insert/delete/replace/atomic macro commands, an `Editor` with bounded undo/redo, redo cleared only on successful execute, and revision-based dirty tracking (undo back to the save point is clean; diverging history never looks saved).
- **Language:** java
- **Tags:** command-pattern, undo-redo, design-patterns, interface-driven, immutability
- **Source round:** practice 20261114T1556Z (level 8)
- **Test:** `mvn -q -B test` (18 tests)
