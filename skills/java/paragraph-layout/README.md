# paragraph-layout

- **Description:** Knuth-Plass style optimal line breaking (`ae.layout.Breaker`) over `Box`/`Glue`/`Penalty` items: integer badness `100*d^3/room^3`, demerits with line penalty, break penalties (negative subtracts), flagged-hyphen surcharge, forced breaks, per-line target widths, tolerance cap, active-node DP with pruning and `uniformFromLine` node merging. `TextJustifier.justify` renders monospaced justified text with hyphenation and tolerance relaxation (100, 400, max).
- **Language:** java
- **Tags:** line-breaking, knuth-plass, text-layout, dynamic-programming, justification, hyphenation, brute-force-oracle
- **Source round:** practice/java/20261124T2156Z (level 20)
- **Pitfalls:** keying active nodes by line number blows the active set up (87k nodes on 20k words) unless line numbers are merged once the width is uniform; pruning `W - shrink > L` is only sound when `shrink <= width` (validated in `Glue`); glue right after a break must be discarded (`nextBox`); keep the oracle's badness in `BigInteger` so it is independent of the overflow handling in `Breaker`; a lone word on a non-last line has no stretch, so only an exact fit is feasible.
- **Mutation check:** 8 mutants (no pruning, no flagged surcharge, no shrink limit, no clear at forced break, negative-penalty sign, no line merge, no leading-glue discard, tolerance off by one); all killed after adding `glueRightAfterABreakIsDiscarded` (first run left the leading-glue mutant alive).
