package ae.tar;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class StrictReaderTest {
    private static List<Entry> read(byte[] a) throws TarFormatException {
        return TarReader.read(a);
    }

    private static void rejects(byte[] a, String messagePart) {
        TarFormatException e = assertThrows(TarFormatException.class, () -> TarReader.read(a));
        assertTrue(e.getMessage().toLowerCase().contains(messagePart.toLowerCase()), "wanted '" + messagePart + "' in: " + e.getMessage());
    }

    /** A sealed header for "a" (size 0) after {@code mutate}, followed by the end marker. */
    private static byte[] withHeader(Consumer<byte[]> mutate) {
        byte[] h = T.header("a", '0', 0);
        mutate.accept(h);
        return T.archive(T.seal(h));
    }

    // ---- control: the unmutated crafted archive is valid --------------------------------------------------

    @Test
    void controlCraftedArchiveIsValid() throws Exception {
        List<Entry> es = read(withHeader(h -> { }));
        assertEquals(1, es.size());
        assertEquals("a", es.get(0).name());
        assertEquals(0644, es.get(0).mode());
        assertEquals(1700000000L, es.get(0).mtime());
    }

    // ---- container-level rules ----------------------------------------------------------------------------

    @Test
    void emptyInputHasNoEndMarker() {
        rejects(new byte[0], "end");
    }

    @Test
    void lengthMustBeBlockMultiple() {
        rejects(Arrays.copyOf(T.archive(T.file("a", "x")), 2049), "multiple of 512");
    }

    @Test
    void missingEndMarker() {
        rejects(T.file("a", "x"), "end");                                  // header+data, nothing after
        rejects(T.cat(T.file("a", "x"), T.zeros(512)), "end");              // only ONE zero block
    }

    @Test
    void moreZeroBlocksAfterEndAreFine() throws Exception {
        assertEquals(1, read(T.cat(T.archive(T.file("a", "x")), T.zeros(512 * 18))).size());
    }

    @Test
    void nonZeroBytesAfterEndMarkerAreRejected() {
        byte[] extra = T.file("b", "y");
        rejects(T.cat(T.archive(T.file("a", "x")), extra), "after");
    }

    @Test
    void onlyEndMarkerIsEmptyList() throws Exception {
        assertEquals(List.of(), read(T.end()));
    }

    // ---- header rules, each with the control above differing only by the defect ----------------------------

    @Test
    void badChecksum() {
        byte[] h = T.header("a", '0', 0);
        h[148] = (byte) (h[148] == '0' ? '1' : '0'); // not re-sealed
        rejects(T.archive(h), "checksum");
    }

    @Test
    void checksumFieldMustBeOctal() {
        byte[] h = T.header("a", '0', 0);
        h[150] = '8'; // not re-sealed: '8' is not an octal digit
        rejects(T.archive(h), "checksum");
        byte[] g = T.header("a", '0', 0);
        g[154] = 'x'; // terminator must be NUL or space
        rejects(T.archive(g), "checksum");
    }

    @Test
    void signedChecksumIsNotAccepted() {
        // One byte 0x80: the unsigned sum counts 128, a signed sum counts -128, so a signed checksum differs by 256.
        byte[] h = T.header("a", '0', 0);
        h[50] = (byte) 0x80;
        long signed = 0;
        for (int i = 0; i < 512; i++) {
            signed += (i >= 148 && i < 156) ? ' ' : h[i];
        }
        T.put(h, 148, String.format("%06o", signed));
        h[154] = 0;
        h[155] = ' ';
        rejects(T.archive(h), "checksum");
    }

    @Test
    void badMagicAndVersion() {
        rejects(withHeader(h -> h[257] = 'U'), "magic");
        rejects(withHeader(h -> h[262] = ' '), "magic");      // "ustar " instead of "ustar\0"
    }

    @Test
    void versionMustBeZeroZero() {
        rejects(withHeader(h -> h[264] = '1'), "version");
        rejects(withHeader(h -> h[263] = ' '), "version");
    }

    @Test
    void unknownTypeflags() {
        for (char c : new char[] {'3', '4', '6', '7', 'L', 'K', 'S', 'A', 'X', 'V', 'M', 'N', 'D', 'a'}) {
            byte[] h = T.header("a", c, 0);
            rejects(T.archive(h), "typeflag");
        }
    }

    @Test
    void nulTypeflagIsRegularFile() throws Exception {
        byte[] h = T.header("a", '0', 0);
        h[156] = 0;
        List<Entry> es = read(T.archive(T.seal(h)));
        assertEquals(Entry.Type.FILE, es.get(0).type());
    }

    @Test
    void octalFieldsMustBeStrict() {
        rejects(withHeader(h -> h[100] = '8'), "mode");
        rejects(withHeader(h -> h[100] = ' '), "mode");                 // leading space
        rejects(withHeader(h -> h[102] = 'x'), "mode");
        rejects(withHeader(h -> { for (int i = 100; i < 108; i++) h[i] = 0; }), "mode"); // empty
        rejects(withHeader(h -> { for (int i = 100; i < 107; i++) h[i] = '7'; h[107] = '7'; }), "mode"); // no terminator
        rejects(withHeader(h -> T.putOct(h, 100, 8, 010000)), "exceeds 07777");  // > 07777
        rejects(withHeader(h -> T.putOct(h, 100, 8, 07777777)), "exceeds 07777");
        rejects(withHeader(h -> { for (int i = 124; i < 136; i++) h[i] = '7'; }), "invalid octal field size"); // 12 digits, no terminator
        rejects(withHeader(h -> h[125] = '9'), "size");
        rejects(withHeader(h -> h[140] = '-'), "mtime");
        rejects(withHeader(h -> h[110] = 'z'), "uid");
        rejects(withHeader(h -> h[118] = 'z'), "gid");
    }

    @Test
    void checksumTerminatorMayBeSpaceThenNulToo() throws Exception {
        byte[] h = T.header("a", '0', 0);
        h[154] = ' ';
        h[155] = 0; // the sum is still computed with the field read as eight spaces
        assertEquals(1, read(T.archive(h)).size());
    }

    @Test
    void octalTerminatorMayBeSpaceOrNul() throws Exception {
        byte[] h = T.header("a", '0', 0);
        h[107] = ' ';
        assertEquals(1, read(T.archive(T.seal(h))).size());
    }

    @Test
    void textFieldsNeedNothingAfterTheirNul() {
        rejects(withHeader(h -> h[50] = 'x'), "field name");  // a stray byte after "a\0"
        rejects(withHeader(h -> h[200] = 'x'), "field linkname");
        rejects(withHeader(h -> h[270] = 'x'), "field uname");
        rejects(withHeader(h -> h[300] = 'x'), "field gname");
        rejects(withHeader(h -> h[400] = 'x'), "field prefix");
    }

    @Test
    void invalidUtf8InTextFields() {
        rejects(withHeader(h -> { h[1] = (byte) 0xC3; h[2] = '('; }), "UTF-8");
        rejects(withHeader(h -> { h[265] = (byte) 0xFF; }), "UTF-8");
    }

    @Test
    void deviceFieldsMustBeNulOrZeroOctal() throws Exception {
        assertEquals(1, read(withHeader(h -> T.putOct(h, 329, 8, 0))).size());          // "0000000\0" is fine
        rejects(withHeader(h -> T.putOct(h, 329, 8, 1)), "device");
        rejects(withHeader(h -> T.putOct(h, 337, 8, 1)), "device");
        rejects(withHeader(h -> h[330] = 'x'), "device");
    }

    @Test
    void trailingHeaderPadMustBeNul() {
        for (int off = 500; off < 512; off++) { // the whole 12-byte tail, first and last byte included
            final int o = off;
            rejects(withHeader(h -> h[o] = 1), "pad");
        }
    }

    @Test
    void prefixIsJoinedWithASlash() throws Exception {
        byte[] h = T.header("name", '0', 0);
        T.put(h, 345, "pre/fix");
        assertEquals("pre/fix/name", read(T.archive(T.seal(h))).get(0).name());
    }

    @Test
    void nameRules() {
        for (String bad : new String[] {"../x", "a/../b", "/abs", "a//b", "./a"}) {
            byte[] h = T.header(bad, '0', 0);
            rejects(T.archive(h), "name");
        }
        rejects(T.archive(T.header("", '0', 0)), "name");
        byte[] h = T.header("a", '0', 0);
        T.put(h, 345, "/abs"); // absolute prefix
        rejects(T.archive(T.seal(h)), "name");
        byte[] h2 = T.header("a", '0', 0);
        T.put(h2, 345, ".."); // prefix ".." -> "../a"
        rejects(T.archive(T.seal(h2)), "name");
    }

    // ---- entry-kind rules -----------------------------------------------------------------------------------

    @Test
    void directoryRules() throws Exception {
        assertEquals("d", read(T.archive(T.header("d/", '5', 0))).get(0).name());       // one slash stripped
        assertEquals("d", read(T.archive(T.header("d", '5', 0))).get(0).name());        // slash optional on read
        rejects(T.archive(T.header("d//", '5', 0)), "name");                            // two slashes: one stays
        rejects(T.archive(T.header("f/", '0', 0)), "name");                             // file with trailing slash
        rejects(T.archive(T.cat(T.header("d/", '5', 3), T.zeros(512))), "size");        // directory with data
    }

    @Test
    void linkRules() throws Exception {
        byte[] ok = T.header("l", '2', 0);
        T.put(ok, 157, "target");
        assertEquals("target", read(T.archive(T.seal(ok))).get(0).linkName());
        rejects(T.archive(T.header("l", '2', 0)), "linkname");                          // empty link name
        byte[] sized = T.header("l", '2', 1);
        T.put(sized, 157, "target");
        rejects(T.archive(T.cat(T.seal(sized), T.zeros(512))), "size");                 // symlink with data
        byte[] hard = T.header("h", '1', 0);
        T.put(hard, 157, "../x");
        rejects(T.archive(T.seal(hard)), "linkname");                                   // hardlink target is a name
        byte[] file = T.header("f", '0', 0);
        T.put(file, 157, "oops");
        rejects(T.archive(T.seal(file)), "linkname");                                   // file with a link name
    }

    // ---- sizes and padding -----------------------------------------------------------------------------------

    @Test
    void dataPaddingMustBeZero() throws Exception {
        byte[] ok = T.archive(T.file("a", "hello"));
        assertEquals(1, read(ok).size());
        byte[] bad = ok.clone();
        bad[512 + 5] = 1; // first padding byte after "hello"
        rejects(bad, "padding");
        byte[] bad2 = ok.clone();
        bad2[1023] = 1; // last padding byte
        rejects(bad2, "padding");
    }

    @Test
    void sizeBeyondRemainingBytesIsRejectedBeforeAllocating() {
        byte[] h = T.header("a", '0', 0);
        T.putOct(h, 124, 12, 077777777777L); // 8 GiB - 1 on a 1.5 KiB archive
        rejects(T.archive(T.seal(h)), "truncated");
        byte[] h2 = T.header("a", '0', 513);
        rejects(T.cat(h2, T.zeros(512)), "truncated"); // needs 1024 data bytes but only 512 follow
    }

    @Test
    void dataMayNotEatTheEndMarker() {
        // 1 data block + exactly one zero block left: the end marker is incomplete
        byte[] h = T.header("a", '0', 512);
        rejects(T.cat(h, T.zeros(512), T.zeros(512)), "end");
    }

    @Test
    void exactFitDataThenEndMarkerIsValid() throws Exception {
        byte[] data = new byte[512];
        data[0] = 7;
        byte[] a = T.archive(T.header("a", '0', 512), data);
        assertEquals(1, read(a).size());
        assertEquals(7, read(a).get(0).data()[0]);
    }

    // ---- pax rules -----------------------------------------------------------------------------------------------

    @Test
    void paxOverridesApplyToTheNextEntryOnly() throws Exception {
        byte[] a = T.archive(
            T.pax("path", "long/é-name", "uid", "99999999", "gid", "7", "uname", "ünï", "gname", "gg", "mtime", "123"),
            T.file("short", "x"),
            T.file("plain", "y"));
        List<Entry> es = read(a);
        assertEquals(2, es.size());
        assertEquals(new Entry("long/é-name", Entry.Type.FILE, 0644, 99999999L, 7, 123, "", "ünï", "gg", new byte[] {'x'}), es.get(0));
        assertEquals("plain", es.get(1).name());
        assertEquals(0, es.get(1).uid());
        assertEquals(1700000000L, es.get(1).mtime());
    }

    @Test
    void paxLinkpathAndSizeOverride() throws Exception {
        byte[] link = T.header("l", '2', 0);
        List<Entry> es = read(T.archive(T.pax("linkpath", "far/away"), link));
        assertEquals("far/away", es.get(0).linkName());
        // size override: header says 0, pax says 3 -> three data bytes follow
        byte[] withData = T.archive(T.pax("size", "3"), T.header("f", '0', 0), T.padded("abc".getBytes(StandardCharsets.UTF_8)));
        assertEquals("abc", new String(read(withData).get(0).data(), StandardCharsets.UTF_8));
    }

    @Test
    void paxMtimeFractionIsFlooredTowardNegativeInfinity() throws Exception {
        String[][] cases = {{"1700000000.9", "1700000000"}, {"1700000000", "1700000000"}, {"-1.5", "-2"}, {"-0.5", "-1"},
            {"-1", "-1"}, {"0.0", "0"}, {"-0.0", "0"}};
        for (String[] c : cases) {
            long got = read(T.archive(T.pax("mtime", c[0]), T.file("a", ""))).get(0).mtime();
            assertEquals(Long.parseLong(c[1]), got, c[0]);
        }
    }

    @Test
    void paxNumbersAreValidated() {
        for (String bad : new String[] {"1e5", "0x10", " 5", "5 ", "+5", "", ".5", "5.", "99999999999999999999"}) {
            rejects(T.archive(T.pax("mtime", bad), T.file("a", "")), "mtime");
        }
        for (String bad : new String[] {"-1", "1.5", "abc", "", "99999999999999999999"}) {
            rejects(T.archive(T.pax("uid", bad), T.file("a", "")), "uid");
            rejects(T.archive(T.pax("gid", bad), T.file("a", "")), "gid");
        }
        for (String bad : new String[] {"-1", "1.5", "abc", ""}) {
            rejects(T.archive(T.pax("size", bad), T.file("a", "")), "size");
        }
    }

    @Test
    void paxSizeBeyondArchiveIsRejectedWithoutAllocation() {
        rejects(T.archive(T.pax("size", "9223372036854775807"), T.header("f", '0', 0)), "truncated");
    }

    @Test
    void overlongPaxMtimeTextIsRejectedEvenWhenItsValueIsSmall() {
        String ok = "0".repeat(39) + "1"; // 40 characters
        String tooLong = "0".repeat(40) + "1"; // 41 characters
        assertDoesNotThrow(() -> read(T.archive(T.pax("mtime", ok), T.file("a", ""))));
        rejects(T.archive(T.pax("mtime", tooLong), T.file("a", "")), "mtime");
    }

    @Test
    void paxSizeMakesDirectoriesInvalidToo() {
        rejects(T.archive(T.pax("size", "1"), T.header("d/", '5', 0), T.zeros(512)), "size");
    }

    @Test
    void unknownPaxKeysAreIgnoredAndLaterDuplicatesWin() throws Exception {
        byte[] a = T.archive(T.pax("comment", "hi", "path", "first", "SCHILY.xattr.user.k", "v", "path", "second"), T.file("x", ""));
        assertEquals("second", read(a).get(0).name());
    }

    @Test
    void emptyOverrideValuesAreNotAccepted() {
        rejects(T.archive(T.pax("path", ""), T.file("x", "")), "name");
    }

    @Test
    void paxPathIsStillSafetyChecked() {
        rejects(T.archive(T.pax("path", "../evil"), T.file("x", "")), "name");
        rejects(T.archive(T.pax("path", "/abs"), T.file("x", "")), "name");
        rejects(T.archive(T.pax("linkpath", ""), T.header("l", '2', 0)), "linkname");
    }

    @Test
    void paxHeaderSequencingRules() throws Exception {
        rejects(T.archive(T.pax("path", "a")), "pax");                                           // x then end marker
        rejects(T.cat(T.pax("path", "a")), "end");                                               // x then EOF
        rejects(T.archive(T.pax("path", "a"), T.pax("uid", "1"), T.file("x", "")), "pax");       // x x
        assertEquals("a", read(T.archive(T.pax("path", "a"), T.file("x", ""))).get(0).name());   // control
    }

    @Test
    void globalHeadersAreAcceptedAndIgnored() throws Exception {
        byte[] glob = T.paxOf('g', Pax.record("comment", "abc") + Pax.record("path", "ignored"));
        List<Entry> es = read(T.archive(glob, T.file("a", "x")));
        assertEquals(List.of("a"), T.names(es));
        // a global header between an x header and its entry does not consume the override
        List<Entry> es2 = read(T.archive(T.pax("path", "named"), glob, T.file("a", "x")));
        assertEquals(List.of("named"), T.names(es2));
        // but a malformed global header is still rejected
        rejects(T.archive(T.paxOf('g', "5 a=b\n"), T.file("a", "x")), "newline");
    }

    @Test
    void paxHeaderSizeCapIsOneMebibyte() throws Exception {
        // 7 digits + " k=" + value + "\n" = 1048576 exactly -> value length 1048565
        String rec = "1048576 k=" + "v".repeat(1048565) + "\n";
        assertEquals(1 << 20, rec.length());
        assertEquals(1, read(T.archive(T.paxRaw(rec), T.file("a", ""))).size());
        byte[] h = T.header("PaxHeader/x", 'x', (1 << 20) + 1);
        rejects(T.archive(h, new byte[(1 << 20) + 512]), "cap");
    }

    // ---- exhaustive damage loops -------------------------------------------------------------------------------

    /** ASCII-only, ustar-only archive: every header byte is covered by the checksum. */
    private static byte[] sample() {
        return TarWriter.write(List.of(
            new Entry("dir/f", Entry.Type.FILE, 0600, 5, 6, 1700000000L, "", "bob", "grp", "hello world".getBytes(StandardCharsets.UTF_8)),
            Entry.dir("dir", 0755, 3),
            Entry.symlink("dir/l", "target"),
            new Entry("p".repeat(90), Entry.Type.FILE, 0644, 7, 0, 9, "", "", "", new byte[600])));
    }

    private static byte[] samplePax() {
        return TarWriter.write(List.of(
            new Entry("dir/é", Entry.Type.FILE, 0600, 5, 6, 1700000000L, "", "bob", "grp", "hello".getBytes(StandardCharsets.UTF_8)),
            Entry.symlink("dir/l", "é/target"),
            new Entry("p".repeat(120), Entry.Type.FILE, 0644, Long.MAX_VALUE, 0, -9, "", "", "", new byte[600])));
    }

    private static Entry strip(Entry e) {
        return new Entry(e.name(), e.type(), e.mode(), e.uid(), e.gid(), e.mtime(), e.linkName(), e.uname(), e.gname(), new byte[0]);
    }

    @Test
    void everyTruncationIsRejected() throws Exception {
        for (byte[] full : new byte[][] {sample(), samplePax()}) {
            assertFalse(read(full).isEmpty());
            for (int len = 0; len < full.length; len++) {
                final int n = len;
                assertThrows(TarFormatException.class, () -> TarReader.read(Arrays.copyOf(full, n)), "len=" + n);
            }
        }
    }

    @Test
    void flippingAnyByteIsRejectedOrChangesOnlyThatDataByte() throws Exception {
        byte[] full = sample();
        List<Entry> want = read(full);
        int rejected = 0;
        int dataOnly = 0;
        for (int i = 0; i < full.length; i++) {
            byte[] bad = full.clone();
            bad[i] ^= 0x01;
            List<Entry> got;
            try {
                got = TarReader.read(bad);
            } catch (TarFormatException e) {
                rejected++;
                continue;
            }
            assertNotEquals(want, got, "identical result after flipping offset " + i);
            assertEquals(want.size(), got.size(), "offset " + i);
            int diffEntries = 0;
            for (int k = 0; k < want.size(); k++) {
                if (!want.get(k).equals(got.get(k))) {
                    diffEntries++;
                    assertEquals(strip(want.get(k)), strip(got.get(k)), "metadata changed at offset " + i);
                    byte[] a = want.get(k).data();
                    byte[] b = got.get(k).data();
                    int diffBytes = 0;
                    for (int j = 0; j < a.length; j++) {
                        diffBytes += a[j] == b[j] ? 0 : 1;
                    }
                    assertEquals(1, diffBytes, "offset " + i);
                }
            }
            assertEquals(1, diffEntries, "offset " + i);
            dataOnly++;
        }
        assertEquals(11 + 600, dataOnly);                 // the data bytes of the two files; nothing else survives
        assertEquals(full.length - 611, rejected);
    }

    @Test
    void randomDamageOnlyEverThrowsTarFormatException() {
        java.util.Random r = new java.util.Random(77);
        for (byte[] base : new byte[][] {sample(), samplePax()}) {
            for (int i = 0; i < 600; i++) {
                byte[] b = base.clone();
                int flips = 1 + r.nextInt(6);
                for (int k = 0; k < flips; k++) {
                    b[r.nextInt(b.length)] = (byte) r.nextInt(256);
                }
                try {
                    TarReader.read(b);
                } catch (TarFormatException expected) {
                    // the only exception type allowed
                }
            }
        }
    }
}
