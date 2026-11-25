# Task: HashLife — Game of Life with memoised quadtrees (level 20 capstone)

Package `ae.life`, standard library only.

## Overlap check
`benchmark/java/` directories: j01-roman j02-wordfreq j03-lru j04-csv j05-intervals j06-lcs j07-expr j08-graph j09-bank j10-counter j11-streams j12-fileindex j13-ratelimit j14-json j15-topo j16-ranges j17-cache j18-diff j19-allocate j20-cron j21-sheet j22-regex j23-segtree j24-queue j25-bizhours j26-rwlock j27-primes j28-script j29-kvlog j30-miniserver j31-ranked j32-fsm j33-pricing j34-vclock j35-minisql.
None is a cellular automaton or hash-consed quadtree (j23-segtree is a range-query tree; unrelated core). No existing skill covers it either.

## Design
- Coordinates: `x` grows east, `y` grows SOUTH (RLE row order), origin at the root centre. A level-k node covers 2^k x 2^k cells, `[-2^(k-1), 2^(k-1))` relative to its own centre.
- `Node` (package-private, immutable): level, four children nw/ne/sw/se, population (`Math.addExact`), lazily filled `memo[j]` = centre half after 2^j generations (`0 <= j <= level-2`). Level 0 has two shared leaves DEAD/ALIVE.
- `Universe` hash-conses nodes (`Key(nw,ne,sw,se)` by identity), `advance(node, j)` is Gosper's algorithm: level 2 brute force; for `j == level-2` two full-speed phases, otherwise a centring phase then one advance by `j`.
- `step(n)`: for each set bit j of n (ascending): expand the root until level >= max(3, j+2) and live cells sit in the centre quarter, then `root = advance(root, j)`; `generation += 2^j`.
- `compact()` rebuilds a fresh table holding only nodes reachable from the root (drops memos).
- `checkInvariants()`: levels, populations, canonicality, memo levels.
- `NaiveLife` is the oracle (sparse set, B3/S23). `Rle` parses/writes the standard run-length format (rule B3/S23 only).

## API
- `record Cell(long x, long y)` ordered by (y, x).
- `Rle.parse(String) -> Rle.Pattern(List<Cell> cells, int width, int height)` (cells relative to top-left, sorted); `Rle.write(Collection<Cell>) -> String`. Strict: bad characters, missing `!`, unsupported rule, cells beyond the header size, run of 0 -> `IllegalArgumentException`.
- `NaiveLife(Collection<Cell>)`, `step(long n)`, `cells()`, `generation()`, `population()`.
- `Universe`: `set/get/step/population/cells/generation/nodeCount/level/compact/checkInvariants`; `step(n<0)` -> IAE.

## Success criteria
1. Published facts: blinker period 2, glider moves (1,1) per 4 gens, R-pentomino has 116 cells at generation 1103, Gosper gun population, glider after 2^40 generations at exact offset, within a time bound.
2. Differential: random soups, random step splits, vs `NaiveLife`, after every step; `step(a)+step(b)` equals `step(a+b)`.
3. Invariant checker has one corruption test per rule; `compact()` preserves cells and shrinks the table.
4. `mvn -q -B test` passes.
