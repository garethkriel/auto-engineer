package ae.ad;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class NodeTest {
    private static final double EPS = 1e-9;

    private static void assertMat(Mat expected, Mat actual) {
        assertTrue(expected.approx(actual, EPS), "expected " + expected + " but was " + actual);
    }

    @Test
    void addWithRowBroadcastHasLiteralGradients() {
        Node a = Node.param(Mat.of(2, 2, 1, 2, 3, 4));
        Node b = Node.param(Mat.of(1, 2, 10, 20));
        Node s = Node.sum(Node.add(a, b));
        assertMat(Mat.of(2, 2, 11, 22, 13, 24), Node.add(a, b).value());
        s.backward();
        assertMat(Mat.of(2, 2, 1, 1, 1, 1), a.grad());
        assertMat(Mat.of(1, 2, 2, 2), b.grad()); // bias grad = sum over the 2 rows
    }

    @Test
    void mulGradientsSwapOperands() {
        Node a = Node.param(Mat.of(1, 2, 1, 2));
        Node b = Node.param(Mat.of(1, 2, 3, 4));
        Node.sum(Node.mul(a, b)).backward();
        assertMat(Mat.of(1, 2, 3, 4), a.grad());
        assertMat(Mat.of(1, 2, 1, 2), b.grad());
    }

    @Test
    void subGradientsHaveOppositeSigns() {
        Node a = Node.param(Mat.of(1, 2, 5, 6));
        Node b = Node.param(Mat.of(1, 2, 1, 1));
        Node.sum(Node.sub(a, b)).backward();
        assertMat(Mat.of(1, 2, 1, 1), a.grad());
        assertMat(Mat.of(1, 2, -1, -1), b.grad());
    }

    @Test
    void matmulLiteralValueAndGradients() {
        Node a = Node.param(Mat.of(2, 2, 1, 2, 3, 4));
        Node b = Node.param(Mat.of(2, 2, 5, 6, 7, 8));
        Node p = Node.matmul(a, b);
        assertMat(Mat.of(2, 2, 19, 22, 43, 50), p.value());
        Node.sum(p).backward();
        // dA[i][k] = row sum k of B = [11, 15]; dB[k][j] = column sum k of A = [4, 6]
        assertMat(Mat.of(2, 2, 11, 15, 11, 15), a.grad());
        assertMat(Mat.of(2, 2, 4, 4, 6, 6), b.grad());
    }

    @Test
    void matmulNonSquareShapes() {
        Node a = Node.param(Mat.of(1, 3, 1, 2, 3));
        Node b = Node.param(Mat.of(3, 2, 1, 0, 0, 1, 1, 1));
        Node p = Node.matmul(a, b);
        assertMat(Mat.of(1, 2, 4, 5), p.value());
        Node.sum(p).backward();
        assertMat(Mat.of(1, 3, 1, 1, 2), a.grad());
        assertMat(Mat.of(3, 2, 1, 1, 2, 2, 3, 3), b.grad());
    }

    @Test
    void tanhAtHalfHasLiteralGradient() {
        Node x = Node.param(Mat.of(1, 1, 0.5));
        Node y = Node.tanh(x);
        assertEquals(0.46211715726, y.value().get(0, 0), 1e-9);
        y.backward();
        assertEquals(0.78644773296, x.grad().get(0, 0), 1e-9); // 1 - tanh(0.5)^2
    }

    @Test
    void reluPassesOnlyPositiveAndIsZeroAtZero() {
        Node x = Node.param(Mat.of(1, 3, -1, 0, 2));
        Node.sum(Node.relu(x)).backward();
        assertMat(Mat.of(1, 3, 0, 0, 1), x.grad());
    }

    @Test
    void sigmoidAtZero() {
        Node x = Node.param(Mat.of(1, 1, 0));
        Node y = Node.sigmoid(x);
        assertEquals(0.5, y.value().get(0, 0), EPS);
        y.backward();
        assertEquals(0.25, x.grad().get(0, 0), EPS);
    }

    @Test
    void sigmoidIsStableForLargeMagnitudes() {
        Node x = Node.param(Mat.of(1, 2, -800, 800));
        Node y = Node.sigmoid(x);
        assertMat(Mat.of(1, 2, 0, 1), y.value());
        Node.sum(y).backward();
        assertMat(Mat.of(1, 2, 0, 0), x.grad());
    }

    @Test
    void squareScaleMeanSum() {
        Node x = Node.param(Mat.of(1, 2, 3, -2));
        Node.sum(Node.square(x)).backward();
        assertMat(Mat.of(1, 2, 6, -4), x.grad());

        Node y = Node.param(Mat.of(2, 2, 1, 2, 3, 4));
        Node m = Node.mean(y);
        assertEquals(2.5, m.value().get(0, 0), EPS);
        m.backward();
        assertMat(Mat.of(2, 2, 0.25, 0.25, 0.25, 0.25), y.grad());

        Node z = Node.param(Mat.of(1, 2, 1, 1));
        Node.sum(Node.scale(z, 3)).backward();
        assertMat(Mat.of(1, 2, 3, 3), z.grad());
    }

    @Test
    void sharedNodeAccumulatesBothContributions() {
        Node x = Node.param(Mat.of(1, 3, 1, 2, 3));
        Node.sum(Node.mul(x, x)).backward();
        assertMat(Mat.of(1, 3, 2, 4, 6), x.grad());

        Node y = Node.param(Mat.of(1, 1, 5));
        Node.sum(Node.add(y, y)).backward();
        assertMat(Mat.of(1, 1, 2), y.grad());
    }

    @Test
    void diamondGraphVisitsSharedIntermediateOnce() {
        // t = x*x; f = t + t*t  => df/dx = 2x + 4x^3 ; at x = 2: 4 + 32 = 36
        Node x = Node.param(Mat.of(1, 1, 2));
        Node t = Node.mul(x, x);
        Node f = Node.add(t, Node.mul(t, t));
        assertEquals(20, f.value().get(0, 0), EPS);
        f.backward();
        assertEquals(36, x.grad().get(0, 0), EPS);
    }

    @Test
    void secondBackwardAccumulatesUntilZeroGrad() {
        Node x = Node.param(Mat.of(1, 1, 3));
        Node f = Node.square(x);
        f.backward();
        assertEquals(6, x.grad().get(0, 0), EPS);
        f.backward();
        assertEquals(12, x.grad().get(0, 0), EPS);
        Node.zeroGrad(List.of(x, f));
        assertEquals(0, x.grad().get(0, 0), EPS);
        f.backward();
        assertEquals(6, x.grad().get(0, 0), EPS);
    }

    @Test
    void constantsNeverReceiveGradient() {
        Node c = Node.constant(Mat.of(1, 2, 7, 8));
        Node x = Node.param(Mat.of(1, 2, 1, 1));
        Node.sum(Node.mul(c, x)).backward();
        assertMat(Mat.of(1, 2, 7, 8), x.grad());
        assertMat(Mat.zeros(1, 2), c.grad());
    }

    @Test
    void backwardRequiresScalar() {
        Node x = Node.param(Mat.of(1, 2, 1, 2));
        IllegalStateException e = assertThrows(IllegalStateException.class, x::backward);
        assertTrue(e.getMessage().contains("1x2"), e.getMessage());
    }

    @Test
    void deepChainDoesNotOverflowTheStack() {
        Node x = Node.param(Mat.of(1, 1, 1));
        Node one = Node.constant(Mat.of(1, 1, 1));
        Node n = x;
        for (int i = 0; i < 200_000; i++) {
            n = Node.add(n, one);
        }
        assertEquals(200_001, n.value().get(0, 0), EPS);
        n.backward();
        assertEquals(1, x.grad().get(0, 0), EPS);
    }

    @Test
    void shapeErrorsNameBothShapes() {
        Node a = Node.param(Mat.of(2, 3, 1, 2, 3, 4, 5, 6));
        Node b = Node.param(Mat.of(2, 3, 1, 2, 3, 4, 5, 6));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Node.matmul(a, b));
        assertTrue(e.getMessage().contains("2x3") && e.getMessage().indexOf("2x3") != e.getMessage().lastIndexOf("2x3"),
                e.getMessage());
        Node c = Node.param(Mat.of(3, 2, 1, 2, 3, 4, 5, 6));
        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class, () -> Node.add(a, c));
        assertTrue(e2.getMessage().contains("2x3") && e2.getMessage().contains("3x2"), e2.getMessage());
        // a column vector is not a broadcastable row
        Node col = Node.param(Mat.of(2, 1, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> Node.add(a, col));
    }

    @Test
    void matRejectsBadConstruction() {
        assertThrows(IllegalArgumentException.class, () -> Mat.of(2, 2, 1, 2, 3));
        assertThrows(IllegalArgumentException.class, () -> Mat.zeros(0, 3));
    }
}
