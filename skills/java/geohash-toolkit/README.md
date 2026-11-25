# geohash-toolkit

- **Description:** Geohash encode/decode via integer cell indices, 8-direction neighbours (antimeridian wrap, empty past poles), sorted box cover with antimeridian-crossing boxes and a cell cap, haversine distance.
- **Language:** java
- **Tags:** geospatial, geohash, bit-interleaving, neighbours, bounding-box-cover, haversine
- **Source round:** practice/java/20261125T1656Z
- **Tests:** literal known hashes, prefix/containment properties, invertible neighbours, cover vs brute-force enumeration oracle.
- **Note:** a wrapped box (west > east) must clamp its width to the number of cells in a row, else narrow wrapped boxes duplicate cells.
