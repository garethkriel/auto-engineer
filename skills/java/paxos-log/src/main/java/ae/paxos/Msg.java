package ae.paxos;

import java.util.Map;

/** Protocol messages. Every message names its sender and receiver. */
public sealed interface Msg {
    int from();

    int to();

    /** Phase 1a. */
    record Prepare(int from, int to, Ballot ballot) implements Msg { }

    /** Phase 1b: everything the acceptor ever accepted (slot to ballot/value). */
    record Promise(int from, int to, Ballot ballot, Map<Integer, Node.Accepted> accepted) implements Msg {
        public Promise {
            accepted = Map.copyOf(accepted);
        }
    }

    /** Rejection: the acceptor has promised a higher ballot. */
    record Nack(int from, int to, Ballot promised) implements Msg { }

    /** Phase 2a. */
    record Accept(int from, int to, Ballot ballot, int slot, String value) implements Msg { }

    /** Phase 2b. */
    record Accepted(int from, int to, Ballot ballot, int slot) implements Msg { }

    /** The value of a slot is chosen. */
    record Decide(int from, int to, int slot, String value) implements Msg { }

    /** Leader liveness signal. */
    record Heartbeat(int from, int to, Ballot ballot) implements Msg { }

    /** Learner catch-up: the sender has decided every slot below {@code firstUndecided}. */
    record Sync(int from, int to, int firstUndecided) implements Msg { }

    /** A follower hands a client command to the leader it knows. */
    record Forward(int from, int to, String command) implements Msg { }
}
