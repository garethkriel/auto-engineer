package bench.topo;

import java.util.List;

public final class CycleException extends RuntimeException {
    private final List<String> cycle;

    public CycleException(List<String> cycle) {
        super("cycle: " + cycle);
        this.cycle = List.copyOf(cycle);
    }

    public List<String> cycle() {
        return cycle;
    }
}
