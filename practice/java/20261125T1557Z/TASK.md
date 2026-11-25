# Task: reverse-mode automatic differentiation + tiny MLP (package `ae.ad`)

Level 20 capstone, topic "capstone systems": a small end-to-end numeric system.
Overlap check: `ls benchmark/java` lists j01-roman .. j35-minisql (roman, wordfreq, lru, csv, intervals, lcs, expr,
graph, bank, counter, streams, fileindex, ratelimit, json, topo, ranges, cache, diff, allocate, cron, sheet, regex,
segtree, queue, bizhours, rwlock, primes, script, kvlog, miniserver, ranked, fsm, pricing, vclock, minisql).
None is autodiff, matrices or neural nets. `j07-expr` is expression evaluation; this task differentiates a
computation graph of matrices and shares no algorithm with it.

## Design
- `Mat`: immutable row-major `double` matrix (rows x cols, both >= 1). `of(rows, cols, values...)`, `zeros`, `get`, `plus`, `times`
  (elementwise), `matmul`, `transpose`, `equals` with tolerance via `approx(other, eps)`.
- `Node`: a value in a computation graph. Holds `Mat value`, `Mat grad` (same shape, zero initially) and parents.
  Leaves: `Node.param(Mat)`, `Node.constant(Mat)` (constants never receive gradient).
  Ops (static, all return new Nodes): `add(a,b)` (same shape, or `b` is 1 x cols row broadcast over a's rows),
  `sub`, `mul` (elementwise), `matmul`, `scale(a, k)`, `tanh`, `relu`, `sigmoid`, `square`, `sum` (-> 1x1), `mean` (-> 1x1).
- `node.backward()`: node must be 1x1 (else IllegalStateException). Seeds its grad with 1, propagates through every
  ancestor in reverse topological order (iterative, no recursion: a 200000-node chain must work),
  and ACCUMULATES grads (a node used twice gets both contributions). Calling backward twice accumulates again
  until `zeroGrad()` is called on the nodes (`Node.zeroGrad(Collection<Node>)` zeroes the grads of the given nodes).
- `GradCheck.maxRelError(Function<List<Node>,Node> f, Mat... inputs)`: builds param nodes from inputs, runs f and backward,
  then compares each analytic grad entry with a central difference (h = 1e-6) of a fresh forward pass;
  returns max over entries of |a-n| / max(1, |a|+|n|).
- `Mlp(long seed, int... sizes)`: layers with tanh between and linear output; weights from an internal deterministic
  generator (`SplittableRandom(seed)`, uniform in +-sqrt(6/(in+out))), biases zero. `forward(Node x)`, `params()` (List<Node>,
  order W0,b0,W1,b1...), `sgd(double lr)` (value -= lr*grad, then zero grads).

## Success criteria
- Hand-derived literal gradients for each op; gradient check < 1e-6 for each op and composed graphs.
- Shared-node accumulation, broadcast bias gradient (sum over rows), constants get no grad, double backward accumulates.
- Deep chain 200000 nodes does not overflow the stack.
- Shape errors throw IllegalArgumentException with a message naming both shapes.
- MLP trains XOR to loss < 0.01 and is deterministic for a seed; gradient check on the MLP passes.
- `mvn -q -B test` passes.
