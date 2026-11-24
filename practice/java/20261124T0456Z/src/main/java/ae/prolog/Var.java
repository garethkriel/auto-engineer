package ae.prolog;

import java.util.concurrent.atomic.AtomicLong;

/** A logic variable. {@code ref} is set by unification and undone through the trail. Identity semantics. */
public final class Var implements Term {
    private static final AtomicLong COUNTER = new AtomicLong();

    final String name;
    final long id = COUNTER.incrementAndGet();
    Term ref;

    public Var(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return "_G" + id;
    }
}
