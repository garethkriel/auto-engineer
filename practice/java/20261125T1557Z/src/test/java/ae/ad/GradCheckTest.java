package ae.ad;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.SplittableRandom;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class GradCheckTest {
    private static Mat random(SplittableRandom r, int rows, int cols) {
        double[] v = new double[rows * cols];
        for (int i = 0; i < v.length; i++) {
            v[i] = r.nextDouble() * 2 - 1 + 0.05; // avoid exact 0 (relu kink)
        }
        return Mat.of(rows, cols, v);
    }

    private static void check(Function<List<Node>, Node> f, Mat... inputs) {
        double err = GradCheck.maxRelError(f, inputs);
        assertTrue(err < 1e-6, "max relative error " + err);
    }

    @Test
    void everyUnaryOpPassesTheCheck() {
        SplittableRandom r = new SplittableRandom(1);
        Mat x = random(r, 3, 4);
        check(in -> Node.sum(Node.tanh(in.get(0))), x);
        check(in -> Node.sum(Node.relu(in.get(0))), x);
        check(in -> Node.sum(Node.sigmoid(in.get(0))), x);
        check(in -> Node.sum(Node.square(in.get(0))), x);
        check(in -> Node.mean(Node.scale(in.get(0), -2.5)), x);
    }

    @Test
    void everyBinaryOpPassesTheCheck() {
        SplittableRandom r = new SplittableRandom(2);
        Mat a = random(r, 3, 4);
        Mat b = random(r, 3, 4);
        Mat row = random(r, 1, 4);
        Mat w = random(r, 4, 2);
        check(in -> Node.sum(Node.add(in.get(0), in.get(1))), a, b);
        check(in -> Node.sum(Node.square(Node.add(in.get(0), in.get(1)))), a, row);
        check(in -> Node.sum(Node.square(Node.sub(in.get(0), in.get(1)))), a, b);
        check(in -> Node.sum(Node.mul(in.get(0), in.get(1))), a, b);
        check(in -> Node.sum(Node.square(Node.matmul(in.get(0), in.get(1)))), a, w);
    }

    @Test
    void composedGraphWithSharingPassesTheCheck() {
        SplittableRandom r = new SplittableRandom(3);
        Mat x = random(r, 2, 3);
        Mat w = random(r, 3, 3);
        Mat b = random(r, 1, 3);
        check(in -> {
            Node h = Node.tanh(Node.add(Node.matmul(in.get(0), in.get(1)), in.get(2)));
            Node g = Node.sigmoid(Node.matmul(h, in.get(1))); // w reused
            return Node.mean(Node.mul(g, h));
        }, x, w, b);
    }

    @Test
    void checkerCanFailAtAKink() {
        // relu at exactly 0: analytic subgradient 0, central difference 0.5 -> error ~0.5
        double err = GradCheck.maxRelError(in -> Node.sum(Node.relu(in.get(0))), Mat.of(1, 1, 0));
        assertEquals(0.5, err, 1e-3);
    }

    @Test
    void checkerDetectsAWrongGradient() {
        // f(x) = sum(x*x) computed through a graph that treats one factor as a constant: grad is x, truth is 2x
        double err = GradCheck.maxRelError(in -> {
            Node x = in.get(0);
            Node frozen = Node.constant(x.value());
            return Node.sum(Node.mul(x, frozen));
        }, Mat.of(1, 2, 3, 4));
        assertTrue(err > 0.1, "err " + err);
    }
}
