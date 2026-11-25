# Task 20261125T1956Z — symmetric crypto primitives from scratch (`ae.crypto`)

Language: Java 21, standard library only (JUnit 5 for tests). Level 20 capstone.

## Overlap check
Benchmark directories read: j01-roman .. j35-minisql (roman, wordfreq, lru, csv, intervals, lcs, expr, graph, bank, counter,
streams, fileindex, ratelimit, json, topo, ranges, cache, diff, allocate, cron, sheet, regex, segtree, queue, bizhours, rwlock,
primes, script, kvlog, miniserver, ranked, fsm, pricing, vclock, minisql). None is a hash/cipher task. Existing skills have no
hash, MAC, KDF or block-cipher code (deflate-codec, merkle-sync and torrent-core only call `MessageDigest`).

## Design (no `MessageDigest`, `Mac`, `Cipher` or `SecretKeyFactory` in `src/main`; they are test oracles only)
| Class | API |
|---|---|
| `Sha256` | streaming: `update(byte[])`, `update(byte[],off,len)`, `digest()` (finalises, then resets), `copy()`, static `hash(byte[])`. Handles any length incl. the 55/56/63/64-byte padding edges. |
| `Hmac` | static `sha256(key, msg)`; keys longer than 64 bytes are hashed first. |
| `Kdf` | `hkdfExtract(salt, ikm)`, `hkdfExpand(prk, info, length)` (length 0..255*32 else `IllegalArgumentException`), `pbkdf2Sha256(password, salt, iterations, dkLen)` (iterations >= 1, dkLen >= 1). |
| `Aes` | `Aes(key)` for 16/24/32-byte keys (else `IllegalArgumentException`); `encryptBlock/decryptBlock(in, inOff, out, outOff)`; S-box is computed (GF(2^8) inverse + affine map), not typed in. |
| `AesModes` | `cbcEncrypt(key, iv16, pt)` PKCS#7; `cbcDecrypt` throws `CryptoException("bad padding")` with ONE message for every padding/length failure; `ctr(key, iv16, data)` with a full 128-bit big-endian counter; `gcmEncrypt(key, iv12, aad, pt)` -> ciphertext || 16-byte tag; `gcmDecrypt` verifies the tag in constant time BEFORE any decryption and throws `CryptoException("bad tag")`. |
| `ConstantTime` | `equals(a, b)`: no early exit on content. |
| `CryptoException` | unchecked. |

Inputs are never mutated; outputs are fresh arrays. Null arguments throw `NullPointerException`.

## Success criteria
* Published vectors (FIPS 180-4, RFC 4231, RFC 5869, FIPS 197, NIST GCM test cases, PBKDF2) pass as literals.
* Randomised differential tests against the JDK (`MessageDigest`, `Mac`, `Cipher`, `SecretKeyFactory`) over many lengths/keys.
* Streaming SHA-256 split at every offset equals one-shot; `copy()` is independent.
* Tamper tests: flip every bit of a GCM ciphertext/tag/AAD -> rejected; CBC padding table with controls.
* `mvn -q -B clean test` passes.
