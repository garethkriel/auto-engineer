package ae.ad;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class MlpTest {
    private static final Mat XOR_X = Mat.of(4, 2, 0, 0, 0, 1, 1, 0, 1, 1);
    private static final Mat XOR_Y = Mat.of(4, 1, 0, 1, 1, 0);

    private static Node loss(Mlp m) {
        Node out = m.forward(Node.constant(XOR_X));
        return Node.mean(Node.square(Node.sub(out, Node.constant(XOR_Y))));
    }

    @Test
    void paramShapesFollowLayerSizes() {
        Mlp m = new Mlp(7, 2, 3, 1);
        List<Node> p = m.params();
        assertEquals(4, p.size());
        assertEquals("2x3", p.get(0).value().shape());
        assertEquals("1x3", p.get(1).value().shape());
        assertEquals("3x1", p.get(2).value().shape());
        assertEquals("1x1", p.get(3).value().shape());
        assertTrue(p.get(1).value().approx(Mat.zeros(1, 3), 0), "biases start at zero");
        double bound = Math.sqrt(6.0 / 5); // layer 0: in 2, out 3
        for (double v : p.get(0).value().toArray()) {
            assertTrue(Math.abs(v) <= bound, "weight " + v + " outside +-" + bound);
        }
    }

    @Test
    void sameSeedSameWeightsDifferentSeedDiffers() {
        Mlp a = new Mlp(5, 2, 4, 1);
        Mlp b = new Mlp(5, 2, 4, 1);
        Mlp c = new Mlp(6, 2, 4, 1);
        assertTrue(a.params().get(0).value().approx(b.params().get(0).value(), 0));
        assertFalse(a.params().get(0).value().approx(c.params().get(0).value(), 1e-12));
    }

    @Test
    void rejectsTooFewSizes() {
        assertThrows(IllegalArgumentException.class, () -> new Mlp(1, 3));
        assertThrows(IllegalArgumentException.class, () -> new Mlp(1, 3, 0, 1));
    }

    @Test
    void gradientCheckOnTheWholeNetwork() {
        Mlp m = new Mlp(11, 2, 3, 1);
        List<Node> ps = m.params();
        Mat[] inputs = ps.stream().map(Node::value).toArray(Mat[]::new);
        double err = GradCheck.maxRelError(in -> {
            // rebuild the same network on the supplied params: forward = tanh(x W0 + b0) W1 + b1
            Node h = Node.tanh(Node.add(Node.matmul(Node.constant(XOR_X), in.get(0)), in.get(1)));
            Node out = Node.add(Node.matmul(h, in.get(2)), in.get(3));
            return Node.mean(Node.square(Node.sub(out, Node.constant(XOR_Y))));
        }, inputs);
        assertTrue(err < 1e-6, "err " + err);
        // and the Mlp's own forward agrees with that hand-built network
        Node viaMlp = m.forward(Node.constant(XOR_X));
        Node h = Node.tanh(Node.add(Node.matmul(Node.constant(XOR_X), ps.get(0)), ps.get(1)));
        Node byHand = Node.add(Node.matmul(h, ps.get(2)), ps.get(3));
        assertTrue(viaMlp.value().approx(byHand.value(), 1e-12));
    }

    @Test
    void sgdMovesAgainstTheGradientAndClearsGrads() {
        Mlp m = new Mlp(3, 2, 3, 1);
        Node l = loss(m);
        l.backward();
        Mat w0 = m.params().get(0).value();
        Mat g0 = m.params().get(0).grad();
        m.sgd(0.1);
        Mat expected = w0.plus(g0.scaled(-0.1));
        assertTrue(expected.approx(m.params().get(0).value(), 1e-12));
        assertTrue(Mat.zeros(2, 3).approx(m.params().get(0).grad(), 0), "grads cleared after step");
    }

    @Test
    void trainsXorBelowThreshold() {
        Mlp m = new Mlp(42, 2, 4, 1);
        double first = loss(m).value().get(0, 0);
        double last = first;
        for (int epoch = 0; epoch < 3000; epoch++) {
            Node l = loss(m);
            l.backward();
            m.sgd(0.3);
            last = l.value().get(0, 0);
        }
        assertTrue(last < 0.01, "final loss " + last + " (initial " + first + ")");
        Mat out = m.forward(Node.constant(XOR_X)).value();
        double[] y = out.toArray();
        assertTrue(y[0] < 0.2 && y[1] > 0.8 && y[2] > 0.8 && y[3] < 0.2, java.util.Arrays.toString(y));
    }

    @Test
    void trainingIsDeterministicForASeed() {
        double[] a = train(9);
        double[] b = train(9);
        assertArrayEquals(a, b, 0);
    }

    private static double[] train(long seed) {
        Mlp m = new Mlp(seed, 2, 3, 1);
        for (int i = 0; i < 50; i++) {
            loss(m).backward();
            m.sgd(0.1);
        }
        return m.forward(Node.constant(XOR_X)).value().toArray();
    }
}
