#!/usr/bin/env python3
"""Mutation pass: one single-line mutant at a time; restore the file afterwards (also on signals).

Run from the round folder: python3 mutate.py
KILLED = `mvn -q -B clean test` fails or times out. Each pattern must match exactly once and compile.
"""
import pathlib, signal, subprocess, sys

SRC = pathlib.Path("src/main/java/ae/linalg")
MUTANTS = [
    ("Rational.java", "if (dd.signum() < 0) {", "if (dd.signum() > 5) {", "sign not moved to numerator"),
    ("Rational.java", "BigInteger g = n.gcd(d);", "BigInteger g = BigInteger.ONE;", "no gcd reduction"),
    ("Rational.java", "return of(num.multiply(o.den).subtract(o.num.multiply(den)), den.multiply(o.den));", "return of(num.multiply(o.den).subtract(o.num), den.multiply(o.den));", "sub forgets den"),
    ("Rational.java", "if ((k & 1) == 1) {\n                result = result.mul(base);", "if ((k & 1) == 0) {\n                result = result.mul(base);", "pow parity flipped"),
    ("Rational.java", "return num.multiply(o.den).compareTo(o.num.multiply(den));", "return num.compareTo(o.num);", "compare numerators only"),
    ("Matrix.java", "s = s.add(a[i][k].mul(o.a[k][j]));", "s = s.add(a[i][k].mul(o.a[j % o.rows][j]));", "product wrong index"),
    ("Matrix.java", "g[j][i] = a[i][j];", "g[j][i] = a[j % rows][i % cols];", "transpose wrong"),
    ("Matrix.java", "if (raw[i].length != raw[0].length) {\n                throw new IllegalArgumentException(\"ragged rows\");\n            }\n            g[i] = new Rational", "if (raw[i].length < raw[0].length) {\n                throw new IllegalArgumentException(\"ragged rows\");\n            }\n            g[i] = new Rational", "long ragged check weakened"),
    ("Matrix.java", "Matrix result = identity(rows);", "Matrix result = this;", "pow starts from this"),
    ("Matrix.java", "g[i] = raw[i].clone();", "g[i] = raw[i];", "ofRational aliases input"),
    ("Elimination.java", "for (int i = 0; i < m.rows(); i++) {\n                if (i != r && !g[i][c].isZero()) {", "for (int i = r + 1; i < m.rows(); i++) {\n                if (i != r && !g[i][c].isZero()) {", "rref forward only"),
    ("Elimination.java", "Rational inv = g[r][c].inverse();", "Rational inv = Rational.ONE;", "pivot not scaled"),
    ("Elimination.java", "det = det.negate();", "det = det.add(Rational.ZERO);", "det swap sign ignored"),
    ("Elimination.java", "if (r.pivots().size() < n || r.pivots().get(n - 1) >= n) {", "if (r.pivots().size() < n) {", "inverse accepts pivot in identity half"),
    ("Elimination.java", "x[r.pivots().get(row)] = r.matrix().get(row, f).negate();", "x[r.pivots().get(row)] = r.matrix().get(row, f);", "nullspace sign"),
    ("Elimination.java", "cols.add(m.col(p));", "cols.add(rref(m).matrix().col(p));", "column space from rref columns"),
    ("Elimination.java", "if (r.pivots().contains(n)) {", "if (false) {", "inconsistency undetected"),
    ("Elimination.java", "if (r.pivots().size() == n) {", "if (r.pivots().size() >= n + 5) {", "unique never reported"),
    ("Bareiss.java", "sign = -sign;", "sign = sign + 0;", "bareiss swap sign"),
    ("Bareiss.java", ".divide(prev);", ".divide(BigInteger.ONE);", "bareiss missing exact division"),
    ("Lu.java", "sign = -sign;", "sign = sign + 0;", "lu swap sign"),
    ("Lu.java", "t = l[p];\n                l[p] = l[c];\n                l[c] = t;", "t = l[p];", "lu does not swap L rows"),
    ("Lu.java", "int pi = perm[p];\n                perm[p] = perm[c];\n                perm[c] = pi;", "int pi = perm[p];", "lu permutation not recorded"),
    ("Lu.java", "if (u.get(i, i).isZero()) {", "if (false) {", "lu solve accepts singular"),
    ("Lu.java", "s = s.sub(l.get(i, j).mul(y[j]));", "s = s.add(l.get(i, j).mul(y[j]));", "forward substitution sign"),
    ("CharPoly.java", "c[n - k] = t.negate().div(Rational.of(k));", "c[n - k] = t.negate();", "faddeev missing 1/k"),
    ("CharPoly.java", "result.put(Rational.ZERO, low);", "result.put(Rational.ZERO, 1);", "zero multiplicity capped"),
    ("CharPoly.java", "candidates.add(Rational.of(-p, q));", "candidates.add(Rational.of(p, q));", "negative candidates missing"),
    ("CharPoly.java", "while (poly.size() > 1) {", "if (poly.size() > 1) {", "multiplicity capped at 1"),
    ("CharPoly.java", "carry = poly.get(k).add(carry.mul(r));", "carry = poly.get(k).add(carry);", "synthetic division ignores root"),
    ("CharPoly.java", "return Elimination.nullspace(a.minus(Matrix.identity(a.rows()).times(lambda)));", "return Elimination.nullspace(a.plus(Matrix.identity(a.rows()).times(lambda)));", "eigenspace uses A + lambda I"),
    ("GramSchmidt.java", "Rational f = dot(v, u).div(dot(u, u));\n                for (int i = 0; i < r.length; i++) {", "Rational f = dot(r, u).div(dot(u, u));\n                for (int i = 0; i < r.length; i++) {", "modified GS (equivalent?)"),
    ("GramSchmidt.java", "if (!zero) {", "if (true) {", "zero vectors kept"),
    ("GramSchmidt.java", "Rational f = dot(v, u).div(dot(u, u));\n            for (int i = 0; i < p.length; i++) {", "Rational f = dot(v, u);\n            for (int i = 0; i < p.length; i++) {", "projection not normalised"),
]

def run():
    try:
        return subprocess.run(["mvn", "-q", "-B", "clean", "test"], capture_output=True, text=True, timeout=240).returncode == 0
    except subprocess.TimeoutExpired:
        return False

current = None
def restore(*_):
    if current:
        current[0].write_text(current[1])
    if _:
        sys.exit(130)
signal.signal(signal.SIGTERM, restore)
signal.signal(signal.SIGINT, restore)

def main():
    global current
    if not run():
        print("baseline is not green"); sys.exit(2)
    survived = []
    for fname, old, new, name in MUTANTS:
        path = SRC / fname
        original = path.read_text()
        if original.count(old) != 1:
            print(f"PATTERN-MISS {name}: matched {original.count(old)} times"); sys.exit(3)
        current = (path, original)
        try:
            path.write_text(original.replace(old, new))
            if subprocess.run(["mvn", "-q", "-B", "-DskipTests", "compile"], capture_output=True, text=True).returncode != 0:
                print(f"NOT-COMPILED {name}"); survived.append(name + " (does not compile)")
                continue
            ok = run()
        finally:
            path.write_text(original)
            current = None
        print(("SURVIVED " if ok else "KILLED   ") + name, flush=True)
        if ok:
            survived.append(name)
    print(f"{len(MUTANTS) - len(survived)}/{len(MUTANTS)} killed; survivors: {survived}")

main()
