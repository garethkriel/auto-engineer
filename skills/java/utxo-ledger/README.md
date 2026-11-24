# utxo-ledger

**Description:** UTXO blockchain ledger core: SHA-256 Merkle tree with inclusion proofs (odd node promoted, domain-separated leaves), proof-of-work blocks, atomic block apply with an undo log, side branches validated against replayed parent state, and reorganisation to the heavier chain.

**Language:** Java 21, standard library only.

**Tags:** merkle, blockchain, utxo, reorg, undo-log, simulation, oracle-testing

**Source round:** practice/java/20261124T0756Z

**Use it for:** apply/undo structures where a failed step must leave state unchanged, branch-switching with a common ancestor, and tests that compare live state with an independent replay oracle after every random step.

Tests: `mvn -q -B test` (30 tests).
