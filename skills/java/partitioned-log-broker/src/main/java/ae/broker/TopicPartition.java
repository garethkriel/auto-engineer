package ae.broker;

import java.util.Comparator;
import java.util.Objects;

/** A topic name plus partition number; ordered by topic, then partition. */
public record TopicPartition(String topic, int partition) implements Comparable<TopicPartition> {
    public TopicPartition {
        Objects.requireNonNull(topic, "topic");
        if (partition < 0) throw new IllegalArgumentException("partition < 0");
    }

    @Override
    public int compareTo(TopicPartition o) {
        return Comparator.comparing(TopicPartition::topic).thenComparingInt(TopicPartition::partition).compare(this, o);
    }
}
