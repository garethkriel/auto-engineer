# range-coder-compressor

**Description:** LZMA-style carry-propagating range coder (frequency coding and adaptive binary coding in one stream), a Fenwick-tree adaptive frequency model, and a byte compressor with five context-model engines (order 0/1/2 symbol models, bit-tree order 0/1) in a CRC-checked container with hostile-input guards (ratio bound, over-read detection).

**Language:** Java 21, standard library only.

**Tags:** range-coder, arithmetic-coding, entropy, fenwick-tree, context-model, compression, carry-propagation, corruption-testing

**Source round:** practice/java/20261124T0856Z

**Use it for:** any entropy-coding work; encoder/decoder pairs that must stay in lock-step (shared model update order); tests that bound coded size by an independently computed empirical entropy; exhaustive flip-every-byte / truncate-every-prefix container checks.

**Gotchas recorded:** `BitModel` (11-bit, shift 5) saturates at p = 1 - 31/2048, so 1 MB of equal bytes still costs ~22 KB and iid data costs ~9% over entropy; the count-based `FenwickModel` is within 3%. Decoder `code` is relative to `low` so carries never reach it; the first coder byte is always 0 and is verified.
