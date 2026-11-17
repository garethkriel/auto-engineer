# glob-matcher

- **Description:** compiled path glob (`?`, `*`, `**`, `**/`, `[a-z]`, `[!x]`, `\` escapes) with memoised matching that stays polynomial on pathological patterns.
- **Language:** java
- **Tags:** parsing, recursion, memoisation, wildcard, glob, differential-testing
- **Source round:** practice/java/20261117T0456Z

Usage: `Glob.compile("src/**/*.java").matches("src/a/B.java")`. Invalid patterns throw `IllegalArgumentException`.
