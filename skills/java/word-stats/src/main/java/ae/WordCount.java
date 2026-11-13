package ae;

import java.util.Objects;

/** A word and how many times it occurred (always at least 1). */
public record WordCount(String word, int count) {
    public WordCount {
        Objects.requireNonNull(word, "word");
        if (count < 1) {
            throw new IllegalArgumentException("count must be >= 1: " + count);
        }
    }
}
