package ae.swim;

import java.util.HashMap;
import java.util.Map;

/** Safety checks run after every tick; throws {@link IllegalStateException} on the first violation. */
final class Invariants {
    private final Map<String, Status> lastStatus = new HashMap<>();
    private final Map<String, Integer> lastIncarnation = new HashMap<>();

    void check(Simulation sim) {
        for (String observer : sim.ids()) {
            if (sim.isCrashed(observer)) {
                continue;
            }
            SwimNode obs = sim.rawNode(observer);
            for (var entry : obs.view().entrySet()) {
                String subject = entry.getKey();
                Status status = entry.getValue();
                int inc = obs.incarnation(subject);
                String key = observer + ">" + subject;
                String where = " (" + observer + " about " + subject + " at t=" + sim.now() + ")";
                Status before = lastStatus.put(key, status);
                if (before == Status.DEAD && status != Status.DEAD) {
                    throw new IllegalStateException("DEAD is absorbing but was resurrected" + where);
                }
                Integer incBefore = lastIncarnation.put(key, inc);
                if (incBefore != null && inc < incBefore && status != Status.DEAD) {
                    throw new IllegalStateException("incarnation decreased " + incBefore + " -> " + inc + where);
                }
                int actual = sim.rawNode(subject).selfIncarnation();
                if (inc > actual) {
                    throw new IllegalStateException(
                        "observer holds incarnation " + inc + " beyond the subject's own " + actual + where);
                }
            }
        }
    }
}
