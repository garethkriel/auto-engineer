package bench.topo;

import java.util.List;

public final class CycleException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient List<String> cycle;

    public CycleException(List<String> cycle) {
        super("cycle: " + cycle);
        this.cycle = List.copyOf(cycle);
    }

    public List<String> cycle() { return cycle; }
}
