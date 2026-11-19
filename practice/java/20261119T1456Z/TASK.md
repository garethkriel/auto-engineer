# Task: Hindley-Milner type inference for a tiny functional language (`ae.hm`)

Level 20 capstone. Overlap check against `benchmark/java/` (j07-expr arithmetic evaluator, j28-script
interpreter, j35-minisql, ...): those *evaluate* programs; this task *infers types* by unification and
let-generalisation and never evaluates anything, so it is not a paraphrase of any of them.

## Language
```
expr  := '\' ident+ '->' expr
       | 'let' ['rec'] ident ident* '=' expr 'in' expr      (let f x y = e  desugars to lambdas)
       | 'if' expr 'then' expr 'else' expr
       | add ['==' add]
add   := mul (('+' | '-') mul)*        mul := app ('*' app)*
app   := atom atom*                    (left-associative application)
atom  := int | 'true' | 'false' | ident | '(' expr [',' expr] ')'
```
Keywords: let rec in if then else true false. No negative literals (write `0 - 1`).
Builtins (shadowable): `+ - *` : Int->Int->Int, `==` : Int->Int->Bool, `not`, `fst`, `snd`,
`nil : [a]`, `cons : a->[a]->[a]`, `head`, `tail`, `isNil`.

## API
- `Hm.typeOf(String source) : String` - principal type, pretty-printed with variables renamed a, b, c ... in
  order of first appearance; arrows right-associative; pairs `(Int, Bool)`; lists `[a]`.
- `TypeException(kind, line, col, message)` with kind MISMATCH | OCCURS | UNBOUND; `ParseException(line, col, message)`.
  Lines/columns are 1-based. Positions: UNBOUND = the variable; MISMATCH in application = the application's
  start (for a binary operator: the operator token); `if` condition mismatch = the condition start;
  branch mismatch = the else-branch start; `let rec` body/binding mismatch = the bound expression start.
- Let-polymorphism: `let` generalises variables not free in the environment; lambda parameters stay monomorphic;
  `let rec` binds the name monomorphically inside its own definition.

## Success criteria
1. Known principal types (id, const, compose, S combinator, flip, fact, map via rec, polymorphic let used at two types).
2. Errors: mismatch, occurs check (`\x -> x x`), unbound, parse errors, with exact line/col.
3. Generalisation traps: lambda-bound variable not polymorphic; let-bound function capturing an outer lambda variable not generalised over it.
4. Randomised type-directed term generator: principal type must be an instance of the target type (independent matcher);
   let-inlining must preserve the principal type; term pretty-print/parse round trip; substitution invariant holds.
5. `mvn -q -B clean test` passes.
