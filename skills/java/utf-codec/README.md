# utf-codec

- **Description:** Unicode text encodings: strict and lossy (WHATWG maximal-subpart) UTF-8 decoding, a chunk-invariant streaming UTF-8 decoder, UTF-16 LE/BE with BOM detection, and RFC 3492 Punycode.
- **Language:** java (package `ae.utf`)
- **Tags:** utf-8, utf-16, punycode, streaming-decoder, state-machine, differential-testing
- **Source round:** practice/java/20261126T0257Z
- **Notes:** JDK's lossy decoder folds `ED A0..BF` into one U+FFFD; this code follows the WHATWG spec (two). Strict errors report the offset of the first byte of the bad sequence.
