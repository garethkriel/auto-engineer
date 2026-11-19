package ae.rsync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DeltaWireTest {
    // Image of [Literal "abc"], layout:
    //  0..3 magic | 4..7 opCount | 8 tag | 9..12 literal length | 13..15 data
    //  16..23 targetLength | 24..55 sha256 | 56..59 crc
    private static final int COUNT_AT = 4;
    private static final int TAG_AT = 8;
    private static final int LEN_AT = 9;
    private static final int TARGET_LEN_AT = 16;

    private static byte[] abcImage() {
        byte[] abc = TestData.ascii("abc");
        return new Delta(List.of(new Delta.Literal(abc)), 3, Delta.sha256(abc)).toBytes();
    }

    private static Delta sample() {
        byte[] basis = TestData.random(40, 200);
        byte[] target = TestData.concat(TestData.slice(basis, 0, 50), TestData.random(41, 9), TestData.slice(basis, 100, 200));
        return DeltaEncoder.compute(Signature.of(basis, 16), target);
    }

    @Test
    void imageSizeMatchesLayout() {
        assertEquals(60, abcImage().length);
    }

    @Test
    void roundTrip() throws Exception {
        Delta d = sample();
        assertEquals(d, Delta.parse(d.toBytes()));
        Delta empty = new Delta(List.of(), 0, Delta.sha256(new byte[0]));
        assertEquals(empty, Delta.parse(empty.toBytes()));
    }

    @Test
    void everyTruncationIsRejected() {
        byte[] wire = sample().toBytes();
        for (int cut = 0; cut < wire.length; cut++) {
            int n = cut;
            assertThrows(FormatException.class, () -> Delta.parse(TestData.slice(wire, 0, n)), "cut " + n);
        }
    }

    @Test
    void everySingleByteFlipIsRejected() {
        byte[] wire = sample().toBytes();
        for (int i = 0; i < wire.length; i++) {
            byte[] bad = wire.clone();
            bad[i] ^= 0x01;
            int at = i;
            assertThrows(FormatException.class, () -> Delta.parse(bad), "flip at " + at);
        }
    }

    @Test
    void trailingBytesRejected() {
        assertThrows(FormatException.class, () -> Delta.parse(TestData.concat(abcImage(), new byte[1])));
    }

    @Test
    void resealedControlImageParses() throws Exception {
        byte[] img = abcImage();
        WireTestSupport.reseal(img);
        Delta d = Delta.parse(img);
        assertEquals(3, d.targetLength());
    }

    @Test
    void unknownTagRejectedByStructure() {
        byte[] img = abcImage();
        img[TAG_AT] = 7;
        WireTestSupport.reseal(img);
        FormatException e = assertThrows(FormatException.class, () -> Delta.parse(img));
        assertTrue(e.getMessage().contains("tag"), e.getMessage());
    }

    @Test
    void hugeLiteralLengthRejectedWithoutAllocating() {
        byte[] img = abcImage();
        img[LEN_AT] = 0x7F;
        img[LEN_AT + 1] = (byte) 0xFF;
        img[LEN_AT + 2] = (byte) 0xFF;
        img[LEN_AT + 3] = (byte) 0xFF;
        WireTestSupport.reseal(img);
        FormatException e = assertThrows(FormatException.class, () -> Delta.parse(img));
        assertTrue(e.getMessage().contains("length"), e.getMessage());
    }

    @Test
    void opCountLargerThanPayloadRejected() {
        byte[] img = abcImage();
        img[COUNT_AT + 3] = 2;
        WireTestSupport.reseal(img);
        assertThrows(FormatException.class, () -> Delta.parse(img));
    }

    @Test
    void targetLengthMustEqualSumOfOps() {
        byte[] img = abcImage();
        img[TARGET_LEN_AT + 7] = 4;
        WireTestSupport.reseal(img);
        FormatException e = assertThrows(FormatException.class, () -> Delta.parse(img));
        assertTrue(e.getMessage().contains("target length"), e.getMessage());
    }

    @Test
    void constructorRejectsInconsistentDelta() {
        byte[] abc = TestData.ascii("abc");
        assertThrows(IllegalArgumentException.class,
                () -> new Delta(List.of(new Delta.Literal(abc)), 4, Delta.sha256(abc)));
        assertThrows(IllegalArgumentException.class,
                () -> new Delta(List.of(new Delta.Literal(new byte[0])), 0, Delta.sha256(abc)));
        assertThrows(IllegalArgumentException.class,
                () -> new Delta(List.of(), 0, new byte[5]));
    }

    @Test
    void literalDataIsDefensivelyCopied() {
        byte[] data = TestData.ascii("xyz");
        Delta.Literal l = new Delta.Literal(data);
        data[0] = 'Q';
        assertArrayEquals(TestData.ascii("xyz"), l.data());
    }
}
