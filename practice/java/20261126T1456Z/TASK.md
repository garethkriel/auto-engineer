# Task: ae.hash — Keccak/SHA-3/SHAKE, BLAKE2b/2s, HMAC, HKDF, PBKDF2 from scratch

Level 20 capstone. Package `ae.hash`, standard library only.

Overlap check: `benchmark/java/` names j01-roman .. j35-minisql listed; none is a hash or MAC
primitive (nearest: j14-json, j29-kvlog use no hashing). No existing skill contains Keccak/BLAKE2
(`grep -il "keccak|blake" skills/java/*/README.md` is empty).

## API
- `interface Digest`: `digestLength()`, `blockLength()` (bytes), `update(byte[],int,int)`,
  `update(byte[])`, `digest()` (finishes AND resets), `reset()`.
- `Keccak`: factories `sha3_224/256/384/512()`, `shake128()`, `shake256()`; blockLength = rate.
  SHAKE has default digest length 32/64 bytes and `squeeze(int n)` for extendable output
  (can be called repeatedly; after the first squeeze `update` throws `IllegalStateException`;
  `reset()` returns to absorbing). Squeezing n1 then n2 bytes equals squeezing n1+n2 at once.
- `Blake2b(int outLen, byte[] key)` (outLen 1..64, key 0..64 bytes), `Blake2s` (1..32, key 0..32);
  block 128 / 64 bytes. Convenience constructors with no key / default full length.
  Invalid sizes -> `IllegalArgumentException`.
- `Hmac(Supplier<Digest>, byte[] key)`: `update`, `doFinal()` (resets, keeps key), static `mac(...)`.
- `Hkdf.extract(Supplier<Digest>, salt, ikm)`, `Hkdf.expand(supplier, prk, info, length)`;
  length > 255*hashLen -> `IllegalArgumentException`. Empty/null salt = hashLen zero bytes.
- `Pbkdf2.derive(Supplier<Digest>, password, salt, iterations, dkLen)`; iterations >= 1, dkLen >= 1.
- `Hex.encode/decode` (strict lowercase/uppercase hex, odd length rejected).

## Success criteria
- Published/Python-hashlib literals for empty, "abc", 200-byte messages, and a "chain of lengths
  0..300" digest-of-digests literal for every variant (rate boundaries 135/136/137 etc.).
- Streaming invariance: random chunk splits == one-shot; digest() resets; reuse gives same result.
- Differential tests against JDK `MessageDigest` SHA3-*, `Mac` HmacSHA3-256 / PBKDF2WithHmacSHA256.
- Keyed BLAKE2 and variable output lengths vs hashlib literals; HKDF vs RFC 5869 vectors.
- Argument validation tests; state-misuse tests for SHAKE.
- A mutation pass over the main sources (each single-line mutant must be killed).
