# dns-toolkit

- Description: DNS toolkit (package `ae.dns`): immutable `Name` with validation, RFC 1035 `Wire` codec (name compression on encode, strict pointer-safe decoder that throws only `DecodeException`), `Zone` lookup (delegation cuts, CNAME, empty non-terminals, wildcard synthesis) with a master-file subset parser, authoritative `Server` (in-zone CNAME chasing, referrals with glue, SOA in authority), and an iterative caching `Resolver` (positive/NXDOMAIN/NODATA caching with TTL decay, bailiwick checks, glue-less NS, CNAME loops, failover, query budget) over a wire-level `Network` test transport and `ManualClock`.
- Language: java
- Tags: dns, wire-format, compression-pointers, zone, wildcard, resolver, ttl-cache, negative-caching, bailiwick, differential-testing
- Source round: practice/java/20261125T0257Z
- Tests (68): literal wire hex, pointer/flag/rdlength rejection tests each with a control, exhaustive truncation and bit-flip loops, randomized zone lookup vs flat-list oracle, exact resolver query logs, hostile-transport tests (poisoned records, wrong id/question, lame referral, mutual glue-less NS), differential test of the resolver against a direct zone-chasing oracle with fresh and warm caches.
- Mutation check: 20 hand mutants; 16 killed outright, 2 equivalent (decoder name-length loop cap and `pos > end` check are duplicated by `Name.of` and the rdlength-mismatch check), 2 gaps (NXDOMAIN without AA, server CNAME-loop guard) closed with new tests and then killed.
