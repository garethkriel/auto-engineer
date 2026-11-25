# Geohash toolkit (level 20 capstone: geospatial encoding)

Package `ae.geohash`. Overlap check: benchmark/java names (j01-roman .. j35-minisql) contain no geospatial,
geohash or bounding-box task (nearest are j19-allocate, j16-ranges which are unrelated). Existing skill
`spatial-quadtree` is a point index, not a text encoding.

## API
- `GeoHash.encode(double lat, double lon, int precision)` -> base32 string, precision 1..12; lat in [-90,90], lon in [-180,180], else IAE (NaN too).
- `GeoHash.decode(String)` -> `Cell` record (latMin, latMax, lonMin, lonMax; `centerLat()`, `centerLon()`, `contains`). Invalid chars/empty/too long -> IAE.
- `GeoHash.neighbor(String, Direction)` -> `Optional<String>`; longitude wraps at +-180, empty past the poles.
- `GeoHash.neighbors(String)` -> list of existing neighbours (up to 8) in order N,NE,E,SE,S,SW,W,NW.
- `GeoHash.cover(double south, double west, double north, double east, int precision, int maxCells)` -> sorted list of every cell
  intersecting the box (west > east means the box crosses the antimeridian); IAE if more than maxCells cells needed.
- `GeoHash.distanceMeters(lat1, lon1, lat2, lon2)` haversine, R = 6371008.8 m.

## Success criteria
`mvn -q -B test` passes; literal known hashes (ezs42, u4pruydqqvj), prefix/containment properties, neighbour adjacency,
randomized cover-vs-brute-force oracle.
## Assumptions
Cell edges are half-open [min,max) except lat=90 / lon=180 belong to the last cell.
