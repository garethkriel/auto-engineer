# Task: ae.png — strict PNG codec (level 20, capstone)

Overlap check: `benchmark/java/` names (j01-roman … j35-minisql) contain nothing about image codecs; no overlap.
Existing skill `deflate-codec` implements DEFLATE itself; this task uses `java.util.zip` for compression and focuses on the container format and scanline filters.

## Design
- `Png` (record): `width`, `height`, `colorType` (0 gray, 2 RGB, 3 palette, 4 gray+alpha, 6 RGBA), `bitDepth`,
  `samples` (int[], row-major, channels interleaved, one int per sample, length `height*width*channels`), `palette` (int[] 0xRRGGBB, only type 3).
  Allowed depths: type 0 -> 1,2,4,8; type 3 -> 1,2,4,8; types 2,4,6 -> 8. Validation in the compact constructor (copy arrays).
- `PngFilter`: the five scanline filters (None, Sub, Up, Average, Paeth) as `filter(type, row, prior, bpp)` / `unfilter(...)` on byte[] rows.
- `PngEncoder.encode(Png, FilterMode)`: `FilterMode` is `FIXED_0..FIXED_4` or `ADAPTIVE` (min sum of absolute signed byte values per row, ties -> lowest filter type).
  Output: signature, IHDR, PLTE (type 3), one IDAT (Deflater level 9), IEND. No interlace.
- `PngDecoder.decode(byte[])`: strict. Rejects (IllegalArgumentException, message names the defect): bad signature, truncated chunk, bad CRC,
  IHDR not first / wrong length / illegal colour-depth pair / nonzero compression or filter method / interlace (message contains "interlace"),
  missing/duplicate PLTE, PLTE bad length or too many entries, PLTE after IDAT, PLTE for types 0/4, palette index out of range,
  non-contiguous IDAT, missing IDAT, bad filter type byte, inflated size != expected (short or long), data after IEND, missing IEND,
  zero or > 16384 width/height (and width*height cap 4M pixels before allocation). Unknown ancillary chunks (lowercase first letter) are skipped; unknown critical chunks are rejected.
- Coordinate convention: sample index = `(y*width + x)*channels + c`, y=0 is the top row.

## Success criteria
- Literal tests for each filter (hand-computed), Paeth tie order (a, b, c).
- Round trip for every colour type/depth and every FilterMode.
- Differential: my encoder -> `javax.imageio` decodes identical pixels; ImageIO-written PNGs -> my decoder.
- Corruption: one crafted CRC-resealed image per structural rule plus a control that differs by that defect only; flip-every-byte loop = "exception or identical image".
- `mvn -q -B clean test` passes; mutation check of filter rules.
