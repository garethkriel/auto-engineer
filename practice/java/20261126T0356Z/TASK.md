# 20261126T0356Z — public-key toolkit `ae.pk` (level 20 capstone)

## Task
Build, from scratch on `BigInteger` arithmetic only (no JDK key classes in main code), a small public-key toolkit:
ECC over two short-Weierstrass curves (NIST P-256, secp256k1), deterministic ECDSA (RFC 6979, HMAC-SHA-256),
strict DER signature codec, ECDH, and RSA (Miller-Rabin, seeded key generation, CRT private operation with fault check,
RSASSA-PKCS1-v1_5 / SHA-256 with strict verification).

Overlap check: `benchmark/java/` names are j01-roman … j35-minisql (roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter,
streams, fileindex, ratelimit, json, topo, ranges, cache, diff, allocate, cron, sheet, regex, segtree, queue, bizhours, rwlock, primes,
script, kvlog, miniserver, ranked, fsm, pricing, vclock, minisql). None is public-key crypto. (`crypto-primitives` skill is symmetric only.)

## Design
Packages: single package `ae.pk`.
- `Curve`: immutable parameters (p,a,b,n,G; cofactor 1), `P256`, `SECP256K1`. `Point` (affine, `INFINITY`).
  Arithmetic in Jacobian coordinates (generic `a`): `add`, `dbl`, `mul(k)`, `mulAdd`. Encoding: SEC1 uncompressed `04|x|y`,
  compressed `02/03|x`; strict decode (length, prefix, x,y < p, on curve, sqrt check, no infinity).
- `Ecdsa`: `publicKey`, `sign(d, msg)` (RFC 6979 nonce, retry loop when r or s is zero), `verify`, `lowS`, `Signature(r,s)`.
- `Der`: `encode(Signature)` minimal; `decode` strict (canonical: re-encoding equals input; short-form lengths only, no negative or padded ints).
- `Ecdh`: `sharedSecret(curve, d, peer)` validates scalar range and peer point (invalid-curve defence).
- `Primes`: Miller-Rabin (first 12 prime bases deterministic below 3.3e24 + random bases above), `randomPrime`.
- `Rsa`: `PublicKey(n,e)`, `PrivateKey` (validated CRT params), `generate(bits, Random)` (exact bit length, |p-q| large, gcd(e,λ)=1),
  `sign`/`verify` PKCS#1 v1.5 SHA-256. Verification rebuilds the expected encoded message and compares bytes (no lax parsing).
  Private operation recomputes `s^e mod n` and throws on mismatch (Lenstra CRT fault defence).

## Success criteria
- Constants of P-256 equal the JDK's `secp256r1` parameters; `n*G = O` for both curves; published 2G coordinates.
- RFC 6979 A.2.5 P-256/SHA-256 vectors ("sample", "test") reproduce k, r, s exactly.
- Differential: our ECDSA signatures verify under JDK `SHA256withECDSA`; JDK signatures verify with ours; ECDH equals JDK `KeyAgreement`;
  our RSA signature bytes equal JDK `SHA256withRSA` bytes (deterministic) and JDK signatures verify with ours.
- Jacobian multiplication equals a naive affine double-and-add oracle on random scalars.
- Miller-Rabin equals `BigInteger.isProbablePrime`/sieve for all n < 200000; strong pseudoprimes and Carmichael numbers rejected.
- Negative tests: off-curve/aliased/bad-prefix points, DER corruptions (exhaustive truncation and byte flips: either rejected or canonical),
  forged lax-padding RSA signature rejected with a control that differs only by the defect, CRT fault detected.
- A mutation pass over the main code; survivors justified.

## Assumptions
Constant-time behaviour is out of scope (BigInteger is not constant time); the toolkit is educational. Hash is SHA-256 via `MessageDigest`,
HMAC via `javax.crypto.Mac` (JDK standard library, allowed).
