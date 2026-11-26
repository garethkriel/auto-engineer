# hash-mac-toolkit

- **Language:** Java 21, standard library only
- **Tags:** hash, sha3, keccak, shake, blake2, hmac, hkdf, pbkdf2, crypto, streaming-digest
- **Source round:** practice/java/20261126T1456Z

Package `ae.hash`: `Digest` interface; `Keccak` (SHA3-224/256/384/512, SHAKE128/256 with streaming `squeeze`);
`Blake2b` / `Blake2s` (keyed, 1..64 / 1..32 byte output); `Hmac`, `Hkdf`, `Pbkdf2` generic over
`Supplier<Digest>`; strict `Hex`.

Notes: `digest()` finishes and resets; BLAKE2 holds back the last full block until more input or `digest()`
(final-block flag); round constants and rotation offsets of Keccak are derived, not tabulated.
Tests: Python-hashlib literals (chain of lengths 0..300 per variant), RFC 4231/5869/7914 vectors,
JDK `MessageDigest`/`Mac`/`SecretKeyFactory` differentials, chunk-invariance, validation. 42 tests.
Mutation pass: 45/48 killed; survivors are equivalent (high counter word needs >4 GiB input,
redundant `outer.reset()`, empty salt == zero block in HMAC).
