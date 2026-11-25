# Task: DNS toolkit (ae.dns)

Level 20 capstone. Overlap check: `ls benchmark/java` lists j01-roman, j02-wordfreq, j03-lru, j04-csv, j05-intervals,
j06-lcs, j07-expr, j08-graph, j09-bank, j10-counter, j11-streams, j12-fileindex, j13-ratelimit, j14-json, j15-topo,
j16-ranges, j17-cache, j18-diff, j19-allocate, j20-cron, j21-sheet, j22-regex, j23-segtree, j24-queue, j25-bizhours,
j26-rwlock, j27-primes, j28-script, j29-kvlog, j30-miniserver, j31-ranked, j32-fsm, j33-pricing, j34-vclock, j35-minisql.
None is a DNS task: j03-lru/j17-cache are key-value caches (this task has a TTL cache of RRsets as one small part, with
negative caching and bailiwick rules, but the core is a wire codec, zone lookup and an iterative resolver);
j30-miniserver is a request/response server (this one has no sockets). No shared API or core algorithm.
Earlier practice rounds contain no DNS work (`ls practice/java`, `skills/java/INDEX.md` checked).

## Design (package `ae.dns`, standard library only)
- `Name`: immutable absolute domain name, labels stored lower-case (DNS is case-insensitive; case is NOT preserved).
  Label 1..63 printable-ASCII chars, wire length (sum(len+1)+1) <= 255. `Name.parse("www.Example.com.")`, `"."` is root.
- `RType` {A=1, NS=2, CNAME=5, SOA=6, MX=15, TXT=16, AAAA=28}; `Rcode` {NOERROR, FORMERR, SERVFAIL, NXDOMAIN, NOTIMP, REFUSED}.
- `RData` sealed: `A(int)`, `Aaaa(long hi, long lo)`, `Ns`, `Cname`, `Mx`, `Txt(List<String>)`, `Soa`. `Rr(name, ttl, data)`, ttl 0..2^31-1.
- `Message(id, response, aa, tc, rd, ra, rcode, qname, qtype, answers, authority, additional)`: exactly one question, class IN.
- `Wire.encode/decode`: RFC 1035 layout with name compression (pointers to earlier suffixes; names in NS/CNAME/MX/SOA rdata
  compressible). Decoder is strict, throwing only `DecodeException`: pointer must target an offset before the start of the
  name being read, label-type bits 01/10 rejected, name length capped, rdlength must match, no trailing bytes, qdcount == 1,
  class IN, known types, reserved flag bits zero, opcode 0, A rdlength 4 / AAAA 16.
- `Zone` (authoritative data for one origin; one SOA at the apex; CNAME alone at its name; owners inside the origin).
  `lookup(qname, qtype)` -> `Kind`:
  1. qname outside origin -> NOT_IN_ZONE.
  2. Delegation: the shallowest name strictly below the origin, at or above qname, holding NS records -> REFERRAL (cut = that name,
     records = its NS set), even for qname == cut and any type.
  3. Name exists (has records, or is an empty non-terminal because a descendant has records): qtype records -> ANSWER;
     else CNAME present -> CNAME; else NODATA.
  4. Name does not exist: closest encloser = deepest existing proper ancestor; if `*.encloser` has records the
     answer is synthesised with owner = qname (ANSWER / CNAME / NODATA by the same rules); otherwise NXDOMAIN.
  `Zone.parse(origin, text)` reads a master-file subset (`$TTL`, `@`, relative names, `;` comments, quoted TXT).
- `Server(zones)`: longest-origin zone match (none -> REFUSED); AA set except for referrals; follows a CNAME chain while the target
  is inside one of its own zones (loop-safe, max 8 hops); SOA in authority for NODATA/NXDOMAIN; referral = NS in authority + glue
  in additional; additional A/AAAA for NS/MX answers.
- `Resolver(transport, rootServers, clock)`: iterative resolution from the roots with a TTL cache.
  - cache: positive RRsets (min TTL, remaining TTL returned), NXDOMAIN per name and NODATA per (name,type) with TTL
    min(SOA ttl, SOA minimum); TTL 0 is never cached.
  - only records reachable from the question by the CNAME chain are used; chain names outside the answering zone's bailiwick are
    re-resolved from scratch; glue is accepted only if the host is below the delegated name; a referral must be strictly deeper than the
    current cut (otherwise the server is skipped).
  - glue-less name servers are resolved recursively (depth <= 3); CNAME chain <= 8 with loop detection (SERVFAIL); per-resolve
    query budget 48 (SERVFAIL); unreachable servers are skipped in order; all unusable -> SERVFAIL.
- `Network` (test transport): servers by address, per-address down switch, every message goes through `Wire` both ways, query log.

## Success criteria
`mvn -q -B clean test` passes: literal wire hex (query, compression pointer), exhaustive truncation and flip-every-byte loops
("DecodeException or stable re-encode"), crafted malformed messages each with a control, zone lookup literals plus a
randomized flat-list oracle, resolver literals (exact query logs and counts, TTL expiry, negative caching, failover,
glue-less NS, lame referral, CNAME loop, poisoning attempts) and a differential test of the resolver against a direct
zone-chasing oracle with shared cache.
