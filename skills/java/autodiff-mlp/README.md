# autodiff-mlp

- **Description:** Reverse-mode automatic differentiation over small matrices (`Mat`, `Node`) with add/row-broadcast, sub, mul, matmul, scale, tanh, relu, sigmoid, square, sum, mean; iterative topological backward (no stack overflow on 200k-node chains); finite-difference `GradCheck`; seeded tanh `Mlp` with SGD, trained on XOR.
- **Language:** java (standard library only)
- **Tags:** autodiff, backpropagation, gradient-check, neural-network, matrix, numeric
- **Source round:** practice/java/20261125T1557Z
- **Usage:** `Node.backward()` on a 1x1 loss; leaf grads accumulate until `Node.zeroGrad`, intermediates reset per pass. Use `GradCheck.maxRelError(f, inputs)` to test any new op (< 1e-6 expected, but not at kinks such as relu at 0).
- **Tests:** `mvn -q -B test` (30 tests).
