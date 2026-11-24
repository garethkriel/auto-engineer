# prolog-engine

- **Language:** java (21, standard library only)
- **Tags:** prolog, unification, sld-resolution, cut, backtracking, parser, operator-precedence, choicepoints, trail, interpreters
- **Source round:** practice/java/20261124T0456Z

A Prolog subset in package `ae.prolog`. `Parser` is a tokenizer plus operator-precedence parser (line/column errors).
`Engine` is an iterative machine: a continuation list, a choicepoint stack and a trail, so deep recursion does not use the
Java stack. Cut barriers are recorded per call; `->`, `;`, `\+` and `call/N` use a private `$cut(B)` goal.

Supported: `, ; -> \+ ! = \= == \== is =:= =\= < > =< >=`, `+ - * // mod rem min max abs` (overflow-checked longs),
`call/1..4 findall/3 assertz asserta retract sort msort compare`, type tests, and a library of append, member, reverse,
length, select, permutation, between, sum_list, last, once, ignore. Clause lists are copy-on-write (logical update view).
Solutions are deep copies. First-argument indexing by functor keeps fact tables determinate (`Solution.choicepointsLeft()`).
Not supported: floats, strings, directives, `retract` backtracking, exceptions as terms.

Notes from the mutation run (24 mutants): the only equivalent one is "re-scan from i+1 instead of the next matching clause";
all others are killed.
Test-writing traps found: `length(L,N), N < 2` has only two answers, so asking for three never terminates; unbounded
cyclic terms (no occurs check) must be guarded when copying answers (`Terms.COPY_BUDGET`).
Run: `mvn -q -B test`.
