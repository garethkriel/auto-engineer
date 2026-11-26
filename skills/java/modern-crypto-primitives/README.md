# modern-crypto-primitives

- **Description:** ChaCha20, Poly1305, RFC 8439 ChaCha20-Poly1305 AEAD (`Aead.seal/open`), X25519 (RFC 7748) and strict Ed25519 (RFC 8032, canonical-encoding and S<L checks) in plain Java (SHA-512 from the JDK). Verified against RFC vectors and JDK 21 differential tests.
- **Language:** java
- **Tags:** crypto, aead, chacha20, poly1305, x25519, ed25519, strict-verification, differential-testing
- **Source round:** practice/java/20261126T0957Z
- **Notes:** BigInteger arithmetic, not constant time (educational). The round folder also holds `mutate.sh`; the torsion/identity-key tests in `CcTest` are the pattern for making strictness rules observable.
