# Task: modern symmetric/asymmetric primitives from scratch (package `ae.cc`)

Level 20 capstone. Overlap check: `benchmark/java/` names j01-roman ... j35-minisql read; none is cryptography. Existing skill `crypto-primitives` covers SHA-256/HMAC/AES; this round covers ChaCha20, Poly1305, the RFC 8439 AEAD, X25519 and Ed25519 (SHA-512 taken from `MessageDigest`; everything else is written here).

## API (all static, all inputs validated, all `byte[]` are copied)
- `ChaCha20.block(key32, nonce12, counter)` -> 64-byte keystream block; `ChaCha20.xor(key32, nonce12, counter, data)` -> data XOR keystream (counter is a 32-bit unsigned int, wrap past 2^32-1 is rejected as `IllegalArgumentException`).
- `Poly1305.mac(key32, msg)` -> 16-byte tag (BigInteger arithmetic, r clamped); `Poly1305.verify(key, msg, tag)` constant time.
- `Aead.seal(key, nonce12, aad, plaintext)` -> ciphertext||tag (RFC 8439 s2.8: one-time key = block(counter 0), data from counter 1, mac over aad|pad|ct|pad|len(aad)|len(ct)); `Aead.open(...)` -> `Optional<byte[]>` empty on any forgery/short input.
- `X25519.scalarMult(k32, u32)` (RFC 7748, clamping, top bit of u ignored), `X25519.publicKey(k)`, `X25519.agree(k, peerPub)` throws `IllegalArgumentException` on all-zero output (low order point).
- `Ed25519.publicKey(seed32)`, `Ed25519.sign(seed32, msg)` (64 bytes), `Ed25519.verify(pub32, msg, sig64)` -> boolean, strict: rejects S >= L, non-canonical y (>= p) in A or R, points not on the curve, wrong lengths; never throws on malformed input.

## Success criteria
- Published vectors (RFC 8439, 7748, 8032) as literals.
- Differential tests against JDK 21 (`ChaCha20-Poly1305`, `X25519`, `Ed25519`) on randomized inputs of lengths 0..300, including both directions for verification.
- Rejection tests with a control: one-bit-flip of every byte of ct/tag/aad/nonce rejected; sig malleability (S+L) rejected while S accepted; non-canonical encodings rejected.
- Mutation check script over the main sources.
