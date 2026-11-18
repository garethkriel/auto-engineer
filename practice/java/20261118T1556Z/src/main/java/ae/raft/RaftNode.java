package ae.raft;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/**
 * A single-threaded, tick-driven Raft node (leader election and log replication, no snapshots
 * or membership changes). The caller drives it with {@link #tick()} and {@link #receive}.
 * Log indexes are 1-based; index 0 means "before the first entry".
 */
public final class RaftNode {

    /** Node role. */
    public enum Role { FOLLOWER, CANDIDATE, LEADER }

    private final int id;
    private final int size;
    private final Transport transport;
    private final Random random;
    private final int minTimeout;
    private final int maxTimeout;
    private final int heartbeat;

    // durable state: survives restart()
    private int currentTerm;
    private int votedFor = -1;
    private final List<LogEntry> log = new ArrayList<>();

    // volatile state
    private Role role = Role.FOLLOWER;
    private int commitIndex;
    private int electionElapsed;
    private int electionTimeout;
    private int heartbeatElapsed;
    private Set<Integer> votes = new HashSet<>();
    private int[] nextIndex;
    private int[] matchIndex;

    public RaftNode(int id, int clusterSize, Transport transport, Random random,
                    int minElectionTicks, int maxElectionTicks, int heartbeatTicks) {
        if (clusterSize < 1 || id < 0 || id >= clusterSize) {
            throw new IllegalArgumentException("bad id " + id + " for cluster of " + clusterSize);
        }
        if (minElectionTicks < 1 || maxElectionTicks < minElectionTicks) {
            throw new IllegalArgumentException("bad election timeout range");
        }
        if (heartbeatTicks < 1 || heartbeatTicks >= minElectionTicks) {
            throw new IllegalArgumentException("heartbeat must be in [1, minElectionTicks)");
        }
        this.id = id;
        this.size = clusterSize;
        this.transport = Objects.requireNonNull(transport, "transport");
        this.random = Objects.requireNonNull(random, "random");
        this.minTimeout = minElectionTicks;
        this.maxTimeout = maxElectionTicks;
        this.heartbeat = heartbeatTicks;
        resetElectionTimer();
    }

    // ---- accessors -------------------------------------------------------------------------

    public int id() { return id; }

    public int term() { return currentTerm; }

    public Role role() { return role; }

    /** Candidate voted for in the current term, or -1. */
    public int votedFor() { return votedFor; }

    public int commitIndex() { return commitIndex; }

    /** Immutable snapshot of the log. */
    public List<LogEntry> log() { return List.copyOf(log); }

    /** Commands up to {@link #commitIndex()}, without leader no-ops. */
    public List<String> committed() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < commitIndex; i++) {
            if (!log.get(i).isNoop()) {
                out.add(log.get(i).command());
            }
        }
        return out;
    }

    // ---- driving the node ------------------------------------------------------------------

    /** Advances logical time by one tick. */
    public void tick() {
        if (role == Role.LEADER) {
            heartbeatElapsed++;
            if (heartbeatElapsed >= heartbeat) {
                broadcastAppend();
            }
        } else {
            electionElapsed++;
            if (electionElapsed >= electionTimeout) {
                startElection();
            }
        }
    }

    /**
     * Appends a client command if this node is the leader.
     *
     * @return the new entry's index, or -1 when not the leader
     */
    public int propose(String command) {
        Objects.requireNonNull(command, "command");
        if (command.equals(LogEntry.NOOP)) {
            throw new IllegalArgumentException("empty command is reserved for the leader no-op");
        }
        if (role != Role.LEADER) {
            return -1;
        }
        log.add(new LogEntry(currentTerm, command));
        if (size == 1) {
            advanceCommit();
        }
        return log.size();
    }

    /** Simulates a crash followed by recovery: durable state is kept, volatile state is lost. */
    public void restart() {
        role = Role.FOLLOWER;
        commitIndex = 0;
        votes = new HashSet<>();
        nextIndex = null;
        matchIndex = null;
        resetElectionTimer();
    }

    /** Handles a message from node {@code from}. */
    public void receive(int from, Message message) {
        Objects.requireNonNull(message, "message");
        if (message.term() > currentTerm) {
            stepDown(message.term());
        }
        switch (message) {
            case Message.RequestVote m -> onRequestVote(from, m);
            case Message.VoteReply m -> onVoteReply(from, m);
            case Message.AppendEntries m -> onAppendEntries(from, m);
            case Message.AppendReply m -> onAppendReply(from, m);
        }
    }

    // ---- elections -------------------------------------------------------------------------

    private void startElection() {
        currentTerm++;
        role = Role.CANDIDATE;
        votedFor = id;
        votes = new HashSet<>(Set.of(id));
        resetElectionTimer();
        if (votes.size() >= majority()) {
            becomeLeader();
            return;
        }
        Message.RequestVote req = new Message.RequestVote(currentTerm, id, lastIndex(), termAt(lastIndex()));
        for (int peer = 0; peer < size; peer++) {
            if (peer != id) {
                transport.send(peer, req);
            }
        }
    }

    private void onRequestVote(int from, Message.RequestVote m) {
        boolean grant = false;
        if (m.term() == currentTerm && (votedFor == -1 || votedFor == m.candidateId())
                && candidateUpToDate(m.lastLogTerm(), m.lastLogIndex())) {
            grant = true;
            votedFor = m.candidateId();
            resetElectionTimer();
        }
        transport.send(from, new Message.VoteReply(currentTerm, grant));
    }

    private boolean candidateUpToDate(int candLastTerm, int candLastIndex) {
        int myTerm = termAt(lastIndex());
        return candLastTerm > myTerm || (candLastTerm == myTerm && candLastIndex >= lastIndex());
    }

    private void onVoteReply(int from, Message.VoteReply m) {
        if (role != Role.CANDIDATE || m.term() != currentTerm || !m.granted()) {
            return;
        }
        votes.add(from);
        if (votes.size() >= majority()) {
            becomeLeader();
        }
    }

    private void becomeLeader() {
        role = Role.LEADER;
        log.add(new LogEntry(currentTerm, LogEntry.NOOP));
        nextIndex = new int[size];
        matchIndex = new int[size];
        java.util.Arrays.fill(nextIndex, lastIndex() + 1);
        if (size == 1) {
            advanceCommit();
            return;
        }
        broadcastAppend();
    }

    private void stepDown(int newTerm) {
        currentTerm = newTerm;
        votedFor = -1;
        if (role != Role.FOLLOWER) {
            role = Role.FOLLOWER;
            nextIndex = null;
            matchIndex = null;
        }
        resetElectionTimer();
    }

    // ---- replication -----------------------------------------------------------------------

    private void broadcastAppend() {
        heartbeatElapsed = 0;
        for (int peer = 0; peer < size; peer++) {
            if (peer != id) {
                sendAppend(peer);
            }
        }
    }

    private void sendAppend(int peer) {
        int prev = nextIndex[peer] - 1;
        List<LogEntry> entries = new ArrayList<>(log.subList(prev, log.size()));
        transport.send(peer, new Message.AppendEntries(currentTerm, id, prev, termAt(prev), entries, commitIndex));
    }

    private void onAppendEntries(int from, Message.AppendEntries m) {
        if (m.term() < currentTerm) {
            transport.send(from, new Message.AppendReply(currentTerm, false, 0));
            return;
        }
        // a valid leader for this term exists
        role = Role.FOLLOWER;
        nextIndex = null;
        matchIndex = null;
        resetElectionTimer();
        int prev = m.prevLogIndex();
        if (prev > lastIndex() || termAt(prev) != m.prevLogTerm()) {
            transport.send(from, new Message.AppendReply(currentTerm, false, 0));
            return;
        }
        int idx = prev;
        for (LogEntry entry : m.entries()) {
            idx++;
            if (idx <= lastIndex()) {
                if (termAt(idx) == entry.term()) {
                    continue; // already have it
                }
                truncateFrom(idx);
            }
            log.add(entry);
        }
        int lastNew = prev + m.entries().size();
        if (m.leaderCommit() > commitIndex) {
            commitIndex = Math.min(m.leaderCommit(), lastNew);
        }
        transport.send(from, new Message.AppendReply(currentTerm, true, lastNew));
    }

    private void truncateFrom(int index) {
        while (log.size() >= index) {
            log.remove(log.size() - 1);
        }
    }

    private void onAppendReply(int from, Message.AppendReply m) {
        if (role != Role.LEADER || m.term() != currentTerm) {
            return;
        }
        if (m.success()) {
            matchIndex[from] = Math.max(matchIndex[from], m.matchIndex());
            nextIndex[from] = Math.max(nextIndex[from], matchIndex[from] + 1);
            advanceCommit();
        } else {
            nextIndex[from] = Math.max(matchIndex[from] + 1, nextIndex[from] - 1);
            sendAppend(from);
        }
    }

    /** Commit the highest index replicated on a majority whose entry is from the current term. */
    private void advanceCommit() {
        for (int n = lastIndex(); n > commitIndex; n--) {
            if (termAt(n) != currentTerm) {
                break; // older terms cannot be committed by counting replicas
            }
            int count = 1;
            for (int peer = 0; peer < size; peer++) {
                if (peer != id && matchIndex != null && matchIndex[peer] >= n) {
                    count++;
                }
            }
            if (count >= majority()) {
                commitIndex = n;
                return;
            }
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private int majority() { return size / 2 + 1; }

    private int lastIndex() { return log.size(); }

    private int termAt(int index) { return index == 0 ? 0 : log.get(index - 1).term(); }

    private void resetElectionTimer() {
        electionElapsed = 0;
        electionTimeout = minTimeout + random.nextInt(maxTimeout - minTimeout + 1);
    }
}
