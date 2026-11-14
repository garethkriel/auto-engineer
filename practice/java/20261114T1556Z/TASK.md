# Task (level 8, command pattern: undo/redo editor)
Package `cmd`. Overlap check: benchmark/java names (roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter, streams, fileindex) do not involve command/undo; no overlap.

- `Command`: `Command apply(StringBuilder doc)` applies itself and returns its inverse. Invalid positions throw `IllegalArgumentException` and leave the document unchanged.
- `Commands.insert(pos, text)`, `Commands.delete(pos, length)`, `Commands.replace(pos, length, text)`, `Commands.macro(List<Command>)` (atomic: if a step fails, earlier steps are rolled back and the exception rethrown).
- `Editor(int historyLimit)`: `execute(Command)`, `undo()`/`redo()` (return boolean), `canUndo/canRedo`, `text()`, `save()`, `isDirty()` (true when text revision differs from the saved one, even after undo back to the save point -> clean again). Executing clears redo. Oldest history is dropped beyond limit. limit < 1 -> IAE.
Assumptions: positions are char offsets; insert allows pos==length; empty insert/delete are legal no-ops that still record history.
