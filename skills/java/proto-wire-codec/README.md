# proto-wire-codec

- **Description:** protobuf-style wire format over a dynamic message model: unsigned LEB128 varints and zigzag, `fixed32/64`, length-delimited fields, a proto3-like schema parser (`Schema.parse`), immutable `Msg`, `Codec.encode`/`decode` with packed repeated scalars, last-wins singular fields, byte-exact unknown-field preservation (including wire-type mismatches), strict errors with absolute byte offsets, nesting-depth cap 32.
- **Language:** java (standard library only)
- **Tags:** binary-format, varint, protobuf, schema, codec, fuzzing, strict-decoder
- **Source round:** practice/java/20261125T1056Z (level 20)
- **Tests:** 48, including guide literals, a BigInteger-based independent reference encoder over 3000 random messages, and truncation/bit-flip fuzzing.

Reuse notes: decoder offsets are the start of the offending varint/length; padded varints are accepted, 10th byte > 1 is overflow; empty repeated fields are omitted; a set singular field is always encoded (explicit presence).
