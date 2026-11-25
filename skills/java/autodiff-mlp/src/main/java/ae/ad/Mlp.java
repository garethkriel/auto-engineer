package ae.ad;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/** Fully connected network: tanh between layers, linear output; deterministic for a seed. */
public final class Mlp {
    private final List<Node> params = new ArrayList<>();

    /** {@code sizes} = input width, hidden widths..., output width (at least two, all positive). */
    public Mlp(long seed, int... sizes) {
        if (sizes.length < 2) {
            throw new IllegalArgumentException("need at least input and output sizes");
        }
        SplittableRandom rnd = new SplittableRandom(seed);
        for (int l = 0; l + 1 < sizes.length; l++) {
            int in = sizes[l];
            int out = sizes[l + 1];
            if (in < 1 || out < 1) {
                throw new IllegalArgumentException("layer sizes must be positive");
            }
            double bound = Math.sqrt(6.0 / (in + out));
            double[] w = new double[in * out];
            for (int i = 0; i < w.length; i++) {
                w[i] = (rnd.nextDouble() * 2 - 1) * bound;
            }
            params.add(Node.param(Mat.of(in, out, w)));
            params.add(Node.param(Mat.zeros(1, out)));
        }
    }

    /** Current parameters in order W0, b0, W1, b1, ...; {@link #sgd} replaces them, so re-fetch after a step. */
    public List<Node> params() {
        return List.copyOf(params);
    }

    public Node forward(Node x) {
        Node h = x;
        int layers = params.size() / 2;
        for (int l = 0; l < layers; l++) {
            h = Node.add(Node.matmul(h, params.get(2 * l)), params.get(2 * l + 1));
            if (l + 1 < layers) {
                h = Node.tanh(h);
            }
        }
        return h;
    }

    /** value -= lr * grad for every parameter, then clears the grads. */
    public void sgd(double lr) {
        List<Node> next = new ArrayList<>();
        for (Node p : params) {
            next.add(Node.param(p.value().plus(p.grad().scaled(-lr))));
        }
        params.clear();
        params.addAll(next);
    }
}
