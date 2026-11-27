package ae.xsort;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/** Serialises records to and from run files. Implementations must be stateless between records. */
public interface Codec<T> {
    void write(DataOutput out, T value) throws IOException;

    T read(DataInput in) throws IOException;
}
