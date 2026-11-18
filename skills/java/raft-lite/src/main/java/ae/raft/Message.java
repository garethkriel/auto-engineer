package ae.raft;

import java.util.List;

/** Messages exchanged between Raft nodes. Every message carries the sender's current term. */
public sealed interface Message {

    /** The sender's term. */
    int term();

    /** Candidate asks for a vote. {@code lastLogIndex}/{@code lastLogTerm} describe the candidate's log. */
    record RequestVote(int term, int candidateId, int lastLogIndex, int lastLogTerm) implements Message {}

    /** Reply to {@link RequestVote}. */
    record VoteReply(int term, boolean granted) implements Message {}

    /** Leader replicates entries (or heartbeats with an empty list). */
    record AppendEntries(int term, int leaderId, int prevLogIndex, int prevLogTerm,
                         List<LogEntry> entries, int leaderCommit) implements Message {
        public AppendEntries {
            entries = List.copyOf(entries);
        }
    }

    /** Reply to {@link AppendEntries}; {@code matchIndex} is meaningful only when {@code success}. */
    record AppendReply(int term, boolean success, int matchIndex) implements Message {}
}
