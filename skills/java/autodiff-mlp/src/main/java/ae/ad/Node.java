package ae.ad;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.Consumer;

/**
 * A value in a computation graph. Build graphs with the static ops, call {@link #backward()} on a 1x1 result,
 * then read {@link #grad()} of the parameters. Leaves ({@link #param}) accumulate gradient across backward calls
 * until {@link #zeroGrad(Collection)}; intermediate nodes are reset on every backward pass.
 */
public final class Node {
    private final Mat value;
    private Mat grad;
    private final List<Node> parents;
    /** Propagates this node's grad into its parents; null for leaves. */
    private final Consumer<Mat> backwardFn;
    private final boolean requiresGrad;

    private Node(Mat value, List<Node> parents, Consumer<Mat> backwardFn, boolean requiresGrad) {
        this.value = value;
        this.grad = Mat.zeros(value.rows(), value.cols());
        this.parents = parents;
        this.backwardFn = backwardFn;
        this.requiresGrad = requiresGrad;
    }

    public static Node param(Mat value) {
        return new Node(value, List.of(), null, true);
    }

    public static Node constant(Mat value) {
        return new Node(value, List.of(), null, false);
    }

    public Mat value() {
        return value;
    }

    public Mat grad() {
        return grad;
    }

    private static Node derived(Mat value, List<Node> parents, Consumer<Mat> backwardFn) {
        boolean req = parents.stream().anyMatch(p -> p.requiresGrad);
        return new Node(value, parents, req ? backwardFn : null, req);
    }

    private void accumulate(Mat g) {
        if (requiresGrad) {
            grad = grad.plus(g);
        }
    }

    /** Zeroes the grads of the given nodes. */
    public static void zeroGrad(Collection<Node> nodes) {
        for (Node n : nodes) {
            n.grad = Mat.zeros(n.value.rows(), n.value.cols());
        }
    }

    // ---- ops ----

    /** Same-shape sum, or {@code b} a 1 x cols row broadcast over the rows of {@code a}. */
    public static Node add(Node a, Node b) {
        Mat av = a.value;
        Mat bv = b.value;
        if (av.sameShape(bv)) {
            return derived(av.plus(bv), List.of(a, b), g -> {
                a.accumulate(g);
                b.accumulate(g);
            });
        }
        if (bv.rows() == 1 && bv.cols() == av.cols()) {
            double[] out = new double[av.rows() * av.cols()];
            for (int i = 0; i < av.rows(); i++) {
                for (int j = 0; j < av.cols(); j++) {
                    out[i * av.cols() + j] = av.get(i, j) + bv.get(0, j);
                }
            }
            return derived(Mat.of(av.rows(), av.cols(), out), List.of(a, b), g -> {
                a.accumulate(g);
                b.accumulate(g.columnSums());
            });
        }
        throw new IllegalArgumentException("add: shape mismatch " + av.shape() + " vs " + bv.shape());
    }

    public static Node sub(Node a, Node b) {
        Mat v = a.value.zip(b.value, (x, y) -> x - y);
        return derived(v, List.of(a, b), g -> {
            a.accumulate(g);
            b.accumulate(g.scaled(-1));
        });
    }

    /** Elementwise product. */
    public static Node mul(Node a, Node b) {
        Mat av = a.value;
        Mat bv = b.value;
        return derived(av.times(bv), List.of(a, b), g -> {
            a.accumulate(g.times(bv));
            b.accumulate(g.times(av));
        });
    }

    public static Node matmul(Node a, Node b) {
        Mat av = a.value;
        Mat bv = b.value;
        return derived(av.matmul(bv), List.of(a, b), g -> {
            a.accumulate(g.matmul(bv.transpose()));
            b.accumulate(av.transpose().matmul(g));
        });
    }

    public static Node scale(Node a, double k) {
        return derived(a.value.scaled(k), List.of(a), g -> a.accumulate(g.scaled(k)));
    }

    public static Node tanh(Node a) {
        Mat y = a.value.map(Math::tanh);
        return derived(y, List.of(a), g -> a.accumulate(g.zip(y, (gi, yi) -> gi * (1 - yi * yi))));
    }

    public static Node relu(Node a) {
        Mat x = a.value;
        return derived(x.map(v -> v > 0 ? v : 0), List.of(a), g -> a.accumulate(g.zip(x, (gi, xi) -> xi > 0 ? gi : 0)));
    }

    public static Node sigmoid(Node a) {
        Mat y = a.value.map(Node::sigmoidOf);
        return derived(y, List.of(a), g -> a.accumulate(g.zip(y, (gi, yi) -> gi * yi * (1 - yi))));
    }

    private static double sigmoidOf(double v) {
        if (v >= 0) {
            return 1 / (1 + Math.exp(-v));
        }
        double e = Math.exp(v);
        return e / (1 + e);
    }

    public static Node square(Node a) {
        Mat x = a.value;
        return derived(x.times(x), List.of(a), g -> a.accumulate(g.times(x).scaled(2)));
    }

    /** Sum of all entries as a 1x1 node. */
    public static Node sum(Node a) {
        Mat x = a.value;
        return derived(Mat.of(1, 1, x.sumAll()), List.of(a), g -> a.accumulate(x.filled(g.get(0, 0))));
    }

    /** Mean of all entries as a 1x1 node. */
    public static Node mean(Node a) {
        Mat x = a.value;
        int n = x.rows() * x.cols();
        return derived(Mat.of(1, 1, x.sumAll() / n), List.of(a), g -> a.accumulate(x.filled(g.get(0, 0) / n)));
    }

    // ---- backward ----

    /**
     * Back-propagates from this 1x1 node. Ancestors are visited once each in reverse topological order
     * (iteratively). Intermediate grads are reset first; leaf grads accumulate.
     */
    public void backward() {
        if (value.rows() != 1 || value.cols() != 1) {
            throw new IllegalStateException("backward needs a 1x1 node but this is " + value.shape());
        }
        List<Node> order = topoOrder();
        for (Node n : order) {
            if (!n.parents.isEmpty()) {
                zeroGrad(List.of(n));
            }
        }
        if (!requiresGrad) {
            return;
        }
        grad = grad.plus(Mat.of(1, 1, 1));
        for (int i = order.size() - 1; i >= 0; i--) {
            Node n = order.get(i);
            if (n.backwardFn != null) {
                n.backwardFn.accept(n.grad);
            }
        }
    }

    /** Parents-before-children order of this node and its ancestors, via an explicit stack. */
    private List<Node> topoOrder() {
        List<Node> order = new ArrayList<>();
        IdentityHashMap<Node, Boolean> seen = new IdentityHashMap<>();
        ArrayDeque<Node> stack = new ArrayDeque<>();
        ArrayDeque<Integer> next = new ArrayDeque<>();
        stack.push(this);
        next.push(0);
        seen.put(this, Boolean.TRUE);
        while (!stack.isEmpty()) {
            Node n = stack.peek();
            int idx = next.pop();
            if (idx < n.parents.size()) {
                next.push(idx + 1);
                Node p = n.parents.get(idx);
                if (seen.put(p, Boolean.TRUE) == null) {
                    stack.push(p);
                    next.push(0);
                }
            } else {
                stack.pop();
                order.add(n);
            }
        }
        return order;
    }
}
