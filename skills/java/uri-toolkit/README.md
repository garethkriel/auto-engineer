# uri-toolkit

- **Language:** Java 21 (standard library only)
- **Tags:** uri, rfc3986, percent-encoding, reference-resolution, normalisation, query-string, differential-testing
- **Source round:** `practice/java/20261126T1557Z`

Strict RFC 3986 `ae.uri.Uri` (parse, exact recomposition, section 5.2 `resolve`, `removeDotSegments`, `normalize`, `relativize`), `PercentCodec` (strict UTF-8), `QueryParams` (form-urlencoded multimap).

Notes: `/.` and `./` prefixes keep results re-parseable (no authority + path starting `//`; scheme-less first segment with `:`). The JDK `java.net.URI` is not a valid oracle for empty authority, `//` in paths, dot segments or absolute refs; those are excluded from the differential test on purpose.
