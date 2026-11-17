# j32-fsm: Hierarchical state machine

Java 21, standard library only. Put your solution under `bench-solutions/java/j32-fsm/src/main/java/`, using exactly the package and public signatures below. CI adds the hidden JUnit 5 tests, and the task passes only if every one of them passes.

```java
package bench.fsm;
public final class StateMachine {
    public static Builder builder();
    public String current();
    public java.util.List<String> active();
    public boolean fire(String event);
    public java.util.List<String> log();

    public static final class Builder {
        public Builder state(String name);
        public Builder state(String name, String parent);
        public Builder initial(String state);
        public Builder history(String composite);
        public Builder transition(String source, String event, String target);
        public Builder transition(String source, String event, String target, java.util.function.BooleanSupplier guard);
        public StateMachine build();
    }
}
```

## Defining a machine

- `state(name)` declares a top-level state. `state(name, parent)` declares a child of an already declared state. A state with children is a *composite*; a state without children is a *leaf*.
- `initial(s)` marks `s` as the initial child of its parent, or as the initial top-level state if `s` is top-level.
- `history(c)` gives composite `c` deep history (see below).
- `transition(source, event, target[, guard])` adds a transition. `source` and `target` may be any states, leaf or composite. Several transitions may share a source and event.
- **Errors:** each of these throws `IllegalArgumentException` from the builder method itself:
  - a null or empty name, or a duplicate name;
  - an undeclared parent, source, target or `initial`/`history` state;
  - a second `initial` for the same parent, or a second top-level `initial`;
  - a null event or guard.
- `build()` throws `IllegalStateException` if there is no top-level initial state, a composite has no initial child, or a leaf was given history.

## Running it

- The machine always has one active leaf. `current()` is its name. `active()` is the path from its top-level ancestor down to it. Both lists returned by `active()` and `log()` are unmodifiable snapshots.
- **Log:** every state entered or exited is appended to the log as `"enter:NAME"` or `"exit:NAME"`, in the order it happens.
- **Entering a state** means logging its entry. If it's a composite, its initial child is then entered, repeatedly, until a leaf is reached. The exception is history (below).
- **Start:** `build()` enters the top-level initial state, so the first log entries come from that.
- **`fire(event)`:**
  - It looks for a transition for `event`, first on the active leaf, then on each ancestor in turn, innermost first.
  - On each state, transitions are tried in the order they were declared. A transition applies if it has no guard or its guard returns `true`. Guards are evaluated only until one transition applies.
  - The first transition that applies is taken, and `fire` returns `true`. If none applies anywhere, `fire` returns `false` and nothing changes.
  - A null event throws `IllegalArgumentException`.
- **Taking a transition** from source `S`, the state that declared it, to target `T`:
  1. Let `A` be the deepest common ancestor of `S` and `T`, where every state counts as its own ancestor. If `A` is `S` or `T`, use `A`'s parent instead. That parent may be "above the top level", in which case every active state is exited.
  2. Exit states from the active leaf upwards, innermost first, stopping before `A`.
  3. Enter states from just below `A` down to `T`, outermost first.
  4. If `T` is a composite, continue entering as described above.
  
  So self-transitions, and transitions to an ancestor or descendant of `S`, exit and re-enter `S`.
- **Deep history:** when a composite with history is exited, it remembers the active leaf at that moment. Later, when that composite is entered as a transition's target, or while descending into it, it re-enters the states from below it down to the remembered leaf instead of following initial children. If it has never been exited, the initial children are used. A transition whose target is a state *inside* the composite goes to that target as usual.
