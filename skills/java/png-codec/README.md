# png-codec

- **Language:** Java 21, standard library only (`java.util.zip` for deflate/CRC).
- **Tags:** png, image, codec, scanline-filters, paeth, crc32, strict-decoder, imageio-differential
- **Source round:** `practice/java/20261125T2256Z`

Strict PNG codec in package `ae.png`: `Png` (validated record; samples one int each, row-major, top row first, depths 1/2/4/8 for gray and palette, 8 for others), `PngFilter` (None/Sub/Up/Average/Paeth with exact Paeth tie order a,b,c), `PngEncoder.encode(png, FilterMode)` (fixed or adaptive min-signed-abs filter choice, ties to lowest type), `PngDecoder.decode(bytes)` (rejects bad CRC, chunk order, PLTE rules, interlace, wrong inflated size, trailing data, unknown critical chunks, oversize dimensions before allocation).

Tests: filter literals, round trips over every colour type/depth/filter mode, ImageIO differential in both directions, CRC-resealed crafted images with controls, flip-every-byte and truncation loops. Mutation-checked.
