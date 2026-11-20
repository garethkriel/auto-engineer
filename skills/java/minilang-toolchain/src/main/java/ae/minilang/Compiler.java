package ae.minilang;

import ae.minilang.Ast.*;
import ae.minilang.Bytecode.Function;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Compiles a checked program to stack bytecode. Every variable gets its own slot; scopes only rename. */
final class Compiler {
    private final Map<String, Integer> funcIndex = new HashMap<>();
    private final Map<String, Integer> arity = new HashMap<>();

    private List<Long> code;
    private Deque<Map<String, Integer>> scopes;
    private int nLocals;

    private Compiler() { }

    static Bytecode compile(Program p) {
        Compiler c = new Compiler();
        int idx = 1;
        for (Func f : p.funcs()) {
            c.funcIndex.put(f.name(), idx++);
            c.arity.put(f.name(), f.params().size());
        }
        List<Function> out = new ArrayList<>();
        out.add(c.function("main", List.of(), p.main()));
        for (Func f : p.funcs()) {
            out.add(c.function(f.name(), f.params(), f.body()));
        }
        return new Bytecode(List.copyOf(out));
    }

    private Function function(String name, List<String> params, List<Stmt> body) {
        code = new ArrayList<>();
        scopes = new ArrayDeque<>();
        nLocals = 0;
        Map<String, Integer> top = new HashMap<>();
        for (String prm : params) {
            top.put(prm, nLocals++);
        }
        scopes.push(top);
        stmts(body);
        emit(Op.PUSH, 0);
        emit(Op.RET);
        long[] arr = code.stream().mapToLong(Long::longValue).toArray();
        return new Function(name, params.size(), nLocals, arr);
    }

    private void emit(Op op, long... imm) {
        code.add((long) op.ordinal());
        for (long v : imm) {
            code.add(v);
        }
    }

    /** Emits a jump with a placeholder target and returns the index of that operand. */
    private int jump(Op op) {
        emit(op, -1);
        return code.size() - 1;
    }

    private void patchHere(int operandIndex) {
        code.set(operandIndex, (long) code.size());
    }

    private int slot(String name) {
        for (Map<String, Integer> s : scopes) {
            Integer i = s.get(name);
            if (i != null) {
                return i;
            }
        }
        throw new IllegalStateException("unchecked variable " + name);
    }

    private void scoped(List<Stmt> body) {
        scopes.push(new HashMap<>());
        stmts(body);
        scopes.pop();
    }

    private void stmts(List<Stmt> body) {
        for (Stmt s : body) {
            stmt(s);
        }
    }

    private void stmt(Stmt s) {
        switch (s) {
            case Let l -> {
                expr(l.init());
                int slot = nLocals++;
                scopes.peek().put(l.name(), slot);
                emit(Op.STORE, slot);
            }
            case Assign a -> {
                expr(a.value());
                emit(Op.STORE, slot(a.name()));
            }
            case If i -> {
                expr(i.cond());
                int toElse = jump(Op.JZ);
                scoped(i.thenBody());
                if (i.elseBody().isEmpty()) {
                    patchHere(toElse);
                } else {
                    int toEnd = jump(Op.JMP);
                    patchHere(toElse);
                    scoped(i.elseBody());
                    patchHere(toEnd);
                }
            }
            case While w -> {
                int top = code.size();
                expr(w.cond());
                int toEnd = jump(Op.JZ);
                scoped(w.body());
                emit(Op.JMP, top);
                patchHere(toEnd);
            }
            case Return r -> {
                if (r.value() == null) {
                    emit(Op.PUSH, 0);
                } else {
                    expr(r.value());
                }
                emit(Op.RET);
            }
            case Print p -> {
                expr(p.value());
                emit(Op.PRINT);
            }
            case ExprStmt e -> {
                expr(e.expr());
                emit(Op.POP);
            }
            case Block b -> scoped(b.body());
        }
    }

    private void expr(Expr e) {
        switch (e) {
            case Num n -> emit(Op.PUSH, n.value());
            case Var v -> emit(Op.LOAD, slot(v.name()));
            case Unary u -> {
                expr(u.operand());
                emit(u.op() == UnOp.NEG ? Op.NEG : Op.NOT);
            }
            case Binary b -> binary(b);
            case Call c -> {
                c.args().forEach(this::expr);
                emit(Op.CALL, funcIndex.get(c.name()), c.args().size());
            }
        }
    }

    private void binary(Binary b) {
        switch (b.op()) {
            case AND -> {
                expr(b.left());
                int toFalse = jump(Op.JZ);
                expr(b.right());
                emit(Op.PUSH, 0);
                emit(Op.NE);
                int toEnd = jump(Op.JMP);
                patchHere(toFalse);
                emit(Op.PUSH, 0);
                patchHere(toEnd);
            }
            case OR -> {
                expr(b.left());
                int toRight = jump(Op.JZ);
                emit(Op.PUSH, 1);
                int toEnd = jump(Op.JMP);
                patchHere(toRight);
                expr(b.right());
                emit(Op.PUSH, 0);
                emit(Op.NE);
                patchHere(toEnd);
            }
            default -> {
                expr(b.left());
                expr(b.right());
                emit(Op.of(b.op()));
            }
        }
    }
}
