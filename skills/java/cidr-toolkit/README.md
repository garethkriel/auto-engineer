# cidr-toolkit

- **Description:** IPv4 addressing in package `ae.netroute`: strict `Ip4`/`Cidr` parsing (host bits rejected), `AddrSet` (union/intersect/subtract/complement, minimal CIDR cover via `cidrs()`), `RouteTable` (binary-trie longest-prefix match, remove with pruning, lossless `aggregate()`, exact `equivalentTo`), and `Firewall` (first-match text rules, exact `shadowed()` by box subtraction, `allowedSources`).
- **Language:** java (21, standard library only)
- **Tags:** cidr, ipv4, trie, longest-prefix-match, route-aggregation, firewall, interval-set, box-subtraction
- **Source round:** `practice/java/20261124T0156Z`
- **Reuse notes:** minimal cover of a range = repeatedly take the largest aligned block at `lo` (lowest set bit, shrink until it fits). Piecewise-constant functions are compared exactly by checking only breakpoints. Verify geometry-like analyses with a coordinate-compression cell oracle.
- **Tests:** `mvn -q -B test` (60 tests; randomized differential tests against independent oracles).
