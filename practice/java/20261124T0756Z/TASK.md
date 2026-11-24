# Task: `ae.chain` — a UTXO blockchain ledger core (level 20 capstone)

Overlap check: `benchmark/java/` j01-j35 (roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter, streams, fileindex, ratelimit, json, topo, ranges, cache, diff, allocate, cron, sheet, regex, segtree, queue, bizhours, rwlock, primes, script, kvlog, miniserver, ranked, fsm, pricing, vclock, minisql) has no Merkle tree, proof-of-work or UTXO/reorg task; j09-bank is account balances, not an unspent-output set with undo.

## Design
- `Hashing`: SHA-256 hex (JDK `MessageDigest`), leading-zero-hex-digit count.
- `Tx(signer, inputs, outputs, memo)`; id = SHA-256 of a length-prefixed (`DataOutputStream`) encoding, so field boundaries are unambiguous. No inputs = coinbase. `TxIn(txId,index)` doubles as outpoint key. `TxOut(owner, amount>0)`.
- `Merkle`: leaf = H("L"+id), node = H("N"+l+r), odd node promoted unchanged (no duplicate-last, which would make `[a,b,c]` and `[a,b,c,c]` collide). Inclusion proofs of (sibling, siblingIsLeft) steps.
- `Block(prevId, height, nonce, merkleRoot, txs)`; id hashes the header only; `Block.mine` searches the nonce for `difficulty` leading zero hex digits.
- `UtxoSet.apply(block, subsidy)` validates and applies atomically, returning an `Undo` log; `undo` reverts. Rules: first tx is the only coinbase with memo `height:N`; non-coinbase inputs exist (earlier txs of the same block count), owner == signer, no overflow (`Math.addExact`), outputs <= inputs, fee = difference; coinbase pays <= subsidy + fees (paying less burns); coinbase outputs are not spendable inside their own block; duplicate tx ids rejected. A failure leaves the set unchanged.
- `Chain`: genesis mined in the constructor; `add(block)` returns `EXTENDED`, `SIDE_CHAIN`, `REORGANISED` or `DUPLICATE`, or throws `InvalidBlockException` with no state change. Heavier chain = greater height (constant difficulty); ties keep the first seen. Blocks off the tip are validated against state replayed from genesis to their parent; a reorg undoes to the common ancestor and applies the new branch.

## Success criteria
- Merkle known answers, proofs verify for every index of 1..40 leaves, tampering fails.
- Every rejection rule has a test asserting the message and unchanged state.
- Fork/reorg scenarios, and a seeded random simulation with invalid-block injection comparing the live UTXO set to an independent oracle replay of the best chain, plus a supply invariant.
- `mvn -q -B test` passes.
