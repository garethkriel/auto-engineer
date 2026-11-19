package ae.stream;

/** How events are grouped into windows. */
public sealed interface WindowSpec {

    /** Fixed, non-overlapping windows aligned to multiples of {@code size}. */
    record Tumbling(long size) implements WindowSpec {
        public Tumbling {
            if (size <= 0) {
                throw new IllegalArgumentException("size must be > 0");
            }
        }
    }

    /** Overlapping windows of {@code size} starting at every multiple of {@code slide} ({@code slide <= size}). */
    record Sliding(long size, long slide) implements WindowSpec {
        public Sliding {
            if (size <= 0 || slide <= 0 || slide > size) {
                throw new IllegalArgumentException("need 0 < slide <= size");
            }
        }
    }

    /** Per-key sessions: events closer than {@code gap} (touching counts) share a window. */
    record Session(long gap) implements WindowSpec {
        public Session {
            if (gap <= 0) {
                throw new IllegalArgumentException("gap must be > 0");
            }
        }
    }
}
