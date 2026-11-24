package ae.logic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Static timing: longest path of gate delays. Inputs arrive at 0; DFF outputs and constants at their own delay. */
public final class Timing {
    /** The critical path: total delay and the signals from source to sink. */
    public record Path(int delay, List<String> signals) {}

    private Timing() {}

    public static Path criticalPath(Netlist n) {
        int total = n.signalCount();
        if (total == 0) return new Path(0, List.of());
        int nIn = n.inputCount();
        int[] arrival = new int[total];
        int[] pred = new int[total];
        java.util.Arrays.fill(pred, -1);
        for (int j = 0; j < n.gates().size(); j++)
            if (n.gates().get(j).op().isSource()) arrival[nIn + j] = n.gates().get(j).delay();
        for (int j : n.order()) {
            Netlist.Gate g = n.gates().get(j);
            int best = 0, from = -1;
            for (int in : n.fanin()[j]) {
                if (from == -1 || arrival[in] > best) { best = arrival[in]; from = in; }
            }
            arrival[nIn + j] = best + g.delay();
            pred[nIn + j] = from;
        }
        int end = 0;
        for (int i = 1; i < total; i++) if (arrival[i] > arrival[end]) end = i;
        List<String> path = new ArrayList<>();
        for (int s = end; s != -1; s = pred[s]) path.add(n.signalName(s));
        Collections.reverse(path);
        return new Path(arrival[end], List.copyOf(path));
    }
}
