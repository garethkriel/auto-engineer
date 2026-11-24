#!/usr/bin/env python3
"""Mutation check: apply one single-rule mutation at a time, run the suite, restore the file.
Run from the round folder: python3 mutate.py. A mutant is killed when `mvn test` fails."""
import subprocess, sys
from pathlib import Path

M = Path("src/main/java/ae/chord")
MUTANTS = [
    ("Ring.java", "dx > 0 && dx < dist(a, b)", "dx >= 0 && dx < dist(a, b)"),
    ("Ring.java", "dx > 0 && dx <= dist(a, b)", "dx > 0 && dx < dist(a, b)"),
    ("Ring.java", "return x != a;", "return true;"),
    ("Ring.java", "return top >>> (64 - bits);", "return top & (size - 1);"),
    ("ChordNode.java", "if (f != null && ring.inOpen(f, id, key))", "if (f != null && ring.inHalfOpen(f, id, key))"),
    ("ChordNode.java", "for (int i = fingers.length - 1; i >= 0; i--) {", "for (int i = 0; i < fingers.length; i++) {"),
    ("ChordNode.java", "if (ring.inHalfOpen(target, cur, s))", "if (ring.inOpen(target, cur, s))"),
    ("ChordNode.java", "ring.inOpen(candidate, pred, id)", "ring.inOpen(pred, candidate, id)"),
    ("ChordNode.java", "if (pred != null && !net.ping(pred)) {\n            pred = null;", "if (pred != null && !net.ping(pred)) {\n            pred = pred;"),
    ("ChordNode.java", "if (copies >= listSize) {", "if (copies >= listSize - 1) {"),
    ("ChordNode.java", "if (!on.replicaSet().contains(id)) {", "if (true) {"),
    ("ChordNode.java", "old.version() < v.version()", "old.version() > v.version()"),
    ("ChordNode.java", "findSuccessor(ring.fingerStart(id, i)).owner()", "findSuccessor(ring.fingerStart(id, i) + 1).owner()"),
    ("ChordNode.java", "sn.pred = (pred != null", "sn.pred = null; if (false) sn.pred = (pred != null"),
    ("ChordNode.java", "if (ring.inHalfOpen(e.getKey(), p, id)) {", "if (false) {"),
    ("ChordNode.java", "fresh.size() < listSize)", "fresh.size() < listSize + 1)"),
    ("ChordNode.java", "pn.succ.remove(Long.valueOf(id));", "pn.succ.size();"),
    ("ChordNetwork.java", "v.version() > best.version()", "v.version() < best.version()"),
    ("ChordNetwork.java", "n.handOver();", "n.checkPredecessor();"),
    ("ChordNetwork.java", "node(s).storeLocal(k, v);", "node(s).stored(k);"),
]

killed = survived = invalid = 0
for fname, a, b in MUTANTS:
    path = M / fname
    original = path.read_text()
    try:
        if a not in original:
            print("PATTERN NOT FOUND:", fname, repr(a)); invalid += 1; continue
        path.write_text(original.replace(a, b, 1))
        try:
            r = subprocess.run(["mvn", "-q", "-B", "test"], capture_output=True, text=True, timeout=240)
            code, out = r.returncode, r.stdout + r.stderr
        except subprocess.TimeoutExpired:
            code, out = 1, "TIMEOUT"
    finally:
        path.write_text(original)
    if "COMPILATION ERROR" in out:
        print("COMPILE ERROR (invalid mutant):", fname, repr(a)); invalid += 1
    elif code != 0:
        print("KILLED:  ", fname, repr(a)); killed += 1
    else:
        print("SURVIVED:", fname, repr(a)); survived += 1
print(f"killed={killed} survived={survived} invalid={invalid}")
sys.exit(0)
