package ae.ad;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Compares analytic gradients with central finite differences. */
public final class GradCheck {
    private static final double H = 1e-6;

    private GradCheck() {}

    /**
     * Max over all input entries of {@code |analytic - numeric| / max(1, |analytic| + |numeric|)}, where numeric
     * is a central difference of a fresh forward pass.
     */
    public static double maxRelError(Function<List<Node>, Node> f, Mat... inputs) {
        List<Node> params = new ArrayList<>();
        for (Mat m : inputs) {
            params.add(Node.param(m));
        }
        f.apply(params).backward();
        double worst = 0;
        for (int p = 0; p < inputs.length; p++) {
            Mat grad = params.get(p).grad();
            for (int i = 0; i < inputs[p].rows(); i++) {
                for (int j = 0; j < inputs[p].cols(); j++) {
                    double base = inputs[p].get(i, j);
                    double plus = eval(f, inputs, p, inputs[p].with(i, j, base + H));
                    double minus = eval(f, inputs, p, inputs[p].with(i, j, base - H));
                    double numeric = (plus - minus) / (2 * H);
                    double analytic = grad.get(i, j);
                    double err = Math.abs(analytic - numeric) / Math.max(1, Math.abs(analytic) + Math.abs(numeric));
                    worst = Math.max(worst, err);
                }
            }
        }
        return worst;
    }

    private static double eval(Function<List<Node>, Node> f, Mat[] inputs, int replaced, Mat replacement) {
        List<Node> ps = new ArrayList<>();
        for (int k = 0; k < inputs.length; k++) {
            ps.add(Node.param(k == replaced ? replacement : inputs[k]));
        }
        return f.apply(ps).value().get(0, 0);
    }
}
