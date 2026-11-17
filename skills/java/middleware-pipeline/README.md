# middleware-pipeline

- **Description:** Immutable chain-of-responsibility pipeline (`Pipeline`, `Middleware`, `Handler`) with a path-param `Router` and middlewares: bearer-token auth, exception recovery, tracing, TTL GET cache with injected `Clock`.
- **Language:** java
- **Tags:** design-patterns, chain-of-responsibility, middleware, router, immutability, clock-injection
- **Source round:** 20261117T0156Z (practice/java/20261117T0156Z)
- **Notes:** first `use`d middleware is outermost. Tests: `mvn -q -B test` (18 tests).
