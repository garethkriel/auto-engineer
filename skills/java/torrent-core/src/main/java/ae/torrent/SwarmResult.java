package ae.torrent;

import java.util.Map;
import java.util.Set;

/**
 * Outcome of {@link Swarm#run}.
 *
 * @param ticks            ticks executed (a final stalled tick counts)
 * @param complete         every leecher holds the whole file
 * @param stalled          a tick found nothing that could be requested
 * @param hashFailures     pieces rejected by hash verification
 * @param bans             {@code "leecher>uploader"} pairs
 * @param completionTick   tick at which each leecher that finished during the run completed
 * @param maxServedPerTick largest number of pieces any single peer uploaded in one tick
 */
public record SwarmResult(int ticks, boolean complete, boolean stalled, int hashFailures, Set<String> bans,
        Map<String, Integer> completionTick, int maxServedPerTick) {
    public SwarmResult {
        bans = Set.copyOf(bans);
        completionTick = Map.copyOf(completionTick);
    }
}
