package ae.png;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class PngRoundTripTest {
    static Png random(Random rnd, int w, int h, int colorType, int depth) {
        int ch = Png.channels(colorType);
        int[] s = new int[w * h * ch];
        int max = (1 << depth) - 1;
        int[] pal = null;
        if (colorType == 3) {
            int n = 1 + rnd.nextInt(1 << depth);
            pal = new int[n];
            for (int i = 0; i < n; i++) pal[i] = rnd.nextInt(1 << 24);
            max = n - 1;
        }
        for (int i = 0; i < s.length; i++) s[i] = rnd.nextInt(max + 1);
        return new Png(w, h, colorType, depth, s, pal);
    }

    static final int[][] FORMATS = {
        {0, 1}, {0, 2}, {0, 4}, {0, 8}, {2, 8}, {3, 1}, {3, 2}, {3, 4}, {3, 8}, {4, 8}, {6, 8}
    };

    @Test
    void roundTripEveryFormatAndFilterMode() {
        Random rnd = new Random(42);
        for (int[] f : FORMATS) {
            for (FilterMode mode : FilterMode.values()) {
                for (int[] dim : new int[][] {{1, 1}, {3, 5}, {9, 2}, {17, 7}}) {
                    Png p = random(rnd, dim[0], dim[1], f[0], f[1]);
                    Png q = PngDecoder.decode(PngEncoder.encode(p, mode));
                    assertEquals(p, q, "type " + f[0] + " depth " + f[1] + " mode " + mode + " " + dim[0] + "x" + dim[1]);
                }
            }
        }
    }

    @Test
    void adaptiveNeverLargerRawScoreThanAnyFixedChoiceOnGradient() {
        // a horizontal gradient: Sub filtering gives constant residuals, so the adaptive pick must be type 1 on every row after row 0
        int w = 32, h = 6;
        int[] s = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) s[y * w + x] = x * 3;
        Png p = new Png(w, h, 0, 8, s, null);
        byte[] raw = PngEncoder.filteredScanlines(p, FilterMode.ADAPTIVE);
        int stride = 1 + w;
        for (int y = 0; y < h; y++) {
            int type = raw[y * stride];
            // row 0: Sub (1) and Paeth (4) coincide; lowest wins ties. Later rows: Up gives all zeros after row 0? no: rows are identical -> Up residuals 0
            if (y == 0) assertEquals(1, type, "row 0");
            else assertEquals(2, type, "row " + y);
        }
    }

    @Test
    void adaptiveScoreTreatsBytesAsSigned() {
        // 1x2 gray, rows [10] then [255]. Row 1 residuals: None 255 (signed 1), Sub 255 (1), Up 245 (signed -11 -> 11),
        // Average 255-5=250 (6), Paeth predictor b=10 -> 245 (11). Signed scoring picks None(0); unsigned would pick Up(2).
        Png p = new Png(1, 2, 0, 8, new int[] {10, 255}, null);
        byte[] raw = PngEncoder.filteredScanlines(p, FilterMode.ADAPTIVE);
        assertEquals(0, raw[2]);
        assertEquals((byte) 255, raw[3]);
    }

    @Test
    void adaptiveTieGoesToLowestFilterType() {
        Png p = new Png(2, 1, 0, 8, new int[] {0, 0}, null); // all-zero row: every filter scores 0
        assertEquals(0, PngEncoder.filteredScanlines(p, FilterMode.ADAPTIVE)[0]);
    }

    @Test
    void fixedModesWriteThatFilterTypeOnEveryRow() {
        Png p = random(new Random(1), 5, 4, 2, 8);
        for (int t = 0; t <= 4; t++) {
            byte[] raw = PngEncoder.filteredScanlines(p, FilterMode.values()[t]);
            int stride = 1 + 15;
            for (int y = 0; y < 4; y++) assertEquals(t, raw[y * stride]);
        }
    }

    @Test
    void sampleLayoutIsRowMajorTopRowFirst() {
        // 2x2 gray 8-bit: top row 1,2 ; bottom row 3,4
        Png p = new Png(2, 2, 0, 8, new int[] {1, 2, 3, 4}, null);
        byte[] raw = PngEncoder.filteredScanlines(p, FilterMode.FIXED_0);
        assertArrayEquals(new byte[] {0, 1, 2, 0, 3, 4}, raw);
    }

    @Test
    void packsSubByteSamplesMsbFirstWithRowPadding() {
        // 3 wide, 1-bit gray: 1,0,1 -> 1010_0000 = 0xA0 ; row 2: 0,1,1 -> 0110_0000 = 0x60
        Png p = new Png(3, 2, 0, 1, new int[] {1, 0, 1, 0, 1, 1}, null);
        assertArrayEquals(new byte[] {0, (byte) 0xA0, 0, 0x60}, PngEncoder.filteredScanlines(p, FilterMode.FIXED_0));
        // 3 wide, 4-bit palette: 15,1,2 -> 0xF1 0x20
        Png q = new Png(3, 1, 3, 4, new int[] {15, 1, 2}, new int[16]);
        assertArrayEquals(new byte[] {0, (byte) 0xF1, 0x20}, PngEncoder.filteredScanlines(q, FilterMode.FIXED_0));
    }

    @Test
    void encodedBytesStartWithSignatureAndHaveChunkOrder() {
        Png p = random(new Random(3), 4, 4, 3, 2);
        byte[] b = PngEncoder.encode(p, FilterMode.ADAPTIVE);
        assertArrayEquals(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}, java.util.Arrays.copyOf(b, 8));
        assertEquals("IHDR", new String(b, 12, 4, java.nio.charset.StandardCharsets.US_ASCII));
        // 8 signature + (4 len + 4 type + 13 data + 4 crc) = 33; next chunk: 4 length bytes then the type at offset 37
        assertEquals("PLTE", new String(b, 37, 4, java.nio.charset.StandardCharsets.US_ASCII));
        String tail = new String(b, b.length - 8, 4, java.nio.charset.StandardCharsets.US_ASCII);
        assertEquals("IEND", tail);
    }

    // ---- differential tests against javax.imageio ----

    private static BufferedImage imageIoRead(byte[] png) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(img, "ImageIO could not decode our PNG");
        return img;
    }

    @Test
    void imageIoDecodesOurRgbaToIdenticalPixels() throws IOException {
        Png p = random(new Random(9), 11, 6, 6, 8);
        BufferedImage img = imageIoRead(PngEncoder.encode(p, FilterMode.ADAPTIVE));
        for (int y = 0; y < 6; y++)
            for (int x = 0; x < 11; x++) {
                int i = (y * 11 + x) * 4;
                int argb = (p.samples()[i + 3] << 24) | (p.samples()[i] << 16) | (p.samples()[i + 1] << 8) | p.samples()[i + 2];
                assertEquals(argb, img.getRGB(x, y), "pixel " + x + "," + y);
            }
    }

    @Test
    void imageIoDecodesOurRgbEveryFilterMode() throws IOException {
        Png p = random(new Random(10), 13, 9, 2, 8);
        for (FilterMode m : FilterMode.values()) {
            BufferedImage img = imageIoRead(PngEncoder.encode(p, m));
            for (int y = 0; y < 9; y++)
                for (int x = 0; x < 13; x++) {
                    int i = (y * 13 + x) * 3;
                    int rgb = 0xFF000000 | (p.samples()[i] << 16) | (p.samples()[i + 1] << 8) | p.samples()[i + 2];
                    assertEquals(rgb, img.getRGB(x, y), m + " pixel " + x + "," + y);
                }
        }
    }

    @Test
    void imageIoDecodesOurPalettePng() throws IOException {
        Random rnd = new Random(11);
        for (int depth : new int[] {1, 2, 4, 8}) {
            Png p = random(rnd, 10, 5, 3, depth);
            BufferedImage img = imageIoRead(PngEncoder.encode(p, FilterMode.FIXED_4));
            for (int y = 0; y < 5; y++)
                for (int x = 0; x < 10; x++)
                    assertEquals(0xFF000000 | p.palette()[p.samples()[y * 10 + x]], img.getRGB(x, y), "depth " + depth);
        }
    }

    @Test
    void ourDecoderReadsImageIoWrittenRgbAndRgba() throws IOException {
        Random rnd = new Random(12);
        for (int type : new int[] {BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_INT_ARGB}) {
            BufferedImage img = new BufferedImage(14, 8, type);
            int[][] expect = new int[8][14];
            for (int y = 0; y < 8; y++)
                for (int x = 0; x < 14; x++) {
                    int argb = rnd.nextInt();
                    if (type == BufferedImage.TYPE_INT_RGB) argb |= 0xFF000000;
                    img.setRGB(x, y, argb);
                    expect[y][x] = img.getRGB(x, y);
                }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertTrue(ImageIO.write(img, "png", out));
            Png p = PngDecoder.decode(out.toByteArray());
            assertEquals(type == BufferedImage.TYPE_INT_RGB ? 2 : 6, p.colorType());
            int ch = Png.channels(p.colorType());
            for (int y = 0; y < 8; y++)
                for (int x = 0; x < 14; x++) {
                    int i = (y * 14 + x) * ch;
                    int a = ch == 4 ? p.samples()[i + 3] : 255;
                    int argb = (a << 24) | (p.samples()[i] << 16) | (p.samples()[i + 1] << 8) | p.samples()[i + 2];
                    assertEquals(expect[y][x], argb, "pixel " + x + "," + y);
                }
        }
    }

    @Test
    void ourDecoderReadsImageIoWrittenGrayAndBinary() throws IOException {
        Random rnd = new Random(13);
        BufferedImage gray = new BufferedImage(9, 7, BufferedImage.TYPE_BYTE_GRAY);
        int[] expect = new int[63];
        for (int y = 0; y < 7; y++)
            for (int x = 0; x < 9; x++) {
                int v = rnd.nextInt(256);
                gray.getRaster().setSample(x, y, 0, v);
                expect[y * 9 + x] = v;
            }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(gray, "png", out);
        Png p = PngDecoder.decode(out.toByteArray());
        assertEquals(0, p.colorType());
        assertArrayEquals(expect, p.samples());

        BufferedImage bin = new BufferedImage(9, 7, BufferedImage.TYPE_BYTE_BINARY);
        int[] bits = new int[63];
        for (int y = 0; y < 7; y++)
            for (int x = 0; x < 9; x++) {
                int v = rnd.nextInt(2);
                bin.getRaster().setSample(x, y, 0, v);
                bits[y * 9 + x] = v;
            }
        out.reset();
        ImageIO.write(bin, "png", out);
        Png q = PngDecoder.decode(out.toByteArray());
        assertEquals(1, q.bitDepth());
        assertArrayEquals(bits, q.samples()); // 1-bit samples (gray level or palette index, depending on what ImageIO chose)
        for (int y = 0; y < 7; y++)
            for (int x = 0; x < 9; x++) {
                int v = q.samples()[y * 9 + x];
                int rgb = q.colorType() == 3 ? q.palette()[v] : v * 0xFFFFFF; // gray 1-bit: 0 black, 1 white
                assertEquals(bin.getRGB(x, y) & 0xFFFFFF, rgb, "pixel " + x + "," + y);
            }
    }
}
