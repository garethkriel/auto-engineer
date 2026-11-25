package recon;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministic in-process reconcile loop: kubelet, deployment controllers, scheduler (in that order per tick).
 */
public final class Cluster {

    private static final class Node {
        final int cpu;
        boolean up = true;

        Node(int cpu) {
            this.cpu = cpu;
        }
    }

    private final Map<String, Node> nodes = new TreeMap<>();
    private final Map<String, DeploymentSpec> specs = new TreeMap<>();
    private final Map<Integer, Pod> pods = new TreeMap<>();
    private int nextId = 1;

    public void addNode(String name, int cpu) {
        if (name == null || name.isBlank() || cpu <= 0 || nodes.containsKey(name)) {
            throw new IllegalArgumentException("bad or duplicate node: " + name);
        }
        nodes.put(name, new Node(cpu));
    }

    private Node node(String name) {
        Node n = nodes.get(name);
        if (n == null) {
            throw new IllegalArgumentException("unknown node: " + name);
        }
        return n;
    }

    public void failNode(String name) {
        node(name).up = false;
        pods.values().removeIf(p -> name.equals(p.node()));
    }

    public void recoverNode(String name) {
        node(name).up = true;
    }

    public void apply(DeploymentSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("spec is null");
        }
        specs.put(spec.name(), spec);
    }

    public void delete(String name) {
        specs.remove(name);
        pods.values().removeIf(p -> p.deployment().equals(name));
    }

    /** One reconcile round. */
    public void tick() {
        kubelet();
        for (DeploymentSpec spec : new ArrayList<>(specs.values())) {
            reconcile(spec);
        }
        schedule();
    }

    private void kubelet() {
        for (Map.Entry<Integer, Pod> e : pods.entrySet()) {
            Pod p = e.getValue();
            if (p.phase() == Phase.PENDING && p.node() != null) {
                e.setValue(new Pod(p.id(), p.deployment(), p.version(), p.cpu(), Phase.RUNNING, p.node()));
            }
        }
    }

    private void reconcile(DeploymentSpec s) {
        List<Pod> mine = pods(s.name());
        List<Pod> fresh = new ArrayList<>();
        List<Pod> old = new ArrayList<>();
        int running = 0;
        for (Pod p : mine) {
            (p.version().equals(s.version()) ? fresh : old).add(p);
            if (p.phase() == Phase.RUNNING) {
                running++;
            }
        }
        int total = mine.size();

        // 1. scale-in of new: not running first, then highest id first
        if (fresh.size() > s.replicas()) {
            List<Pod> order = new ArrayList<>(fresh);
            order.sort(Comparator.comparing((Pod p) -> p.phase() == Phase.RUNNING).thenComparing(Pod::id, Comparator.reverseOrder()));
            int surplus = fresh.size() - s.replicas();
            for (int i = 0; i < surplus; i++) {
                pods.remove(order.get(i).id());
            }
            total -= surplus;
        }
        int freshCount = Math.min(fresh.size(), s.replicas());

        // 2. scale-up
        int create = Math.min(s.replicas() - freshCount, s.replicas() + s.maxSurge() - total);
        for (int i = 0; i < create; i++) {
            int id = nextId++;
            pods.put(id, new Pod(id, s.name(), s.version(), s.cpu(), Phase.PENDING, null));
        }

        // 3. scale-down of old
        int minAvail = Math.max(0, s.replicas() - s.maxUnavailable());
        int budget = Math.max(0, running - minAvail);
        for (Pod p : old) { // already in id order
            if (p.phase() != Phase.RUNNING) {
                pods.remove(p.id());
            } else if (budget > 0) {
                pods.remove(p.id());
                budget--;
            }
        }
    }

    private void schedule() {
        for (Pod p : new ArrayList<>(pods.values())) {
            if (p.node() != null) {
                continue;
            }
            String best = null;
            int bestFree = -1;
            for (Map.Entry<String, Node> e : nodes.entrySet()) { // name order: strict > keeps smaller name on ties
                if (!e.getValue().up) {
                    continue;
                }
                int free = freeCpu(e.getKey());
                if (free >= p.cpu() && free > bestFree) {
                    best = e.getKey();
                    bestFree = free;
                }
            }
            if (best != null) {
                pods.put(p.id(), new Pod(p.id(), p.deployment(), p.version(), p.cpu(), p.phase(), best));
            }
        }
    }

    public List<Pod> pods() {
        return List.copyOf(pods.values());
    }

    public List<Pod> pods(String deployment) {
        return pods.values().stream().filter(p -> p.deployment().equals(deployment)).toList();
    }

    public int running(String deployment) {
        return (int) pods.values().stream()
                .filter(p -> p.deployment().equals(deployment) && p.phase() == Phase.RUNNING).count();
    }

    public int freeCpu(String node) {
        Node n = node(node);
        int used = 0;
        for (Pod p : pods.values()) {
            if (node.equals(p.node())) {
                used += p.cpu();
            }
        }
        return n.cpu - used;
    }

    public boolean settled() {
        for (Pod p : pods.values()) {
            if (!specs.containsKey(p.deployment())) {
                return false;
            }
        }
        for (DeploymentSpec s : specs.values()) {
            List<Pod> mine = pods(s.name());
            if (mine.size() != s.replicas()) {
                return false;
            }
            for (Pod p : mine) {
                if (!p.version().equals(s.version()) || p.phase() != Phase.RUNNING) {
                    return false;
                }
            }
        }
        return true;
    }
}
