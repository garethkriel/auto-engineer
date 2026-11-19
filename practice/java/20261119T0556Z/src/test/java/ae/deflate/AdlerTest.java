package ae.deflate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.zip.Adler32;
import org.junit.jupiter.api.Test;

class AdlerTest {
    @Test
    void wikipediaVector() {
        assertEquals(0x11E60398L, ae.deflate.Adler32.of(Samples.ascii("Wikipedia")));
    }

    @Test
    void emptyIsOne() {
        assertEquals(1L, ae.deflate.Adler32.of(new byte[0]));
    }

    @Test
    void matchesJdkOnLargeInputsThatCrossTheModulusBoundary() {
        // 0xFF bytes maximise the running sums, so an unreduced sum would overflow int/mis-reduce.
        byte[] ff = new byte[300_000];
        java.util.Arrays.fill(ff, (byte) 0xFF);
        for (byte[] data : new byte[][] {ff, Samples.random(200_001, 9), Samples.text(12_345, 3)}) {
            Adler32 jdk = new Adler32();
            jdk.update(data);
            assertEquals(jdk.getValue(), ae.deflate.Adler32.of(data));
        }
    }
}
