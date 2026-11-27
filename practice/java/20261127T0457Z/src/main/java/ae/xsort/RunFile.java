package ae.xsort;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.StreamCorruptedException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Temporary run file format: {@code [1][record]} for every record, then a single {@code [0]} end marker.
 * A file without the end marker (truncated) is reported as {@link CorruptRunException}.
 */
final class RunFile {
    private static final int MORE = 1;
    private static final int END = 0;

    private RunFile() {}

    static <T> Writer<T> create(Path file, Codec<T> codec) {
        try {
            return new Writer<>(new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file))), codec);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static <T> Reader<T> open(Path file, Codec<T> codec) {
        try {
            return new Reader<>(new DataInputStream(new BufferedInputStream(Files.newInputStream(file))), codec);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static final class Writer<T> implements AutoCloseable {
        private final DataOutputStream out;
        private final Codec<T> codec;

        private Writer(DataOutputStream out, Codec<T> codec) {
            this.out = out;
            this.codec = codec;
        }

        void write(T value) {
            try {
                out.writeByte(MORE);
                codec.write(out, value);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /** Writes the end marker and closes the file. */
        @Override
        public void close() {
            try {
                out.writeByte(END);
                out.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    static final class Reader<T> implements Iterator<T>, AutoCloseable {
        private final DataInputStream in;
        private final Codec<T> codec;
        private boolean peeked;
        private boolean more;

        private Reader(DataInputStream in, Codec<T> codec) {
            this.in = in;
            this.codec = codec;
        }

        @Override
        public boolean hasNext() {
            if (!peeked) {
                try {
                    int marker = in.readUnsignedByte();
                    if (marker == MORE) more = true;
                    else if (marker == END) more = false;
                    else throw new CorruptRunException("bad record marker " + marker, null);
                } catch (EOFException e) {
                    throw new CorruptRunException("run file ends without end marker", e);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                peeked = true;
            }
            return more;
        }

        @Override
        public T next() {
            if (!hasNext()) throw new NoSuchElementException();
            peeked = false;
            try {
                return codec.read(in);
            } catch (EOFException | StreamCorruptedException e) {
                throw new CorruptRunException("corrupt record: " + e.getMessage(), e);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void close() {
            try {
                in.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
