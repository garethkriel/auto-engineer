package ae.build;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BuilderTest {

    /** Rule that concatenates the values of the given deps with '+' and counts its runs. */
    private static Task concat(String... deps) {
        return ctx -> {
            StringBuilder sb = new StringBuilder();
            for (String d : deps) {
                if (sb.length() > 0) {
                    sb.append('+');
                }
                sb.append(ctx.need(d));
            }
            return sb.toString();
        };
    }

    @Test
    void buildsDiamondAndRunsEachRuleOnce() {
        Builder b = new Builder();
        b.input("src", "x");
        b.rule("left", concat("src"));
        b.rule("right", concat("src"));
        b.rule("top", concat("left", "right"));
        assertEquals("x+x", b.build("top"));
        // completion order: deps before dependents, "left" before "right" (first-need order)
        assertEquals(List.of("left", "right", "top"), b.lastExecuted());
    }

    @Test
    void secondBuildWithoutChangesRunsNothing() {
        Builder b = new Builder();
        b.input("src", "x");
        b.rule("a", concat("src"));
        b.rule("b", concat("a"));
        b.build("b");
        assertEquals("x", b.build("b"));
        assertEquals(List.of(), b.lastExecuted());
        assertEquals(List.of("a", "b"), b.lastReused());
    }

    @Test
    void changedInputReruns_onlyAffectedRules() {
        Builder b = new Builder();
        b.input("p", "1");
        b.input("q", "2");
        b.rule("fromP", concat("p"));
        b.rule("fromQ", concat("q"));
        b.rule("all", concat("fromP", "fromQ"));
        assertEquals("1+2", b.build("all"));
        b.input("p", "9");
        assertEquals("9+2", b.build("all"));
        assertEquals(List.of("fromP", "all"), b.lastExecuted());
        assertEquals(List.of("fromQ"), b.lastReused());
    }

    @Test
    void earlyCutoffStopsPropagationWhenValueIsUnchanged() {
        Builder b = new Builder();
        b.input("n", "4");
        // parity: 4 and 6 both give "even"
        b.rule("parity", ctx -> Integer.parseInt(ctx.need("n")) % 2 == 0 ? "even" : "odd");
        b.rule("label", ctx -> "is " + ctx.need("parity"));
        assertEquals("is even", b.build("label"));
        b.input("n", "6");
        assertEquals("is even", b.build("label"));
        assertEquals(List.of("parity"), b.lastExecuted(), "label must be reused: parity's value did not change");
        assertEquals(List.of("label"), b.lastReused());
        b.input("n", "7");
        assertEquals("is odd", b.build("label"));
        assertEquals(List.of("parity", "label"), b.lastExecuted());
    }

    @Test
    void dynamicDependenciesAreRecordedFromWhatTheTaskActuallyNeeded() {
        Builder b = new Builder();
        b.input("flag", "a");
        b.input("a", "A1");
        b.input("b", "B1");
        b.rule("pick", ctx -> ctx.need(ctx.need("flag")));
        assertEquals("A1", b.build("pick"));

        b.input("b", "B2"); // pick never needed b
        assertEquals("A1", b.build("pick"));
        assertEquals(List.of(), b.lastExecuted());

        b.input("a", "A2");
        assertEquals("A2", b.build("pick"));
        assertEquals(List.of("pick"), b.lastExecuted());

        b.input("flag", "b"); // flips the dependency
        assertEquals("B2", b.build("pick"));
        b.input("a", "A3"); // no longer relevant
        assertEquals("B2", b.build("pick"));
        assertEquals(List.of(), b.lastExecuted());
        b.input("b", "B3");
        assertEquals("B3", b.build("pick"));
        assertEquals(List.of("pick"), b.lastExecuted());
    }

    @Test
    void verificationStopsAtFirstChangedDependency() {
        // "gate" needs "mode" first and then, only for mode=full, "heavy" (which must not be touched
        // while verifying after mode changed to "off": the stale record would need heavy).
        List<String> ran = new ArrayList<>();
        Builder b = new Builder();
        b.input("mode", "full");
        b.input("h", "H");
        b.rule("heavy", ctx -> {
            ran.add("heavy");
            return ctx.need("h");
        });
        b.rule("gate", ctx -> ctx.need("mode").equals("full") ? ctx.need("heavy") : "none");
        assertEquals("H", b.build("gate"));
        ran.clear();
        b.input("mode", "off");
        assertEquals("none", b.build("gate"));
        assertEquals(List.of("gate"), b.lastExecuted());
        assertEquals(List.of(), ran, "heavy was not needed by the new run");
    }

    @Test
    void duplicateNeedsAreRecordedOnceAndReturnSameValue() {
        Builder b = new Builder();
        b.input("s", "v");
        b.rule("twice", ctx -> ctx.need("s") + ctx.need("s"));
        assertEquals("vv", b.build("twice"));
        assertEquals(List.of("s"), b.store().record("twice").depKeys());
    }

    @Test
    void failingTaskWrapsCauseAndKeepsOldRecord() {
        Builder b = new Builder();
        b.input("n", "2");
        b.rule("inv", ctx -> String.valueOf(10 / Integer.parseInt(ctx.need("n"))));
        b.rule("msg", ctx -> "got " + ctx.need("inv"));
        b.rule("other", concat("n"));
        assertEquals("got 5", b.build("msg"));

        b.input("n", "0");
        BuildException e = assertThrows(BuildException.class, () -> b.build("msg"));
        assertEquals("inv", e.key());
        assertInstanceOf(ArithmeticException.class, e.getCause());
        assertEquals("5", b.store().record("inv").value(), "old record survives a failed rerun");

        // unrelated key still builds; fixing the input recovers
        assertEquals("0", b.build("other"));
        b.input("n", "5");
        assertEquals("got 2", b.build("msg"));
    }

    @Test
    void failureIsMemoisedWithinOneRunAndSharedByDependents() {
        int[] runs = {0};
        Builder b = new Builder();
        b.rule("bad", ctx -> {
            runs[0]++;
            throw new IllegalStateException("boom");
        });
        b.rule("a", concat("bad"));
        b.rule("b", concat("bad"));
        b.rule("top", ctx -> ctx.need("a") + ctx.need("b"));
        BuildException e = assertThrows(BuildException.class, () -> b.build("top"));
        assertEquals("bad", e.key());
        assertEquals(1, runs[0]);
        assertThrows(BuildException.class, () -> b.build("top"));
        assertEquals(2, runs[0], "a new run retries the failed rule");
    }

    @Test
    void taskCanCatchFailureOfDependency() {
        Builder b = new Builder();
        b.rule("bad", ctx -> {
            throw new IllegalStateException("boom");
        });
        b.rule("safe", ctx -> {
            try {
                return ctx.need("bad");
            } catch (BuildException e) {
                return "fallback:" + e.key();
            }
        });
        assertEquals("fallback:bad", b.build("safe"));
    }

    @Test
    void cycleIsReportedWithPath() {
        Builder b = new Builder();
        b.rule("a", concat("b"));
        b.rule("b", concat("c"));
        b.rule("c", concat("a"));
        CycleException e = assertThrows(CycleException.class, () -> b.build("a"));
        assertEquals(List.of("a", "b", "c", "a"), e.path());
        // the failure is reported against the key that closed the loop
        assertEquals("a", e.key());
    }

    @Test
    void selfCycleAndCycleReachedFromOutside() {
        Builder b = new Builder();
        b.rule("self", concat("self"));
        b.rule("entry", concat("x"));
        b.rule("x", concat("y"));
        b.rule("y", concat("x"));
        assertEquals(List.of("self", "self"), assertThrows(CycleException.class, () -> b.build("self")).path());
        assertEquals(List.of("x", "y", "x"), assertThrows(CycleException.class, () -> b.build("entry")).path());
    }

    @Test
    void unknownKeyAndMissingInputAreBuildExceptions() {
        Builder b = new Builder();
        b.rule("r", concat("nope"));
        BuildException e = assertThrows(BuildException.class, () -> b.build("r"));
        assertEquals("nope", e.key());
        assertThrows(BuildException.class, () -> b.build("nope"));
    }

    @Test
    void removedDependencyForcesRerunInsteadOfFailing() {
        Builder b = new Builder();
        b.input("old", "1");
        b.rule("r", concat("old"));
        assertEquals("1", b.build("r"));
        // a new Builder sharing the store, where "old" no longer exists and r changed shape
        Builder b2 = new Builder(b.store());
        b2.input("fresh", "2");
        b2.rule("r", concat("fresh"));
        assertEquals("2", b2.build("r"));
        assertEquals(List.of("r"), b2.lastExecuted());
    }

    @Test
    void keyCannotBeBothInputAndRule_andNullsRejected() {
        Builder b = new Builder();
        b.input("k", "v");
        assertThrows(IllegalArgumentException.class, () -> b.rule("k", concat("k")));
        b.rule("r", concat("k"));
        assertThrows(IllegalArgumentException.class, () -> b.input("r", "v"));
        assertThrows(NullPointerException.class, () -> b.input("z", null));
        assertThrows(NullPointerException.class, () -> b.rule("z", null));
        assertThrows(NullPointerException.class, () -> b.build((String) null));
    }

    @Test
    void nullResultIsAnError_andContextExpiresAfterTask() {
        Builder b = new Builder();
        b.rule("nul", ctx -> null);
        assertInstanceOf(IllegalStateException.class, assertThrows(BuildException.class, () -> b.build("nul")).getCause());

        Context[] leaked = new Context[1];
        b.input("s", "v");
        b.rule("leak", ctx -> {
            leaked[0] = ctx;
            return ctx.need("s");
        });
        b.build("leak");
        assertThrows(IllegalStateException.class, () -> leaked[0].need("s"));
    }

    @Test
    void pruneDropsUnreachableRecords() {
        Builder b = new Builder();
        b.input("i", "1");
        b.rule("keep", concat("i"));
        b.rule("deep", concat("keep"));
        b.rule("gone", concat("i"));
        b.build("deep");
        b.build("gone");
        assertEquals(1, b.prune(List.of("deep")));
        assertNull(b.store().record("gone"));
        assertNotNull(b.store().record("keep"));
        assertEquals(0, b.prune(List.of("deep")));
    }

    @Test
    void buildSeveralKeysInOneRunSharesWork() {
        Builder b = new Builder();
        b.input("i", "1");
        b.rule("shared", concat("i"));
        b.rule("a", concat("shared"));
        b.rule("c", concat("shared"));
        assertEquals(List.of("1", "1"), b.build(List.of("a", "c")));
        assertEquals(List.of("shared", "a", "c"), b.lastExecuted());
    }
}
