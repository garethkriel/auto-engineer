# Task: middleware pipeline (chain of responsibility) — level 8, design patterns

Package `ae.mw`. Build an immutable HTTP-like middleware pipeline.

- `record Request(String method, String path, Map<String,String> headers)`: method upper-cased in the
  canonical constructor, header names lower-cased, headers copied immutably; `withHeader(name,value)` returns a copy.
- `record Response(int status, String body)`; status must be 100..599.
- `interface Handler { Response handle(Request) }`, `interface Middleware { Response handle(Request, Handler next) }`.
- `Pipeline`: immutable; `Pipeline.to(Handler terminal)`, `use(Middleware)` returns a new pipeline; the first
  `use`d middleware is outermost (runs first). `build()` returns a `Handler`. Adding to a pipeline never alters earlier ones.
- `Router implements Handler`: immutable, `Router.empty().route(method, pattern, handler)`; patterns have
  literal segments and `{name}` params (captured into request header `x-param-name`). Unknown path -> 404, known
  path with other method -> 405 with body listing allowed methods sorted, comma separated. First matching route wins.
- Middlewares in `Middlewares`: `requireToken(String)` (header `authorization` must be `Bearer <token>` else 401, next not called),
  `recover()` (RuntimeException from downstream -> 500 with body = exception class simple name),
  `trace(List<String> sink)` (adds "-> METHOD path" before and "<- status" after the call, also "<- error" if it throws then rethrows),
  `cacheGet(Clock, Duration ttl)` (caches 200 responses to GET by path until ttl elapses; non-GET and non-200 never cached).
- Arguments validated (null, blank pattern, non-positive ttl).

Success: `mvn -q -B test` passes. Assumption: benchmark overlap check — benchmark names are roman, wordfreq, lru,
csv, intervals, lcs, expr, graph, bank, counter, streams, fileindex; none concern middleware/routing. (A TTL cache here is
not an LRU; it has no capacity/eviction order.)
