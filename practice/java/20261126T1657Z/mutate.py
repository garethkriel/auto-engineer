#!/usr/bin/env python3
"""Mutation pass: apply one single-line mutant at a time, run the tests, restore the file.

Run from the round folder: python3 mutate.py
A mutant is KILLED when `mvn -q -B clean test` fails (or times out); SURVIVED otherwise.
Each pattern must match exactly once and the unmutated build must be green first.
"""
import pathlib, subprocess, sys

SRC = pathlib.Path("src/main/java/ae/lr")
MUTANTS = [
    ("Grammar.java", "if (!nonterminals.contains(s) || !nullable.contains(s)) {", "if (!nonterminals.contains(s)) {", "firstOfSequence ignores nullable"),
    ("Grammar.java", "follow.get(start).add(EOF);", "follow.get(start).size();", "start FOLLOW lacks $"),
    ("Grammar.java", "changed |= target.addAll(follow.get(p.lhs()));", "changed |= false;", "FOLLOW not propagated"),
    ("Grammar.java", "if (!nullable.contains(s)) {\n                        break;", "if (false) {\n                        break;", "FIRST ignores non-nullable stop"),
    ("Grammar.java", "if (eps && !rhs.isEmpty()) {", "if (false) {", "eps mixed accepted"),
    ("Grammar.java", "if (nonterminals.contains(s) && !prod.contains(s)) {", "if (false) {", "productive ignores rhs"),
    ("Grammar.java", "if (nonterminals.contains(s) && seen.add(s)) {", "if (false) {", "reachable stops at start"),
    ("LrTable.java", "mode == Mode.SLR ? followOf(p) : Set.of(it.la())", "Set.of(it.la())", "SLR uses item lookahead"),
    ("LrTable.java", "grammar.firstOfSequence(rhs, it.dot() + 1, it.la())", "grammar.firstOfSequence(rhs, it.dot(), it.la())", "closure lookahead from wrong suffix"),
    ("LrTable.java", "mergedStates.get(id).addAll(states.get(s));", "if (mergedStates.get(id).isEmpty()) { mergedStates.get(id).addAll(states.get(s)); }", "LALR drops lookahead union"),
    ("LrTable.java", "return 1 + r.production();", "return 1000 - r.production();", "reduces sorted descending"),
    ("LrTable.java", "shift ? Conflict.Kind.SHIFT_REDUCE : Conflict.Kind.REDUCE_REDUCE", "Conflict.Kind.SHIFT_REDUCE", "kind always S/R"),
    ("LrTable.java", "new Item(0, 0, withLa ? Grammar.EOF : \"\")", "new Item(0, 0, \"\")", "LR1 start lookahead empty"),
    ("LrTable.java", "if (i == core.dot()) {", "if (i == core.dot() + 1) {", "describe dot misplaced"),
    ("LrTable.java", "if (it.dot() < p.rhs().size()) {\n                    continue;", "if (it.dot() < p.rhs().size() - 1) {\n                    continue;", "reduce on almost-complete items"),
    ("LrParser.java", "int k = p.rhs().size();", "int k = Math.max(0, p.rhs().size() - 1);", "pop one too few"),
    ("LrParser.java", "pos < tokens.size() ? tokens.get(pos)", "pos <= tokens.size() ? tokens.get(pos)", "lookahead off by one"),
    ("LrParser.java", "if (Grammar.EOF.equals(t)) {", "if (false) {", "$ token accepted"),
    ("LrParser.java", "if (!table.conflicts().isEmpty()) {", "if (false) {", "conflicted table accepted"),
    ("Tree.java", "return new Tree(token, List.of(), -1);", "return new Tree(token, List.of(), 0);", "leaf production 0"),
    ("Tree.java", "for (int i = t.children.size() - 1; i >= 0; i--) {\n                    stack.push(t.children.get(i));\n                }", "for (int i = 0; i < t.children.size(); i++) {\n                    stack.push(t.children.get(i));\n                }", "yield reversed"),
]

def run():
    try:
        r = subprocess.run(["mvn", "-q", "-B", "clean", "test"], capture_output=True, text=True, timeout=240)
        return r.returncode == 0
    except subprocess.TimeoutExpired:
        return False

def main():
    if not run():
        print("baseline is not green"); sys.exit(2)
    survived = []
    for fname, old, new, name in MUTANTS:
        path = SRC / fname
        original = path.read_text()
        if original.count(old) != 1:
            print(f"PATTERN-MISS {name}: matched {original.count(old)} times"); sys.exit(3)
        try:
            path.write_text(original.replace(old, new))
            compiled = subprocess.run(["mvn", "-q", "-B", "-DskipTests", "compile"], capture_output=True, text=True).returncode == 0
            if not compiled:
                print(f"NOT-COMPILED {name}"); survived.append(name + " (does not compile)")
                continue
            ok = run()
        finally:
            path.write_text(original)
        print(("SURVIVED " if ok else "KILLED   ") + name, flush=True)
        if ok:
            survived.append(name)
    print(f"{len(MUTANTS) - len(survived)}/{len(MUTANTS)} killed; survivors: {survived}")

main()
