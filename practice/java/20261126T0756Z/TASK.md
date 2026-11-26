# Task: strict binary-to-text codecs (package `ae.b2t`)

Level 20 capstone-style practice. Overlap check: read `benchmark/java/` names j01-roman … j35-minisql; none is a
binary-to-text codec (j14-json, j04-csv are text parsers; j01-roman is numerals). No earlier skill has Base58/Bech32.

## API
- `Base64.encode(byte[] data, boolean url, boolean pad)` / `Base64.decode(String s, boolean url, boolean requirePad)`
  RFC 4648 §4/§5. Strict: wrong alphabet, whitespace, misplaced/odd padding, non-zero trailing bits, length%4==1 -> `IllegalArgumentException`.
  When `requirePad` is false, padding is rejected (unpadded form only); when true, it is mandatory.
- `Base32.encode(byte[] data, boolean hex, boolean pad)` / `Base32.decode(String s, boolean hex, boolean requirePad)`
  RFC 4648 §6/§7, same strictness (valid unpadded lengths mod 8: 0,2,4,5,7; non-zero trailing bits rejected). Upper-case only.
- `Base58.encode(byte[])` / `Base58.decode(String)`: Bitcoin alphabet; each leading zero byte <-> leading `1`. Invalid char -> IAE.
- `Base58Check.encode(int version, byte[] payload)` (version 0..255): Base58 of version‖payload‖first4(SHA256(SHA256(version‖payload))).
  `Base58Check.decode(String)` -> record `Decoded(int version, byte[] payload)`; bad checksum / too short -> IAE.
- `Bech32.encode(String hrp, byte[] data5, Variant v)` (data5 values 0..31; BIP173 `BECH32` const 1, BIP350 `BECH32M` const 0x2bc830a3),
  `Bech32.decode(String s)` -> record `Decoded(String hrp, byte[] data5, Variant variant)`.
  Rules: total length <= 90, hrp 1..83 chars of ASCII 33..126, a single case (mixed case rejected), output lower-case,
  separator is the LAST `1`, data part >= 6 chars of the charset `qpzry9x8gf2tvdw0s3jn54khce6mua7l`. Checksum must verify for one variant.
- `Bech32.convertBits(byte[] in, int from, int to, boolean pad)` (BIP173 reference semantics, IAE on out-of-range value or non-zero leftover bits when !pad).
- `Segwit.encode(String hrp, int witnessVersion, byte[] program)` / `Segwit.decode(String hrp, String addr)` -> record `Decoded(int version, byte[] program)`.
  version 0 uses Bech32 and program length 20 or 32; versions 1..16 use Bech32M, program 2..40 bytes. Wrong variant for version, wrong hrp -> IAE.

## Success criteria
`mvn -q -B test` passes; tests include RFC 4648 / BIP173 / BIP350 published vectors, JDK differential tests (Base64 vs
`java.util.Base64`, Base58 vs a BigInteger oracle, SHA-256 via MessageDigest), exhaustive strictness tests and round-trip
property tests with seeded random input.
