# Level 20 capstone: `ae.uri` RFC 3986 URI toolkit

Overlap check: `benchmark/java/` names j01-roman .. j35-minisql listed; none is a URI/URL parser, reference resolver or percent codec. No existing skill covers RFC 3986.

## Assumptions
Standard library only, Java 21. Strict RFC 3986 syntax (no IRIs, no lenient browser behaviour). Query form parsing follows `application/x-www-form-urlencoded`.

## Design
- `PercentCodec`: `encode(String, IntPredicate keep)` UTF-8, uppercase hex; `decode(String)` strict (bad `%`, bad UTF-8 -> `IllegalArgumentException`); `decodeForm` additionally maps `+` to space.
- `Uri` (immutable, equals/hashCode by components): fields scheme, userInfo, host, port (-1 absent), path (never null, may be empty), query, fragment (null = absent; empty = present but empty). `Uri.parse(String)` is strict: scheme `ALPHA *(ALPHA/DIGIT/+/-/.)`, authority with optional userinfo, host as reg-name / IPv4 / `[IPv6]` (colon-hex with optional embedded IPv4, validated: at most one `::`, at most 8 groups) / `[v1.xxx]`, port digits (<= 65535), path/query/fragment charsets with valid `%HH` triplets. `toString()` is RFC 3986 §5.3 recomposition and `parse(x).toString().equals(x)` for every valid input.
- `Uri.resolve(Uri ref)` : §5.2.2 strict algorithm (base must have a scheme); `removeDotSegments(String)` §5.2.4.
- `Uri.normalize()`: lowercase scheme and host, uppercase percent hex, decode percent-encoded unreserved characters, remove dot segments, drop default port (http 80, https 443, ftp 21, ws 80, wss 443), empty path -> `/` when authority present and scheme is http/https/ws/wss/ftp. Idempotent.
- `Uri.relativize(Uri target)`: return a reference that resolves back to target against this base (same scheme+authority required, else return the target unchanged).
- `QueryParams`: ordered list of (name, value) pairs; `parse(String)`, `toString()` re-encodes; `get`, `getAll`, `with`/`without` immutable updates.

## Success criteria
- RFC 3986 §5.4.1/§5.4.2 resolution examples as literals.
- Round-trip and rejection tests (each rejection test has a control input differing only by the defect).
- Differential tests against `java.net.URI` (resolve of valid inputs, parse components) and seeded random generator checks: round trip, normalize idempotent, `base.resolve(base.relativize(t)) == t.normalize-free equal`.
- `mvn -q -B clean test` passes; mutation pass over single-line mutants.
