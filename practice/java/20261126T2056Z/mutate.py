#!/usr/bin/env python3
"""Mutation pass over ae.cfg: each row is (file, name, old, new); every mutant must make `mvn clean test` fail.
Restores every file on exit; asserts each pattern matched and the mutant compiled."""
import subprocess, sys, signal
D = "src/main/java/ae/cfg/"
M = [
 ("Function", "dup-label", "if (!labels.add(b.label()))", "if (false)"),
 ("Function", "dup-param", "if (!paramSet.add(p))", "if (false)"),
 ("Function", "terminator-last", "!ins.get(ins.size() - 1).isTerminator()", "false"),
 ("Function", "terminator-early", "if (ins.get(i).isTerminator()) {", "if (false) {"),
 ("Function", "unknown-label", "if (!labels.contains(t)) {", "if (false) {"),
 ("Function", "undefined-var", "if (!defined.contains(u)) {", "if (false) {"),
 ("Function", "param-defined", "new HashSet<>(paramSet)", "new HashSet<>()"),
 ("Function", "before-label", "if (label == null) {\n                throw syntax(lineNo, \"instruction before", "if (false) {\n                throw syntax(lineNo, \"instruction before"),
 ("Function", "comment-strip", "int hash = raw.indexOf('#');", "int hash = -1;"),
 ("Function", "neg-const", "(-?[0-9]+)", "([0-9]+)"),
 ("Instr", "uses-bin-order", "List.of(b.a(), b.b())", "List.of(b.b(), b.a())"),
 ("Instr", "br-targets", "List.of(b.thenLabel(), b.elseLabel())", "List.of(b.elseLabel())"),
 ("Instr", "def-copy", "case Copy c -> Optional.of(c.dst());", "case Copy c -> Optional.empty();"),
 ("Cfg", "succ-dedupe", "if (!succ.get(b).contains(s)) {", "if (true) {"),
 ("Cfg", "rpo-reverse", "new ArrayList<>(post.reversed())", "new ArrayList<>(post)"),
 ("Dominators", "frontier-entry-join", "preds.size() + (b == 0 ? 1 : 0) < 2", "preds.size() < 2"),
 ("Dominators", "frontier-reachable-preds", "if (cfg.reachable(p)) {\n                    preds.add(p);", "if (true) {\n                    preds.add(p);"),
 ("Dominators", "idom-skip-unprocessed", "if (idom[p] == -1) {", "if (false) {"),
 ("Dominators", "intersect-order", "while (number[a] > number[b]) {", "while (number[a] < number[b]) {"),
 ("Dominators", "dominates-reachable", "if (!cfg.reachable(a) || !cfg.reachable(b)) {", "if (false) {"),
 ("Dominators", "dominates-reflexive", "for (int x = b; x != -1; x = idom[x]) {", "for (int x = idom[b]; x != -1; x = idom[x]) {"),
 ("Dominators", "frontier-runner-stop", "while (runner != idom[b]) {", "while (runner != b) {"),
 ("Loops", "back-edge-test", "if (dom.dominates(h, t)) {\n                    tailsByHeader", "if (dom.dominates(t, h)) {\n                    tailsByHeader"),
 ("Loops", "body-reachable", "if (cfg.reachable(p) && body.add(p)) {", "if (body.add(p)) {"),
 ("Loops", "body-includes-header", "body.add(header);\n            ArrayDeque", "ArrayDeque"),
 ("Loops", "depth", "depth[b]++;", "depth[b] = 1;"),
 ("Loops", "reducible-back-edge-skip", "if (dom.dominates(s, b)) {\n                    continue;", "if (false) {\n                    continue;"),
 ("Loops", "reducible-cycle", "if (state[s] == 1) {\n                    return false;", "if (state[s] == 1) {\n                    return true;"),
 ("Liveness", "upward-exposed", "if (!d.contains(v)) {", "if (true) {"),
 ("Liveness", "kill-def", "i.removeAll(def.get(b));", "i.removeAll(java.util.Set.of());"),
 ("Liveness", "out-union", "o.addAll(in.get(s));", "o.addAll(use.get(s));"),
 ("Liveness", "after-def-removed", "ins.get(k).def().ifPresent(live::remove);", "ins.get(k).def().ifPresent(x -> { });"),
 ("Liveness", "after-uses", "live.addAll(ins.get(k).uses());", "if (k < 0) live.addAll(ins.get(k).uses());"),
 ("Interp", "branch-polarity", "read(env, b.cond()) != 0 ?", "read(env, b.cond()) == 0 ?"),
 ("Interp", "step-budget", "if (++steps > maxSteps)", "if (++steps >= maxSteps)"),
 ("Interp", "lt", "case LT -> a < b ? 1 : 0;", "case LT -> a <= b ? 1 : 0;"),
 ("Interp", "sub", "case SUB -> a - b;", "case SUB -> b - a;"),
 ("Interp", "argcount", "if (args.length != f.params().size())", "if (args.length < f.params().size())"),
 ("Dce", "drop-unreachable", "if (cfg.reachable(b)) {\n                kept", "if (true) {\n                kept"),
 ("Dce", "dead-test", "!after.get(i).contains(ins.def().get())", "false"),
 ("Dce", "fixpoint", "if (next.equals(cur)) {", "if (true) {"),
 ("RegAlloc", "copy-exception", "boolean copySource = i instanceof Instr.Copy c && c.src().equals(v);", "boolean copySource = false;"),
 ("RegAlloc", "self-edge", "if (!v.equals(d) && !copySource) {", "if (!copySource) {"),
 ("RegAlloc", "param-pairs", "edge(g, params.get(a), params.get(b));", "if (a < 0) edge(g, params.get(a), params.get(b));"),
 ("RegAlloc", "param-livein", "for (String v : live.liveIn(0)) {", "for (String v : java.util.Set.<String>of()) {"),
 ("RegAlloc", "simplify-threshold", "degree.get(v) < k)", "degree.get(v) <= k)"),
 ("RegAlloc", "spill-choice", "Comparator.<String>comparingInt(degree::get).thenComparing(Comparator.reverseOrder())", "Comparator.<String>comparingInt(degree::get).thenComparing(Comparator.naturalOrder())"),
 ("RegAlloc", "simplify-tiebreak", "Comparator.<String>comparingInt(degree::get).thenComparing(Comparator.naturalOrder()))\n                    .orElseGet", "Comparator.<String>comparingInt(degree::get).thenComparing(Comparator.reverseOrder()))\n                    .orElseGet"),
 ("RegAlloc", "degree-update", "degree.merge(n, -1, Integer::sum);", "degree.merge(n, 0, Integer::sum);"),
 ("RegAlloc", "lowest-free", "while (reg < k && used[reg]) {", "while (reg < k - 1 && used[reg]) {"),
 ("RegAlloc", "pop-order", "for (int s = stack.size() - 1; s >= 0; s--) {", "for (int s = 0; s < stack.size(); s++) {"),
 ("RegAlloc", "k-check", "if (k < 1) {", "if (k < 0) {"),
 ("RegAlloc", "rename-spill-check", "if (!a.spilled().isEmpty()) {", "if (false) {"),
]
files = {}
def restore(*_):
    for f, s in files.items():
        open(D + f + ".java", "w").write(s)
signal.signal(signal.SIGTERM, lambda *a: (restore(), sys.exit(143)))
only = set(sys.argv[1:])
if only:
    M = [r for r in M if r[1] in only]
survivors = []
try:
    for f, name, old, new in M:
        if f not in files:
            files[f] = open(D + f + ".java").read()
        src = files[f]
        if src.count(old) < 1:
            print("PATTERN NOT FOUND", name); survivors.append(name + "(nopattern)"); continue
        open(D + f + ".java", "w").write(src.replace(old, new, 1))
        try:
            r = subprocess.run(["mvn", "-q", "-B", "clean", "test"], capture_output=True, text=True, timeout=240)
            out, rc = r.stdout + r.stderr, r.returncode
        except subprocess.TimeoutExpired:
            out, rc = "TIMEOUT", 1
        open(D + f + ".java", "w").write(src)
        if "COMPILATION ERROR" in out:
            print("COMPILE ERROR (bad mutant)", name); survivors.append(name + "(compile)"); continue
        print(("KILLED   " if rc != 0 else "SURVIVED ") + name, flush=True)
        if rc == 0: survivors.append(name)
finally:
    restore()
print("survivors:", survivors)
sys.exit(1 if survivors else 0)
