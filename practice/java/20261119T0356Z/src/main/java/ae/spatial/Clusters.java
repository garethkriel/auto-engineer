package ae.spatial;

import java.util.List;

/** Result of {@link SpatialIndex#cluster}: clusters in seed order and the noise ids. */
public record Clusters(List<List<String>> clusters, List<String> noise) {
}
