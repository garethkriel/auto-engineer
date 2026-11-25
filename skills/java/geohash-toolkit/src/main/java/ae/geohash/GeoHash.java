package ae.geohash;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Geohash encoding, decoding, neighbours, box cover and distance. */
public final class GeoHash {
    private static final String ALPHABET = "0123456789bcdefghjkmnpqrstuvwxyz";
    private static final int MAX_PRECISION = 12;
    private static final double EARTH_RADIUS_M = 6371008.8;

    private GeoHash() {
    }

    private static int lonBits(int precision) {
        return (5 * precision + 1) / 2;
    }

    private static int latBits(int precision) {
        return (5 * precision) / 2;
    }

    private static void checkPrecision(int precision) {
        if (precision < 1 || precision > MAX_PRECISION) {
            throw new IllegalArgumentException("precision must be 1..12: " + precision);
        }
    }

    private static long index(double value, double offset, double span, int bits) {
        long cells = 1L << bits;
        long i = (long) Math.floor((value + offset) * cells / span);
        return Math.min(Math.max(i, 0), cells - 1);
    }

    /** Encode a coordinate. */
    public static String encode(double lat, double lon, int precision) {
        checkPrecision(precision);
        if (!(lat >= -90 && lat <= 90) || !(lon >= -180 && lon <= 180)) {
            throw new IllegalArgumentException("coordinate out of range: " + lat + "," + lon);
        }
        return fromIndices(index(lon, 180, 360, lonBits(precision)),
                index(lat, 90, 180, latBits(precision)), precision);
    }

    private static String fromIndices(long ix, long iy, int precision) {
        int lonB = lonBits(precision);
        int latB = latBits(precision);
        int total = 5 * precision;
        StringBuilder sb = new StringBuilder(precision);
        int lonUsed = 0;
        int latUsed = 0;
        int chunk = 0;
        for (int bit = 0; bit < total; bit++) {
            int b;
            if (bit % 2 == 0) { // even bits are longitude
                b = (int) ((ix >> (lonB - 1 - lonUsed)) & 1);
                lonUsed++;
            } else {
                b = (int) ((iy >> (latB - 1 - latUsed)) & 1);
                latUsed++;
            }
            chunk = (chunk << 1) | b;
            if (bit % 5 == 4) {
                sb.append(ALPHABET.charAt(chunk));
                chunk = 0;
            }
        }
        return sb.toString();
    }

    /** Cell indices {ix, iy} of a hash. */
    private static long[] toIndices(String hash) {
        if (hash == null || hash.isEmpty() || hash.length() > MAX_PRECISION) {
            throw new IllegalArgumentException("bad geohash length");
        }
        long ix = 0;
        long iy = 0;
        int bit = 0;
        for (int c = 0; c < hash.length(); c++) {
            int v = ALPHABET.indexOf(hash.charAt(c));
            if (v < 0) {
                throw new IllegalArgumentException("bad geohash character: " + hash.charAt(c));
            }
            for (int k = 4; k >= 0; k--) {
                int b = (v >> k) & 1;
                if (bit % 2 == 0) {
                    ix = (ix << 1) | b;
                } else {
                    iy = (iy << 1) | b;
                }
                bit++;
            }
        }
        return new long[] {ix, iy};
    }

    private static Cell cellOf(long ix, long iy, int precision) {
        double w = 360.0 / (1L << lonBits(precision));
        double h = 180.0 / (1L << latBits(precision));
        return new Cell(-90 + iy * h, -90 + (iy + 1) * h, -180 + ix * w, -180 + (ix + 1) * w);
    }

    /** Decode a hash to its cell. */
    public static Cell decode(String hash) {
        long[] i = toIndices(hash);
        return cellOf(i[0], i[1], hash.length());
    }

    /** Neighbour in one direction; empty beyond the poles, wraps around the antimeridian. */
    public static Optional<String> neighbor(String hash, Direction d) {
        long[] i = toIndices(hash);
        int p = hash.length();
        long lonCells = 1L << lonBits(p);
        long latCells = 1L << latBits(p);
        long ny = i[1] + d.dy;
        if (ny < 0 || ny >= latCells) {
            return Optional.empty();
        }
        long nx = Math.floorMod(i[0] + d.dx, lonCells);
        return Optional.of(fromIndices(nx, ny, p));
    }

    /** Existing neighbours in the order N, NE, E, SE, S, SW, W, NW (duplicates possible at width 1-2 cells). */
    public static List<String> neighbors(String hash) {
        List<String> out = new ArrayList<>();
        for (Direction d : Direction.values()) {
            neighbor(hash, d).ifPresent(out::add);
        }
        return out;
    }

    /** Sorted list of all cells of the given precision that intersect the box. */
    public static List<String> cover(double south, double west, double north, double east,
                                     int precision, int maxCells) {
        checkPrecision(precision);
        if (!(south >= -90 && north <= 90 && south <= north)
                || !(west >= -180 && west <= 180 && east >= -180 && east <= 180)) {
            throw new IllegalArgumentException("bad box");
        }
        int lonB = lonBits(precision);
        long y0 = index(south, 90, 180, latBits(precision));
        long y1 = index(north, 90, 180, latBits(precision));
        long x0 = index(west, 180, 360, lonB);
        long x1 = index(east, 180, 360, lonB);
        long lonCells = 1L << lonB;
        long width = west <= east ? x1 - x0 + 1 : Math.min(lonCells, (lonCells - x0) + x1 + 1);
        long count = width * (y1 - y0 + 1);
        if (count > maxCells) {
            throw new IllegalArgumentException("cover needs " + count + " cells, max " + maxCells);
        }
        List<String> out = new ArrayList<>((int) count);
        for (long y = y0; y <= y1; y++) {
            for (long k = 0; k < width; k++) {
                out.add(fromIndices((x0 + k) % lonCells, y, precision));
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** Great-circle distance in metres (haversine). */
    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = p2 - p1;
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
