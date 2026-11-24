package ae.prolog;

/** A compound term {@code name(arg1, ..., argN)} with N >= 1. Equality is structural; variables compare by identity. */
public final class Struct implements Term {
    private final String name;
    final Term[] args;

    public Struct(String name, Term... args) {
        if (args.length == 0) throw new IllegalArgumentException("compound needs at least one argument");
        this.name = java.util.Objects.requireNonNull(name);
        this.args = args.clone();
    }

    /** Package-private: takes ownership of the array so builders can fill it in later. */
    static Struct adopt(String name, Term[] args) {
        return new Struct(name, args, true);
    }

    private Struct(String name, Term[] args, boolean adopt) {
        this.name = name;
        this.args = args;
    }

    public String name() {
        return name;
    }

    public int arity() {
        return args.length;
    }

    public Term arg(int i) {
        return args[i];
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Term t && Terms.compare(this, t) == 0;
    }

    @Override
    public int hashCode() {
        int h = 17;
        Term t = this;
        while (true) {
            t = Terms.deref(t);
            if (t instanceof Struct s) {
                h = h * 31 + s.name.hashCode() + s.args.length;
                for (int i = 0; i < s.args.length - 1; i++) h = h * 31 + shallow(s.args[i]);
                t = s.args[s.args.length - 1];
            } else {
                return h * 31 + shallow(t);
            }
        }
    }

    private static int shallow(Term t) {
        t = Terms.deref(t);
        if (t instanceof Atom a) return a.name().hashCode();
        if (t instanceof Int i) return Long.hashCode(i.value());
        if (t instanceof Var v) return Long.hashCode(v.id);
        Struct s = (Struct) t;
        return s.name.hashCode() * 7 + s.args.length;
    }

    @Override
    public String toString() {
        return Terms.format(this);
    }
}
