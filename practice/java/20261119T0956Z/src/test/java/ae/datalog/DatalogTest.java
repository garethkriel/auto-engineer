package ae.datalog;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DatalogTest {

    private static final String FAMILY = """
        parent(tom, bob). parent(tom, liz). parent(bob, ann). parent(bob, pat). parent(pat, jim).
        anc(X, Y) :- parent(X, Y).
        anc(X, Z) :- parent(X, Y), anc(Y, Z).
        ?- anc(tom, X).
        ?- anc(X, jim).
        """;

    @Test
    void ancestorRelation() {
        Result r = Datalog.run(FAMILY);
        assertEquals(List.of(
            List.of("bob", "ann"), List.of("bob", "jim"), List.of("bob", "pat"),
            List.of("pat", "jim"), List.of("tom", "ann"), List.of("tom", "bob"),
            List.of("tom", "jim"), List.of("tom", "liz"), List.of("tom", "pat")),
            r.relation("anc"));
    }

    @Test
    void queryAnswersAreSortedBindings() {
        Result r = Datalog.run(FAMILY);
        assertEquals(List.of(Map.of("X", "ann"), Map.of("X", "bob"), Map.of("X", "jim"),
            Map.of("X", "liz"), Map.of("X", "pat")), r.answers(0));
        assertEquals(List.of(Map.of("X", "bob"), Map.of("X", "pat"), Map.of("X", "tom")), r.answers(1));
    }

    @Test
    void groundQueryYieldsOneEmptyBindingWhenTrueAndNoneWhenFalse() {
        Result r = Datalog.run(FAMILY + "?- anc(tom, jim). ?- anc(jim, tom).");
        assertEquals(List.of(Map.of()), r.answers(2));
        assertEquals(List.of(), r.answers(3));
    }

    @Test
    void repeatedVariableInQueryRequiresEqualValues() {
        Result r = Datalog.run("e(a,a). e(a,b). e(c,c). ?- e(X,X).");
        assertEquals(List.of(Map.of("X", "a"), Map.of("X", "c")), r.answers(0));
    }

    @Test
    void anonymousVariablesAreFreshAndNotReported() {
        Result r = Datalog.run("e(a,b). e(a,c). e(b,b). ?- e(X,_). ?- e(_,_).");
        assertEquals(List.of(Map.of("X", "a"), Map.of("X", "b")), r.answers(0));
        assertEquals(List.of(Map.of()), r.answers(1));
    }

    @Test
    void sameGeneration() {
        Result r = Datalog.run("""
            par(a, b). par(a, c). par(b, d). par(c, e). par(d, f). par(e, g).
            person(X) :- par(X, _).
            person(X) :- par(_, X).
            sg(X, X) :- person(X).
            sg(X, Y) :- par(P, X), sg(P, Q), par(Q, Y).
            ?- sg(f, X).
            """);
        assertEquals(List.of(Map.of("X", "f"), Map.of("X", "g")), r.answers(0));
        assertEquals(1, r.strata());
    }

    @Test
    void negationUsesCompletedLowerStratum() {
        Result r = Datalog.run("""
            node(a). node(b). node(c). node(d).
            edge(a, b). edge(b, c).
            reach(X, Y) :- edge(X, Y).
            reach(X, Z) :- reach(X, Y), edge(Y, Z).
            unreachable(X, Y) :- node(X), node(Y), not reach(X, Y), X != Y.
            ?- unreachable(a, Y).
            """);
        assertEquals(List.of(Map.of("Y", "d")), r.answers(0));
        assertEquals(9, r.relation("unreachable").size()); // 12 ordered pairs X!=Y minus reach (a,b),(a,c),(b,c)
    }

    @Test
    void existentialAnonymousVariableInNegation() {
        Result r = Datalog.run("""
            node(a). node(b). node(c). edge(a, b).
            hasOut(X) :- node(X), edge(X, _).
            sink(X) :- node(X), not edge(X, _).
            ?- sink(X).
            """);
        assertEquals(List.of(Map.of("X", "b"), Map.of("X", "c")), r.answers(0));
    }

    @Test
    void winMoveOnAcyclicGameWithStratifiedAttempt() {
        // lost(X): every move leads to a won position, computed per layer by explicit levels
        Result r = Datalog.run("""
            move(a, b). move(b, c). move(c, d).
            pos(X) :- move(X, _).
            pos(X) :- move(_, X).
            hasMove(X) :- move(X, _).
            terminal(X) :- pos(X), not hasMove(X).
            win1(X) :- move(X, Y), terminal(Y).
            ?- terminal(X). ?- win1(X).
            """);
        assertEquals(List.of(Map.of("X", "d")), r.answers(0));
        assertEquals(List.of(Map.of("X", "c")), r.answers(1));
        assertEquals(2, r.strata());
    }

    @Test
    void integersAndQuotedStringsAreConstants() {
        Result r = Datalog.run("""
            v(1, "two words"). v(-7, "say \\"hi\\"").
            ?- v(X, Y).
            """);
        assertEquals(List.of(Map.of("X", "-7", "Y", "say \"hi\""), Map.of("X", "1", "Y", "two words")),
            r.answers(0));
        assertEquals(List.of(Map.of()), Datalog.run("p(\"abc\"). ?- p(abc).").answers(0));
    }

    @Test
    void commentsAndLayoutAreIgnored() {
        Result r = Datalog.run("% comment\np(a). % trailing\n\n  q(X) :-\n   p(X).  ?- q(X).");
        assertEquals(List.of(Map.of("X", "a")), r.answers(0));
    }

    @Test
    void unknownPredicateHasEmptyRelation() {
        assertEquals(List.of(), Datalog.run("p(a).").relation("nothing"));
    }

    @Test
    void duplicateFactsCollapse() {
        assertEquals(1, Datalog.run("p(a). p(a). p(a).").relation("p").size());
    }

    // ---- errors ----

    private static DatalogException fail(String src) {
        return assertThrows(DatalogException.class, () -> Datalog.run(src), src);
    }

    @Test
    void syntaxErrorsCarryLineAndColumn() {
        DatalogException e = fail("p(a).\nq(b) :- p(X)\n?- q(X).");
        assertEquals(3, e.line(), e.getMessage());
        assertEquals(1, e.column(), e.getMessage());
        e = fail("p(a, ).");
        assertEquals(1, e.line());
        assertEquals(6, e.column());
        e = fail("p(\"unterminated).");
        assertEquals(1, e.line());
        assertEquals(3, e.column());
        e = fail("p(a) $ .");
        assertEquals(6, e.column(), e.getMessage()); // '$' is the 6th char
    }

    @Test
    void factsMustBeGround() {
        DatalogException e = fail("p(a).\n  q(X).");
        assertEquals(2, e.line());
        assertEquals(3, e.column());
        assertTrue(e.getMessage().contains("ground"), e.getMessage());
    }

    @Test
    void arityMustBeConsistent() {
        DatalogException e = fail("p(a, b).\nq(X) :- p(X).");
        assertEquals(2, e.line());
        assertEquals(9, e.column());
        assertTrue(e.getMessage().contains("arity"), e.getMessage());
        fail("p(a). ?- p(a, b).");
    }

    @Test
    void unsafeRulesAreRejected() {
        assertTrue(fail("p(a).\nq(X, Y) :- p(X).").getMessage().contains("Y"));
        assertTrue(fail("p(a). r(a). q(X) :- p(X), not r(Y).").getMessage().contains("Y"));
        assertTrue(fail("p(a). q(X) :- p(X), X != Z.").getMessage().contains("Z"));
        assertTrue(fail("p(a). q(X) :- p(X), X != _.").getMessage().contains("unsafe"));
        fail("q(X) :- not p(X).");
    }

    @Test
    void negationThroughRecursionIsRejected() {
        DatalogException e = fail("""
            move(a, b). move(b, a).
            win(X) :- move(X, Y), not win(Y).
            """);
        assertEquals(2, e.line());
        assertTrue(e.getMessage().contains("stratif"), e.getMessage());
        fail("p(a). a(X) :- p(X), not b(X). b(X) :- p(X), not a(X).");
    }

    @Test
    void negativeDependencyOutsideACycleIsFine() {
        Result r = Datalog.run("p(a). p(b). a(X) :- p(X), not b(X). b(a). ?- a(X).");
        assertEquals(List.of(Map.of("X", "b")), r.answers(0));
    }
}
