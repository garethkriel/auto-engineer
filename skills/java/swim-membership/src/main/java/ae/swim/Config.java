package ae.swim;

/**
 * Protocol timing, all in simulation ticks.
 *
 * @param periodTicks       length of one probe period
 * @param pingTimeoutTicks  wait for a direct ack before asking helpers (must be shorter than the period)
 * @param indirectProbes    number of helpers asked via ping-req
 * @param suspicionTicks    time a SUSPECT member has to refute before it is declared DEAD
 * @param maxPiggyback      gossip updates attached to each message
 * @param retransmitMult    each update is sent at most retransmitMult * ceil(log2(n+1)) times
 */
public record Config(int periodTicks, int pingTimeoutTicks, int indirectProbes, int suspicionTicks,
                     int maxPiggyback, int retransmitMult) {
    public Config {
        if (pingTimeoutTicks < 1 || pingTimeoutTicks >= periodTicks) {
            throw new IllegalArgumentException("need 1 <= pingTimeoutTicks < periodTicks");
        }
        if (indirectProbes < 0) {
            throw new IllegalArgumentException("indirectProbes < 0");
        }
        if (suspicionTicks < 1 || maxPiggyback < 1 || retransmitMult < 1) {
            throw new IllegalArgumentException("suspicionTicks, maxPiggyback, retransmitMult must be >= 1");
        }
    }
}
