package ae.paxos;

/** Totally ordered proposal number: round first, proposer id breaks ties. */
public record Ballot(long round, int node) implements Comparable<Ballot> {
    /** Below every real ballot; the initial promise of a fresh acceptor. */
    public static final Ballot NONE = new Ballot(0, -1);

    @Override
    public int compareTo(Ballot o) {
        int c = Long.compare(round, o.round);
        return c != 0 ? c : Integer.compare(node, o.node);
    }
}
