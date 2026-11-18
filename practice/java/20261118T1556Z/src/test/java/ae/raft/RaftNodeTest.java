package ae.raft;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RaftNodeTest {
    record Sent(int to, Message msg) {}

    final List<Sent> sent = new ArrayList<>();

    /** Node 0 of a 3-node cluster; election timeout fixed at 10 ticks, heartbeat 3. */
    RaftNode node() {
        return new RaftNode(0, 3, (to, m) -> sent.add(new Sent(to, m)), new Random(1), 10, 10, 3);
    }

    static LogEntry e(int term, String cmd) {
        return new LogEntry(term, cmd);
    }

    @Test
    void followerBecomesCandidateAfterTimeoutAndRequestsVotes() {
        RaftNode n = node();
        for (int i = 0; i < 9; i++) n.tick();
        assertEquals(RaftNode.Role.FOLLOWER, n.role());
        n.tick();
        assertEquals(RaftNode.Role.CANDIDATE, n.role());
        assertEquals(1, n.term());
        assertEquals(0, n.votedFor());
        assertEquals(2, sent.size());
        assertEquals(new Message.RequestVote(1, 0, 0, 0), sent.get(0).msg());
        assertEquals(List.of(1, 2), sent.stream().map(Sent::to).toList());
    }

    @Test
    void candidateWinsWithOneExtraVoteAndAppendsNoop() {
        RaftNode n = node();
        for (int i = 0; i < 10; i++) n.tick();
        sent.clear();
        n.receive(1, new Message.VoteReply(1, true));
        assertEquals(RaftNode.Role.LEADER, n.role());
        assertEquals(List.of(e(1, "")), n.log());
        assertEquals(2, sent.size());
        assertInstanceOf(Message.AppendEntries.class, sent.get(0).msg());
    }

    @Test
    void duplicateVoteFromSamePeerDoesNotMakeMajorityInFiveNodeCluster() {
        RaftNode n = new RaftNode(0, 5, (to, m) -> {}, new Random(1), 10, 10, 3);
        for (int i = 0; i < 10; i++) n.tick();
        n.receive(1, new Message.VoteReply(1, true));
        n.receive(1, new Message.VoteReply(1, true));
        assertEquals(RaftNode.Role.CANDIDATE, n.role());
        n.receive(2, new Message.VoteReply(1, true));
        assertEquals(RaftNode.Role.LEADER, n.role());
    }

    @Test
    void staleVoteReplyFromOldTermIgnored() {
        RaftNode n = node();
        for (int i = 0; i < 10; i++) n.tick(); // term 1
        for (int i = 0; i < 10; i++) n.tick(); // term 2
        n.receive(1, new Message.VoteReply(1, true));
        assertEquals(RaftNode.Role.CANDIDATE, n.role());
        assertEquals(2, n.term());
    }

    @Test
    void singleNodeClusterElectsItself() {
        RaftNode n = new RaftNode(0, 1, (to, m) -> fail("no peers"), new Random(1), 5, 5, 2);
        for (int i = 0; i < 5; i++) n.tick();
        assertEquals(RaftNode.Role.LEADER, n.role());
        assertEquals(2, n.propose("x"), "index 1 holds the leader no-op");
        assertEquals(List.of("x"), n.committed());
    }

    @Test
    void grantsOneVotePerTermAndRepeatsToSameCandidate() {
        RaftNode n = node();
        n.receive(1, new Message.RequestVote(1, 1, 0, 0));
        n.receive(2, new Message.RequestVote(1, 2, 0, 0));
        n.receive(1, new Message.RequestVote(1, 1, 0, 0));
        assertEquals(new Message.VoteReply(1, true), sent.get(0).msg());
        assertEquals(new Message.VoteReply(1, false), sent.get(1).msg());
        assertEquals(new Message.VoteReply(1, true), sent.get(2).msg());
        assertEquals(1, n.votedFor());
    }

    @Test
    void deniesVoteToStaleTermAndReportsOwnTerm() {
        RaftNode n = node();
        n.receive(1, new Message.RequestVote(5, 1, 0, 0));
        sent.clear();
        n.receive(2, new Message.RequestVote(3, 2, 0, 0));
        assertEquals(new Message.VoteReply(5, false), sent.get(0).msg());
    }

    @Test
    void deniesVoteToCandidateWithOlderLog() {
        RaftNode n = node();
        n.receive(1, new Message.AppendEntries(2, 1, 0, 0, List.of(e(2, "a"), e(2, "b")), 0));
        sent.clear();
        // same last term, shorter log
        n.receive(2, new Message.RequestVote(3, 2, 1, 2));
        // lower last term, longer log
        n.receive(2, new Message.RequestVote(4, 2, 9, 1));
        // same last term, same length: granted
        n.receive(2, new Message.RequestVote(5, 2, 2, 2));
        assertEquals(new Message.VoteReply(3, false), sent.get(0).msg());
        assertEquals(new Message.VoteReply(4, false), sent.get(1).msg());
        assertEquals(new Message.VoteReply(5, true), sent.get(2).msg());
    }

    @Test
    void higherTermMessageClearsVoteAndStepsDownLeader() {
        RaftNode n = node();
        for (int i = 0; i < 10; i++) n.tick();
        n.receive(1, new Message.VoteReply(1, true));
        assertEquals(RaftNode.Role.LEADER, n.role());
        n.receive(2, new Message.RequestVote(7, 2, 5, 5));
        assertEquals(RaftNode.Role.FOLLOWER, n.role());
        assertEquals(7, n.term());
        assertEquals(2, n.votedFor(), "candidate log (5,5) beats ours (1,1), so the vote is granted");
    }

    @Test
    void appendRejectsStaleTermAndMissingPrevEntry() {
        RaftNode n = node();
        n.receive(1, new Message.AppendEntries(3, 1, 0, 0, List.of(e(3, "a")), 0));
        sent.clear();
        n.receive(2, new Message.AppendEntries(2, 2, 0, 0, List.of(e(2, "z")), 0));
        n.receive(1, new Message.AppendEntries(3, 1, 4, 3, List.of(e(3, "q")), 0));
        n.receive(1, new Message.AppendEntries(3, 1, 1, 2, List.of(e(3, "q")), 0)); // wrong prev term
        assertEquals(new Message.AppendReply(3, false, 0), sent.get(0).msg());
        assertEquals(new Message.AppendReply(3, false, 0), sent.get(1).msg());
        assertEquals(new Message.AppendReply(3, false, 0), sent.get(2).msg());
        assertEquals(List.of(e(3, "a")), n.log());
    }

    @Test
    void appendTruncatesOnlyOnConflictNotOnDuplicateOrShorterPrefix() {
        RaftNode n = node();
        n.receive(1, new Message.AppendEntries(1, 1, 0, 0, List.of(e(1, "a"), e(1, "b"), e(1, "c")), 0));
        // delayed duplicate carrying only a prefix must not delete "c"
        n.receive(1, new Message.AppendEntries(1, 1, 0, 0, List.of(e(1, "a")), 0));
        assertEquals(List.of(e(1, "a"), e(1, "b"), e(1, "c")), n.log());
        // new leader overwrites index 2 and 3
        n.receive(2, new Message.AppendEntries(2, 2, 1, 1, List.of(e(2, "x")), 0));
        assertEquals(List.of(e(1, "a"), e(2, "x")), n.log());
    }

    @Test
    void followerCommitIsMinOfLeaderCommitAndLastNewEntry() {
        RaftNode n = node();
        n.receive(1, new Message.AppendEntries(1, 1, 0, 0, List.of(e(1, "a"), e(1, "b")), 10));
        assertEquals(2, n.commitIndex());
        assertEquals(List.of("a", "b"), n.committed());
        // heartbeat with a lower leaderCommit never lowers commitIndex
        n.receive(1, new Message.AppendEntries(1, 1, 2, 1, List.of(), 1));
        assertEquals(2, n.commitIndex());
    }

    @Test
    void leaderBacksUpNextIndexOnRejectAndCommitsOnMajority() {
        RaftNode n = node();
        for (int i = 0; i < 10; i++) n.tick();
        n.receive(1, new Message.VoteReply(1, true)); // leader, log [noop@1]
        assertEquals(2, n.propose("a"));
        assertEquals(3, n.propose("b"));
        sent.clear();
        n.receive(1, new Message.AppendReply(1, false, 0)); // follower 1 has nothing
        AppendEntriesView v = lastAppendTo(1);
        assertEquals(0, v.prevIndex);
        assertEquals(3, v.entries);
        assertEquals(0, n.commitIndex());
        n.receive(1, new Message.AppendReply(1, true, 3));
        assertEquals(3, n.commitIndex());
        assertEquals(List.of("a", "b"), n.committed());
    }

    record AppendEntriesView(int prevIndex, int entries) {}

    AppendEntriesView lastAppendTo(int to) {
        for (int i = sent.size() - 1; i >= 0; i--) {
            if (sent.get(i).to() == to && sent.get(i).msg() instanceof Message.AppendEntries a) {
                return new AppendEntriesView(a.prevLogIndex(), a.entries().size());
            }
        }
        return fail("no append sent to " + to);
    }

    @Test
    void leaderNeverCommitsOldTermEntryByCounting() {
        RaftNode n = node();
        // become a follower holding an old-term entry at index 1
        n.receive(1, new Message.AppendEntries(1, 1, 0, 0, List.of(e(1, "old")), 0));
        for (int i = 0; i < 10; i++) n.tick(); // term 2 candidate
        assertEquals(2, n.term());
        n.receive(1, new Message.VoteReply(2, true)); // leader, log [old@1, noop@2]
        // peer 1 acknowledges only index 1 (the old-term entry): must not commit
        n.receive(1, new Message.AppendReply(2, true, 1));
        assertEquals(0, n.commitIndex());
        // acknowledging the current-term no-op commits everything before it too
        n.receive(1, new Message.AppendReply(2, true, 2));
        assertEquals(2, n.commitIndex());
        assertEquals(List.of("old"), n.committed());
    }

    @Test
    void staleAppendReplyDoesNotRegressMatchIndex() {
        RaftNode n = node();
        for (int i = 0; i < 10; i++) n.tick();
        n.receive(1, new Message.VoteReply(1, true));
        n.propose("a");
        n.receive(1, new Message.AppendReply(1, true, 2));
        assertEquals(2, n.commitIndex());
        n.receive(1, new Message.AppendReply(1, true, 1)); // delayed older ack
        n.receive(1, new Message.AppendReply(1, false, 0)); // delayed older reject
        sent.clear();
        for (int i = 0; i < 3; i++) n.tick();
        AppendEntriesView v = lastAppendTo(1);
        assertTrue(v.prevIndex >= 2, "nextIndex must stay above matchIndex, was prev=" + v.prevIndex);
    }

    @Test
    void proposeOnNonLeaderReturnsMinusOneAndRejectsNoopCommand() {
        RaftNode n = node();
        assertEquals(-1, n.propose("a"));
        for (int i = 0; i < 10; i++) n.tick();
        n.receive(1, new Message.VoteReply(1, true));
        assertThrows(IllegalArgumentException.class, () -> n.propose(""));
        assertThrows(NullPointerException.class, () -> n.propose(null));
    }

    @Test
    void leaderSendsHeartbeatsEveryHeartbeatTicks() {
        RaftNode n = node();
        for (int i = 0; i < 10; i++) n.tick();
        n.receive(1, new Message.VoteReply(1, true));
        sent.clear();
        for (int i = 0; i < 3; i++) n.tick();
        assertEquals(2, sent.size());
        for (int i = 0; i < 2; i++) n.tick();
        assertEquals(2, sent.size());
        n.tick();
        assertEquals(4, sent.size());
    }

    @Test
    void restartKeepsDurableStateAndLosesVolatileState() {
        RaftNode n = node();
        n.receive(1, new Message.AppendEntries(4, 1, 0, 0, List.of(e(4, "a")), 1));
        n.receive(1, new Message.RequestVote(5, 2, 1, 4));
        assertEquals(1, n.commitIndex());
        n.restart();
        assertEquals(5, n.term());
        assertEquals(2, n.votedFor());
        assertEquals(List.of(e(4, "a")), n.log());
        assertEquals(RaftNode.Role.FOLLOWER, n.role());
        assertEquals(0, n.commitIndex());
        assertEquals(List.of(), n.committed());
    }

    @Test
    void invalidConstructionRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RaftNode(3, 3, (t, m) -> {}, new Random(), 5, 6, 1));
        assertThrows(IllegalArgumentException.class, () -> new RaftNode(0, 3, (t, m) -> {}, new Random(), 6, 5, 1));
        assertThrows(IllegalArgumentException.class, () -> new RaftNode(0, 3, (t, m) -> {}, new Random(), 5, 6, 5));
    }
}
