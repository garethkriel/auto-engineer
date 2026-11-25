package ae.kad;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Synchronous in-process RPC fabric. A down, unknown or fault-dropped target is a failed call. */
public final class Network {
    /** Decides whether a single call is lost. */
    @FunctionalInterface
    public interface Fault {
        boolean drops(NodeId from, NodeId to);
    }

    /** Reply to findValue: a value if the node holds one, otherwise its closest contacts. */
    public record ValueReply(Optional<String> value, List<NodeId> nodes) {}

    private final Map<NodeId, KadNode> nodes = new HashMap<>();
    private final Set<NodeId> down = new HashSet<>();
    private Fault fault = (f, t) -> false;
    private int rpcCount;

    void register(KadNode node) {
        if (nodes.putIfAbsent(node.id(), node) != null) {
            throw new IllegalArgumentException("duplicate node id " + node.id());
        }
    }

    public void setUp(NodeId id, boolean up) {
        if (up) down.remove(id);
        else down.add(id);
    }

    public boolean isUp(NodeId id) {
        return nodes.containsKey(id) && !down.contains(id);
    }

    public void setFault(Fault fault) {
        this.fault = fault;
    }

    public int rpcCount() {
        return rpcCount;
    }

    public void resetRpcCount() {
        rpcCount = 0;
    }

    /** Counts the call and returns the target if it can be reached. */
    private Optional<KadNode> reach(NodeId from, NodeId to) {
        rpcCount++;
        if (!isUp(to) || fault.drops(from, to)) return Optional.empty();
        return Optional.of(nodes.get(to));
    }

    public boolean ping(NodeId from, NodeId to) {
        return reach(from, to).map(n -> n.onPing(from)).orElse(false);
    }

    public Optional<List<NodeId>> findNode(NodeId from, NodeId to, NodeId target) {
        return reach(from, to).map(n -> n.onFindNode(from, target));
    }

    public Optional<ValueReply> findValue(NodeId from, NodeId to, NodeId key) {
        return reach(from, to).map(n -> n.onFindValue(from, key));
    }

    public boolean store(NodeId from, NodeId to, NodeId key, String value) {
        return reach(from, to).map(n -> n.onStore(from, key, value)).orElse(false);
    }
}
