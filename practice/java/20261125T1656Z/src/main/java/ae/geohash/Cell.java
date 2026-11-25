package ae.geohash;

/** The rectangle covered by one geohash. */
public record Cell(double latMin, double latMax, double lonMin, double lonMax) {
    public double centerLat() {
        return (latMin + latMax) / 2;
    }

    public double centerLon() {
        return (lonMin + lonMax) / 2;
    }

    /** Half-open containment, except the maximum world edges are inclusive. */
    public boolean contains(double lat, double lon) {
        boolean latOk = lat >= latMin && (lat < latMax || (latMax == 90.0 && lat == 90.0));
        boolean lonOk = lon >= lonMin && (lon < lonMax || (lonMax == 180.0 && lon == 180.0));
        return latOk && lonOk;
    }
}
