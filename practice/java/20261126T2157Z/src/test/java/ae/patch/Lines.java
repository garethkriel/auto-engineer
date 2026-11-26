package ae.patch;

import java.util.Arrays;
import java.util.List;

/** Test helpers. */
final class Lines {
    private Lines() { }

    static List<String> of(String spaceSeparated) {
        return spaceSeparated.isEmpty() ? List.of() : Arrays.asList(spaceSeparated.split(" "));
    }

    static String text(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l).append('\n');
        }
        return sb.toString();
    }
}
