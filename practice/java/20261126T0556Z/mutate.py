#!/usr/bin/env python3
"""Mutation pass: apply one textual mutant at a time to src/main, run the tests, restore the file."""
import subprocess, sys, pathlib
M = pathlib.Path('src/main/java/ae/ot')
MUTANTS = [
 ('TextOp.java', 'if (last >= 1 && parts.get(last - 1) instanceof Insert i) {', 'if (false) {'),
 ('TextOp.java', 'parts.add(last, new Insert(text));', 'parts.add(new Insert(text));'),
 ('TextOp.java', 'if (n == 0) return this;\n            int last = parts.size() - 1;\n            if (last >= 0 && parts.get(last) instanceof Retain', 'int last = parts.size() - 1;\n            if (last >= 0 && parts.get(last) instanceof Retain'),
 ('TextOp.java', 'if (doc.length() != baseLength) {\n            throw new IllegalArgumentException("document length " + doc.length()', 'if (false) {\n            throw new IllegalArgumentException("document length " + doc.length()'),
 ('TextOp.java', 'case Insert i -> b.delete(i.text().length());', 'case Insert i -> b.retain(i.text().length());'),
 ('TextOp.java', 'case Delete d -> { b.insert(docBefore.substring(pos, pos + d.n())); pos += d.n(); }', 'case Delete d -> { b.insert(docBefore.substring(pos, pos + d.n())); }'),
 ('TextOp.java', 'if (targetLength != next.baseLength) {', 'if (false) {'),
 ('TextOp.java', '            if (a instanceof Delete) {\n                int n = c1.remaining();\n                out.delete(n);', '            if (a instanceof Delete) {\n                int n = c1.remaining();\n                out.retain(n);'),
 ('TextOp.java', '            } else if (a instanceof Retain && b instanceof Delete) {\n                out.delete(m);', '            } else if (a instanceof Retain && b instanceof Delete) {\n                out.retain(m);'),
 ('TextOp.java', '            } else if (a instanceof Insert && b instanceof Retain) {\n                out.insert(c1.text(m));', '            } else if (a instanceof Insert && b instanceof Retain) {\n                out.insert(c1.text(1));'),
 ('TextOp.java', 'if (a.baseLength != b.baseLength) {', 'if (false) {'),
 ('TextOp.java', '            Component x = ca.current();\n            Component y = cb.current();\n            if (x instanceof Insert) {', '            Component x = ca.current();\n            Component y = cb.current();\n            if (x instanceof Insert && !(y instanceof Insert && false)) {\n                if (y instanceof Insert) { int n = cb.remaining(); left.retain(n); right.insert(cb.text(n)); cb.take(n); continue; }'),
 ('TextOp.java', '            } else if (x instanceof Delete && y instanceof Retain) {\n                left.delete(m);', '            } else if (x instanceof Delete && y instanceof Retain) {\n                left.retain(m);'),
 ('TextOp.java', '            } else if (x instanceof Retain && y instanceof Delete) {\n                right.delete(m);', '            } else if (x instanceof Retain && y instanceof Delete) {\n                right.retain(m);'),
 ('TextOp.java', 'if (base == index && !advanceOverInsert) return out;', 'if (base == index) return out;'),
 ('TextOp.java', 'if (index < base + r.n()) return out + (index - base);', 'if (index <= base + r.n()) return out + (index - base);'),
 ('TextOp.java', 'if (index < base + d.n()) return out;', 'if (index <= base + d.n()) return out;'),
 ('TextOp.java', 'if (index < 0 || index > baseLength) {', 'if (index < 0 || index > baseLength + 1) {'),
 ('OtServer.java', 'current = TextOp.transform(history.get(i), current).right();', 'current = TextOp.transform(current, history.get(i)).left();'),
 ('OtServer.java', 'if (baseRevision < 0 || baseRevision > history.size()) {', 'if (baseRevision < 0 || baseRevision > history.size() + 1) {'),
 ('OtServer.java', 'if (op.baseLength() != lengths.get(baseRevision)) {', 'if (false) {'),
 ('OtServer.java', 'for (int i = baseRevision; i < history.size(); i++) {', 'for (int i = baseRevision + 1; i < history.size(); i++) {'),
 ('OtClient.java', 'TextOp.Pair p = TextOp.transform(toApply, outstanding);   // remote op has priority', 'TextOp.Pair p = TextOp.transform(outstanding, toApply); p = new TextOp.Pair(p.right(), p.left());'),
 ('OtClient.java', 'buffer = buffer == null ? op : buffer.compose(op);', 'buffer = op;'),
 ('OtClient.java', 'return Optional.of(new Message(revision, outstanding));\n    }', 'return Optional.of(new Message(revision - 1, outstanding));\n    }'),
 ('OtClient.java', 'if (newRevision != revision + 1) {\n            throw new IllegalStateException("ack revision', 'if (false) {\n            throw new IllegalStateException("ack revision'),
 ('OtClient.java', 'if (newRevision != revision + 1) {\n            throw new IllegalStateException("remote revision', 'if (false) {\n            throw new IllegalStateException("remote revision'),
 ('OtClient.java', '                toApply = q.left();\n                newBuffer = q.right();', '                newBuffer = q.right();'),
 ('OtClient.java', 'if (outstanding == null) throw new IllegalStateException("ack with nothing in flight");', ''),
 ('OtClient.java', 'String next = op.apply(document);   // validates the length before any state changes\n        document = next;', 'document = op.apply(document);'),
 ('OtSimulation.java', 'if (m.baseRevision() < server.revision()) stale++;', ''),
 ('OtSimulation.java', 'if (!replay.equals(server.document())) bad.add', 'if (false) bad.add'),
 ('OtSimulation.java', 'if (!expected.equals(client.document())) bad.add', 'if (false) bad.add'),
]
def run():
    try:
        r = subprocess.run(['mvn','-q','-B','clean','test'], capture_output=True, text=True, timeout=240)
        return r.returncode
    except subprocess.TimeoutExpired:
        return 'timeout'
killed = survived = 0
for i,(f,old,new) in enumerate(MUTANTS):
    path = M/f
    src = path.read_text()
    if src.count(old) != 1:
        print(f'M{i:02d} PATTERN-MISS ({src.count(old)}) {f}: {old[:50]!r}'); continue
    try:
        path.write_text(src.replace(old,new))
        rc = run()
    finally:
        path.write_text(src)
    if rc == 0: survived += 1; print(f'M{i:02d} SURVIVED {f}: {old[:70]!r}')
    else: killed += 1; print(f'M{i:02d} killed ({rc})')
    sys.stdout.flush()
print('killed', killed, 'survived', survived)
