package ae.coord;

import java.util.Objects;

/** One step of an atomic {@link CoordService#multi}. */
public sealed interface Op {
    record Create(String path, String data, CreateMode mode) implements Op {
        public Create {
            Objects.requireNonNull(path);
            Objects.requireNonNull(data);
            Objects.requireNonNull(mode);
        }
    }

    record Delete(String path, int expectedVersion) implements Op {
        public Delete {
            Objects.requireNonNull(path);
        }
    }

    record SetData(String path, String data, int expectedVersion) implements Op {
        public SetData {
            Objects.requireNonNull(path);
            Objects.requireNonNull(data);
        }
    }

    /** Fails the transaction unless the node exists (and has {@code expectedVersion}, unless -1). */
    record Check(String path, int expectedVersion) implements Op {
        public Check {
            Objects.requireNonNull(path);
        }
    }
}
