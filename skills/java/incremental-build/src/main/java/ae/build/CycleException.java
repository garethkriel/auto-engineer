package ae.build;

import java.util.List;

/** Dependencies form a cycle. {@link #path()} is e.g. {@code [a, b, c, a]}. */
public class CycleException extends BuildException {
    private static final long serialVersionUID = 1L;

    private final transient List<String> path;

    public CycleException(List<String> path) {
        super(path.get(0), "dependency cycle: " + String.join(" -> ", path), null);
        this.path = List.copyOf(path);
    }

    public List<String> path() {
        return path;
    }
}
