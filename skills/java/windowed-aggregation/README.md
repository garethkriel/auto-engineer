# windowed-aggregation

- **Description:** Event-time stream aggregation in `ae.stream`: `WindowEngine` with a bounded-out-of-orderness watermark, allowed lateness (`LATE_UPDATE` results, `Dropped` side output), tumbling / sliding / session windows (touching sessions merge), and canonical CRC32-checked `snapshot()`/`restore()`.
- **Language:** java
- **Tags:** streaming, watermark, windows, sessions, late-data, checkpoint, differential-testing
- **Source round:** practice/java/20261119T0156Z

## Use it for
Any exercise that groups out-of-order timestamped records: reuse the watermark/lateness rules, or the snapshot pattern (canonical bytes, CRC footer, structural validation on restore).

## Key rules baked in
- Watermark = max time - maxOutOfOrder, monotonic, saturating. Window `[s,e)` fires when `wm >= e`; purged when `e + lateness <= wm` (equal means dead).
- Lateness is checked against the watermark before the event itself; late-but-live events emit `LATE_UPDATE` immediately.
- Compute all throwing values (window bounds, `Math.addExact` merges) before mutating; assert snapshot bytes unchanged after a failure.
- Differential oracle recomputes the prefix watermark naively; sessions use an unbounded-lateness oracle (sorted merge), since purged windows are not merged into.
- Checkpoint/restore at random points must reproduce the uninterrupted output and final snapshot bytes.

Run: `mvn -q -B test` (31 tests).
