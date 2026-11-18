package ae.deps;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Backtracking dependency resolver. Requirement names are processed in first-seen order; for each the candidate
 * versions (preferred one first, then highest first) are tried with forward checking on the new dependencies.
 */
public final class Resolver {
    private static final String ROOT = "<root>";
    private static final int DEFAULT_BUDGET = 200_000;

    private record Req(String from, Constraint constraint) {}

    private final Registry registry;
    private final int budget;

    /** Resolver with the default search budget. */
    public Resolver(Registry registry) {
        this(registry, DEFAULT_BUDGET);
    }

    /** Resolver that gives up after {@code budget} candidate attempts. */
    public Resolver(Registry registry, int budget) {
        this.registry = Objects.requireNonNull(registry, "registry");
        if (budget < 1) throw new IllegalArgumentException("budget must be positive");
        this.budget = budget;
    }

    /** Resolves {@code roots} without preferences. */
    public Resolution resolve(Map<String, Constraint> roots) throws ResolutionException {
        return resolve(roots, Map.of());
    }

    /**
     * Resolves {@code roots}; a package listed in {@code preferred} keeps that version whenever it satisfies every
     * constraint on it and still leads to a solution.
     */
    public Resolution resolve(Map<String, Constraint> roots, Map<String, Version> preferred) throws ResolutionException {
        Objects.requireNonNull(roots, "roots");
        Objects.requireNonNull(preferred, "preferred");
        Search s = new Search(preferred);
        Map<String, List<Req>> reqs = new LinkedHashMap<>();
        for (var e : new TreeMap<>(roots).entrySet()) {
            reqs.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(new Req(ROOT, e.getValue()));
        }
        for (String name : reqs.keySet()) {
            if (s.candidates(name, reqs.get(name)).isEmpty()) throw new ResolutionException(s.noCandidates(name, reqs.get(name)));
        }
        Map<String, Version> chosen = s.solve(new LinkedHashMap<>(), reqs);
        if (chosen == null) throw new ResolutionException(s.failure == null ? "no solution" : s.failure);
        return new Resolution(chosen, installOrder(chosen, roots.keySet()));
    }

    private final class Search {
        private final Map<String, Version> preferred;
        private int attempts;
        private int deepest = -1;
        String failure;

        Search(Map<String, Version> preferred) {
            this.preferred = preferred;
        }

        private void fail(int depth, String message) {
            if (depth > deepest) {
                deepest = depth;
                failure = message;
            }
        }

        private static String describe(List<Req> reqs) {
            List<String> parts = new ArrayList<>();
            for (Req r : reqs) parts.add(r.constraint() + " (required by " + r.from() + ")");
            return String.join(", ", parts);
        }

        /** Candidates for {@code name}: versions allowed by every requirement, preferred first then highest first. */
        List<Version> candidates(String name, List<Req> reqs) {
            List<Version> out = new ArrayList<>();
            Version pref = preferred.get(name);
            for (Version v : registry.versions(name)) {
                boolean ok = true;
                for (Req r : reqs) ok &= r.constraint().matches(v);
                if (!ok) continue;
                if (v.equals(pref)) out.add(0, v);
                else out.add(v);
            }
            return out;
        }

        /** Explanation for a package that has no usable version. */
        String noCandidates(String name, List<Req> reqs) {
            String why = registry.versions(name).isEmpty() ? "unknown package " + name + ": " : "no version of " + name + " satisfies: ";
            return why + describe(reqs);
        }

        Map<String, Version> solve(Map<String, Version> chosen, Map<String, List<Req>> reqs) throws ResolutionException {
            String next = null;
            for (String n : reqs.keySet()) {
                if (!chosen.containsKey(n)) {
                    next = n;
                    break;
                }
            }
            if (next == null) return chosen;
            List<Req> mine = reqs.get(next);
            List<Version> cands = candidates(next, mine);
            if (cands.isEmpty()) {
                fail(chosen.size(), noCandidates(next, mine));
                return null;
            }
            for (Version v : cands) {
                if (++attempts > budget) throw new ResolutionException("search budget exceeded after " + budget + " attempts");
                Package pkg = registry.find(next, v).orElseThrow();
                Map<String, Version> chosen2 = new LinkedHashMap<>(chosen);
                chosen2.put(next, v);
                Map<String, List<Req>> reqs2 = new LinkedHashMap<>();
                reqs.forEach((k, l) -> reqs2.put(k, new ArrayList<>(l)));
                boolean ok = true;
                for (var d : pkg.deps().entrySet()) {
                    String dn = d.getKey();
                    Req req = new Req(next + "@" + v, d.getValue());
                    List<Req> list = reqs2.computeIfAbsent(dn, k -> new ArrayList<>());
                    list.add(req);
                    Version have = chosen2.get(dn);
                    if (have != null) {
                        if (!req.constraint().matches(have)) {
                            fail(chosen2.size(), dn + "@" + have + " is chosen but " + req.from() + " requires "
                                + req.constraint() + " (all requirements: " + describe(list) + ")");
                            ok = false;
                            break;
                        }
                    } else if (candidates(dn, list).isEmpty()) {
                        fail(chosen2.size(), noCandidates(dn, list));
                        ok = false;
                        break;
                    }
                }
                if (!ok) continue;
                Map<String, Version> result = solve(chosen2, reqs2);
                if (result != null) return result;
            }
            return null;
        }
    }

    private List<String> installOrder(Map<String, Version> chosen, Set<String> rootNames) {
        List<String> order = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String r : new TreeSet<>(rootNames)) visit(r, chosen, seen, order);
        return order;
    }

    private void visit(String name, Map<String, Version> chosen, Set<String> seen, List<String> order) {
        if (!seen.add(name)) return;
        for (String d : new TreeSet<>(registry.find(name, chosen.get(name)).orElseThrow().deps().keySet())) {
            visit(d, chosen, seen, order);
        }
        order.add(name);
    }
}
