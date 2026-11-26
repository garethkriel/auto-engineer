# binary-to-text-codecs

- **Language:** Java 21 (package `ae.b2t`)
- **Tags:** base64, base32, base58, base58check, bech32, bech32m, segwit, strict-decoding, rfc4648
- **Source round:** practice/java/20261126T0756Z

Strict binary-to-text codecs: RFC 4648 Base64/Base32 (std + url/hex alphabets, mandatory-or-forbidden padding, non-zero
trailing bits rejected, one shared `BaseN` bit-group core), Bitcoin Base58 and Base58Check, BIP173/BIP350 Bech32/Bech32m
with `convertBits`, and Segwit address encode/decode with version/variant/length rules. Tests use published vectors, JDK
and BigInteger differential oracles, exhaustive single-character substitution checks and a mutation pass.

Notes: valid data-char remainders are {0,2,3} mod 4 (Base64) and {0,2,4,5,7} mod 8 (Base32); `convertBits(.., pad=false)` must reject
leftover bits >= `from` even when they are zero.
