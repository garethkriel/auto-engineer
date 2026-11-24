package ae.mc;

import java.util.ArrayList;
import java.util.List;

/** Small models used to exercise the checker: mutual exclusion protocols, dining philosophers, water jugs. */
public final class Examples {
    private Examples() {}

    // ---------------------------------------------------------------- water jugs

    /** Contents of two jugs. */
    public record Jugs(int a, int b) {}

    /** Classic jug puzzle: fill, empty, pour. Initial state is both empty. */
    public static Model<Jugs> jugs(int capA, int capB) {
        return new Model<>() {
            @Override
            public List<Jugs> initial() {
                return List.of(new Jugs(0, 0));
            }

            @Override
            public List<Move<Jugs>> moves(Jugs s) {
                List<Move<Jugs>> out = new ArrayList<>();
                out.add(new Move<>("fillA", new Jugs(capA, s.b())));
                out.add(new Move<>("fillB", new Jugs(s.a(), capB)));
                out.add(new Move<>("emptyA", new Jugs(0, s.b())));
                out.add(new Move<>("emptyB", new Jugs(s.a(), 0)));
                int ab = Math.min(s.a(), capB - s.b());
                out.add(new Move<>("pourAB", new Jugs(s.a() - ab, s.b() + ab)));
                int ba = Math.min(s.b(), capA - s.a());
                out.add(new Move<>("pourBA", new Jugs(s.a() + ba, s.b() - ba)));
                return out;
            }
        };
    }

    // ---------------------------------------------------------------- two-process mutual exclusion

    /** Joint state of two processes; process i has program counter pc, flag and shared turn. */
    public record Peterson(int pc0, int pc1, boolean f0, boolean f1, int turn) {
        public static final int IDLE = 0;
        public static final int CHECK = 1;
        public static final int SET_FLAG = 2;
        public static final int SET_TURN = 3;
        public static final int WAIT = 4;
        public static final int CRIT = 5;
        public static final int EXIT = 6;

        public boolean mutex() {
            return !(pc0 == CRIT && pc1 == CRIT);
        }

        int pc(int i) {
            return i == 0 ? pc0 : pc1;
        }

        boolean flag(int i) {
            return i == 0 ? f0 : f1;
        }

        Peterson with(int i, int pc, boolean flag, int newTurn) {
            return i == 0 ? new Peterson(pc, pc1, flag, f1, newTurn) : new Peterson(pc0, pc, f0, flag, newTurn);
        }
    }

    private enum Protocol { PETERSON, FLAG_ONLY, CHECK_THEN_SET }

    /** Peterson's algorithm (set flag, give way, wait). Safe and deadlock free. */
    public static Model<Peterson> peterson() {
        return mutexModel(Protocol.PETERSON);
    }

    /** Flag-only protocol (set flag, wait until the other flag is down): safe but can deadlock. */
    public static Model<Peterson> flagOnly() {
        return mutexModel(Protocol.FLAG_ONLY);
    }

    /** Check the other flag first, then set own flag: both can pass the check, so mutual exclusion fails. */
    public static Model<Peterson> checkThenSet() {
        return mutexModel(Protocol.CHECK_THEN_SET);
    }

    private static Model<Peterson> mutexModel(Protocol p) {
        return new Model<>() {
            @Override
            public List<Peterson> initial() {
                return List.of(new Peterson(Peterson.IDLE, Peterson.IDLE, false, false, 0));
            }

            @Override
            public List<Move<Peterson>> moves(Peterson s) {
                List<Move<Peterson>> out = new ArrayList<>();
                for (int i = 0; i < 2; i++) step(p, s, i, out);
                return out;
            }
        };
    }

    private static void step(Protocol p, Peterson s, int i, List<Move<Peterson>> out) {
        int other = 1 - i;
        boolean flag = s.flag(i);
        String who = "p" + i + ":";
        switch (s.pc(i)) {
            case Peterson.IDLE -> {
                int next = p == Protocol.CHECK_THEN_SET ? Peterson.CHECK : Peterson.SET_FLAG;
                out.add(new Move<>(who + "want", s.with(i, next, flag, s.turn())));
            }
            case Peterson.CHECK -> {
                // only reached by CHECK_THEN_SET: proceed when the other flag is down
                if (!s.flag(other)) out.add(new Move<>(who + "check", s.with(i, Peterson.SET_FLAG, flag, s.turn())));
            }
            case Peterson.SET_FLAG -> {
                int next = p == Protocol.PETERSON ? Peterson.SET_TURN
                        : p == Protocol.FLAG_ONLY ? Peterson.WAIT : Peterson.CRIT;
                out.add(new Move<>(who + "flag", s.with(i, next, true, s.turn())));
            }
            case Peterson.SET_TURN -> out.add(new Move<>(who + "turn", s.with(i, Peterson.WAIT, flag, other)));
            case Peterson.WAIT -> {
                boolean go = p == Protocol.PETERSON ? (!s.flag(other) || s.turn() == i) : !s.flag(other);
                if (go) out.add(new Move<>(who + "enter", s.with(i, Peterson.CRIT, flag, s.turn())));
            }
            case Peterson.CRIT -> out.add(new Move<>(who + "leave", s.with(i, Peterson.EXIT, flag, s.turn())));
            case Peterson.EXIT -> out.add(new Move<>(who + "unflag", s.with(i, Peterson.IDLE, false, s.turn())));
            default -> throw new IllegalStateException("bad pc " + s.pc(i));
        }
    }

    // ---------------------------------------------------------------- dining philosophers

    /**
     * Table of n philosophers and n forks. Philosopher i has phase 0 (thinking), 1 (holds first fork),
     * 2 (holds both, eating). {@code holder[f]} is the philosopher holding fork f or -1.
     */
    public record Table(List<Integer> phase, List<Integer> holder) {
        public Table {
            phase = List.copyOf(phase);
            holder = List.copyOf(holder);
        }

        /** Every fork is held by at most the philosophers whose phase says they hold it, and nobody shares. */
        public boolean forksConsistent() {
            int n = phase.size();
            for (int i = 0; i < n; i++) {
                int held = 0;
                for (int f = 0; f < n; f++) if (holder.get(f) == i) held++;
                if (held != phase.get(i)) return false;
            }
            return true;
        }
    }

    /**
     * n philosophers. Philosopher i's forks are i and (i+1)%n. Naive: everybody takes the left (i) fork first.
     * Ordered: take the lower-numbered fork first, which breaks the cycle.
     */
    public static Model<Table> philosophers(int n, boolean ordered) {
        if (n < 2) throw new IllegalArgumentException("n must be >= 2");
        return new Model<>() {
            @Override
            public List<Table> initial() {
                List<Integer> phase = new ArrayList<>();
                List<Integer> holder = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    phase.add(0);
                    holder.add(-1);
                }
                return List.of(new Table(phase, holder));
            }

            @Override
            public List<Move<Table>> moves(Table t) {
                List<Move<Table>> out = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    int left = i;
                    int right = (i + 1) % n;
                    int first = ordered ? Math.min(left, right) : left;
                    int second = first == left ? right : left;
                    int ph = t.phase().get(i);
                    if (ph == 0 && t.holder().get(first) == -1) out.add(take(t, i, first, 1, "first"));
                    if (ph == 1 && t.holder().get(second) == -1) out.add(take(t, i, second, 2, "second"));
                    if (ph == 2) out.add(release(t, i, first, second));
                }
                return out;
            }
        };
    }

    private static Move<Table> take(Table t, int phil, int fork, int newPhase, String which) {
        List<Integer> phase = new ArrayList<>(t.phase());
        List<Integer> holder = new ArrayList<>(t.holder());
        phase.set(phil, newPhase);
        holder.set(fork, phil);
        return new Move<>("phil" + phil + ":take-" + which, new Table(phase, holder));
    }

    private static Move<Table> release(Table t, int phil, int a, int b) {
        List<Integer> phase = new ArrayList<>(t.phase());
        List<Integer> holder = new ArrayList<>(t.holder());
        phase.set(phil, 0);
        holder.set(a, -1);
        holder.set(b, -1);
        return new Move<>("phil" + phil + ":put-down", new Table(phase, holder));
    }
}
