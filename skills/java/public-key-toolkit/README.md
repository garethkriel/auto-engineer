# public-key-toolkit

- **Description:** public-key crypto on `BigInteger`: NIST P-256 and secp256k1 (Jacobian arithmetic, strict SEC1 point codec), RFC 6979 deterministic ECDSA, strict DER signature codec, ECDH with invalid-curve checks, Miller-Rabin and seeded RSA key generation, CRT private operation with fault check, RSASSA-PKCS1-v1_5/SHA-256 with strict (rebuild-and-compare) verification. Not constant time; educational.
- **Language:** java (package `ae.pk`, JDK only)
- **Tags:** ecdsa, rfc6979, p-256, secp256k1, rsa, pkcs1, miller-rabin, der, ecdh, differential-testing
- **Source round:** `practice/java/20261126T0356Z`
- **Tests:** 49 JUnit tests (`mvn -q -B test`): RFC 6979 vectors, JDK `Signature`/`KeyAgreement` differential, naive affine oracle, brute-forced toy curve (all scalars, all keys, retry paths, encoding boundaries), exhaustive DER truncation/bit flips, forged-padding RSA signatures with controls, CRT fault.
- **Mutation pass:** 45 mutants, 41 killed after closing 8 gaps; the 4 survivors are equivalent (y=0 doubling in a prime-order group, `r >= n` guard in verify masked by `r == x mod n`, DER overrun guard masked by the final position check, trial division masked by Miller-Rabin).
- Overlap: not related to any benchmark task (no benchmark solution here).
