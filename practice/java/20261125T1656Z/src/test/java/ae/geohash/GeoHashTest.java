package ae.geohash;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GeoHashTest {
    private static final String ALPHABET = "0123456789bcdefghjkmnpqrstuvwxyz";

    @ParameterizedTest
    @CsvSource({
        "42.6, -5.6, 5, ezs42",
        "57.64911, 10.40744, 11, u4pruydqqvj",
        "0, 0, 1, s",
        "-90, -180, 3, 000",
        "90, 180, 3, zzz",
        "90, -180, 1, b",
        "-90, 180, 1, p"
    })
    void encodesKnownLiterals(double lat, double lon, int p, String expected) {
        assertEquals(expected, GeoHash.encode(lat, lon, p));
    }

    @Test
    void decodeOfEzs42HasKnownCenterAndSize() {
        Cell c = GeoHash.decode("ezs42");
        assertEquals(42.60498046875, c.centerLat(), 1e-12); // 42.6 + half of 0.0439..
        assertEquals(-5.603, c.centerLon(), 0.011);
        assertEquals(0.0439453125, c.lonMax() - c.lonMin(), 1e-12); // 25 bits: 13 lon bits, 360 / 2^13
        assertEquals(0.0439453125, c.latMax() - c.latMin(), 1e-12); // 12 lat bits, 180 / 2^12
    }

    @Test
    void roundTripPointLiesInItsCellAndPrefixesNest() {
        Random r = new Random(7);
        for (int n = 0; n < 2000; n++) {
            double lat = r.nextDouble() * 180 - 90;
            double lon = r.nextDouble() * 360 - 180;
            String full = GeoHash.encode(lat, lon, 12);
            for (int p = 1; p <= 12; p++) {
                String h = GeoHash.encode(lat, lon, p);
                assertEquals(full.substring(0, p), h, "prefix at " + p);
                assertTrue(GeoHash.decode(h).contains(lat, lon), h + " must contain " + lat + "," + lon);
            }
        }
    }

    @Test
    void decodeThenEncodeCenterIsIdentity() {
        Random r = new Random(11);
        for (int n = 0; n < 500; n++) {
            int p = 1 + r.nextInt(12);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < p; i++) {
                sb.append(ALPHABET.charAt(r.nextInt(32)));
            }
            Cell c = GeoHash.decode(sb.toString());
            assertEquals(sb.toString(), GeoHash.encode(c.centerLat(), c.centerLon(), p));
        }
    }

    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> GeoHash.encode(91, 0, 5));
        assertThrows(IllegalArgumentException.class, () -> GeoHash.encode(0, -180.5, 5));
        assertThrows(IllegalArgumentException.class, () -> GeoHash.encode(Double.NaN, 0, 5));
        assertThrows(IllegalArgumentException.class, () -> GeoHash.encode(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> GeoHash.encode(0, 0, 13));
        assertThrows(IllegalArgumentException.class, () -> GeoHash.decode(""));
        assertThrows(IllegalArgumentException.class, () -> GeoHash.decode("ezs4a")); // 'a' not in alphabet
        assertThrows(IllegalArgumentException.class, () -> GeoHash.decode("EZS42"));
        assertThrows(IllegalArgumentException.class, () -> GeoHash.decode("0123456789bcd"));
    }

    @Test
    void neighboursOfEzs42MatchPublishedValues() {
        assertEquals(Optional.of("ezs48"), GeoHash.neighbor("ezs42", Direction.N));
        assertEquals(Optional.of("ezs43"), GeoHash.neighbor("ezs42", Direction.E));
        // '2' = 00010: bits lon,lat,lon,lat,lon -> south clears the lat bit -> '0' (00000), no borrow.
        assertEquals(Optional.of("ezs40"), GeoHash.neighbor("ezs42", Direction.S));
    }

    @Test
    void neighbourCellsAreAdjacentAndOrderIsFixed() {
        Cell c = GeoHash.decode("ezs42");
        List<String> ns = GeoHash.neighbors("ezs42");
        assertEquals(8, ns.size());
        int[][] steps = {{0, 1}, {1, 1}, {1, 0}, {1, -1}, {0, -1}, {-1, -1}, {-1, 0}, {-1, 1}};
        double w = c.lonMax() - c.lonMin();
        double h = c.latMax() - c.latMin();
        for (int i = 0; i < 8; i++) {
            Cell n = GeoHash.decode(ns.get(i));
            assertEquals(c.centerLon() + steps[i][0] * w, n.centerLon(), 1e-9, "dx for " + Direction.values()[i]);
            assertEquals(c.centerLat() + steps[i][1] * h, n.centerLat(), 1e-9, "dy for " + Direction.values()[i]);
        }
    }

    @Test
    void neighbourWrapsAntimeridianAndStopsAtPoles() {
        // 'zzz' is the north-east corner, '000' the south-west corner at precision 3.
        assertEquals(Optional.of(GeoHash.encode(90, -180, 3)), GeoHash.neighbor("zzz", Direction.E));
        assertEquals(Optional.empty(), GeoHash.neighbor("zzz", Direction.N));
        assertEquals(Optional.empty(), GeoHash.neighbor("000", Direction.S));
        assertEquals(Optional.empty(), GeoHash.neighbor("000", Direction.SW));
        // west of the SW corner wraps to the SE corner
        String se = GeoHash.encode(-90, 180, 3);
        assertEquals(Optional.of(se), GeoHash.neighbor("000", Direction.W));
        assertEquals(5, GeoHash.neighbors("000").size());
        assertEquals(5, GeoHash.neighbors("zzz").size());
    }

    @Test
    void neighbourIsInvertible() {
        Random r = new Random(3);
        for (int n = 0; n < 300; n++) {
            String h = GeoHash.encode(r.nextDouble() * 160 - 80, r.nextDouble() * 360 - 180, 2 + r.nextInt(7)); // precision>=2 so |lat|<=80 is never in a pole row
            for (Direction d : Direction.values()) {
                Direction back = Direction.values()[(d.ordinal() + 4) % 8];
                Optional<String> there = GeoHash.neighbor(h, d);
                assertTrue(there.isPresent(), h + " " + d);
                assertEquals(Optional.of(h), GeoHash.neighbor(there.get(), back), h + " " + d);
            }
        }
    }

    private static List<String> bruteCover(double s, double w, double n, double e, int p) {
        List<String> out = new ArrayList<>();
        enumerate("", p, out, s, w, n, e);
        java.util.Collections.sort(out);
        return out;
    }

    private static void enumerate(String prefix, int p, List<String> out, double s, double w, double n, double e) {
        if (prefix.length() == p) {
            Cell c = GeoHash.decode(prefix);
            boolean latHit = c.latMin() <= n && c.latMax() > s;
            boolean lonHit = w <= e
                    ? c.lonMin() <= e && c.lonMax() > w
                    : (c.lonMin() <= e) || (c.lonMax() > w);
            if (latHit && lonHit) {
                out.add(prefix);
            }
            return;
        }
        for (char ch : ALPHABET.toCharArray()) {
            enumerate(prefix + ch, p, out, s, w, n, e);
        }
    }

    @Test
    void coverMatchesBruteForceOnRandomBoxes() {
        Random r = new Random(99);
        for (int n = 0; n < 40; n++) {
            double s = r.nextDouble() * 170 - 90;
            double nn = Math.min(90, s + r.nextDouble() * 40);
            double w = r.nextDouble() * 360 - 180;
            double e = r.nextBoolean() ? Math.min(180, w + r.nextDouble() * 60) : r.nextDouble() * 360 - 180;
            int p = 1 + r.nextInt(2); // precision 1..2: 32..1024 cells to brute force
            List<String> expected = bruteCover(s, w, nn, e, p);
            assertEquals(expected, GeoHash.cover(s, w, nn, e, p, 100000), "box " + s + "," + w + "," + nn + "," + e + " p" + p);
        }
    }

    @Test
    void coverAtPrecision3MatchesBruteForceForFixedBoxes() {
        double[][] boxes = {
            {40, -10, 45, 5}, {-1, 179, 1, -179}, {-90, -180, 90, 180}, {10, 10, 10, 10}, {0, 0, 0.1, 0.1}};
        for (double[] b : boxes) {
            assertEquals(bruteCover(b[0], b[1], b[2], b[3], 3), GeoHash.cover(b[0], b[1], b[2], b[3], 3, 100000),
                    java.util.Arrays.toString(b));
        }
    }

    @Test
    void coverOfSinglePointIsItsCellAndContainsEveryInteriorPoint() {
        assertEquals(List.of("ezs42"), GeoHash.cover(42.6, -5.6, 42.6, -5.6, 5, 10));
        List<String> cells = GeoHash.cover(10, 20, 11, 22, 4, 100000);
        Random r = new Random(5);
        for (int i = 0; i < 500; i++) {
            String h = GeoHash.encode(10 + r.nextDouble(), 20 + 2 * r.nextDouble(), 4);
            assertTrue(cells.contains(h), h);
        }
    }

    @Test
    void coverRespectsMaxCellsAndBadBoxes() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> GeoHash.cover(-90, -180, 90, 180, 3, 1000));
        assertTrue(ex.getMessage().contains("32768"), ex.getMessage());
        assertThrows(IllegalArgumentException.class, () -> GeoHash.cover(5, 0, 4, 1, 3, 100));
        assertThrows(IllegalArgumentException.class, () -> GeoHash.cover(0, 0, 1, 181, 3, 100));
        assertEquals(1024, GeoHash.cover(-90, -180, 90, 180, 2, 1024).size());
    }

    @Test
    void haversineKnownDistances() {
        assertEquals(0.0, GeoHash.distanceMeters(10, 20, 10, 20), 1e-9);
        // one degree of latitude on this sphere: R * pi / 180
        assertEquals(111195.08, GeoHash.distanceMeters(0, 0, 1, 0), 0.01);
        // antipodes: half circumference
        assertEquals(Math.PI * 6371008.8, GeoHash.distanceMeters(0, 0, 0, 180), 1e-6);
        // across the antimeridian is short
        assertEquals(111195.08, GeoHash.distanceMeters(0, 179.5, 0, -179.5), 0.01);
    }
}
