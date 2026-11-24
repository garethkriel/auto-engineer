# Task: ae.netroute — IPv4 addressing toolkit (level 20 capstone)

Overlap check: `benchmark/java/` has no addressing/CIDR/routing/firewall task (nearest: j19-allocate, j34-vclock,
j20-cron; none about prefixes or tries). `skills/java/` has no CIDR/LPM skill (nearest: role-policy-acl, consistent-hash-ring).

## Design (written first)
Package `ae.netroute`, standard library only. Addresses are `long` values 0..2^32-1.

| Type | Responsibility |
|---|---|
| `Ip4` | strict dotted-quad parse/format. Exactly 4 decimal octets, 1-3 digits, no leading zeros ("0" ok), each <= 255; otherwise `IllegalArgumentException` whose message contains the offending text. |
| `Cidr` (record `base`,`len`) | `parse("a.b.c.d/n")`, `of(base,len)`, `containing(addr,len)`; **host bits set is an error** ("host bits set"); `first/last/size/contains(addr)/contains(Cidr)/overlaps`; `toString`; natural order (base, then len). |
| `AddrSet` | immutable set of addresses as sorted, disjoint, non-adjacent inclusive ranges. `of(lo,hi)`, `of(Cidr)`, `union/intersect/subtract/complement`, `contains`, `size`, `isEmpty`, `ranges()`, and `cidrs()` = the **minimal** list of CIDR blocks covering exactly the set (ascending). |
| `RouteTable` | binary trie, `Cidr -> String next hop`. `add` (replaces, returns previous or null), `remove` (prunes empty trie nodes), `lookup(addr)` longest-prefix match -> `Optional<Route>`, `routes()` ordered by (base,len), `size()`, `aggregate()` = lossless aggregation (drop routes whose covering route has the same hop; replace two sibling routes with equal hops by their parent; repeat to fixpoint), `equivalentTo(other)` = same next hop for **every** address (exact: compares at all route boundary points, since lookup is piecewise constant). |
| `Firewall` | text rules, one per line: `allow|deny <src> <dst> <ports>` with `<src>/<dst>` = `any` or CIDR, `<ports>` = `any`, `80` or `1000-2000`; `#` comments and blank lines ignored; syntax errors say `line N: ...`. First match wins, default deny. `evaluate(Packet)` -> `Decision(allow, ruleIndex or -1)`, `shadowed()` = indexes of rules whose whole (src,dst,port) box is covered by the union of earlier rules (exact, by box subtraction), `allowedSources(dst, port)` -> `AddrSet` of sources allowed for that destination and port. |

Assumptions: rule indexes are 0-based positions among *rules* (comments/blank lines do not count); `Cidr.parse` is strict
(no masking); ports are 0..65535; a route table may hold `0.0.0.0/0`.

## Success criteria
- `mvn -q -B test` passes. Tests written before code.
- Literal cases + randomized differential tests against independent oracles: boolean array over a 4096-address window
  (AddrSet), linear scan (LPM), a segment-count recursion (minimal CIDR count), brute-force lookups plus boundary points
  (aggregation), coordinate-compression cell enumeration (shadowing and allowedSources).
- Every rule of the aggregator is exercised by a test that would change behaviour if the rule were wrong (mutation check).
