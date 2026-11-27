#!/usr/bin/env python3
"""Mutation pass for the ae.wasm round.

Reads mutants.tsv (label TAB file TAB pattern TAB replacement; the two-character sequence \\n is a newline), applies
each mutant to a private copy of the project, runs `mvn -q -B clean test` there with a timeout and reports
KILLED / SURVIVED / TIMEOUT(killed) / NOMATCH / COMPILE-FAIL. The real sources are never modified.

usage: python3 mutate.py [workers] [label-substring]
"""
import concurrent.futures
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, "src", "main", "java", "ae", "wasm")
TIMEOUT = 240


def load():
    rows = []
    with open(os.path.join(HERE, "mutants.tsv"), encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            label, fname, pat, rep = line.split("\t")
            rows.append((label, fname, pat.replace("\\n", "\n"), rep.replace("\\n", "\n")))
    return rows


def run_one(row):
    label, fname, pat, rep = row
    work = tempfile.mkdtemp(prefix="mut-")
    try:
        shutil.copytree(ROOT, os.path.join(work, "p"), ignore=shutil.ignore_patterns("target", "mutants"))
        path = os.path.join(work, "p", "src", "main", "java", "ae", "wasm", fname)
        text = open(path, encoding="utf-8").read()
        n = text.count(pat)
        if n != 1:
            return label, "NOMATCH(%d)" % n, ""
        open(path, "w", encoding="utf-8").write(text.replace(pat, rep))
        try:
            p = subprocess.run(["mvn", "-q", "-B", "-o", "clean", "test"], cwd=os.path.join(work, "p"),
                               capture_output=True, text=True, timeout=TIMEOUT)
        except subprocess.TimeoutExpired:
            return label, "TIMEOUT(killed)", ""
        out = p.stdout + p.stderr
        if p.returncode == 0:
            return label, "SURVIVED", ""
        if "COMPILATION ERROR" in out:
            return label, "COMPILE-FAIL", ""
        failed = sorted({ln.split("ae.wasm.")[1].split(":")[0].split(" ")[0] for ln in out.splitlines()
                         if ln.startswith("[ERROR]   ") and "ae.wasm." in ln})[:3]
        return label, "KILLED", ",".join(failed)
    finally:
        shutil.rmtree(work, ignore_errors=True)


def main():
    workers = int(sys.argv[1]) if len(sys.argv) > 1 else 4
    only = sys.argv[2] if len(sys.argv) > 2 else ""
    rows = [r for r in load() if only in r[0]]
    counts = {}
    with concurrent.futures.ThreadPoolExecutor(workers) as ex:
        for label, status, by in ex.map(run_one, rows):
            key = status.split("(")[0]
            counts[key] = counts.get(key, 0) + 1
            print("%-28s %-16s %s" % (label, status, by), flush=True)
    print("TOTAL", len(rows), counts)


if __name__ == "__main__":
    main()
