package ae.prolog;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EngineTest {
    private static List<String> all(Engine e, String q, String var) {
        List<String> out = new ArrayList<>();
        for (Solution s : e.all(q)) out.add(Terms.format(s.get(var)));
        return out;
    }

    private static String first(Engine e, String q, String var) {
        Optional<Solution> s = e.first(q);
        assertTrue(s.isPresent(), "no solution for " + q);
        return Terms.format(s.get().get(var));
    }

    private static PrologError error(Engine e, String q) {
        return assertThrows(PrologError.class, () -> e.all(q), q);
    }

    @Test
    void factsAndBacktrackingOrder() {
        Engine e = new Engine();
        e.consult("parent(tom,bob). parent(tom,liz). parent(bob,ann). parent(bob,pat).");
        assertEquals(List.of("bob", "liz"), all(e, "parent(tom,X)", "X"));
        e.consult("grand(X,Z) :- parent(X,Y), parent(Y,Z).");
        assertEquals(List.of("ann", "pat"), all(e, "grand(tom,Z)", "Z"));
        assertTrue(e.all("parent(ann,_)").isEmpty());
    }

    @Test
    void unificationBasics() {
        Engine e = new Engine();
        assertEquals("f(a,b)", first(e, "f(X,b) = f(a,Y), Z = f(X,Y)", "Z"));
        assertFalse(e.holds("f(a) = f(b)"));
        assertFalse(e.holds("f(a) = g(a)"));
        assertFalse(e.holds("f(a) = f(a,b)"));
        assertTrue(e.holds("X = Y, Y = 3, X == 3"));
        assertTrue(e.holds("f(X,X) = f(1,1)"));
        assertFalse(e.holds("f(X,X) = f(1,2)"));
        assertTrue(e.holds("a \\= b"));
        assertFalse(e.holds("X \\= 1"));
    }

    @Test
    void unificationDoesNotLeakBindingsThroughNotUnifiable() {
        Engine e = new Engine();
        // \= must undo any partial bindings it made: X stays unbound afterwards.
        Solution s = e.first("f(X,b) \\= f(a,c), var(X)").orElseThrow();
        assertTrue(s.get("X") instanceof Var);
    }

    @Test
    void occursCheckIsOptional() {
        Engine plain = new Engine();
        assertTrue(plain.holds("_X = f(_X)"), "cyclic binding is allowed without the occurs check");
        // ... but a cyclic answer cannot be copied out: a clean RESOURCE error instead of running out of memory
        assertEquals(PrologError.Kind.RESOURCE, error(plain, "X = f(X)").kind());
        Engine strict = new Engine(true, 1_000_000);
        assertFalse(strict.holds("X = f(X)"));
        assertTrue(strict.holds("X = f(Y)"));
    }

    @Test
    void cutCommitsToFirstClause() {
        Engine e = new Engine();
        e.consult("max(X,Y,X) :- X >= Y, !. max(_,Y,Y).");
        assertEquals("5", first(e, "max(5,3,M)", "M"));
        assertEquals("7", first(e, "max(2,7,M)", "M"));
        assertEquals(1, e.all("max(5,3,M)").size());
        e.consult("t(1). t(2). t(3). firstt(X) :- t(X), !.");
        assertEquals(List.of("1"), all(e, "firstt(X)", "X"));
    }

    @Test
    void cutIsLocalToItsPredicate() {
        Engine e = new Engine();
        e.consult("t(1). t(2). t(3). p(X) :- t(X), q. q :- t(_), !. ");
        // the cut inside q must not prune t(X) in p
        assertEquals(List.of("1", "2", "3"), all(e, "p(X)", "X"));
    }

    @Test
    void cutInsideCallIsLocal() {
        Engine e = new Engine();
        e.consult("t(1). t(2). t(3).");
        assertEquals(List.of("1", "2", "3"), all(e, "t(X), call((true, !))", "X"));
        assertEquals(List.of("1"), all(e, "t(X), !", "X"));
    }

    @Test
    void cutInsideDisjunctionCutsTheClause() {
        Engine e = new Engine();
        e.consult("t(1). t(2). u(X) :- ( t(X), ! ; X = 9 ). v(X) :- ( X = 1 ; X = 2 ), !.");
        assertEquals(List.of("1"), all(e, "u(X)", "X"));
        assertEquals(List.of("1"), all(e, "v(X)", "X"));
    }

    @Test
    void negationAsFailure() {
        Engine e = new Engine();
        e.consult("t(1). t(2).");
        assertTrue(e.holds("\\+ t(3)"));
        assertFalse(e.holds("\\+ t(1)"));
        assertEquals(List.of("1", "2"), all(e, "t(X), \\+ X = 3", "X"));
        // \+ leaves no bindings
        assertTrue(e.first("\\+ \\+ X = 1, var(X)").isPresent());
    }

    @Test
    void ifThenElse() {
        Engine e = new Engine();
        e.consult("t(1). t(2). sign(X,S) :- ( X > 0 -> S = pos ; X < 0 -> S = neg ; S = zero ).");
        assertEquals("pos", first(e, "sign(5,S)", "S"));
        assertEquals("neg", first(e, "sign(-5,S)", "S"));
        assertEquals("zero", first(e, "sign(0,S)", "S"));
        // condition is committed: only first solution of the condition is used
        assertEquals(List.of("a"), all(e, "( t(_) -> R = a ; R = b )", "R"));
        // (C -> T) without else fails when C fails
        assertFalse(e.holds("( fail -> true )"));
        // but then-branch may backtrack
        assertEquals(List.of("1", "2"), all(e, "( true -> t(X) ; X = 0 )", "X"));
    }

    @Test
    void disjunctionOrder() {
        Engine e = new Engine();
        assertEquals(List.of("1", "2", "3"), all(e, "( X = 1 ; X = 2 ; X = 3 )", "X"));
    }

    @Test
    void arithmetic() {
        Engine e = new Engine();
        assertEquals("7", first(e, "X is 1 + 2 * 3", "X"));
        assertEquals("-3", first(e, "X is -7 // 2", "X"), "// truncates toward zero");
        assertEquals("1", first(e, "X is -7 mod 2", "X"), "mod follows the divisor's sign");
        assertEquals("-1", first(e, "X is -7 rem 2", "X"), "rem follows the dividend's sign");
        assertEquals("-1", first(e, "X is 7 mod -2", "X"));
        assertEquals("3", first(e, "X is max(1, 3)", "X"));
        assertEquals("2", first(e, "X is abs(-2)", "X"));
        assertTrue(e.holds("3 =:= 1 + 2"));
        assertTrue(e.holds("3 =\\= 4"));
        assertTrue(e.holds("1 < 2, 2 =< 2, 3 > 2, 3 >= 3"));
        assertFalse(e.holds("2 < 2"));
    }

    @Test
    void errorKinds() {
        Engine e = new Engine();
        assertEquals(PrologError.Kind.INSTANTIATION, error(e, "X is Y + 1").kind());
        assertEquals(PrologError.Kind.INSTANTIATION, error(e, "X").kind());
        assertEquals(PrologError.Kind.TYPE, error(e, "X is foo + 1").kind());
        assertEquals(PrologError.Kind.TYPE, error(e, "3").kind());
        assertEquals(PrologError.Kind.EVALUATION, error(e, "X is 1 // 0").kind());
        assertEquals(PrologError.Kind.EVALUATION, error(e, "X is 1 mod 0").kind());
        assertEquals(PrologError.Kind.EVALUATION, error(e, "X is 9223372036854775807 + 1").kind());
        assertEquals(PrologError.Kind.EVALUATION, error(e, "X is -9223372036854775808 // -1").kind());
        assertEquals(PrologError.Kind.EVALUATION, error(e, "X is -(-9223372036854775807 - 1)").kind());
        assertEquals(PrologError.Kind.EXISTENCE, error(e, "nosuch(1)").kind());
        assertEquals(PrologError.Kind.SYNTAX, error(e, "foo(").kind());
        assertEquals(PrologError.Kind.PERMISSION, assertThrows(PrologError.class, () -> e.consult("append(a,b,c).")).kind());
    }

    @Test
    void failedArithmeticComparisonJustFails() {
        Engine e = new Engine();
        assertFalse(e.holds("1 > 2"));
        assertEquals(PrologError.Kind.INSTANTIATION, error(e, "X > 2").kind());
    }

    @Test
    void libraryListPredicates() {
        Engine e = new Engine();
        assertEquals("[1,2,3,4]", first(e, "append([1,2],[3,4],L)", "L"));
        assertEquals(List.of("[]-[1,2]", "[1]-[2]", "[1,2]-[]"),
                all(e, "append(A,B,[1,2]), P = A-B", "P"));
        assertEquals(List.of("1", "2", "3"), all(e, "member(X,[1,2,3])", "X"));
        assertEquals("[3,2,1]", first(e, "reverse([1,2,3],R)", "R"));
        assertEquals("3", first(e, "length([a,b,c],N)", "N"));
        assertEquals("[_V0,_V1]", Terms.formatCanonical(e.first("length(L,2)").orElseThrow().get("L")));
        assertEquals(List.of("[]", "[_V0]"), canon(e, "length(L,N), N < 2", "L"));
        assertEquals(List.of("1-[2,3]", "2-[1,3]", "3-[1,2]"), all(e, "select(X,[1,2,3],R), P = X-R", "P"));
        assertEquals(List.of("1", "2", "3"), all(e, "between(1,3,X)", "X"));
        assertTrue(e.holds("between(1,3,2)"));
        assertFalse(e.holds("between(1,3,4)"));
        assertEquals("6", first(e, "sum_list([1,2,3],S)", "S"));
        assertEquals("3", first(e, "last([1,2,3],X)", "X"));
    }

    private static List<String> canon(Engine e, String q, String var) {
        List<String> out = new ArrayList<>();
        for (Solution s : e.all(q, 2)) out.add(Terms.formatCanonical(s.get(var)));
        return out;
    }

    @Test
    void onceIgnoreAndCall() {
        Engine e = new Engine();
        assertEquals(List.of("1"), all(e, "once(member(X,[1,2,3]))", "X"));
        assertTrue(e.holds("ignore(fail)"));
        e.consult("add(A,B,C) :- C is A+B.");
        assertEquals("5", first(e, "call(add(2), 3, S)", "S"));
        assertEquals("5", first(e, "call(add, 2, 3, S)", "S"));
        assertEquals("5", first(e, "G = add(2,3,S), call(G)", "S"));
    }

    @Test
    void findallCollectsCopiesInOrder() {
        Engine e = new Engine();
        e.consult("t(3). t(1). t(2).");
        assertEquals("[3,1,2]", first(e, "findall(X, t(X), L)", "L"));
        assertEquals("[]", first(e, "findall(X, fail, L)", "L"));
        // each collected copy gets its own fresh variable
        assertEquals("[f(_V0),f(_V1),f(_V2)]", Terms.formatCanonical(e.first("findall(f(_), t(_), L)").orElseThrow().get("L")));
        // findall leaves its goal's bindings undone
        assertTrue(e.holds("findall(X, t(X), _), var(X)"));
        // nested findall
        assertEquals("[[3,1,2],[3,1,2]]", first(e, "findall(L, (member(_,[a,b]), findall(X,t(X),L)), R)", "R"));
    }

    @Test
    void sortingAndCompare() {
        Engine e = new Engine();
        assertEquals("[1,2,3,3]", first(e, "msort([3,1,3,2],L)", "L"));
        assertEquals("[1,2,3]", first(e, "sort([3,1,3,2],L)", "L"));
        assertEquals("[7,b,c,f(a),g(a),f(a,a)]",
                first(e, "sort([f(a,a),c,g(a),7,f(a),b],L)", "L"), "standard order: Int < Atom < Struct by arity, name, args");
        assertEquals("<", first(e, "compare(O,1,2)", "O"));
        assertEquals("=", first(e, "compare(O,f(a),f(a))", "O"));
        assertEquals(">", first(e, "compare(O,b,a)", "O"));
        assertTrue(e.holds("a == a, f(X) == f(X), f(X) \\== f(Y)"));
        assertEquals(PrologError.Kind.INSTANTIATION, error(e, "sort(L, _)").kind());
        assertEquals(PrologError.Kind.TYPE, error(e, "sort(foo, _)").kind());
    }

    @Test
    void assertAndRetractUseLogicalUpdateView() {
        Engine e = new Engine();
        assertTrue(e.holds("assertz(c(1)), assertz(c(2)), asserta(c(0))"));
        assertEquals(List.of("0", "1", "2"), all(e, "c(X)", "X"));
        // clauses added while iterating are not seen by the running query
        assertEquals(List.of("0", "1", "2"), all(e, "c(X), assertz(c(9))", "X"));
        assertEquals(6, e.all("c(_)").size());
        assertTrue(e.holds("retract(c(9))"));
        assertEquals(5, e.all("c(_)").size());
        assertFalse(e.holds("retract(c(42))"));
        assertEquals(PrologError.Kind.PERMISSION, error(e, "assertz(append(a,b,c))").kind());
        // known-but-empty predicate fails instead of raising an existence error
        assertTrue(e.holds("assertz(d(1)), retract(d(1))"));
        assertFalse(e.holds("d(_)"));
    }

    @Test
    void assertedClausesWithBodies() {
        Engine e = new Engine();
        assertTrue(e.holds("assertz((double(X,Y) :- Y is X*2))"));
        assertEquals("8", first(e, "double(4,Y)", "Y"));
    }

    @Test
    void solutionsStayValidAfterFurtherBacktracking() {
        Engine e = new Engine();
        e.consult("t(1). t(2). t(3).");
        var it = e.query("t(X), Y = f(X, _)");
        Solution a = it.next();
        Solution b = it.next();
        Solution c = it.next();
        assertFalse(it.hasNext());
        assertEquals("1", Terms.format(a.get("X")));
        assertEquals("2", Terms.format(b.get("X")));
        assertEquals("f(3,_V0)", Terms.formatCanonical(c.get("Y")));
        assertEquals("f(1,_V0)", Terms.formatCanonical(a.get("Y")));
    }

    @Test
    void sharedVariablesStayLinkedInASolution() {
        Engine e = new Engine();
        Solution s = e.first("A = f(Z), B = g(Z)").orElseThrow();
        assertEquals("f(_V0)-g(_V0)", Terms.formatCanonical(new Struct("-", s.get("A"), s.get("B"))));
    }

    // ---- scale ----
    @Test
    void deepRecursionDoesNotOverflowTheJavaStack() {
        Engine e = new Engine(false, 50_000_000L);
        e.consult("count(N,N) :- !. count(I,N) :- I1 is I+1, count(I1,N).");
        assertTrue(e.holds("count(0,200000)"));
        e.consult("build(0,[]) :- !. build(N,[N|T]) :- N1 is N-1, build(N1,T).");
        Solution s = e.first("build(50000,L), length(L,Len), last(L,La)").orElseThrow();
        assertEquals("50000", Terms.format(s.get("Len")));
        assertEquals("1", Terms.format(s.get("La")));
        assertEquals(50000, Terms.toJavaList(s.get("L")).size());
        // long list round trips through format and parse
        assertEquals(50000, Terms.toJavaList(Parser.parseTerm(Terms.format(s.get("L")))).size());
    }

    @Test
    void determinateRecursionLeavesNoChoicepoints() {
        Engine e = new Engine(false, 50_000_000L);
        // first-argument indexing: len2/2 on a list must not keep a choicepoint per element
        e.consult("len2([],0). len2([_|T],N) :- len2(T,M), N is M+1.");
        e.consult("numlist2(0,[]) :- !. numlist2(N,[N|T]) :- M is N-1, numlist2(M,T).");
        Solution s = e.first("numlist2(30000,L), len2(L,N)").orElseThrow();
        assertEquals("30000", Terms.format(s.get("N")));
        assertEquals(0, s.choicepointsLeft(), "no choicepoint should remain after a determinate query");
        Solution m = e.first("member(X,[a,b])").orElseThrow();
        assertEquals(1, m.choicepointsLeft(), "member leaves the alternative for b");
    }

    @Test
    void stepLimitStopsRunawayQueries() {
        Engine e = new Engine(false, 10_000);
        e.consult("loop :- loop.");
        assertEquals(PrologError.Kind.RESOURCE, error(e, "loop").kind());
        e.consult("grow(X) :- grow(f(X)).");
        assertEquals(PrologError.Kind.RESOURCE, error(e, "grow(a)").kind());
        // the engine is still usable afterwards
        assertTrue(e.holds("true"));
    }

    @Test
    void errorInsideFindallLeavesEngineUsable() {
        Engine e = new Engine();
        assertEquals(PrologError.Kind.EVALUATION, error(e, "findall(X, (member(Y,[1,0]), X is 1 // Y), _)").kind());
        assertTrue(e.holds("findall(X, member(X,[1]), [1])"));
    }

    @Test
    void famousPuzzles() {
        Engine e = new Engine(false, 100_000_000L);
        e.consult(
            "queens(N,Qs) :- numlist2(N,Ns), permutation(Ns,Qs), safe(Qs).\n"
          + "numlist2(0,[]) :- !.\nnumlist2(N,[N|T]) :- M is N-1, numlist2(M,T).\n"
          + "safe([]). safe([Q|Qs]) :- no_attack(Q,Qs,1), safe(Qs).\n"
          + "no_attack(_,[],_).\n"
          + "no_attack(Q,[Q1|Qs],D) :- Q =\\= Q1 + D, Q =\\= Q1 - D, D1 is D+1, no_attack(Q,Qs,D1).\n");
        assertEquals(2, e.all("queens(4,_)").size());
        assertEquals(10, e.all("queens(5,_)").size());
        assertEquals(4, e.all("queens(6,_)").size());
        e.consult(
            "hanoi(0,_,_,_,[]) :- !.\n"
          + "hanoi(N,A,B,C,Moves) :- N1 is N-1, hanoi(N1,A,C,B,M1), hanoi(N1,C,B,A,M2),\n"
          + "    append(M1,[A-B|M2],Moves).\n");
        assertEquals("3", first(e, "hanoi(2,l,r,m,M), length(M,N)", "N"));
        assertEquals("[l-m,l-r,m-r]", first(e, "hanoi(2,l,r,m,M)", "M"));
        assertEquals("1023", first(e, "hanoi(10,l,r,m,M), length(M,N)", "N"));
    }

    // ---- tests added after the mutation run (each kills a surviving mutant) ----

    @Test
    void cutInsideIfConditionIsLocalToTheCondition() {
        Engine e = new Engine();
        e.consult("t(1). t(2).");
        // the cut commits t/1 inside the condition only; the else branch must stay reachable
        assertEquals(List.of("b"), all(e, "( (t(X), !, X > 1) -> R = a ; R = b )", "R"));
        assertEquals(List.of("a"), all(e, "( (t(X), !, X > 0) -> R = a ; R = b )", "R"));
        assertTrue(e.holds("\\+ (t(X), !, X > 1)"));
    }

    @Test
    void cutInsideElseBranchOnlyCutsItsOwnClause() {
        Engine e = new Engine();
        e.consult("t(1). t(2). s2 :- ( fail ; ! ). w(X) :- t(X), s2.");
        assertEquals(List.of("1", "2"), all(e, "w(X)", "X"));
    }

    @Test
    void firstArgumentIndexingKeepsFactTablesDeterminate() {
        Engine e = new Engine();
        e.consult("f(1,a). f(2,b). f(3,c). g(x,1). g(y,2). g(z(1),3). g(z(2),4).");
        assertEquals(0, e.first("f(2,V)").orElseThrow().choicepointsLeft());
        assertEquals(0, e.first("g(y,V)").orElseThrow().choicepointsLeft());
        // indexing looks at the functor only, so the second z/1 clause is still a candidate
        assertEquals(1, e.first("g(z(1),V)").orElseThrow().choicepointsLeft());
        assertEquals(1, e.first("f(N,V)").orElseThrow().choicepointsLeft());
    }

    @Test
    void failedHeadUnificationLeavesNoBindingsBehind() {
        Engine e = new Engine();
        e.consult("p(a,c). p(_,b).");
        Solution s = e.first("p(X,b)").orElseThrow();
        assertTrue(s.get("X") instanceof Var, "X must stay unbound: the first clause failed after binding X=a");
    }

    @Test
    void betweenIncludesBothBoundsWhenCheckingAnInteger() {
        Engine e = new Engine();
        assertTrue(e.holds("between(1,3,1)"));
        assertTrue(e.holds("between(1,3,3)"));
        assertFalse(e.holds("between(1,3,0)"));
    }

    @Test
    void identityComparisonDoesNotBind() {
        Engine e = new Engine();
        assertFalse(e.holds("X == 1"));
        assertFalse(e.holds("f(X) == f(1)"));
        assertTrue(e.holds("X \\== 1, var(X)"));
    }

    @Test
    void nonAssociativeOperatorsRejectChains() {
        assertThrows(PrologError.class, () -> Parser.parseTerm("a = b = c"));
        assertThrows(PrologError.class, () -> Parser.parseTerm("a :- b :- c"));
        assertEquals("a=(b=c)", Terms.format(Parser.parseTerm("a = (b = c)")));
        assertEquals("a;b;c", Terms.format(Parser.parseTerm("a ; b ; c")));
    }
}
